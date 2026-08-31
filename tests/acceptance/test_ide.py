"""The IDE endpoints, with the IDE feature switched off.

`oops.ide.enabled` is `false` in `tests/acceptance/application.yml`, and that is
deliberate: a code-server instance is a long-lived pod with a domain of its own,
so enabling it would make every run depend on ingress and DNS that the kind
fixture does not provide.

That leaves the disabled path as the thing worth pinning, and it is a real
contract rather than a placeholder. The whole controller disappears when the
flag is off, so all four routes stop existing, and what a client sees is decided
entirely by how unmapped paths are handled. The requirement is that they stay
inside the result envelope — `{success, message, data}` with `success: false` —
because the frontend's `apiFetch` reads the envelope and treats a bare HTTP
error page as a session problem, which is how a disabled optional feature ends
up logging the user out.

None of this needs a cluster: the routes are absent, so nothing is dispatched
and the `environment` parameter is never read. The file is therefore not marked
`cluster`, and it runs on a bare stack.

**If the IDE is ever enabled in the acceptance config**, these tests stop being
meaningful and every one of them will skip with a message pointing here. Replace
them with the enabled scenario: read the default config, create an instance,
list it, delete it, and assert it is gone — modelled on `test_podfs.py`, which
shows how to get a real workload to act against.
"""

from __future__ import annotations

import pytest

# The routes below are not registered while the feature is off, so nothing ever
# looks this name up. It is a placeholder, not a reference to a real cluster.
PLACEHOLDER_ENVIRONMENT = "ide-is-disabled"


def ide_base(namespace: str, application: str) -> str:
    return f"/api/namespaces/{namespace}/applications/{application}/ides"


@pytest.fixture(scope="session")
def ide_enabled(client) -> bool:
    return bool(client.get("/api/features").data.get("ide"))


@pytest.fixture(scope="session")
def ide_is_off(ide_enabled):
    """Skip the disabled-path tests when the IDE has been turned on."""
    if ide_enabled:
        pytest.skip("the IDE feature is enabled in this deployment, so the "
                    "disabled-path tests prove nothing — see the module "
                    "docstring for what to replace them with")


def test_the_feature_flag_reports_the_ide_off(client):
    """`/api/features` is how the frontend decides to render the IDE at all.

    Getting this wrong in either direction is a visible bug: a false negative
    hides a working feature, a false positive shows menu items that lead to
    endpoints which do not exist.
    """
    features = client.get("/api/features").data

    assert "ide" in features, (
        f"the features payload has no `ide` key: {features}; the sidebar gates "
        f"the IDE section on it and would fall back to hiding it forever")
    assert features["ide"] is False, (
        "the features endpoint reports the IDE as enabled, but "
        "tests/acceptance/application.yml sets oops.ide.enabled=false — the "
        "flag is not being read from configuration")
    # Documented, not endorsed: the host and scheme fields stay in the payload
    # when the feature is off, with the host null rather than the configured
    # value. A client that reads them without checking `ide` first gets null.
    assert features.get("ideHost") is None, (
        f"ideHost is {features.get('ideHost')!r} while the IDE is disabled; the "
        f"configured domain is leaking into a payload served to every user")
    assert features.get("ideHttps") is False, (
        "ideHttps should stay false while the IDE is disabled")


@pytest.mark.parametrize("method,suffix,body", [
    ("GET", "", None),
    ("GET", "/config/default", None),
    ("POST", "", {"name": "should-not-be-created", "branch": "main",
                  "settings": "", "env": "", "extensions": ""}),
    ("DELETE", "/should-not-exist", None),
], ids=["list", "default-config", "create", "delete"])
def test_each_ide_endpoint_declines_inside_the_envelope(client, namespace,
                                                        application, ide_is_off,
                                                        method, suffix, body):
    """All four routes, refused without leaving the result contract.

    The failure this guards against is not a wrong message — it is a response
    the client cannot parse. `OopsClient.request` raises unless the body carries
    all three envelope fields, so an HTML error page or an empty 404 fails here
    before any assertion runs.
    """
    path = (f"{ide_base(namespace, application)}{suffix}"
            f"?environment={PLACEHOLDER_ENVIRONMENT}")

    result = client.request(method, path, body, expect_success=False)

    assert result.success is False, (
        f"{method} {path} reported success while the IDE feature is disabled; "
        f"either the controller is registered regardless of the flag, or an "
        f"unmapped path is being answered by something that claims to have "
        f"worked")
    assert result.status == 200, (
        f"{method} {path} answered HTTP {result.status}; the frontend's "
        f"apiFetch hard-redirects to /login on a 401 and surfaces other "
        f"non-200s as transport errors, so a disabled feature must not use one")
    # Documented, not endorsed: an absent route is reported as
    # "Internal server error" — the global handler's catch-all for
    # NoResourceFoundException — rather than as a "feature disabled" or
    # "not found" message. It is a legible envelope, but it tells an operator
    # nothing about the flag they need to flip.
    assert result.message == "Internal server error", (
        f"{method} {path} refused with {result.message!r}, not the "
        f"'Internal server error' this suite recorded. If the message improved "
        f"— naming the disabled feature — update this assertion; if it "
        f"regressed to no message at all, the client has nothing to show")
    assert result.data is None, (
        f"{method} {path} carried data {result.data!r} in a failed envelope")


def test_a_disabled_ide_creates_nothing(client, namespace, application,
                                        ide_is_off):
    """The refusal has to be a refusal, not a silent success.

    A POST that answered `success: false` while still starting a code-server pod
    would be the worst outcome — an orphaned workload nobody knows to clean up —
    and the listing is the only way to see it from the API.
    """
    base = ide_base(namespace, application)
    client.post(f"{base}?environment={PLACEHOLDER_ENVIRONMENT}",
                {"name": "orphan-check", "branch": "main"},
                expect_success=False)

    listing = client.get(f"{base}?environment={PLACEHOLDER_ENVIRONMENT}",
                         expect_success=False)

    assert listing.success is False, (
        "the IDE listing succeeded after a create was refused; if the feature "
        "is in fact live, this test must be replaced by the enabled scenario "
        "described in the module docstring")
    assert not listing.data, (
        f"the listing returned {listing.data!r} while the feature is disabled, "
        f"which means something was created by the refused POST")
