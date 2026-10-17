#!/usr/bin/env bash
#
# assert-test-evidence.sh — a focused Gradle run that SELECTED NOTHING is not a pass.
#
# ## Why this exists
#
# On 2026-10-17 a targeted run of `UatDsl006BodyExecutionTest` reported
# `BUILD SUCCESSFUL in 9s` and was read as "the hang is fixed". The XML said
# `tests="1" skipped="1" time="0.001"`: the class carries `@Disabled("migrated-pending")`,
# so no line of it executed. The number was true and the conclusion was invented.
#
# Gradle exits 0 when every selected test is skipped, and the console prints nothing that
# distinguishes that from a real pass. The skill law is explicit — "no llamar verde a SKIP" —
# and nothing enforced it at the point where every focused run passes.
#
# ## What it checks
#
# Reads the JUnit XML the run just wrote and requires that at least one selected test actually
# RAN:
#
#   tests == 0        -> nothing matched the filter (or the task never ran)
#   skipped == tests  -> everything matched was disabled/assumed away
#
# A partial skip is allowed and reported: whole-suite gates legitimately contain disabled tests,
# and failing those would make the guard noise. Only "no evidence at all" fails.
#
# ## Usage
#
#   assert-test-evidence.sh <results-dir>
#
# Exit 0 when evidence exists, 1 when it does not. Always prints the counts, because the count
# is the thing a reader otherwise has to go and dig out of an XML file.

set -Eeuo pipefail

RESULTS_DIR="${1:-}"


die() {
    echo "ASSERT-TEST-EVIDENCE: $*" >&2
    exit 1
}


[[ -n "$RESULTS_DIR" ]] || die "usage: assert-test-evidence.sh <results-dir>"
[[ -d "$RESULTS_DIR" ]] || die "no such results dir: $RESULTS_DIR (did the task run? is the path right?)"

shopt -s nullglob
xmls=("$RESULTS_DIR"/TEST-*.xml)
shopt -u nullglob

[[ ${#xmls[@]} -gt 0 ]] || die "no TEST-*.xml in $RESULTS_DIR — the task produced no results."

python3 - "$RESULTS_DIR" "${xmls[@]}" <<'PYTHON'
import sys, xml.etree.ElementTree as ET

results_dir, paths = sys.argv[1], sys.argv[2:]

tests = skipped = failures = errors = 0
skipped_names = []

for path in paths:
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        tests += 1
        if case.find("skipped") is not None:
            skipped += 1
            skipped_names.append(f'{case.get("classname")}.{case.get("name")}')
        if case.find("failure") is not None:
            failures += 1
        if case.find("error") is not None:
            errors += 1

print(f"ASSERT-TEST-EVIDENCE: {tests} selected, {tests - skipped} ran, "
      f"{skipped} skipped, {failures} failed, {errors} errored  ({results_dir})")

if tests == 0:
    print("ASSERT-TEST-EVIDENCE: the filter matched no test. A green here is the ABSENCE of a "
          "test, not a passing one.", file=sys.stderr)
    sys.exit(1)

if skipped == tests:
    print("ASSERT-TEST-EVIDENCE: every selected test was SKIPPED, so nothing ran:", file=sys.stderr)
    for name in skipped_names[:10]:
        print(f"  skipped: {name}", file=sys.stderr)
    print("  A @Disabled class still matches its own name in --tests, which is how a filter "
          "reports success without executing anything.", file=sys.stderr)
    sys.exit(1)

sys.exit(1 if (failures or errors) else 0)
PYTHON