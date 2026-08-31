#!/usr/bin/env python3
"""Create the Git fixtures the suite builds from: one public repo, one private.

Gitea is seeded through its API rather than by pushing, so no git binary or
working copy is needed. Content is written with the contents endpoint, which
creates the initial commit on the default branch.

    python scripts/seed_gitea.py
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

DOCKERFILE = """FROM alpine:3.20
RUN echo "acceptance fixture" > /message
CMD ["sh", "-c", "while true; do cat /message; sleep 30; done"]
"""

README = "Fixture repository for the OOPS acceptance suite.\n"


def call(url: str, method: str = "GET", body: dict | None = None,
         auth: tuple[str, str] | None = None) -> tuple[int, dict | list | None]:
    request = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    if auth:
        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            return error.code, json.loads(payload) if payload else None
        except json.JSONDecodeError:
            return error.code, {"raw": payload.decode(errors="replace")[:200]}


def wait_for_gitea(base: str, attempts: int = 60) -> None:
    for _ in range(attempts):
        status, _ = call(f"{base}/api/healthz")
        if status == 200:
            return
        time.sleep(2)
    raise SystemExit(f"gitea never became healthy at {base}")


def ensure_user(container: str, username: str, password: str, email: str) -> None:
    """Gitea has no API for creating the first user, so use its CLI."""
    import subprocess

    listing = subprocess.run(
        ["docker", "exec", "-u", "git", container,
         "gitea", "admin", "user", "list"],
        capture_output=True, text=True)
    if username in listing.stdout:
        return
    result = subprocess.run(
        ["docker", "exec", "-u", "git", container,
         "gitea", "admin", "user", "create",
         "--username", username, "--password", password,
         "--email", email, "--admin", "--must-change-password=false"],
        capture_output=True, text=True)
    if result.returncode != 0 and "already exists" not in result.stderr:
        raise SystemExit(f"could not create the gitea user: {result.stderr}")


def ensure_repository(base: str, auth: tuple[str, str], name: str,
                      private: bool) -> None:
    status, _ = call(f"{base}/api/v1/repos/{auth[0]}/{name}", auth=auth)
    if status == 200:
        return
    status, payload = call(
        f"{base}/api/v1/user/repos", "POST",
        {"name": name, "private": private, "auto_init": True,
         "default_branch": "main",
         "description": "OOPS acceptance fixture"},
        auth=auth)
    if status not in (201, 409):
        raise SystemExit(f"could not create {name}: {status} {payload}")


def ensure_file(base: str, auth: tuple[str, str], repository: str, path: str,
                content: str, branch: str = "main") -> None:
    status, _ = call(
        f"{base}/api/v1/repos/{auth[0]}/{repository}/contents/{path}"
        f"?ref={branch}", auth=auth)
    if status == 200:
        return
    status, payload = call(
        f"{base}/api/v1/repos/{auth[0]}/{repository}/contents/{path}", "POST",
        {"content": base64.b64encode(content.encode()).decode(),
         "message": f"add {path}", "branch": branch},
        auth=auth)
    if status not in (201, 200, 422):
        raise SystemExit(f"could not write {path}: {status} {payload}")


def ensure_branch(base: str, auth: tuple[str, str], repository: str,
                  name: str, source: str = "main") -> None:
    status, _ = call(
        f"{base}/api/v1/repos/{auth[0]}/{repository}/branches/{name}", auth=auth)
    if status == 200:
        return
    call(f"{base}/api/v1/repos/{auth[0]}/{repository}/branches", "POST",
         {"new_branch_name": name, "old_branch_name": source}, auth=auth)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    # The published port, because this runs on the host. OOPS_GITEA_URL is the
    # container name the backend and the pipeline pods use, and does not resolve
    # out here.
    parser.add_argument("--base", default=os.environ.get(
        "OOPS_GITEA_PROBE_URL", "http://127.0.0.1:13000"))
    parser.add_argument("--container", default="oops-acceptance-gitea")
    parser.add_argument("--username", default=os.environ.get(
        "OOPS_GITEA_USER", "oops"))
    parser.add_argument("--password", default=os.environ.get(
        "OOPS_GITEA_PASSWORD", "oops-git-secret"))
    arguments = parser.parse_args()

    base = arguments.base.rstrip("/")
    auth = (arguments.username, arguments.password)

    wait_for_gitea(base)
    ensure_user(arguments.container, arguments.username, arguments.password,
                f"{arguments.username}@example.invalid")

    for repository, private in (("public-fixture", False),
                                ("private-fixture", True)):
        ensure_repository(base, auth, repository, private)
        ensure_file(base, auth, repository, "Dockerfile", DOCKERFILE)
        ensure_file(base, auth, repository, "README.md", README)
        # A second branch, so the branch picker has more than one thing to list.
        ensure_branch(base, auth, repository, "release")

    print(f"seeded {base}/{arguments.username}/public-fixture (public)")
    print(f"seeded {base}/{arguments.username}/private-fixture (private)")
    print()
    print(f'  export OOPS_GITEA_USER="{arguments.username}"')
    print(f'  export OOPS_GITEA_PASSWORD="{arguments.password}"')
    print('  export OOPS_GITEA_URL="http://gitea:3000"')
    print(f'  export OOPS_GITEA_PROBE_URL="{base}"')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
