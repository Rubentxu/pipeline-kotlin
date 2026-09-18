#!/usr/bin/env python3
"""WU-LPR-061 deterministic @Disabled inventory generator.

Regenerates docs/v2/07-uat/WU_LPR_061_DISABLED_INVENTORY.md from the source
tree. The generated table is the ONLY authority for @Disabled counts; never
hand-edit the numbers.

Categories (classification law, WU-LPR-061):
  MANDATORY_SUPPORTED  may block Gate-1; must converge to zero disabled
  COMPATIBILITY        deferred to WU-LPR-103 contract decision
  HISTORICAL           archived closed-cycle evidence; never gates
  EXPERIMENTAL         non-gating research surface
  OBSOLETE             to be deleted (must name its live replacement)

Usage: python3 scripts/gen-disabled-inventory.py
"""
import os
import re
import subprocess
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_ROOT = os.path.join(ROOT, "v2")
OUT = os.path.join(ROOT, "docs", "v2", "07-uat", "WU_LPR_061_DISABLED_INVENTORY.md")

# file -> (category, reason-summary, decision) classification table.
# Everything NOT listed here defaults to HISTORICAL (the archived migration
# snapshot pattern dominates: "@Disabled(\"Historical ...\" / \"Archived ...").
# A default would silently mask new misclassified entries, so new non-historical
# @Disabled entries MUST be added to CLASSIFICATION below or the generator
# fails the run.
CLASSIFICATION = {
    # COMPATIBILITY: DSL classpath surface for CredentialsId inside .pipeline.kts.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal008CredentialsTest.kt": [
        ("CR-BD-034 mismatched credential kind throws", "COMPATIBILITY",
         "CredentialsId not on the .pipeline.kts script classpath; deferred to WU-LPR-103 contract decision", "no"),
    ],
    # COMPATIBILITY: load is explicitly non-blocking for local-core-v1 (product doc);
    # coordinator step-yielding is a real capability gap tracked as INC-024.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal011WorkflowControlTest.kt": [
        ("SC-011-11 load executes script content", "COMPATIBILITY",
         "INC-024: coordinator does not yet inject loaded pipeline; load is explicitly non-blocking for local-core-v1", "no"),
    ],
}


def module_of(rel: str) -> str:
    m = re.match(r"v2/([^/]+(?:/[^/]+)?)/src/test", rel)
    return m.group(1) if m else rel


def classify(rel: str, method: str) -> tuple:
    for f, entries in CLASSIFICATION.items():
        if rel == f:
            for meth, cat, reason, blocking in entries:
                if meth in method:
                    return cat, reason, blocking
    # default: archived snapshot evidence
    return "HISTORICAL", "Archived migration/cycle snapshot evidence (verbatim traceability)", "no"


def extract_entries(rel_path: str, abs_path: str):
    src = open(abs_path, encoding="utf-8").read()
    entries = []
    # match @Disabled("...") possibly multiline, followed by optional annotations
    # then `fun \`name\`` or `class Name`
    for m in re.finditer(r'(?:@|^|\s)@(?:org\.junit\.jupiter\.api\.)?Disabled\(\s*"((?:[^"\\]|\\.)*)"', src, re.S):
        tail = src[m.end():m.end() + 600]
        fm = re.search(r'(?:@[^\n@]*\n\s*)*fun `([^`]+)`', tail)
        cm = re.search(r'class\s+(\w+)', tail)
        if fm:
            target = f"`{fm.group(1)}`"
        elif cm:
            target = f"class {cm.group(1)} (whole class)"
        else:
            target = "<unresolved>"
        reason = m.group(1).replace("\\\"", "\"")[:160]
        entries.append((target, reason))
    return entries


def main():
    rows = []
    counts = {"MANDATORY_SUPPORTED": 0, "COMPATIBILITY": 0, "HISTORICAL": 0,
              "EXPERIMENTAL": 0, "OBSOLETE": 0}
    for dirpath, _dirs, files in os.walk(TEST_ROOT):
        for f in files:
            if not f.endswith(".kt"):
                continue
            abs_path = os.path.join(dirpath, f)
            rel = os.path.relpath(abs_path, ROOT)
            if "/src/test/" not in rel:
                continue
            if "@Disabled" not in open(abs_path, encoding="utf-8", errors="ignore").read():
                continue
            cls = f
            for target, reason in extract_entries(rel, abs_path):
                cat, cat_reason, blocking = classify(rel, target)
                counts[cat] += 1
                rows.append((module_of(rel), cls, target, cat, cat_reason or reason, "pipeline-kotlin maintainers", blocking))

    sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    mandatory = counts["MANDATORY_SUPPORTED"]
    gate = "OPEN (mandatory disabled > 0)" if mandatory > 0 else "not blocked by @Disabled inventory"
    lines = [
        "# WU-LPR-061 — @Disabled Inventory (GENERATED, do not hand-edit counts)",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')} · HEAD `{sha}`",
        "",
        "| module | class | method/target | category | reason | owner | blocks Gate-1 |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in sorted(rows):
        lines.append("| " + " | ".join(str(x).replace("|", "\\|") for x in r) + " |")
    lines += [
        "",
        "## Counts",
        "",
        "```text",
        f"@Disabled total                 = {len(rows)}",
        *[f"{k:<24} = {v}" for k, v in counts.items()],
        f"mandatory-disabled (Gate-1)    = {mandatory}",
        f"Gate-1 @Disabled state         = {gate}",
        "```",
        "",
        "Regenerate: `python3 scripts/gen-disabled-inventory.py`",
        "",
        "Law: Gate-1 cannot be GREEN while mandatory-disabled > 0. A new",
        "non-HISTORICAL @Disabled entry MUST be added to CLASSIFICATION in the",
        "generator or the inventory silently miscounts it as HISTORICAL.",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[-14:]))


if __name__ == "__main__":
    main()
