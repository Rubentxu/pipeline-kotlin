#!/usr/bin/env python3
"""XCA-1A — remove false coverage claims from step-certification.yaml.

Principle: correct the ledger BEFORE using the ledger to classify work.

Removes, for every CERTIFIED surface, a real_fixtures entry that XCA-0 proved the
file does not actually invoke. Replaces the lie with [] — never with a provisional
path and never with a "coverage pending" comment that keeps CERTIFIED falsely green.
"""
import re, sys, pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
LEDGER = REPO / "docs/v2/status/step-certification.yaml"

UTIL_FIXTURE = "examples/utilities/01-json-roundtrip.pipeline.kts"
# genuinely invoked by that fixture (hand-verified AND source-verified)
UTIL_LEGIT = {"utilities.readJSON", "utilities.writeJSON", "utilities.sha256"}
UPPER_FIXTURE = "examples/example-uppercase-plugin/scripts/uppercase-demo.pipeline.kts"

TOP = re.compile(r'^  - step_key:\s*"?([\w.\-]+)"?\s*$')
ITEM = re.compile(r'^      - (.+?)\s*$')


def main():
    lines = LEDGER.read_text().split("\n")
    out, i = [], 0
    removed, added = [], []

    while i < len(lines):
        ln = lines[i]
        m = TOP.match(ln)
        if not m:
            out.append(ln)
            i += 1
            continue

        key = m.group(1)
        out.append(ln)
        i += 1

        # copy record body until the next top-level record or a fixtures block
        while i < len(lines) and not TOP.match(lines[i]):
            f = re.match(r'^    real_fixtures:\s*(.*?)\s*$', lines[i])
            if not f:
                out.append(lines[i])
                i += 1
                continue

            # collect the fixture items belonging to this block
            header = lines[i]
            i += 1
            items = []
            while i < len(lines) and ITEM.match(lines[i]):
                items.append(ITEM.match(lines[i]).group(1).strip().strip('"'))
                i += 1

            new = list(items)
            # XCA-1A rule: drop the utilities fixture for surfaces that do not invoke it
            if UTIL_FIXTURE in new and key not in UTIL_LEGIT:
                new.remove(UTIL_FIXTURE)
                removed.append((key, UTIL_FIXTURE))
            # A_REMAP_EXISTING: example.uppercase already has a real product script
            if key == "example.uppercase" and UPPER_FIXTURE not in new:
                new.append(UPPER_FIXTURE)
                added.append((key, UPPER_FIXTURE))

            if new == items:
                out.append(header)
                out.extend(f"      - {x}" for x in items)
            elif not new:
                out.append("    real_fixtures: []")
            else:
                out.append("    real_fixtures:")
                out.extend(f"      - {x}" for x in new)
            continue
        continue

    text = "\n".join(out)
    LEDGER.write_text(text)

    print(f"removed false claims : {len(removed)}")
    for k, f in removed:
        print(f"   - {k:26} <- {f.split('/')[-1]}")
    print(f"added real claims    : {len(added)}")
    for k, f in added:
        print(f"   + {k:26} -> {f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
