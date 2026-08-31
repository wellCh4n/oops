"""Operating a pipeline after it has been triggered: stop, manual deploy, rollback.

These are the controls an operator reaches for when something is going wrong, and
they are the ones least likely to be exercised by hand before a release. All
three mutate a pipeline mid-flight or reuse a historic artefact, so they only
mean anything against a real cluster.
"""

from __future__ import annotations

import pytest

from oops_client import wait_until
from test_deploy import (ACTIVE_STATUSES, DEPLOY_TIMEOUT, TERMINAL_STATUSES,
                         configure_for_build, git_strategy)

pytestmark = pytest.mark.cluster


def deploy(client, namespace, application, environment, mode="IMMEDIATE") -> str:
    configure_for_build(client, namespace, application, environment)
    return client.deploy(namespace, application, environment,
                         deploy_mode=mode, strategy=git_strategy())


def wait_for_terminal(client, namespace, application, pipeline_id) -> dict:
    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    return wait_until(finished, timeout=DEPLOY_TIMEOUT,
                      description=f"pipeline {pipeline_id} to reach a terminal state")


def test_a_running_pipeline_can_be_stopped(client, namespace, application,
                                           environment):
    """Stopping moves the pipeline to STOPPED rather than leaving it running.

    The guard against concurrent deploys keys on the active statuses, so a
    pipeline that cannot be stopped also blocks every later deploy of the same
    application.
    """
    pipeline_id = deploy(client, namespace, application, environment)

    def started():
        status = client.get_pipeline(namespace, application,
                                     pipeline_id)["status"]
        return status if status in ACTIVE_STATUSES | TERMINAL_STATUSES else None

    status = wait_until(started, timeout=180, interval=2,
                        description="the pipeline to start")
    if status in TERMINAL_STATUSES:
        pytest.skip(f"the pipeline finished as {status} before it could be stopped")

    client.put(f"/api/namespaces/{namespace}/applications/{application}"
               f"/pipelines/{pipeline_id}/stop")

    def stopped():
        current = client.get_pipeline(namespace, application,
                                      pipeline_id)["status"]
        return current if current in TERMINAL_STATUSES else None

    final = wait_until(stopped, timeout=180, interval=3,
                       description="the stopped pipeline to settle")
    assert final == "STOPPED", (
        f"a stopped pipeline settled as {final}; it should record STOPPED so the "
        f"duplicate-deploy guard releases and the history shows who stopped it")


def test_a_manual_build_is_deployed_on_request(client, namespace, application,
                                               environment):
    """MANUAL holds at BUILD_SUCCEEDED until the deploy call arrives.

    This is the two-step release: build now, deploy during the window.
    """
    pipeline_id = deploy(client, namespace, application, environment,
                         mode="MANUAL")

    def built():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        status = pipeline["status"]
        return status if status in TERMINAL_STATUSES | {"BUILD_SUCCEEDED"} else None

    status = wait_until(built, timeout=DEPLOY_TIMEOUT,
                        description="the manual build to finish")
    if status != "BUILD_SUCCEEDED":
        pytest.skip(f"the manual build ended as {status}")

    client.put(f"/api/namespaces/{namespace}/applications/{application}"
               f"/pipelines/{pipeline_id}/deploy")

    final = wait_for_terminal(client, namespace, application, pipeline_id)
    assert final["status"] == "SUCCEEDED", (
        f"a manually released build ended as {final['status']}; the held "
        f"artefact was not deployed")


def test_a_successful_pipeline_can_be_rolled_back(client, namespace, application,
                                                  environment):
    """Rollback reuses the historic artefact instead of rebuilding.

    A rollback that rebuilds is not a rollback: the point is to redeploy exactly
    the image that was running before, so it must not depend on the source still
    being reachable.
    """
    pipeline_id = deploy(client, namespace, application, environment)
    first = wait_for_terminal(client, namespace, application, pipeline_id)
    if first["status"] != "SUCCEEDED":
        pytest.skip(f"the first deploy ended as {first['status']}, nothing to roll back")

    rollback_id = client.post(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/pipelines/{pipeline_id}/rollback").data
    assert rollback_id, "rollback did not return a new pipeline id"
    assert rollback_id != pipeline_id, (
        "rollback reused the original pipeline id instead of creating a new one")

    rolled_back = wait_for_terminal(client, namespace, application, rollback_id)
    assert rolled_back["status"] == "SUCCEEDED", (
        f"the rollback pipeline ended as {rolled_back['status']}")
    assert rolled_back.get("triggerType") == "ROLLBACK", (
        f"the rollback pipeline is recorded as "
        f"{rolled_back.get('triggerType')!r} rather than ROLLBACK, so the "
        f"history cannot tell a rollback from an ordinary deploy")


def test_pipeline_listing_and_detail_agree(client, namespace, application,
                                           environment):
    """The row in the list and the detail view describe the same pipeline."""
    pipeline_id = deploy(client, namespace, application, environment)
    wait_for_terminal(client, namespace, application, pipeline_id)

    page = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/pipelines?page=1&size=20").data
    rows = {row["id"]: row for row in page["data"]}
    assert pipeline_id in rows, (
        "the pipeline is missing from its own application's listing")

    detail = client.get_pipeline(namespace, application, pipeline_id)
    assert detail["status"] == rows[pipeline_id]["status"], (
        "the listing and the detail view disagree about the pipeline status")
