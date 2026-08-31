"""A bounded, self-erasing tail on the terminal, the way `docker build` draws one.

Two kinds of line. What a run is worth reading afterwards — step headings, test
results, failures, how long each took — is committed, and stays. Log output is
pushed, and only the last few lines of it are on screen at any moment; when the
step ends the whole region is erased, leaving the committed lines behind. So the
terminal ends up holding the shape of the run rather than forty minutes of
container plumbing, while you can still watch that plumbing as it happens.

With nothing to redraw — a pipe, a CI log, TERM=dumb — everything is written
straight out and nothing is erased, which is the same fallback docker makes and
is what keeps `./run.sh > run.log` worth reading.
"""

from __future__ import annotations

import os
import shutil
from collections import deque

CURSOR_UP = "\033[{n}A"
ERASE_BELOW = "\033[J"

BOLD, DIM, GREEN, RED, YELLOW, RESET = (
    "\033[1m", "\033[2m", "\033[32m", "\033[31m", "\033[33m", "\033[0m")

DEFAULT_TAIL = 6


class View:
    def __init__(self, stream, tail: int = 0):
        self.stream = stream
        # A dumb terminal has no cursor movement, and a pipe has no cursor at
        # all; both get the plain transcript instead.
        self.live = (stream.isatty()
                     and os.environ.get("TERM", "") not in ("", "dumb"))
        self.colour = self.live and not os.environ.get("NO_COLOR")
        self.recent: deque = deque(
            maxlen=tail or int(os.environ.get("OOPS_LIVE_TAIL", DEFAULT_TAIL)))
        self.drawn = 0

    # -- the two kinds of line ----------------------------------------------

    def commit(self, text: str, colour: str = "") -> None:
        """A line that stays: a heading, a result, a failure."""
        self._erase()
        self._write(text, colour)
        self._draw()

    def push(self, text: str, colour: str = DIM) -> None:
        """A line of log: on screen for now, gone when the step ends."""
        if not self.live:
            self._write(text, colour)
            return
        self._erase()
        self.recent.append((text, colour))
        self._draw()

    def clear(self) -> None:
        """End the step: the log region goes, the committed lines stay."""
        self._erase()
        self.recent.clear()

    close = clear

    # -- drawing ------------------------------------------------------------

    def _write(self, text: str, colour: str) -> None:
        body = self._fit(text)
        if self.colour and colour:
            body = f"{colour}{body}{RESET}"
        self.stream.write(body + "\n")
        self.stream.flush()

    def _fit(self, text: str) -> str:
        """Truncate to the terminal width.

        A line that wraps takes two rows, and the cursor arithmetic below counts
        lines rather than rows, so a single wrapped line would leave the region
        misaligned and start eating the transcript above it.
        """
        room = max(20, shutil.get_terminal_size((100, 24)).columns - 1)
        return text if len(text) <= room else text[:room - 1] + "…"

    def _erase(self) -> None:
        if self.drawn:
            self.stream.write(CURSOR_UP.format(n=self.drawn) + ERASE_BELOW)
            self.drawn = 0

    def _draw(self) -> None:
        if not self.live:
            return
        for text, colour in self.recent:
            self._write(text, colour)
        self.drawn = len(self.recent)
