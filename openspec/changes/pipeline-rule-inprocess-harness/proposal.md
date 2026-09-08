# Proposal: In-process PipelineRule test harness (JenkinsRule-style)

## Intent
Pre-existing `:pipeline-application` UATs fail on real product gaps (compiler
`error`-in-workflow projection, parallel+siblings, coordinator step-name drift),
but every semantic experiment runs an installed binary / `java -cp` subprocess
with up to 300s timeouts and filesystem/git/sandbox coupling. We need a fast,
deterministic, in-process way to run a DSL script through the canonical
coordinator and observe the typed event timeline — the same role `JenkinsRule`
plays for Jenkins. SPIKE-017 captures the hypothesis and exit criterion.

## Scope
### In Scope
- `PipelineRule.run(script) -> (RunOutcome, List<DomainEvent>)` compiling DSL
  in-process (`Kotlin24ScriptingHost` + `DslCompiledPipelineCompiler`) and running
  `CanonicalDurableRunCoordinator` with in-memory journal + event store + real
  short `DurableShellExecutor`.
- Port one representative failing suite (start `ErrorHandlingTest` ERR-S-001 or a
  DSL fixture) to prove subprocess-parity and wall-time reduction.
- Shared test-support seam (no new runtime/process adapters).
### Out of Scope
- Fixing G1/G2/G3 product gaps (tracked in UAT_GATE_GAPS_DIAGNOSIS, separate change).
- Replacing durable real-process UATs for replay/kill semantics.

## Capabilities
### New Capabilities
- `pipeline-test-rule`: in-process DSL compile + canonical coordinator run returning
  typed `RunOutcome` + `DomainEvent` timeline, plus parity/time assertions.
### Modified Capabilities
- None.

## Approach
Reuse the CLI in-memory path already in Main.kt (Kotlin24ScriptingHost →
`PipelineSpec` → `DslCompiledPipelineCompiler.compile` → analyzeCanonical → in-memory
coordinator) but return `(outcome, events)` instead of printing. Expose as a test
helper in a shared test-support source set; existing `DslCompiledPipelineCompilerTest`
and `CanonicalDurableRunCoordinatorTest` prove the pieces compose.

## Affected Areas
| Area | Impact | Description |
|------|--------|-------------|
| `v2/pipeline-application/src/test/.../PipelineRule.kt` | New | In-process harness helper |
| ported failing suite | Modified | Run via `PipelineRule` instead of subprocess |
| SPIKE-017 / TEST_STRATEGY | Modified | Record parity + wall-time evidence |

## Risks
| Risk | Likelihood | Mitigation |
|------|------------|------------|
| In-process semantics diverge from real subprocess (durable/journal) | Med | Parity proof: same typed events as binary run; keep durable UATs for replay |
| Script compile first-run slow (Kotlin scripting host) | Med | Warm daemon + measure; still << 300s |

## Rollback Plan
Pure additive test-support code + one ported suite; revert by deleting the helper
and restoring the suite's subprocess path. No production/runtime change.

## Dependencies
- SPIKE-017 (authorized).
- Existing in-memory coordinator + scripting-host test infra.

## Success Criteria
- [ ] A failing case taking >=300s (subprocess) reproduces in <5s in-process with the
      SAME typed events/outcome (parity proven).
- [ ] G1/G2/G3 divergences recorded with minimal repro + owner; no silent behavior change.
