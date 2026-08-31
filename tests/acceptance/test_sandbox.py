"""Sandboxes: throwaway Kubernetes Jobs, long-lived pods, and their filesystems.

Three shapes of the same idea, and they share almost no code. A one-shot
execution is a Job that runs a script and is reaped; an instance is a
StatefulSet kept alive so a terminal can attach to it; the file operations are
`tar` and `cat` piped through an exec into that instance's container. Each layer
can break on its own, and none of them is observable except through the layer
above.

All of it needs a cluster, so the whole file carries the marker. Every instance
is created through a fixture that deletes it afterwards — an instance that
outlives its test is a pod that keeps running and a node's worth of memory that
never comes back.
"""

from __future__ import annotations

import uuid

import pytest

from oops_client import wait_until

pytestmark = pytest.mark.cluster

# Configured under `oops.sandbox.images`. Both keep a container alive long
# enough to exec into, which the sandbox instance depends on.
KEEPALIVE_IMAGE = "python:3.12-slim"
SHELL_IMAGE = "alpine:3.20"

# A cold image pull plus scheduling. Generous, because a timeout here reads as a
# broken sandbox rather than as a slow registry.
INSTANCE_READY_TIMEOUT = 300
EXECUTION_TIMEOUT = 240


# -- fixtures ---------------------------------------------------------------


def wait_for_running(client, sandbox_id: str) -> dict:
    def running():
        instance = client.get(f"/api/sandbox/instances/{sandbox_id}",
                              expect_success=False)
        if not instance.success or not instance.data:
            return None
        if instance.data["status"] == "FAILED":
            pytest.fail(f"sandbox instance {sandbox_id} reached FAILED before "
                        f"it could be used")
        return instance.data if instance.data["status"] == "RUNNING" else None

    return wait_until(running, timeout=INSTANCE_READY_TIMEOUT, interval=3,
                      description=f"sandbox instance {sandbox_id} to run")


@pytest.fixture
def instance_factory(client, environment):
    """Creates sandbox instances and removes them afterwards, always.

    Function scoped on purpose: each instance is a running pod, so holding them
    open until the end of the file would keep half a dozen containers alive at
    once on a single-node cluster.
    """
    created: list[str] = []

    def create(prefix: str = "acc-sandbox", image: str = KEEPALIVE_IMAGE,
               **overrides) -> dict:
        body = {
            "environment": environment,
            "name": f"{prefix}-{uuid.uuid4().hex[:8]}",
            "image": image,
        }
        body.update(overrides)
        instance = client.post("/api/sandbox/instances", body).data
        created.append(instance["id"])
        return instance

    yield create

    for sandbox_id in reversed(created):
        try:
            client.delete(f"/api/sandbox/instances/{sandbox_id}",
                          expect_success=False)
        except Exception:
            # Cleanup is best effort, but a leak here leaves a pod running, so
            # say so rather than swallowing it entirely.
            print(f"failed to delete sandbox instance {sandbox_id}")


@pytest.fixture(scope="module")
def running_instance(client, environment) -> dict:
    """One instance, started once, shared by every test that only reads it.

    Exec and the file operations all need the same thing — a container to reach
    into — and a cold start costs about fifteen seconds, so starting one per
    test would spend minutes proving the same precondition. Each of them works
    in a directory of its own, so sharing changes nothing they observe.
    """
    instance = client.post("/api/sandbox/instances", {
        "environment": environment,
        "name": f"acc-files-{uuid.uuid4().hex[:8]}",
        "image": KEEPALIVE_IMAGE,
    }).data
    try:
        yield wait_for_running(client, instance["id"])
    finally:
        client.delete(f"/api/sandbox/instances/{instance['id']}",
                      expect_success=False)


@pytest.fixture
def scratch_directory(client, running_instance):
    """A private directory per test, removed afterwards.

    The instance is shared, so tests that wrote into a common path would pass or
    fail depending on the order they ran in.
    """
    sandbox_id = running_instance["id"]
    path = f"/tmp/acc-{uuid.uuid4().hex[:8]}"
    client.post(f"/api/sandbox/instances/{sandbox_id}/files/directory",
                {"path": path})
    yield path
    client.delete(f"/api/sandbox/instances/{sandbox_id}/files?path={path}",
                  expect_success=False)


# -- runtime images ---------------------------------------------------------


def test_the_runtime_images_are_listed_and_sorted(client):
    """The create form is a dropdown over this list, so its order is the order
    an operator sees."""
    images = client.get("/api/sandbox/images").data

    assert images, (
        "no sandbox runtime images are offered; the create form would present "
        "an empty dropdown with nothing to explain why")
    assert images == sorted(images), (
        f"the runtime images came back unsorted: {images}. The dropdown renders "
        f"them in the order given.")
    assert KEEPALIVE_IMAGE in images, (
        f"{KEEPALIVE_IMAGE} is not offered; the rest of this file assumes the "
        f"configured images from `oops.sandbox.images`")


def test_an_image_outside_the_configured_list_still_runs(client, environment):
    """Documents that the image list is advisory rather than an allowlist.

    `/api/sandbox/images` reads as the set of images a sandbox may use, and the
    UI treats it that way, but nothing on the execution path checks a request
    against it — any image the cluster can pull will run.

    Written against today's behaviour. If the list is ever enforced this fails,
    and should be inverted rather than deleted: enforcing it is the improvement,
    and it is worth making deliberately because it would break every caller that
    passes an image of its own.
    """
    offered = client.get("/api/sandbox/images").data
    unlisted = "busybox:1.36"
    if unlisted in offered:
        pytest.skip(f"{unlisted} is configured, so it proves nothing here")

    result = client.post("/api/sandbox/executions", {
        "environment": environment,
        "image": unlisted,
        "commands": ["echo unlisted-image-ran"],
        "timeoutSeconds": EXECUTION_TIMEOUT,
    }, expect_success=False)

    assert result.success is True, (
        f"an unlisted image was refused ({result.message}); the runtime list is "
        f"now enforced, which is an improvement — invert this test")
    assert result.data["exitCode"] == 0, (
        f"an unlisted image was accepted but its script exited "
        f"{result.data['exitCode']}; the image could not be pulled or run")


# -- one-shot executions ----------------------------------------------------


def test_a_one_shot_execution_returns_its_combined_output(client, environment):
    """The Job path: a script runs to completion and its output comes back in
    the response rather than over a stream, because `stream` was not set."""
    result = client.post("/api/sandbox/executions", {
        "environment": environment,
        "image": SHELL_IMAGE,
        "commands": ["echo first-line", "echo second-line >&2"],
        "timeoutSeconds": EXECUTION_TIMEOUT,
    }).data

    assert result["exitCode"] == 0, (
        f"a successful script reported exit code {result['exitCode']}; the Job "
        f"either failed or its status was not read back")
    assert "first-line" in result["output"], (
        f"stdout is missing from the output: {result['output']!r}")
    assert "second-line" in result["output"], (
        f"stderr is missing from the output: {result['output']!r}. Both streams "
        f"are merged, which is the only way a caller sees why a script failed.")


def test_the_commands_run_as_one_script_not_as_separate_processes(
        client, environment):
    """`commands` is joined with newlines into a single shell script, so state
    set by one line is visible to the next. A caller writing a `cd` on its own
    line depends on it."""
    result = client.post("/api/sandbox/executions", {
        "environment": environment,
        "image": SHELL_IMAGE,
        "commands": ["marker=carried-over", "echo $marker"],
        "timeoutSeconds": EXECUTION_TIMEOUT,
    }).data

    assert "carried-over" in result["output"], (
        f"the second command did not see the first one's variable: "
        f"{result['output']!r}; the lines are being run as separate processes")


def test_a_failing_script_reports_its_exit_code(client, environment):
    """A non-zero exit is a successful call with a non-zero code, not a failed
    envelope — the caller wanted the script's verdict, not an error."""
    result = client.post("/api/sandbox/executions", {
        "environment": environment,
        "image": SHELL_IMAGE,
        "commands": ["echo before-the-failure", "exit 7"],
        "timeoutSeconds": EXECUTION_TIMEOUT,
    })

    assert result.success is True, (
        f"a script that exited non-zero produced a failed envelope: "
        f"{result.message}")
    assert result.data["exitCode"] == 7, (
        f"the script exited 7 but the API reported {result.data['exitCode']}; "
        f"a caller cannot tell a failure from a success")
    assert "before-the-failure" in result.data["output"], (
        "output written before the failure was lost")


def test_environment_variables_reach_the_container(client, environment):
    marker = uuid.uuid4().hex[:12]
    result = client.post("/api/sandbox/executions", {
        "environment": environment,
        "image": SHELL_IMAGE,
        "commands": ["echo $ACCEPTANCE_MARKER"],
        "env": {"ACCEPTANCE_MARKER": marker},
        "timeoutSeconds": EXECUTION_TIMEOUT,
    }).data

    assert marker in result["output"], (
        f"the environment variable did not reach the container: "
        f"{result['output']!r}")


def test_an_execution_validates_its_request_before_scheduling_anything(
        client, environment):
    """All of these are refused before a Job exists. Scheduling first and
    failing afterwards would leave pods behind for requests that were never
    going to be accepted."""
    rejected = [
        ({"image": SHELL_IMAGE, "commands": ["echo x"]},
         "a missing environment"),
        ({"environment": environment, "commands": ["echo x"]},
         "a missing image"),
        ({"environment": environment, "image": SHELL_IMAGE, "commands": []},
         "an empty command list"),
        ({"environment": environment, "image": SHELL_IMAGE,
          "commands": ["   "]},
         "a command list of nothing but blanks"),
        ({"environment": environment, "image": SHELL_IMAGE,
          "commands": ["echo x"], "timeoutSeconds": -1},
         "a negative timeout"),
        ({"environment": environment, "image": SHELL_IMAGE,
          "commands": ["echo x"], "env": {"not a name": "value"}},
         "an environment variable name a shell cannot express"),
        ({"environment": "not-registered-9f3a", "image": SHELL_IMAGE,
          "commands": ["echo x"]},
         "an unregistered environment"),
    ]
    for body, description in rejected:
        result = client.post("/api/sandbox/executions", body,
                             expect_success=False)
        assert result.success is False, (
            f"{description} was accepted; the request reached the cluster "
            f"before anyone checked it")
        assert result.message, (
            f"{description} was refused without a message, so the caller has "
            f"nothing to correct")


# -- instance lifecycle -----------------------------------------------------


def test_an_instance_starts_pending_and_becomes_running(client, environment,
                                                        instance_factory):
    """Creation returns immediately with a PENDING instance; the pod is still
    being scheduled. A caller that treated the create response as ready would
    exec into a container that does not exist yet."""
    instance = instance_factory(prefix="acc-lifecycle")

    assert instance["status"] == "PENDING", (
        f"a freshly created instance reported {instance['status']!r}; creation "
        f"is meant to return before the pod is scheduled")
    assert instance["id"], "the created instance has no id to address it by"
    assert instance["environment"] == environment
    assert instance["image"] == KEEPALIVE_IMAGE
    assert instance["createdBy"], (
        "the instance records no creator; the listing shows every user's "
        "sandboxes and would have nothing to attribute them to")

    running = wait_for_running(client, instance["id"])
    assert running["status"] == "RUNNING"
    assert running["createdByName"], (
        "the running instance has no createdByName; the creator id is resolved "
        "to a username on read, and the listing shows that name")


def test_an_instance_is_listed_across_and_within_environments(
        client, environment, instance_factory):
    """Listing without an environment spans every registered one, which is what
    the sandbox page opens with. The filters narrow it rather than being
    required."""
    instance = instance_factory(prefix="acc-listing")
    sandbox_id = instance["id"]

    everywhere = client.get("/api/sandbox/instances").data
    assert sandbox_id in [item["id"] for item in everywhere], (
        f"{sandbox_id} is absent from the unfiltered listing; listing without "
        f"an environment is not spanning all of them")

    in_environment = client.get(
        f"/api/sandbox/instances?environment={environment}").data
    assert sandbox_id in [item["id"] for item in in_environment], (
        f"{sandbox_id} is absent from the {environment} listing")

    by_image = client.get(
        f"/api/sandbox/instances"
        f"?environment={environment}&image={KEEPALIVE_IMAGE}").data
    assert sandbox_id in [item["id"] for item in by_image], (
        f"{sandbox_id} is absent when filtering by its own image")

    other_image = client.get(
        f"/api/sandbox/instances"
        f"?environment={environment}&image={SHELL_IMAGE}").data
    assert sandbox_id not in [item["id"] for item in other_image], (
        f"{sandbox_id} runs {KEEPALIVE_IMAGE} but was returned when filtering "
        f"for {SHELL_IMAGE}; the image filter is not applied")


def test_a_single_instance_can_be_read_back_by_id(client, instance_factory):
    instance = instance_factory(prefix="acc-read")
    fetched = client.get(f"/api/sandbox/instances/{instance['id']}").data

    assert fetched["id"] == instance["id"]
    assert fetched["name"] == instance["name"]
    assert fetched["image"] == instance["image"]


def test_a_duplicate_instance_name_is_refused(client, environment,
                                              instance_factory):
    """Names are how the sandbox page addresses an instance, so two with the
    same name would be indistinguishable in the listing."""
    instance = instance_factory(prefix="acc-duplicate")

    result = client.post("/api/sandbox/instances", {
        "environment": environment,
        "name": instance["name"],
        "image": KEEPALIVE_IMAGE,
    }, expect_success=False)

    assert result.success is False, (
        f"a second instance called {instance['name']!r} was created; the two "
        f"are now indistinguishable in the listing")


def test_an_instance_needs_an_environment_and_an_image(client, environment):
    for body, description in [
        ({"image": KEEPALIVE_IMAGE}, "a missing environment"),
        ({"environment": environment}, "a missing image"),
        ({"environment": "not-registered-9f3a", "image": KEEPALIVE_IMAGE},
         "an unregistered environment"),
    ]:
        result = client.post("/api/sandbox/instances", body,
                             expect_success=False)
        assert result.success is False, (
            f"{description} was accepted, so a pod was created that no listing "
            f"can find")


def test_a_deleted_instance_is_gone_from_both_the_listing_and_by_id(
        client, environment, instance_factory):
    """Deletion is the only thing that stops the pod, so a delete that reported
    success without taking effect would leak a container indefinitely."""
    instance = instance_factory(prefix="acc-delete")
    sandbox_id = instance["id"]
    wait_for_running(client, sandbox_id)

    client.delete(f"/api/sandbox/instances/{sandbox_id}")

    def gone():
        result = client.get(f"/api/sandbox/instances/{sandbox_id}",
                            expect_success=False)
        return result.success is False

    wait_until(gone, timeout=60, interval=2,
               description=f"the deleted instance {sandbox_id} to stop being "
                           f"readable; while it still resolves its pod is "
                           f"still running and nothing will stop it")

    listing = client.get(f"/api/sandbox/instances?environment={environment}").data
    assert sandbox_id not in [item["id"] for item in listing], (
        f"{sandbox_id} was deleted but is still in the listing")


def test_deleting_an_unknown_instance_fails(client):
    """A miss is an error rather than a silent success, because the caller's
    whole reason to delete was to be sure the pod is gone."""
    result = client.delete("/api/sandbox/instances/definitely-not-a-sandbox",
                           expect_success=False)
    assert result.success is False, (
        "deleting an instance that does not exist reported success; a caller "
        "cleaning up a mistyped id would believe it had worked")


# -- exec inside an instance ------------------------------------------------


def test_exec_runs_inside_the_running_instance(client, running_instance):
    """Exec attaches to the live container rather than starting a new one, which
    is the whole difference from a one-shot execution: the filesystem it sees is
    the one the file operations write to."""
    sandbox_id = running_instance["id"]
    marker = uuid.uuid4().hex[:12]

    client.put(f"/api/sandbox/instances/{sandbox_id}/files/content",
               {"path": f"/tmp/{marker}", "content": marker})
    try:
        result = client.post(f"/api/sandbox/instances/{sandbox_id}/exec",
                             {"command": f"cat /tmp/{marker}",
                              "timeoutSeconds": 60}).data
        assert marker in result["output"], (
            f"exec did not see a file written through the file API: "
            f"{result['output']!r}. The two are reaching different containers.")
    finally:
        client.delete(
            f"/api/sandbox/instances/{sandbox_id}/files?path=/tmp/{marker}",
            expect_success=False)


def test_exec_reports_the_websocket_close_code_rather_than_the_exit_status(
        client, running_instance):
    """Documents a defect rather than endorsing it.

    One-shot executions report the script's real exit code. Instance exec does
    not: the gateway's `onClose(int code, ...)` callback carries the WebSocket
    close code, and a normal close is 1000, so every command — successful or
    not — comes back as 1000 and a caller cannot tell them apart.

    Written against today's behaviour so the gap is visible. When the exit
    status is read properly this test fails, and should be replaced with the
    assertions in the comments rather than adjusted to the new sentinel.
    """
    sandbox_id = running_instance["id"]

    succeeded = client.post(f"/api/sandbox/instances/{sandbox_id}/exec",
                            {"command": "true", "timeoutSeconds": 60}).data
    failed = client.post(f"/api/sandbox/instances/{sandbox_id}/exec",
                         {"command": "exit 3", "timeoutSeconds": 60}).data

    # Once the real status is reported these become 0 and 3.
    assert succeeded["exitCode"] == failed["exitCode"] == 1000, (
        f"instance exec now reports {succeeded['exitCode']} for a successful "
        f"command and {failed['exitCode']} for one that exited 3. If those are "
        f"the real exit statuses the defect is fixed — assert 0 and 3 here "
        f"instead of deleting the test.")


def test_exec_requires_a_command_and_a_known_instance(client, running_instance):
    sandbox_id = running_instance["id"]

    blank = client.post(f"/api/sandbox/instances/{sandbox_id}/exec",
                        {"command": "   "}, expect_success=False)
    assert blank.success is False, (
        "a blank command was accepted; the shell would be handed nothing to run")

    unknown = client.post("/api/sandbox/instances/definitely-not-a-sandbox/exec",
                          {"command": "echo x"}, expect_success=False)
    assert unknown.success is False, (
        "exec against an unknown instance reported success; a mistyped id would "
        "look like a command that produced no output")


# -- instance filesystem ----------------------------------------------------


def test_the_instance_filesystem_lists_the_container_root(client,
                                                          running_instance):
    """Listing with no path defaults to `/`, which is what the file browser
    opens on."""
    sandbox_id = running_instance["id"]
    entries = client.get(f"/api/sandbox/instances/{sandbox_id}/files").data

    assert entries, (
        "the container root listed nothing; the file browser opens on an empty "
        "pane with no explanation")

    names = [entry["name"] for entry in entries]
    assert "tmp" in names and "etc" in names, (
        f"the root listing is missing the usual directories: {names}")

    for entry in entries:
        for field in ("name", "path", "type", "size"):
            assert field in entry, f"a file entry is missing {field!r}"
        assert entry["type"] in ("DIRECTORY", "FILE", "SYMLINK_DIRECTORY",
                                 "SYMLINK_FILE", "OTHER"), (
            f"{entry['name']} has type {entry['type']!r}, which the browser has "
            f"no icon for")

    # Symlinks are classified by what they point at, so the browser knows
    # whether clicking one descends or opens an editor.
    assert any(entry["type"].startswith("SYMLINK") for entry in entries), (
        f"nothing in the container root was reported as a symlink, and a Debian "
        f"root has several (/bin, /lib); symlinks are being flattened into "
        f"plain entries: {names}")


def test_a_file_written_through_the_api_reads_back_unchanged(
        client, running_instance, scratch_directory):
    """Write and read are the editor's save and open. Anything that mangles
    newlines between them corrupts the file the user was editing."""
    sandbox_id = running_instance["id"]
    path = f"{scratch_directory}/note.txt"
    content = "first line\nsecond line\n\ttabbed\n"

    client.put(f"/api/sandbox/instances/{sandbox_id}/files/content",
               {"path": path, "content": content})

    stored = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files/content?path={path}").data
    assert stored["path"] == path, (
        f"the read echoed back {stored['path']!r} rather than the path asked for")
    assert stored["content"] == content, (
        f"the file came back as {stored['content']!r} rather than "
        f"{content!r}; saving in the editor would corrupt the file")

    listing = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files"
        f"?path={scratch_directory}").data
    entries = [entry for entry in listing if entry["name"] == "note.txt"]
    assert entries, f"the written file is absent from the {scratch_directory} listing"
    assert entries[0]["type"] == "FILE"
    assert entries[0]["size"] == len(content.encode()), (
        f"the listing reports {entries[0]['size']} bytes for a "
        f"{len(content.encode())} byte file")


def test_a_created_directory_is_listed_as_a_directory(client, running_instance,
                                                      scratch_directory):
    """The scratch directory itself was created through the API, so this checks
    the parent listing rather than trusting the call that made it."""
    sandbox_id = running_instance["id"]
    nested = f"{scratch_directory}/nested"

    client.post(f"/api/sandbox/instances/{sandbox_id}/files/directory",
                {"path": nested})

    listing = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files"
        f"?path={scratch_directory}").data
    entries = [entry for entry in listing if entry["name"] == "nested"]
    assert entries, f"the created directory is absent from {scratch_directory}"
    assert entries[0]["type"] == "DIRECTORY", (
        f"the created directory is listed as {entries[0]['type']!r}; the "
        f"browser would try to open it in the editor")

    blank = client.post(f"/api/sandbox/instances/{sandbox_id}/files/directory",
                        {"path": "  "}, expect_success=False)
    assert blank.success is False, (
        "a blank directory path was accepted; the target is whatever the shell "
        "makes of an empty argument")


def test_a_file_can_be_renamed_within_the_instance(client, running_instance,
                                                   scratch_directory):
    """Rename is a move, so the old path has to be gone as well as the new one
    present — a copy would silently double the disk use of every rename."""
    sandbox_id = running_instance["id"]
    source = f"{scratch_directory}/before.txt"
    target = f"{scratch_directory}/after.txt"
    client.put(f"/api/sandbox/instances/{sandbox_id}/files/content",
               {"path": source, "content": "renamed content\n"})

    client.post(f"/api/sandbox/instances/{sandbox_id}/files/rename",
                {"fromPath": source, "toPath": target})

    listing = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files"
        f"?path={scratch_directory}").data
    names = [entry["name"] for entry in listing]
    assert "after.txt" in names, f"the renamed file is absent: {names}"
    assert "before.txt" not in names, (
        f"the original file survived the rename: {names}; the file was copied "
        f"rather than moved")

    moved = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files/content?path={target}").data
    assert moved["content"] == "renamed content\n", (
        "the renamed file lost its content")

    incomplete = client.post(
        f"/api/sandbox/instances/{sandbox_id}/files/rename",
        {"fromPath": target}, expect_success=False)
    assert incomplete.success is False, (
        "a rename without a destination was accepted; the file would be moved "
        "somewhere the caller never named")


def test_an_uploaded_file_lands_under_a_directory_path(client, running_instance,
                                                       scratch_directory):
    """A path ending in `/` means "into this directory, under the uploaded
    name". That is how the browser uploads into the folder it is showing,
    without having to construct the target name itself."""
    sandbox_id = running_instance["id"]
    payload = b"uploaded bytes\n"

    client.post(
        f"/api/sandbox/instances/{sandbox_id}/files/upload"
        f"?path={scratch_directory}/",
        files={"file": ("uploaded.txt", payload, "text/plain")})

    stored = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files/content"
        f"?path={scratch_directory}/uploaded.txt").data
    assert stored["content"] == payload.decode(), (
        f"the uploaded file reads back as {stored['content']!r}; the bytes were "
        f"altered on the way through")


def test_an_uploaded_file_can_be_given_an_explicit_name(client,
                                                        running_instance,
                                                        scratch_directory):
    """A path that does not end in `/` is the target file itself, so the upload
    can be renamed on the way in."""
    sandbox_id = running_instance["id"]
    target = f"{scratch_directory}/explicit.bin"

    client.post(
        f"/api/sandbox/instances/{sandbox_id}/files/upload?path={target}",
        files={"file": ("original-name.txt", b"explicit\n", "text/plain")})

    listing = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files"
        f"?path={scratch_directory}").data
    names = [entry["name"] for entry in listing]
    assert "explicit.bin" in names, (
        f"the upload did not take the name it was given: {names}")
    assert "original-name.txt" not in names, (
        f"the upload used the multipart filename instead of the requested "
        f"path: {names}")


def test_a_download_carries_the_bytes_and_names_the_file(client,
                                                         running_instance,
                                                         scratch_directory):
    """Download is the one file endpoint that answers with bytes rather than the
    envelope, and the browser saves whatever `Content-Disposition` names."""
    sandbox_id = running_instance["id"]
    path = f"{scratch_directory}/download.bin"
    payload = b"\x00\x01binary payload\xff\n"

    client.post(f"/api/sandbox/instances/{sandbox_id}/files/upload?path={path}",
                files={"file": ("download.bin", payload,
                                "application/octet-stream")})

    response = client.raw(
        "GET",
        f"/api/sandbox/instances/{sandbox_id}/files/download?path={path}")

    assert response.status_code == 200, (
        f"the download answered HTTP {response.status_code}")
    assert response.content == payload, (
        f"the download returned {response.content!r} rather than the "
        f"{len(payload)} bytes uploaded; the stream is being re-encoded")
    disposition = response.headers.get("Content-Disposition", "")
    assert "download.bin" in disposition, (
        f"Content-Disposition is {disposition!r}; the browser would save the "
        f"file under the request path instead of its own name")


def test_a_deleted_path_disappears_from_the_listing(client, running_instance,
                                                    scratch_directory):
    """Delete takes files and directories alike, which is what the browser's one
    delete button relies on."""
    sandbox_id = running_instance["id"]
    file_path = f"{scratch_directory}/doomed.txt"
    directory_path = f"{scratch_directory}/doomed-directory"
    client.put(f"/api/sandbox/instances/{sandbox_id}/files/content",
               {"path": file_path, "content": "gone soon\n"})
    client.post(f"/api/sandbox/instances/{sandbox_id}/files/directory",
                {"path": directory_path})

    client.delete(
        f"/api/sandbox/instances/{sandbox_id}/files?path={file_path}")
    client.delete(
        f"/api/sandbox/instances/{sandbox_id}/files?path={directory_path}")

    listing = client.get(
        f"/api/sandbox/instances/{sandbox_id}/files"
        f"?path={scratch_directory}").data
    names = [entry["name"] for entry in listing]
    assert "doomed.txt" not in names, (
        f"the deleted file is still listed: {names}")
    assert "doomed-directory" not in names, (
        f"the deleted directory is still listed: {names}; delete only handles "
        f"files and the browser's delete button silently does nothing on a "
        f"folder")


def test_file_operations_on_an_unknown_instance_fail(client):
    """Every file endpoint resolves the instance to a pod first, so an unknown
    id must fail there rather than reaching some other container."""
    result = client.get(
        "/api/sandbox/instances/definitely-not-a-sandbox/files?path=/tmp",
        expect_success=False)
    assert result.success is False, (
        "listing files of an unknown instance reported success; the id is not "
        "being resolved before the exec")
