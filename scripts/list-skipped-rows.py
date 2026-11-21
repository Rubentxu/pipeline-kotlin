#!/usr/bin/env python3
"""Name the test rows a JUnit report declares as SKIPPED.

A report declares a row as skipped when the platform gave it no answer: the
row was aborted, so it never measured, or it was never asked to run. The gate
counts those rows in the receipt of the lane, and this reader names them,
because a reader who is told that a row did not measure has to know WHICH one
to pay again.

The reader parses the report by GRAMMAR: it walks the test cases the report
declares and looks for the `skipped` child that declares one of them skipped.
It reads no message and no prose of the row. A message is the row's own text
and it changes with the platform, while the declaration is what the report
says about the row, and a reader of a declaration can be trusted whatever the
platform writes.

What it prints, one line per row that did not measure:

    <path> <classname>#<name>

What it refuses, with the reason on stderr and a nonzero exit status:

  - a report that is not well-formed XML, or that cannot be read;
  - a report whose root is neither <testsuite> nor <testsuites>.

A report that cannot be read is a report whose rows cannot be named, and the
reason says which file and why. The rows it declares are not counted as
named either: a row the reader cannot name is a row the reader cannot report.

Usage: list-skipped-rows.py REPORT [REPORT ...]
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

# The elements that can be the root of a JUnit report.
ROOTS = ("testsuite", "testsuites")

# The element that declares one test case, and the child that declares that
# case as skipped.
TEST_CASE = "testcase"
SKIPPED = "skipped"

# What a report that declares no name says about the row: the row exists and
# the report does not say which one it is.
UNNAMED = "?"


def read_report(path: str) -> tuple[list[tuple[str, str]], str | None]:
    """The rows the report declares as skipped, or None and the reason."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        return [], f"the report is not well-formed XML: {error}"
    except OSError as error:
        return [], f"the report cannot be read: {error}"

    if root.tag not in ROOTS:
        return [], (
            "the root of a JUnit report must be <testsuite> or <testsuites>, "
            f"and this one is <{root.tag}>"
        )

    rows: list[tuple[str, str]] = []
    for case in root.iter(TEST_CASE):
        if any(child.tag == SKIPPED for child in case):
            rows.append((case.get("classname") or UNNAMED, case.get("name") or UNNAMED))
    return rows, None


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(
            "list-skipped-rows: usage: list-skipped-rows.py REPORT [REPORT ...]",
            file=sys.stderr,
        )
        return 2

    refused = 0
    for path in argv[1:]:
        rows, reason = read_report(path)
        if reason is not None:
            refused += 1
            print(f"list-skipped-rows: {path}: {reason}", file=sys.stderr)
            continue
        for classname, name in rows:
            print(f"{path} {classname}#{name}")

    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
