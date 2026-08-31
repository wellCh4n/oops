"""The ZIP source path: presigned upload to object storage, then a build from it.

`GIT` and `ZIP` are the two source types, and they diverge early — ZIP asks the
API for a presigned PUT, uploads straight to S3 without the request passing
through OOPS at all, and the build's fetch step downloads it with curl instead of
cloning. None of that shares code with the git path, so testing only git leaves
half of the source handling unexercised.

Skipped unless object storage is configured, since `oops.object-storage.enabled`
is off by default.
"""

from __future__ import annotations

import io
import os
import zipfile

import pytest

from oops_client import ApiError, wait_until
from test_deploy import DEPLOY_TIMEOUT, TERMINAL_STATUSES

DOCKERFILE = b"""FROM alpine:3.20
RUN echo "acceptance" > /message
CMD ["sh", "-c", "while true; do cat /message; sleep 30; done"]
"""


def make_zip() -> bytes:
    """A minimal buildable source bundle, held in memory."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("Dockerfile", DOCKERFILE)
        archive.writestr("README.md", "acceptance fixture\n")
    return buffer.getvalue()


@pytest.fixture(scope="session")
def object_storage_enabled(client) -> bool:
    """Object storage is optional, and the UI gates on the same flag."""
    features = client.get("/api/features", expect_success=False)
    if features.success and isinstance(features.data, dict):
        if not features.data.get("objectStorage", False):
            pytest.skip("object storage is disabled (oops.object-storage.enabled)")
        return True
    pytest.skip("cannot read the feature flags to check object storage")


def test_source_upload_returns_a_presigned_put(client, namespace, application,
                                               object_storage_enabled):
    """The API hands back a URL the client uploads to directly.

    OOPS never sees the bytes, which is the point — a large source bundle must
    not travel through the application server.
    """
    payload = make_zip()
    upload = client.create_source_upload(namespace, application,
                                         "source.zip", len(payload))

    for field in ("objectKey", "uploadUrl"):
        assert field in upload, f"the upload response is missing {field!r}"
    assert upload["uploadUrl"].startswith("http"), (
        f"uploadUrl is not a URL: {upload['uploadUrl']!r}")
    assert upload["objectKey"], "objectKey must identify the stored object"


def test_a_zip_can_be_uploaded_to_the_presigned_url(client, namespace,
                                                    application,
                                                    object_storage_enabled):
    """The presigned URL actually accepts the bytes.

    Signed URLs fail in ways that only show up on use — a clock skew, a wrong
    region, a signature that did not include the content type the client sends.
    """
    payload = make_zip()
    upload = client.create_source_upload(namespace, application,
                                         "source.zip", len(payload))

    status = client.put_bytes(
        upload["uploadUrl"], payload,
        {**(upload.get("headers") or {}), "Content-Type": "application/zip"})

    assert status in (200, 204), (
        f"the presigned PUT was rejected with HTTP {status}; the signature or "
        f"the headers it was signed for do not match what the client sent")


def test_an_oversized_upload_is_refused(client, namespace, application,
                                        object_storage_enabled):
    """`max-file-size-bytes` is enforced when the URL is requested, not after
    the upload — refusing afterwards would mean paying for the transfer first."""
    huge = 10 * 1024 * 1024 * 1024  # 10 GiB, comfortably over any sane limit
    result = client.post(
        f"/api/namespaces/{namespace}/applications/{application}"
        f"/deployments/source-upload",
        {"fileName": "huge.zip", "fileSize": huge,
         "contentType": "application/zip"},
        expect_success=False,
    )
    assert result.success is False, (
        f"a {huge} byte upload was granted a presigned URL; the size limit is "
        f"not being checked before signing")


@pytest.mark.cluster
def test_a_zip_source_builds_and_deploys(client, namespace, application,
                                         environment, object_storage_enabled):
    """End to end on the ZIP path: upload, build from the archive, roll out.

    The build's fetch step downloads and unzips instead of cloning, so this is
    the only test that covers that container at all.
    """
    from test_deploy import configure_for_build

    configure_for_build(client, namespace, application, environment)
    client.put_build_config(namespace, application, {
        "namespace": namespace,
        "applicationName": application,
        "sourceType": "ZIP",
        "environmentConfigs": [{"environment": environment, "buildCommand": ""}],
    })

    payload = make_zip()
    upload = client.create_source_upload(namespace, application,
                                         "source.zip", len(payload))
    status = client.put_bytes(
        upload["uploadUrl"], payload,
        {**(upload.get("headers") or {}), "Content-Type": "application/zip"})
    assert status in (200, 204), f"upload failed with HTTP {status}"

    pipeline_id = client.deploy(
        namespace, application, environment,
        strategy={"type": "ZIP", "objectKey": upload["objectKey"]})

    def finished():
        pipeline = client.get_pipeline(namespace, application, pipeline_id)
        return pipeline if pipeline["status"] in TERMINAL_STATUSES else None

    pipeline = wait_until(finished, timeout=DEPLOY_TIMEOUT,
                          description="the ZIP-sourced pipeline to finish")

    assert pipeline["status"] == "SUCCEEDED", (
        f"a ZIP-sourced deploy ended as {pipeline['status']}; the fetch step "
        f"could not download or unpack the archive")
