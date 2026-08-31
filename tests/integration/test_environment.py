"""Registering and maintaining an environment.

An environment is a cluster plus the credentials to reach it, and it is split
across three write endpoints: create, update cluster, update credentials. They
are separate because the editor is tabbed and each tab saves on its own, so a
credential edit must not require re-sending the API server token and vice versa.

Secrets are encrypted at rest and are never expected to come back out, which is
the property most easily lost when the persistence layer is rewritten.
"""

from __future__ import annotations

import os
import uuid

import pytest

REGISTRY = os.environ.get("OOPS_REGISTRY", "registry:5000")


@pytest.fixture
def registered_environment(client, cluster_credentials):
    """A throwaway environment, removed afterwards even if a test fails."""
    created: list[str] = []

    def create(**overrides) -> dict:
        name = f"env-{uuid.uuid4().hex[:8]}"
        body = {
            "name": name,
            "kubernetesApiServer": {
                "url": cluster_credentials["url"],
                "token": cluster_credentials["token"],
            },
            "workNamespace": cluster_credentials["workNamespace"],
            "imageRepository": {
                "url": REGISTRY, "username": "oops", "password": "oops",
            },
        }
        body.update(overrides)
        environment = client.post("/api/environments", body).data
        created.append(environment["id"])
        return environment

    yield create

    for identifier in reversed(created):
        client.delete(f"/api/environments/{identifier}", expect_success=False)


def test_an_environment_can_be_registered_and_read_back(client,
                                                        registered_environment):
    environment = registered_environment()

    listed = {item["name"]: item for item in client.list_environments()}
    assert environment["name"] in listed, (
        "a registered environment is missing from the environment list")

    detail = client.get(f"/api/environments/{environment['id']}").data
    assert detail["name"] == environment["name"]
    assert detail["workNamespace"], "the work namespace was not stored"


def test_cluster_credentials_are_returned_in_clear_text_to_any_user(
        client, endpoint, registered_environment, secondary_user):
    """Documents a live credential exposure rather than the behaviour we want.

    `GET /api/environments` carries no role restriction, and the response
    includes `kubernetesApiServer.token` in clear text. The token is the
    cluster-admin service account the environment was registered with, so any
    account that can log in to OOPS — including a plain USER — can read it and
    then talk to the Kubernetes API server directly, as cluster-admin, entirely
    outside OOPS.

    Encrypting the column at rest does not help here: the API decrypts it on the
    way out. The fix is for EnvironmentDto to omit the secret fields, the same
    way the editor already treats a blank value as "keep what is stored".

    Written to pass against today's behaviour so the suite is honest about it,
    and to fail the moment the field stops coming back — at which point invert
    it to assert the token is absent.
    """
    from oops_client import OopsClient

    environment = registered_environment()

    plain_user = OopsClient(endpoint)
    plain_user.login(secondary_user["username"], secondary_user["password"])
    visible = plain_user.get("/api/environments").data

    tokens = [(item.get("kubernetesApiServer") or {}).get("token")
              for item in visible if item["name"] == environment["name"]]
    assert tokens and tokens[0], (
        "the cluster token is no longer returned to a plain user, which is the "
        "correct behaviour; invert this test to assert its absence")
    assert tokens[0] == os.environ.get("OOPS_CLUSTER_TOKEN"), (
        "a token came back but it is not the one that was stored; the exposure "
        "may have changed shape rather than being closed")


def test_the_cluster_tab_saves_on_its_own(client, registered_environment,
                                          cluster_credentials):
    """Updating the cluster must not require re-sending the credentials."""
    environment = registered_environment()

    client.put(f"/api/environments/{environment['id']}/cluster", {
        "name": environment["name"],
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": cluster_credentials["token"],
        },
        "workNamespace": cluster_credentials["workNamespace"],
        "buildStorageClass": "standard",
    })

    detail = client.get(f"/api/environments/{environment['id']}").data
    assert detail["buildStorageClass"] == "standard", (
        "the build storage class did not survive a cluster-tab save")


def test_the_credentials_tab_saves_on_its_own(client, registered_environment):
    """Updating credentials must not clear the cluster configuration.

    The two tabs post different subsets of the same object, so a save that
    replaces rather than merges silently unregisters the cluster.
    """
    environment = registered_environment()
    before = client.get(f"/api/environments/{environment['id']}").data

    client.put(f"/api/environments/{environment['id']}/credentials", {
        "name": environment["name"],
        "imageRepository": {
            "url": REGISTRY, "username": "changed", "password": "changed",
        },
        "gitCredential": {
            "username": "git-user", "password": "git-password", "privateKey": "",
        },
    })

    after = client.get(f"/api/environments/{environment['id']}").data
    assert after["workNamespace"] == before["workNamespace"], (
        "saving the credentials tab cleared the work namespace; the two tabs "
        "must merge rather than replace")
    assert (after.get("kubernetesApiServer") or {}).get("url") == \
           (before.get("kubernetesApiServer") or {}).get("url"), (
        "saving the credentials tab cleared the API server URL")
    assert (after.get("imageRepository") or {}).get("username") == "changed", (
        "the registry username was not updated")


def test_a_duplicate_environment_name_is_rejected(client,
                                                  registered_environment,
                                                  cluster_credentials):
    """Environments are referenced by name everywhere, so names must be unique."""
    environment = registered_environment()

    result = client.post("/api/environments", {
        "name": environment["name"],
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": cluster_credentials["token"],
        },
        "workNamespace": cluster_credentials["workNamespace"],
        "imageRepository": {"url": REGISTRY, "username": "oops",
                            "password": "oops"},
    }, expect_success=False)

    assert result.success is False, (
        "two environments were registered under the same name; every reference "
        "in the product is by name, so one of them is now unreachable")


def test_deleting_an_environment_removes_it_from_the_list(
        client, registered_environment):
    environment = registered_environment()
    client.delete(f"/api/environments/{environment['id']}")

    names = [item["name"] for item in client.list_environments()]
    assert environment["name"] not in names, (
        "the environment is still listed after deletion")


@pytest.mark.cluster
def test_cluster_connectivity_is_validated_before_saving(client,
                                                         cluster_credentials):
    """The editor checks the cluster before the user commits to it.

    A wrong token has to be reported as a failed validation rather than saved
    and discovered later, when a deploy fails for reasons nobody connects to the
    environment form.
    """
    good = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": cluster_credentials["token"],
        },
        "workNamespace": cluster_credentials["workNamespace"],
    }).data
    assert good["success"] is True, (
        f"validation rejected a working cluster: {good.get('message')}")
    assert good["status"] == "VALID"

    bad = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": "not-a-valid-token",
        },
        "workNamespace": cluster_credentials["workNamespace"],
    }).data
    assert bad["success"] is False, (
        "validation accepted an invalid token; the check is not actually "
        "reaching the API server")
    assert bad["status"] in ("CONNECTION_FAILED", "ERROR", "NAMESPACE_MISSING")
