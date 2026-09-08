"""The build-and-deploy pipeline against a real cluster.

This is the tier that guards the Kubernetes layer — the ~5.7k lines that carry
almost no unit tests today because they need a live API server. Everything here
is expressed in terms of the HTTP API, so it holds a reimplementation to the same
observable behaviour without caring how the workloads get created.
"""

from __future__ import annotations

import json
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
# A small public image for the image-publish path, split into the name the build
# config stores and the tag a publish names. Override for an offline cluster.
PUBLIC_IMAGE = os.environ.get("OOPS_TEST_IMAGE", "nginx")
PUBLIC_IMAGE_TAG = os.environ.get("OOPS_TEST_IMAGE_TAG", "alpine")
DEPLOY_TIMEOUT = int(os.environ.get("OOPS_TEST_DEPLOY_TIMEOUT", "900"))


def build_log_tail(client, pipeline: dict, lines: int = 30) -> str:
    """The tail of every build step's log, for a failure message.

    A failed build stores only "look at the pipeline log", and by the time
    anyone reads CI the cluster has been torn down — so the reason has to
    travel with the failure or it is gone. Best effort throughout: a build that
    failed before its pod existed has no log to fetch, and that must never
    replace the real failure with an error raised in here.
    """
    base = (f"/api/namespaces/{pipeline['namespace']}/applications/"
            f"{pipeline['applicationName']}/pipelines/{pipeline['id']}")
    try:
        steps = next((json.loads(event.data)
                      for event in client.sse(f"{base}/steps/watch", timeout=60)
                      if event.event == "steps"), [])
    except Exception as error:
        return f"(could not list the build steps: {error})"

    sections = []
    for step in steps:
        try:
            tail = [line["text"]
                    for event in client.sse(f"{base}/log?container={step}", timeout=60)
                    if event.event == "log"
                    for line in json.loads(event.data)["lines"]][-lines:]
        except Exception as error:
            tail = [f"(could not read this step's log: {error})"]
        if tail:
            sections.append(f"--- {step} ---\n" + "\n".join(tail))
    return "\n".join(sections) or "(the build produced no log at all)"


def require_successful_deploy(client, pipeline: dict, needed_for: str) -> dict:
    """The setup deploy a scenario does for itself has to succeed, or it fails.

    Skipping instead takes the whole scenario out of the run without a sound,
    and the endpoints only it reaches are then reported by the coverage test as
    covered by no scenario at all — a red build naming the wrong file, twenty
    minutes after the deploy that actually broke.
    """
    if pipeline["status"] == "SUCCEEDED":
        return pipeline
    pytest.fail(f"the deploy this scenario needs for {needed_for} ended as "
                f"{pipeline['status']}: {pipeline.get('message') or 'no message'}\n"
                f"{build_log_tail(client, pipeline)}")


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


def configure_for_image(client, namespace, application, environment):
    """Like `configure_for_build`, for an application that deploys a prebuilt
    image: the build config carries only the image name, the tag comes per deploy."""
    configure_for_build(client, namespace, application, environment)
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "IMAGE",
        "repository": PUBLIC_IMAGE,
    })


def image_strategy(tag: str = PUBLIC_IMAGE_TAG) -> dict:
    return {"type": "IMAGE", "tag": tag}


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


def put_inline_dockerfile(client, namespace, application, environment, content):
    """Full-rewrite of the build config with an inline USER Dockerfile.

    The PUT replaces the whole config, so everything `configure_for_build` set
    has to travel again alongside the Dockerfile.
    """
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": SOURCE_REPOSITORY,
        "dockerFileConfig": {"type": "USER", "content": content},
        "environmentConfigs": [{"environment": environment, "buildCommand": ""}],
    })


def wait_for_terminal(client, namespace, application, pipeline_id, description):
    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    return wait_until(finished, timeout=DEPLOY_TIMEOUT, description=description)


def test_deploy_recovers_from_a_crash_looping_previous_release(
        client, namespace, application, environment):
    """A release whose pod never becomes ready must not block the next one.

    Under the default OrderedReady policy the StatefulSet controller refuses to
    replace a pod that is not running-and-ready, so the crash-looping pod a
    failed release leaves behind would pin every later template change forever —
    the manual workaround used to be scaling to zero and back. The deploy path
    deletes such pods right after applying the new template; this test locks
    that behaviour end to end against a real controller.
    """
    configure_for_build(client, namespace, application, environment)

    # A container whose only process exits immediately: CrashLoopBackOff within
    # seconds of the rollout starting, and a pod that can never become ready.
    put_inline_dockerfile(client, namespace, application, environment,
                          'FROM alpine:3.20\nCMD ["sh", "-c", "exit 1"]')
    bad = client.deploy(namespace, application, environment,
                        strategy=git_strategy())
    pipeline = wait_for_terminal(client, namespace, application, bad,
                                 "the crash-looping release to fail")
    assert pipeline["status"] == "ERROR", (
        f"the broken release should fail its rollout, got {pipeline['status']}")

    # Same base image, a process that stays up. Without the deploy-time unstick
    # this pipeline hangs against the crash-looping pod until the rollout timeout.
    put_inline_dockerfile(client, namespace, application, environment,
                          'FROM alpine:3.20\n'
                          'CMD ["sh", "-c", "while true; do sleep 30; done"]')
    good = client.deploy(namespace, application, environment,
                         strategy=git_strategy())
    pipeline = wait_for_terminal(client, namespace, application, good,
                                 "the follow-up release to converge")
    assert pipeline["status"] == "SUCCEEDED", (
        f"a release after a crash-looping one should still converge, got "
        f"{pipeline['status']} ({pipeline.get('message')})")

    # The original symptom was the pod silently keeping the old image while the
    # StatefulSet claimed the new one. Artifact tags are pipeline ids, so the
    # replacement is checkable directly.
    pods = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/status"
        f"?environment={environment}").data
    images = [container["image"]
              for pod in pods for container in pod.get("containers", [])]
    assert any(image.endswith(f":{good}") for image in images), (
        f"no pod is running the new artifact (tag :{good}); images: {images}")


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


def test_image_publish_deploys_without_a_build(client, namespace, application,
                                               environment):
    """An image publish has its artifact before it starts: no build job, so the
    pipeline never passes through RUNNING, and what reaches the cluster is the
    configured image name joined with the tag the publish named."""
    configure_for_image(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=image_strategy())
    seen: list[str] = []

    def finished():
        pipeline = poll_pipeline(client, namespace, application, pipeline_id, seen)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description=f"image pipeline {pipeline_id} to reach a terminal state")

    assert pipeline["status"] == "SUCCEEDED", (
        f"image publish ended as {pipeline['status']} after passing through {seen}: "
        f"{pipeline.get('message')}")
    assert "RUNNING" not in seen, f"an image publish must not build, but passed through {seen}"
    assert pipeline["artifact"] == f"{PUBLIC_IMAGE}:{PUBLIC_IMAGE_TAG}"
    assert pipeline["publishType"] == "IMAGE"
    assert pipeline["publishConfig"] == {
        "type": "IMAGE", "repository": PUBLIC_IMAGE, "tag": PUBLIC_IMAGE_TAG}
    assert pipeline["triggerType"] == "RELEASE"

    status = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/status"
        f"?environment={environment}").data
    images = {container["image"] for pod in status for container in pod.get("containers", [])}
    # The runtime reports the reference it resolved, so a bare `nginx:alpine` comes back as
    # `docker.io/library/nginx:alpine`; only the trailing name:tag is ours to check.
    published = f"{PUBLIC_IMAGE}:{PUBLIC_IMAGE_TAG}"
    assert any(image == published or image.endswith("/" + published) for image in images), (
        f"the running pods should carry the published image {published}; saw {images}")


def test_image_publish_in_manual_mode_waits_for_the_deploy_call(
        client, namespace, application, environment):
    configure_for_image(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                deploy_mode="MANUAL", strategy=image_strategy())

    pipeline = client.get_pipeline(namespace, application, pipeline_id)
    assert pipeline["status"] == "BUILD_SUCCEEDED", (
        f"a MANUAL image publish should park at BUILD_SUCCEEDED at once, got {pipeline['status']}")

    client.put(f"/api/namespaces/{namespace}/applications/{application}"
               f"/pipelines/{pipeline_id}/deploy")
    pipeline = wait_for_terminal(client, namespace, application, pipeline_id,
                                 "the manually deployed image publish to finish")
    assert pipeline["status"] == "SUCCEEDED", pipeline.get("message")


def test_image_publish_requires_a_tag_and_matching_strategy(
        client, namespace, application, environment):
    configure_for_image(client, namespace, application, environment)
    for strategy in (image_strategy(""), image_strategy("other/image:1.0"),
                     image_strategy("1.0@sha256:abc"), git_strategy()):
        response = client.post(
            f"/api/namespaces/{namespace}/applications/{application}/deployments",
            {"environment": environment, "deployMode": "IMMEDIATE", "strategy": strategy},
            expect_success=False)
        assert response.success is False, f"{strategy} should have been rejected"


def test_pipeline_listing_accepts_the_all_scope(client, namespace, application,
                                                environment):
    """Namespace `all` spans namespaces and a blank environment means no filter."""
    result = client.get("/api/namespaces/all/applications/"
                        f"{application}/pipelines?page=1&size=10&environment=")
    assert result.success is True
    assert "data" in result.data
