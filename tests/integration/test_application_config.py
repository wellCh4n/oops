"""Configuring an application: the round trip through every config surface.

An application carries five independent pieces of configuration — profile, build,
service, runtime spec, expert — each stored as a JSON blob and each with a
per-environment variant. They are read and written through separate endpoints,
and the shapes are not symmetric, which is exactly where a reimplementation drifts.

The scenario is the one a user actually performs: create an application, fill in
each section, read it all back, and check that what came out is what went in.
"""

from __future__ import annotations

import pytest


def test_the_profile_can_be_updated(client, namespace, application):
    client.put(f"/api/namespaces/{namespace}/applications/{application}", {
        "name": application,
        "namespace": namespace,
        "description": "updated by the integration suite",
        "icon": "🚀",
    })

    profile = client.get_application(namespace, application).data
    assert profile["description"] == "updated by the integration suite"
    assert profile["icon"] == "🚀", "the icon did not survive the round trip"


def test_the_icon_must_be_non_ascii_and_short(client, namespace, application):
    """The rule is at most eight code points with no ASCII, not "one emoji".

    The rejection message says "must be a single emoji", but a single emoji is
    frequently several code points — a flag, a skin tone, a ZWJ family — so the
    limit is eight and anything below U+0080 is refused. Two rocket emoji are
    therefore accepted, which the message would not lead you to expect.
    """
    accepted = ("🚀", "🇬🇧", "👩‍💻", "🚀🚀", "")
    refused = ("rocket", "ab", "a🚀", "🚀🚀🚀🚀🚀🚀🚀🚀🚀")

    for candidate in accepted:
        result = client.put(
            f"/api/namespaces/{namespace}/applications/{application}",
            {"name": application, "namespace": namespace, "icon": candidate},
            expect_success=False)
        assert result.success is True, (
            f"{candidate!r} was refused as an application icon: {result.message}")

    for candidate in refused:
        result = client.put(
            f"/api/namespaces/{namespace}/applications/{application}",
            {"name": application, "namespace": namespace, "icon": candidate},
            expect_success=False)
        assert result.success is False, (
            f"{candidate!r} was accepted as an application icon")


def test_build_config_round_trips(client, namespace, application, environment):
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": "https://example.invalid/team/service.git",
        "buildImage": "node:20-slim",
        "dockerFileConfig": {"content": "FROM alpine:3.20\n"},
        "environmentConfigs": [
            {"environment": environment, "buildCommand": "npm run build"},
        ],
    })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/build/config").data

    assert stored["sourceType"] == "GIT"
    assert stored["repository"] == "https://example.invalid/team/service.git"
    assert stored["buildImage"] == "node:20-slim"

    per_environment = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/build/configs").data
    commands = {item["environment"]: item["buildCommand"]
                for item in per_environment}
    assert commands.get(environment) == "npm run build", (
        f"the per-environment build command was lost; got {commands}")


def test_per_environment_build_configs_can_be_written_separately(
        client, namespace, application, environment):
    """The per-environment collection has its own endpoint as well as riding
    along inside the build config, and both have to agree."""
    client.put(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/build/configs",
        [{"environment": environment, "buildCommand": "make release"}])

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/build/configs").data
    commands = {item["environment"]: item["buildCommand"] for item in stored}
    assert commands.get(environment) == "make release"


def test_service_config_round_trips(client, namespace, application, environment):
    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 8080,
        "internalPorts": [9090, 9091],
        "environmentConfigs": [
            {"environment": environment, "hosts": []},
        ],
    })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/service").data

    assert stored["port"] == 8080
    assert stored["internalPorts"] == [9090, 9091], (
        f"internal ports did not survive the round trip: {stored['internalPorts']}")


def test_runtime_spec_round_trips(client, namespace, application, environment):
    """Memory is a bare number of MiB while cpu is a full quantity string.

    The gateway appends "Mi" to the memory values when it builds the container
    resources, so sending "256Mi" here produces "256MiMi" and the API server
    rejects the StatefulSet.
    """
    client.put_runtime_spec(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "environmentConfigs": [{
            "environment": environment,
            "replicas": 2,
            "cpuRequest": "100m",
            "cpuLimit": "1",
            "memoryRequest": "128",
            "memoryLimit": "512",
        }],
    })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/runtime-spec").data
    configs = {item["environment"]: item
               for item in stored["environmentConfigs"]}
    assert configs[environment]["replicas"] == 2
    assert configs[environment]["memoryLimit"] == "512", (
        "the memory limit was rewritten in storage; it must round trip as the "
        "bare number the caller sent")

    per_environment = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/runtime-specs").data
    assert any(item["environment"] == environment for item in per_environment)


@pytest.mark.cluster
def test_saving_a_runtime_spec_before_the_first_deploy_is_quiet(
        client, namespace, application, environment, kubectl):
    """Setting resources on an application that has never been deployed.

    This is the ordinary first-time flow — create, size, then deploy — and it
    used to log a Kubernetes 404 with a full API URL, because the save tried to
    scale a StatefulSet that does not exist yet. The spec was stored correctly
    either way, so the only casualty was the log: a routine action that looked
    like a failure, and real cluster problems buried in the same shape of
    message.

    Asserted through the API rather than by reading logs: the save has to
    succeed, the value has to come back, and no workload may be created as a
    side effect.
    """
    assert not kubectl.exists("statefulset", application, namespace), (
        "this application has already been deployed, so it cannot exercise the "
        "before-first-deploy path")

    client.put_runtime_spec(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "environmentConfigs": [{
            "environment": environment,
            "replicas": 2,
            "cpuRequest": "50m",
            "cpuLimit": "200m",
            "memoryRequest": "64",
            "memoryLimit": "128",
        }],
    })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/runtime-spec").data
    configs = {item["environment"]: item
               for item in stored["environmentConfigs"]}
    assert configs[environment]["replicas"] == 2, (
        "the runtime spec was not stored for an undeployed application")

    assert not kubectl.exists("statefulset", application, namespace), (
        "saving a runtime spec created a StatefulSet; sizing an application is "
        "not supposed to deploy it")


def test_per_environment_runtime_specs_can_be_written_separately(
        client, namespace, application, environment):
    client.put(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/runtime-specs",
        [{"environment": environment, "replicas": 3,
          "cpuRequest": "50m", "cpuLimit": "500m",
          "memoryRequest": "64", "memoryLimit": "256"}])

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments/runtime-specs").data
    configs = {item["environment"]: item for item in stored}
    assert configs[environment]["replicas"] == 3


def test_expert_config_round_trips(client, namespace, application, environment):
    """Service account, priority and the scheduled restart cron."""
    client.put(
        f"/api/namespaces/{namespace}/applications/{application}/expert-config",
        {
            "namespace": namespace,
            "applicationName": application,
            "environmentConfigs": [{
                "environment": environment,
                "serviceAccountName": "default",
                "priority": "",
                "scheduledRestartEnabled": True,
                "scheduledRestartCron": "0 3 * * *",
                "nodeNames": [],
            }],
        })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/expert-config").data
    configs = {item["environment"]: item
               for item in stored["environmentConfigs"]}
    assert configs[environment]["scheduledRestartEnabled"] is True
    assert configs[environment]["scheduledRestartCron"] == "0 3 * * *", (
        "the scheduled restart cron did not survive the round trip")


def test_environment_bindings_round_trip(client, namespace, application,
                                         environment):
    """The binding that decides which clusters a deletion cleans up."""
    client.put_environment_bindings(namespace, application, [
        {"namespace": namespace, "applicationName": application,
         "environment": environment},
    ])

    bound = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/environments").data
    assert environment in [item["environment"] for item in bound], (
        "the environment binding was not stored; deletion relies on it to know "
        "which clusters to clean up")


def test_reading_an_unconfigured_application_is_empty_not_an_error(
        client, namespace, application):
    """Every config surface has to answer for an application with nothing set.

    This is where a reimplementation throws instead of returning empty, because
    the blob column is null and nobody checked.
    """
    for suffix in ("/build/config", "/service", "/runtime-spec", "/expert-config",
                   "/environments", "/environments/build/configs",
                   "/environments/runtime-specs", "/last-successful-pipeline"):
        result = client.get(
            f"/api/namespaces/{namespace}/applications/{application}{suffix}",
            expect_success=False)
        assert result.status == 200, (
            f"{suffix} answered HTTP {result.status} for an application with no "
            f"configuration")


def test_host_conflict_check_answers(client, namespace, application):
    """Answers whether a host is already claimed, before the user saves it.

    An unclaimed host comes back as null data rather than as a view saying "no
    conflict", so the UI treats absence as the clear signal.
    """
    free = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/service/host-check?host=integration-unclaimed.example.invalid")
    assert free.success is True
    assert free.data is None, (
        f"an unclaimed host reported a conflict: {free.data}")


def test_active_deployments_lists_the_namespace(client, namespace, application):
    result = client.get(
        f"/api/namespaces/{namespace}/applications/active-deployments")
    assert isinstance(result.data, list), (
        "active deployments should be a list, even when nothing is deploying")


@pytest.mark.cluster
def test_cluster_facing_reads_answer_for_a_deployed_application(
        client, namespace, application, environment):
    """Status, metrics, events, resources and the current image.

    Grouped into one scenario because they all describe the same running
    workload and each needs the same expensive deploy to exist first.
    """
    from test_deploy import configure_for_build, git_strategy
    from test_deploy import DEPLOY_TIMEOUT, TERMINAL_STATUSES
    from oops_client import wait_until

    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the deploy to finish")
    if pipeline["status"] != "SUCCEEDED":
        pytest.skip(f"deploy ended as {pipeline['status']}")

    reads = {
        "/status": "pod status",
        "/current-image": "the image currently rolled out",
        "/events": "Kubernetes events",
        "/resources": "the rendered manifests",
        "/metrics": "live pod metrics",
        "/service/cluster-domain": "the in-cluster service address",
    }
    for suffix, description in reads.items():
        result = client.get(
            f"/api/namespaces/{namespace}/applications/{application}{suffix}"
            f"?environment={environment}", expect_success=False)
        assert result.status == 200, (
            f"{description} ({suffix}) answered HTTP {result.status} for a "
            f"successfully deployed application")

    # Metrics history needs a Prometheus-compatible backend, which the fixture cluster has not
    # got. It must decline cleanly rather than fail.
    history = client.get(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/metrics/history?environment={environment}&rangeHours=1",
        expect_success=False)
    assert history.status == 200, (
        "metrics history should answer inside the envelope when no monitoring "
        "backend is configured, not fail with an HTTP error")
