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

The classification is fail-closed
----------------------------------
An @Disabled entry that CLASSIFICATION does not name is classified UNKNOWN,
and UNKNOWN **blocks Gate-1**. It is never silently HISTORICAL.

That distinction is the whole point of this generator, and it was not what the
code did. `classify()` ended in an unconditional

    return "HISTORICAL", "Archived migration/cycle snapshot evidence", "no"

so every new `@Disabled` a contributor wrote in passing was filed as archived
evidence of a closed cycle and stopped blocking the gate. Measured on a
brand-new class with no history whatsoever, the generator returned
category='HISTORICAL', blocks_gate_1='no', and exited 0. The comment above
CLASSIFICATION claimed "the generator fails the run"; it never did, because
there was no failure path to take.

HISTORICAL is still reachable, but only by being declared. An archived entry is
a fact about a closed cycle and belongs in the table with its cycle named, where
a reviewer can see it. Silence is not evidence of archiving.

Usage: python3 scripts/gen-disabled-inventory.py
"""
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_ROOT = os.path.join(ROOT, "v2")
OUT = os.path.join(ROOT, "docs", "v2", "07-uat", "WU_LPR_061_DISABLED_INVENTORY.md")

# Every category the generator can emit, plus the one that means "nobody
# triaged this". UNKNOWN is deliberately NOT in the same family as the rest: it
# is the fail-closed outcome, it blocks Gate-1, and it is the only category that
# makes the generator exit non-zero.
CATEGORIES = (
    "MANDATORY_SUPPORTED",
    "COMPATIBILITY",
    "HISTORICAL",
    "EXPERIMENTAL",
    "OBSOLETE",
)
UNTRIAGED = "UNKNOWN"

# file -> (category, reason-summary, decision) classification table.
# A @Disabled entry NOT listed here is UNKNOWN and blocks Gate-1. Declaring an
# entry HISTORICAL is a positive claim about a closed cycle and belongs here,
# where it is reviewable; the previous unconditional HISTORICAL default made
# every new disabled test non-gating without anyone deciding that.
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
    """Return (category, reason, blocks_gate_1) for one @Disabled entry.

    The default is UNKNOWN, not HISTORICAL. A disabled test nobody triaged is
    an open question, and an open question must not pass for a closed cycle.
    """
    for f, entries in CLASSIFICATION.items():
        if rel == f:
            for meth, cat, reason, blocking in entries:
                if meth in method:
                    return cat, reason, blocking
    # Untriaged: fail closed. This entry blocks Gate-1 until a human declares
    # what it is, and the run below refuses to publish an inventory that hides
    # it.
    return (
        UNTRIAGED,
        "No CLASSIFICATION entry declares this @Disabled; it must be triaged "
        "before it can be counted as anything else",
        "yes",
    )


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
    counts = dict.fromkeys(CATEGORIES + (UNTRIAGED,), 0)
    untriaged = []
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
                counts[cat] = counts.get(cat, 0) + 1
                if cat == UNTRIAGED:
                    untriaged.append(f"{rel} :: {target}")
                rows.append((module_of(rel), cls, target, cat, cat_reason or reason, "pipeline-kotlin maintainers", blocking))

    sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    # UNKNOWN blocks Gate-1 exactly like MANDATORY_SUPPORTED does: an untriaged
    # disabled test is an unverified hole, and an unverified hole must not be
    # green. It is counted separately so the report does not conflate "nobody
    # triaged this" with "triaged and found mandatory".
    mandatory = counts["MANDATORY_SUPPORTED"] + counts[UNTRIAGED]
    gate = "OPEN (mandatory or untriaged disabled > 0)" if mandatory > 0 else "not blocked by @Disabled inventory"
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
        "Law: Gate-1 cannot be GREEN while mandatory-disabled > 0.",
        f"`{UNTRIAGED}` counts toward that total and is the fail-closed",
        "default: a @Disabled entry no CLASSIFICATION row declares is",
        "UNTRIAGED, never HISTORICAL. Declaring HISTORICAL is a positive",
        "claim about a closed cycle and must appear in CLASSIFICATION, where",
        "it is reviewable. An unclassified entry also makes this generator",
        "exit non-zero, so the inventory cannot be regenerated while hiding one.",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[-16:]))

    if untriaged:
        print("\n".join([
            "",
            f"FAIL: {len(untriaged)} @Disabled entr(y/ies) not declared in CLASSIFICATION.",
            "An unclassified entry is UNKNOWN and blocks Gate-1. Add a row to",
            "CLASSIFICATION in scripts/gen-disabled-inventory.py naming its real",
            "category, or the inventory is not trustworthy:",
            *[f"  - {u}" for u in sorted(untriaged)[:20]],
            *([f"  ... and {len(untriaged) - 20} more"] if len(untriaged) > 20 else []),
        ]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
