#!/usr/bin/env python3
"""The durable shell path spends no probe process and no fixed wait.

THE GUARANTEE
  Every process the durable shell path creates, and every wait it makes, is
  DECLARED here with the purpose it serves. A declaration whose purpose is a
  probe — a process whose only object is to ask the platform whether a tool
  exists — or a fixed wait — a wait whose only object is to let a state be
  decided by its absence — is refused. The count of each of those is zero,
  and the count is taken over the code of the live path, not over a sample of
  what a run happened to do.

WHY A SCRIPT AND NOT A UNIT TEST
  The guarantee is about the CODE, not about one execution: "no auxiliary
  process exists whose only object is to probe `setsid`" is a statement about
  the tree, and a unit test can only observe one execution. A unit test that
  reads the source would be source-text fitness, which the policy forbids
  inside unit tests; a script that reads the source is what the repository's
  other guards do, and this one is the guard for that guarantee.

  The guard is therefore a census, not a linter: it does not judge the code,
  it COMPARES it against a table that a human reads and changes. A cost that
  is not in the table fails; a row in the table that is not in the code fails
  too, because a table that describes a code that no longer exists is a
  second, stale authority.

THE VOCABULARY
  A process is created to serve the execution:
    child     the execution's own child, or the worker that serves it.
    protocol  a named file of the durable protocol (a FIFO).
    signal    the delivery of a signal that ends the execution.
    question  a process whose only object is to ASK whether the execution's
              own session is still there. It changes nothing.
    probe     FORBIDDEN. A process whose only object is to ask the platform
              whether a TOOL is available, before the work begins.

  The line between `question` and `probe` is where the work happens, not how
  much it costs: a question is asked on the expired path about the execution
  that is dying, and a probe is asked on every path about the host's
  capabilities. A probe's answer changes only the launch's confidence; a
  question's answer decides whether a sweep is needed.

  A wait is made to serve the execution:
    cadence   the wait re-reads a state and continues while the state says
              "not yet". The state decides when it ends.
    grace     the window a dying process is given before the next step.
    deadline  the wait for a moment the operator named.
    fixed     FORBIDDEN. A wait whose duration is fixed and whose only object
              is to let a state be decided by its absence.

HOW THE OWNING FUNCTION IS FOUND
  By reading the source as source, not as text, with the tokeniser that
  `check-process-exits.py` already owns: comments and string contents are
  blanked, offsets are preserved, and the innermost function whose body holds
  the cost is the one that owns it.

Exit status is 0 when every cost of the path is declared and no declaration
is of a forbidden purpose, and 1 with the offenders when one is not.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

_TOKENISER_PATH = Path(__file__).resolve().parent / "check-process-exits.py"
_spec = importlib.util.spec_from_file_location("check_process_exits", _TOKENISER_PATH)
if _spec is None or _spec.loader is None:
    print(f"NO SE ENCONTRO el tokenizador en {_TOKENISER_PATH}", file=sys.stderr)
    raise SystemExit(2)
_tokeniser = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_tokeniser)
mask_kotlin = _tokeniser.mask_kotlin
brace_pairs = _tokeniser.brace_pairs
match_paren = _tokeniser.match_paren
find_functions = _tokeniser.find_functions
owning_function = _tokeniser.owning_function

# The durable shell path, in the two layers it lives in.
SDK = "pipelinek/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable"
APP = "pipelinek/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable"
SCOPES = (SDK, APP)

# The two costs, and the two kinds of occurrence.
CREATE = re.compile(r"\bProcessBuilder\s*\(")
WAIT = re.compile(r"\bThread\s*\.\s*sleep\s*\(")

# The purposes a process may be created for.
PURPOSE_CHILD = "child"
PURPOSE_PROTOCOL = "protocol"
PURPOSE_SIGNAL = "signal"
PURPOSE_QUESTION = "question"
PURPOSE_PROBE = "probe"

# The purposes a wait may be made for.
PURPOSE_CADENCE = "cadence"
PURPOSE_GRACE = "grace"
PURPOSE_DEADLINE = "deadline"
PURPOSE_FIXED = "fixed"

FORBIDDEN = (PURPOSE_PROBE, PURPOSE_FIXED)

# The census. Every cost of the path, with the purpose it serves and why.
#
# The key is (file relative to the repository, function, occurrence) and the
# value lists one entry per occurrence, in the order the occurrences appear
# in the file. A function that creates two processes declares two entries.
CENSUS: dict[tuple[str, str, str], list[tuple[str, str]]] = {
    (f"{SDK}/ShellProcessLauncher.kt", "launch", "create"): [
        (PURPOSE_CHILD, "the execution's own child: the wrapper, and the handle the launch keeps IS the wrapper"),
    ],
    (f"{SDK}/ProcessRendezvousHost.kt", "materialiseFifos", "create"): [
        (PURPOSE_PROTOCOL, "the protocol's own FIFOs: named files of the durable format"),
    ],
    (f"{SDK}/ProcessRendezvousHost.kt", "launchAgent", "create"): [
        (PURPOSE_CHILD, "the ingest agent: the worker that drains the protocol's FIFOs"),
    ],
    (f"{SDK}/ProcessRendezvousHost.kt", "awaitReady", "wait"): [
        (PURPOSE_CADENCE, "the agent's readiness is read from its own file, re-read until it appears"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "arm", "wait"): [
        (PURPOSE_DEADLINE, "the watch waits for the operator's own deadline"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "settle", "wait"): [
        (PURPOSE_CADENCE, "the latch is re-read until the watch publishes it"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "killWithCookieScan", "wait"): [
        (PURPOSE_GRACE, "the window the TERM is given before the escalation replaces it"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "terminateSession", "create"): [
        (PURPOSE_SIGNAL, "the TERM to the session"),
        (PURPOSE_SIGNAL, "the KILL that replaces the TERM when the signal cannot be delivered"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "escalateToKill", "create"): [
        (PURPOSE_SIGNAL, "the KILL to the session, when the grace did not suffice"),
        (PURPOSE_QUESTION, "whether the session is still there, asked on the expired path to decide whether a sweep is needed"),
    ],
    (f"{SDK}/ShellWatchdog.kt", "escalateToKill", "wait"): [
        (PURPOSE_CADENCE, "the tree is re-read until it is gone or the escalation's own deadline passes"),
    ],
    (f"{SDK}/ShellResultPoll.kt", "await", "wait"): [
        (PURPOSE_CADENCE, "the wrapper's state is re-read until it ends the wait"),
    ],
    (f"{SDK}/DurableShellExecutor.kt", "pollResult", "wait"): [
        (PURPOSE_CADENCE, "the result is re-read until the publication arrives or the window closes"),
    ],
    (f"{SDK}/task/ProcessDurableTaskRuntime.kt", "execute", "create"): [
        (PURPOSE_CHILD, "the task's own child: the PROCESS TASK protocol, another protocol with another control directory"),
    ],
}

# A payload that does nothing. A process whose whole payload is one of these
# is a process that asks the platform something and then exits: the probe this
# guard exists to refuse.
DO_NOTHING_PAYLOAD = ("true", "false", ":", "--")

# A duration written as a number. A wait whose length is a number in the
# middle of a function is a wait nobody reviewed.
NUMERIC_DURATION = re.compile(r"^\s*[0-9][0-9_]*L?\s*$")

# A string literal, to read the payloads a creation carries.
STRING_LITERAL = re.compile(r'"([^"\\]*)"')


class Cost:
    """One creation or one wait, with the function that owns it."""

    def __init__(self, path: str, function: str, kind: str, arguments: str, line: int) -> None:
        self.path = path
        self.function = function
        self.kind = kind
        self.arguments = arguments
        self.line = line

    def label(self) -> str:
        return f"{self.path}:{self.line}  en {self.function}()"


def costs_in(repo: Path, scope: str) -> list[Cost]:
    """Every creation and every wait under one scope, in source order."""
    found: list[Cost] = []
    root = repo / scope
    if not root.is_dir():
        return found
    for path in sorted(root.rglob("*.kt")):
        try:
            source = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        masked = mask_kotlin(source)
        pairs = brace_pairs(masked)
        functions = find_functions(masked, pairs)
        for pattern, kind in ((CREATE, "create"), (WAIT, "wait")):
            for match in pattern.finditer(masked):
                position = match.start()
                line = source.count("\n", 0, position) + 1
                owner = owning_function(functions, position)
                if owner is None:
                    found.append(
                        Cost(str(path.relative_to(repo)), "<sin dueno>", kind, "", line)
                    )
                    continue
                # The arguments are read from the ORIGINAL source: the mask
                # blanks the string contents, and the payload of a creation is
                # made of string contents.
                open_paren = masked.index("(", position)
                close_paren = match_paren(masked, open_paren)
                arguments = source[open_paren + 1:close_paren]
                found.append(
                    Cost(str(path.relative_to(repo)), owner[0], kind, arguments, line)
                )
    found.sort(key=lambda cost: (cost.path, cost.line, cost.kind))
    return found


def main() -> int:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    costs: list[Cost] = []
    for scope in SCOPES:
        costs.extend(costs_in(repo, scope))

    # The facts, grouped the way the census is keyed.
    facts: dict[tuple[str, str, str], list[Cost]] = {}
    for cost in costs:
        key = (cost.path, cost.function, cost.kind)
        facts.setdefault(key, []).append(cost)

    offenders: list[str] = []

    for key, occurrences in sorted(facts.items()):
        declared = CENSUS.get(key)
        if declared is None:
            offenders.append(
                f"  {occurrences[0].label()} -- un coste del camino durable que no esta declarado"
            )
        elif len(declared) != len(occurrences):
            offenders.append(
                f"  {occurrences[0].label()} -- el camino tiene {len(occurrences)} costes de este tipo "
                f"y el censo declara {len(declared)}"
            )
        else:
            for (purpose, why), occurrence in zip(declared, occurrences):
                if purpose in FORBIDDEN:
                    offenders.append(
                        f"  {occurrence.label()} -- un coste de tipo '{purpose}', que esta prohibido"
                    )
                if not why.strip():
                    offenders.append(
                        f"  {occurrence.label()} -- un coste declarado sin el motivo por el que existe"
                    )
        # The shape of a cost is judged on its own, so a cost that is not in
        # the table is still named for what it IS: the table says which costs
        # are allowed, and the shape says what an undeclared one looks like.
        for occurrence in occurrences:
            if occurrence.kind == "wait" and NUMERIC_DURATION.match(occurrence.arguments):
                offenders.append(
                    f"  {occurrence.label()} -- una espera cuya duracion esta escrita a mano"
                )
            if occurrence.kind == "create":
                for payload in STRING_LITERAL.findall(occurrence.arguments):
                    if payload in DO_NOTHING_PAYLOAD:
                        offenders.append(
                            f"  {occurrence.label()} -- un proceso cuyo payload no hace nada: '{payload}'"
                        )

    for key in sorted(CENSUS):
        if key not in facts:
            offenders.append(
                f"  {key[0]}  en {key[1]}() -- un coste declarado que el codigo ya no tiene"
            )

    probes = sum(
        1
        for entries in CENSUS.values()
        for purpose, _ in entries
        if purpose == PURPOSE_PROBE
    )
    fixed = sum(
        1
        for entries in CENSUS.values()
        for purpose, _ in entries
        if purpose == PURPOSE_FIXED
    )
    creations = sum(1 for cost in costs if cost.kind == "create")
    waits = sum(1 for cost in costs if cost.kind == "wait")

    if offenders:
        print("\n".join(offenders))
        print(f"\nCOSTES DEL CAMINO DURABLE QUE NO CUMPLEN EL CENSO: {len(offenders)}")
        print(
            f"  {creations} procesos creados y {waits} esperas en el camino durable; "
            f"{probes} sondas y {fixed} esperas fijas declaradas"
        )
        print("El camino durable no crea procesos de sondeo y no hace esperas fijas.")
        return 1

    print(
        f"OK: {creations} procesos y {waits} esperas declarados en el camino durable; "
        f"{probes} sondas y {fixed} esperas fijas"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
