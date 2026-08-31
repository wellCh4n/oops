"""The git path against a real Git server, with and without credentials.

Building from a public repository on the internet proves very little: it never
exercises authentication, never touches the `git-credential` secret OOPS syncs
into the work namespace, and cannot test a private repository at all. A local
Gitea gives all three.

One address for the server, because everything that touches it runs in Docker:
the backend lists branches in process, and the pipeline pods clone. Both resolve
the container name over the shared network, so a repository URL means the same
thing wherever it is redeemed.
"""

from __future__ import annotations

import os

import pytest

from oops_client import wait_until
from test_deploy import DEPLOY_TIMEOUT, TERMINAL_STATUSES

GITEA_USER = os.environ.get("OOPS_GITEA_USER", "oops")
GITEA_PASSWORD = os.environ.get("OOPS_GITEA_PASSWORD", "oops-git-secret")
# One address now. The backend lists branches and the pipeline pods clone, and
# both run in Docker, so both resolve the container name. The published port is
# only used by this suite to check the fixture server is up.
GITEA_URL = os.environ.get("OOPS_GITEA_URL", "http://gitea:3000")
PROBE_URL = os.environ.get("OOPS_GITEA_PROBE_URL", "http://127.0.0.1:13000")


def repository(base: str, name: str) -> str:
    return f"{base.rstrip('/')}/{GITEA_USER}/{name}.git"


@pytest.fixture(scope="session")
def gitea_available(client) -> bool:
    """Skip rather than fail when the Git fixture server is not running."""
    import requests

    try:
        response = requests.get(f"{PROBE_URL}/api/healthz", timeout=5)
    except requests.exceptions.RequestException:
        pytest.skip(f"no Gitea at {PROBE_URL} — run ./run.sh, which starts it")
    if response.status_code != 200:
        pytest.skip(f"Gitea at {PROBE_URL} is not healthy")
    return True


@pytest.fixture
def credentialed_environment(environment_factory, gitea_available):
    """An environment carrying git credentials for the private fixture repo."""
    return environment_factory(
        prefix="git",
        registry=os.environ.get("OOPS_REGISTRY", "registry:5000"),
        registry_username="oops",
        registry_password="oops",
        git_username=GITEA_USER,
        git_password=GITEA_PASSWORD,
    )


# -- branch listing, in process, no cluster needed ---------------------------

def test_branches_of_a_public_repository_are_listed(client, namespace,
                                                    application, environment,
                                                    gitea_available):
    """The branch picker reads refs directly from the OOPS process.

    This is the JGit path — `Git.lsRemoteRepository` against the remote, with no
    build pod involved, which is the whole reason it exists.
    """
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": repository(GITEA_URL, "public-fixture"),
    })

    result = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/branches"
        f"?environment={environment}")

    names = [branch["name"] for branch in result.data]
    assert "main" in names, f"the default branch is missing from {names}"
    assert "release" in names, (
        f"the second branch is missing from {names}; ls-remote is not returning "
        f"every head")


def test_branch_listing_carries_the_tip_commit(client, namespace, application,
                                               environment, gitea_available):
    """Each branch comes back with its tip, and best effort commit detail.

    The commit message and author are fetched separately and are documented as
    best effort, so the SHA is the only part that must always be present.
    """
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": repository(GITEA_URL, "public-fixture"),
    })

    branches = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/branches"
        f"?environment={environment}").data

    for branch in branches:
        assert branch.get("commitId"), (
            f"branch {branch['name']} came back without a tip SHA")
        assert len(branch["commitId"]) >= 7, (
            f"branch {branch['name']} has an implausible SHA "
            f"{branch['commitId']!r}")


def test_a_private_repository_without_credentials_is_refused(
        client, namespace, application, environment, gitea_available):
    """The failure has to be legible, because this is a common misconfiguration.

    The environment used here has no git credentials, so the listing must fail
    rather than silently return nothing, and the message must point at the
    credentials rather than at a raw transport error.
    """
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": repository(GITEA_URL, "private-fixture"),
    })

    result = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/branches"
        f"?environment={environment}",
        expect_success=False)

    assert result.success is False, (
        "a private repository was listed without credentials, which means "
        "either the repository is not actually private or authentication is "
        "not being enforced")
    assert result.message, "a refused branch listing must explain itself"


def test_branches_of_a_private_repository_with_credentials(
        client, namespace, application, credentialed_environment):
    """The same listing succeeds once the environment carries credentials."""
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": repository(GITEA_URL, "private-fixture"),
    })

    result = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/branches"
        f"?environment={credentialed_environment}")

    names = [branch["name"] for branch in result.data]
    assert "main" in names, (
        f"a private repository with valid credentials listed {names}; the "
        f"environment git credentials are not reaching JGit")


def test_a_missing_repository_reports_a_useful_message(client, namespace,
                                                       application, environment,
                                                       gitea_available):
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": repository(GITEA_URL, "does-not-exist"),
    })

    result = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/branches"
        f"?environment={environment}",
        expect_success=False)

    assert result.success is False
    assert result.message, "a missing repository must explain itself"


# -- building from git, needs a cluster --------------------------------------

def build_from(client, namespace, application, environment, url) -> dict:
    from test_deploy import configure_for_build

    configure_for_build(client, namespace, application, environment)
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "GIT",
        "repository": url,
        "environmentConfigs": [{"environment": environment, "buildCommand": ""}],
    })
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy={"type": "GIT", "branch": "main"})

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    return wait_until(finished, timeout=DEPLOY_TIMEOUT,
                      description="the git-sourced pipeline to finish")


@pytest.mark.cluster
def test_a_public_repository_builds(client, namespace, application, environment,
                                    gitea_available):
    """The clone init container reaches the fixture server and builds it."""
    pipeline = build_from(client, namespace, application, environment,
                          repository(GITEA_URL, "public-fixture"))

    assert pipeline["status"] == "SUCCEEDED", (
        f"a build from a public repository ended as {pipeline['status']}; the "
        f"clone container could not reach {GITEA_URL} or the build failed")


@pytest.mark.cluster
def test_a_private_repository_builds_with_synced_credentials(
        client, namespace, application, credentialed_environment, kubectl):
    """The whole credential path, from the environment to the clone container.

    Saving the environment writes a `git-credential` secret into the work
    namespace, the build pod mounts it, and the clone step authenticates with
    it. Reaching SUCCEEDED against a private repository means every link held.
    """
    work_namespace = os.environ.get("OOPS_WORK_NAMESPACE", "oops-work")
    assert kubectl.exists("secret", "git-credential", work_namespace), (
        "no git-credential secret in the work namespace; saving an environment "
        "with git credentials is meant to sync one")

    pipeline = build_from(client, namespace, application,
                          credentialed_environment,
                          repository(GITEA_URL, "private-fixture"))

    assert pipeline["status"] == "SUCCEEDED", (
        f"a build from a private repository ended as {pipeline['status']}; the "
        f"git-credential secret is not reaching the clone container")


@pytest.mark.cluster
def test_git_credentials_are_shared_across_a_work_namespace(
        client, namespace, application, environment, kubectl, gitea_available):
    """Git credentials are scoped to the work namespace, not to the environment.

    Saving an environment writes its credentials into the work namespace as the
    `git-credential` secret, and every build pod in that namespace mounts it —
    the mount does not know which environment triggered the build. So an
    environment with no git credentials of its own still clones private
    repositories, using whatever another environment last synced.

    That is worth knowing before two teams are pointed at one work namespace:
    the isolation the environment list implies is not there for git.

    Written against today's behaviour. If credentials are ever scoped per
    environment this fails, and it should then be inverted to assert that the
    clone is refused.
    """
    work_namespace = os.environ.get("OOPS_WORK_NAMESPACE", "oops-work")
    if not kubectl.exists("secret", "git-credential", work_namespace):
        pytest.skip("no credentials have been synced into the work namespace, "
                    "so there is nothing for this environment to inherit")

    pipeline = build_from(client, namespace, application, environment,
                          repository(GITEA_URL, "private-fixture"))

    assert pipeline["status"] == "SUCCEEDED", (
        f"a private clone from an environment without git credentials ended as "
        f"{pipeline['status']}. If it now fails, credentials have become "
        f"environment-scoped — an improvement; invert this test.")
