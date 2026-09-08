"""Streams: pipeline step status and logs over SSE, pod logs and the pod terminal.

These carry the log tailing and exec multiplexing that the Kubernetes gateways
implement, and they have their own contract that is easy to get subtly wrong on
a reimplementation. The pipeline streams are server-sent events driven by a
browser EventSource, so every one of them must end with an `end` event — the
browser reconnects on its own after a closed response, and without `end` a
finished step would be refetched forever — and a reconnect carries the last
event id back, after which nothing may be replayed. The sockets have the ping
asymmetry: the pod log socket answers a text "ping" with "pong", the terminal
must not, because its stdin is live and a stray "pong" would be typed into the
user's shell.
"""

from __future__ import annotations

import json
import uuid

import pytest

from oops_client import read_until_closed, wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy, require_successful_deploy)

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
        require_successful_deploy(client, pipeline, "the pipeline and pod to stream")
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


def pipeline_events(client, namespace, application, pipeline_id, suffix,
                    headers=None, timeout=60):
    """Every event of one pipeline stream, heartbeats dropped.

    Read to the end rather than to a count: on a finished build both streams
    end by themselves, and the `end` event is the part of the contract that
    matters most, so a stream that never sends it fails here on the timeout.
    """
    path = (f"/api/namespaces/{namespace}/applications/{application}"
            f"/pipelines/{pipeline_id}/{suffix}")
    return [event for event in client.sse(path, timeout=timeout, headers=headers)
            if event.event != "heartbeat"]


def log_lines(events):
    return [line for event in events if event.event == "log"
            for line in json.loads(event.data)["lines"]]


def test_pipeline_steps_stream_announces_steps_then_status_then_ends(
        client, namespace, environment, deployed_application):
    application, pipeline_id = deployed_application
    events = pipeline_events(client, namespace, application, pipeline_id,
                             "steps/watch")

    kinds = [event.event for event in events]
    assert kinds and kinds[0] == "steps", f"stream did not open with steps: {kinds}"
    assert kinds[-1] == "end", f"stream did not end with end: {kinds}"
    assert "error" not in kinds, [event.data for event in events
                                  if event.event == "error"]

    steps = json.loads(events[0].data)
    # A build fetches the source first, publishes the image, and finishes on
    # the trailing `done` container, which is a step like any other. The names
    # are the contract: the step bar shows them as they are.
    assert steps[0] == "fetch" and "publish" in steps and steps[-1] == "done", steps

    snapshots = [json.loads(event.data) for event in events
                 if event.event == "status"]
    assert snapshots, "a finished build sent no status snapshot"
    final = snapshots[-1]
    assert final["phase"] == "Succeeded", final
    by_name = {step["name"]: step for step in final["steps"]}
    assert list(by_name) == steps, (
        f"status steps {list(by_name)} do not line up with steps {steps}")
    for name, step in by_name.items():
        assert step["state"] == "SUCCEEDED", f"{name}: {step}"
        assert step["exitCode"] == 0, f"{name}: {step}"
        assert step["startedAt"] and step["finishedAt"], f"{name}: {step}"


def test_pipeline_step_log_replays_stamped_batches_and_ends(
        client, namespace, environment, deployed_application):
    application, pipeline_id = deployed_application
    events = pipeline_events(client, namespace, application, pipeline_id,
                             "log?container=fetch")

    kinds = [event.event for event in events]
    assert kinds and kinds[-1] == "end", f"stream did not end with end: {kinds}"
    assert "error" not in kinds, [event.data for event in events
                                  if event.event == "error"]
    batches = [event for event in events if event.event == "log"]
    assert batches, "a finished fetch step replayed no log at all"

    for batch in batches:
        lines = json.loads(batch.data)["lines"]
        assert lines, "an empty log batch was sent"
        for line in lines:
            assert set(line) >= {"time", "text"}, line
            assert "\n" not in line["text"], (
                f"a log line carried an embedded newline: {line['text'][:200]!r}")
        # The event id is what the browser hands back on a reconnect, so it has
        # to be the stamp of the last line the batch delivered.
        assert batch.id == lines[-1]["time"], (batch.id, lines[-1])

    times = [line["time"] for line in log_lines(events)]
    assert times == sorted(times), "log lines arrived out of time order"


def test_pipeline_step_log_resumes_after_the_last_event_id(
        client, namespace, environment, deployed_application):
    """What the browser does on a dropped connection: reopen the stream with
    Last-Event-ID set. Resumed after the final line, nothing may come back but
    the end — otherwise every reconnect would replay the whole step."""
    application, pipeline_id = deployed_application
    first = pipeline_events(client, namespace, application, pipeline_id,
                            "log?container=fetch")
    last_id = [event.id for event in first if event.event == "log"][-1]

    resumed = pipeline_events(client, namespace, application, pipeline_id,
                              "log?container=fetch",
                              headers={"Last-Event-ID": last_id})

    assert [event.event for event in resumed][-1:] == ["end"]
    replayed = log_lines(resumed)
    assert not replayed, (
        f"resuming after {last_id} replayed {len(replayed)} lines, the first "
        f"stamped {replayed[0]['time']}")


def test_pipeline_step_log_of_an_unknown_step_is_an_error_then_end(
        client, namespace, environment, deployed_application):
    application, pipeline_id = deployed_application
    events = pipeline_events(client, namespace, application, pipeline_id,
                             "log?container=no-such-step")

    assert [event.event for event in events] == ["error", "end"], events
    assert "no-such-step" in events[0].data, events[0].data


def pod_log_path(namespace, application, pod, environment):
    return (f"/api/namespaces/{namespace}/applications/{application}/pods/{pod}/log"
            f"?environment={environment}")


def test_pod_log_stream_replays_stamped_batches(client, namespace, environment,
                                                deployed_application):
    """The pod log is the same SSE shape as a pipeline step log, but a running
    pod's stream follows the container and never ends on its own, so read the
    first batch and hang up the way a browser leaving the page would."""
    application, _ = deployed_application
    pod = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod to appear")

    first_batch = None
    for event in client.sse(pod_log_path(namespace, application, pod, environment),
                            timeout=30):
        assert event.event != "error", f"the pod log stream failed: {event.data}"
        if event.event == "log":
            first_batch = event
            break
    assert first_batch is not None, "the pod log stream delivered no log batch"

    # Content depends on the image, so assert the transport rather than the text:
    # stamped lines, split on newlines, with the batch id set to the last stamp so
    # a reconnect can resume after it.
    lines = json.loads(first_batch.data)["lines"]
    assert lines, "an empty log batch was sent"
    for line in lines:
        assert set(line) >= {"time", "text"}, line
        assert "\n" not in line["text"], (
            f"a log line carried an embedded newline, lines are not being split: "
            f"{line['text'][:200]!r}")
    assert first_batch.id == lines[-1]["time"], (first_batch.id, lines[-1])


def test_pod_log_stream_of_an_unknown_pod_is_an_error_then_end(
        client, namespace, environment, deployed_application):
    """A pod that is gone gets a reason and an `end`, so the browser's
    EventSource does not keep reconnecting to a log that will never exist."""
    application, _ = deployed_application
    events = [event for event in client.sse(
        pod_log_path(namespace, application, "no-such-pod", environment), timeout=30)
        if event.event != "heartbeat"]

    assert [event.event for event in events] == ["error", "end"], events
    assert "no-such-pod" in events[0].data, events[0].data


def test_terminal_socket_does_not_answer_ping(client, namespace, environment,
                                              deployed_application):
    """The mirror image of the log-socket test: a terminal must stay silent, or
    the keepalive ends up as characters in the user's shell."""
    application, _ = deployed_application
    pod = wait_until(
        lambda: first_running_pod(client, namespace, application, environment),
        timeout=180, interval=5, description="a running pod to appear")

    socket = client.websocket(
        f"/api/namespaces/{namespace}/applications/{application}/pods/{pod}/terminal"
        f"?environment={environment}")
    try:
        socket.send("ping")
        answered = [frame for frame in read_until_closed(socket, timeout=8)
                    if frame.strip() == "pong"]
        assert not answered, (
            "the terminal socket answered a ping with pong; that text would be "
            "delivered into the live shell")
    finally:
        socket.close()
