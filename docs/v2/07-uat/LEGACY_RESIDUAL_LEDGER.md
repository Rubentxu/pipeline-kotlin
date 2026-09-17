# Legacy Residual Ledger — burn-down counter history

**Source of truth:** `docs/v2/status/step-certification.yaml` and the per-Step G5 receipts.

**Last updated:** 2026-09-17 (LFC-2E0 GLOBAL CLOSURE — FASE 3 of the SDDK cycle)

This file is the single source of truth for the legacy executable Step
surface. The "residual counter" is N + M + R, where:

- **N** = `LEGACY_PLUGIN_IDS.size` (legacy decoder entry still present)
- **M** = `CanonicalCoreStepMetadata.pluginIds.size` (legacy metadata row still present)
- **R** = `Canonical*NodeDispatcher.kt` file count in `v2/pipeline-application/src/main/.../durable/` (legacy dispatcher source still present)

The counter tends to 0/0/0 as each legacy Step is migrated to the registry
(`LEGACY_REMOVED`) or formally rejected (`REJECTED`).

## Burn-down timeline

| Date | Slice | Step | Counter (N+M+R) | Action |
|---|---|---|---|---|
| 2026-09-09 | baseline | (initial state, 11/11/11) | 11+11+11 | pre-burn-down (echo, sh already burned) |
| 2026-09-10 | S2-A1 | `core.error` | 11+11+11 → 10+10+10 | REGISTRY_PRIMARY + LEGACY_REMOVED |
| 2026-09-11 | S2-A4 | `core.emit.event` | 10+10+10 → 9+9+9 | LEGACY_REMOVED |
| 2026-09-11 | S2-A2 | `core.sleep` | 9+9+9 → 8+8+8 | LEGACY_REMOVED |
| 2026-09-11 | S2-A3 | `core.file.writeFile` | 8+8+8 → 7+7+7 | LEGACY_REMOVED |
| 2026-09-12 | S2-A5 | `core.isUnix` | 7+7+7 → 6+6+6 | LEGACY_REMOVED |
| 2026-09-12 | S2-A6 | `core.pwd` | 6+6+6 → 5+5+5 | LEGACY_REMOVED (note: STEP itself is STOPPED_G7, but the legacy forms are physically gone — see FASE 3 matrix) |
| 2026-09-12 | S2-A7 | `core.deleteDir` | 5+5+5 → 4+4+4 | LEGACY_REMOVED |
| 2026-09-13 | S2-A9 | `core.milestone` | 4+4+4 → 3+3+3 | LEGACY_REMOVED |
| 2026-09-13 | S2-A10 | `core.cleanWs` | 3+3+3 → 2+2+2 | LEGACY_REMOVED |
| 2026-09-13 | S2-B10 | `core.archiveArtifacts` | 2+2+2 → 1+1/1 | LEGACY_REMOVED (counter: 2+2+2 → 2+2+2 because the `core.waitUntil` WU-G5R.3 had already removed its factory entry; only `core.load` and `core.waitUntil` legacy forms remained in LEGACY_PLUGIN_IDS at S2-B10 close) |
| 2026-09-17 | WU-G5B | `core.waitUntil` | 2+2+2 → 1+1+1 | LEGACY_REMOVED (canonical RepeatUntil machinery; no registry handler — `CoreWaitUntilStep.kt` retained only as typed codec source for `WaitUntilPolled`/`WaitUntilCompleted` events) |
| **2026-09-17** | **CORE-LOAD-REJECTED** | **`core.load`** | **1+1+1 → 0+0+0** | **REJECTED** — first ZERO LEGACY RESIDUAL |

## Final state (2026-09-17, post-CORE-LOAD-REJECTED)

```text
LEGACY_PLUGIN_IDS                              = {}       (FIRST ZERO LEGACY RESIDUAL)
CanonicalCoreStepMetadata.pluginIds            = {}
Canonical*NodeDispatcher.kt files in main      = 0
CanonicalCoreStepCommand sealed subtypes       = 0
StepSpec.Load subtype                          = (removed)
fun load(path: String) DSL façade              = (removed)
core.waitUntil branch in CanonicalNodeDispatcher = (removed)
core.load branch in CanonicalNodeDispatcher    = (removed)
core.waitUntil branch in BlockStepFlattener    = (removed)
core.load branch in BlockStepFlattener         = (removed)
Main.kt bridge error string for load           = (removed)
```

## Counter semantics (per the project dashboard)

```text
Certified Steps:            N
Legacy executable Steps:    M     (where N + M = |LEGACY_PLUGIN_IDS| + external plugin count)
Registry-primary Steps:     N

N + M = total
Convergence means M → 0
```

Post-CORE-LOAD-REJECTED: **M = 0** (legacy executable Steps). Every Step in
the registry is registry-primary (CERTIFIED, ORCHESTRATION, or REJECTED).

## Rejected vs LEGACY_REMOVED (terminology distinction)

| Path | When | Outcome |
|---|---|---|
| `LEGACY_REMOVED` | Step migrated to registry; legacy forms deleted | `CERTIFIED + LEGACY_REMOVED` |
| `REJECTED` | Step impossible to migrate under current architecture law; legacy forms deleted; no replacement | `REJECTED` (no CERTIFIED transition) |

`core.load` is the first REJECTED entry. The directive forbids the only
viable implementation shape ("handler → compiler arbitrario → execute
child pipeline como segundo execution engine"), and SPIKE-018 §1.3
declares it the LAST legacy lift requiring new infrastructure out of LFC-2
scope. See `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` for the
full reasoning.

## Future-shape cost (post-LFC-2E0)

With M = 0, the "3 edits, 2 files" cost (variant + decoder when +
dispatcher when) for adding a new legacy variant is no longer the
relevant cost. Adding a new Step now requires the full registry burn-down
G0..G8 (or a REJECTED justification), per the `STEP_CONSTITUTION` and
ADR-0070..0074. See `STEP_INVENTORY_LFC2E0.md` "Note on EC-9" for the
formal shift.

## See also

- `docs/v2/status/step-certification.yaml` — current per-Step certification matrix
- `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` — human-readable rollup
- `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` — `core.load` REJECTED
- `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md` — `core.waitUntil` final closure
