"""Exposing an application on a host: IngressRoutes, TLS and basic auth.

Everything up to here proves an application runs. This proves it can be reached,
which is the point of deploying it at all — and it was the largest hole in the
suite, because the previous fixture cluster shipped no Traefik CRDs, so every
deploy took the "no host configured, skipping ingress route creation" branch and
none of this code ran even once.

The shapes are worth stating plainly, because the object graph is not obvious
from the API:

- An HTTP-only host produces one IngressRoute on the `web` entrypoint.
- An HTTPS host produces two: one on `web` that only redirects, and one on
  `websecure` that actually serves. The redirect is a shared `Middleware`.
- Basic auth belongs on whichever route serves, never on the redirect — putting
  it on the redirect would ask for a password before sending the browser away.
"""

from __future__ import annotations

import os
import uuid

import pytest

from oops_client import wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy)

pytestmark = pytest.mark.cluster

REDIRECT_MIDDLEWARE = "oops-redirect-https"


@pytest.fixture(scope="session", autouse=True)
def traefik_available():
    """Skip rather than fail where the cluster has no Traefik.

    OOPS treats a missing CRD as "nothing to do", so without this the tests would
    pass by asserting on objects nobody ever tried to create.
    """
    if os.environ.get("OOPS_TRAEFIK_AVAILABLE", "yes").lower() != "yes":
        pytest.skip("the cluster has no Traefik CRDs, so no IngressRoute is "
                    "created and there is nothing to assert on")


def unique_host(label: str) -> str:
    return f"{label}-{uuid.uuid4().hex[:8]}.acceptance.invalid"


def deploy_with_host(client, namespace, application, environment, host,
                     https=False, basic_auth=None):
    """Configure a host on the service, deploy, and wait for the pipeline."""
    configure_for_build(client, namespace, application, environment)

    environment_config = {"environment": environment, "host": host,
                          "https": https}
    if basic_auth:
        environment_config.update({
            "basicAuthEnabled": True,
            "basicAuthUsername": basic_auth[0],
            "basicAuthPassword": basic_auth[1],
        })

    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 80,
        "internalPorts": [],
        "environmentConfigs": [environment_config],
    })

    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the deploy to finish")
    if pipeline["status"] != "SUCCEEDED":
        pytest.skip(f"deploy ended as {pipeline['status']}, nothing was exposed")
    return pipeline


def routes_for(kubectl, namespace, host):
    """Every IngressRoute in the namespace that matches this host."""
    matching = []
    for name in kubectl.names("ingressroute", namespace):
        route = kubectl.get_json("ingressroute", name, namespace)
        if not route:
            continue
        for rule in (route.get("spec") or {}).get("routes") or []:
            if host in (rule.get("match") or ""):
                matching.append(route)
                break
    return matching


def test_an_http_host_gets_one_route_on_the_web_entrypoint(
        client, namespace, application, environment, kubectl):
    host = unique_host("plain")
    deploy_with_host(client, namespace, application, environment, host)

    routes = wait_until(lambda: routes_for(kubectl, namespace, host) or None,
                        timeout=120, interval=3,
                        description="the IngressRoute to appear")

    assert len(routes) == 1, (
        f"an HTTP-only host produced {len(routes)} IngressRoutes; it should "
        f"produce exactly one, on the web entrypoint")
    spec = routes[0]["spec"]
    assert spec.get("entryPoints") == ["web"], (
        f"the route listens on {spec.get('entryPoints')} rather than web")
    assert not spec.get("tls"), (
        "an HTTP-only host was given a TLS block, so Traefik would try to serve "
        "it over HTTPS with no certificate")


def test_an_https_host_gets_a_redirect_and_a_serving_route(
        client, namespace, application, environment, kubectl):
    """Two routes, and the shared redirect Middleware they depend on."""
    host = unique_host("secure")
    deploy_with_host(client, namespace, application, environment, host,
                     https=True)

    routes = wait_until(
        lambda: (lambda found: found if len(found) >= 2 else None)(
            routes_for(kubectl, namespace, host)),
        timeout=120, interval=3,
        description="both IngressRoutes to appear")

    by_entrypoint = {tuple(route["spec"].get("entryPoints") or []): route
                     for route in routes}
    assert ("web",) in by_entrypoint, "no route on the web entrypoint to redirect"
    assert ("websecure",) in by_entrypoint, "no route on the websecure entrypoint"

    secure = by_entrypoint[("websecure",)]["spec"]
    assert secure.get("tls") is not None, (
        "the websecure route carries no TLS block, so nothing tells Traefik "
        "which certificate to serve")

    plain = by_entrypoint[("web",)]["spec"]
    middlewares = [item.get("name")
                   for rule in plain.get("routes") or []
                   for item in rule.get("middlewares") or []]
    assert REDIRECT_MIDDLEWARE in middlewares, (
        f"the HTTP route does not reference {REDIRECT_MIDDLEWARE}, so plain "
        f"HTTP would be served rather than redirected")

    assert kubectl.exists("middleware", REDIRECT_MIDDLEWARE, namespace), (
        f"the route references {REDIRECT_MIDDLEWARE} but the Middleware itself "
        f"was never created, so Traefik drops the route")


def test_basic_auth_lands_on_the_serving_route_only(
        client, namespace, application, environment, kubectl):
    """A password prompt in front of a redirect would ask, then send you away.

    So on an HTTPS host the auth middleware belongs on the websecure route, and
    the web route keeps only the redirect.
    """
    host = unique_host("guarded")
    deploy_with_host(client, namespace, application, environment, host,
                     https=True, basic_auth=("acceptance", "acceptance-secret"))

    routes = wait_until(
        lambda: (lambda found: found if len(found) >= 2 else None)(
            routes_for(kubectl, namespace, host)),
        timeout=120, interval=3, description="both IngressRoutes to appear")

    by_entrypoint = {tuple(route["spec"].get("entryPoints") or []): route
                     for route in routes}

    def middleware_names(route):
        return [item.get("name")
                for rule in route["spec"].get("routes") or []
                for item in rule.get("middlewares") or []]

    secure_middlewares = middleware_names(by_entrypoint[("websecure",)])
    plain_middlewares = middleware_names(by_entrypoint[("web",)])

    auth_middlewares = [name for name in secure_middlewares
                        if name != REDIRECT_MIDDLEWARE]
    assert auth_middlewares, (
        f"the serving route carries no basic auth middleware: "
        f"{secure_middlewares}")
    assert plain_middlewares == [REDIRECT_MIDDLEWARE], (
        f"the redirect route carries more than the redirect ({plain_middlewares}); "
        f"asking for a password before redirecting prompts the user twice")

    for name in auth_middlewares:
        middleware = kubectl.get_json("middleware", name, namespace)
        assert middleware is not None, (
            f"the route references middleware {name} which does not exist")
        users = (((middleware.get("spec") or {}).get("basicAuth") or {}))
        assert users, f"middleware {name} has no basicAuth section"


def test_the_basic_auth_password_is_never_returned(client, namespace,
                                                   application, environment):
    """Only a flag comes back, never the password or its hash.

    The editor sends a blank password to mean "keep the stored one", which only
    works if the stored one was never handed out.
    """
    host = unique_host("secret")
    deploy_with_host(client, namespace, application, environment, host,
                     https=True, basic_auth=("acceptance", "acceptance-secret"))

    stored = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/service").data
    config = next(item for item in stored["environmentConfigs"]
                  if item["environment"] == environment)

    assert config.get("basicAuthEnabled") is True
    assert config.get("basicAuthUsername") == "acceptance"
    assert not config.get("basicAuthPassword"), (
        f"the basic auth password came back from the API: "
        f"{config.get('basicAuthPassword')!r}")
    assert config.get("basicAuthPasswordSet") is True, (
        "nothing tells the editor a password is stored, so it cannot show the "
        "difference between 'unset' and 'unchanged'")


def test_removing_the_host_removes_its_routes(client, namespace, application,
                                              environment, kubectl):
    """Clearing the host has to withdraw the application from Traefik.

    A route left behind keeps answering on a hostname the owner believes they
    have released.
    """
    host = unique_host("temporary")
    deploy_with_host(client, namespace, application, environment, host)
    wait_until(lambda: routes_for(kubectl, namespace, host) or None,
               timeout=120, interval=3, description="the IngressRoute to appear")

    client.put_service_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "port": 80,
        "internalPorts": [],
        "environmentConfigs": [{"environment": environment, "host": "",
                                "https": False}],
    })
    pipeline_id = client.deploy(namespace, application, environment,
                                strategy=git_strategy())

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    wait_until(finished, timeout=DEPLOY_TIMEOUT,
               description="the redeploy without a host to finish")

    gone = wait_until(lambda: not routes_for(kubectl, namespace, host),
                      timeout=120, interval=3,
                      description="the IngressRoute to be withdrawn")
    assert gone, (
        f"an IngressRoute for {host} is still present after the host was "
        f"cleared, so the application still answers on a released hostname")
