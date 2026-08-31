#!/usr/bin/env python3
"""Render a JUnit XML run as a short terminal summary.

The HTML report is for reading afterwards; this is what you want in the terminal
and in CI logs, where the useful signal is which tests failed and why, not a
progress bar.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

GREEN, RED, YELLOW, DIM, RESET = (
    "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m")


def main(path: Path) -> int:
    if not path.exists():
        print(f"no report at {path}", file=sys.stderr)
        return 1

    root = ElementTree.parse(path).getroot()
    suites = root.findall("testsuite") or [root]

    total = failures = errors = skipped = 0
    duration = 0.0
    problems: list[tuple[str, str, str]] = []

    for suite in suites:
        total += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        duration += float(suite.get("time", 0) or 0)

        for case in suite.findall("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is not None:
                    name = f"{case.get('classname', '')}::{case.get('name', '')}"
                    message = (node.get("message") or "").strip().splitlines()
                    problems.append((kind, name, message[0] if message else ""))

    passed = total - failures - errors - skipped
    width = 68

    print("=" * width)
    print("OOPS acceptance report".center(width))
    print("=" * width)
    print(f"  {GREEN}passed{RESET}   {passed}")
    if failures:
        print(f"  {RED}failed{RESET}   {failures}")
    if errors:
        print(f"  {RED}errored{RESET}  {errors}")
    if skipped:
        print(f"  {YELLOW}skipped{RESET}  {skipped}")
    print(f"  {DIM}total{RESET}    {total}   {DIM}in {duration:.1f}s{RESET}")

    if problems:
        print("-" * width)
        for kind, name, message in problems:
            print(f"  {RED}{kind.upper()}{RESET} {name}")
            if message:
                print(f"        {DIM}{message[:width - 10]}{RESET}")

    print("=" * width)
    if failures or errors:
        print(f"{RED}FAILED{RESET} — {failures + errors} of {total} did not pass")
        return 1
    if total == 0:
        print(f"{YELLOW}no tests ran{RESET}")
        return 1
    if skipped:
        print(f"{GREEN}PASSED{RESET} — {passed}/{total}, {skipped} skipped")
    else:
        print(f"{GREEN}PASSED{RESET} — all {total} tests")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: summarise.py <junit.xml>", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(Path(sys.argv[1])))
