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
  PENDING_EXTERNAL     owner is another repository; awaits its verdict
  KNOWN_DEFECT         reproduced open defect kept as a witness, not a guard

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
    "PENDING_EXTERNAL",
    "KNOWN_DEFECT",
)
UNTRIAGED = "UNKNOWN"

# Owner implied by the category. `migrated-pending` means another
# repository holds the authority to retire the entry; filing it under the
# producer's name would name an owner who cannot act.
OWNER_BY_CATEGORY = {
    "PENDING_EXTERNAL": "pipelinek-release-harness",
}
DEFAULT_OWNER = "pipeline-kotlin maintainers"

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

# --------------------------------------------------------------------------
# Derived rule: legacy burn-down counter snapshots
# --------------------------------------------------------------------------
#
# ~100 of the untriaged entries assert how many legacy keys REMAINED at a
# named gate of the S2 burn-down ("post-S2-A6-G4", "counters are 7-7-7",
# "3 residual legacy keys remain"). Listing each one in CLASSIFICATION would
# be a hundred exact-string rows hand-maintained by hand — the same fragile
# shape this repository has been removing all week, and it would drift the
# moment a method is renamed.
#
# They are nonetheless a single fact, and one this script can CHECK rather
# than assume: LEGACY_PLUGIN_IDS is empty, so a counter snapshot describing
# "N residual keys" describes a closed cycle. That is what the rule asserts,
# and it reads the set from the source at generation time instead of trusting
# a name.
#
# The rule is fail-closed by construction. If the burn-down ever reopens and
# the set is non-empty, the rule stops firing and every snapshot it covered
# falls back to UNKNOWN, blocking Gate-1 again — the correct outcome, because
# a reopened migration has live invariants rather than closed history.

LEGACY_IDS_PATH = (
    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/"
    "CanonicalCoreStepDecoder.kt"
)

# Phrasings that only make sense as a snapshot OF a migration in progress.
# Deliberately narrow: an entry that does not read like a burn-down counter
# keeps its UNKNOWN status and stays a question for a human.
BURN_DOWN_SNAPSHOT_RE = re.compile(
    r"post-S2-[A-Z0-9]+-G\d+"            # a named (work-unit, gate) point
    r"|residual legacy keys remain"
    r"|counters?\s+(are|drop to)\b",
    re.IGNORECASE,
)

BURN_DOWN_REASON = (
    "Legacy burn-down counter snapshot. LEGACY_PLUGIN_IDS was read as EMPTY "
    "at generation time, so the residual-key count this entry asserts "
    "describes a closed cycle rather than a live invariant."
)

# Counter snapshots are not the only thing the burn-down left behind. Some
# entries assert a property that is STILL TRUE today -- "core.pwd stays
# absent from LEGACY_PLUGIN_IDS", "no legacy resurrection" -- and are pinned
# to a gate name only because that is where they were written. Those are live
# guards wearing a snapshot's clothes, and filing them HISTORICAL would hide a
# regression behind a non-gating category. They stay UNKNOWN for a human.
LIVE_INVARIANT_RE = re.compile(
    r"stays?\s+absent"
    r"|is\s+absent"
    r"|no\s+legacy\s+resurrection"
    r"|irreversible",
    re.IGNORECASE,
)


def legacy_burn_down_converged() -> bool:
    """True only while `LEGACY_PLUGIN_IDS` is empty.

    The declaration is anchored to this fact, so the fact is read rather than
    assumed. A missing or unreadable source is NOT convergence: it must fall
    back to UNKNOWN, because silence about the set is not evidence about it.
    """
    path = os.path.join(ROOT, LEGACY_IDS_PATH)
    if not os.path.isfile(path):
        return False
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    start = src.find("val LEGACY_PLUGIN_IDS")
    if start < 0:
        return False
    end = src.find(")", start)
    if end < 0:
        return False
    # Comments narrate the removed keys by name; only code counts.
    body = "\n".join(
        line for line in src[start:end].splitlines() if not line.strip().startswith("//")
    )
    return '"core.' not in body


# The ENABLED fitness that pins LEGACY_PLUGIN_IDS to empty.
#
# Sixteen disabled rows assert "core.X stays absent from LEGACY_PLUGIN_IDS".
# A @Disabled row guards nothing, so on its own that guarantee would be
# unowned -- until you notice that LegacyResidualSnapshot.physicalResidual is
# `emptySet()` and LegacyResidualConvergenceFitnessTest is NOT disabled, and
# therefore already asserts exactly that. The sixteen are disabled duplicates
# of a live guard, not unowned guarantees.
#
# The claim is only worth anything if it is checked, so it is: the retirement
# below is conditional on this file existing AND carrying no @Disabled. Delete
# or disable the successor and the sixteen fall back to blocking, which is the
# correct direction to fail.
CONVERGENCE_FITNESS_PATH = (
    "v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/"
    "architecture/LegacyResidualConvergenceFitnessTest.kt"
)


def convergence_fitness_is_live() -> bool:
    """True only while the successor guard exists and is actually enabled."""
    path = os.path.join(ROOT, CONVERGENCE_FITNESS_PATH)
    if not os.path.isfile(path):
        return False
    with open(path, encoding="utf-8") as fh:
        return "@Disabled" not in fh.read()


# "core dot pwd stays absent from LEGACY_PLUGIN_IDS", "no legacy resurrection",
# "core.X is absent from LEGACY_PLUGIN_IDS": the whole cluster, phrased three
# different ways across sixteen rows.
LEGACY_ABSENCE_RE = re.compile(r"absent|resurrection", re.IGNORECASE)

OBSOLETE_BY_EQUIVALENCE_REASON = (
    "Asserts a live invariant that IS owned, by an enabled guard: "
    + CONVERGENCE_FITNESS_PATH.split("/")[-1]
    + " pins LEGACY_PLUGIN_IDS to empty via LegacyResidualSnapshot."
    + "physicalResidual = emptySet(). This @Disabled row is a disabled duplicate "
    + "of that guard, so it adds no coverage and cannot fail. Retiring it is "
    + "authorised by equivalence, not by age."
)


# --------------------------------------------------------------------------
# Declared tokens: the author's own disposition
# --------------------------------------------------------------------------
#
# An author who writes WHY a test is disabled has triaged it. Until this
# generator learned to read that, 96 entries sat at UNKNOWN while carrying a
# deliberate, policy-cited disposition in the only field a reader will look
# at. Silence is not evidence of archiving -- but neither is prose the
# generator cannot see.
#
# The tokens are matched at the START of the reason, so they are a declared
# vocabulary rather than a guess about wording anywhere in a sentence.
#
# This is NOT a bypass, and the reason matters: a token is a claim, and a
# claim that a human wrote is a human decision -- the same status a row in
# CLASSIFICATION has. What keeps it honest is that the tokens are a CLOSED
# vocabulary, and every one of them is still subject to the LIVE_INVARIANT
# guard below. An author who writes "Historical ..." on top of a method named
# `no legacy resurrection` does not get a non-gating category; that is
# observed in the tree today, in EmitEventStepContractSuiteTest.

DECLARED_TOKENS = (
    # (regex anchored at reason start, category, blocking, reason-summary)
    (r"migrated-pending\b", "PENDING_EXTERNAL", "yes",
     "Author-declared migrated-pending: the authority to verify and retire this "
     "test moved to pipelinek-release-harness (AGENTS.md responsibility boundary). "
     "It is NOT historical; it blocks Gate-1 until the harness rules on it."),
    (r"producer-internal:", "MANDATORY_SUPPORTED", "yes",
     "Author-declared producer-internal: an external harness cannot reproduce this "
     "fixture or replay, so the test stays with the producer permanently and "
     "remains mandatory here."),
    (r"(Historical|Archived)\b", "HISTORICAL", "no",
     "Author-declared closed-cycle snapshot; names the cycle in the reason."),
    (r"reproduced;", "KNOWN_DEFECT", "no",
     "Author-declared reproduced defect kept as a witness rather than a passing "
     "guard. Non-blocking by construction: the test documents a defect that is "
     "known and open, so gating on it would gate on a known-red."),
)
DECLARED_TOKEN_RES = tuple(
    (re.compile(pattern, re.IGNORECASE), category, blocking, summary)
    for pattern, category, blocking, summary in DECLARED_TOKENS
)


def declared_disposition(reason: str):
    """Return (category, blocking, summary) from the reason's leading token."""
    text = reason.strip()
    for rx, cat, blk, why in DECLARED_TOKEN_RES:
        if rx.match(text):
            return cat, blk, why
    return None


def module_of(rel: str) -> str:
    m = re.match(r"v2/([^/]+(?:/[^/]+)?)/src/test", rel)
    return m.group(1) if m else rel


def classify(rel: str, method: str, reason: str = "") -> tuple:
    """Return (category, reason, blocks_gate_1) for one @Disabled entry.

    The default is UNKNOWN, not HISTORICAL. A disabled test nobody triaged is
    an open question, and an open question must not pass for a closed cycle.

    `reason` is the author's @Disabled text. It is consulted -- but never
    before CLASSIFICATION, which is this repository's canonical place for a
    declaration a reviewer can see without opening 89 test files.
    """
    for f, entries in CLASSIFICATION.items():
        if rel == f:
            for meth, cat, cat_reason, blocking in entries:
                if meth in method:
                    return cat, cat_reason, blocking

    # A live guard whose guarantee is ALREADY owned by an enabled fitness is a
    # disabled duplicate, and equivalence -- not age -- is what authorises its
    # retirement. Checked before the LIVE_INVARIANT guard below, which would
    # otherwise park it at UNKNOWN forever for a question that is already
    # answered by a running test.
    if (LIVE_INVARIANT_RE.search(method) or LIVE_INVARIANT_RE.search(reason)) \
            and "LEGACY_PLUGIN_IDS" in (method + reason) \
            and LEGACY_ABSENCE_RE.search(method + reason) \
            and legacy_burn_down_converged() \
            and convergence_fitness_is_live():
        return "OBSOLETE", OBSOLETE_BY_EQUIVALENCE_REASON, "no"

    # A live guard is a live guard however it was labelled. Checked against the
    # METHOD because that is what the guard asserts, and against the REASON
    # because an author can put the claim in either -- EmitEventStepContractSuite
    # declares itself historical while its method is named
    # `no legacy resurrection - irreversible G5 state holds`.
    if LIVE_INVARIANT_RE.search(method) or LIVE_INVARIANT_RE.search(reason):
        return (
            UNTRIAGED,
            "Asserts a property that is still live (legacy must not resurrect), not a "
            "closed-cycle record. It is NOT historical under any label; it needs a human "
            "to decide whether to keep it as MANDATORY_SUPPORTED with an updated assertion.",
            "yes",
        )

    # Derived rule, evaluated AFTER the explicit table so a human declaration
    # always wins over an inference. See the rule's comment for why it is
    # anchored to a fact read from source rather than to the method name.
    if BURN_DOWN_SNAPSHOT_RE.search(method) and legacy_burn_down_converged():
        return "HISTORICAL", BURN_DOWN_REASON, "no"

    declared = declared_disposition(reason)
    if declared:
        cat, blocking, why = declared
        return cat, why, blocking

    # Untriaged: fail closed. This entry blocks Gate-1 until a human declares
    # what it is, and the run below refuses to publish an inventory that hides
    # it.
    return (
        UNTRIAGED,
        "No CLASSIFICATION row and no declared token in the @Disabled reason "
        "classify this entry; it must be triaged before it can be counted as "
        "anything else",
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
        reason = m.group(1).replace("\\\"", "\"")
        entries.append((target, reason))
    return entries


# Only the TABLE is truncated. classify() always sees the whole reason: a
# disposition stated in the tail of a long @Disabled string is still a
# disposition, and truncating first is how the generator learned to disagree
# with the author.
DISPLAY_REASON_CHARS = 160


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
                cat, cat_reason, blocking = classify(rel, target, reason)
                counts[cat] = counts.get(cat, 0) + 1
                if cat == UNTRIAGED:
                    untriaged.append(f"{rel} :: {target}")
                rows.append((module_of(rel), cls, target, cat,
                            cat_reason or reason[:DISPLAY_REASON_CHARS],
                            OWNER_BY_CATEGORY.get(cat, DEFAULT_OWNER), blocking))

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
        "default: an entry is UNKNOWN unless a CLASSIFICATION row declares it",
        "or its @Disabled reason opens with one of the declared tokens",
        "(`migrated-pending`, `producer-internal:`, `Historical`/`Archived`,",
        "`reproduced;`). A token is the author's decision and is honoured -- but",
        "it is still subject to the live-invariant guard, so labelling a live",
        "guard `Historical` does not make it non-gating. An unclassified entry",
        "also makes this generator exit non-zero, so the inventory cannot be",
        "regenerated while hiding one.",
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
