#!/usr/bin/env python3
"""LOC budget gate: VERIFY_LOC <= 2 * PRODUCT_LOC, globally and per module.

AGENTS.md section 4 states the gate. This script is the single implementation
of it, stdlib only, and it prints every classification rule it applied so a
reader can audit the accounting instead of trusting it.

What is counted, per AGENTS.md section 4:

  VERIFY_LOC   permanent unit tests under src/test, plus the persistent E2E
               .pipeline.kts programs, their thin launcher, and any other
               repository verification script.
  PRODUCT_LOC  active, non-generated production source: Kotlin/Java under
               src/main, the build scripts that produce the product, and the
               scripts that ship it.

Not counted anywhere: comments, blank lines, generated output, and anything
matched by neither rule. Excluded paths are printed, because an exclusion that
is not printed is indistinguishable from a file that was never looked at.

Exit 0 when every measured ratio is within budget, 1 otherwise.

Usage: check-budgets.py [--repo ROOT] [--json]
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import subprocess
import sys
from collections import defaultdict

RATIO_LIMIT = 2.0

# --- Classification rules. Ordered; first match wins. -----------------------
# Each entry is (side, label, glob). "side" is "verify", "product" or "skip".
# A file matching none of them is counted nowhere and reported as uncategorised,
# so the blast radius of a wrong rule is visible instead of silent.

VERIFY_SOURCE = "src/test"
PRODUCT_SOURCE = "src/main"
BUILD_SCRIPT = "build.gradle.kts"

RULES: list[tuple[str, str, str]] = [
    # Verification, most specific first.
    ("verify", "budget script", "scripts/check-budgets.py"),
    ("verify", "verification entrypoint", "scripts/verify-all.sh"),
    ("verify", "E2E launcher", "examples/run.sh"),
    # Permanent unit tests. Two module depths: pipelinek/<m> and pipelinek/<g>/<m>.
    ("verify", "unit tests", "pipelinek/*/src/test/*"),
    ("verify", "unit tests", "pipelinek/*/*/src/test/*"),
    # Persistent E2E pipelines, wherever they currently live.
    ("verify", "E2E pipeline", "examples/*.pipeline.kts"),
    ("verify", "E2E pipeline", "examples/*/*.pipeline.kts"),
    ("verify", "E2E pipeline", "examples/*/*/*.pipeline.kts"),
    ("verify", "E2E pipeline", "ci/*.pipeline.kts"),
    # Production. Also two module depths.
    ("product", "production source", "pipelinek/*/src/main/*"),
    ("product", "production source", "pipelinek/*/*/src/main/*"),
    ("product", "build script", "pipelinek/settings.gradle.kts"),
    ("product", "build script", "pipelinek/build.gradle.kts"),
    ("product", "build script", "pipelinek/*/build.gradle.kts"),
    ("product", "build script", "pipelinek/*/*/build.gradle.kts"),
    # The three example plugins are independent Gradle builds whose JARs the
    # distribution ships via runtimeOnly, so their build scripts are the
    # denominator even though they live under examples/.
    ("product", "example plugin build", "examples/*/settings.gradle.kts"),
    ("product", "example plugin build", "examples/*/build.gradle.kts"),
    ("product", "example plugin source", "examples/*/src/main/*"),
    # Remaining scripts/ are release and install tooling: they ship the
    # product, so they are the denominator, not the numerator.
    ("product", "release script", "scripts/*"),
]

# Never counted: build output and anything the toolchain generates.
NEVER = ("*/build/*", "*/.gradle/*", "*/generated/*", "*/.kotlin/*")


def git_tracked(root: str) -> list[str]:
    out = subprocess.run(
        ["git", "-C", root, "ls-files", "-z"],
        capture_output=True,
        check=True,
        text=True,
    )
    return [p for p in out.stdout.split("\0") if p]


def classify(path: str) -> tuple[str, str] | None:
    for side, label, pattern in RULES:
        if fnmatch.fnmatch(path, pattern):
            return side, label
    return None


def is_never(path: str) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in NEVER)


def module_of(path: str) -> str | None:
    """Module path for a src/main or src/test file, else None.

    pipelinek/<m>/src/main/...            -> <m>
    pipelinek/<g>/<m>/src/main/...        -> <g>:<m>
    """
    parts = path.split("/")
    try:
        idx = parts.index("src")
    except ValueError:
        return None
    if idx == 0 or parts[0] != "pipelinek":
        return None
    if idx == 2:
        return parts[1]
    if idx == 3:
        return f"{parts[1]}:{parts[2]}"
    return None


def effective_loc(path: str, root: str) -> int:
    """Non-blank, non-comment lines.

    Line comments are // and /* */ in both Kotlin and Python. # is a comment in
    Python and a shebang, handled separately. Strings and regexes are not
    parsed; a '#' inside a shell or Python string is counted as code. That
    over-counts by a line at a time, which is the safe direction for a budget.
    """
    full = os.path.join(root, path)
    try:
        with open(full, encoding="utf-8", errors="replace") as handle:
            lines = handle.readlines()
    except OSError:
        return 0

    count = 0
    in_block = False
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if in_block:
            if "*/" in line:
                in_block = False
            continue
        if line.startswith("#!"):
            continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block = True
            continue
        if line.startswith("//"):
            continue
        if path.endswith(".py") and line.startswith("#"):
            continue
        count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    root = args.repo
    verify_total = 0
    product_total = 0
    per_module: dict[str, dict[str, int]] = defaultdict(lambda: {"verify": 0, "product": 0})
    uncategorised: list[tuple[str, str]] = []
    excluded: list[str] = []
    rules_used: dict[str, int] = defaultdict(int)

    for path in git_tracked(root):
        if is_never(path):
            excluded.append(path)
            continue
        verdict = classify(path)
        if verdict is None:
            uncategorised.append((path, ""))
            continue
        side, label = verdict
        loc = effective_loc(path, root)
        if loc == 0:
            uncategorised.append((path, label))
            continue
        rules_used[f"{side}: {label}"] += 1

        if side == "verify":
            verify_total += loc
        else:
            product_total += loc

        module = module_of(path)
        if module is not None:
            per_module[module][side] += loc

    failures: list[str] = []

    if product_total == 0:
        if verify_total > 0:
            failures.append(
                f"PRODUCT_LOC is 0 while VERIFY_LOC is {verify_total}: the ratio fails by rule."
            )
        ratio = None
    else:
        ratio = verify_total / product_total
        if verify_total > RATIO_LIMIT * product_total:
            failures.append(
                f"global: VERIFY_LOC={verify_total} > {RATIO_LIMIT:g} * PRODUCT_LOC="
                f"{product_total} (ratio {ratio:.3f})"
            )

    module_rows = []
    for module in sorted(per_module):
        row = per_module[module]
        # A module only has a ratio when it has both sides. Product-only
        # modules cannot breach: they contribute no verification code.
        if row["verify"] == 0 or row["product"] == 0:
            continue
        module_ratio = row["verify"] / row["product"]
        breached = row["verify"] > RATIO_LIMIT * row["product"]
        module_rows.append(
            {
                "module": module,
                "verify_loc": row["verify"],
                "product_loc": row["product"],
                "ratio": round(module_ratio, 3),
                "breach": breached,
            }
        )
        if breached:
            failures.append(
                f"module {module}: VERIFY_LOC={row['verify']} > {RATIO_LIMIT:g} * "
                f"PRODUCT_LOC={row['product']} (ratio {module_ratio:.3f})"
            )

    report = {
        "verify_loc": verify_total,
        "product_loc": product_total,
        "ratio": None if ratio is None else round(ratio, 3),
        "limit": RATIO_LIMIT,
        "per_module": module_rows,
        "rules_applied": dict(sorted(rules_used.items())),
        "uncategorised": sorted(p for p, _ in uncategorised),
        "excluded_count": len(excluded),
        "failures": failures,
    }

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("LOC budget (AGENTS.md section 4)")
        print(f"  rules in force, first match wins:")
        for key in sorted(rules_used):
            print(f"    {key}: {rules_used[key]} files")
        print(f"  VERIFY_LOC   = {verify_total}")
        print(f"  PRODUCT_LOC  = {product_total}")
        if ratio is None:
            print("  ratio        = undefined (PRODUCT_LOC is 0)")
        else:
            print(f"  ratio        = {ratio:.3f} (limit {RATIO_LIMIT:g})")
        if module_rows:
            print("  per module (only modules with both sides):")
            for row in module_rows:
                flag = "  BREACH" if row["breach"] else ""
                print(
                    f"    {row['module']}: {row['verify_loc']} / {row['product_loc']}"
                    f" = {row['ratio']}{flag}"
                )
        if uncategorised:
            print(f"  counted nowhere ({len(uncategorised)}):")
            for path, label in sorted(uncategorised):
                print(f"    {path}" + (f"  [{label}]" if label else ""))
        print(f"  excluded as generated ({len(excluded)} files)")
        if failures:
            print("RESULT: BLOCKED_BUDGET")
            for failure in failures:
                print(f"  - {failure}")
        else:
            print("RESULT: PASS")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())