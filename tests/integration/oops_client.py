"""HTTP and WebSocket client for the OOPS API.

Deliberately knows nothing about the backend implementation — it speaks only the
wire protocol, so the same suite validates the current Java service, a Go or Rust
rewrite, or a GraalVM native build without a single change.
"""

from __future__ import annotations

import json
import os
import time
import urllib.parse
from dataclasses import dataclass
from typing import Any, Callable, Iterator, Optional

import requests
import websocket

import runlog

DEFAULT_TIMEOUT = 30
# How often a long wait says it is still waiting. Long enough not to bury the
# module it is reported under, short enough to tell a slow deploy from a hang.
HEARTBEAT_SECONDS = 15


class ApiError(AssertionError):
    """A call that came back with success=false, or an unexpected HTTP status."""


@dataclass
class Response:
    """One decoded `Result` envelope: {success, message, data}."""

    success: bool
    message: Optional[str]
    data: Any
    status: int


# Every (method, path) the suite touches, across all client instances. The
# coverage test compares this against the route table extracted from the
# controllers, which is what makes "the scenarios cover every endpoint" a
# checkable property rather than a claim.
CALLED: set[tuple[str, str]] = set()


def record(method: str, path: str) -> None:
    CALLED.add((method.upper(), path.split("?", 1)[0]))


class OopsClient:
    def __init__(self, endpoint: str, token: Optional[str] = None,
                 timeout: int = DEFAULT_TIMEOUT):
        self.endpoint = endpoint.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.session = requests.Session()

    # -- auth ---------------------------------------------------------------

    def login(self, username: str, password: str) -> str:
        result = self.post("/api/auth/login",
                           {"username": username, "password": password},
                           authenticated=False)
        self.token = result.data["token"]
        return self.token

    def _headers(self, authenticated: bool) -> dict:
        headers = {"Content-Type": "application/json"}
        if authenticated and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    # -- raw verbs ----------------------------------------------------------

    def raw(self, method: str, path: str, body: Any = None,
            authenticated: bool = True, files: Any = None) -> requests.Response:
        """The underlying HTTP response, envelope unchecked.

        Needed for the cases where the envelope is deliberately absent — rejected
        authentication is answered by the servlet filter with a bare status, and
        a file download answers with bytes.
        """
        record(method, path)
        headers = self._headers(authenticated)
        if files is not None:
            # requests has to pick the multipart boundary itself, which it only
            # does when nothing has already claimed the Content-Type header.
            headers.pop("Content-Type", None)
        return self.session.request(
            method, self.endpoint + path,
            data=json.dumps(body) if body is not None else None,
            files=files,
            headers=headers,
            timeout=self.timeout,
        )

    def request(self, method: str, path: str, body: Any = None,
                authenticated: bool = True, expect_success: bool = True,
                files: Any = None) -> Response:
        raw = self.raw(method, path, body, authenticated, files)
        try:
            payload = raw.json()
        except ValueError:
            raise ApiError(
                f"{method} {path} returned {raw.status_code} with a non-JSON body: "
                f"{raw.text[:400]!r}"
            )

        # Every endpoint is contracted to answer with the envelope, including errors.
        for key in ("success", "message", "data"):
            if key not in payload:
                raise ApiError(
                    f"{method} {path} returned {raw.status_code} without the "
                    f"'{key}' envelope field: {payload!r}"
                )

        response = Response(
            success=payload["success"],
            message=payload["message"],
            data=payload["data"],
            status=raw.status_code,
        )
        if expect_success and not response.success:
            raise ApiError(f"{method} {path} failed: {response.message}")
        return response

    def get(self, path: str, **kwargs) -> Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, body: Any = None, **kwargs) -> Response:
        return self.request("POST", path, body, **kwargs)

    def put(self, path: str, body: Any = None, **kwargs) -> Response:
        return self.request("PUT", path, body, **kwargs)

    def delete(self, path: str, **kwargs) -> Response:
        return self.request("DELETE", path, **kwargs)

    # -- resource helpers ---------------------------------------------------

    def create_application(self, namespace: str, name: str,
                           description: str = "integration test") -> str:
        return self.post(
            f"/api/namespaces/{namespace}/applications",
            {"name": name, "description": description, "namespace": namespace},
        ).data

    def delete_application(self, namespace: str, name: str,
                           expect_success: bool = True) -> Response:
        return self.delete(
            f"/api/namespaces/{namespace}/applications/{name}",
            expect_success=expect_success,
        )

    def get_application(self, namespace: str, name: str, **kwargs) -> Response:
        return self.get(f"/api/namespaces/{namespace}/applications/{name}", **kwargs)

    def put_build_config(self, namespace: str, name: str, config: dict) -> Response:
        return self.put(
            f"/api/namespaces/{namespace}/applications/{name}/build/config", config)

    def put_service_config(self, namespace: str, name: str, config: dict) -> Response:
        return self.put(
            f"/api/namespaces/{namespace}/applications/{name}/service", config)

    def put_runtime_spec(self, namespace: str, name: str, spec: dict) -> Response:
        return self.put(
            f"/api/namespaces/{namespace}/applications/{name}/runtime-spec", spec)

    def put_environment_bindings(self, namespace: str, name: str,
                                 bindings: list) -> Response:
        return self.put(
            f"/api/namespaces/{namespace}/applications/{name}/environments",
            bindings)

    def create_source_upload(self, namespace: str, name: str, file_name: str,
                             size: int,
                             content_type: str = "application/zip") -> dict:
        """Ask for a presigned PUT to upload a ZIP source bundle to object storage."""
        return self.post(
            f"/api/namespaces/{namespace}/applications/{name}"
            f"/deployments/source-upload",
            {"fileName": file_name, "fileSize": size, "contentType": content_type},
        ).data

    def put_bytes(self, url: str, payload: bytes, headers: dict) -> int:
        """Upload straight to the presigned URL, bypassing the API entirely.

        Every other party — the backend, the pipeline pods — lives in Docker and
        resolves the signed hostname directly. The test process does not, so it
        connects to the published port while keeping the Host header pointed at
        the name the URL was signed for. SigV4 covers that header, not the
        address the socket went to.
        """
        target = url
        override = os.environ.get("OOPS_OBJECT_STORAGE_HOST_OVERRIDE")
        extra = dict(headers)
        if override:
            parsed = urllib.parse.urlsplit(url)
            if parsed.netloc != override:
                extra.setdefault("Host", parsed.netloc)
                target = urllib.parse.urlunsplit(parsed._replace(netloc=override))

        response = requests.put(target, data=payload, headers=extra,
                                timeout=self.timeout)
        return response.status_code

    def deploy(self, namespace: str, name: str, environment: str,
               deploy_mode: str = "IMMEDIATE",
               strategy: Optional[dict] = None) -> str:
        """Trigger a deploy and return the pipeline id.

        `strategy` is polymorphic on its `type` field: {"type": "GIT", "branch": …}
        or {"type": "ZIP", "objectKey": …}.
        """
        return self.post(
            f"/api/namespaces/{namespace}/applications/{name}/deployments",
            {
                "environment": environment,
                "deployMode": deploy_mode,
                "strategy": strategy or {"type": "GIT", "branch": ""},
            },
        ).data

    def get_pipeline(self, namespace: str, name: str, pipeline_id: str) -> dict:
        return self.get(
            f"/api/namespaces/{namespace}/applications/{name}/pipelines/{pipeline_id}"
        ).data

    def list_environments(self) -> list:
        return self.get("/api/environments").data

    # -- websocket ----------------------------------------------------------

    def sse(self, path: str, timeout: int = 60,
            last_event_id: Optional[str] = None) -> Iterator[tuple[str, Optional[str], str]]:
        """Open a server-sent event stream and yield its (event, id, data) triples.

        The browser reaches these streams with EventSource, which cannot set
        headers, so the server also accepts the JWT as a cookie; a plain client
        can still send the bearer header, and does. `last_event_id` plays the
        Last-Event-ID a browser sends on its own reconnect.
        """
        record("GET", path)
        headers = self._headers(True)
        headers["Accept"] = "text/event-stream"
        headers.pop("Content-Type", None)
        if last_event_id is not None:
            headers["Last-Event-ID"] = last_event_id
        response = self.session.get(self.endpoint + path, headers=headers,
                                    stream=True, timeout=(10, timeout))
        if response.status_code != 200:
            body = response.text[:400]
            response.close()
            raise ApiError(f"GET {path} answered {response.status_code}: {body!r}")
        return iter_sse(response, timeout)

    def websocket(self, path: str, timeout: int = 60) -> websocket.WebSocket:
        """Open a socket. WebSocket auth travels as ?token= — headers are not
        available to the browser client, so the server accepts the query form."""
        record("WS", path)
        scheme = "wss" if self.endpoint.startswith("https") else "ws"
        base = self.endpoint.split("://", 1)[1]
        separator = "&" if "?" in path else "?"
        url = f"{scheme}://{base}{path}{separator}token={urllib.parse.quote(self.token or '')}"
        connection = websocket.create_connection(url, timeout=timeout)
        return connection


def wait_until(predicate: Callable[[], Any], timeout: int = 300, interval: float = 3.0,
               description: str = "condition") -> Any:
    """Poll until the predicate returns something truthy, then return it.

    Deploys run as Kubernetes Jobs behind 5s scan loops, so anything touching a
    pipeline needs minutes of patience rather than seconds. That is long enough
    to look like a hang, so the wait says what it is waiting for every so often;
    with no renderer listening the report goes nowhere.
    """
    started = time.time()
    deadline = started + timeout
    announced = started
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        if time.time() - announced >= HEARTBEAT_SECONDS:
            announced = time.time()
            runlog.note(f"waiting for {description} "
                        f"({int(time.time() - started)}s of {timeout}s)")
        time.sleep(interval)
    raise AssertionError(
        f"timed out after {timeout}s waiting for {description}; last value: {last!r}")


def iter_sse(response: requests.Response, timeout: int = 60) -> Iterator[tuple[str, Optional[str], str]]:
    """Yield (event, id, data) from a server-sent event response until the server
    ends the stream or the timeout expires.

    Heartbeats are yielded like any other event; a test that does not care can
    filter them. Both pipeline streams close with an `end` event, so a reader that
    stops there never waits on the browser-side reconnect the server is written
    for.
    """
    deadline = time.time() + timeout
    event, event_id, data = "message", None, []
    try:
        for line in response.iter_lines(decode_unicode=True):
            if time.time() > deadline:
                return
            if line is None:
                continue
            if line == "":
                if data or event != "message":
                    yield event, event_id, "\n".join(data)
                event, event_id, data = "message", None, []
                continue
            if line.startswith(":"):
                continue
            field, _, value = line.partition(":")
            value = value[1:] if value.startswith(" ") else value
            if field == "event":
                event = value
            elif field == "id":
                event_id = value
            elif field == "data":
                data.append(value)
    except requests.exceptions.ConnectionError:
        return
    finally:
        response.close()


def read_until_closed(connection: websocket.WebSocket,
                      timeout: int = 60) -> Iterator[str]:
    """Yield text frames until the peer closes or the timeout expires.

    Log sockets answer a text "ping" with "pong"; terminal sockets must not be
    pinged, because their stdin is live.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            frame = connection.recv()
        except websocket.WebSocketTimeoutException:
            return
        except websocket.WebSocketConnectionClosedException:
            return
        if frame is None or frame == "":
            return
        if isinstance(frame, bytes):
            frame = frame.decode("utf-8", errors="replace")
        yield frame
