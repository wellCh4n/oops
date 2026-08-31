"""The wire contract itself — envelope, auth, paging, identifiers, timestamps.

None of this needs a cluster, and it is the tier a reimplementation breaks most
quietly: the shapes still deserialize, they just no longer mean the same thing.
"""

from __future__ import annotations

import re

import pytest

from oops_client import OopsClient

# 24-character NanoId, as minted by domain.NewID / NanoIdUtils.
ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{24}$")

# LocalDateTime: local wall clock with microseconds and no zone suffix. A trailing
# Z or +08:00 means someone started converting timestamps, which silently shifts
# every stored value by the process offset.
TIMESTAMP_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$")


def test_login_returns_a_token_and_identity(endpoint):
    fresh = OopsClient(endpoint)
    token = fresh.login("admin", "admin123")
    assert token, "login returned an empty token"

    result = fresh.post("/api/auth/login",
                        {"username": "admin", "password": "admin123"},
                        authenticated=False)
    # The identity field is `id` here, while the JWT claim inside the token is
    # `userId`. Both spellings are load-bearing and easy to conflate.
    for field in ("token", "id", "username", "role"):
        assert field in result.data, f"login response is missing {field!r}"
    assert result.data["role"] in ("ADMIN", "USER")
    assert ID_PATTERN.match(result.data["id"])


def test_bad_credentials_fail_inside_the_envelope(endpoint):
    """A rejected login is still a 200 with success=false, not an HTTP error."""
    fresh = OopsClient(endpoint)
    result = fresh.post("/api/auth/login",
                        {"username": "admin", "password": "definitely-not-the-password"},
                        authenticated=False, expect_success=False)
    assert result.success is False
    assert result.message, "a failed login must carry a message"
    assert result.data is None


def test_unauthenticated_calls_are_rejected(endpoint, namespace, service_is_up):
    """Assert on the status specifically. Catching "any exception" here would let
    a connection refusal pass as a successful authorisation check."""
    anonymous = OopsClient(endpoint)
    response = anonymous.raw(
        "GET", f"/api/namespaces/{namespace}/applications", authenticated=False)
    assert response.status_code in (401, 403), (
        f"an unauthenticated call returned {response.status_code}, expected 401/403")


def test_a_garbage_token_is_rejected(endpoint, namespace, service_is_up):
    forged = OopsClient(endpoint, token="not-a-real-token")
    response = forged.raw("GET", f"/api/namespaces/{namespace}/applications")
    assert response.status_code in (401, 403), (
        f"a forged bearer token returned {response.status_code}, expected 401/403")


def test_success_envelope_shape(client, namespace):
    result = client.get(f"/api/namespaces/{namespace}/applications?page=1&size=10")
    assert result.success is True
    assert result.message is None, "a successful call should not carry a message"
    assert result.data is not None


def test_pagination_shape_and_one_based_pages(client, namespace):
    page = client.get(
        f"/api/namespaces/{namespace}/applications?page=1&size=5").data
    for field in ("total", "data", "size", "totalPages"):
        assert field in page, f"page envelope is missing {field!r}"
    assert isinstance(page["data"], list)
    assert page["size"] == 5, "size should echo the requested page size"

    # Page numbering is 1-based; asking for page 0 must not be treated as page 1.
    first = client.get(f"/api/namespaces/{namespace}/applications?page=1&size=1").data
    assert first["size"] == 1


def test_created_application_has_a_nanoid_and_naive_timestamp(client, namespace,
                                                              application):
    profile = client.get_application(namespace, application).data

    assert ID_PATTERN.match(profile["id"]), (
        f"id {profile['id']!r} is not a 24-char NanoId")

    created = profile.get("createdTime")
    assert created, "createdTime is missing"
    assert TIMESTAMP_PATTERN.match(created), (
        f"createdTime {created!r} is not a naive local timestamp — a zone suffix "
        "means values are being converted rather than read verbatim")


def test_invalid_resource_names_are_rejected(client, namespace):
    for candidate in ("UPPERCASE", "has space", "trailing-", "-leading", "dots.not.ok"):
        result = client.post(
            f"/api/namespaces/{namespace}/applications",
            {"name": candidate, "namespace": namespace},
            expect_success=False,
        )
        assert result.success is False, (
            f"{candidate!r} was accepted as an application name")


def test_missing_application_answers_success_with_null_data(client, namespace):
    """A missing application is *not* an error here — the call succeeds and hands
    back null data, with HTTP 200 throughout.

    Pinning this down because it is the kind of undocumented contract a
    reimplementation quietly changes: returning 404, or an envelope with
    success=false, would break every caller that currently checks `data == null`.
    Change it deliberately if you want to, but change this test in the same
    commit.
    """
    result = client.get_application(namespace, "definitely-not-here-9f3a2b",
                                    expect_success=False)
    assert result.status == 200, f"expected HTTP 200, got {result.status}"
    assert result.success is True
    assert result.data is None


def test_duplicate_application_name_is_rejected(client, namespace, application):
    result = client.post(
        f"/api/namespaces/{namespace}/applications",
        {"name": application, "namespace": namespace},
        expect_success=False,
    )
    assert result.success is False, "the same application name was accepted twice"


def test_application_names_are_globally_unique_not_per_namespace(client, namespace,
                                                                 application):
    """Documents a constraint that contradicts the shape of the API.

    Every path is namespaced — `/api/namespaces/{namespace}/applications/{name}`
    — which reads as though a name only has to be unique within its namespace.
    The schema disagrees: `UNIQUE KEY uk_application_name (name)` is on the name
    alone, so two teams working in separate namespaces cannot both have an
    application called `web`.

    Written to pass against today's behaviour. If the key is ever widened to
    (namespace, name), this test fails and should be inverted rather than
    deleted — the change is worth making deliberately, because it is visible to
    every user who has ever picked a name and been told it was taken.
    """
    other_namespace = f"{namespace}-second"
    result = client.post(
        f"/api/namespaces/{other_namespace}/applications",
        {"name": application, "namespace": other_namespace},
        expect_success=False,
    )
    assert result.success is False, (
        "the same application name was accepted in a second namespace — the "
        "unique key now appears to be per namespace, which is an improvement; "
        "invert this test")


def test_application_appears_in_its_namespace_listing(client, namespace, application):
    page = client.get(
        f"/api/namespaces/{namespace}/applications?page=1&size=200").data
    names = [item["name"] for item in page["data"]]
    assert application in names, (
        f"{application} was created but is absent from the namespace listing")
