"""Accounts: the administrator's directory, and what an account does for itself.

Two journeys rather than ten endpoint pings. An administrator creates an
account, edits it, switches it off and removes it. The account holder meanwhile
maintains their own email, password and access token, and must not be able to
reach the administrative half of the same controller.

The session logs in as admin/admin123 and later files depend on that, so every
credential change here happens on a throwaway account that is removed again.
None of it needs a cluster.
"""

from __future__ import annotations

import re
import uuid

import pytest

from oops_client import OopsClient

ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{24}$")

# Access tokens are the machine credential for /openapi/**: a fixed prefix so a
# leaked one is recognisable on sight, then a NanoId.
ACCESS_TOKEN_PATTERN = re.compile(r"^sk-oops-[A-Za-z0-9_-]{24}$")

INITIAL_PASSWORD = "integration-initial-123"
REPLACEMENT_PASSWORD = "integration-replacement-456"


def directory(client) -> list[dict]:
    return client.get("/api/users").data or []


def accounts_named(client, username: str) -> list[dict]:
    return [item for item in directory(client) if item["username"] == username]


def newest_account_named(client, username: str) -> dict | None:
    """The most recently created account carrying the name.

    Written to tolerate more than one match, because the API does not stop the
    same username being registered twice — see the duplicate scenario below.
    """
    matches = accounts_named(client, username)
    if not matches:
        return None
    return max(matches, key=lambda item: item["createdTime"])


@pytest.fixture
def user_factory(client):
    """Creates accounts and removes them afterwards, even when a test fails.

    Creation answers with `true` rather than the new identifier, so the id has
    to be read back out of the directory. Cleanup deletes every row carrying the
    name rather than one id, because duplicates are reachable through this API
    and a leaked duplicate would poison the next run's login.
    """
    created: list[str] = []

    def create(prefix: str = "acc-user", username: str | None = None,
               password: str = INITIAL_PASSWORD) -> dict:
        name = username or f"{prefix}-{uuid.uuid4().hex[:8]}"
        email = f"{name}-{uuid.uuid4().hex[:4]}@example.invalid"
        client.post("/api/users",
                    {"username": name, "email": email, "password": password})
        created.append(name)
        stored = newest_account_named(client, name)
        assert stored is not None, (
            f"POST /api/users reported success for {name!r} but the account is "
            "absent from the directory, so nothing was actually persisted")
        return {"username": name, "password": password, "email": email,
                "id": stored["id"]}

    yield create

    for name in reversed(created):
        try:
            for account in accounts_named(client, name):
                client.delete(f"/api/users/{account['id']}", expect_success=False)
        except Exception:
            # Cleanup is best effort — a leaked account must never mask the
            # assertion failure that got us here.
            pass


def test_the_directory_lists_pages_and_filters_the_same_accounts(client, user_factory):
    account = user_factory()

    listed = directory(client)
    assert isinstance(listed, list), (
        "GET /api/users must answer with a flat array; the admin console renders "
        "it directly")
    assert account["username"] in [item["username"] for item in listed], (
        "a freshly created account is missing from the flat listing, so it would "
        "be invisible in the console that just created it")

    page = client.get("/api/users/page?page=1&size=5").data
    for field in ("total", "data", "size", "totalPages"):
        assert field in page, f"the user page envelope is missing {field!r}"
    assert page["size"] == 5, "size should echo the requested page size"
    assert page["total"] >= len(page["data"]), (
        "total is smaller than the page it describes, which makes the pager "
        "compute the wrong number of pages")

    by_username = client.get(
        f"/api/users/page?keyword={account['username']}&page=1&size=10").data
    assert [item["username"] for item in by_username["data"]] == [account["username"]], (
        "the keyword filter did not narrow the page to the one matching account")

    by_email = client.get(
        f"/api/users/page?keyword={account['email']}&page=1&size=10").data
    assert [item["username"] for item in by_email["data"]] == [account["username"]], (
        "the keyword filter ignores the email address, so an administrator "
        "holding only an address cannot find the account behind it")


def test_an_account_lifecycle_from_creation_to_removal(client, endpoint, user_factory):
    account = user_factory()
    assert ID_PATTERN.match(account["id"]), (
        f"user id {account['id']!r} is not a 24-char NanoId")

    OopsClient(endpoint).login(account["username"], account["password"])

    updated_email = f"updated-{uuid.uuid4().hex[:6]}@example.invalid"
    client.put(f"/api/users/{account['id']}", {
        "role": "ADMIN",
        "email": updated_email,
        "password": REPLACEMENT_PASSWORD,
        "enabled": False,
    })

    stored = newest_account_named(client, account["username"])
    assert stored["role"] == "ADMIN", "the administrative role change was not stored"
    assert stored["email"] == updated_email, "the email change was not stored"
    assert stored["enabled"] is False, (
        "the account is still enabled after being switched off, so a departing "
        "colleague would keep their access")

    disabled = OopsClient(endpoint).post(
        "/api/auth/login",
        {"username": account["username"], "password": REPLACEMENT_PASSWORD},
        authenticated=False, expect_success=False)
    assert disabled.success is False, (
        "a disabled account was still able to log in, which makes disabling an "
        "account meaningless")

    client.put(f"/api/users/{account['id']}", {
        "role": "USER",
        "email": updated_email,
        "enabled": True,
    })

    revived = OopsClient(endpoint)
    revived.login(account["username"], REPLACEMENT_PASSWORD)
    assert revived.get("/api/users/me").data["role"] == "USER", (
        "the re-enabled account did not come back as a plain user, so the "
        "temporary promotion was never undone")

    client.delete(f"/api/users/{account['id']}")
    assert accounts_named(client, account["username"]) == [], (
        "the deleted account is still in the directory")

    gone = OopsClient(endpoint).post(
        "/api/auth/login",
        {"username": account["username"], "password": REPLACEMENT_PASSWORD},
        authenticated=False, expect_success=False)
    assert gone.success is False, "a deleted account can still log in"


def test_the_self_service_journey(client, endpoint, user_factory):
    """Profile, password and access token, all driven by the account holder.

    A plain user owns these three endpoints outright — no administrator is in
    the loop — which is exactly why they need to hold their own boundaries.
    """
    account = user_factory()
    holder = OopsClient(endpoint)
    holder.login(account["username"], account["password"])

    me = holder.get("/api/users/me").data
    assert me["id"] == account["id"], (
        "GET /api/users/me answered with somebody else's account")
    assert me["username"] == account["username"]
    assert me["role"] == "USER", "a newly created account must not be an administrator"

    self_chosen_email = f"self-{uuid.uuid4().hex[:6]}@example.invalid"
    holder.put("/api/users/me", {"email": self_chosen_email})
    assert holder.get("/api/users/me").data["email"] == self_chosen_email, (
        "the profile update reported success but the email did not change")

    wrong_old = holder.put(
        "/api/users/me/password",
        {"oldPassword": "not-the-current-password", "newPassword": REPLACEMENT_PASSWORD},
        expect_success=False)
    assert wrong_old.success is False, (
        "the password was changed without proving knowledge of the old one, so "
        "a stolen session token is enough to take the account over")

    blank_new = holder.put(
        "/api/users/me/password",
        {"oldPassword": account["password"], "newPassword": ""},
        expect_success=False)
    assert blank_new.success is False, "a blank password was accepted"

    holder.put("/api/users/me/password",
               {"oldPassword": account["password"], "newPassword": REPLACEMENT_PASSWORD})

    stale = OopsClient(endpoint).post(
        "/api/auth/login",
        {"username": account["username"], "password": account["password"]},
        authenticated=False, expect_success=False)
    assert stale.success is False, "the replaced password still works"
    OopsClient(endpoint).login(account["username"], REPLACEMENT_PASSWORD)

    first_token = holder.post("/api/users/me/access-token/reset").data
    assert ACCESS_TOKEN_PATTERN.match(first_token), (
        f"access token {first_token!r} does not carry the sk-oops- prefix and a "
        "NanoId; the prefix is what makes a leaked token recognisable")

    second_token = holder.post("/api/users/me/access-token/reset").data
    assert second_token != first_token, (
        "resetting the access token handed back the same value, so a leaked "
        "token cannot actually be revoked")
    assert holder.get("/api/users/me").data["accessToken"] == second_token, (
        "the account still carries the superseded access token")


def test_account_creation_requires_a_username_and_an_email(client):
    for payload, missing in (
        ({"username": "", "email": "someone@example.invalid", "password": "x"}, "username"),
        ({"username": "acc-no-email", "email": "", "password": "x"}, "email"),
    ):
        result = client.post("/api/users", payload, expect_success=False)
        assert result.success is False, (
            f"an account with a blank {missing} was accepted")
        assert result.message, (
            f"the rejection of a blank {missing} carries no message, leaving the "
            "form with nothing to show")


def test_a_plain_user_cannot_create_accounts(endpoint, secondary_user):
    """Authorisation holds, but the refusal is indistinguishable from a fault.

    Denial is reported as HTTP 200 with success=false and the message "Internal
    server error", because the catch-all exception handler swallows the access
    denial along with everything else. Documented rather than endorsed: the
    refusal is correct, its wording tells the caller to retry a request that
    will never succeed. Fix it by handling AccessDeniedException separately, and
    change this test in the same commit.
    """
    plain = OopsClient(endpoint)
    plain.login(secondary_user["username"], secondary_user["password"])

    result = plain.post("/api/users", {
        "username": f"acc-forbidden-{uuid.uuid4().hex[:8]}",
        "email": "forbidden@example.invalid",
        "password": "integration-forbidden-123",
    }, expect_success=False)
    assert result.success is False, (
        "a plain user created an account, so anyone who can log in can mint "
        "themselves a second identity")


def test_duplicate_usernames_are_accepted_and_then_break_login(client, endpoint,
                                                               user_factory):
    """Pins a defect so that fixing it is a deliberate, visible act.

    Nothing rejects a username that is already taken, and the second account is
    not merely cosmetic: the login lookup expects at most one row, so the moment
    a name is duplicated *both* holders are locked out with an internal error.

    Written against today's behaviour. When the uniqueness constraint arrives
    this test fails at the first assertion and should be inverted rather than
    deleted, because the change is visible to every user who has ever picked a
    name.
    """
    account = user_factory()
    user_factory(username=account["username"], password=REPLACEMENT_PASSWORD)

    assert len(accounts_named(client, account["username"])) == 2, (
        "the second account with an existing username was rejected — usernames "
        "are unique now, which is an improvement; invert this test")

    ambiguous = OopsClient(endpoint).post(
        "/api/auth/login",
        {"username": account["username"], "password": account["password"]},
        authenticated=False, expect_success=False)
    assert ambiguous.success is False, (
        "login resolved a duplicated username, so it silently picked one of two "
        "accounts")


def test_an_admin_update_clears_the_role_it_does_not_mention(client, endpoint,
                                                             user_factory):
    """Pins an asymmetry that quietly bricks accounts.

    `password` and `enabled` are patched — omitting them keeps the stored value
    — while `role` and `email` are overwritten with whatever the body carries,
    null included. An administrator who submits a partial body therefore strips
    the role, and an account with no role cannot log in at all.

    Documented rather than endorsed. Making every field patch-like, or rejecting
    a null role, would be an improvement; this test should change with it.
    """
    account = user_factory()

    client.put(f"/api/users/{account['id']}", {"email": account["email"]})

    stored = newest_account_named(client, account["username"])
    assert stored["role"] is None, (
        "the role survived an update that omitted it — partial updates are now "
        "safe, which is an improvement; invert this test")

    locked_out = OopsClient(endpoint).post(
        "/api/auth/login",
        {"username": account["username"], "password": account["password"]},
        authenticated=False, expect_success=False)
    assert locked_out.success is False, (
        "an account with no role can log in again, so the consequence this test "
        "documents is gone; invert it")


def test_editing_or_deleting_an_unknown_account_reports_success(client):
    """A missing account is not an error here — both calls answer success=true.

    Pinned because it is the kind of undocumented contract a reimplementation
    quietly changes. Returning 404, or success=false, would break every caller
    that currently treats `true` as confirmation. It also means an administrator
    editing a stale row is told the change landed when nothing happened. Change
    it deliberately, and change this test in the same commit.
    """
    missing = "definitely-not-a-user-x"

    updated = client.put(f"/api/users/{missing}",
                         {"role": "USER", "email": "nobody@example.invalid"},
                         expect_success=False)
    assert updated.status == 200, f"expected HTTP 200, got {updated.status}"
    assert updated.success is True and updated.data is True, (
        "updating an unknown account no longer reports success; that is arguably "
        "better, but it changes the contract every caller was written against")

    removed = client.delete(f"/api/users/{missing}", expect_success=False)
    assert removed.status == 200, f"expected HTTP 200, got {removed.status}"
    assert removed.success is True and removed.data is True, (
        "deleting an unknown account no longer reports success; same contract "
        "change as above")


def test_every_user_payload_carries_the_stored_password_hash(client):
    """Documents an exposure, and fails the day it is closed.

    The user aggregate is serialised as-is, so the BCrypt hash and the access
    token travel to every authenticated caller of GET /api/users, /page and /me
    — including plain users, who have no business reading either. Hashes are not
    plaintext, but they are offline-crackable, and the access token is a live
    credential in the clear.

    Kept as a test rather than a comment so that introducing a response DTO is
    noticed here first.
    """
    me = client.get("/api/users/me").data
    assert "password" in me, (
        "the password hash is no longer serialised — the exposure this test "
        "documents has been fixed; delete this test and the field from any "
        "client that reads it")


def test_the_enabled_external_login_providers_are_listed(client):
    providers = client.get("/api/auth/external/providers").data
    assert isinstance(providers, list), (
        "the provider list must be an array; the login page iterates it to draw "
        "one button per provider")
    assert all(isinstance(item, str) for item in providers), (
        "provider entries must be plain identifier strings")
