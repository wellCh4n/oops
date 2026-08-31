#!/usr/bin/env python3
"""Register the fixture cluster with a running OOPS as a named environment.

Reads what `cluster-provision.sh` exported. `run.sh` calls both in order; doing
it by hand looks like:

    eval "$(./scripts/cluster-provision.sh | grep '^  export' | sed 's/^  //')"
    python scripts/register_environment.py --name acceptance
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from oops_client import OopsClient  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="acceptance",
                        help="environment name in OOPS")
    parser.add_argument("--endpoint",
                        default=os.environ.get("OOPS_ENDPOINT", "http://localhost:8080"))
    parser.add_argument("--api-server", default=os.environ.get("OOPS_CLUSTER_API_SERVER"))
    parser.add_argument("--token", default=os.environ.get("OOPS_CLUSTER_TOKEN"))
    parser.add_argument("--work-namespace",
                        default=os.environ.get("OOPS_WORK_NAMESPACE", "oops-work"))
    parser.add_argument("--registry", default=os.environ.get("OOPS_REGISTRY", ""),
                        help="image registry the build pushes to")
    # The local registry accepts anything, but these must not be blank:
    # syncImagePullSecret() skips creating the `dockerhub` secret when either is
    # empty, while the build job mounts that secret unconditionally — so blank
    # credentials leave every build pod stuck at Init:0/2 on a missing volume.
    parser.add_argument("--registry-username",
                        default=os.environ.get("OOPS_REGISTRY_USERNAME", "oops"))
    parser.add_argument("--registry-password",
                        default=os.environ.get("OOPS_REGISTRY_PASSWORD", "oops"))
    parser.add_argument("--username", default=os.environ.get("OOPS_USERNAME", "admin"))
    parser.add_argument("--password", default=os.environ.get("OOPS_PASSWORD", "admin123"))
    arguments = parser.parse_args()

    if not arguments.api_server or not arguments.token:
        parser.error("--api-server and --token are required "
                     "(cluster-provision.sh prints them as OOPS_CLUSTER_* "
                     "exports)")

    client = OopsClient(arguments.endpoint)
    client.login(arguments.username, arguments.password)

    existing = [item for item in client.list_environments()
                if item["name"] == arguments.name]
    if existing:
        print(f"environment {arguments.name!r} already registered, leaving it alone")
        return 0

    body = {
        "name": arguments.name,
        "kubernetesApiServer": {
            "url": arguments.api_server,
            "token": arguments.token,
        },
        "workNamespace": arguments.work_namespace,
        "imageRepository": {
            "url": arguments.registry,
            "username": arguments.registry_username,
            "password": arguments.registry_password,
        },
    }
    created = client.post("/api/environments", body).data
    print(f"registered environment {arguments.name!r} -> {created.get('id')}")
    print(f"run the cluster tier with:  pytest --environment {arguments.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
