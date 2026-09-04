"""HTTP, WebSocket and server-sent event client for the OOPS API.

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

    # -- server-sent events -------------------------------------------------

    def sse(self, path: str, timeout: int = 60,
            headers: Optional[dict] = None) -> Iterator[SseEvent]:
        """Open an event stream and yield its events until the server ends the
        response or `timeout` seconds pass with nothing arriving.

        A GET like any other, so it is recorded as one for coverage. `headers`
        is for what a browser's EventSource sends on its own — `Last-Event-ID`
        on a reconnect — since that is the only input these streams take beyond
        the URL.
        """
        record("GET", path)
        request_headers = {"Accept": "text/event-stream"}
        if self.token:
            request_headers["Authorization"] = f"Bearer {self.token}"
        if headers:
            request_headers.update(headers)
        response = self.session.get(self.endpoint + path, headers=request_headers,
                                    stream=True, timeout=timeout)
        try:
            if response.status_code != 200:
                raise ApiError(
                    f"GET {path} returned {response.status_code} instead of an "
                    f"event stream: {response.text[:400]!r}")
            content_type = response.headers.get("Content-Type", "")
            if not content_type.startswith("text/event-stream"):
                raise ApiError(
                    f"GET {path} answered with {content_type!r}, not text/event-stream")
            yield from parse_sse(response.iter_lines())
        finally:
            response.close()


@dataclass
class SseEvent:
    """One server-sent event: the `event:` name, the `id:` if the server set one,
    and the `data:` lines joined back together."""

    event: str
    id: Optional[str]
    data: str


def parse_sse(lines: Iterator[bytes]) -> Iterator[SseEvent]:
    """Decode the wire format: `field:value` lines, a blank line ends an event,
    a leading `:` is a comment. Yields events in arrival order."""
    event, event_id, data = "message", None, []
    for raw in lines:
        line = raw.decode("utf-8", errors="replace")
        if line == "":
            if data or event != "message":
                yield SseEvent(event, event_id, "\n".join(data))
            event, event_id, data = "message", None, []
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if field == "event":
            event = value
        elif field == "id":
            event_id = value
        elif field == "data":
            data.append(value)


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


def read_until_closed(connection: websocket.WebSocket,
                      timeout: int = 60) -> Iterator[str]:
    """Yield text frames until the peer closes or the timeout expires.

    Log sockets answer a text "ping" with "pong"; terminal sockets must not be
    pinged, because their stdin is live.

    Frames are read one at a time rather than through `recv()`, which swallows
    control frames and waits on for a data frame with a fresh timeout each time
    — and every handler sends a protocol-level ping every few seconds, so an
    idle socket would never time out. The pings are answered here instead, and
    the deadline is the wall clock.
    """
    deadline = time.time() + timeout
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            return
        connection.settimeout(remaining)
        try:
            frame = connection.recv_frame()
        except websocket.WebSocketTimeoutException:
            return
        except websocket.WebSocketConnectionClosedException:
            return
        if frame is None or frame.opcode == websocket.ABNF.OPCODE_CLOSE:
            return
        if frame.opcode == websocket.ABNF.OPCODE_PING:
            connection.pong(frame.data)
            continue
        if frame.opcode not in (websocket.ABNF.OPCODE_TEXT,
                                websocket.ABNF.OPCODE_BINARY):
            continue
        data = frame.data
        if isinstance(data, bytes):
            data = data.decode("utf-8", errors="replace")
        if data == "":
            continue
        yield data
