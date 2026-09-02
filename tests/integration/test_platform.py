"""The platform surface — everything that exists before a single application does.

Namespaces, application search, feature flags, health, cron previews, static
assets, registry and cluster validation, node scheduling. This is the tier an
operator meets first, while registering a cluster or checking a node, and it is
almost entirely read-only, which is exactly why it rots unnoticed: nothing
downstream breaks when a listing quietly starts answering with an empty array.

Most of it needs no cluster. Only nodes, service accounts and the Kubernetes
validation endpoints genuinely do, and only those carry the `cluster` marker.
"""

from __future__ import annotations

import re
import urllib.parse
import uuid

import pytest

from test_contract import ID_PATTERN, TIMESTAMP_PATTERN

# The cron preview renders in the server's own zone, to the minute, with a space
# rather than a `T` — it is display text for the expert-config UI, not a timestamp.
CRON_RUN_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$")

# A port nothing listens on, so the connection is refused rather than timing out.
UNREACHABLE_REGISTRY = "http://127.0.0.1:1"

PLATFORM_NAMESPACE = "integration-platform"


# -- fixtures ---------------------------------------------------------------


@pytest.fixture(scope="session")
def platform_namespace(client) -> str:
    """A namespace row to read and update, created once and then reused.

    Creation is conditional on purpose. The API has no delete for namespaces and
    happily stores the same name twice, so creating unconditionally would leave
    one more duplicate row behind on every run of the suite.
    """
    registered = [item["name"] for item in client.get("/api/namespaces").data]
    if PLATFORM_NAMESPACE not in registered:
        client.post("/api/namespaces",
                    {"name": PLATFORM_NAMESPACE,
                     "description": "created by the integration suite"})
    return PLATFORM_NAMESPACE


@pytest.fixture(scope="session")
def assets_available(client) -> bool:
    """Static assets live in object storage, which is off by default."""
    features = client.get("/api/features", expect_success=False)
    if features.success and isinstance(features.data, dict):
        if not features.data.get("objectStorage", False):
            pytest.skip("object storage is disabled (oops.object-storage.enabled)")
        return True
    pytest.skip("cannot read the feature flags to check object storage")


@pytest.fixture
def asset_keys(client, assets_available):
    """Removes uploaded assets afterwards, even when a test fails part way."""
    keys: list[str] = []
    yield keys
    for key in reversed(keys):
        client.delete(f"/api/assets?key={urllib.parse.quote(key)}",
                      expect_success=False)


@pytest.fixture
def cordoned_nodes(client, environment):
    """Puts every node the test cordoned back into service.

    A node left unschedulable is invisible until some later test waits out its
    whole deploy timeout wondering why its build pod never started.
    """
    names: list[str] = []
    yield names
    for name in reversed(names):
        client.post(
            f"/api/nodes/{name}/schedulable"
            f"?environment={environment}&schedulable=true",
            expect_success=False)


@pytest.fixture
def cluster_namespaces(kubectl):
    """Removes work namespaces the validation endpoint created.

    The API can create one and has no endpoint to remove it, so the cleanup has
    to go around the API and talk to the cluster directly.
    """
    names: list[str] = []
    yield names
    for name in reversed(names):
        kubectl._run("delete", "namespace", name,
                     "--ignore-not-found=true", "--wait=false")


# -- health and features ----------------------------------------------------


def test_health_answers_ok(client):
    """The liveness probe every deployment of OOPS is wired to."""
    result = client.get("/api/health")
    assert result.data == "ok", (
        f"health answered {result.data!r} rather than 'ok'; container probes "
        f"and load balancers compare the body, not just the status")


def test_features_report_the_optional_subsystems(client):
    """The frontend hides whole sections on these flags, so a missing key is not
    a cosmetic problem — it reads as false and the feature disappears."""
    features = client.get("/api/features").data

    for flag in ("feishu", "ide", "ideHttps", "objectStorage"):
        assert flag in features, (
            f"the feature flags are missing {flag!r}; the UI treats an absent "
            f"flag as disabled and silently drops that navigation group")
        assert isinstance(features[flag], bool), (
            f"feature flag {flag!r} is {features[flag]!r}, not a boolean")

    assert "ideHost" in features, (
        "ideHost is missing; the IDE links are built from it and become "
        "unreachable without it")


# -- namespaces -------------------------------------------------------------


def test_a_namespace_is_listed_with_an_identity_and_a_naive_timestamp(
        client, platform_namespace):
    listing = client.get("/api/namespaces").data
    assert isinstance(listing, list), "the namespace listing is not an array"

    matched = [item for item in listing if item["name"] == platform_namespace]
    assert matched, (
        f"{platform_namespace} was created but does not appear in the namespace "
        f"listing")

    namespace = matched[0]
    assert ID_PATTERN.match(namespace["id"]), (
        f"namespace id {namespace['id']!r} is not a 24-char NanoId")
    assert TIMESTAMP_PATTERN.match(namespace["createdTime"]), (
        f"createdTime {namespace['createdTime']!r} is not a naive local "
        f"timestamp — a zone suffix means values are being converted")
    assert "description" in namespace, (
        "the namespace listing carries no description, which is the only thing "
        "the settings page has to show about a namespace")


def test_a_namespace_description_can_be_updated(client, platform_namespace):
    """Update is keyed on the name, not on the id — the body has no id field at
    all, so a rename is impossible by construction."""
    description = f"updated {uuid.uuid4().hex[:8]}"
    client.put("/api/namespaces",
               {"name": platform_namespace, "description": description})

    listing = client.get("/api/namespaces").data
    stored = [item["description"] for item in listing
              if item["name"] == platform_namespace]
    assert description in stored, (
        f"the namespace update reported success but {description!r} is not in "
        f"the listing; the write went somewhere else or was discarded")


def test_updating_an_unregistered_namespace_fails(client):
    """Update does not create. A caller that relied on upsert semantics would
    silently lose the description instead of being told."""
    result = client.put(
        "/api/namespaces",
        {"name": f"never-registered-{uuid.uuid4().hex[:8]}", "description": "x"},
        expect_success=False)
    assert result.success is False, (
        "updating a namespace that was never created reported success; the "
        "endpoint is quietly upserting")


def test_invalid_namespace_names_are_rejected(client):
    """The same resource-name rules as applications, applied before the row is
    written — a namespace name ends up in Kubernetes object names."""
    for candidate in ("UPPERCASE", "has space", "trailing-", "-leading",
                      "dots.not.ok"):
        result = client.post("/api/namespaces",
                             {"name": candidate, "description": "x"},
                             expect_success=False)
        assert result.success is False, (
            f"{candidate!r} was accepted as a namespace name and is now a row "
            f"that cannot be deleted through the API")


def test_an_application_namespace_needs_no_namespace_row(client):
    """Documents a split the URLs give no hint of.

    `/api/namespaces/{namespace}/applications` reads as though the namespace has
    to exist first, but the path segment is a free-form string on the
    application row and the `namespace` table is an unrelated, purely
    descriptive listing. Applications can therefore live in namespaces the
    settings page has never heard of.

    Written against today's behaviour. If namespaces ever become a referenced
    entity, this test fails and should be inverted rather than deleted — the
    change is visible to anyone who ever typed a namespace into a URL.
    """
    unregistered = f"unregistered-{uuid.uuid4().hex[:8]}"
    application = f"acc-{uuid.uuid4().hex[:8]}"

    try:
        client.create_application(unregistered, application)
        listed = [item["name"] for item in client.get("/api/namespaces").data]
        assert unregistered not in listed, (
            f"creating an application in {unregistered!r} also registered the "
            f"namespace; the two are now coupled and this test should be "
            f"inverted")
        profile = client.get_application(unregistered, application).data
        assert profile["namespace"] == unregistered, (
            "the application did not keep the namespace it was created in")
    finally:
        client.delete_application(unregistered, application,
                                  expect_success=False)


# -- search -----------------------------------------------------------------


def test_search_matches_a_name_fragment_regardless_of_case(client, application):
    """The command palette types into this endpoint, so a case-sensitive match
    would mean half the keystrokes find nothing."""
    fragment = application.split("-")[1]

    for keyword in (fragment, fragment.upper()):
        results = client.get(
            f"/api/search/applications?keyword={keyword}&size=20").data
        names = [item["name"] for item in results]
        assert application in names, (
            f"searching for {keyword!r} did not find {application}; the match "
            f"is not a case-insensitive substring")

    match = [item for item in client.get(
        f"/api/search/applications?keyword={fragment}&size=20").data
        if item["name"] == application][0]
    for field in ("id", "name", "namespace", "owner", "sourceType"):
        assert field in match, (
            f"the search result is missing {field!r}; the palette needs it to "
            f"build the link it navigates to")


def test_search_honours_the_size_limit(client, application):
    """`size` caps the results rather than paging them — there is no cursor, so
    a caller asking for one gets exactly the first one."""
    fragment = application.split("-")[1]
    results = client.get(
        f"/api/search/applications?keyword={fragment}&size=1").data
    assert len(results) <= 1, (
        f"size=1 returned {len(results)} results; the limit is not applied")

    # size=0 is not treated as "unlimited", which is worth pinning: the opposite
    # convention would flood the palette on an empty query.
    empty = client.get(
        f"/api/search/applications?keyword={fragment}&size=0").data
    assert empty == [], (
        f"size=0 returned {len(empty)} results, so zero is being read as "
        f"unbounded rather than as none")


def test_search_for_an_unknown_name_returns_an_empty_list(client):
    """An empty result is a successful search, not a failure — a caller checking
    `success` must not be told the search broke."""
    result = client.get(
        "/api/search/applications?keyword=definitely-not-an-application-9f3a2b")
    assert result.success is True
    assert result.data == [], (
        f"a search that matched nothing answered with {result.data!r} rather "
        f"than an empty list")


# -- cron preview -----------------------------------------------------------


def test_cron_preview_returns_the_next_fire_times(client):
    """The scheduled-restart UI shows these before the user saves, so they are
    the only feedback that an expression means what was intended."""
    runs = client.get("/api/cron/next?expression=0+9+*+*+*&count=3").data

    assert len(runs) == 3, f"asked for 3 fire times, got {len(runs)}"
    for run in runs:
        assert CRON_RUN_PATTERN.match(run), (
            f"fire time {run!r} is not `yyyy-MM-dd HH:mm`; the UI renders the "
            f"string as-is")
        assert run.endswith("09:00"), (
            f"`0 9 * * *` produced {run!r}; the leading field is being read as "
            f"the hour, so a 5-field expression is not being padded with "
            f"seconds")
    assert runs == sorted(runs), "fire times came back out of order"


def test_cron_preview_clamps_the_count_between_one_and_five(client):
    """Clamped rather than rejected, so the preview can never be asked to
    enumerate a year of fire times."""
    many = client.get("/api/cron/next?expression=*/5+*+*+*+*&count=99").data
    assert len(many) == 5, (
        f"count=99 returned {len(many)} fire times; the upper clamp of 5 is "
        f"missing and a caller can make the server enumerate arbitrarily far")

    none = client.get("/api/cron/next?expression=*/5+*+*+*+*&count=0").data
    assert len(none) == 1, (
        f"count=0 returned {len(none)} fire times, expected the lower clamp of 1")

    default = client.get("/api/cron/next?expression=*/5+*+*+*+*").data
    assert len(default) == 1, (
        f"omitting count returned {len(default)} fire times, expected 1")


def test_cron_accepts_six_field_expressions_and_macros(client):
    """The UI offers 5 fields, but 6-field expressions and `@` macros pass
    straight through to the scheduler, so they are part of the contract whether
    or not anything types them."""
    six_field = client.get("/api/cron/next?expression=0+0+9+*+*+*&count=1").data
    assert six_field and six_field[0].endswith("09:00"), (
        f"a 6-field expression produced {six_field!r}; the leading seconds "
        f"field is being padded a second time")

    macro = client.get("/api/cron/next?expression=%40daily&count=1").data
    assert macro and macro[0].endswith("00:00"), (
        f"`@daily` produced {macro!r}; macros are no longer passed through")


def test_an_invalid_cron_expression_is_refused(client):
    """Refused inside the envelope rather than thrown, because the UI previews
    on every keystroke and half-typed expressions are the normal case."""
    for expression in ("not-a-cron", "0+9+*", "99+9+*+*+*"):
        result = client.get(f"/api/cron/next?expression={expression}&count=1",
                            expect_success=False)
        assert result.success is False, (
            f"{expression!r} was accepted as a cron expression; an unparseable "
            f"schedule would be stored and simply never fire")
        assert result.status == 200, (
            f"an invalid expression answered HTTP {result.status}; the preview "
            f"must not surface as a request error")


# -- static assets ----------------------------------------------------------


def test_asset_upload_url_is_presigned_under_the_asset_prefix(client,
                                                              assets_available):
    """Assets and build sources share a bucket and are kept apart only by the
    key prefix, so a signed URL that escaped it would let the asset browser list
    and delete build sources."""
    upload = client.post("/api/assets/upload-url", {
        "path": "integration",
        "fileName": "note.txt",
        "contentType": "text/plain",
        "fileSize": 12,
    }).data

    for field in ("objectKey", "objectUrl", "uploadUrl", "headers"):
        assert field in upload, f"the upload response is missing {field!r}"
    assert upload["objectKey"].startswith("oops-assets/"), (
        f"objectKey {upload['objectKey']!r} is outside the asset prefix; the "
        f"asset browser would be able to reach build sources")
    assert upload["uploadUrl"].startswith("http"), (
        f"uploadUrl is not a URL: {upload['uploadUrl']!r}")

    # The stored content type is guessed from the extension, not taken from the
    # caller, so a browser renders the file instead of downloading it.
    assert upload["headers"].get("Content-Type") == "text/plain", (
        f"the signed headers carry {upload['headers']!r}; the upload will be "
        f"rejected unless the client sends exactly what was signed")


def test_an_uploaded_asset_is_listed_and_can_be_deleted(client, asset_keys):
    """The whole asset lifecycle, because each step is only observable through
    the next one: the signature is only proven by an upload, the upload only by
    the listing, and the delete only by the listing going empty again."""
    folder = f"integration-{uuid.uuid4().hex[:8]}"
    payload = b"integration asset\n"

    upload = client.post("/api/assets/upload-url", {
        "path": folder,
        "fileName": "note.txt",
        "contentType": "text/plain",
        "fileSize": len(payload),
    }).data
    asset_keys.append(upload["objectKey"])

    status = client.put_bytes(upload["uploadUrl"], payload,
                              dict(upload.get("headers") or {}))
    assert status in (200, 204), (
        f"the presigned PUT was rejected with HTTP {status}; the signature does "
        f"not match the headers the client was told to send")

    root = client.get("/api/assets").data
    folders = [item["name"] for item in root if item["type"] == "FOLDER"]
    assert folder in folders, (
        f"{folder} holds an object but is not listed as a folder at the root; "
        f"the listing is not synthesising folders from key prefixes")

    listing = client.get(f"/api/assets?path={folder}").data
    entries = [item for item in listing if item["name"] == "note.txt"]
    assert entries, f"the uploaded asset is absent from the {folder} listing"

    entry = entries[0]
    assert entry["type"] == "FILE"
    assert entry["size"] == len(payload), (
        f"the listing reports {entry['size']} bytes for a {len(payload)} byte "
        f"upload")
    assert entry["contentType"] == "text/plain", (
        f"the listing reports {entry['contentType']!r}; a browser opening the "
        f"public URL would download the file rather than render it")
    for field in ("publicUrl", "signedUrl"):
        assert entry[field], (
            f"the asset entry has no {field}; the browser has no way to reach "
            f"the object")

    client.delete(f"/api/assets?key={urllib.parse.quote(entry['key'])}")

    remaining = client.get(f"/api/assets?path={folder}").data
    assert remaining == [], (
        f"the asset survived its delete: {remaining!r}")


def test_asset_upload_url_validates_its_input(client, assets_available):
    """Every one of these is checked before the URL is signed. Signing first and
    refusing afterwards would mean paying for the transfer to learn it was
    never allowed."""
    rejected = [
        ({"path": "integration", "fileName": "", "fileSize": 12},
         "a blank file name"),
        ({"path": "integration", "fileName": "note.txt", "fileSize": 0},
         "a zero byte size"),
        ({"path": "integration", "fileName": "note.txt",
          "fileSize": 10 * 1024 * 1024 * 1024},
         "a size beyond the configured limit"),
        ({"path": "../escape", "fileName": "note.txt", "fileSize": 12},
         "a traversing path"),
        ({"path": "integration", "fileName": "../escape.txt", "fileSize": 12},
         "a traversing file name"),
    ]
    for body, description in rejected:
        result = client.post("/api/assets/upload-url", body,
                             expect_success=False)
        assert result.success is False, (
            f"{description} was granted a presigned URL; the object would land "
            f"outside the asset prefix or beyond the size limit with nothing "
            f"left to stop it")


def test_deleting_a_key_outside_the_asset_prefix_is_refused(client,
                                                            assets_available):
    """Delete takes a raw object key, so the prefix check is the only thing
    standing between the asset browser and the build sources in the same
    bucket."""
    for key in ("build-sources/some-source.zip",
                "oops-assets/../build-sources/some-source.zip",
                ""):
        result = client.delete(f"/api/assets?key={urllib.parse.quote(key)}",
                               expect_success=False)
        assert result.success is False, (
            f"the key {key!r} was accepted for deletion; anything in the bucket "
            f"can be removed through the asset browser")


# -- image repositories -----------------------------------------------------


def test_image_repository_validation_needs_all_three_fields(client):
    """Validation short-circuits to false unless url, username and password are
    all present, which matches `syncImagePullSecret`: with any of them blank no
    pull secret is written at all."""
    incomplete = [
        {"url": "", "username": "", "password": ""},
        {"url": "http://registry.invalid", "username": "", "password": ""},
        {"url": "http://registry.invalid", "username": "oops", "password": ""},
    ]
    for repository in incomplete:
        result = client.post("/api/image-repositories/validations", repository)
        assert result.data is False, (
            f"{repository} validated as usable; the environment form would let "
            f"an operator save a registry that produces no pull secret")


def test_image_repository_validation_fails_for_an_unreachable_registry(client):
    """A refused connection is a false verdict rather than an error envelope, so
    the form can show a red tick instead of a stack trace.

    The probe is a plain GET of the registry root, which a Docker registry
    leaves unauthenticated — a true verdict therefore means the host answered,
    not that the credentials work. Documented rather than endorsed; validating
    against `/v2/` would actually check them.
    """
    result = client.post("/api/image-repositories/validations", {
        "url": UNREACHABLE_REGISTRY,
        "username": "oops",
        "password": "oops-secret",
    })
    assert result.success is True, (
        f"an unreachable registry produced a failed envelope: {result.message}")
    assert result.data is False, (
        "an unreachable registry validated as usable; every environment would "
        "pass validation regardless of what it points at")


# -- service accounts (cluster) ---------------------------------------------


@pytest.mark.cluster
def test_service_accounts_of_a_namespace_are_listed(client, environment):
    """Expert config offers these as the ServiceAccount a workload runs under,
    so the list has to come from the cluster rather than from OOPS's own rows.

    Asked of the Kubernetes `default` namespace because every cluster has one
    and every namespace has a `default` ServiceAccount inside it.
    """
    accounts = client.get(
        f"/api/namespaces/default/service-accounts?environment={environment}").data

    assert isinstance(accounts, list), "the service account listing is not an array"
    assert "default" in accounts, (
        f"the `default` namespace reported {accounts!r}; every Kubernetes "
        f"namespace has a default ServiceAccount, so the listing is not "
        f"reaching the cluster")


@pytest.mark.cluster
def test_service_accounts_of_an_absent_namespace_are_empty(client, environment):
    """A namespace that does not exist in the cluster is an empty list, not an
    error — expert config asks for this before the namespace is ever created."""
    absent = f"never-created-{uuid.uuid4().hex[:8]}"
    result = client.get(
        f"/api/namespaces/{absent}/service-accounts?environment={environment}",
        expect_success=False)
    assert result.success is True, (
        f"listing service accounts of an absent namespace failed: "
        f"{result.message}")
    assert result.data == [], (
        f"an absent namespace reported {result.data!r} rather than an empty list")


@pytest.mark.cluster
def test_service_accounts_of_an_unregistered_environment_fail(client):
    """The environment name is looked up before the cluster is touched, so an
    unknown name must not be silently treated as "no accounts"."""
    result = client.get(
        "/api/namespaces/default/service-accounts?environment=not-registered-9f3a",
        expect_success=False)
    assert result.success is False, (
        "an unregistered environment answered with a service account listing; "
        "a typo in the environment name would look like an empty cluster")


# -- nodes (cluster) --------------------------------------------------------


@pytest.mark.cluster
def test_nodes_report_readiness_capacity_and_versions(client, environment):
    """The cluster page renders one row per node from exactly these fields, and
    a missing one shows as a blank cell rather than as an error."""
    nodes = client.get(f"/api/nodes?environment={environment}").data

    assert nodes, (
        "the environment reported no nodes at all; either the credentials "
        "cannot list nodes or the cluster is empty")

    for node in nodes:
        for field in ("name", "hostname", "ready", "schedulable", "roles",
                      "internalIP", "kubeletVersion", "osImage",
                      "containerRuntimeVersion", "cpu", "memory", "pods",
                      "creationTimestamp"):
            assert field in node, (
                f"node {node.get('name')!r} is missing {field!r}; the cluster "
                f"page renders it as an empty cell with no explanation")
        assert isinstance(node["ready"], bool), (
            f"`ready` is {node['ready']!r}, not a boolean")
        assert isinstance(node["schedulable"], bool), (
            f"`schedulable` is {node['schedulable']!r}, not a boolean")


@pytest.mark.cluster
def test_a_node_can_be_cordoned_and_uncordoned(client, environment,
                                               cordoned_nodes):
    """Cordoning is the one destructive thing the cluster page can do, and it is
    only observable through the listing that follows it."""
    nodes = client.get(f"/api/nodes?environment={environment}").data
    schedulable = [node for node in nodes if node["schedulable"]]
    if not schedulable:
        pytest.skip("every node is already cordoned; nothing to toggle")
    target = schedulable[0]["name"]

    cordoned_nodes.append(target)
    client.post(f"/api/nodes/{target}/schedulable"
                f"?environment={environment}&schedulable=false")

    after_cordon = client.get(f"/api/nodes?environment={environment}").data
    state = {node["name"]: node["schedulable"] for node in after_cordon}
    assert state[target] is False, (
        f"{target} reported success for the cordon but is still schedulable; "
        f"draining a node would silently do nothing")

    client.post(f"/api/nodes/{target}/schedulable"
                f"?environment={environment}&schedulable=true")

    after_uncordon = client.get(f"/api/nodes?environment={environment}").data
    restored = {node["name"]: node["schedulable"] for node in after_uncordon}
    assert restored[target] is True, (
        f"{target} could be cordoned but not returned to service; the cluster "
        f"is now short a node and every later deploy in this run will wait out "
        f"its timeout")


@pytest.mark.cluster
def test_listing_nodes_of_an_unregistered_environment_fails(client):
    result = client.get("/api/nodes?environment=not-registered-9f3a",
                        expect_success=False)
    assert result.success is False, (
        "an unregistered environment answered with a node listing; a typo in "
        "the environment name would look like a cluster with no nodes")


# -- Kubernetes validation (cluster) ----------------------------------------


@pytest.mark.cluster
def test_validation_passes_for_a_reachable_cluster(client, cluster_credentials):
    """The environment form calls this before saving, so its verdict is the only
    thing between an operator and an environment that cannot deploy anything."""
    result = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": cluster_credentials["token"],
        },
        "workNamespace": cluster_credentials["workNamespace"],
    }).data

    assert result["success"] is True, (
        f"validating a reachable cluster with an existing work namespace "
        f"reported {result['status']}: {result['message']}")
    assert result["status"] == "VALID", (
        f"expected status VALID, got {result['status']!r}; the form branches on "
        f"this string, not on the boolean")


@pytest.mark.cluster
def test_validation_reports_a_missing_work_namespace_distinctly(
        client, cluster_credentials):
    """`NAMESPACE_MISSING` is a different verdict from a failed connection
    because it is the recoverable one — the form offers to create the namespace
    on exactly this status and on no other."""
    result = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": cluster_credentials["token"],
        },
        "workNamespace": f"never-created-{uuid.uuid4().hex[:8]}",
    }).data

    assert result["success"] is False
    assert result["status"] == "NAMESPACE_MISSING", (
        f"a reachable cluster with an absent namespace reported "
        f"{result['status']!r}; the operator loses the offer to create it and "
        f"is told the connection failed instead")


@pytest.mark.cluster
def test_validation_reports_an_unusable_credential_as_a_connection_failure(
        client, cluster_credentials):
    """A rejected token and an unreachable host collapse into one verdict, which
    is coarse but is what the form is written against."""
    result = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": {
            "url": cluster_credentials["url"],
            "token": "definitely-not-a-service-account-token",
        },
        "workNamespace": cluster_credentials["workNamespace"],
    }).data

    assert result["success"] is False, (
        "a rejected token validated successfully; the environment would be "
        "saved and every deploy against it would fail instead")
    assert result["status"] == "CONNECTION_FAILED", (
        f"expected CONNECTION_FAILED, got {result['status']!r}")


@pytest.mark.cluster
def test_a_work_namespace_can_be_created_and_then_validates(
        client, cluster_credentials, cluster_namespaces):
    """The recovery path from `NAMESPACE_MISSING`, end to end.

    Creating twice is part of the contract rather than an afterthought: the
    button is one click away from the validation that offered it, and a double
    click must not leave the operator staring at a Kubernetes 409.
    """
    name = f"integration-work-{uuid.uuid4().hex[:8]}"
    api_server = {
        "url": cluster_credentials["url"],
        "token": cluster_credentials["token"],
    }
    cluster_namespaces.append(name)

    created = client.post("/api/kubernetes/namespaces", {
        "kubernetesApiServer": api_server,
        "workNamespace": name,
    })
    assert created.data is True, (
        f"creating the work namespace {name} reported {created.data!r}")

    validated = client.post("/api/kubernetes/validations", {
        "kubernetesApiServer": api_server,
        "workNamespace": name,
    }).data
    assert validated["status"] == "VALID", (
        f"the namespace was created but validation still reports "
        f"{validated['status']!r}; the operator is stuck in a loop of creating "
        f"a namespace that already exists")

    # Creating twice is idempotent: the API server's 409 is treated as the
    # namespace already being satisfactory rather than surfaced as a failure.
    # It used to be the latter, which left a double click looking like an error
    # for a state that was exactly what the operator asked for.
    again = client.post("/api/kubernetes/namespaces", {
        "kubernetesApiServer": api_server,
        "workNamespace": name,
    }, expect_success=False)
    assert again.success is True, (
        "creating the same work namespace twice failed; a double click on the "
        "button the validation offers must not report an error for a namespace "
        "that already exists")
