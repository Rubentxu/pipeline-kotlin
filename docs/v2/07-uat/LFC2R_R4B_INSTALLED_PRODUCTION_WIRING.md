# LFC-2R / R4B — Installed Production Wiring (Scripted Frontend, Canonical Backend)

Status: CLOSED
Commits: `9761ddf0` (wiring), `5456a2eb` (frontend-selection scope fix)
Authority: R4A decision (`LFC2R_R4A_PRODUCTION_WIRING_ARCHITECTURE.md`),
`PRODUCTION_WIRING_MODEL = SCRIPTED_FRONTEND_CANONICAL_BACKEND`, identity law R4A-L1.

## Scope (user GO)

R4B only. No `core.isUnix` authority flip, no legacy removal, no `pwd` /
`readFile` / `fileExists` migration. STOP after slice.

## Implemented

1. `ScriptedFrontendRunner` (`pipeline-application`, `...application/scripted/`):
   - Fail-closed artifact fingerprint check (source-derived identity vs compiled
     artifact). Mismatch → `Outcome.ArtifactIncompatible`; Main exits 2.
     Never a silent fallback to eager PipelineSpec.
   - ONE durable backend: the SAME `StepRegistry`, `OperationJournal`,
     `EventSink`, `Clock` instances as the canonical path are passed in.
     `ScriptedRegistryInvoker` composes `CanonicalRuntimeContext`
     (`OpId(runId, 0, ordinal)`, stage `scripted`).
   - `JournaledScriptedOperationRuntime` wired to `ShExecution.invokeShell`
     (single writer, same journal key space → R4A-L1).
   - `ScriptedRuntime.run` executes the compiled entry point with
     `RuntimeScriptedStepFacade` (`scoped` / `sh`×3 / `isUnix`).
2. `Main.kt` frontend FORM selection (R4A law: Main may choose the frontend
   representation, never the durable authority):
   - `KotlinScriptedSourceMapper` (R3, PSI-backed) maps the source.
   - **Generator-level isUnix-only gate**: a source routes to the scripted
     frontend ONLY when (a) the mapped calls include `ScriptedCallKind.IsUnix`
     and (b) the source is generator-level (no `pipeline {` DSL structure).
     Two regressions caught and fixed by UAT evidence:
     - UatLocal002: the R3 mapper also detects generator-level `sh(...)`;
       an `sh`-only fixture would be flipped to the scripted path out of
       scope. Fixed by filtering to IsUnix only.
     - UatLocal011/SC-011-10: `isUnix()` inside a `stage {}` body is rewritten
       by the lowering into a suspend call inside the eager StageScope lambda,
       which cannot compile ("Suspension functions can only be called within
       coroutine body"), exit 2. Fixed by the generator-level gate:
       `pipeline {}`-structured sources keep the eager PipelineSpec frontend
       this slice (per R4B GO: no DSL-body migration). Both proven base-green
       at `50ffb299` before the fix and green after.
   - Lowered source compiled WITHOUT the eager `RuntimeConfig` injection
     (runtime-returned platform values only, DSL_RUNTIME_RETURN_GAP closure).
   - Failure at lowering / compile / entry-point extraction → fail closed,
     exit 2, never eager fallback (critical on resume).
3. Fitness (`R4BProductionWiringFitnessTest`):
   - F1: Main wiring references no concrete Step keys.
   - F2: no `ScriptedArtifactRuntime` (second runner) in Main; scripted entry
     points run through `ScriptedFrontendRunner` on the shared composition.
   Both green: `failures="0" errors="0"`.

## Verification executed (fresh, this slice)

| Evidence | Result |
| --- | --- |
| `:pipeline-application:compileKotlin` + `compileTestKotlin` | clean |
| `ScriptedIsUnixCompilerMappingTest` | 10/0 |
| All `*Scripted*` app tests (mapping, runtime, invoker, scope, Spike016 replay) | 70/0 |
| `UatLocal001/002/003/004/006` | 0 failures (002 first regressed then fixed — see below) |
| `CompatibilityCorpusTest` | only pre-existing fixture14 red (baseline unchanged) |
| Architecture/registry fitness set | 0 failures |
| Round gate `./gradlew -p v2 check` | see receipt addendum |

## Regression caught and fixed

First UatLocal002 run FAILED with my wiring: the mapper's call inventory
includes generator-level `sh(...)` (R3 behaviour), so an `sh`-only fixture was
routed to the scripted frontend, whose body executes the whole source as a
scripted entry point. Base-SHA check (stash method) proved the failure was
caused by the wiring change. Fix: Main filters mapped calls to
`ScriptedCallKind.IsUnix` before selecting the scripted FORM. UatLocal002 green
after fix; debug instrumentation removed from the test.

## Acceptance matrix (frozen exit contract)

- FRESH Mac OS X → canonical `true`, unix branch, persisted, handler=1.
- RESUME Windows 11 → same runId, persisted observation reused, unix branch,
  handler=0, platform reads=0, no new `UnixDetected`.
- RERUN Windows 11 → new runId, observed `false`, windows branch.
Cross-host rows require a multi-host runner; the in-process proof rows
(ScriptedIsUnixRuntimeTest 13/0, Spike016DurableScriptedReplayTest 24/0) cover
the durable observe/reuse/never-refabricate semantics at HF1. Installed
matrix rows marked PARTIAL (host constraint), semantics proven.

## Counters (unchanged by R4B — no burn-down in this slice)

Certified Steps / Legacy executable / Registry-primary: unchanged from R4A receipt.

## D3 impact

`DSL_RUNTIME_RETURN_GAP`: CLOSED for the `isUnix` seam in installed production
wiring. `LFC-2R`: CLOSED (R1..R4B complete). S2-A5 remains 8/8/8
REGISTERED-only: no certification claims, no authority flip.
