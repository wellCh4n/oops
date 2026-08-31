#!/usr/bin/env python3
"""Show one command's output as a bounded tail that erases itself when it ends.

`run.sh` pipes a step's combined output through this. Every line is appended to
the step's log unchanged, and the last few are on screen while the step runs;
when it finishes the region disappears and `run.sh` prints the one line that is
worth keeping.

The command's exit status arrives as a sentinel line, because a pipe carries
output and not status, and the step's DONE or ERROR depends on it. This exits
with that status, so a caller reading the end of the pipeline learns what the
command did.

Only the standard library, and Python 3.9: this runs before the virtualenv
exists, since creating the virtualenv is itself a step.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from liveview import View  # noqa: E402

SENTINEL = "__step_status__"
# docker compose writes cursor control sequences even into a pipe, which arrive
# as a stray [K in the middle of a line.
ANSI = re.compile(r"\033\[[0-9;]*[A-Za-z]")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="", help="step marker, e.g. #4")
    parser.add_argument("--log", help="file to append every line to, verbatim")
    arguments = parser.parse_args()

    log = open(arguments.log, "a", encoding="utf-8") if arguments.log else None
    view = View(sys.stdout)
    status = 0
    try:
        for raw in sys.stdin:
            line = raw.rstrip("\n")
            if line.startswith(SENTINEL):
                status = int(line.split()[-1] or 0)
                continue
            if log:
                log.write(line + "\n")
                log.flush()
            line = ANSI.sub("", line).replace("\r", "").strip()
            if line:
                view.push(f"{arguments.prefix} {line}" if arguments.prefix
                          else line)
    except KeyboardInterrupt:
        status = 130
    finally:
        # Whatever happened, the terminal goes back to the caller with the
        # cursor where it started and no half-drawn region on it.
        view.clear()
        if log:
            log.close()
    return status


if __name__ == "__main__":
    raise SystemExit(main())
