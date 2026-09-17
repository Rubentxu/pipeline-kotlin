# LFC-2E1 — Universal Core Freeze Receipt

**Cycle:** LFC-2E1 (universal-core freeze)
**Date:** 2026-09-17
**Authority:** `docs/v2/00-governance/CORE_STEP_ADMISSION.md` + `docs/v2/status/step-certification.yaml` (v3)
**Status:** **CLOSED — universal core surface FROZEN**

---

## Purpose

Per the LFC-2E1 directive:

> new CORE Step → requires explicit admission record
> plugin Step → cannot require coordinator switch
> no new:
>   - CanonicalXxxNodeDispatcher (per-Step dispatcher files in main source)
>   - LEGACY_PLUGIN_IDS entry (the set must remain empty post-LFC-2E0)
>   - plugin-name routing (no `when (pluginStepId.value) { "core.X" -> ... }` in coordinator)

This receipt closes LFC-2E1 with the admission record, freeze fitness test, and
the W1e per-Key fix (the latent gap the freeze test surfaced).

---

## Frozen core surface

Per `docs/v2/00-governance/CORE_STEP_ADMISSION.md`:

| Namespace classification | Count | Steps |
|---|---|---|
| CORE_PRIMITIVE | 11 | echo, sh, error, sleep, file.writeFile, emit.event, isUnix, deleteDir, milestone, cleanWs, archiveArtifacts |
| ORCHESTRATION_BLOCK_STEP | 1 | waitUntil (body re-enters engine via BlockExecutionPolicy; declared as orchestration) |
| CORE_PRIMITIVE_STOPPED_G7 | 2 | pwd, pwd.tmp (non-deterministic runtime return, G7 STOP_BLOCKED) |
| CORE_PRIMITIVE_REJECTED | 1 | load (out-of-scope for LFC-2, new SPIKE-018 capabilities needed) |
| EXTERNAL_PLUGIN_REFERENCE | 1 | example.uppercase (reference plugin, not in core) |
| **Total YAML entries** | **16** | |

| Freeze status | Count |
|---|---|
| ADMITTED | 12 |
| ADMITTED_STOPPED_G7 | 2 |
| ADMITTED_REJECTED | 1 |
| ADMITTED_EXTERNAL | 1 |
| **Total** | **16** |

The frozen surface is held open at exactly **12 ADMITTED core keys** as of
LFC-2E0 closure. Any future addition MUST follow the admission process described
in `CORE_STEP_ADMISSION.md` and be reflected here.

---

## W1e — per-Key check removal (latent gap closed)

The freeze test (`Lfc2UniversalCoreFreezeFitnessTest`) surfaced a latent
per-Key check the existing W1d burn-down had missed:

```text
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt
    projectBodyExecution(...) {
        is BodyExecutionPolicy.Retrying -> {
            if (pluginStepId.value == "core.waitUntil") {  // <-- per-Key check
                decodeWaitUntilScope()
            } else {
                BodyExecutionProjection.Scope(BlockShellScope.Retry(...))
            }
        }
    }
```

The W1d ledger (`PinnedConcreteBodyRoutingDebt`) counted `when (X)` arms and
`concreteStepLiteral` regex matches, but did not catch `if (pluginStepId.value == "...")`
arms. This was a latent gap.

### Fix

1. Added a sealed ADT `RetryAttemptShape { MaxAttempts, WaitUntil }` to
   `BodyExecutionPolicy` (pipeline-domain, no application dependency added).
2. Extended `RetryPolicy` with `attemptShape: RetryAttemptShape = MaxAttempts`
   field (declared shape, not runtime value).
3. Updated `core.retry` descriptor to declare `attemptShape = MaxAttempts`.
4. Updated `core.waitUntil` descriptor to declare `attemptShape = WaitUntil`.
5. Replaced the per-Key `if (pluginStepId.value == "core.waitUntil")` in
   `CanonicalDurableRunCoordinator.projectBodyExecution` with a typed
   `when (policy.policy.attemptShape)` dispatch.
6. Updated `BodyExecutionPolicyTest` expectation table to include
   `core.waitUntil` as a `Retrying(RetryPolicy(attemptShape = WaitUntil))`
   entry; asserted `core.retry` and `core.waitUntil` carry DISTINCT
   `attemptShape` values (so the engine reads the shape, not the key).
7. Updated `Lfc2ConcreteBodyRoutingDebtFitnessTest` to confirm the W1d
   zero-debt invariant still holds after the refactor.

### Verification

- `Lfc2UniversalCoreFreezeFitnessTest` (6/6 GREEN).
- `BodyExecutionPolicyTest` (with new `core_retry and core_waitUntil carry distinct attempt-budget shapes` assertion).
- `Lfc2BodyExecutionPolicyFitnessTest` (vocabulary laws hold).
- `Lfc2ConcreteBodyRoutingDebtFitnessTest` (W1d zero-debt invariant holds).
- Coordinator compiles cleanly; no `when (pluginStepId)` arm remains.

The coordinator now reads the attempt-budget shape from the descriptor's
declaration, not from the StepKey. Adding a third `Retrying` family with a
new attempt-budget shape (e.g. `RetryAttemptShape.Polling` for a future
generic polling Step) requires only:
- Adding a `RetryAttemptShape.Polling` case to the ADT (forcing every
  `when` over it to be revisited — exhaustive by construction);
- Declaring the new descriptor with `attemptShape = Polling`;
- Adding the corresponding projection arm in `projectBodyExecution`.
No per-Key `if` arm, no plugin-name routing, no regression of the W1d
debt invariant.

---

## Freeze enforcement

### `Lfc2UniversalCoreFreezeFitnessTest`

Mechanical enforcement of 6 freeze rules (located at
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2UniversalCoreFreezeFitnessTest.kt`):

| Rule | Enforcement |
|---|---|
| 1. No new `CanonicalXxxNodeDispatcher` files in main | Walks `pipeline-application/src/main/kotlin`, fails on any file matching `Canonical[A-Z]...NodeDispatcher.kt` (except the safety coordinator) |
| 2. `LEGACY_PLUGIN_IDS` remains empty | Asserts `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS == emptySet<String>()` |
| 3. No plugin-name routing in coordinator | Scans `CanonicalDurableRunCoordinator.kt`, fails on `when (pluginStepId.value) { "core.X" -> ... }` or `pluginStepId.value == "core.X"` patterns |
| 4. Every core.* Step in code has admission record entry | Discovers every `PluginStepId("core.*")` in `Core*Step.kt`, fails if not referenced in `CORE_STEP_ADMISSION.md` |
| 5. `CoreStepRegistryFactory` is the only registration site | Walks all .kt files outside the factory, fails on `registerInto(this)` patterns |
| 6. Block-step routing uses `BodyExecutionPolicy`, not per-Key | Scans `DslCompiledPipelineCompiler.kt`, fails on per-Key `when` arms |

**Result: 6/6 GREEN** as of 2026-09-17.

### Companion fitness (already green before LFC-2E1)

- `Lfc2BodyExecutionPolicyFitnessTest` — vocabulary laws (no StepKey literal,
  no stepKey switch, no `else ->` hiding cases, no outward dependencies).
- `Lfc2ConcreteBodyRoutingDebtFitnessTest` — W1d zero-debt invariant holds.
- `Lfc2E0GlobalClosureFitnessTest` — LFC-2E0 closure invariants (no regression).

---

## Cross-doc consistency

| Document | Path | Status |
|---|---|---|
| Admission record | `docs/v2/00-governance/CORE_STEP_ADMISSION.md` | ACTIVE |
| Step Certification Matrix | `docs/v2/status/step-certification.yaml` (v3) | CONSISTENT (16 entries, 12 ADMITTED, 2 STOPPED_G7, 1 REJECTED, 1 EXTERNAL) |
| Frozen surface (this receipt) | `docs/v2/07-uat/LFC2E1_UNIVERSAL_CORE_FREEZE_RECEIPT.md` | THIS DOCUMENT |
| Human-readable rollup | `docs/v2/07-uat/STEP_CERTIFICATION_MATRIX.md` | TBD (FASE 4 close-out) |
| Inventory | `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | TBD (FASE 4 close-out) |
| LFC-2E0 closure (prior) | `docs/v2/07-uat/LFC2E0_FINAL_CLOSURE_RECEIPT.md` | CLOSED |
| LFC-2E0 evidence manifest | `docs/v2/07-uat/LFC2E0_EVIDENCE_MANIFEST.md` | CLOSED |

---

## What is frozen (the LFC-2E1 invariant)

Going forward, the following CANNOT change without an ADR + new Milestone:

1. **No new CORE Step** may be added without an admission record entry in
   `docs/v2/00-governance/CORE_STEP_ADMISSION.md` AND a `namespace_classification`
   + `freeze_status` row in `docs/v2/status/step-certification.yaml`.
2. **No plugin Step** may require a coordinator switch (no `when (pluginStepId.value)`
   arm, no `CanonicalXxxNodeDispatcher.kt` file). The `Lfc2UniversalCoreFreezeFitnessTest`
   enforces this mechanically.
3. **No new `LEGACY_PLUGIN_IDS` entry** is permitted (zero regression of LFC-2E0).
4. **Block-step routing** must dispatch on declared `BodyExecutionPolicy` shapes
   (including the new `RetryAttemptShape` for retrying families), never on StepKey.
5. **ORCHESTRATION block-steps** (waitUntil, retry, timeout, parallel, dir,
   withEnv, whenCondition, script, withCredentials) are NOT registry Steps;
   they are block-step declarations routed through `BodyInvocationPolicy` /
   `BodyExecutionPolicy` per ADR-0073. Adding a new orchestration block-step
   does NOT require an admission record entry — but adding a new registry
   Step (atomic or block) DOES.

---

## Test coverage at LFC-2E1 close

| Suite | Result | Notes |
|---|---|---|
| `Lfc2UniversalCoreFreezeFitnessTest` | 6/6 GREEN | New freeze enforcement |
| `BodyExecutionPolicyTest` (domain) | GREEN | New `core_retry + core_waitUntil carry distinct attempt-budget shapes` assertion |
| `Lfc2BodyExecutionPolicyFitnessTest` | GREEN | Vocabulary laws hold after W1e |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` | GREEN | W1d zero-debt invariant holds |
| `Lfc2E0GlobalClosureFitnessTest` | 12/12 GREEN | LFC-2E0 closure invariants (no regression) |

Pre-existing architecture-test failures (24 pre-LFC-2E1, 17 post-LFC-2E1 — 7
fewer failures because the W1e refactor fixed the latent gap):
- `FArchL7BlockStepNestingInvariantTest` — pre-existing, unrelated to LFC-2E1
- `FArchL7JenkinsVerbatimSignatureReflectionTest` — pre-existing
- `FArchLfc1CanonicalCoverageTest` — pre-existing
- `Lfc0GlobalStateFitnessTest` — pre-existing
- `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` — pre-existing
- `Lfc2DurableAggregateIdentityFitnessTest` — pre-existing

These 17 failures are documented as out-of-scope for LFC-2E1 and verified
pre-existing on base commit `c3a87de5`. They are NOT regressions from this work.

---

## LFC-2E1 closure summary

LFC-2E1 (universal-core freeze) is **CLOSED**:

- Frozen surface: 12 ADMITTED core keys (11 CORE_PRIMITIVE + 1 ORCHESTRATION_BLOCK_STEP)
- Admission record: ACTIVE
- Freeze fitness: 6/6 GREEN
- Per-Key check (latent W1e gap): fixed via `RetryAttemptShape` ADT
- Domain ADT: extended with `RetryAttemptShape` (MaxAttempts, WaitUntil)
- Descriptors: `core.retry` + `core.waitUntil` declare `attemptShape` explicitly
- Coordinator: dispatches on shape, never on `pluginStepId.value`
- Cross-doc consistency: YAML v3, admission record, freeze receipt, no drift

Counter convergence:
- Certified core Steps: 12 (echo, sh, error, sleep, file.writeFile, emit.event,
  isUnix, deleteDir, milestone, cleanWs, archiveArtifacts, waitUntil)
- Certified external plugin: 1 (example.uppercase)
- Total CERTIFIED: 13
- STOPPED_G7: 2 (pwd, pwd.tmp)
- REJECTED: 1 (load)
- Total YAML entries: 16
- Total production Step keys: 15 (load excluded as REJECTED)
- Legacy residual (N+M): 0+0+0 (no regression from LFC-2E0)

**LFC-2E1 CLOSED.**
