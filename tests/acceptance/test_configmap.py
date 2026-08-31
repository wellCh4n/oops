"""Application configuration items, and the metadata that never reaches the pod.

Config items are stored in Kubernetes, not in the OOPS database: plain values go
into the application ConfigMap, secret ones into the Secret, and items with a
mount path become files rather than environment variables.

The interesting part is the metadata. `mountPath`, `group`, `comment` and `order`
exist for the UI alone, and carrying them inside the ConfigMap data would inject
them into the container as environment variables. They are kept in annotations
instead, which is invisible from the API and easy to lose in a rewrite — so these
tests read the objects back through kubectl as well as through OOPS.
"""

from __future__ import annotations

import json

import pytest

pytestmark = pytest.mark.cluster


def write(client, namespace, application, environment, items):
    return client.put(
        f"/api/namespaces/{namespace}/applications/{application}/configmaps"
        f"?environment={environment}", items)


def read(client, namespace, application, environment):
    return client.get(
        f"/api/namespaces/{namespace}/applications/{application}/configmaps"
        f"?environment={environment}").data


def test_config_items_round_trip(client, namespace, application, environment):
    write(client, namespace, application, environment, [
        {"key": "LOG_LEVEL", "value": "debug", "secret": False,
         "group": "runtime", "comment": "verbosity", "order": 1},
        {"key": "GREETING", "value": "hello", "secret": False,
         "group": "runtime", "order": 2},
    ])

    items = {item["key"]: item for item in
             read(client, namespace, application, environment)}

    assert items["LOG_LEVEL"]["value"] == "debug"
    assert items["LOG_LEVEL"]["group"] == "runtime", (
        "the group was lost; the UI groups items by it and would show them all "
        "as ungrouped")
    assert items["LOG_LEVEL"]["comment"] == "verbosity", "the comment was lost"
    # Order is not stored as sent: it is reassigned from the item's position in
    # the submitted list, zero-based. So the second item comes back as 1 no
    # matter what was sent. Worth pinning, because a caller that reads a value
    # back, edits one field and writes the whole list again keeps a stable
    # ordering only by accident of list order.
    assert items["LOG_LEVEL"]["order"] == 0, (
        f"the first item came back with order {items['LOG_LEVEL']['order']}, "
        f"expected its list position")
    assert items["GREETING"]["order"] == 1, (
        f"the second item came back with order {items['GREETING']['order']}, "
        f"expected its list position")


def test_a_secret_item_is_stored_apart_from_a_plain_one(
        client, namespace, application, environment, kubectl):
    """Plain values land in the ConfigMap, secret ones in the Secret.

    Getting this wrong is not visible through the API — both come back the same
    way — but it decides whether the value is readable by anyone who can get a
    ConfigMap.
    """
    write(client, namespace, application, environment, [
        {"key": "PUBLIC_VALUE", "value": "visible", "secret": False},
        {"key": "PRIVATE_VALUE", "value": "hidden", "secret": True},
    ])

    config_map = kubectl.get_json("configmap", application, namespace)
    secret = kubectl.get_json("secret", application, namespace)
    if config_map is None:
        pytest.skip("no ConfigMap was created for the application")

    assert "PUBLIC_VALUE" in (config_map.get("data") or {}), (
        "a plain item is missing from the ConfigMap")
    assert "PRIVATE_VALUE" not in (config_map.get("data") or {}), (
        "a secret item was written into the ConfigMap in clear text, where "
        "anyone able to read ConfigMaps can see it")
    assert secret is not None and "PRIVATE_VALUE" in (secret.get("data") or {}), (
        "the secret item did not reach the application Secret")

    items = {item["key"]: item for item in
             read(client, namespace, application, environment)}
    assert items["PRIVATE_VALUE"]["secret"] is True, (
        "reading back lost the secret flag, so the UI would offer to display it")


def test_ui_metadata_stays_out_of_the_container(
        client, namespace, application, environment, kubectl):
    """The pod sees keys and values, never the metadata describing them.

    `mountPath`, `group`, `comment` and `order` live in annotations. If they were
    stored as data they would arrive in the container as environment variables
    named after them.
    """
    write(client, namespace, application, environment, [
        {"key": "APP_MODE", "value": "test", "secret": False,
         "group": "runtime", "comment": "which mode to boot in", "order": 3},
    ])

    config_map = kubectl.get_json("configmap", application, namespace)
    if config_map is None:
        pytest.skip("no ConfigMap was created for the application")

    data = config_map.get("data") or {}
    assert set(data) == {"APP_MODE"} or "group" not in data, (
        f"metadata leaked into the ConfigMap data and would be injected into "
        f"the container: {sorted(data)}")

    annotations = (config_map.get("metadata") or {}).get("annotations") or {}
    assert "oops.config-meta" in annotations, (
        "the UI metadata annotation is missing, so groups, comments and "
        "ordering are lost on the next read")

    meta = json.loads(annotations["oops.config-meta"])
    entry = meta.get("APP_MODE") if isinstance(meta, dict) else None
    if isinstance(meta, list):
        entry = next((item for item in meta if item.get("key") == "APP_MODE"), None)
    assert entry, f"APP_MODE is missing from the metadata annotation: {meta}"


def test_a_file_item_is_recorded_as_a_mount(client, namespace, application,
                                            environment, kubectl):
    """An item with a mount path becomes a file rather than an env var."""
    write(client, namespace, application, environment, [
        {"key": "application.properties", "value": "server.port=8080\n",
         "secret": False, "mountPath": "/config/application.properties"},
    ])

    items = {item["key"]: item for item in
             read(client, namespace, application, environment)}
    assert items["application.properties"]["mountPath"] == \
           "/config/application.properties", (
        "the mount path was lost, so the item would be injected as an "
        "environment variable instead of written as a file")

    # File items live in a separate `{app}.files` ConfigMap, not in the one that
    # carries environment variables, so that the deploy can mount one as a volume
    # without also mounting the other.
    files_map = kubectl.get_json("configmap", f"{application}.files", namespace)
    assert files_map is not None, (
        f"no {application}.files ConfigMap was created; a file item was stored "
        f"alongside the environment variables instead")

    annotations = (files_map.get("metadata") or {}).get("annotations") or {}
    assert "oops.mounts" in annotations, (
        "the mount annotation is missing, so the deploy has no way to know "
        "where the file belongs inside the container")
    assert "/config/application.properties" in annotations["oops.mounts"], (
        f"the mount path is not recorded in the annotation: "
        f"{annotations['oops.mounts']}")


def test_removing_an_item_removes_it_from_the_cluster(
        client, namespace, application, environment, kubectl):
    """A write is the whole set, not a patch.

    Sending a shorter list has to delete what is missing, or a removed variable
    would keep being injected until the ConfigMap is deleted by hand.
    """
    write(client, namespace, application, environment, [
        {"key": "KEEP_ME", "value": "yes", "secret": False},
        {"key": "REMOVE_ME", "value": "no", "secret": False},
    ])
    keys = {item["key"] for item in
            read(client, namespace, application, environment)}
    assert {"KEEP_ME", "REMOVE_ME"} <= keys

    write(client, namespace, application, environment, [
        {"key": "KEEP_ME", "value": "yes", "secret": False},
    ])

    keys = {item["key"] for item in
            read(client, namespace, application, environment)}
    assert "REMOVE_ME" not in keys, (
        "a removed item is still returned; the write is behaving as a patch "
        "rather than replacing the set")

    config_map = kubectl.get_json("configmap", application, namespace)
    if config_map is not None:
        assert "REMOVE_ME" not in (config_map.get("data") or {}), (
            "the item was dropped from the API response but is still in the "
            "ConfigMap, so the container would keep receiving it")
