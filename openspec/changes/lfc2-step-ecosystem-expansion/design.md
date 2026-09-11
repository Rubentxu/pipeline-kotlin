# Design: lfc2-step-ecosystem-expansion

## Law

`STEP_ECOSYSTEM_MATRIX.md` is a planning hypothesis. Until corrected against
the actual production code, it cannot drive the local-first Step ecosystem
program. LFC-2E0 produces the **machine-derived** inventory that anchors
every subsequent cycle (E1..E10).

```text
production code
   ↓
LFC-2E0 inventory (machine-derived, every row file:line cited)
   ↓
corrected STEP_ECOSYSTEM_MATRIX.md (planning view, anchored to inventory)
   ↓
LFC-2E1..E10 sequencing
```

## Inventory authority

The LFC-2E0 inventory file (`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`) is
authoritative for:

1. **what counts as a Step key** — any `PluginStepId(...)` literal in production;
2. **delivery classification** — CORE / OFFICIAL_PLUGIN / EXTERNAL_REFERENCE /
   DEFERRED_REMOTE / REJECTED_JENKINS_INTERNAL (per user law #6);
3. **certification state** — `CERTIFIED` / `LEGACY_IMPLEMENTED_UNCERTIFIED` /
   `IMPLEMENTED_UNCERTIFIED` (block / orchestration DSL) / `NOT_STARTED` /
   `DEFERRED` / `REJECTED`.

Every row must cite file:line.

## What the inventory exposes

The LFC-2E0 cycle made three discoveries that the original matrix did not:

1. **Production registry has only 2 core keys** (`core.echo`, `core.sh`).
   The matrix's broader `CORE / CERTIFIED` claims for legacy Steps are inaccurate.
2. **12 core keys still routed through legacy** `Canonical*NodeDispatcher`.
   They have a DSL façade but no `StepDefinition<I,O>` form, no contract suite,
   no capability declaration, no replay policy.
3. **The "block / orchestration DSL" (retry, timeout, parallel, …) is not a Step
   key.** It re-enters the engine through `BodyInvoker.invoke` /
   `BranchInvoker.invokeAll` per ADR-0073. The matrix conflated these with Steps.

## Why no production code change in E0

Per user law #10 and #11: LFC-2E0 is **inventory only**. No Step implementation.

Per LB-01 / LEGACY_BURNDOWN_POLICY (the per-Step state machine
LEGACY → DUAL_AVAILABLE → REGISTRY_PRIMARY → LEGACY_UNREACHABLE →
LEGACY_REMOVED → CERTIFIED; **CERTIFIED requires LEGACY_REMOVED**), the next
cycle LFC-2E1 must:

1. **E1-S1 (FIRST):** complete the LB-02 LEGACY_REMOVED slice — refixture any
   reachable legacy Echo machinery (LegacyEchoUnreachableProofTest,
   EchoDurableSpineTest, UatStep002EchoCaptureTest, CoreEchoSeamTest,
   EchoStepContractSuiteTest) to drive `core.echo` exclusively through the
   registry seam; activate `S3EchoLegacyRemovedFitnessTest`; record
   `LEGACY_REMOVED` in the LB-01 ledger (do NOT re-record CERTIFIED).
2. **E1-S2:** burn down the 12 legacy keys onto the registry seam in priority
   order (P0 first: error, sleep, pwd, isUnix; then P1: deleteDir, cleanWs,
   waitUntil; then P2: milestone, load, archiveArtifacts, emit.event,
   file.writeFile). Each burn-down follows G0..G8.

## Sequencing rules preserved

- EVT-4 stays PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout E0..E10.
- M4 (controller/remote) stays DEFERRED until local-first feature freeze.
- The core/plugin classification law (user #6) and the architecture gate (user #7)
  apply to every subsequent cycle.

## Sources (verified, 2026-09-11)

- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt` — `LEGACY_PLUGIN_IDS`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` — registry composition
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEchoStep.kt` — `core.echo`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt` — `core.sh`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/Canonical*NodeDispatcher.kt` — 12 legacy dispatchers
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt` L990..1900 — DSL façades
- `examples/example-uppercase-plugin/src/main/resources/META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` — ServiceLoader
- `examples/*.pipeline.kts` — 10 real examples (01..10)
- `examples/contracts/*.events.yaml` — 4 Event Harness contracts (07..10)
- `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md`, `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md`, `LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` — certification receipts

## Architecture fitness for LFC-2E0

- LFC-2E0 is docs-only; no production code change.
- The inventory file cites production code by file:line; no fabricated rows.
- `STEP_ECOSYSTEM_MATRIX.md` is corrected against the inventory (not the other way around).
- No new `when(stepName)` cases; no `CanonicalDurableRunCoordinator` step-specific edits.
- Working tree clean before commit.
- Internal links resolve.
