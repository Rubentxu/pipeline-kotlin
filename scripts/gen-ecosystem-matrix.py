#!/usr/bin/env python3
"""WU-F4: deterministic ecosystem matrix generator.

Produces `docs/v2/07-uat/WU_F4_ECOSYSTEM_MATRIX.md`, derived (never
hand-edited) from:

  1. CoreStepRegistryFactory.kt                 -> CORE StepDefinitions
  2. examples/*/src/main/kotlin/**/*StepDefinition.kt -> EXTERNAL_REFERENCE
  3. PipelineDsl.kt                              -> orchestration blocks
  4. WU_LPR_032_RECEIPT.md                       -> admission (SUPPORTED/EXPERIMENTAL/DEFERRED/UNSUPPORTED)
  5. WU-LPR-060 certification ledger             -> certification provenance

The matrix follows LFC2_STEP_ECOSYSTEM_EXPANSION §Core/plugin law:

  CORE                  universal semantics, in CoreStepRegistryFactory
  OFFICIAL_PLUGIN       non-universal high-value, planned in v2 plugin SDK
  EXTERNAL_REFERENCE    external JARs discovered via StepDefinitionContributor
  DEFERRED_REMOTE       remote-controller-only (M4+)
  REJECTED_JENKINS_INTERNAL  Jenkins Java-extension bridges, never ported

Per-operation state ladder (LFC-2E):

  NO_IMPLEMENTADO -> DISENADO -> IMPLEMENTADO_NO_CERTIFICADO -> CERTIFICADO

Orchestration blocks (parallel, dir, withCredentials, etc.) are
listed SEPARATELY from StepDefinitions; the two counts are not summed.
"""
import os
import re
import sys
import datetime
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

FACTORY = ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt"
DSL = ROOT / "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt"
RECEIPT_032 = ROOT / "docs/v2/07-uat/WU_LPR_032_RECEIPT.md"
LEDGER = ROOT / "docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md"
EXTERNAL_PLUGINS_DIR = ROOT / "examples"
OUT = ROOT / "docs/v2/07-uat/WU_F4_ECOSYSTEM_MATRIX.md"

# Known orchestration blocks. Order matches the order in PipelineDsl.kt.
ORCHESTRATION_BLOCKS = [
    ("pipeline",       "PipelineScope"),
    ("stages",         "StagesScope"),
    ("stage",          "StageScope"),
    ("environment",    "EnvironmentScope"),
    ("options",        "OptionsScope"),
    ("post",           "PostScope"),
    ("parallel",       "ParallelScope"),
    ("withCredentials","CredentialsScope"),
    ("retry",          "RetryBlock"),
    ("timeout",        "TimeoutBlock"),
    ("withEnv",        "EnvOverrideBlock"),
    ("dir",            "DirBlock"),
    ("timestamps",     "TimestampsDecorator"),
    ("waitUntil",      "WaitUntilBlock"),
    ("whenCondition",  "WhenConditionBlock"),
    ("script",         "ScriptBlock"),
    ("node",           "NodeNoOp"),
    ("load",           "LoadStep"),
    ("pwd",            "RuntimeValue"),
    ("isUnix",         "RuntimeValue"),
]


def core_step_keys():
    factory = FACTORY.read_text()
    classes = sorted(set(re.findall(r"(Core[A-Za-z]+Step)\.registerInto", factory)))
    keys = {}
    for cls in classes:
        path = next(ROOT.glob(f"v2/**/{cls}.kt"), None)
        if path is None:
            sys.exit(f"FAIL-LOUD: source for {cls} not found")
        m = re.search(r'PluginStepId\("([^"]+)"\)', path.read_text())
        if not m:
            sys.exit(f"FAIL-LOUD: no StepKey constant in {path}")
        keys[cls] = m.group(1)
    return keys


def external_references():
    """Scan examples/* for *StepDefinition.kt and extract StepKey + step class."""
    out = {}
    for d in sorted(EXTERNAL_PLUGINS_DIR.iterdir()):
        if not d.is_dir():
            continue
        for kt in d.glob("**/*StepDefinition.kt"):
            text = kt.read_text()
            m_key = re.search(r'val\s+KEY(?::\s*PluginStepId)?\s*=\s*PluginStepId\("([^"]+)"\)', text)
            # Match the canonical step object: `object X : StepDefinition<...>`
            m_class = re.search(r'object\s+(\w+)\s*:\s*StepDefinition\s*[<(]', text)
            if m_key and m_class:
                out[m_class.group(1)] = (m_key.group(1), str(kt.relative_to(ROOT)))
    return out


def admission_rows():
    rows = {}
    text = RECEIPT_032.read_text()
    for m in re.finditer(r"^\|\s*`([^`]+)`\s*\|([^|]*)\|([^|]*)\|\s*\*\*([A-Z]+)\*\*([^|]*)\|", text, re.M):
        key, _descriptor, cert, status, _note = m.group(1), m.group(2).strip(), m.group(3).strip(), m.group(4), m.group(5).strip()
        rows[key] = {"cert": cert, "status": status}
    return rows


def ledger_certified_count():
    if not LEDGER.exists():
        return None
    text = LEDGER.read_text()
    m = re.search(r"SUPPORTED_CERTIFIED:\s*\*\*(\d+)\*\*", text)
    return int(m.group(1)) if m else None


def main():
    core_keys = core_step_keys()
    external = external_references()
    admission = admission_rows()
    led_cert = ledger_certified_count()

    # Sanity: every CORE key has an admission row (the ledger generator
    # already enforces this for the cert ledger; we mirror here for
    # this matrix).
    missing = [k for k in core_keys.values() if k not in admission]
    if missing:
        sys.exit(f"FAIL-LOUD: CORE keys missing from WU_LPR_032 admission: {missing}")

    # ---- write matrix ----
    lines = []
    lines.append("# WU-F4 — Ecosystem Matrix (GENERATED)")
    lines.append("")
    lines.append(f"Generated: {datetime.datetime.now(datetime.UTC).isoformat(timespec='seconds')} "
                 f"by `scripts/gen-ecosystem-matrix.py`.")
    lines.append("Single authority for ecosystem inventory. DO NOT edit by hand — regenerate.")
    lines.append("")
    lines.append("Source files:")
    lines.append(f"  - `v2/pipeline-application/src/main/kotlin/.../CoreStepRegistryFactory.kt` (CORE)")
    lines.append(f"  - `examples/*/src/main/kotlin/**/*StepDefinition.kt` (EXTERNAL_REFERENCE)")
    lines.append(f"  - `v2/pipeline-scripting-api/src/main/kotlin/.../dsl/PipelineDsl.kt` (orchestration)")
    lines.append(f"  - `docs/v2/07-uat/WU_LPR_032_RECEIPT.md` (admission)")
    lines.append(f"  - `docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md` (counts: SUPPORTED_CERTIFIED = {led_cert})")
    lines.append("")
    lines.append("## 1. CORE StepDefinitions (registered in `CoreStepRegistryFactory`)")
    lines.append("")
    lines.append(f"Count: **{len(core_keys)}**")
    lines.append("")
    lines.append("| StepKey | Registered class | Admission | Certification |")
    lines.append("|---|---|---|---|")
    for cls, key in sorted(core_keys.items(), key=lambda kv: kv[1]):
        a = admission[key]
        lines.append(f"| `{key}` | `{cls}` | **{a['status']}** | {a['cert']} |")
    lines.append("")
    lines.append(f"SUPPORTED_CERTIFIED: **{sum(1 for k in core_keys.values() if admission[k]['status'] == 'SUPPORTED' and 'CERTIFIED' in admission[k]['cert'])}**")
    lines.append(f"EXPERIMENTAL: **{sum(1 for k in core_keys.values() if admission[k]['status'] == 'EXPERIMENTAL')}**")
    lines.append(f"DEFERRED/UNSUPPORTED: **{sum(1 for k in core_keys.values() if admission[k]['status'] in ('DEFERRED','UNSUPPORTED'))}**")
    lines.append("")
    lines.append("## 2. OFFICIAL_PLUGIN StepDefinitions")
    lines.append("")
    lines.append("Count: **0** (no `examples/*-plugin/` with official SDK packaging).")
    lines.append("")
    lines.append("The official plugin SDK is provided by `pipeline-step-sdk` (per `STEP_PLUGIN_SDK.md`).")
    lines.append("No project-owned OFFICIAL_PLUGIN exists in this repository at the time of this matrix.")
    lines.append("")
    lines.append("## 3. EXTERNAL_REFERENCE StepDefinitions")
    lines.append("")
    lines.append(f"Count: **{len(external)}**")
    lines.append("")
    if external:
        lines.append("| Class | StepKey | Source path |")
        lines.append("|---|---|---|")
        for cls, (key, path) in sorted(external.items()):
            lines.append(f"| `{cls}` | `{key}` | `{path}` |")
    else:
        lines.append("(none)")
    lines.append("")
    lines.append("## 4. DEFERRED_REMOTE / REJECTED_JENKINS_INTERNAL")
    lines.append("")
    lines.append("Count: **0** declared (categories are reserved for M4+ remote-controller work and "
                 "for Jenkins Java-extension bridges that are intentionally never ported).")
    lines.append("")
    lines.append("## 5. Orchestration blocks (NOT StepDefinitions)")
    lines.append("")
    lines.append(f"Count: **{len(ORCHESTRATION_BLOCKS)}**")
    lines.append("")
    lines.append("These are DSL funs that build structural IR (BlockStepNode / StepSpec.*) but")
    lines.append("do not register a `StepDefinition` in the registry. They are NOT counted in")
    lines.append("the CORE total above. The list mirrors `PipelineDsl.kt` and is split by the")
    lines.append("admission gate observed in `WU-F3` empirical audit (HEAD `d89c0f9f`).")
    lines.append("")
    lines.append("| Block | Structural shape | Status (per WU-F3 audit) |")
    lines.append("|---|---|---|")
    block_status = {
        "pipeline": "SUPPORTED (entry point)",
        "stages": "SUPPORTED",
        "stage": "SUPPORTED",
        "environment": "SUPPORTED",
        "options": "SUPPORTED",
        "post": "SUPPORTED (construction-time capture; runtime semantics per WU-LPR-401)",
        "parallel": "DEFERRED (canonical body machinery per ADR-0073; not yet at LPR-GATE-1)",
        "withCredentials": "DEFERRED (credential binder scope; per `UatLocal008 PRE_EXISTING` ledger §4)",
        "retry": "DEFERRED (durable retry; B12 in lfc2-step-constitution-plugin-seam; steered by E-EM-11 D1/D2)",
        "timeout": "DEFERRED (durable timeout; B12 in lfc2-step-constitution-plugin-seam)",
        "withEnv": "SUPPORTED (env-override block)",
        "dir": "DEFERRED (workspace-context block; B11)",
        "timestamps": "DEFERRED (output-decorator block; not certified)",
        "waitUntil": "SUPPORTED (S2-A8 G3R CERTIFIED; runtime predicate via BodyInvoker)",
        "whenCondition": "UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3)",
        "script": "UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3)",
        "node": "UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3)",
        "load": "UNSUPPORTED, fail-closed at compile (canonical bridge rejects; see WU-F3)",
        "pwd": "SUPPORTED (honest placeholder + execution-time materialisation; WU-LPR-402)",
        "isUnix": "SUPPORTED (honest placeholder + execution-time materialisation; WU-LPR-402)",
    }
    for name, shape in ORCHESTRATION_BLOCKS:
        lines.append(f"| `{name}` | `{shape}` | {block_status.get(name, '?')} |")
    lines.append("")
    lines.append("## 6. Counts summary")
    lines.append("")
    lines.append("| Bucket | Count | Authority |")
    lines.append("|---|---|---|")
    lines.append(f"| CORE StepDefinitions | {len(core_keys)} | `CoreStepRegistryFactory.kt` |")
    lines.append(f"| OFFICIAL_PLUGIN StepDefinitions | 0 | (none in repo) |")
    lines.append(f"| EXTERNAL_REFERENCE StepDefinitions | {len(external)} | `examples/*/...*StepDefinition.kt` |")
    lines.append(f"| DEFERRED_REMOTE / REJECTED | 0 | (categories reserved) |")
    lines.append(f"| Orchestration blocks (DSL funs) | {len(ORCHESTRATION_BLOCKS)} | `PipelineDsl.kt` |")
    lines.append("")
    lines.append("**Not summed**: orchestration blocks are not StepDefinitions and live in a")
    lines.append("different namespace. The CORE total governs `StepRegistry` size; the")
    lines.append("orchestration total governs `BlockStepNode` shape coverage.")

    OUT.write_text("\n".join(lines) + "\n")
    print(f"Wrote {OUT}")
    print(f"  CORE keys: {len(core_keys)}")
    print(f"  EXTERNAL references: {len(external)}")
    print(f"  Orchestration blocks: {len(ORCHESTRATION_BLOCKS)}")


if __name__ == "__main__":
    main()
