"""A one-line channel from a test to whatever is rendering the run.

A deploy test spends minutes inside a single `wait_until`, and from the outside
that is indistinguishable from a hang. Anything that waits says what it is
waiting for through here, and the step renderer prints it under the module
currently running.

Nothing has to be listening. With no sink installed a call costs one comparison,
so the tests can speak up unconditionally and still run unchanged under a bare
`pytest`.
"""

from __future__ import annotations

from typing import Callable, Optional

_sink: Optional[Callable[[str], None]] = None


def set_sink(sink: Optional[Callable[[str], None]]) -> None:
    """Install the renderer's line handler, or None to go quiet again."""
    global _sink
    _sink = sink


def note(message: str) -> None:
    """Report what is happening right now. Never raises — a broken renderer must
    not fail the test it is describing."""
    if _sink is None:
        return
    try:
        _sink(message)
    except Exception:
        pass
