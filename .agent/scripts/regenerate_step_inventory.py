#!/usr/bin/env python3
"""
Regenerate docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md from authoritative sources.

Authoritative sources (machine-derived):
- v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
- v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt
- v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt
- v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt
- v2/pipeline-step-sdk/{scm-git,junit,utilities}/src/main/kotlin/.../Key.kt or Contract.kt (PluginStepId("..."))
- examples/example-uppercase-plugin/src/main/kotlin/.../UppercaseStepDefinition.kt

Certification state (per receipt):
- CERTIFIED_AT_SHA — receipt exists at docs/v2/07-uat/S2_*_G8_*.md or equivalent G8
- REGISTERED — handler exists but no G8 receipt yet
- EXPERIMENTAL — registered but not on the production canonical path
- REJECTED — design rejected, no production wiring (e.g. core.load)

This script is the source-of-truth authority for the inventory. The generated
markdown table is human-readable evidence; the script output is authoritative.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORE_REGISTRY = REPO_ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt"
LEGACY_DECODER = REPO_ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt"
LEGACY_METADATA = REPO_ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt"
LEGACY_DISPATCHER = REPO_ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt"
SDK_KEY_GLOB = "v2/pipeline-step-sdk/*/src/main/kotlin/**/Key.kt"
EXTERNAL_KEY = REPO_ROOT / "examples/example-uppercase-plugin/src/main/kotlin/example/uppercase/UppercaseStepDefinition.kt"
INVENTORY_OUT = REPO_ROOT / "docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md"

# --- Receipt mapping: per-Step certification receipts (G8/G7/G6 receipts).
CERTIFIED_RECEIPTS = {
    "core.echo": "S3_ECHO_BURNDOWN_CERTIFICATION.md",
    "core.sh": "LB02_S6_BURN_DOWN_AND_CERTIFICATION.md",
    "core.error": "S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.sleep": "S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.file.writeFile": "S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.file.readFile": "LB02_LEG1_PIPELINE_RUN_BURN_DOWN.md",
    "core.file.fileExists": "LB02_LEG1_PIPELINE_RUN_BURN_DOWN.md",
    "core.emit.event": "S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.isUnix": "S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.pwd": "S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md",  # BLOCKED, not CERTIFIED
    "core.pwd.tmp": None,  # No G6/G8 yet
    "core.deleteDir": "S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md",
    "core.waitUntil": "S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md",
    "core.milestone": "S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md",
    "core.cleanWs": "S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md",
    "core.archiveArtifacts": "S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md",
    "core.stash": "WU_LPR_089_CORE_STASH_UNSTASH_TIER_B1.md",
    "core.unstash": "WU_LPR_089_CORE_STASH_UNSTASH_TIER_B1.md",
    "core.publishHTML": "WU_LPR_090_CORE_PUBLISH_HTML_TIER_B2.md",
    "core.artifact.query": None,  # E1.1 bridge; G6/G8 pending
    "example.uppercase": "LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md",
}

# Steps explicitly in BLOCKED state
BLOCKED_STEPS = {
    "core.pwd": ("STRUCTURED_DSL_RUNTIME_RETURN_GAP",
                 "Blocked by LFC-2R2 spike (ADR-0093 ACCEPTED on main; WU-LPR-087 implementation pending)"),
}

# Steps explicitly DEFERRED (no production intent yet)
DEFERRED_STEPS = {
    "core.load": ("DEFERRED (UNSUPPORTED)", "DSL load(path) is fail-closed at compile-time per WU-LPR-301 / G5"),
}

# Tier B batch 2 / WU-094 TBD — kept in registry but scope undecided
TIER_B_TBD = {
    # core.input (WU-092) and core.httpRequest (WU-093) are scope decisions for RP-6
}


def parse_registered_core_steps(path: Path) -> list[str]:
    """Extract CoreXxxStep.registerInto(this) calls in CoreStepRegistryFactory.kt."""
    text = path.read_text()
    return re.findall(r"Core([A-Z][A-Za-z]+)Step\.registerInto", text)


def parse_legacy_plugin_ids(path: Path) -> list[str]:
    """Extract LEGACY_PLUGIN_IDS set entries (excluding comments and dedup)."""
    text = path.read_text()
    m = re.search(r"val LEGACY_PLUGIN_IDS: Set<String>\s*=\s*setOf\((.*?)\n\s*\)\n", text, re.DOTALL)
    if not m:
        return []
    body = m.group(1)
    # Strip comments line-by-line (everything from // onwards)
    cleaned = []
    for line in body.split("\n"):
        idx = line.find("//")
        if idx >= 0:
            line = line[:idx]
        if line.strip():
            cleaned.append(line)
    keys = re.findall(r'"([a-zA-Z0-9_.]+)"', "\n".join(cleaned))
    # Filter only "core.*" keys (some entries like "workspace" are not StepKeys)
    return sorted(set(k for k in keys if k.startswith("core.")))


def parse_legacy_metadata_rows(path: Path) -> list[str]:
    """Extract keys from CanonicalCoreStepMetadata.table mapOf entries (excluding comments)."""
    text = path.read_text()
    m = re.search(r"private val table: Map<String, StepMetadata>\s*=\s*mapOf\((.*?)\n\s*\)", text, re.DOTALL)
    if not m:
        return []
    body = m.group(1)
    cleaned = []
    for line in body.split("\n"):
        idx = line.find("//")
        if idx >= 0:
            line = line[:idx]
        if line.strip():
            cleaned.append(line)
    return sorted(set(re.findall(r'"([a-zA-Z0-9_.]+)"', "\n".join(cleaned))))


def parse_legacy_dispatcher_keys(path: Path) -> list[str]:
    """Extract PluginStepId("...") references in CanonicalNodeDispatcher.kt when-statement."""
    text = path.read_text()
    return sorted(set(re.findall(r'PluginStepId\("([a-zA-Z0-9_.\-]+)"\)', text)))


def parse_sdk_step_keys() -> list[tuple[str, str]]:
    """Return [(step_key, source_path)] for every PluginStepId(...) in pipeline-step-sdk plugins."""
    matches = []
    # pathlib.rglob('Key.kt') (without wildcard prefix) returns 0 results on this filesystem;
    # use a glob that anchors at the directory and walks recursively. rglob from the
    # repo-root relative path also failed (0 results); rglob from the absolute SDK root
    # returns the expected 9 (utilities) + 1 (junit) + 1 (scm-git/Contract.kt) = 11.
    sdk_root = REPO_ROOT / "v2/pipeline-step-sdk"
    seen_paths = set()
    for kt in sdk_root.rglob("*"):  # walk everything under the SDK
        if not kt.is_file():
            continue
        if kt.name.endswith("Key.kt") or kt.name.endswith("Contract.kt"):
            seen_paths.add(kt)
    for kt in sorted(seen_paths):
        text = kt.read_text()
        for m in re.finditer(r'PluginStepId\("([a-zA-Z0-9_.\-]+)"\)', text):
            matches.append((m.group(1), str(kt.relative_to(REPO_ROOT))))
    return sorted(set(matches), key=lambda t: t[0])


def parse_external_step_keys(path: Path) -> list[str]:
    text = path.read_text()
    return list({m.group(1) for m in re.finditer(r'PluginStepId\("([a-zA-Z0-9_.\-]+)"\)', text)})


def resolve_core_step_key(class_short: str) -> str | None:
    """
    Map a CoreXxxStep class short name to its production StepKey (PluginStepId value).
    Returns None if the class is a private helper (e.g. CoreStashStep hosts CoreUnstashStep).
    """
    mapping = {
        "Echo": "core.echo",
        "Shell": "core.sh",
        "Error": "core.error",
        "Sleep": "core.sleep",
        "WriteFile": "core.file.writeFile",
        "ReadFile": "core.file.readFile",
        "FileExists": "core.file.fileExists",
        "ArchiveArtifacts": "core.archiveArtifacts",
        "ArtifactQuery": "core.artifact.query",
        "EmitEvent": "core.emit.event",
        "IsUnix": "core.isUnix",
        "Pwd": "core.pwd",
        "PwdTmp": "core.pwd.tmp",
        "DeleteDir": "core.deleteDir",
        "WaitUntil": "core.waitUntil",
        "Milestone": "core.milestone",
        "CleanWs": "core.cleanWs",
        "Stash": "core.stash",
        "Unstash": "core.unstash",
        "PublishHtml": "core.publishHTML",
    }
    return mapping.get(class_short)


def git_head_sha() -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True).strip()
    except subprocess.CalledProcessError:
        return "UNKNOWN"


def classify_core_step(step_key: str) -> str:
    """Return one of: CERTIFIED_AT_SHA | REGISTERED | EXPERIMENTAL | BLOCKED_<reason> | DEFERRED."""
    if step_key in BLOCKED_STEPS:
        return f"BLOCKED ({BLOCKED_STEPS[step_key][0]})"
    if step_key in DEFERRED_STEPS:
        return f"DEFERRED ({DEFERRED_STEPS[step_key][0]})"
    receipt = CERTIFIED_RECEIPTS.get(step_key)
    if receipt:
        return "CERTIFIED_AT_SHA"
    return "REGISTERED"


def receipt_for(step_key: str) -> str:
    return CERTIFIED_RECEIPTS.get(step_key) or "—"


def notes_for(step_key: str) -> str:
    if step_key in BLOCKED_STEPS:
        return BLOCKED_STEPS[step_key][1]
    if step_key in DEFERRED_STEPS:
        return DEFERRED_STEPS[step_key][1]
    if step_key in TIER_B_TBD:
        return TIER_B_TBD[step_key]
    if step_key == "core.artifact.query":
        return "E1.1 bridge; G6/G8 pending"
    if step_key == "core.pwd.tmp":
        return "Depends on LFC-2R2 like pwd; will follow WU-LPR-087"
    return ""


def build_inventory(head_sha: str) -> dict:
    core_classes = parse_registered_core_steps(CORE_REGISTRY)
    # Resolve each CoreXxxStep class short name to its production key (some private helpers skipped).
    core_keys = []
    for cls in core_classes:
        k = resolve_core_step_key(cls)
        if k is None:
            print(f"WARN: Core{cls}Step.registerInto has no PluginStepId mapping", file=sys.stderr)
            continue
        core_keys.append(k)

    legacy_plugin_ids = parse_legacy_plugin_ids(LEGACY_DECODER)
    legacy_metadata_rows = parse_legacy_metadata_rows(LEGACY_METADATA)
    legacy_dispatcher_keys = parse_legacy_dispatcher_keys(LEGACY_DISPATCHER)
    sdk_keys = parse_sdk_step_keys()
    external_keys = parse_external_step_keys(EXTERNAL_KEY)

    return {
        "head_sha": head_sha,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "core_keys": sorted(set(core_keys)),
        "legacy_plugin_ids": legacy_plugin_ids,
        "legacy_metadata_rows": legacy_metadata_rows,
        "legacy_dispatcher_keys": legacy_dispatcher_keys,
        "sdk_keys": sdk_keys,
        "external_keys": external_keys,
    }


def render_markdown(inv: dict) -> str:
    lines = []
    lines.append("# LFC-2E0+1+2 — Step Inventory (machine-derived, regenerable)")
    lines.append("")
    lines.append(f"Status: **REGENERATED {inv['generated_at']}** (auto-generated by `.agent/scripts/regenerate_step_inventory.py`).")
    lines.append("This file is the **source of truth** for the LFC-2E program. The script")
    lines.append("that produces it parses authoritative sources (see below) and emits this")
    lines.append("table. The table is the human-readable evidence; the script output is the")
    lines.append("authoritative state. Run the script to refresh after any Step burn-down.")
    lines.append("")
    lines.append("## Head SHA at generation")
    lines.append("")
    lines.append(f"```text")
    lines.append(f"git rev-parse HEAD: {inv['head_sha']}")
    lines.append("```")
    lines.append("")
    lines.append("## Generation command")
    lines.append("")
    lines.append("```bash")
    lines.append("python3 .agent/scripts/regenerate_step_inventory.py")
    lines.append("```")
    lines.append("")
    lines.append("## Scope of inventory")
    lines.append("")
    lines.append("- Production Core Step keys: derived from `CoreStepRegistryFactory.registerInto`")
    lines.append("  calls (one `Core<X>Step.registerInto(this)` per production key).")
    lines.append("- SDK plugin Step keys: derived from `PluginStepId(\"...\")` constants in")
    lines.append("  `v2/pipeline-step-sdk/*/src/main/kotlin/**/Key.kt`.")
    lines.append("- External plugin Step keys: derived from `examples/example-uppercase-plugin`")
    lines.append("  (current canonical external reference per ADR-0074).")
    lines.append("- Legacy authority counters: derived from `LEGACY_PLUGIN_IDS`, the")
    lines.append("  `CanonicalCoreStepMetadata.table` map, and the `CanonicalNodeDispatcher`")
    lines.append("  `when` cases. All three should converge to 0 in production.")
    lines.append("")
    lines.append("## Counts (machine-derived)")
    lines.append("")
    n_certified = sum(1 for k in inv["core_keys"] + [k for k, _ in inv["sdk_keys"]] + inv["external_keys"]
                      if classify_core_step(k) == "CERTIFIED_AT_SHA" and k in CERTIFIED_RECEIPTS and CERTIFIED_RECEIPTS[k] is not None)
    n_registered = sum(1 for k in inv["core_keys"] if classify_core_step(k) == "REGISTERED")
    n_blocked = sum(1 for k in inv["core_keys"] if k in BLOCKED_STEPS)
    lines.append(f"```text")
    lines.append(f"Production Step keys total:                {len(inv['core_keys']) + len(inv['sdk_keys']) + len(inv['external_keys'])}")
    lines.append(f"  Core (CoreStepRegistryFactory):         {len(inv['core_keys'])}")
    lines.append(f"  SDK plugins:                            {len(inv['sdk_keys'])}")
    lines.append(f"  External plugins:                       {len(inv['external_keys'])}")
    lines.append("")
    lines.append(f"  CERTIFIED_AT_SHA:                       {n_certified}")
    lines.append(f"  REGISTERED (handler present, no G8):    {n_registered}")
    lines.append(f"  BLOCKED:                                {n_blocked}")
    lines.append("")
    lines.append(f"Legacy authority counters (must be 0):")
    lines.append(f"  LEGACY_PLUGIN_IDS:                      {len(inv['legacy_plugin_ids'])}")
    lines.append(f"  CanonicalCoreStepMetadata rows:         {len(inv['legacy_metadata_rows'])}")
    lines.append(f"  CanonicalNodeDispatcher when-cases:     {len(inv['legacy_dispatcher_keys'])}")
    lines.append("```")
    lines.append("")
    lines.append("## Inventory table")
    lines.append("")
    lines.append("State legend:")
    lines.append("- `CERTIFIED_AT_SHA` — handler exists, G8 receipt on file, real installDist canary green.")
    lines.append("- `REGISTERED` — handler exists, no G8 receipt yet (burn-down pending).")
    lines.append("- `BLOCKED (<reason>)` — handler exists but routed as legacy / fail-closed at admission.")
    lines.append("- `DEFERRED (<reason>)` — DSL surface accepted by compiler; runtime is fail-closed at compile-time.")
    lines.append("- `EXPERIMENTAL` — design-only, not on production path.")
    lines.append("- `REJECTED` — design rejected, never reaches production.")
    lines.append("")
    lines.append("| Step key | Source path | State | Receipt | Notes |")
    lines.append("|---|---|---|---|---|")

    def emit_row(key: str, src: str):
        state = classify_core_step(key)
        if state == "REGISTERED" and key not in CERTIFIED_RECEIPTS and key not in BLOCKED_STEPS and key not in DEFERRED_STEPS:
            receipt_str = "—"
        else:
            receipt_str = receipt_for(key) if receipt_for(key) != "—" else "—"
        notes = notes_for(key)
        # Truncate notes for readability
        if notes and len(notes) > 80:
            notes = notes[:77] + "..."
        lines.append(f"| `{key}` | `{src}` | {state} | {receipt_str} | {notes} |")

    # Core
    for k in inv["core_keys"]:
        # Resolve source path heuristically from class short name
        cls = k.replace(".", "").replace("core", "").title()
        # Find any Core<name>Step.kt that matches
        candidates = list(REPO_ROOT.glob(f"v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Core*Step.kt"))
        # Pick the matching one
        src = "—"
        for cand in candidates:
            stem = cand.stem  # e.g. CoreEchoStep
            if stem == f"Core{cls}Step":
                src = str(cand.relative_to(REPO_ROOT))
                break
        # Special case: Unstash lives in CoreStashStep.kt
        if k == "core.unstash":
            stash = REPO_ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStashStep.kt"
            if stash.exists():
                src = str(stash.relative_to(REPO_ROOT))
        emit_row(k, src or "—")

    # SDK plugins
    for key, src in inv["sdk_keys"]:
        emit_row(key, src)

    # External plugins
    for k in inv["external_keys"]:
        emit_row(k, "examples/example-uppercase-plugin/src/main/kotlin/example/uppercase/UppercaseStepDefinition.kt")

    lines.append("")
    lines.append("## State invariants")
    lines.append("")
    lines.append(f"- `LEGACY_PLUGIN_IDS.size() == {len(inv['legacy_plugin_ids'])}` (production target: 0).")
    lines.append(f"- `CanonicalCoreStepMetadata.table.size() == {len(inv['legacy_metadata_rows'])}` (production target: 0).")
    lines.append(f"- `CanonicalNodeDispatcher` when-cases = {len(inv['legacy_dispatcher_keys'])} (production target: 0).")
    lines.append("- `CoreStepRegistryFactory.registry()` returns a fresh registry with every")
    lines.append("  Core Step registered. No global/singleton registry is exposed.")
    lines.append("- External plugin StepDefinitions enter runtime through `StepDefinitionContributor`")
    lines.append("  (ServiceLoader SPI), never by manual registration.")
    lines.append("")
    lines.append("## Tier B queue (RP-6 scope)")
    lines.append("")
    lines.append("Per `docs/v2/05-roadmap/ROADMAP.md` §8, the post-RP-5 reconciliation will")
    lines.append("decide Tier B and C. Current state:")
    lines.append("- `core.input` (WU-092): TBD")
    lines.append("- `core.httpRequest` (WU-093): TBD")
    lines.append("- Tier B #3 (`core.lock`, WU-LPR-091): **NO_GO** per SESSION_POINTER; preserved in stash.")
    lines.append("- Tier B #4 (`core.publishHTML`, WU-LPR-090 phase-a): **REGISTERED** (RP-1 reconciles).")
    lines.append("- Tier C (readTOML/writeTOML, tar/untar): TBD by product decision.")
    lines.append("")
    lines.append("## References")
    lines.append("")
    lines.append("- `docs/v2/04-adrs/ADR-0074-step-certification.md` — CERTIFIED state law.")
    lines.append("- `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md` — LFC-2R2 spike")
    lines.append("  (ACCEPTED on main; implementation WU-LPR-087 closes the BLOCKED state of")
    lines.append("  `core.pwd` and any future runtime-returning family).")
    lines.append("- `openspec/changes/lfc2-step-constitution-plugin-seam` — constitution.")
    lines.append("- `docs/v2/04-adrs/ADR-0070..0074` — closed execution structure, registry seam.")
    lines.append("")
    return "\n".join(lines) + "\n"


def main_check(inv: dict) -> int:
    """
    Optional sanity check: report any consistency drift between registry and inventory.

    Returns 0 on clean, non-zero on drift (so CI can fail closed).
    """
    errors = 0
    # 1. Every Core<X>Step.registerInto must resolve to a known core key.
    core_classes = parse_registered_core_steps(CORE_REGISTRY)
    for cls in core_classes:
        if resolve_core_step_key(cls) is None:
            print(f"DRIFT: Core{cls}Step.registerInto has no known key mapping", file=sys.stderr)
            errors += 1

    # 2. Legacy counters must be 0 (we expect an empty legacy world).
    if inv["legacy_plugin_ids"]:
        print(f"DRIFT: LEGACY_PLUGIN_IDS not empty: {inv['legacy_plugin_ids']}", file=sys.stderr)
        errors += 1
    if inv["legacy_metadata_rows"]:
        print(f"DRIFT: CanonicalCoreStepMetadata table not empty: {inv['legacy_metadata_rows']}", file=sys.stderr)
        errors += 1
    if inv["legacy_dispatcher_keys"]:
        print(f"DRIFT: CanonicalNodeDispatcher when-cases not empty: {inv['legacy_dispatcher_keys']}", file=sys.stderr)
        errors += 1

    # 3. Every CERTIFIED_AT_SHA entry must have a receipt path.
    for k, receipt in CERTIFIED_RECEIPTS.items():
        if receipt and not (REPO_ROOT / "docs/v2/07-uat" / receipt).exists():
            print(f"WARN: CERTIFIED_AT_SHA {k} points to missing receipt {receipt}", file=sys.stderr)

    return errors


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="Run consistency checks only (exit non-zero on drift).")
    ap.add_argument("--out", type=Path, default=INVENTORY_OUT, help="Output markdown file.")
    args = ap.parse_args()

    head_sha = git_head_sha()
    inv = build_inventory(head_sha)
    drift = main_check(inv)

    if args.check:
        print(f"DRIFT_COUNT={drift}")
        return 1 if drift else 0

    md = render_markdown(inv)
    args.out.write_text(md)
    print(f"wrote {args.out} (head_sha={head_sha}, drift={drift})")
    return 1 if drift else 0


if __name__ == "__main__":
    sys.exit(main())
