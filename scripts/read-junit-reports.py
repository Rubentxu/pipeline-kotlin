#!/usr/bin/env python3
"""Read the counters a JUnit report DECLARES, and refuse what it does not declare.

The gate publishes a number of tests that ran, and that number comes from the
JUnit XML the test lane wrote. This script is the reader that produces it, and
it reads the report by GRAMMAR: it parses the document and looks at the root
element and its attributes. A reader that looked for the attribute in the first
lines of the file would know only the shape the current producer writes, and
would report a run that nobody declared for every report whose root sits
elsewhere or declares nothing.

What it prints, one line per report:

    <path> tests=<n> failures=<n> errors=<n> skipped=<n>

What it refuses, with the reason on stderr and a nonzero exit status:

  - a report that is not well-formed XML, or that cannot be read;
  - a report whose root is not `<testsuite>` nor `<testsuites>`;
  - a report whose root does not declare one of the four counters;
  - a counter that is declared with a value that is not a whole number, or that
    is negative.

A counter that is ABSENT is an absence and not a zero: a zero is a claim about
the run, and an attribute that is not there makes no claim at all. So an
absent counter is refused, naming the file and the attribute, rather than read
as a zero.

The reader measures the reports of a build lane. The authority for a report a
user hands to the pipeline is the product's own parser, in the module that
publishes the JUnit capability; this one answers a narrower question, about
what the lane's own reports declare.

Usage: read-junit-reports.py REPORT [REPORT ...]
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET

# The counters a report's root declares. Every one of them is required: a
# report that leaves one out does not say how many of that kind it ran, and a
# missing number is not a number.
COUNTERS = ("tests", "failures", "errors", "skipped")

# The elements that can be the root of a JUnit report.
ROOTS = ("testsuite", "testsuites")

# A whole number, written as digits. The pattern is closed so that a value
# like "1_0" or "+3" is not accepted as a number by accident.
WHOLE = re.compile(r"[0-9]+")

# A count cannot be negative, and a negative one is told apart from a value
# that is not a number at all: the two say different things about the report.
NEGATIVE = re.compile(r"-[0-9]+")


def read_report(path: str) -> tuple[dict[str, int] | None, str | None]:
    """The counters the root of the report declares, or None and the reason."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        return None, f"the report is not well-formed XML: {error}"
    except OSError as error:
        return None, f"the report cannot be read: {error}"

    if root.tag not in ROOTS:
        return None, (
            "the root of a JUnit report must be <testsuite> or <testsuites>, "
            f"and this one is <{root.tag}>"
        )

    declared: dict[str, int] = {}
    for name in COUNTERS:
        raw = root.get(name)
        if raw is None:
            return None, f'the root declares no "{name}" counter'
        text = raw.strip()
        if NEGATIVE.fullmatch(text):
            return None, f'the root declares "{name}={raw}", which is negative'
        if not WHOLE.fullmatch(text):
            return None, f'the root declares "{name}={raw}", which is not a whole number'
        declared[name] = int(text)

    return declared, None


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(
            "read-junit-reports: usage: read-junit-reports.py REPORT [REPORT ...]",
            file=sys.stderr,
        )
        return 2

    refused = 0
    for path in argv[1:]:
        declared, reason = read_report(path)
        if declared is None:
            refused += 1
            print(f"read-junit-reports: {path}: {reason}", file=sys.stderr)
            continue
        counters = " ".join(f"{name}={declared[name]}" for name in COUNTERS)
        print(f"{path} {counters}")

    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
