"""WebSocket streams: pipeline logs, pod logs, and the pod terminal.

These sockets carry the log tailing and exec multiplexing that the Kubernetes
gateways implement, and they have their own contract that is easy to get subtly
wrong on a reimplementation — in particular the ping asymmetry: log sockets
answer a text "ping" with "pong", terminal sockets must not, because their stdin
is live and a stray "pong" would be typed into the user's shell.
"""

from __future__ import annotations

import json
import uuid

import pytest

from oops_client import read_until_closed, wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy)

pytestmark = pytest.mark.cluster


@pytest.fixture(scope="module")
def deployed_application(client, namespace, environment):
    """One application, deployed once, shared by every test in this file.

    All four tests need the same thing — a running workload with a finished
    pipeline behind it — and a build takes about ninety seconds, so deploying per
    test would spend six minutes proving the same precondition four times. None
    of these tests mutate the application, so sharing is safe.
    """
    application = f"stream-{uuid.uuid4().hex[:8]}"
    client.create_application(namespace, application)
    try:
        configure_for_build(client, namespace, application, environment)
        pipeline_id = client.deploy(namespace, application, environment,
                                    strategy=git_strategy())

        def finished():
            pipeline = client.get_pipeline(namespace, application, pipeline_id)
            return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

        pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                              description="the fixture deploy to finish")
        if pipeline["status"] != "SUCCEEDED":
            pytest.skip(
                f"fixture deploy ended as {pipeline['status']}, cannot stream")
        yield application, pipeline_id
    finally:
        try:
            client.delete_application(namespace, application,
                                      expect_success=False)
        except Exception:
            pass


def first_running_pod(client, namespace, application, environment):
    """Name of a running pod, via the application status endpoint.

    Pods are listed under `/status`, not under a `/pods` collection — the pod
    path segment only exists for operations on a pod already known by name.
    """
    result = client.get(
        f"/api/namespaces/{namespace}/applications/{application}/status"
        f"?environment={environment}",
        expect_success=False,
    )
    if not result.success or not result.data:
        return None
    for pod in result.data:
        if pod.get("name") and pod.get("status") == "Running":
            return pod["name"]
    return None


def test_pipeline_log_frames_are_tagged_json(client, namespace, environment,
                                             deployed_application):
    application, pipeline_id = deployed_application
    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/pipelines/{pipeline_id}/log")
    try:
        frames = []
        for frame in read_until_closed(socket, timeout=30):
            frames.append(frame)
            if len(frames) >= 5:
                break
    finally:
        socket.close()

    assert frames, "the pipeline log socket delivered nothing"
    for frame in frames:
        payload = json.loads(frame)
        assert "type" in payload, f"frame without a type field: {frame[:200]}"
        assert payload["type"] in ("steps", "step", "error"), (
            f"unexpected frame type {payload['type']!r}")


def test_log_socket_answers_ping_with_pong(client, namespace, environment,
                                           deployed_application):
    application, pipeline_id = deployed_application
    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/pipelines/{pipeline_id}/log")
    try:
        socket.send("ping")
        deadline_frames = []
        for frame in read_until_closed(socket, timeout=15):
            deadline_frames.append(frame)
            if frame == "pong":
                break
        assert "pong" in deadline_frames, (
            "the log socket did not answer a text ping with pong; the browser "
            f"keepalive depends on it. Frames seen: {deadline_frames[:5]}")
    finally:
        socket.close()


def test_pod_log_socket_streams_text_lines(client, namespace, environment,
                                           deployed_application):
    application, _ = deployed_application
    pod = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod to appear")

    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}/pods/{pod}/log")
    try:
        frames = [frame for _, frame in zip(range(3), read_until_closed(socket, 20))]
    finally:
        socket.close()

    # Content depends on the image, so assert the transport rather than the text:
    # lines arrive as text frames and are split, never glued together by a redraw.
    for frame in frames:
        assert isinstance(frame, str)
        assert "\n" not in frame.rstrip("\n"), (
            f"a log frame carried an embedded newline, lines are not being split: "
            f"{frame[:200]!r}")


def test_terminal_socket_does_not_answer_ping(client, namespace, environment,
                                              deployed_application):
    """The mirror image of the log-socket test: a terminal must stay silent, or
    the keepalive ends up as characters in the user's shell."""
    application, _ = deployed_application
    pod = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod to appear")

    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}/pods/{pod}/terminal")
    try:
        socket.send("ping")
        answered = [frame for frame in read_until_closed(socket, timeout=8)
                    if frame.strip() == "pong"]
        assert not answered, (
            "the terminal socket answered a ping with pong; that text would be "
            "delivered into the live shell")
    finally:
        socket.close()
