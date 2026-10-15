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

The generator fails closed (exit 2, nothing written) when:
  - TEST_ROOT does not exist or contains no Kotlin test sources;
  - git cannot name the scanned tree;
  - an @Disabled entry is neither declared in CLASSIFICATION nor
    explained by the archive pattern (`Historical ...` / `Archived ...`);
  - a declared CLASSIFICATION entry is no longer observed in the tree.

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

# file -> (category, reason-summary, decision) classification table.
# Entries whose reason starts with `Historical`/`Archived` are the archived
# migration snapshot pattern and count as HISTORICAL without a declaration.
# Every OTHER @Disabled entry MUST be declared below or the generator fails
# the run: a silent default is exactly how a gate-critical disable used to be
# miscounted as archived evidence.
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
    # --- Declared in the 2026-10-15 harness sweep (B1). Each entry below was
    # surfaced by the generator refusing the tree (exit 2, nothing written) and
    # was classified from its own @Disabled reason plus the evidence it names;
    # the categories state why the disable is tolerable, not that it is good.
    # OBSOLETE: the pinned behaviour can never return (the defect is closed and
    # a live successor test exists), so the row is a deletion candidate.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleFrameFiltersTest.kt": [
        ("StageStepKindResolver treats empty axes as no-op conjuncts", "OBSOLETE",
         "RED pin of the `--kind` semantic drop, now CLOSED; live successor is ConsoleOperationSelectionTest RC3-4/RC3-5 "
         "(docs/v2/07-uat/R4_WU_RC3_CONSOLE_SELECTION_RECEIPT.md)", "no"),
    ],
    # OBSOLETE: the legacy subject was physically deleted; the registry-side
    # witness is CoreSleepG3DifferentialParityTest over CoreSleepStep.descriptor.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreLegacyStepMetadataResolverTest.kt": [
        ("resolves sleep metadata by step key matching the decoded command", "OBSOLETE",
         "subject physically deleted (CanonicalCoreStepCommand Pwd/CleanWs/Load LEGACY_REMOVED); live successor is "
         "CoreSleepG3DifferentialParityTest + CoreSleepStep.descriptor", "no"),
    ],
    # HISTORICAL: archived registry-primary / WONTFIX evidence, kept verbatim.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixRegistryPrimaryFitnessTest.kt": [
        ("legacy source code is physically present", "HISTORICAL",
         "S2-A5 / G5 archived registry-primary evidence (G4 row 8 preserved verbatim)", "no"),
    ],
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/WULpr010CliCharacterizationTest.kt": [
        ("resume of a terminal run reuses the recorded aggregate", "HISTORICAL",
         "WU-LPR-011 F5 closed as WONTFIX; its run() helper hangs, and the canonical contract is pinned by "
         "CanonicalDurableRunCoordinatorTest (SKIP 4-event lifecycle) and UatDsl003ParallelTest.P6", "no"),
    ],
    # EXPERIMENTAL: non-gating surfaces whose subject is blocked or pending rework.
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/RuntimeApiVersionPrereleaseProbeTest.kt": [
        ("a plain version parses", "EXPERIMENTAL",
         "P3 witness probe: 0.48.0-rc1 is illegal BY DESIGN (ProductVersion rejects a candidate suffix); kept as a "
         "law-regression witness, non-gating (P3_CANDIDATE_VERSION_BLOCKER)", "no"),
    ],
    "v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt": [
        ("class ViolationFixture", "EXPERIMENTAL",
         "violation-injection fixture pending a synthetic-source refactor (H2 slice 3): with zero baseline debt it "
         "reports WithinPinnedDebt, so it is non-gating until refactored", "no"),
    ],
    # COMPATIBILITY: reproduced witnesses whose fix is a deferred contract decision.
    "v2/pipeline-output-store/src/test/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStoreTest.kt": [
        ("OUT-01 prune of a short runId", "COMPATIBILITY",
         "OUT-01 reproduced witness kept as a reproduction; the fix needs the durable-format migration policy "
         "(docs/v2/07-uat/WIP4_OUT01_OUT02_REPRODUCED.md), not decided in this cycle", "no"),
        ("OUT-02 distinct runIds whose safe forms are equal collide", "COMPATIBILITY",
         "OUT-02 reproduced witness kept as a reproduction; the fix needs the durable-format migration policy "
         "(docs/v2/07-uat/WIP4_OUT01_OUT02_REPRODUCED.md), not decided in this cycle", "no"),
    ],
}


def module_of(rel: str) -> str:
    m = re.match(r"v2/([^/]+(?:/[^/]+)?)/src/test", rel)
    return m.group(1) if m else rel


# The archived migration/cycle snapshot pattern: the only reason string that
# counts as HISTORICAL without an explicit CLASSIFICATION entry.
ARCHIVE_REASON = re.compile(r"^(Historical|Archived)\b")


def classify(rel: str, method: str, reason: str):
    """Return (category, reason, blocking) or None when the entry is undeclared."""
    for f, entries in CLASSIFICATION.items():
        if rel == f:
            for meth, cat, cat_reason, blocking in entries:
                if meth in method:
                    return cat, cat_reason, blocking
    if ARCHIVE_REASON.match(reason):
        return "HISTORICAL", "Archived migration/cycle snapshot evidence (verbatim traceability)", "no"
    return None


def code_mask(src: str) -> list:
    """Return `is_code` per character: False inside comments and string data.

    The inventory must describe real `@Disabled` annotations, not mentions of
    one. Before this mask existed the extractor scanned raw source, so the
    RP-040 fitness test's own documented example -- `the @Disabled("...") string
    argument` in its KDoc -- was counted as a whole-class disabled entry with
    reason `'...'`, and the generator refused the tree with an invented
    finding. Offsets and line numbers are preserved: the mask only classifies
    characters, it never rewrites the text.
    """
    mask = [True] * len(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                mask[k] = False
            i = j
            continue
        if c == "/" and src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                mask[k] = False
            i = j
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                mask[k] = False
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            for k in range(i, min(j, n)):
                mask[k] = False
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            for k in range(i, min(j + 1, n)):
                mask[k] = False
            i = min(j + 1, n)
            continue
        i += 1
    return mask


def _first_in_code(pattern: str, text: str, mask: list):
    """First match of `pattern` whose start is real code, or None."""
    for m in re.finditer(pattern, text):
        if mask[m.start()]:
            return m
    return None


def extract_entries(rel_path: str, abs_path: str):
    with open(abs_path, encoding="utf-8") as fh:
        src = fh.read()
    entries = []
    mask = code_mask(src)
    # match @Disabled("...") possibly multiline, followed by optional annotations
    # then `fun \`name\`` or `class Name`. Matches inside comments or string
    # data are documentation, not disabled tests, and are skipped below.
    for m in re.finditer(r'(?:@|^|\s)@(?:org\.junit\.jupiter\.api\.)?Disabled\(\s*"((?:[^"\\]|\\.)*)"', src, re.S):
        at = src.find("@", m.start(), m.end())
        if at < 0 or not mask[at]:
            continue
        tail = src[m.end():m.end() + 600]
        tail_mask = mask[m.end():m.end() + 600]
        fm = _first_in_code(r'(?:@[^\n@]*\n\s*)*fun `([^`]+)`', tail, tail_mask)
        cm = _first_in_code(r'class\s+(\w+)', tail, tail_mask)
        if fm:
            target = f"`{fm.group(1)}`"
        elif cm:
            target = f"class {cm.group(1)} (whole class)"
        else:
            target = "<unresolved>"
        reason = m.group(1).replace("\\\"", "\"")[:160]
        line = src.count("\n", 0, m.start()) + 1
        entries.append((target, reason, line))
    return entries


def main():
    """Regenerate the inventory. Returns 0 on success, 2 on a fail-closed refusal."""
    errors = []
    rows = []
    counts = {"MANDATORY_SUPPORTED": 0, "COMPATIBILITY": 0, "HISTORICAL": 0,
              "EXPERIMENTAL": 0, "OBSOLETE": 0}
    scanned_kt = 0
    observed = []

    if not os.path.isdir(TEST_ROOT):
        errors.append(f"scan root does not exist: {TEST_ROOT}")

    if os.path.isdir(TEST_ROOT):
        for dirpath, _dirs, files in os.walk(TEST_ROOT):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                abs_path = os.path.join(dirpath, f)
                rel = os.path.relpath(abs_path, ROOT)
                if "/src/test/" not in rel:
                    continue
                scanned_kt += 1
                with open(abs_path, encoding="utf-8", errors="ignore") as fh:
                    has_disabled = "@Disabled" in fh.read()
                if not has_disabled:
                    continue
                cls = f
                for target, reason, line in extract_entries(rel, abs_path):
                    observed.append((rel, target))
                    result = classify(rel, target, reason)
                    if result is None:
                        errors.append(
                            f"unclassified @Disabled entry: {rel}:{line}  {target}  {reason!r}"
                        )
                        continue
                    cat, cat_reason, blocking = result
                    counts[cat] += 1
                    rows.append((module_of(rel), cls, target, cat, cat_reason or reason, "pipeline-kotlin maintainers", blocking))
        if scanned_kt == 0:
            errors.append(
                f"scan root contains no Kotlin test sources under src/test: {TEST_ROOT}"
            )

    # A declared classification that no longer matches anything is stale: it
    # would hide the entry it was written for (expected COMPATIBILITY 2 -> 0).
    for f, entries in CLASSIFICATION.items():
        for meth, _cat, _reason, _blocking in entries:
            if not any(rel == f and meth in target for rel, target in observed):
                errors.append(
                    f"declared CLASSIFICATION entry not observed in the tree: {f} :: {meth}"
                )

    git_result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT
    )
    sha = git_result.stdout.strip()
    if git_result.returncode != 0 or not sha:
        errors.append(
            "cannot determine the scanned tree: git rev-parse --short HEAD failed in "
            f"{ROOT}"
        )

    if errors:
        print(
            f"REFUSED: {len(errors)} problem(s); no inventory written. Classify the entries "
            "in CLASSIFICATION or fix the scan root, then rerun.",
            file=sys.stderr,
        )
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 2

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
        f"kotlin test files scanned     = {scanned_kt}",
        f"@Disabled total                 = {len(rows)}",
        *[f"{k:<24} = {v}" for k, v in counts.items()],
        f"mandatory-disabled (Gate-1)    = {mandatory}",
        f"Gate-1 @Disabled state         = {gate}",
        "```",
        "",
        "Regenerate: `python3 scripts/gen-disabled-inventory.py`",
        "",
        "Law: Gate-1 cannot be GREEN while mandatory-disabled > 0. Every",
        "non-archived @Disabled entry MUST be declared in CLASSIFICATION; an",
        "undeclared entry, an unobserved declaration, an empty scan or a missing",
        "git SHA makes the generator refuse (exit 2) and publish nothing.",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[-16:]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
