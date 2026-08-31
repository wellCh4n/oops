"""Deleting an application — the cascade across the database and the cluster.

Deletion is where a rewrite leaks: every config table has to go, the workload has
to be removed from every environment the application was deployed to, and the
authorisation rule (owner or admin only) has to survive. None of it is covered by
a unit test today because it spans JPA and the Kubernetes client at once.

The cluster-side assertions are deliberately specific about *which* objects
disappear, because today not all of them do — see
test_service_survives_application_deletion.
"""

from __future__ import annotations

import pytest

from oops_client import wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy)


def deploy_and_wait(client, namespace, application, environment) -> str:
    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the deploy to finish before deleting it")
    if pipeline["status"] != "SUCCEEDED":
        pytest.skip(f"deploy ended as {pipeline['status']}, nothing to delete")
    return pipeline_id


# -- database side, no cluster needed ---------------------------------------

def test_delete_removes_the_application_and_its_config(client, namespace,
                                                       application_factory):
    """Every per-application config row goes with the application.

    Read back through the API rather than the database, so the assertion holds
    for any storage engine a reimplementation picks.
    """
    application = application_factory()
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": "https://example.invalid/repo.git",
    })

    assert client.get_application(namespace, application).data is not None
    client.delete_application(namespace, application)

    assert client.get_application(namespace, application,
                                  expect_success=False).data is None, (
        "the application is still readable after deletion")

    build = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/build/config",
        expect_success=False)
    assert not build.data, (
        "the build config outlived its application — deleteAggregate is not "
        "clearing every per-application table")


def test_deleting_twice_reports_failure_inside_the_envelope(client, namespace,
                                                            application_factory):
    """A second delete fails, but as a result envelope rather than an HTTP error.

    Note this differs from reading a missing application, which answers
    success=true with null data. Delete raises where read does not, and both
    shapes are part of the contract a reimplementation has to reproduce.
    """
    application = application_factory()
    client.delete_application(namespace, application)

    second = client.delete_application(namespace, application,
                                       expect_success=False)
    assert second.status == 200, (
        f"a repeated delete answered HTTP {second.status}; it should stay "
        f"inside the result envelope")
    assert second.success is False
    assert second.message, "a repeated delete should say what went wrong"


def test_deleted_application_leaves_the_namespace_listing(client, namespace,
                                                          application_factory):
    application = application_factory()
    client.delete_application(namespace, application)

    page = client.get(
        f"/api/namespaces/{namespace}/applications?page=1&size=200").data
    assert application not in [item["name"] for item in page["data"]]


def test_a_non_owner_cannot_delete(client, endpoint, namespace,
                                   application_factory, secondary_user):
    """Delete stays with the owner and admins.

    The application is created by the admin session, so the second account is a
    non-owner by construction.
    """
    from oops_client import OopsClient

    application = application_factory()

    intruder = OopsClient(endpoint)
    intruder.login(secondary_user["username"], secondary_user["password"])

    result = intruder.delete_application(namespace, application,
                                         expect_success=False)
    assert result.success is False, (
        "a user who neither owns the application nor is an admin was allowed "
        "to delete it")

    # And the application is genuinely still there, not merely reported as kept.
    assert client.get_application(namespace, application).data is not None


# -- cluster side ------------------------------------------------------------

@pytest.mark.cluster
def test_delete_removes_the_statefulset(client, namespace, application,
                                        environment, kubectl):
    """The workload goes when the application does."""
    deploy_and_wait(client, namespace, application, environment)

    assert kubectl.exists("statefulset", application, namespace), (
        "no StatefulSet to delete — the deploy did not create one")

    client.delete_application(namespace, application)

    gone = wait_until(
        lambda: not kubectl.exists("statefulset", application, namespace),
        timeout=120, interval=3,
        description="the StatefulSet to disappear")
    assert gone


@pytest.mark.cluster
def test_the_service_goes_with_the_statefulset(client, namespace, application,
                                               environment, kubectl):
    """The Service is garbage-collected rather than deleted explicitly.

    `deleteWorkload` removes only the StatefulSet, which looks like a leak until
    you notice `ServiceProcessor` stamps an owner reference on the Service.
    Kubernetes then collects it when its owner goes, so the cleanup is real but
    it happens in the cluster rather than in OOPS.

    Worth a test precisely because the mechanism is invisible in the OOPS code: a
    reimplementation that creates the Service without the owner reference would
    still deploy correctly and would leak one Service per deleted application,
    with nothing in the delete path to point at.
    """
    deploy_and_wait(client, namespace, application, environment)
    if not kubectl.exists("service", application, namespace):
        pytest.skip("no Service was created, nothing to say about its removal")

    client.delete_application(namespace, application)
    wait_until(lambda: not kubectl.exists("statefulset", application, namespace),
               timeout=120, interval=3,
               description="the StatefulSet to disappear")

    gone = wait_until(
        lambda: not kubectl.exists("service", application, namespace),
        timeout=120, interval=3,
        description="the Service to be garbage-collected with its owner")
    assert gone, (
        "the Service outlived the StatefulSet; its owner reference is missing, "
        "so every deleted application now leaves one behind")
