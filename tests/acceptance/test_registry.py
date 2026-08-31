"""The image path, both halves of it.

A registry has two configurations that behave completely differently inside OOPS,
and only one of them used to be exercised:

- **credentials present** — `syncImagePullSecret` writes a `dockerhub` secret into
  the work namespace, buildah mounts it to authenticate its push, the pull secret
  processor copies it into the application namespace, and the kubelet uses it to
  pull. Four steps, each able to fail on its own.
- **credentials absent** — no secret is written at all. Every consumer has to
  tolerate that, or the pipeline wedges before it starts.

The second case was a live bug: the build pod mounted the secret as a
non-optional volume, so an anonymous registry left every build stuck at
`Init:0/2` with `MountVolume.SetUp failed for volume "registry-secret"`, and
nothing in the OOPS UI said why.
"""

from __future__ import annotations

import os

import pytest

from oops_client import wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy)

pytestmark = pytest.mark.cluster

ANONYMOUS_REGISTRY = os.environ.get("OOPS_REGISTRY", "registry:5000")
AUTH_REGISTRY = os.environ.get("OOPS_AUTH_REGISTRY", "registry-auth:5000")
AUTH_REGISTRY_USER = os.environ.get("OOPS_AUTH_REGISTRY_USER", "oops")
AUTH_REGISTRY_PASSWORD = os.environ.get("OOPS_AUTH_REGISTRY_PASSWORD",
                                        "oops-secret")


def run_to_completion(client, namespace, application, environment) -> dict:
    configure_for_build(client, namespace, application, environment)
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    return wait_until(finished, timeout=DEPLOY_TIMEOUT,
                      description=f"pipeline {pipeline_id} to finish")


def test_build_starts_when_the_registry_needs_no_credentials(
        client, namespace, application, environment_factory):
    """Regression test for the wedged-build bug.

    An environment pointing at an anonymous registry gets no `dockerhub` secret.
    The build must still start — if the registry-secret volume is not marked
    optional the pod never leaves Init and this fails on the timeout rather than
    on an assertion.
    """
    anonymous = environment_factory(prefix="anon", registry=ANONYMOUS_REGISTRY)

    pipeline = run_to_completion(client, namespace, application, anonymous)

    assert pipeline["status"] == "SUCCEEDED", (
        f"a deploy against a credential-free registry ended as "
        f"{pipeline['status']}. If the build pod is stuck in Init, check that "
        f"the registry-secret volume in SecretVolume is marked optional.")


def test_build_pushes_and_pulls_through_an_authenticated_registry(
        client, namespace, application, environment_factory):
    """The whole credential chain, end to end.

    Reaching SUCCEEDED here means all four steps worked: the secret was created
    from the environment's credentials, buildah authenticated its push with it,
    the processor copied it into the application namespace, and the kubelet used
    it to pull the image back. A break anywhere shows up as ERROR.
    """
    authenticated = environment_factory(
        prefix="auth",
        registry=AUTH_REGISTRY,
        registry_username=AUTH_REGISTRY_USER,
        registry_password=AUTH_REGISTRY_PASSWORD,
    )

    pipeline = run_to_completion(client, namespace, application, authenticated)

    assert pipeline["status"] == "SUCCEEDED", (
        f"a deploy against an authenticated registry ended as "
        f"{pipeline['status']}; the push, the secret propagation or the image "
        f"pull failed")


def test_wrong_registry_credentials_fail_the_pipeline(
        client, namespace, application, environment_factory):
    """The negative case, so the test above cannot pass for the wrong reason.

    If a bad password still reached SUCCEEDED, it would mean the registry is not
    actually enforcing auth and the previous test proves nothing.
    """
    broken = environment_factory(
        prefix="badauth",
        registry=AUTH_REGISTRY,
        registry_username=AUTH_REGISTRY_USER,
        registry_password="definitely-the-wrong-password",
    )

    pipeline = run_to_completion(client, namespace, application, broken)

    assert pipeline["status"] == "ERROR", (
        f"a push with wrong credentials ended as {pipeline['status']}, expected "
        f"ERROR. Either the registry is not enforcing authentication, or a "
        f"failed push is not being reported as a failed pipeline.")
