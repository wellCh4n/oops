"""Day-two operations: search, live status, restarting a pod, moving a namespace.

What is left once an application is running. Grouped together because they share
nothing except being the things an operator reaches for after the deploy, and
each is a single endpoint that would otherwise sit untested.
"""

from __future__ import annotations

import pytest

from oops_client import wait_until


# -- search, no cluster needed ----------------------------------------------

def test_applications_can_be_searched_by_name_and_namespace(client, namespace,
                                                            application):
    """The index endpoints back the command palette.

    A POST with a body rather than query parameters, and a bare list rather than
    a page, so it does not follow the pagination convention the rest of the API
    uses.
    """
    matches = client.post("/api/index/applications",
                          {"name": application, "namespace": namespace}).data

    assert isinstance(matches, list), (
        "application search returned a page envelope; every other caller of "
        "this endpoint expects a bare list")
    assert application in [item["name"] for item in matches], (
        f"{application} was not found by a search for its own exact name")


def test_searching_for_nothing_in_particular_is_not_an_error(client, namespace):
    """An empty query is what the palette sends before anything is typed."""
    result = client.post("/api/index/applications",
                         {"name": "", "namespace": namespace},
                         expect_success=False)
    assert result.status == 200, (
        f"an empty application search answered HTTP {result.status}")

    result = client.post("/api/index/pipelines",
                         {"namespace": namespace, "applicationName": ""},
                         expect_success=False)
    assert result.status == 200, (
        f"an empty pipeline search answered HTTP {result.status}")


def test_pipelines_can_be_searched_for_an_application(client, namespace,
                                                      application):
    matches = client.post("/api/index/pipelines",
                          {"namespace": namespace,
                           "applicationName": application}).data
    assert isinstance(matches, list), (
        "pipeline search returned something other than a list")


# -- live status and pod operations ------------------------------------------

@pytest.mark.cluster
def test_the_status_stream_opens_and_sends_something(client, namespace,
                                                     environment, application):
    """`/status/watch` is Server-Sent Events, not JSON.

    An endpoint that answers with a stream rather than the result envelope is
    exactly the sort of thing a reimplementation turns back into a plain GET
    without noticing. The client refuses anything but `text/event-stream`, so
    reaching an event at all is the proof; one is enough, since the status
    stream follows the workload for as long as the viewer stays.
    """
    stream = client.sse(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/status/watch?environment={environment}", timeout=30)
    try:
        first = next(stream, None)
    finally:
        stream.close()
    assert first is not None, "the status stream sent nothing"


@pytest.mark.cluster
def test_a_pod_can_be_restarted(client, namespace, environment, application,
                                kubectl):
    """Restart deletes the pod and lets the StatefulSet replace it.

    A StatefulSet always rebuilds a pod under the same name, so the name proves
    nothing here. The UID does: a new one means a genuinely new pod rather than
    a call that quietly did nothing.
    """
    from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES,
                             configure_for_build, git_strategy)
    from test_streams import first_running_pod

    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the deploy to finish")
    if pipeline["status"] != "SUCCEEDED":
        pytest.skip(f"deploy ended as {pipeline['status']}, nothing to restart")

    original = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod")
    original_uid = (kubectl.get_json("pod", original, namespace) or {}) \
        .get("metadata", {}).get("uid")
    assert original_uid, f"could not read the UID of pod {original}"

    client.put(f"/api/namespaces/{namespace}/applications/{application}"
               f"/pods/{original}/restart?environment={environment}")

    def replaced():
        current = kubectl.get_json("pod", original, namespace) or {}
        uid = current.get("metadata", {}).get("uid")
        phase = current.get("status", {}).get("phase")
        return uid if uid and uid != original_uid and phase == "Running" else None

    new_uid = wait_until(replaced, timeout=300, interval=5,
                         description="the pod to be replaced")
    assert new_uid != original_uid, (
        f"pod {original} still has UID {original_uid} after a restart, so it "
        f"was never actually recreated")


@pytest.mark.cluster
def test_an_application_can_be_moved_to_another_namespace(
        client, namespace, environment, application_factory, kubectl):
    """Migration moves the database rows and the live workload together.

    Half a migration is worse than none: the application would answer on the new
    namespace while its StatefulSet kept running under the old one.
    """
    from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES,
                             configure_for_build, git_strategy)

    application = application_factory(prefix="migrate")
    target = f"{namespace}-target"

    # The target has to exist as a namespace row: migration refuses to move an
    # application into a namespace nobody has registered, which is what stops a
    # typo from stranding it somewhere invisible.
    client.post("/api/namespaces", {"name": target}, expect_success=False)

    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the deploy to finish")
    if pipeline["status"] != "SUCCEEDED":
        pytest.skip(f"deploy ended as {pipeline['status']}, nothing to migrate")

    result = client.post(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/namespace-migration", {"targetNamespace": target}).data
    assert result is not None, "migration returned no result to report on"

    try:
        moved = client.get_application(target, application, expect_success=False)
        assert moved.data is not None, (
            f"the application is not readable under {target} after migrating "
            f"to it")

        gone = client.get_application(namespace, application,
                                      expect_success=False)
        assert gone.data is None, (
            f"the application is still readable under its old namespace "
            f"{namespace}, so it now exists in two places")

        # The workload has to follow, or the old namespace keeps serving it.
        left_behind = wait_until(
            lambda: not kubectl.exists("statefulset", application, namespace),
            timeout=180, interval=3,
            description="the old StatefulSet to be removed")
        assert left_behind, (
            f"the StatefulSet is still running in {namespace} after the "
            f"migration; the workload did not follow the database rows")
    finally:
        client.delete_application(target, application, expect_success=False)
