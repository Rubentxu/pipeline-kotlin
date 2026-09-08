---
type: spike
id: SPIKE-017
title: "Prove an in-process PipelineRule harness (JenkinsRule-style) for fast, deterministic DSL-semantics UATs"
status: proposed
date: 2026-09-08
related:
  - docs/v2/06-quality/TEST_STRATEGY.md
  - docs/v2/05-roadmap/EXECUTION_MODEL_MIGRATION.md
  - docs/v2/07-uat/UAT_JENKINS_EXECUTION_PARITY.md
---

# SPIKE-017 — PipelineRule in-process harness

## Problem (evidence, 2026-09-08)

The `:pipeline-application` real-process UATs fail in this sandbox, but the root
cause is NOT slowness or environment. Executing the installed binary on each
fixture shows **real, pre-existing product gaps** surfaced as fail-closed
`IllegalStateException`s or semantic divergence:

- `grammar-full` / `timeout-retry`: `buildShellScript cannot embed structured step
  'error' into a workflow-control shell wrapper ... Refusing to emit a silent shell
  comment` (DslCompiledPipelineCompiler.kt:506) — `error` nested in `timeout`/`retry`
  is not projected.
- `parallel`: `Stage 'ParallelTest' cannot mix a parallel body with sibling steps`
  (DslCompiledPipelineCompiler.kt:106) — fixture may be invalid Jenkins declarative
  or the restriction is correct; needs triage.
- `ErrorHandlingTest` ERR-S-001..008 run 300s-timeout subprocesses and fail on a
  step-name divergence (`Steps: [... echo-0]` vs expected `echo`).

The UAT harness spawns an installed binary / `java -cp` per case (300s timeout,
filesystem + git + sandbox), so every semantic experiment is slow and
environment-sensitive. Jenkins solves exactly this with `JenkinsRule` (embedded
controller in-process, fast, deterministic).

## Hypothesis

A thin **`PipelineRule`** helper — compile a DSL script in-process with
`Kotlin24ScriptingHost` + `DslCompiledPipelineCompiler`, run the canonical
`CanonicalDurableRunCoordinator` against in-memory journal + event store + real
(short) `DurableShellExecutor`, and return the typed `RunOutcome` + `DomainEvent`
timeline — reproduces the same semantics in milliseconds with no installed binary,
no `java -cp`, no 300s timeout, and surfaces these product gaps deterministically.

## Why this spike is mandatory

The semantic gaps above are currently hidden inside slow subprocess UATs. A fast
deterministic harness is the prerequisite to fix and re-verify them (error
projection, step naming, catchError semantics) with short feedback loops. It also
de-flakes the suite by removing filesystem/git/sandbox coupling for pure DSL
semantics, matching the AGENTS "change-scoped, fast, evidence-driven" rule.

## Scope

- `PipelineRule.run(script: String): (RunOutcome, List<DomainEvent>)` using the
  existing in-memory coordinator path (mirror of Main.kt's no-`--db` branch).
- Port ONE representative failing suite (start `ErrorHandlingTest` ERR-S-001 or a
  DSL fixture) to prove parity + speed; capture before/after wall time and the
  exposed semantic divergences.
- Do NOT add new runtime/process adapters; only a shared test-support seam.

## Acceptance / exit criterion

- A failing semantic case that takes >=300s (subprocess) reproduces in <5s in-process
  with the SAME typed events/outcome it produced via the binary (parity proven).
- Divergence(s) (error projection / parallel / step-naming) are recorded with
  minimal repro + proposed owner/backlog item; no silent behavior change.

## Decision output

- accept: promote `PipelineRule` to a shared `test-support` module + adopt for the
  DSL-semantics UAT families.
- reject: document why the subprocess harness must stay for these cases.
