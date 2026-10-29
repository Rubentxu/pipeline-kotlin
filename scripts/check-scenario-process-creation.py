#!/usr/bin/env python3
"""A scenario may not create a process.

THE GUARANTEE
  A `.pipeline.kts` program reaches the outside world through the steps the
  product offers. Creating a process from the program itself is not one of
  those steps: it is a second authority that the product does not know about,
  does not account for and cannot refuse. The `sh` step is the sanctioned
  door, because the product owns it end to end -- the temporaries it names,
  the deadline it puts on it, the exit code it reads back.

  The rule is about CREATING a process. Asking about the process that is
  already running is a different fact, and several scenarios need it: the
  cancel and recover scenarios have to publish the runner's own pid, and the
  way to learn it is the process handle of the current process. That is a
  question, not a spawn, and the rule does not forbid questions.

WHY A SCRIPT AND NOT A LINTER RULE
  detekt 2.x cannot express this guarantee over a language that is not its
  own: the scenarios are `.kts` programs, not Kotlin sources under
  `src/main`, so no module's detekt run sees them. A rule that only ever ran
  where the file is not would read as a green and protect nothing.

HOW THE CALL SITE IS FOUND
  By reading the source as source, not as text. The tokeniser that
  `check-process-exits.py` already owns blanks comments and string contents
  and keeps the offsets, so a name inside a comment or inside the shell
  script of a step is not a call, and a name in the code is. The tokeniser is
  imported, not copied: two copies of a tokeniser drift, and the copy that
  drifts is the one nobody is looking at.

WHAT IS SCANNED
  Every `.pipeline.kts` program in the working tree, and not only the ones
  under `examples/`: the rule is about the language, not about a directory.
  The working tree and not the Git index, for the same reason the exit guard
  reads the working tree: an index-only scan does not see a file that has
  not been added yet, and a green that arrives before the file does is not a
  green.

Exit status is 0 when no scenario creates a process, and 1 with the
offenders when one does.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

# The tokeniser of the exit guard. It lives in a file whose name carries
# hyphens, so it is loaded by path and not by import.
_TOKENISER_PATH = Path(__file__).resolve().parent / "check-process-exits.py"
_spec = importlib.util.spec_from_file_location("check_process_exits", _TOKENISER_PATH)
if _spec is None or _spec.loader is None:  # pragma: no cover - the file is next door
    print(f"NO SE ENCONTRO el tokenizador en {_TOKENISER_PATH}", file=sys.stderr)
    raise SystemExit(2)
_tokeniser = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_tokeniser)
mask_kotlin = _tokeniser.mask_kotlin

# Every way a program can ask the operating system for a new process.
#
# `ProcessBuilder` is the class itself: constructing it is the beginning of
# the act, and there is no use of it that does not lead to one.
#
# `Runtime.getRuntime` is the runtime that hands a command to the operating
# system; there is no use of it that does not launch.
#
# `ProcessHandle.start` is the static launcher of the same API family.
#
# The bare word `Process` and the bare word `ProcessHandle` are NOT here:
# naming the process that is already running is a question, and the scenarios
# that cancel or recover a run need to ask it.
FORBIDDEN: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bProcessBuilder\b"), "la clase que construye un proceso"),
    (re.compile(r"\bRuntime\s*\.\s*getRuntime\b"), "el runtime que lanza un proceso"),
    (re.compile(r"\bProcessHandle\s*\.\s*start\b"), "el lanzador de un manejador de proceso"),
)


def scenario_programs(repo: Path) -> list[Path]:
    """Every `.pipeline.kts` program in the working tree."""
    found: list[Path] = []
    for path in repo.rglob("*.pipeline.kts"):
        parts = path.relative_to(repo).parts
        if any(part.startswith(".") or part in ("build", "out") for part in parts):
            continue
        found.append(path)
    return sorted(found)


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    offenders: list[tuple[str, int, str]] = []
    programs = scenario_programs(repo)
    total_calls = 0
    for path in programs:
        try:
            source = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        masked = mask_kotlin(source)
        for pattern, reason in FORBIDDEN:
            for match in pattern.finditer(masked):
                total_calls += 1
                line = source.count("\n", 0, match.start()) + 1
                offenders.append((str(path.relative_to(repo)), line, reason))
    for rel, line, reason in offenders:
        print(f"  {rel}:{line}  {reason} -- un escenario no crea procesos")
    if offenders:
        print(f"\nCREACION DE PROCESOS EN UN ESCENARIO: {len(offenders)}")
        print(f"  {total_calls} creacion(es) de proceso en {len(programs)} escenarios")
        print("Un escenario alcanza el exterior por los pasos del producto. La puerta es `sh`.")
        return 1
    print(f"OK: {total_calls} creacion(es) de proceso en {len(programs)} escenarios")
    return 0


if __name__ == "__main__":
    sys.exit(main())
