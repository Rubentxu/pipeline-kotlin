---
type: integration-plan
id: EXECUTION-MODEL-INTEGRATION
title: "Integration plan for the Durable Kotlin Execution Model reset"
status: proposed
date: 2026-09-05
related:
  - ADR-0046
  - ADR-0047
  - ADR-0064
  - ADR-0065
  - docs/v2/02-architecture/RUNTIME_MODEL.md
  - docs/v2/03-specifications/DSL_SPEC.md
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
---

# Execution Model Integration

## 1. Why this proposal exists

`INC-039` exposed a concrete defect in shell failure classification, but the defect is
downstream of a larger modeling mismatch:

1. the public DSL often constructs `StepSpec` objects instead of executing steps;
2. runtime values such as `sh(returnStdout=true)`, `pwd()`, `readFile()` and
   `fileExists()` therefore cannot participate naturally in Kotlin control flow;
3. `runShellCommandTyped` projects a durable task result through a lossy legacy
   `String` result;
4. `catchError`/`warnError` are rewritten into shell text rather than executed as
   real body-scoped control flow;
5. `LAUNCHING`/`RUNNING` are modeled in the same result algebra as terminal task
   outcomes, forcing impossible-state classification;
6. timeout semantics have leaked into `sh` even though Jenkins models timeout as a
   block-scoped interruption;
7. `DurableShellResult` does not preserve structured failure provenance, making the
   original INC-039 requirement "preserve cause" impossible without changing the SDK.

Continuing to patch each symptom would harden these constraints.

## 2. What is preserved

The proposal explicitly preserves the strongest local-runtime work:

- ADR-0046 script-file execution pattern;
- user script written verbatim to the filesystem;
- shebang handling and default shell execution;
- atomic exit-result file;
- durable log/output files;
- heartbeat/liveness evidence;
- process cookie;
- detach/reattach semantics;
- workspace isolation and credential redaction work;
- event journal and deterministic run identity;
- source/plugin digests;
- local-first product direction from ADR-0064;
- no dependency from V2 to the V1 compiler-plugin path.

These become lower-level infrastructure beneath a corrected step execution boundary.

## 3. What is intentionally replaced

The following are considered transitional paths, not compatibility constraints:

| Current path | Target |
|---|---|
| `StageScope.sh()` adds `StepSpec.Shell` and returns `Unit` | executable scripted façade + declarative descriptor |
| `ScriptScope` joins commands into shell text | `suspend ScriptedScope.() -> Unit` |
| `runShellCommandTyped()` calls legacy `runShellCommand(): String` | one typed durable execution result, legacy adapter projects afterward |
| `DurableShellResult.state` mixes running + terminal states | `DurableTaskSnapshot` + `DurableTaskTerminal` |
| LOST loses original failure provenance | serializable `FailureRecord` plus ephemeral cause |
| `TIMED_OUT` as a shell terminal classification | block cancellation/interruption owned by `timeout` |
| `catchError` rewrite into shell | real block/body execution |
| `retry()` mutates the previously added step | `retry { body }` |
| per-executor `StepFailed` emission | central `StepExecutionBoundary` |
| `SCHEMA` for engine impossible states | `ENGINE`/`INTERNAL` invariant failure |
| stringly retry conditions | typed retry conditions |

## 4. Affected production areas

Expected production paths to change over the migration:

- `v2/pipeline-scripting-api/.../PipelineDsl.kt`
- `v2/pipeline-application/.../DslCompiledPipelineCompiler.kt`
- `v2/pipeline-application/.../CanonicalCoreStepDecoder.kt`
- `v2/pipeline-application/.../durable/ShExecution.kt`
- `v2/pipeline-application/.../durable/CanonicalShellNodeDispatcher.kt`
- `v2/pipeline-application/.../durable/CanonicalDurableRunCoordinator.kt`
- `v2/pipeline-step-sdk/runtime/.../durable/DurableShellExecutor.kt`
- domain failure/outcome/event types
- canonical IR node/body representation
- replay/journal key generation
- DSL scripting host / compiled artifact loading

This is not a mandate to change all files in one PR. The roadmap requires vertical
slices and compatibility adapters.

## 5. Existing documentation that must be updated after ADR acceptance

The integration is incomplete until these documents agree:

- `docs/v2/01-product/JENKINS_FAMILIARITY.md`
- `docs/v2/02-architecture/RUNTIME_MODEL.md`
- `docs/v2/03-specifications/DSL_SPEC.md`
- `docs/v2/03-specifications/STEP_PLUGIN_SDK.md`
- `docs/v2/04-adrs/README.md`
- `docs/v2/05-roadmap/ROADMAP.md`
- `docs/v2/07-uat/UAT_SCENARIOS.md`
- `docs/v2/07-uat/UAT_ACCEPTANCE_MATRIX.md`
- `docs/v2/00-context/TRACEABILITY.md`
- `docs/v2/MANIFEST.md` after final file contents/hashes are stable.

Do not silently edit historical exit receipts. Add current authority and traceability.

## 6. Merge sequence

### Documentation PR

1. ADR-0065.
2. New specifications.
3. New UAT catalogue.
4. New migration roadmap.
5. Traceability delta.
6. SPIKE-016 definition.
7. Reference baseline.
8. Existing authority/index files updated only after ADR number/path is final.

### Spike PR

No production cut-over.

Must prove:

- a real `suspend` scripted function can call a runtime step and consume its result;
- completed operations replay without relaunch;
- a branch selected from `sh(returnStdout=true)` is identical after restart;
- changed source digest fails closed;
- operation identity is stable through loops and nested block scopes;
- no Kotlin continuation serialization is required.

### Implementation PRs

Each `EM-*` gate is independently releasable or rollbackable. No mega-PR.

## 7. INC-039 disposition

INC-039 is re-scoped from "map all `DurableShellState` values in
`runShellCommandTyped`" to the first executable slice of the new shell boundary.

Temporary emergency fix is permitted only if production is blocked, but it must:

- not establish LAUNCHING/RUNNING as valid terminal outputs;
- not synthesize a fake original Throwable;
- not add more semantic behavior to the legacy String API;
- remain explicitly removable by EM-1/EM-2.

The preferred path is to solve the underlying typed terminal-result contract first.

## 8. INC-040 disposition

INC-040 remains separate unless its implementation changes the same task/result
boundary. If it does, re-plan it against ADR-0065 rather than preserving an obsolete
type contract.

## 9. Rollback strategy

Every migration phase must retain a coarse feature flag or adapter boundary until its
UAT gate passes. Rollback means selecting the previous dispatcher/invoker, not
rewriting persisted history.

Persisted schema evolution must be additive until a compatibility migration has been
proven. A new runtime must either:

- read previous records;
- migrate them explicitly; or
- fail with a clear compatibility error.

Never reinterpret old journal data under new semantics.
