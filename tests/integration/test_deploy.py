"""The build-and-deploy pipeline against a real cluster.

This is the tier that guards the Kubernetes layer — the ~5.7k lines that carry
almost no unit tests today because they need a live API server. Everything here
is expressed in terms of the HTTP API, so it holds a reimplementation to the same
observable behaviour without caring how the workloads get created.
"""

from __future__ import annotations

import os

import pytest

from oops_client import wait_until

pytestmark = pytest.mark.cluster

TERMINAL_STATUSES = {"SUCCEEDED", "ERROR", "STOPPED"}
ACTIVE_STATUSES = {"RUNNING", "DEPLOYING", "ROLLING_OUT"}

# The order the state machine is allowed to advance through. A pipeline may skip
# states but must never move backwards.
STATUS_ORDER = ["INITIALIZED", "RUNNING", "BUILD_SUCCEEDED", "DEPLOYING",
                "ROLLING_OUT", "SUCCEEDED"]

# A small public repository with a Dockerfile at its root. Override for an
# offline or air-gapped cluster.
SOURCE_REPOSITORY = os.environ.get(
    "OOPS_TEST_REPOSITORY", "https://github.com/docker/welcome-to-docker.git")
SOURCE_BRANCH = os.environ.get("OOPS_TEST_BRANCH", "main")
DEPLOY_TIMEOUT = int(os.environ.get("OOPS_TEST_DEPLOY_TIMEOUT", "900"))


def configure_for_build(client, namespace, application, environment):
    """Give an application the minimum it needs to build and serve.

    The branch is not part of the build config — it travels per deploy, inside
    the polymorphic `strategy` object.
    """
    # Bind the application to the environment first. A deploy works without this
    # row, but deletion iterates it to decide which clusters to clean up — so an
    # unbound application deletes from the database and leaves its StatefulSet
    # running. Every test that later asserts on cleanup depends on this call.
    client.put_environment_bindings(namespace, application, [
        {"namespace": namespace, "applicationName": application,
         "environment": environment},
    ])
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": SOURCE_REPOSITORY,
        "environmentConfigs": [{"environment": environment, "buildCommand": ""}],
    })
    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 80,
        "internalPorts": [],
        "environmentConfigs": [{"environment": environment, "hosts": []}],
    })
    # Without an explicit replica count the StatefulSet is created with zero, so
    # the rollout "succeeds" having started nothing and there is no pod for the
    # log and terminal tests to attach to.
    #
    # The two resource fields are not symmetric: cpu* is a full Kubernetes
    # quantity string, memory* is a bare number of MiB because the gateway
    # appends "Mi" itself. Passing "256Mi" here yields "256MiMi" and the API
    # server rejects the whole StatefulSet with "unable to parse quantity's
    # suffix".
    client.put_runtime_spec(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "environmentConfigs": [{
            "environment": environment,
            "replicas": 1,
            "cpuRequest": "10m",
            "cpuLimit": "500m",
            "memoryRequest": "32",
            "memoryLimit": "256",
        }],
    })


def git_strategy(branch: str = SOURCE_BRANCH) -> dict:
    return {"type": "GIT", "branch": branch}


def poll_pipeline(client, namespace, application, pipeline_id, seen: list) -> dict:
    """Fetch the pipeline and record every distinct status it passes through."""
    pipeline = client.get_pipeline(namespace, application, pipeline_id)
    status = pipeline["status"]
    if not seen or seen[-1] != status:
        seen.append(status)
    return pipeline


def test_deploy_reaches_succeeded(client, namespace, application, environment):
    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())
    assert pipeline_id, "deploy did not return a pipeline id"

    seen: list[str] = []

    def finished():
        pipeline = poll_pipeline(client, namespace, application, pipeline_id, seen)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description=f"pipeline {pipeline_id} to reach a terminal state")

    assert pipeline["status"] == "SUCCEEDED", (
        f"pipeline ended as {pipeline['status']} after passing through {seen}")

    # The state machine may skip states but must never regress.
    indexes = [STATUS_ORDER.index(status) for status in seen if status in STATUS_ORDER]
    assert indexes == sorted(indexes), f"pipeline status went backwards: {seen}"


def test_in_flight_pipeline_blocks_a_second_deploy(client, namespace, application,
                                                   environment):
    """The duplicate-deploy guard: one active pipeline per application."""
    configure_for_build(client, namespace, application, environment)
    first = client.deploy(namespace, application, environment,
                          strategy=git_strategy())

    def became_active():
        pipeline = client.get_pipeline(namespace, application, first)
        return pipeline["status"] in ACTIVE_STATUSES or pipeline["status"] in TERMINAL_STATUSES

    wait_until(became_active, timeout=120, interval=2,
               description="the first pipeline to start")

    status = client.get_pipeline(namespace, application, first)["status"]
    if status in TERMINAL_STATUSES:
        pytest.skip(f"first pipeline finished too quickly to race against ({status})")

    second = client.post(
        f"/api/namespaces/{namespace}/applications/{application}/deployments",
        {"environment": environment, "deployMode": "IMMEDIATE",
         "strategy": git_strategy()},
        expect_success=False,
    )
    assert second.success is False, (
        "a second deploy was accepted while a pipeline was still in flight")
    assert second.message, "the duplicate-deploy guard should explain itself"


def test_manual_mode_stops_at_build_succeeded(client, namespace, application,
                                              environment):
    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                deploy_mode="MANUAL", strategy=git_strategy())

    def built():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        status = pipeline["status"]
        return pipeline if status in TERMINAL_STATUSES | {"BUILD_SUCCEEDED"} else None

    pipeline = wait_until(built, timeout=DEPLOY_TIMEOUT,
                          description="the build to finish in MANUAL mode")

    assert pipeline["status"] == "BUILD_SUCCEEDED", (
        f"MANUAL mode should hold at BUILD_SUCCEEDED, got {pipeline['status']}")


def test_pipeline_listing_accepts_the_all_scope(client, namespace, application,
                                                environment):
    """Namespace `all` spans namespaces and a blank environment means no filter."""
    result = client.get("/api/namespaces/all/applications/"
                        f"{application}/pipelines?page=1&size=10&environment=")
    assert result.success is True
    assert "data" in result.data
