#!/usr/bin/env python3
"""OOPS PaaS CLI — pure Python 3.8+, stdlib only."""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

CONFIG_PATH = Path.home() / ".oops" / "config.toml"


def _load_config() -> Dict[str, str]:
    if not CONFIG_PATH.exists():
        return {}
    text = CONFIG_PATH.read_text()
    result: Dict[str, str] = {}
    for line in text.splitlines():
        m = re.match(r'^(\w+)\s*=\s*"(.*)"', line)
        if m:
            result[m.group(1)] = m.group(2)
    return result


def _save_config(cfg: Dict[str, str]) -> None:
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    lines = [f'{k} = "{v}"' for k, v in cfg.items()]
    CONFIG_PATH.write_text("\n".join(lines) + "\n")
    CONFIG_PATH.chmod(0o600)


def resolve_config(args: argparse.Namespace) -> Tuple[str, str]:
    cfg = _load_config()
    endpoint = (
        getattr(args, "endpoint", None)
        or os.environ.get("OOPS_ENDPOINT")
        or cfg.get("endpoint")
    )
    token = (
        getattr(args, "token", None)
        or os.environ.get("OOPS_TOKEN")
        or cfg.get("token")
    )
    if not endpoint:
        die("no endpoint configured — run `oops auth set --endpoint ... --token ...` or pass --endpoint")
    if not token:
        die("no token configured — run `oops auth set --endpoint ... --token ...` or pass --token")
    return endpoint.rstrip("/"), token


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------

class Client:
    def __init__(self, endpoint: str, token: str) -> None:
        self.endpoint = endpoint
        self.token = token

    def _request(self, method: str, path: str, body: Any = None, raw_url: Optional[str] = None) -> Any:
        url = raw_url if raw_url else f"{self.endpoint}{path}"
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Authorization": f"Bearer {self.token}"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            body_text = exc.read().decode(errors="replace")
            if exc.code == 401:
                die("401 Unauthorized — check token (run `oops auth set`)")
            if exc.code == 405:
                die("405 Method Not Allowed — DELETE is not exposed via /openapi")
            snippet = body_text[:500]
            die(f"{method} {url} → {exc.code} {snippet}")
        except urllib.error.URLError as exc:
            die(f"connection error: {exc.reason}")

    def get(self, path: str) -> Any:
        envelope = self._request("GET", path)
        return _unwrap(envelope, "GET", path)

    def post(self, path: str, body: Any) -> Any:
        envelope = self._request("POST", path, body)
        return _unwrap(envelope, "POST", path)

    def put(self, path: str, body: Any = None) -> Any:
        envelope = self._request("PUT", path, body)
        return _unwrap(envelope, "PUT", path)

    def put_raw_bytes(self, url: str, data: bytes, headers: Dict[str, str]) -> None:
        req = urllib.request.Request(url, data=data, headers=headers, method="PUT")
        try:
            with urllib.request.urlopen(req):
                pass
        except urllib.error.HTTPError as exc:
            die(f"PUT {url} → {exc.code} {exc.read().decode(errors='replace')[:500]}")
        except urllib.error.URLError as exc:
            die(f"connection error: {exc.reason}")


def _unwrap(envelope: Any, method: str, path: str) -> Any:
    if not isinstance(envelope, dict):
        return envelope
    if not envelope.get("success"):
        die(f"{method} {path}: {envelope.get('message', 'request failed')}")
    return envelope.get("data")


# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

def dash(value: Any) -> str:
    if value is None or value == "":
        return "-"
    return str(value)


def print_table(headers: List[str], rows: List[List[str]]) -> None:
    all_rows = [headers] + rows
    widths = [max(len(str(cell)) for cell in col) for col in zip(*all_rows)]
    fmt = "  ".join(f"{{:<{w}}}" for w in widths)
    print(fmt.format(*headers))
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print(fmt.format(*row))


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def render(use_json: bool, data: Any, human_fn) -> None:
    if use_json:
        print(json.dumps(data, indent=2))
    else:
        human_fn(data)


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_auth_set(client: None, args: argparse.Namespace) -> None:
    cfg = _load_config()
    cfg["endpoint"] = args.endpoint.rstrip("/")
    cfg["token"] = args.token
    _save_config(cfg)
    print(f"Saved: endpoint={cfg['endpoint']}")


def cmd_auth_status(args: argparse.Namespace) -> None:
    cfg = _load_config()
    endpoint = os.environ.get("OOPS_ENDPOINT") or cfg.get("endpoint") or "(not set)"
    token_raw = os.environ.get("OOPS_TOKEN") or cfg.get("token") or "(not set)"
    masked = token_raw[:8] + "..." if len(token_raw) > 8 else token_raw
    print(f"Endpoint: {endpoint}")
    print(f"Token:    {masked}")


def cmd_ns_ls(client: Client, args: argparse.Namespace) -> None:
    items = client.get("/openapi/namespaces")
    render(args.json, items, lambda lst: print_table(
        ["NAME", "DESCRIPTION", "CREATED"],
        [[dash(n.get("name")), dash(n.get("description")), dash(n.get("createdTime"))] for n in lst],
    ))


def cmd_env_ls(client: Client, args: argparse.Namespace) -> None:
    items = client.get("/openapi/environments")
    render(args.json, items, lambda lst: print_table(
        ["NAME", "K8S URL", "WORK NS", "IMAGE REPO"],
        [[
            dash(e.get("name")),
            dash((e.get("kubernetesApiServer") or {}).get("url")),
            dash(e.get("workNamespace")),
            dash((e.get("imageRepository") or {}).get("url")),
        ] for e in lst],
    ))


def cmd_domain_ls(client: Client, args: argparse.Namespace) -> None:
    items = client.get("/openapi/domains")
    render(args.json, items, lambda lst: print_table(
        ["HOST", "HTTPS", "CERT MODE", "UPLOADED", "DESCRIPTION"],
        [[
            dash(d.get("host")),
            str(bool(d.get("https"))),
            dash(d.get("certMode")),
            str(bool(d.get("hasUploadedCert"))),
            dash(d.get("description")),
        ] for d in lst],
    ))


# --- app ---

def cmd_app_ls(client: Client, args: argparse.Namespace) -> None:
    path = f"/openapi/namespaces/{args.namespace}/applications?page={args.page}&size={args.size}"
    if args.keyword:
        path += f"&keyword={urllib.parse.quote(args.keyword)}"
    result = client.get(path)
    items = result.get("data", []) if isinstance(result, dict) else result
    total = result.get("total", len(items)) if isinstance(result, dict) else len(items)
    def human(lst):
        print_table(
            ["NAME", "DESCRIPTION", "OWNER", "CREATED"],
            [[dash(a.get("name")), dash(a.get("description")), dash(a.get("ownerName")), dash(a.get("createdTime"))] for a in lst],
        )
        print(f"Total: {total}")
    render(args.json, items, human)


def cmd_app_get(client: Client, args: argparse.Namespace) -> None:
    app = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}")
    def human(a):
        print(f"Name:        {dash(a.get('name'))}")
        print(f"Namespace:   {dash(a.get('namespace'))}")
        print(f"Description: {dash(a.get('description'))}")
        print(f"Owner:       {dash(a.get('ownerName'))}")
        print(f"Created:     {dash(a.get('createdTime'))}")
    render(args.json, app, human)


def cmd_app_create(client: Client, args: argparse.Namespace) -> None:
    result = client.post(f"/openapi/namespaces/{args.namespace}/applications", {
        "name": args.name,
        "description": args.description,
        "namespace": args.namespace,
    })
    render(args.json, {"id": result, "name": args.name}, lambda _: print(f"Created application '{args.name}' (id={result})"))


def cmd_app_build_get(client: Client, args: argparse.Namespace) -> None:
    config = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/build/config")
    def human(c):
        print(f"Source:      {dash(c.get('sourceType'))}")
        print(f"Repository:  {dash(c.get('repository'))}")
        print(f"Build image: {dash(c.get('buildImage'))}")
        df = c.get("dockerFileConfig") or {}
        print(f"Dockerfile:  type={dash(df.get('type'))}, path={dash(df.get('path'))}")
    render(args.json, config, human)


def cmd_app_build_set(client: Client, args: argparse.Namespace) -> None:
    body = {
        "namespace": args.namespace,
        "applicationName": args.name,
        "sourceType": args.source.upper(),
        "repository": args.repository or "",
        "dockerFileConfig": {
            "type": args.dockerfile_type.upper(),
            "path": args.dockerfile_path,
            "content": args.dockerfile_content or "",
        },
        "buildImage": args.build_image or "",
    }
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/build/config", body)
    render(args.json, {"updated": True}, lambda _: print(f"Build config updated for {args.namespace}/{args.name}"))


def cmd_app_service_get(client: Client, args: argparse.Namespace) -> None:
    config = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/service")
    def human(c):
        print(f"Port: {dash(c.get('port'))}")
        envs = c.get("environmentConfigs") or []
        print_table(
            ["ENV", "HOST", "HTTPS"],
            [[dash(e.get("environmentName")), dash(e.get("host")), str(bool(e.get("https")))] for e in envs],
        )
    render(args.json, config, human)


def cmd_app_service_set(client: Client, args: argparse.Namespace) -> None:
    env_configs = []
    for entry in (args.env_host or []):
        env_name, rest = entry.split("=", 1)
        parts = rest.rsplit(":", 1)
        if len(parts) == 2 and parts[1] in ("https", "http"):
            host, https = parts[0], parts[1] == "https"
        else:
            host, https = rest, False
        env_configs.append({"environmentName": env_name, "host": host, "https": https})
    body = {
        "namespace": args.namespace,
        "applicationName": args.name,
        "port": args.port,
        "environmentConfigs": env_configs,
    }
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/service", body)
    render(args.json, {"updated": True}, lambda _: print(f"Service config updated for {args.namespace}/{args.name}"))


def cmd_app_runtime_get(client: Client, args: argparse.Namespace) -> None:
    spec = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/runtime-spec")
    def human(s):
        envs = s.get("environmentConfigs") or []
        def fmt_rl(req, lim):
            if req and lim:
                return f"{req}/{lim}"
            return dash(req or lim)
        print_table(
            ["ENV", "CPU req/limit", "MEM req/limit", "REPLICAS"],
            [[
                dash(e.get("environmentName")),
                fmt_rl(e.get("cpuRequest"), e.get("cpuLimit")),
                fmt_rl(e.get("memoryRequest"), e.get("memoryLimit")),
                dash(e.get("replicas")),
            ] for e in envs],
        )
        hc = s.get("healthCheck") or {}
        if hc:
            print(f"Health: enabled={hc.get('enabled', False)}, path={dash(hc.get('path'))}")
    render(args.json, spec, human)


def _parse_env_runtime(entry: str) -> Dict[str, Any]:
    env_name, rest = entry.split("=", 1)
    result: Dict[str, Any] = {"environmentName": env_name}
    for pair in rest.split(","):
        key, value = pair.split(":", 1)
        if key == "cpu":
            req, lim = (value.split("/", 1) + [value])[:2]
            result["cpuRequest"] = req
            result["cpuLimit"] = lim
        elif key in ("mem", "memory"):
            req, lim = (value.split("/", 1) + [value])[:2]
            result["memoryRequest"] = req
            result["memoryLimit"] = lim
        elif key == "replicas":
            result["replicas"] = int(value)
        else:
            die(f"unknown runtime key '{key}'")
    return result


def cmd_app_runtime_set(client: Client, args: argparse.Namespace) -> None:
    env_configs = [_parse_env_runtime(entry) for entry in (args.env or [])]
    body = {
        "namespace": args.namespace,
        "applicationName": args.name,
        "environmentConfigs": env_configs,
        "healthCheck": {
            "enabled": args.health,
            "path": args.health_path,
            "initialDelaySeconds": args.health_initial_delay,
            "periodSeconds": args.health_period,
            "timeoutSeconds": args.health_timeout,
            "failureThreshold": args.health_failure_threshold,
        },
    }
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/runtime-spec", body)
    render(args.json, {"updated": True}, lambda _: print(f"Runtime spec updated for {args.namespace}/{args.name}"))


def cmd_app_env_ls(client: Client, args: argparse.Namespace) -> None:
    items = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/environments")
    render(args.json, items, lambda lst: print_table(
        ["ENV"],
        [[dash(b.get("environmentName"))] for b in lst],
    ))


def cmd_app_env_set(client: Client, args: argparse.Namespace) -> None:
    body = [{"namespace": args.namespace, "applicationName": args.name, "environmentName": env} for env in args.env]
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/environments", body)
    render(args.json, {"updated": True, "count": len(args.env)}, lambda _: print(f"Bound {args.namespace}/{args.name} to {len(args.env)} environment(s)"))


def cmd_app_config_ls(client: Client, args: argparse.Namespace) -> None:
    path = f"/openapi/namespaces/{args.namespace}/applications/{args.name}/configmaps?environment={urllib.parse.quote(args.env)}"
    items = client.get(path)
    render(args.json, items, lambda lst: print_table(
        ["KEY", "VALUE"],
        [[dash(i.get("key")), dash(i.get("value"))] for i in lst],
    ))


def cmd_app_config_set(client: Client, args: argparse.Namespace) -> None:
    path = f"/openapi/namespaces/{args.namespace}/applications/{args.name}/configmaps?environment={urllib.parse.quote(args.env)}"
    body = []
    for kv in (args.kv or []):
        k, v = kv.split("=", 1)
        body.append({"key": k, "value": v})
    client.put(path, body)
    render(args.json, {"updated": True, "count": len(body)}, lambda _: print(f"ConfigMap updated for {args.namespace}/{args.name} (env={args.env}, {len(body)} entries)"))


# --- pipeline ---

TERMINAL_STATUSES = {"SUCCEEDED", "ERROR", "STOPPED", "BUILD_SUCCEEDED"}


def cmd_pipeline_ls(client: Client, args: argparse.Namespace) -> None:
    path = f"/openapi/namespaces/{args.namespace}/applications/{args.name}/pipelines?page={args.page}&size={args.size}"
    if args.env:
        path += f"&environment={urllib.parse.quote(args.env)}"
    result = client.get(path)
    items = result.get("data", []) if isinstance(result, dict) else result
    total = result.get("total", len(items)) if isinstance(result, dict) else len(items)
    def human(lst):
        print_table(
            ["ID", "STATUS", "ENV", "MODE", "OPERATOR", "CREATED"],
            [[
                dash(p.get("id")), dash(p.get("status")), dash(p.get("environment")),
                dash(p.get("deployMode")), dash(p.get("operatorName")), dash(p.get("createdTime")),
            ] for p in lst],
        )
        print(f"Total: {total}")
    render(args.json, items, human)


def cmd_pipeline_get(client: Client, args: argparse.Namespace) -> None:
    pipeline = client.get(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/pipelines/{args.id}")
    def human(p):
        print(f"ID:       {dash(p.get('id'))}")
        print(f"Status:   {dash(p.get('status'))}")
        print(f"Env:      {dash(p.get('environment'))}")
        print(f"Branch:   {dash(p.get('branch'))}")
        print(f"Artifact: {dash(p.get('artifact'))}")
        print(f"Mode:     {dash(p.get('deployMode'))}")
        print(f"Operator: {dash(p.get('operatorName'))}")
        print(f"Created:  {dash(p.get('createdTime'))}")
    render(args.json, pipeline, human)


def cmd_pipeline_stop(client: Client, args: argparse.Namespace) -> None:
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/pipelines/{args.id}/stop")
    render(args.json, {"stopped": args.id}, lambda _: print(f"Stop requested for pipeline {args.id}"))


def cmd_pipeline_deploy(client: Client, args: argparse.Namespace) -> None:
    client.put(f"/openapi/namespaces/{args.namespace}/applications/{args.name}/pipelines/{args.id}/deploy")
    render(args.json, {"deployed": args.id}, lambda _: print(f"Manual deploy triggered for pipeline {args.id}"))


def watch_pipeline(client: Client, namespace: str, name: str, pipeline_id: str, use_json: bool, interval: int = 3) -> Dict:
    path = f"/openapi/namespaces/{namespace}/applications/{name}/pipelines/{pipeline_id}"
    last_status = ""
    while True:
        pipeline = client.get(path)
        status = pipeline.get("status") or ""
        if status != last_status:
            if use_json:
                print(json.dumps({"id": pipeline_id, "status": status}))
            else:
                print(f"[{pipeline_id}] {status}")
            last_status = status
        if status in TERMINAL_STATUSES:
            if status == "ERROR":
                die("pipeline ended with ERROR")
            if status == "STOPPED":
                die("pipeline was STOPPED")
            return pipeline
        time.sleep(interval)


def cmd_pipeline_watch(client: Client, args: argparse.Namespace) -> None:
    watch_pipeline(client, args.namespace, args.name, args.id, args.json, args.interval)


# --- deploy ---

def _trigger_deploy(client: Client, namespace: str, name: str, env: str, mode: str, strategy: Dict, wait: bool, use_json: bool) -> None:
    body = {"environment": env, "deployMode": mode.upper(), "strategy": strategy}
    pipeline_id = client.post(f"/openapi/namespaces/{namespace}/applications/{name}/deployments", body)
    render(use_json, {"pipelineId": pipeline_id}, lambda _: print(f"Triggered pipeline {pipeline_id}"))
    if wait:
        watch_pipeline(client, namespace, name, pipeline_id, use_json, interval=3)


def cmd_deploy_git(client: Client, args: argparse.Namespace) -> None:
    strategy = {"type": "GIT", "branch": args.branch or ""}
    _trigger_deploy(client, args.namespace, args.name, args.env, args.mode, strategy, args.wait, args.json)


def cmd_deploy_zip(client: Client, args: argparse.Namespace) -> None:
    zip_path = Path(args.file)
    if not zip_path.exists():
        die(f"file not found: {zip_path}")
    data = zip_path.read_bytes()
    file_name = zip_path.name
    upload_resp = client.post(
        f"/openapi/namespaces/{args.namespace}/applications/{args.name}/deployments/source-upload",
        {"fileName": file_name, "fileSize": len(data), "contentType": "application/zip"},
    )
    object_key = upload_resp.get("objectKey", "")
    upload_url = upload_resp.get("uploadUrl", "")
    extra_headers = upload_resp.get("headers") or {}
    print(f"Uploading {file_name} ({len(data)} bytes) → object {object_key}")
    client.put_raw_bytes(upload_url, data, {**extra_headers, "Content-Type": "application/zip"})
    strategy = {"type": "ZIP", "repository": upload_resp.get("objectUrl", "")}
    _trigger_deploy(client, args.namespace, args.name, args.env, args.mode, strategy, args.wait, args.json)


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="oops", description="OOPS PaaS CLI")
    root.add_argument("--endpoint", help="OOPS server URL (overrides config/env)")
    root.add_argument("--token", help="Access token (overrides config/env)")
    root.add_argument("--json", action="store_true", default=False, help="Output JSON")
    sub = root.add_subparsers(dest="command", required=True)

    # auth
    auth_p = sub.add_parser("auth")
    auth_sub = auth_p.add_subparsers(dest="auth_cmd", required=True)
    auth_set = auth_sub.add_parser("set")
    auth_set.add_argument("--endpoint", required=True)
    auth_set.add_argument("--token", required=True)
    auth_sub.add_parser("status")

    # ns
    ns_p = sub.add_parser("ns")
    ns_sub = ns_p.add_subparsers(dest="ns_cmd", required=True)
    ns_sub.add_parser("ls")

    # env
    env_p = sub.add_parser("env")
    env_sub = env_p.add_subparsers(dest="env_cmd", required=True)
    env_sub.add_parser("ls")

    # domain
    domain_p = sub.add_parser("domain")
    domain_sub = domain_p.add_subparsers(dest="domain_cmd", required=True)
    domain_sub.add_parser("ls")

    # app
    app_p = sub.add_parser("app")
    app_sub = app_p.add_subparsers(dest="app_cmd", required=True)

    app_ls = app_sub.add_parser("ls")
    app_ls.add_argument("-n", "--namespace", required=True)
    app_ls.add_argument("--keyword")
    app_ls.add_argument("--page", type=int, default=1)
    app_ls.add_argument("--size", type=int, default=20)

    app_get = app_sub.add_parser("get")
    app_get.add_argument("-n", "--namespace", required=True)
    app_get.add_argument("name")

    app_create = app_sub.add_parser("create")
    app_create.add_argument("-n", "--namespace", required=True)
    app_create.add_argument("name")
    app_create.add_argument("--description", default="")

    # app build
    build_p = app_sub.add_parser("build")
    build_sub = build_p.add_subparsers(dest="build_cmd", required=True)
    build_get = build_sub.add_parser("get")
    build_get.add_argument("-n", "--namespace", required=True)
    build_get.add_argument("name")
    build_set = build_sub.add_parser("set")
    build_set.add_argument("-n", "--namespace", required=True)
    build_set.add_argument("name")
    build_set.add_argument("--source", default="git", choices=["git", "zip"])
    build_set.add_argument("--repository", default="")
    build_set.add_argument("--dockerfile-type", default="user", choices=["builtin", "user"], dest="dockerfile_type")
    build_set.add_argument("--dockerfile-path", default="Dockerfile", dest="dockerfile_path")
    build_set.add_argument("--dockerfile-content", dest="dockerfile_content")
    build_set.add_argument("--build-image", dest="build_image")

    # app service
    service_p = app_sub.add_parser("service")
    service_sub = service_p.add_subparsers(dest="service_cmd", required=True)
    svc_get = service_sub.add_parser("get")
    svc_get.add_argument("-n", "--namespace", required=True)
    svc_get.add_argument("name")
    svc_set = service_sub.add_parser("set")
    svc_set.add_argument("-n", "--namespace", required=True)
    svc_set.add_argument("name")
    svc_set.add_argument("--port", type=int, required=True)
    svc_set.add_argument("--env-host", action="append", dest="env_host", metavar="ENV=HOST[:https]")

    # app runtime
    runtime_p = app_sub.add_parser("runtime")
    runtime_sub = runtime_p.add_subparsers(dest="runtime_cmd", required=True)
    rt_get = runtime_sub.add_parser("get")
    rt_get.add_argument("-n", "--namespace", required=True)
    rt_get.add_argument("name")
    rt_set = runtime_sub.add_parser("set")
    rt_set.add_argument("-n", "--namespace", required=True)
    rt_set.add_argument("name")
    rt_set.add_argument("--env", action="append", metavar="ENV=cpu:r/l,mem:r/l,replicas:n")
    rt_set.add_argument("--health", action="store_true", default=False)
    rt_set.add_argument("--health-path", default="/", dest="health_path")
    rt_set.add_argument("--health-initial-delay", type=int, default=10, dest="health_initial_delay")
    rt_set.add_argument("--health-period", type=int, default=10, dest="health_period")
    rt_set.add_argument("--health-timeout", type=int, default=1, dest="health_timeout")
    rt_set.add_argument("--health-failure-threshold", type=int, default=3, dest="health_failure_threshold")

    # app env
    appenv_p = app_sub.add_parser("env")
    appenv_sub = appenv_p.add_subparsers(dest="env_cmd", required=True)
    ae_ls = appenv_sub.add_parser("ls")
    ae_ls.add_argument("-n", "--namespace", required=True)
    ae_ls.add_argument("name")
    ae_set = appenv_sub.add_parser("set")
    ae_set.add_argument("-n", "--namespace", required=True)
    ae_set.add_argument("name")
    ae_set.add_argument("--env", action="append", required=True, metavar="ENV_NAME")

    # app config (configmap)
    config_p = app_sub.add_parser("config")
    config_sub = config_p.add_subparsers(dest="config_cmd", required=True)
    cfg_get = config_sub.add_parser("get")
    cfg_get.add_argument("-n", "--namespace", required=True)
    cfg_get.add_argument("name")
    cfg_get.add_argument("--env", required=True)
    cfg_set = config_sub.add_parser("set")
    cfg_set.add_argument("-n", "--namespace", required=True)
    cfg_set.add_argument("name")
    cfg_set.add_argument("--env", required=True)
    cfg_set.add_argument("--kv", action="append", metavar="KEY=VALUE")

    # pipeline
    pipeline_p = sub.add_parser("pipeline")
    pipeline_sub = pipeline_p.add_subparsers(dest="pipeline_cmd", required=True)

    pl_ls = pipeline_sub.add_parser("ls")
    pl_ls.add_argument("-n", "--namespace", required=True)
    pl_ls.add_argument("name")
    pl_ls.add_argument("--env")
    pl_ls.add_argument("--page", type=int, default=1)
    pl_ls.add_argument("--size", type=int, default=10)

    pl_get = pipeline_sub.add_parser("get")
    pl_get.add_argument("-n", "--namespace", required=True)
    pl_get.add_argument("name")
    pl_get.add_argument("id")

    pl_stop = pipeline_sub.add_parser("stop")
    pl_stop.add_argument("-n", "--namespace", required=True)
    pl_stop.add_argument("name")
    pl_stop.add_argument("id")

    pl_deploy = pipeline_sub.add_parser("deploy")
    pl_deploy.add_argument("-n", "--namespace", required=True)
    pl_deploy.add_argument("name")
    pl_deploy.add_argument("id")

    pl_watch = pipeline_sub.add_parser("watch")
    pl_watch.add_argument("-n", "--namespace", required=True)
    pl_watch.add_argument("name")
    pl_watch.add_argument("id")
    pl_watch.add_argument("--interval", type=int, default=3)

    # deploy
    deploy_p = sub.add_parser("deploy")
    deploy_sub = deploy_p.add_subparsers(dest="deploy_cmd", required=True)

    dg = deploy_sub.add_parser("git")
    dg.add_argument("-n", "--namespace", required=True)
    dg.add_argument("name")
    dg.add_argument("--env", required=True)
    dg.add_argument("--branch")
    dg.add_argument("--mode", default="immediate", choices=["immediate", "manual"])
    dg.add_argument("--wait", action="store_true", default=False)

    dz = deploy_sub.add_parser("zip")
    dz.add_argument("-n", "--namespace", required=True)
    dz.add_argument("name")
    dz.add_argument("--env", required=True)
    dz.add_argument("--file", required=True)
    dz.add_argument("--mode", default="immediate", choices=["immediate", "manual"])
    dz.add_argument("--wait", action="store_true", default=False)

    return root


# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    # auth commands don't need a client
    if args.command == "auth":
        if args.auth_cmd == "set":
            cmd_auth_set(None, args)
        else:
            cmd_auth_status(args)
        return

    endpoint, token = resolve_config(args)
    client = Client(endpoint, token)

    dispatch = {
        ("ns", "ls"): lambda: cmd_ns_ls(client, args),
        ("env", "ls"): lambda: cmd_env_ls(client, args),
        ("domain", "ls"): lambda: cmd_domain_ls(client, args),
        ("app", "ls"): lambda: cmd_app_ls(client, args),
        ("app", "get"): lambda: cmd_app_get(client, args),
        ("app", "create"): lambda: cmd_app_create(client, args),
        ("app", "build"): {
            "get": lambda: cmd_app_build_get(client, args),
            "set": lambda: cmd_app_build_set(client, args),
        },
        ("app", "service"): {
            "get": lambda: cmd_app_service_get(client, args),
            "set": lambda: cmd_app_service_set(client, args),
        },
        ("app", "runtime"): {
            "get": lambda: cmd_app_runtime_get(client, args),
            "set": lambda: cmd_app_runtime_set(client, args),
        },
        ("app", "env"): {
            "ls": lambda: cmd_app_env_ls(client, args),
            "set": lambda: cmd_app_env_set(client, args),
        },
        ("app", "config"): {
            "get": lambda: cmd_app_config_ls(client, args),
            "set": lambda: cmd_app_config_set(client, args),
        },
        ("pipeline", "ls"): lambda: cmd_pipeline_ls(client, args),
        ("pipeline", "get"): lambda: cmd_pipeline_get(client, args),
        ("pipeline", "stop"): lambda: cmd_pipeline_stop(client, args),
        ("pipeline", "deploy"): lambda: cmd_pipeline_deploy(client, args),
        ("pipeline", "watch"): lambda: cmd_pipeline_watch(client, args),
        ("deploy", "git"): lambda: cmd_deploy_git(client, args),
        ("deploy", "zip"): lambda: cmd_deploy_zip(client, args),
    }

    if args.command == "app":
        entry = dispatch.get(("app", args.app_cmd))
        if isinstance(entry, dict):
            sub_cmd = getattr(args, f"{args.app_cmd}_cmd")
            fn = entry.get(sub_cmd)
        else:
            fn = entry
    elif args.command == "pipeline":
        fn = dispatch.get(("pipeline", args.pipeline_cmd))
    elif args.command == "deploy":
        fn = dispatch.get(("deploy", args.deploy_cmd))
    elif args.command == "ns":
        fn = dispatch.get(("ns", args.ns_cmd))
    elif args.command == "env":
        fn = dispatch.get(("env", args.env_cmd))
    elif args.command == "domain":
        fn = dispatch.get(("domain", args.domain_cmd))
    else:
        fn = None

    if fn is None:
        parser.print_help()
        sys.exit(1)
    fn()


if __name__ == "__main__":
    main()
