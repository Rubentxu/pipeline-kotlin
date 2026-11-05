#!/usr/bin/env python3
"""The published reference, reviewed for the KDoc links that do not resolve.

A published contract is read through its reference, and a link in that
reference which names a symbol the reader cannot open is a promise the
artifact does not keep. The generator emits a warning for each of those links;
this script counts them and fails when there is one.

The reference of every published contract is generated again for this review,
and the generation is forced: a generation the build finds up to date emits no
warning at all, so counting the warnings of a held generation would report
zero unresolved links whatever the tree says.

The script writes nothing into the tree. The reference lands under `build/`,
and the answer is a line on the standard output.

The script is an instrument of the gate and it stands on its own: run it over
the tree you are working on and it answers the same question the gate
answers.

Exit 0 when every link of every published contract resolves, 1 otherwise.

Usage: check-published-reference.py [--repo ROOT]
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

# The warning the generator emits for a link that names a symbol it cannot
# find. The target is in brackets: "Couldn't resolve link: [Target]".
UNRESOLVED = re.compile(r"Couldn't resolve link: \[(?P<target>[^\]]*)\]")

# The line the build prints for a reference it generated.
GENERATED = re.compile(r"^> Task (?P<task>:\S*dokkaGeneratePublicationHtml)(?P<state>.*)$")

# The states that mean the reference was not analysed on this tree. A contract
# whose reference comes from the cache was not looked at, and a review that
# did not look at it says nothing about it.
NOT_ANALYSED = ("SKIPPED", "FROM-CACHE")

# How many offending lines the report prints. The count is the evidence; the
# lines are the way to find them, and a wall of them is not.
REPORTED = 20

# How many lines of the build's output the report keeps when the generation
# itself fails. The failure is in the build, and the build's own words are
# what explain it.
TAIL = 20


def repository_root() -> str:
    """The root of the repository this script lives in."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def generate(root: str) -> tuple[int, str]:
    """Generates the reference of every published contract.

    Returns the exit status of the build and everything it printed.
    """
    completed = subprocess.run(
        [
            os.path.join(root, "pipelinek", "gradlew"),
            "-p",
            os.path.join(root, "pipelinek"),
            "--console=plain",
            "publishedReferenceCheck",
        ],
        cwd=root,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return completed.returncode, completed.stdout


def review(output: str) -> tuple[list[str], list[str], list[str]]:
    """Reads what the generation said.

    Returns the contracts that were analysed, the contracts whose reference
    was not analysed on this tree, and the links that did not resolve.
    """
    analysed: list[str] = []
    skipped: list[str] = []
    unresolved: list[str] = []

    for line in output.splitlines():
        generated = GENERATED.match(line)
        if generated:
            state = generated.group("state")
            if any(marker in state for marker in NOT_ANALYSED):
                skipped.append(generated.group("task"))
            else:
                analysed.append(generated.group("task"))
            continue
        if UNRESOLVED.search(line):
            unresolved.append(line.strip())

    return analysed, skipped, unresolved


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo",
        default=repository_root(),
        help="the root of the repository to review (default: the one this script lives in)",
    )
    args = parser.parse_args()
    root = os.path.abspath(args.repo)

    status, output = generate(root)
    analysed, skipped, unresolved = review(output)

    if status != 0:
        print("published reference: the generation did not finish")
        for line in output.splitlines()[-TAIL:]:
            print(f"  {line}")
        print("reference=contracts:0 unresolved:NOT_RUN")
        print("RESULT: FAIL")
        return 1

    print(
        f"published reference: {len(analysed)} contract(s) analysed, "
        f"{len(unresolved)} unresolved link(s)"
    )

    failures = 0

    for task in skipped:
        print(f"  the reference of {task} was not analysed on this tree")
        failures += 1

    for line in unresolved[:REPORTED]:
        print(f"  {line}")
    if len(unresolved) > REPORTED:
        print(f"  ... and {len(unresolved) - REPORTED} more")
    failures += len(unresolved)

    print(f"reference=contracts:{len(analysed)} unresolved:{len(unresolved)}")
    print("RESULT: FAIL" if failures else "RESULT: PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
