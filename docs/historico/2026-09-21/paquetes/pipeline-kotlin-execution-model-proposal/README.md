# Pipeline Kotlin V2 — Durable Kotlin Execution Model Proposal

**Status:** absorbed historical provenance record (not authoritative)  
**Target repository:** `Rubentxu/pipeline-kotlin`  
**Target line:** V2 local-first  
**Date:** 2026-09-05  
**Primary architectural decision:** `ADR-0065`

This package is the historical proposal that was absorbed into the V2 execution
model line. It is retained for provenance and review traceability only. The
current authoritative EM line is `docs/v2/`, as recorded in
[`docs/v2/00-context/EXECUTION_MODEL_PROPOSAL_DISPOSITION.md`](../v2/00-context/EXECUTION_MODEL_PROPOSAL_DISPOSITION.md)
and governed by `docs/v2/00-governance/DOCUMENT_AUTHORITY.md`.

Its original intent was to correct the V2 execution model before continuing to
patch `INC-039` / `INC-040` in isolation.

The proposal keeps the valuable local durable-task substrate already established by
ADR-0046 — script file, atomic result, log file, heartbeat/cookie and reattachment —
but changes the semantic boundary above it:

- the declarative DSL remains statically discoverable;
- `script {}` becomes executable Kotlin rather than a shell-text accumulator;
- step calls become real runtime invocations with return values or typed exceptions;
- durability is provided by deterministic replay + an operation journal, not by
  serializing Kotlin continuations;
- `sh` follows Jenkins observable behavior, including `returnStatus` and
  `returnStdout`;
- block-scoped steps (`timeout`, `retry`, `catchError`, `withEnv`,
  `withCredentials`, `dir`, etc.) become first-class execution nodes/scopes;
- `StepFailed` is owned by one execution boundary, not individual step executors;
- durable task states are separated from terminal task results;
- persisted failures use a serializable `FailureRecord`; an in-process `Throwable`
  is diagnostic context, not the durable/wire contract.

## Package contents

| File | Purpose |
|---|---|
| `docs/v2/00-context/EXECUTION_MODEL_INTEGRATION.md` | Merge/integration order, conflicts and affected files |
| `docs/v2/04-adrs/ADR-0065-durable-kotlin-execution-semantics.md` | Foundational architecture decision |
| `docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md` | Runtime + deterministic replay specification |
| `docs/v2/03-specifications/JENKINS_SH_CONTRACT.md` | Jenkins-compatible `sh` surface and semantics |
| `docs/v2/03-specifications/FAILURE_INTERRUPTION_MODEL.md` | Failure, exception and cancellation model |
| `docs/v2/03-specifications/BLOCK_STEP_EXECUTION.md` | First-class block/body step model |
| `docs/v2/05-roadmap/EXECUTION_MODEL_MIGRATION.md` | Incremental implementation roadmap and gates |
| `docs/v2/07-uat/UAT_JENKINS_EXECUTION_PARITY.md` | Differential Jenkins parity UAT suite |
| `docs/v2/00-context/EXECUTION_MODEL_TRACEABILITY_DELTA.md` | Existing documents/ADRs affected and traceability |
| `docs/v2/08-spikes/SPIKE-016-DURABLE-SCRIPTED-REPLAY.md` | Mandatory prototype before the broad refactor |
| `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md` | Primary-source reference baseline |

## Historical integration policy

The following was the original integration sequence. Its current status and
remaining gates are maintained only under `docs/v2/`:

1. Review/accept ADR-0065 and its explicit supersedence/amendment list.
2. Add the specs, UAT catalogue and roadmap.
3. Run SPIKE-016 without production-path replacement.
4. Only after the spike gate passes, implement `EM-1` onward progressively.
5. Preserve fresh test evidence for every gate.
6. Delete legacy paths only after parity UAT proves the replacement.

This proposal intentionally permits changes to `:pipeline-step-sdk` where the current
contract makes durable failure provenance impossible to preserve. That is a deliberate
change from the earlier INC-039 constraint, not an accidental scope expansion.
