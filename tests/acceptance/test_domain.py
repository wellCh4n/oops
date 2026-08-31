"""Managed domains: the routing suffixes an administrator owns.

A domain row is not a DNS record. It is a claim that one suffix belongs to one
environment, plus the certificate policy for everything under it. That claim is
what stops an application in staging from publishing a host under production's
domain, so the interesting behaviour is not the CRUD but the matching: which
domain governs a given host, and what happens when a governing domain is moved.

No cluster is involved. Domains only need an environment to exist as a row, so
the fixtures register one with a blank work namespace, which is the shape that
touches no API server.
"""

from __future__ import annotations

import re
import uuid

import pytest

ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{24}$")

TIMESTAMP_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$")


@pytest.fixture
def environment_names(client):
    """Registers bare environments and removes them afterwards.

    Only the name matters here: an environment with no work namespace is never
    reached over the network, so the domain tests stay off the cluster tier
    while still satisfying the "environment must exist" rule.
    """
    created: list[str] = []

    def create(prefix: str = "acc-domain") -> str:
        name = f"{prefix}-{uuid.uuid4().hex[:8]}"
        client.post("/api/environments", {
            "name": name,
            "kubernetesApiServer": {"url": "", "token": ""},
            "workNamespace": "",
            "imageRepository": {"url": "", "username": "", "password": ""},
        })
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


@pytest.fixture
def domain_factory(client):
    """Creates domains and removes them afterwards, even when a test fails.

    Cleanup re-reads the listing rather than trusting the ids it was handed,
    because a test may legitimately have deleted a domain already.
    """
    created: list[str] = []

    def create(host: str, environment: str, **fields) -> dict:
        payload = {"host": host, "environment": environment, "https": False}
        payload.update(fields)
        domain = client.post("/api/domains", payload).data
        created.append(domain["id"])
        return domain

    yield create

    for identifier in reversed(created):
        try:
            client.delete(f"/api/domains/{identifier}", expect_success=False)
        except Exception:
            pass


def unique_host(label: str = "acc") -> str:
    return f"{label}-{uuid.uuid4().hex[:8]}.example.invalid"


def hosts_in_listing(client) -> list[str]:
    return [item["host"] for item in client.get("/api/domains").data]


def test_a_managed_domain_lifecycle(client, environment_names, domain_factory):
    environment = environment_names()
    host = unique_host("lifecycle")

    created = domain_factory(host, environment, description="acceptance test",
                             https=True, certMode="AUTO")

    assert ID_PATTERN.match(created["id"]), (
        f"domain id {created['id']!r} is not a 24-char NanoId")
    assert TIMESTAMP_PATTERN.match(created["createdTime"]), (
        f"createdTime {created['createdTime']!r} is not a naive local timestamp — "
        "a zone suffix means values are being converted rather than read verbatim")
    assert created["environment"] == environment, (
        "the domain was stored against a different environment than the one "
        "requested, which would let it govern hosts in the wrong cluster")
    assert created["hasUploadedCert"] is False, (
        "an AUTO domain reports an uploaded certificate, so the UI would offer "
        "to replace a certificate that does not exist")

    assert host in hosts_in_listing(client), (
        "a newly created domain is missing from GET /api/domains, so an "
        "administrator could not find the domain they just registered")

    fetched = client.get(f"/api/domains/{created['id']}").data

    # Everything but the timestamp has to match exactly, or the edit form loads
    # values other than the ones that were just saved.
    assert {key: value for key, value in fetched.items() if key != "createdTime"} \
           == {key: value for key, value in created.items() if key != "createdTime"}, (
        "reading a domain back by id produced a different representation than "
        "creating it did")

    # The timestamp differs in precision alone, which is worth stating rather
    # than glossing over. The create response carries the in-memory value with
    # nanoseconds; a read comes back from a datetime(6) column, rounded to
    # microseconds and with trailing zeros dropped, so `...725229968` becomes
    # `...72523`. Same instant, two renderings, and a client comparing them as
    # strings — a form diffing before and after a save — sees a change that did
    # not happen. Compare them as instants, and allow the rounding.
    from datetime import datetime

    def instant(value: str) -> datetime:
        # fromisoformat handles at most microseconds, so trim the nanoseconds.
        head, _, fraction = value.partition(".")
        return datetime.fromisoformat(f"{head}.{fraction[:6]:0<6}" if fraction
                                      else head)

    drift = abs((instant(fetched["createdTime"])
                 - instant(created["createdTime"])).total_seconds())
    assert drift <= 0.000001, (
        f"the two views disagree about when the domain was created: "
        f"{created['createdTime']} on create, {fetched['createdTime']} on read")

    renamed = unique_host("lifecycle-renamed")
    updated = client.put(f"/api/domains/{created['id']}", {
        "host": renamed,
        "description": "renamed by the acceptance suite",
        "https": False,
        "environment": environment,
    }).data
    assert updated["id"] == created["id"], (
        "the update minted a new identifier instead of editing the row in place")
    assert updated["host"] == renamed, "the host change was not stored"
    assert updated["description"] == "renamed by the acceptance suite"
    assert updated["https"] is False, "HTTPS was not switched off"
    assert updated["certMode"] is None, (
        "the certificate mode survived HTTPS being switched off, leaving the "
        "row claiming a policy it no longer applies")

    assert host not in hosts_in_listing(client), (
        "the old host is still listed after the rename, so the suffix stays "
        "claimed by a domain nobody can see")

    client.delete(f"/api/domains/{created['id']}")
    assert renamed not in hosts_in_listing(client), (
        "the deleted domain is still in the listing")

    orphaned = client.get(f"/api/domains/{created['id']}", expect_success=False)
    assert orphaned.success is False, (
        "a deleted domain can still be read back by id")


def test_reading_an_unknown_domain_fails_inside_the_envelope(client):
    """Unlike a missing application, a missing domain *is* an error.

    Worth pinning because the two conventions sit side by side in the same API:
    GET /api/namespaces/{ns}/applications/{name} answers success with null data,
    while GET /api/domains/{id} answers success=false. Both are HTTP 200.
    """
    result = client.get("/api/domains/definitely-not-a-domain",
                        expect_success=False)
    assert result.status == 200, f"expected HTTP 200, got {result.status}"
    assert result.success is False, (
        "an unknown domain now answers success — that is defensible, but it is "
        "the opposite of what this endpoint has always done")
    assert result.data is None
    assert result.message, "the failure carries no message"


def test_a_wildcard_prefix_is_stripped_on_the_way_in(client, environment_names,
                                                     domain_factory):
    """`*.example.com` and `example.com` are the same claim.

    A domain already governs every host beneath it, so the wildcard form adds
    nothing except a second spelling of one row. It is accepted for the sake of
    people who paste it out of a certificate, then normalised away.
    """
    environment = environment_names()
    host = unique_host("wildcard")

    created = domain_factory(f"*.{host}", environment)
    assert created["host"] == host, (
        f"the wildcard prefix survived normalisation as {created['host']!r}; "
        "stored that way it matches nothing, because suffix matching compares "
        "against the bare host")

    duplicate = client.post("/api/domains", {
        "host": host, "environment": environment, "https": False,
    }, expect_success=False)
    assert duplicate.success is False, (
        "the bare host was accepted alongside its own wildcard form, leaving two "
        "rows claiming one suffix and no defined winner")


def test_host_validation_rejects_what_cannot_be_routed(client, environment_names):
    environment = environment_names()
    for candidate, reason in (
        ("UPPERCASE.example.invalid", "upper case"),
        ("no-dots-at-all", "a single label"),
        ("", "an empty host"),
        ("has space.example.invalid", "a space"),
        ("under_score.example.invalid", "an underscore"),
        ("-leading.example.invalid", "a leading hyphen"),
    ):
        result = client.post("/api/domains", {
            "host": candidate, "environment": environment, "https": False,
        }, expect_success=False)
        assert result.success is False, (
            f"{candidate!r} was accepted as a domain host despite {reason}; an "
            "unroutable suffix silently governs nothing")


def test_a_domain_must_name_a_registered_environment(client):
    """The binding is the whole point of the row, so it cannot be left open.

    A domain with no environment would govern hosts in every cluster at once,
    which is exactly the confusion the environment column exists to prevent.
    """
    host = unique_host("unbound")

    unknown = client.post("/api/domains", {
        "host": host, "environment": "definitely-not-an-environment", "https": False,
    }, expect_success=False)
    assert unknown.success is False, (
        "a domain was bound to an environment that does not exist, so it would "
        "govern hosts nothing can ever deploy into")

    blank = client.post("/api/domains", {
        "host": host, "environment": "", "https": False,
    }, expect_success=False)
    assert blank.success is False, (
        "a domain was created with no environment at all")


def test_https_demands_a_certificate_policy(client, environment_names,
                                            domain_factory):
    environment = environment_names()

    without_mode = client.post("/api/domains", {
        "host": unique_host("nomode"), "environment": environment, "https": True,
    }, expect_success=False)
    assert without_mode.success is False, (
        "HTTPS was enabled with no certificate mode, leaving the router with no "
        "instruction about where the certificate comes from")

    without_material = client.post("/api/domains", {
        "host": unique_host("nopem"), "environment": environment,
        "https": True, "certMode": "UPLOADED",
    }, expect_success=False)
    assert without_material.success is False, (
        "UPLOADED mode was accepted with no certificate, so HTTPS would be "
        "promised and then fail at the router")

    half_supplied = client.post("/api/domains", {
        "host": unique_host("halfpem"), "environment": environment,
        "https": True, "certMode": "UPLOADED",
        "certPem": "-----BEGIN CERTIFICATE-----\nnot-a-certificate\n"
                   "-----END CERTIFICATE-----\n",
    }, expect_success=False)
    assert half_supplied.success is False, (
        "a certificate was accepted without its private key")

    automatic = domain_factory(unique_host("auto"), environment,
                               https=True, certMode="AUTO")
    assert automatic["certMode"] == "AUTO", (
        "AUTO mode was not stored, so Traefik would not be asked to obtain a "
        "certificate for this suffix")
    assert automatic["certSubject"] is None and automatic["certNotAfter"] is None, (
        "an AUTO domain carries uploaded-certificate metadata, which the "
        "expiry warnings in the UI would then read as a real certificate")


def test_duplicate_hosts_are_rejected(client, environment_names, domain_factory):
    environment = environment_names()
    other_environment = environment_names()
    host = unique_host("duplicate")

    domain_factory(host, environment)

    same_environment = client.post("/api/domains", {
        "host": host, "environment": environment, "https": False,
    }, expect_success=False)
    assert same_environment.success is False, (
        "the same host was registered twice in one environment")

    across_environments = client.post("/api/domains", {
        "host": host, "environment": other_environment, "https": False,
    }, expect_success=False)
    assert across_environments.success is False, (
        "the same host was claimed by two environments, which makes the "
        "environment a host resolves to depend on row order")


def test_the_longest_matching_suffix_governs_a_host(client, namespace, application,
                                                    environment_names,
                                                    domain_factory):
    """Nested domains are resolved most-specific-first, not first-match.

    There is no endpoint that reports the governing domain directly, so this
    drives the rule through its only observable consumer: saving an application
    host. `outer` and `inner.outer` are bound to different environments, and a
    host beneath `inner.outer` is governed by the inner one — the environment
    the save is checked against, and the environment named in the refusal.
    """
    outer_environment = environment_names("acc-outer")
    inner_environment = environment_names("acc-inner")

    outer_host = unique_host("outer")
    inner_host = f"inner.{outer_host}"
    application_host = f"app.{inner_host}"

    domain_factory(outer_host, outer_environment)
    domain_factory(inner_host, inner_environment)

    misbound = client.put(
        f"/api/namespaces/{namespace}/applications/{application}/service",
        {
            "namespace": namespace,
            "applicationName": application,
            "port": 8080,
            "internalPorts": [],
            "environmentConfigs": [{
                "environment": outer_environment,
                "host": application_host,
                "https": False,
            }],
        },
        expect_success=False)
    assert misbound.success is False, (
        "a host beneath the inner domain was published into the outer domain's "
        "environment, so the shorter suffix won the match")
    assert inner_host in (misbound.message or ""), (
        f"the refusal names {misbound.message!r} rather than the inner domain; "
        "the outer domain also matches this host, and reporting it would send "
        "the administrator to edit the wrong row")

    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 8080,
        "internalPorts": [],
        "environmentConfigs": [{
            "environment": inner_environment,
            "host": application_host,
            "https": False,
        }],
    })

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/service").data
    assert stored["environmentConfigs"][0]["host"] == application_host, (
        "the accepted host was not stored, so the rule allowed a save that "
        "then did nothing")


def test_a_domain_in_use_cannot_be_moved_out_from_under_its_applications(
        client, namespace, application, environment_names, domain_factory):
    """The conflict has to surface on the edit that creates it.

    Rebinding a domain to another environment, or narrowing its suffix, would
    otherwise leave saved application hosts governed by an environment they
    cannot deploy into — and the failure would land on some unrelated colleague's
    next deploy rather than on the administrator who caused it.
    """
    environment = environment_names("acc-inuse")
    elsewhere = environment_names("acc-elsewhere")

    host = unique_host("inuse")
    domain = domain_factory(host, environment)

    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 8080,
        "internalPorts": [],
        "environmentConfigs": [{
            "environment": environment,
            "host": f"app.{host}",
            "https": False,
        }],
    })

    rebound = client.put(f"/api/domains/{domain['id']}", {
        "host": host, "environment": elsewhere, "https": False,
    }, expect_success=False)
    assert rebound.success is False, (
        "a domain governing a saved application host was moved to another "
        "environment, stranding that host")
    assert application in (rebound.message or ""), (
        f"the refusal {rebound.message!r} does not name the application holding "
        "the domain, leaving the administrator nothing to act on")

    narrowed = client.put(f"/api/domains/{domain['id']}", {
        "host": f"other.{host}", "environment": environment, "https": False,
    }, expect_success=False)
    assert narrowed.success is False, (
        "the domain's suffix was narrowed until it no longer covered the host "
        "it was governing")

    client.put(f"/api/domains/{domain['id']}", {
        "host": host,
        "description": "still in use, edited in place",
        "https": False,
        "environment": environment,
    })
    unchanged = client.get(f"/api/domains/{domain['id']}").data
    assert unchanged["description"] == "still in use, edited in place", (
        "an edit that left the host and environment alone was refused, so a "
        "domain in use could never be described or given a certificate")
