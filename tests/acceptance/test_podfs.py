"""The pod filesystem browser, against a real container.

Every one of these eight endpoints is a `kubectl exec` in disguise: the gateway
pipes a small POSIX shell script into the application container and parses what
comes back, except upload and write, which push a tar stream through the exec
channel. So the whole surface rests on what busybox in the target image happens
to provide, and on bytes surviving a websocket multiplexer — exactly the kind of
thing a reimplementation gets subtly wrong in ways a status code cannot show: a
listing that loses dotfiles, a size counted in characters instead of bytes, a
download that arrives with a trailing newline the file never had.

So these tests compare content, not codes. Each one writes something it can
recognise and reads it back through a different endpoint than the one that put
it there.
"""

from __future__ import annotations

import uuid
from urllib.parse import urlencode

import pytest

from oops_client import wait_until
from test_deploy import (DEPLOY_TIMEOUT, TERMINAL_STATUSES, configure_for_build,
                         git_strategy)
from test_streams import first_running_pod

pytestmark = pytest.mark.cluster


class PodFiles:
    """The eight file endpoints, bound to one pod.

    All eight take the same five coordinates and differ only in verb and body,
    so binding them once keeps the tests about files rather than about URLs.
    """

    def __init__(self, client, namespace: str, application: str, pod: str,
                 environment: str):
        self.client = client
        self.namespace = namespace
        self.application = application
        self.pod = pod
        self.environment = environment
        self.base = (f"/api/namespaces/{namespace}/applications/{application}"
                     f"/pods/{pod}/files")

    def _url(self, suffix: str = "", **params) -> str:
        # `environment` is required on every call and is the environment *name*,
        # never an id.
        return f"{self.base}{suffix}?{urlencode({'environment': self.environment, **params})}"

    def list(self, path: str, **kwargs):
        return self.client.get(self._url(path=path), **kwargs)

    def read(self, path: str, **kwargs):
        return self.client.get(self._url("/content", path=path), **kwargs)

    def write(self, path: str, content: str, **kwargs):
        return self.client.put(self._url("/content"),
                               {"path": path, "content": content}, **kwargs)

    def mkdir(self, path: str, **kwargs):
        return self.client.post(self._url("/directory"), {"path": path}, **kwargs)

    def rename(self, from_path: str, to_path: str, **kwargs):
        return self.client.post(self._url("/rename"),
                                {"fromPath": from_path, "toPath": to_path},
                                **kwargs)

    def upload(self, path: str, file_name: str, payload: bytes, **kwargs):
        # The part name is "file"; `path` stays a query parameter even though the
        # rest of the request is multipart.
        return self.client.post(
            self._url("/upload", path=path),
            files={"file": (file_name, payload, "application/octet-stream")},
            **kwargs)

    def download(self, path: str):
        """Raw, because this endpoint answers with bytes rather than an envelope."""
        return self.client.raw("GET", self._url("/download", path=path))

    def delete(self, path: str, **kwargs):
        return self.client.delete(self._url(path=path), **kwargs)


def entry_named(listing, name: str):
    for entry in listing:
        if entry.get("name") == name:
            return entry
    return None


@pytest.fixture(scope="module")
def pod_files(client, namespace, environment):
    """One deployed application with a running pod, shared by every test here.

    A build takes about ninety seconds and no test in this file touches the
    application itself — only files inside its container — so deploying per test
    would spend ten minutes re-proving the same precondition.
    """
    application = f"podfs-{uuid.uuid4().hex[:8]}"
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
            pytest.skip(f"fixture deploy ended as {pipeline['status']}, so there "
                        f"is no container to browse")

        pod = wait_until(
            lambda: first_running_pod(client, namespace, application, environment),
            timeout=180, interval=5, description="a running pod to appear")

        # The container defaults to the application name when `container` is
        # omitted, which is what the StatefulSet names it — so every call below
        # leaves it out and still lands in the right container.
        yield PodFiles(client, namespace, application, pod, environment)
    finally:
        try:
            client.delete_application(namespace, application, expect_success=False)
        except Exception:
            pass


@pytest.fixture(scope="module")
def scratch(pod_files):
    """A directory of our own inside the pod, removed recursively afterwards.

    Deleting the application takes the pod with it, so this teardown matters
    only when the suite is pointed at a pod that outlives the run — but that is
    also the case where a leaked file would quietly change the next result.
    """
    directory = f"/tmp/oops-acceptance-{uuid.uuid4().hex[:8]}"
    pod_files.mkdir(directory)
    yield directory
    pod_files.delete(directory, expect_success=False)


def test_write_and_read_round_trip_the_content(pod_files, scratch):
    """PUT /files/content then GET /files/content.

    Non-ASCII and a trailing newline are both in the payload on purpose: the
    write encodes to UTF-8 and the read decodes it back, and a shell script sits
    in between, which is where a stray `echo` or a locale assumption would show
    up as mangled text.
    """
    path = f"{scratch}/round-trip.txt"
    content = "héllo 世界\nsecond line\n\ttabbed\n"

    pod_files.write(path, content)
    result = pod_files.read(path)

    assert result.data["content"] == content, (
        f"the file read back as {result.data['content']!r} but was written as "
        f"{content!r}; the editor would silently corrupt whatever a user saves")
    assert result.data["path"] == path, (
        f"the read echoed path {result.data['path']!r} instead of {path!r}; the "
        f"editor keys its open buffers on this field")


def test_the_listing_reports_type_and_byte_size(pod_files, scratch):
    """GET /files.

    Size is what the file browser shows, and it is a byte count, not a character
    count — a nine-character UTF-8 name is twenty bytes on disk.
    """
    directory = f"{scratch}/listing"
    pod_files.mkdir(directory)
    content = "héllo 世界\nline2\n"
    pod_files.write(f"{directory}/note.txt", content)

    entries = pod_files.list(directory).data

    note = entry_named(entries, "note.txt")
    assert note is not None, (
        f"the file just written is missing from the listing: "
        f"{[entry['name'] for entry in entries]}")
    assert note["type"] == "FILE", (
        f"note.txt came back as {note['type']}; the browser decides whether a "
        f"row opens an editor or descends into a directory on this field")
    assert note["size"] == len(content.encode("utf-8")), (
        f"note.txt reported {note['size']} bytes for a "
        f"{len(content.encode('utf-8'))}-byte file; the size is being counted "
        f"in characters rather than bytes")
    assert note["path"] == f"{directory}/note.txt", (
        f"the entry carries path {note['path']!r}; the browser navigates with "
        f"it, so a wrong join makes every row unopenable")


def test_the_listing_sorts_directories_first(pod_files, scratch):
    """Directories before files, each half case-insensitively by name."""
    directory = f"{scratch}/sorting"
    pod_files.mkdir(directory)
    pod_files.mkdir(f"{directory}/Zeta")
    pod_files.mkdir(f"{directory}/alpha")
    pod_files.write(f"{directory}/Beta.txt", "b")
    pod_files.write(f"{directory}/aardvark.txt", "a")

    names = [entry["name"] for entry in pod_files.list(directory).data]

    assert names == ["alpha", "Zeta", "aardvark.txt", "Beta.txt"], (
        f"the listing came back as {names}; the browser renders it in the "
        f"order it arrives, so directories would be scattered through the files")


def test_the_listing_includes_dotfiles(pod_files, scratch):
    """The glob in the listing script has a `.*` arm for exactly this.

    A shell `for entry in *` skips hidden files, and a container's interesting
    files — .env, .npmrc, .bashrc — are mostly hidden ones.
    """
    directory = f"{scratch}/hidden"
    pod_files.mkdir(directory)
    pod_files.write(f"{directory}/.env", "TOKEN=secret\n")

    names = [entry["name"] for entry in pod_files.list(directory).data]

    assert ".env" in names, (
        f"a dotfile is missing from {names}; hidden files would be invisible "
        f"and unreachable in the browser")


def test_rename_moves_the_file_with_its_content(pod_files, scratch):
    """POST /files/rename, then read the content back at the new path."""
    source = f"{scratch}/before-rename.txt"
    target = f"{scratch}/after-rename.txt"
    content = "content that must survive the move\n"
    pod_files.write(source, content)

    pod_files.rename(source, target)

    assert pod_files.read(target).data["content"] == content, (
        "the renamed file lost its content; rename is a move, not a copy of the "
        "name alone")
    names = [entry["name"] for entry in pod_files.list(scratch).data]
    assert "after-rename.txt" in names and "before-rename.txt" not in names, (
        f"after the rename the directory still lists {names}; the old name was "
        f"left behind, so the rename duplicated rather than moved")


def test_rename_refuses_to_overwrite_an_existing_path(pod_files, scratch):
    """The guard that stops a rename silently destroying the target."""
    keeper = f"{scratch}/keeper.txt"
    other = f"{scratch}/other.txt"
    pod_files.write(keeper, "must not be overwritten\n")
    pod_files.write(other, "the mover\n")

    result = pod_files.rename(other, keeper, expect_success=False)

    assert result.success is False, (
        "a rename onto an existing path was accepted; a user renaming a file "
        "would destroy whatever already had that name")
    assert pod_files.read(keeper).data["content"] == "must not be overwritten\n", (
        "the target file was overwritten even though the rename was refused")


def test_download_returns_the_exact_bytes(pod_files, scratch):
    """GET /files/download, which answers with bytes rather than an envelope."""
    path = f"{scratch}/download-me.bin"
    # A NUL, a bare CR, and multi-byte UTF-8: the exec channel is a websocket
    # multiplexer, and any text handling in it would damage at least one of these.
    payload = bytes([0x00, 0x01, 0xFF, 0x0D, 0x0A, 0x41]) + "héllo\n".encode("utf-8")
    pod_files.upload(f"{scratch}/", "download-me.bin", payload)

    response = pod_files.download(path)

    assert response.status_code == 200, (
        f"the download answered {response.status_code}: {response.text[:200]!r}")
    assert response.content == payload, (
        f"the download returned {response.content!r} for a file uploaded as "
        f"{payload!r}; binary files cannot be retrieved intact")
    assert response.headers.get("Content-Length") == str(len(payload)), (
        f"Content-Length is {response.headers.get('Content-Length')} for "
        f"{len(payload)} bytes, so a browser would truncate or hang")
    disposition = response.headers.get("Content-Disposition", "")
    # Documented, not endorsed: the filename is emitted twice, once as an
    # RFC 2047 encoded word (`filename="=?UTF-8?Q?...?="`) and once as RFC 5987
    # (`filename*=UTF-8''...`). Only the second form is what browsers use.
    assert "filename*=UTF-8''download-me.bin" in disposition, (
        f"Content-Disposition is {disposition!r}; without the RFC 5987 filename "
        f"the browser saves the file under the URL's last segment")


def test_upload_into_a_directory_keeps_the_sent_filename(pod_files, scratch):
    """POST /files/upload with a trailing slash on `path`.

    A `path` ending in `/` is a destination directory and the name comes from
    the multipart part; anything else is the full target path. Both forms are
    reachable from the UI, so both are pinned here.
    """
    payload = b"uploaded into a directory\n"

    pod_files.upload(f"{scratch}/", "named-by-the-part.txt", payload)

    assert pod_files.read(f"{scratch}/named-by-the-part.txt").data["content"] == \
        payload.decode(), (
        "an upload to a directory path did not land under the part's filename")

    explicit = f"{scratch}/named-by-the-query.txt"
    pod_files.upload(explicit, "ignored-when-path-is-explicit.txt",
                     b"uploaded to an explicit path\n")

    assert pod_files.read(explicit).data["content"] == \
        "uploaded to an explicit path\n", (
        "an upload to an explicit path did not land there; the part filename "
        "must not win over a path that names the file")


def test_create_directory_then_delete_it_with_its_contents(pod_files, scratch):
    """POST /files/directory and DELETE /files, the two ends of the lifecycle."""
    directory = f"{scratch}/nested/deep"
    pod_files.mkdir(directory)
    pod_files.write(f"{directory}/inside.txt", "gone with the directory\n")

    parent = pod_files.list(f"{scratch}/nested").data
    entry = entry_named(parent, "deep")
    assert entry is not None and entry["type"] == "DIRECTORY", (
        f"the created directory is missing or mistyped in {parent}; mkdir is "
        f"expected to create intermediate directories too")

    refused = pod_files.mkdir(directory, expect_success=False)
    assert refused.success is False, (
        "creating a directory that already exists was accepted; the UI relies "
        "on the refusal to tell the user the name is taken")

    pod_files.delete(f"{scratch}/nested")

    remaining = [item["name"] for item in pod_files.list(scratch).data]
    assert "nested" not in remaining, (
        f"the deleted directory is still listed in {remaining}")
    gone = pod_files.read(f"{directory}/inside.txt", expect_success=False)
    assert gone.success is False, (
        "a file inside the deleted directory is still readable; the delete did "
        "not recurse")


def test_deleting_a_missing_path_is_refused(pod_files, scratch):
    """The negative that keeps the delete test honest.

    Without it, a delete that quietly does nothing would still pass everything
    above, because a file that was never removed and a file that never existed
    look the same from the listing.
    """
    result = pod_files.delete(f"{scratch}/never-existed.txt", expect_success=False)

    assert result.success is False, (
        "deleting a path that does not exist reported success, so a failed "
        "delete cannot be told apart from a real one")
    assert result.message, "a refused delete must explain itself"


def test_an_unknown_environment_is_refused_on_both_resolution_paths(pod_files,
                                                                    scratch):
    """`environment` is a name, and an unknown one must fail as an envelope.

    Two of the eight endpoints resolve the environment in the controller and the
    other six in the service; this is the check that both paths refuse rather
    than dereferencing a null.
    """
    stray = PodFiles(pod_files.client, pod_files.namespace, pod_files.application,
                     pod_files.pod, "no-such-environment")

    listing = stray.list(scratch, expect_success=False)
    reading = stray.read(f"{scratch}/anything.txt", expect_success=False)

    for label, result in (("list", listing), ("read", reading)):
        assert result.success is False, (
            f"{label} against an unregistered environment succeeded, which "
            f"means the environment parameter is not being resolved at all")
        assert "no-such-environment" in (result.message or ""), (
            f"{label} refused with {result.message!r}; the message should name "
            f"the environment so a misconfigured UI is diagnosable")
