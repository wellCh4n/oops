"""Fixtures for the acceptance suite.

Everything is driven by environment variables so the same tests can be pointed at
a local dev server, a CI stack, or a staging deployment.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

import pytest

from oops_client import OopsClient

DEFAULT_ENDPOINT = "http://localhost:8080"
DEFAULT_NAMESPACE = "acceptance"


def pytest_addoption(parser):
    parser.addoption("--endpoint", default=None,
                     help="OOPS base URL (default: $OOPS_ENDPOINT or localhost:8080)")
    parser.addoption("--namespace", default=None,
                     help="namespace the suite creates applications in")
    parser.addoption("--environment", default=None,
                     help="environment name to deploy into; cluster tests skip without it")


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "cluster: needs a reachable Kubernetes environment registered in OOPS",
    )


@pytest.fixture(scope="session")
def endpoint(pytestconfig) -> str:
    return (pytestconfig.getoption("endpoint")
            or os.environ.get("OOPS_ENDPOINT")
            or DEFAULT_ENDPOINT).rstrip("/")


@pytest.fixture(scope="session")
def namespace(pytestconfig) -> str:
    return (pytestconfig.getoption("namespace")
            or os.environ.get("OOPS_NAMESPACE")
            or DEFAULT_NAMESPACE)


@pytest.fixture(scope="session", autouse=True)
def service_is_up(endpoint):
    """Fail the session once, clearly, when nothing is listening.

    Without this every test reports its own connection error and the real cause
    scrolls off the top.
    """
    import requests

    try:
        # The health endpoint, not the login one. A GET against a @PostMapping
        # answers correctly but leaves an HttpRequestMethodNotSupportedException
        # in the backend log on every run, which is exactly the sort of noise
        # this suite is meant to help people notice rather than create.
        requests.get(f"{endpoint}/api/health", timeout=5)
    except requests.exceptions.RequestException as error:
        pytest.exit(
            f"no OOPS service answering at {endpoint}: {error}\n"
            f"Start the backend, or point the suite elsewhere with "
            f"--endpoint / $OOPS_ENDPOINT.",
            returncode=2,
        )


@pytest.fixture(scope="session")
def client(endpoint, service_is_up) -> OopsClient:
    """A logged-in admin client. Fails the whole session early if the service is
    not up, rather than letting every test fail with its own connection error."""
    connection = OopsClient(endpoint)
    username = os.environ.get("OOPS_USERNAME", "admin")
    password = os.environ.get("OOPS_PASSWORD", "admin123")
    try:
        connection.login(username, password)
    except Exception as error:
        pytest.exit(f"cannot log in to {endpoint} as {username}: {error}", returncode=2)
    return connection


@pytest.fixture(scope="session")
def environment(pytestconfig, client) -> str:
    """Name of an environment to deploy into.

    Resolution order: explicit flag, $OOPS_ENVIRONMENT, then the only environment
    registered if there happens to be exactly one. Cluster tests skip when none
    can be resolved, so the non-cluster tier still runs on a bare stack.
    """
    chosen = (pytestconfig.getoption("environment")
              or os.environ.get("OOPS_ENVIRONMENT"))
    registered = [item["name"] for item in client.list_environments()]
    if chosen:
        if chosen not in registered:
            pytest.exit(
                f"environment {chosen!r} is not registered; known: {registered}",
                returncode=2)
        return chosen
    if len(registered) == 1:
        return registered[0]
    pytest.skip("no environment registered — this needs the cluster tier, "
                "which ./run.sh sets up (you are probably in --contract)")


class Kubectl:
    """Reads cluster state directly, to check what the API only claims.

    Deletion is the case that needs it: the HTTP response says the application is
    gone, and only the cluster can say whether its objects actually went with it.
    """

    def __init__(self, kubeconfig: str):
        self.kubeconfig = kubeconfig

    def _run(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["kubectl", *arguments],
            env={**os.environ, "KUBECONFIG": self.kubeconfig},
            capture_output=True, text=True, timeout=30,
        )

    def exists(self, kind: str, name: str, namespace: str) -> bool:
        return self._run("get", kind, name, "-n", namespace).returncode == 0

    def get_json(self, kind: str, name: str, namespace: str):
        result = self._run("get", kind, name, "-n", namespace, "-o", "json")
        if result.returncode != 0:
            return None
        return json.loads(result.stdout)

    def names(self, kind: str, namespace: str) -> list[str]:
        result = self._run("get", kind, "-n", namespace,
                           "-o", "jsonpath={.items[*].metadata.name}")
        if result.returncode != 0:
            return []
        return result.stdout.split()


@pytest.fixture(scope="session")
def kubectl() -> Kubectl:
    kubeconfig = os.environ.get("KUBECONFIG") or str(
        Path(__file__).parent / "scripts" / ".k3s" / "kubeconfig.yaml")
    if not Path(kubeconfig).exists():
        pytest.skip(f"no kubeconfig at {kubeconfig} — run ./run.sh")
    if shutil.which("kubectl") is None:
        pytest.skip("kubectl is not on PATH")
    return Kubectl(kubeconfig)


@pytest.fixture(scope="session")
def cluster_credentials() -> dict:
    """API server address and token, as `cluster-provision.sh` exports them.

    Needed by tests that register their own environment rather than reusing the
    one the session was pointed at.
    """
    api_server = os.environ.get("OOPS_CLUSTER_API_SERVER")
    token = os.environ.get("OOPS_CLUSTER_TOKEN")
    if not api_server or not token:
        pytest.skip("no cluster credentials — this needs the cluster tier, "
                    "which ./run.sh sets up (you are probably in --contract)")
    return {
        "url": api_server,
        "token": token,
        "workNamespace": os.environ.get("OOPS_WORK_NAMESPACE", "oops-work"),
    }


@pytest.fixture
def environment_factory(client, cluster_credentials):
    """Registers environments and removes them afterwards.

    Registry credentials are a parameter rather than a constant because the two
    interesting cases are exactly credentials-present and credentials-absent.
    """
    created: list[str] = []

    def create(prefix: str = "env", registry: str = "",
               registry_username: str = "", registry_password: str = "",
               git_username: str = "", git_password: str = "",
               git_private_key: str = "") -> str:
        name = f"{prefix}-{uuid.uuid4().hex[:8]}"
        body = {
            "name": name,
            "kubernetesApiServer": {
                "url": cluster_credentials["url"],
                "token": cluster_credentials["token"],
            },
            "workNamespace": cluster_credentials["workNamespace"],
            "imageRepository": {
                "url": registry,
                "username": registry_username,
                "password": registry_password,
            },
        }
        if git_username or git_password or git_private_key:
            body["gitCredential"] = {
                "username": git_username,
                "password": git_password,
                "privateKey": git_private_key,
            }
        client.post("/api/environments", body)
        created.append(name)
        return name

    yield create

    for name in reversed(created):
        try:
            registered = {item["name"]: item["id"]
                          for item in client.list_environments()}
            if name in registered:
                client.delete(f"/api/environments/{registered[name]}",
                              expect_success=False)
        except Exception:
            pass


@pytest.fixture(scope="session")
def secondary_user(client) -> dict:
    """A plain, non-admin account, created once and reused.

    Authorisation rules cannot be tested from the admin session alone — admins
    are allowed to do everything, so every check would pass for the wrong
    reason.
    """
    username = "acceptance-nonowner"
    password = "acceptance-nonowner-123"

    existing = client.get("/api/users", expect_success=False)
    known = [item.get("username") for item in (existing.data or [])] \
        if isinstance(existing.data, list) else []

    if username not in known:
        created = client.post("/api/users", {
            "username": username,
            "email": f"{username}@example.invalid",
            "password": password,
        }, expect_success=False)
        if not created.success:
            pytest.skip(f"could not create the secondary account: {created.message}")

    return {"username": username, "password": password}


@pytest.fixture
def application_factory(client, namespace):
    """Creates applications and removes them afterwards, even if a test fails.

    Names carry a random suffix so a crashed run never collides with the next one.
    """
    created: list[str] = []

    def create(prefix: str = "acc") -> str:
        name = f"{prefix}-{uuid.uuid4().hex[:8]}"
        client.create_application(namespace, name)
        created.append(name)
        return name

    yield create

    for name in reversed(created):
        try:
            client.delete_application(namespace, name, expect_success=False)
        except Exception:
            # Cleanup is best effort — a leaked test app must never mask the
            # actual assertion failure that got us here.
            pass


@pytest.fixture
def application(application_factory) -> str:
    return application_factory()


@pytest.fixture(scope="session")
def unique_suffix() -> str:
    return f"{int(time.time())}-{uuid.uuid4().hex[:6]}"
