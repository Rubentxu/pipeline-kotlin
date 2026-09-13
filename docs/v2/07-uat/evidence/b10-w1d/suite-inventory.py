#!/usr/bin/env python3
"""Per-module suite inventory from JUnit XML, and a base-vs-head regression diff.

Result truth is the XML under `<repo>/v2/<module>/build/test-results/test/`, never the
console or the Gradle exit code.

  python3 suite-inventory.py dump  <repo>            > head.json
  python3 suite-inventory.py dump  <base-repo>       > base.json
  python3 suite-inventory.py diff  <base.json> <head.json>

The diff reports, per module: totals, classes red only at head (regressions), classes red
only at base (repairs), and classes red in both with a changed failing-name set (a
re-baseline, which is not a repair).
"""

from __future__ import annotations

import glob
import re
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def dump(repo: str) -> dict:
    declared = declared_modules(repo)
    inventory: dict[str, dict] = {}
    pattern = f"{repo}/v2/**/build/test-results/test/TEST-*.xml"
    for xml in sorted(glob.glob(pattern, recursive=True)):
        # Nested modules (e.g. pipeline-step-sdk/runtime) count as their own module.
        module = xml.split("/v2/", 1)[1].split("/build/", 1)[0]
        if declared and module not in declared:
            # A build directory that survives from an older tree state (a module removed
            # from settings.gradle.kts) is not evidence for the current build. Its XMLs
            # can be months old while looking like a green suite.
            print(f"# skipped (not declared in settings.gradle.kts): {module}", file=sys.stderr)
            continue
        root = ET.parse(xml).getroot()
        row = inventory.setdefault(module, {"tests": 0, "failures": 0, "errors": 0, "red": {}})
        failing = sorted(
            tc.get("name") for tc in root.iter("testcase")
            if tc.find("failure") is not None or tc.find("error") is not None
        )
        row["tests"] += int(root.get("tests"))
        row["failures"] += int(root.get("failures"))
        row["errors"] += int(root.get("errors"))
        if failing:
            row["red"][root.get("name")] = failing
    return inventory


def declared_modules(repo: str) -> set[str]:
    """The project paths declared in `v2/settings.gradle.kts`."""
    settings = Path(repo) / "v2/settings.gradle.kts"
    if not settings.is_file():
        return set()
    block = re.search(r"include\((.*?)\)", settings.read_text(), re.S)
    if not block:
        return set()
    return {m.group(1).lstrip(":").replace(":", "/") for m in re.finditer(r'"(:[^"]+)"', block.group(1))}


def diff(base: dict, head: dict) -> int:
    modules = sorted(set(base) | set(head))
    regressions = 0
    for module in modules:
        b = base.get(module, {"tests": 0, "failures": 0, "errors": 0, "red": {}})
        h = head.get(module, {"tests": 0, "failures": 0, "errors": 0, "red": {}})
        red_only_head = sorted(set(h["red"]) - set(b["red"]))
        red_only_base = sorted(set(b["red"]) - set(h["red"]))
        changed = {
            cls: {"base": b["red"][cls], "head": h["red"][cls]}
            for cls in sorted(set(b["red"]) & set(h["red"]))
            if b["red"][cls] != h["red"][cls]
        }
        new_names = {
            cls: sorted(set(h["red"][cls]) - set(b["red"].get(cls, [])))
            for cls in sorted(set(h["red"]) & set(b["red"]))
            if set(h["red"][cls]) - set(b["red"][cls])
        }
        print(f"{module}:")
        print(f"  totals base={b['tests']}/{b['failures']}/{b['errors']} "
              f"head={h['tests']}/{h['failures']}/{h['errors']}")
        print(f"  red classes base={len(b['red'])} head={len(h['red'])}")
        for label, items in (("REGRESSED CLASS AT HEAD", red_only_head),
                             ("REPAIRED AT HEAD", red_only_base)):
            for cls in items:
                print(f"  {label}: {cls}")
                regressions += 1 if label.startswith("REGRESSED") else 0
        for cls, names in new_names.items():
            print(f"  NEW FAILING TEST AT HEAD: {cls} -> {names}")
            regressions += 1
        for cls, rows in changed.items():
            if cls not in new_names:
                print(f"  CHANGED FAILING SET (re-baseline): {cls} base={rows['base']} head={rows['head']}")
                regressions += 1
    print(f"\nregression signals: {regressions}")
    return 1 if regressions else 0


def main() -> None:
    if len(sys.argv) >= 3 and sys.argv[1] == "dump":
        print(json.dumps(dump(sys.argv[2]), indent=1, sort_keys=True))
        return
    if len(sys.argv) >= 4 and sys.argv[1] == "diff":
        base = json.loads(Path(sys.argv[2]).read_text())
        head = json.loads(Path(sys.argv[3]).read_text())
        sys.exit(diff(base, head))
    print(__doc__)
    sys.exit(2)


if __name__ == "__main__":
    main()
