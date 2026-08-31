"""Renders the test run as numbered steps, the way `docker build` does.

The suite is twenty modules and the better part of an hour, and a single test
can spend two minutes building a container image. Dots tell you nothing about
that: you cannot tell a slow test from a hung one, or say which part of the
product is currently under test.

So each module is announced as a step and every test leaves one line behind
saying what it did. What a test is *doing* while it does it — the name it is
running under, the deploy it is waiting on — scrolls in a small region that
erases itself the moment the test settles, so an hour of waiting does not end up
as an hour of transcript.

    #8 [4/20] deploy — the build-and-deploy pipeline against a real cluster
    #8 6 tests
    #8   OK   12.4s  a build config survives a round trip
    #8 > a git repository builds and deploys              <- these two lines are
    #8   . waiting for the deploy to finish (45s of 900s)    on screen for now
    ...
    #8 DONE 380.4s  6 passed

pytest's own output is not touched: `pytest.log` keeps the -v line per test and
the whole FAILURES section, which is what you read after a failure. This writes
the live view to the descriptor `run.sh` leaves pointing at the terminal, since
pytest captures the other two.

Loaded with `-p` rather than from conftest.py, so it stays out of the way of the
suite itself.
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import runlog
from liveview import BOLD, DIM, GREEN, RED, RESET, YELLOW, View


def _duration(seconds: float) -> str:
    minutes, second = divmod(int(seconds), 60)
    if minutes >= 60:
        hour, minute = divmod(minutes, 60)
        return f"{hour}h{minute:02d}m"
    return f"{minutes}m{second:02d}s" if minutes else f"{seconds:.1f}s"


def _module_of(nodeid: str) -> str:
    """`test_zz_coverage.py::test_x` -> `coverage`.

    The zz prefix only exists to sort that file last; it is not part of what the
    module is called.
    """
    stem = Path(nodeid.split("::")[0]).stem
    if stem.startswith("test_"):
        stem = stem[len("test_"):]
    return stem[len("zz_"):] if stem.startswith("zz_") else stem


def _title(nodeid: str) -> str:
    """Test names in this suite already read as sentences; drop the syntax."""
    name = nodeid.split("::")[-1]
    if name.startswith("test_"):
        name = name[len("test_"):]
    return name.replace("_", " ")


def _summary_line(path: str) -> str:
    """The first line of the file's docstring, which says what the module covers."""
    try:
        with open(path, encoding="utf-8") as handle:
            first = handle.readline().strip()
    except OSError:
        return ""
    return first[3:].strip().rstrip('"') if first.startswith('"""') else ""


def _open_live_stream():
    """The descriptor `run.sh` left open on the terminal, else stderr.

    pytest's stdout and stderr are redirected into the log, so the live view
    needs a channel that was not redirected with them — and one that pytest's
    capture does not replace underneath it, which is why the waiting lines show
    up at all.
    """
    descriptor = os.environ.get("OOPS_LIVE_FD")
    if descriptor:
        try:
            return os.fdopen(int(descriptor), "w", buffering=1, closefd=False)
        except (OSError, ValueError):
            pass
    return sys.stderr


class Steps:
    def __init__(self, view: View, first_step: int):
        self.view = view
        self.step = first_step - 1

        self.order: list[str] = []
        self.sizes: dict[str, int] = {}
        self.paths: dict[str, str] = {}

        self.module: str | None = None
        self.module_started = 0.0
        self.counts = {"passed": 0, "failed": 0, "skipped": 0}
        self.test_started = 0.0
        self.reported = False

    # -- output -------------------------------------------------------------

    def keep(self, text: str, colour: str = "") -> None:
        self.view.commit(f"#{self.step} {text}", colour)

    def passing(self, text: str) -> None:
        self.view.push(f"#{self.step} {text}")

    # -- steps --------------------------------------------------------------

    def _open_module(self, module: str) -> None:
        self.step += 1
        self.module = module
        self.module_started = time.time()
        self.counts = {"passed": 0, "failed": 0, "skipped": 0}

        position = f"[{self.order.index(module) + 1}/{len(self.order)}]"
        summary = _summary_line(self.paths.get(module, ""))
        self.keep(f"{position} {module}" + (f" — {summary}" if summary else ""),
                  BOLD)
        size = self.sizes.get(module, 0)
        self.keep(f"{size} test{'' if size == 1 else 's'}", DIM)

    def _close_module(self) -> None:
        if self.module is None:
            return
        self.view.clear()
        tally = ", ".join(f"{count} {name}"
                          for name, count in self.counts.items() if count)
        elapsed = _duration(time.time() - self.module_started)
        if self.counts["failed"]:
            self.keep(f"FAILED {elapsed}  {tally}", RED)
        else:
            self.keep(f"DONE {elapsed}  {tally or 'nothing ran'}",
                      GREEN if self.counts["passed"] else YELLOW)
        self.module = None

    # -- pytest hooks -------------------------------------------------------

    def pytest_collection_finish(self, session):
        for item in session.items:
            module = _module_of(item.nodeid)
            if module not in self.sizes:
                self.order.append(module)
                self.sizes[module] = 0
                self.paths[module] = str(item.fspath)
            self.sizes[module] += 1
        # Anything that waits reports through here; it belongs with the test
        # that is waiting, so it goes in the scrolling region and leaves with it.
        runlog.set_sink(lambda message: self.passing(f"  . {message}"))

    def pytest_runtest_logstart(self, nodeid, location):
        module = _module_of(nodeid)
        if module != self.module:
            self._close_module()
            self._open_module(module)
        self.test_started = time.time()
        self.reported = False
        self.passing(f"> {_title(nodeid)}")

    def pytest_runtest_logreport(self, report):
        # setup-phase skips and errors never reach the call phase, so both
        # phases have to be able to close a test out — but only once.
        if self.reported or report.when not in ("setup", "call"):
            return
        elapsed = f"{_duration(time.time() - self.test_started):>7}"
        title = _title(report.nodeid)

        if report.failed:
            outcome, colour, extra = "FAIL", RED, self._reason(report)[:3]
        elif report.skipped:
            outcome, colour, extra = "SKIP", YELLOW, [self._skip_reason(report)]
        elif report.when == "call":
            outcome, colour, extra = "OK", GREEN, []
        else:
            return

        self.reported = True
        self.counts[{"OK": "passed", "FAIL": "failed",
                     "SKIP": "skipped"}[outcome]] += 1
        # The scrolling lines described a test that is now over; replace them
        # with the one line worth keeping.
        self.view.clear()
        self.keep(f"  {outcome:<4} {elapsed}  {title}", colour)
        for line in extra:
            if line:
                self.keep(f"         {line}", colour)

    def pytest_sessionfinish(self, session, exitstatus):
        runlog.set_sink(None)
        self._close_module()
        self.view.close()

    # -- failure text -------------------------------------------------------

    @staticmethod
    def _reason(report) -> list[str]:
        """The assertion, not the traceback — the full text is in pytest.log."""
        crash = getattr(getattr(report, "longrepr", None), "reprcrash", None)
        if crash is not None and getattr(crash, "message", ""):
            return crash.message.strip().splitlines()
        text = str(getattr(report, "longreprtext", "") or "").strip()
        return text.splitlines()[-3:] if text else ["no reason recorded"]

    @staticmethod
    def _skip_reason(report) -> str:
        longrepr = getattr(report, "longrepr", None)
        if isinstance(longrepr, tuple) and len(longrepr) == 3:
            reason = str(longrepr[2])
            return reason[len("Skipped: "):] if reason.startswith("Skipped: ") \
                else reason
        return ""


def pytest_configure(config):
    first = int(os.environ.get("OOPS_STEP_OFFSET", "0")) + 1
    view = View(_open_live_stream())
    config.pluginmanager.register(Steps(view, first), "oops-steps")
