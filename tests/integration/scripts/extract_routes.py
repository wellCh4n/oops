#!/usr/bin/env python3
"""Extract the API route table from the Spring controllers.

The suite's coverage check needs to know what the full surface *is*, and the
only authority on that is the source. Parsing the annotations keeps the
inventory honest: add a controller and the coverage test starts failing until a
scenario exercises it.

    python scripts/extract_routes.py > routes.json
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CONTROLLERS = Path("src/main/java/com/github/wellch4n/oops/interfaces/rest")

# Match up to the closing paren rather than the first brace: a path variable
# spelled {namespace} would otherwise terminate an array-form mapping early, and
# the controller would silently drop out of the inventory.
CLASS_MAPPING = re.compile(r"@RequestMapping\s*\(([^)]*)\)", re.S)
METHOD_MAPPING = re.compile(
    r"@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([^)]*)\))?", re.S)
STRING = re.compile(r'"([^"]*)"')
# Endpoints the UI never calls over plain HTTP, or that cannot be driven from a
# test without a browser redirect or a live upgrade handshake.
EXCLUDED = {
    "/api/auth/external/{provider}/redirect",  # 302 into a third-party login
    "/api/auth/external/{provider}/callback",  # requires a provider round trip
}


def class_prefixes(source: str) -> list[str]:
    match = CLASS_MAPPING.search(source)
    if not match:
        return [""]
    return STRING.findall(match.group(1)) or [""]


def join(prefix: str, suffix: str) -> str:
    if not suffix:
        return prefix
    if not prefix:
        return suffix
    return f"{prefix.rstrip('/')}/{suffix.lstrip('/')}"


def normalise(path: str) -> str:
    """Collapse every path variable to a single spelling.

    Controllers disagree — `{name}` here, `{applicationName}` there, for the same
    segment — and a coverage report keyed on the spelling would double-count.
    """
    return re.sub(r"\{[^}]+\}", "{}", path)


def extract(root: Path) -> list[dict]:
    routes: list[dict] = []
    for file in sorted(root.glob("*Controller.java")):
        source = file.read_text()
        prefixes = class_prefixes(source)
        for match in METHOD_MAPPING.finditer(source):
            verb = match.group(1).upper()
            suffixes = STRING.findall(match.group(2) or '""') or [""]
            for prefix in prefixes:
                if not prefix.startswith("/api"):
                    # The suite drives the UI surface; /openapi mirrors it.
                    continue
                for suffix in suffixes:
                    path = join(prefix, suffix)
                    if path in EXCLUDED:
                        continue
                    routes.append({
                        "method": verb,
                        "path": path,
                        "key": f"{verb} {normalise(path)}",
                        "controller": file.stem,
                    })
    unique: dict[str, dict] = {}
    for route in routes:
        unique.setdefault(route["key"], route)
    return sorted(unique.values(), key=lambda item: item["key"])


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else CONTROLLERS
    if not root.exists():
        print(f"no controllers at {root}; run from the repository root",
              file=sys.stderr)
        return 1
    routes = extract(root)
    json.dump(routes, sys.stdout, indent=2)
    sys.stdout.write("\n")
    print(f"{len(routes)} routes", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
