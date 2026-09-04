"""Every endpoint must be reached by some scenario.

Named to sort last, because it judges what the rest of the run did: the client
records each (method, path) it calls, and this compares that against the route
table extracted from the controllers.

The point is that coverage cannot quietly rot. Add a controller and this test
fails until a scenario exercises it — which forces the new surface into a
scenario rather than into a list of endpoints someone pings for a green tick.

It only means something after a full run. On a filtered run — `-k`, the contract
tier alone, or `./run.sh --module deploy` — most routes are legitimately
untouched, so it skips instead of failing.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from oops_client import CALLED

ROUTES_FILE = Path(__file__).parent / "routes.json"

# Routes the suite reaches without going through OopsClient, or that only a
# browser can drive. Each needs a reason; an entry without one is a hole.
UNREACHABLE = {
    "GET /api/": "the SPA index, served to browsers",
    "GET /api/{}": "the SPA catch-all, served to browsers",
}


def normalise(path: str) -> str:
    return re.sub(r"\{[^}]+\}", "{}", path.split("?", 1)[0])


def load_routes() -> list[dict]:
    if not ROUTES_FILE.exists():
        pytest.skip(f"no {ROUTES_FILE.name}; run scripts/extract_routes.py")
    return json.loads(ROUTES_FILE.read_text())


def segments_match(template: str, actual: str) -> bool:
    """A concrete path matches a template when the literal segments line up."""
    left, right = template.strip("/").split("/"), actual.strip("/").split("/")
    if len(left) != len(right):
        return False
    return all(expected == "{}" or expected == got
               for expected, got in zip(left, right))


def called_keys() -> set[str]:
    """Fold every recorded call onto the route template it hit."""
    routes = load_routes()
    matched: set[str] = set()
    for method, path in CALLED:
        actual = normalise(path)
        for route in routes:
            if route["method"] != method:
                continue
            if segments_match(normalise(route["path"]), actual):
                matched.add(route["key"])
                break
    return matched


def is_filtered(config) -> bool:
    """Whether this run was narrowed to part of the suite.

    Selecting modules is the common case — `--module deploy` reaches the routes
    deploying uses and nothing else, which is not a coverage regression.
    """
    if config.getoption("-k") or config.getoption("-m"):
        return True
    return any(Path(argument.split("::")[0]).name.startswith("test_")
               for argument in config.args)


def test_every_endpoint_is_exercised_by_a_scenario(request):
    routes = load_routes()
    if is_filtered(request.config):
        pytest.skip("coverage is only meaningful on a full, unfiltered run")

    reached = called_keys()
    missing = sorted(route["key"] for route in routes
                     if route["key"] not in reached
                     and route["key"] not in UNREACHABLE)

    if missing:
        by_controller: dict[str, list[str]] = {}
        for route in routes:
            if route["key"] in missing:
                by_controller.setdefault(route["controller"], []).append(
                    route["key"])
        report = "\n".join(
            f"  {controller}:\n" + "\n".join(f"    {key}" for key in sorted(keys))
            for controller, keys in sorted(by_controller.items()))
        pytest.fail(
            f"{len(missing)} of {len(routes)} endpoints are not reached by any "
            f"scenario:\n{report}\n\n"
            f"Add them to a scenario that exercises them meaningfully, or list "
            f"them in UNREACHABLE with the reason they cannot be driven.")


def test_coverage_report(record_testsuite_property):
    """Always-passing companion that puts the number in the report.

    Recorded on the suite rather than the test case: the xunit2 schema the
    JUnit file follows has no room for per-case properties, and the figure
    describes the whole run anyway.
    """
    routes = load_routes()
    reached = called_keys()
    covered = len([r for r in routes if r["key"] in reached])
    percentage = 100.0 * covered / len(routes) if routes else 0.0
    record_testsuite_property("endpoints_total", len(routes))
    record_testsuite_property("endpoints_covered", covered)
    record_testsuite_property("endpoint_coverage_percent", round(percentage, 1))
    print(f"\nendpoint coverage: {covered}/{len(routes)} ({percentage:.1f}%)")
