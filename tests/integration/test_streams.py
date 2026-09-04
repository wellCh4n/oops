"""Streams: pipeline build logs over SSE, pod logs and the pod terminal over WebSocket.

These carry the log tailing and exec multiplexing that the Kubernetes gateways
implement, and they have their own contracts that are easy to get subtly wrong
on a reimplementation. The pipeline streams are server-sent events that must
end explicitly — a browser EventSource reconnects on its own otherwise — and
must not replay a step on reconnect. The sockets have the ping asymmetry: the
pod log socket answers a text "ping" with "pong", the terminal socket must not,
because its stdin is live and a stray "pong" would be typed into the user's
shell.
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

    Every test here needs the same thing — a running workload with a finished
    pipeline behind it — and a build takes about ninety seconds, so deploying per
    test would spend minutes proving the same precondition over and over. None
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


def pipeline_path(namespace, application, pipeline_id):
    return (f"/api/namespaces/{namespace}/applications/{application}"
            f"/pipelines/{pipeline_id}")


def test_step_watch_reports_every_step_of_a_finished_build(
        client, namespace, environment, deployed_application):
    """The watch opens with the step list, follows the build pod with status
    snapshots, and ends by itself once the build is over — a finished build gets
    the whole story in one go rather than a stream that never closes."""
    application, pipeline_id = deployed_application
    events = list(client.sse(
        f"{pipeline_path(namespace, application, pipeline_id)}/steps/watch",
        timeout=60))
    names = [event for event, _, _ in events]

    assert names and names[0] == "steps", (
        f"the watch did not open with the step list: {names}")
    assert names[-1] == "end", f"the watch of a finished build did not end: {names}"
    assert "error" not in names, [data for event, _, data in events if event == "error"]

    steps = json.loads(events[0][2])
    assert steps and steps[-1] == "done", (
        f"steps are the build containers in order, ending with `done`: {steps}")

    snapshots = [json.loads(data) for event, _, data in events if event == "status"]
    assert snapshots, "no status snapshot was sent"
    final = snapshots[-1]
    assert final["phase"] == "Succeeded", final
    assert [step["name"] for step in final["steps"]] == steps
    assert all(step["state"] == "SUCCEEDED" for step in final["steps"]), final["steps"]


def test_step_log_replays_a_finished_step_and_does_not_replay_it_on_reconnect(
        client, namespace, environment, deployed_application):
    """A finished step's log is replayed in stamped batches and the stream ends;
    a reconnect carrying the last event id, as a browser sends on its own, gets
    nothing it has already seen."""
    application, pipeline_id = deployed_application
    base = pipeline_path(namespace, application, pipeline_id)
    steps = json.loads(list(client.sse(f"{base}/steps/watch", timeout=60))[0][2])
    first_step = steps[0]

    events = list(client.sse(f"{base}/log?container={first_step}", timeout=60))
    names = [event for event, _, _ in events]
    assert names and names[-1] == "end", f"the log of a finished step did not end: {names}"
    assert "error" not in names, [data for event, _, data in events if event == "error"]

    batches = [(event_id, json.loads(data)) for event, event_id, data in events
               if event == "log"]
    assert batches, f"the `{first_step}` step replayed no log at all"
    lines = [line for _, batch in batches for line in batch["lines"]]
    assert lines, "a log batch carried no lines"
    for line in lines:
        assert line.get("time"), f"a line without a timestamp: {line!r}"
        assert "text" in line, f"a line without text: {line!r}"
    last_id = batches[-1][0]
    assert last_id, "log batches carry the last stamped time as their event id"

    resumed = list(client.sse(f"{base}/log?container={first_step}", timeout=60,
                              last_event_id=last_id))
    assert [event for event, _, _ in resumed][-1] == "end"
    replayed = [line["time"] for event, _, data in resumed if event == "log"
                for line in json.loads(data)["lines"]]
    already_seen = {line["time"] for line in lines}
    assert not already_seen.intersection(replayed), (
        "a reconnect with Last-Event-ID replayed lines the client had already "
        f"received: {sorted(already_seen.intersection(replayed))[:5]}")


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


def test_pod_log_socket_answers_ping_with_pong(client, namespace, environment,
                                               deployed_application):
    """The browser keepalive depends on the log socket answering a text ping."""
    application, _ = deployed_application
    pod = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod to appear")

    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}/pods/{pod}/log")
    try:
        socket.send("ping")
        frames = []
        for frame in read_until_closed(socket, timeout=15):
            frames.append(frame)
            if frame == "pong":
                break
        assert "pong" in frames, (
            "the pod log socket did not answer a text ping with pong; the browser "
            f"keepalive depends on it. Frames seen: {frames[:5]}")
    finally:
        socket.close()


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
