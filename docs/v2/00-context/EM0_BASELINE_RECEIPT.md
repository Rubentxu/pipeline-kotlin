# EM-0 Baseline Receipt

> **Cycle:** `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` (requirement R6)
> **Date:** 2026-09-06 · **Baseline commit:** `0ad4be3` (main) + uncommitted EM
> working tree (55 files, enumerated in the cycle exploration report)

## Fresh full-gate run (the baseline, not memory)

- **Command:** `timeout 1270 ./gradlew -p v2 check` (derived budget: last
  escalated baseline 977 s × 1.3, per testing policy rule 4)
- **Outcome:** `BUILD FAILED in 17m 38s` (under budget); 105 FAILED test
  lines; 95 actionable tasks (45 executed, 50 up-to-date)
- **Log:** `/tmp/opencode/em0-baseline-gate.log` (session-local; failures
  inventoried below)

## Failure inventory and classification

| Suite | Failures | Classification |
|---|---|---|
| `ScriptTextEscaperTest` | 3 | **Known RED on base `0ad4be3`** (worktree base-vs-head evidence, 2026-09-06): comment `$`-escaping drift |
| `WithCredentialsCompileIntegrationTest` | 4 | **Known RED on base**: part of F-1..F-8 withCredentials failing-UAT baseline (UAT008 19 PASS / 8 FAIL) |
| `Spike016DurableScriptedReplayTest` | 6 | **Concurrent-edit artifact**: the gate compiled the spike file mid-extension. Superseded by fresh isolated run 2026-09-06T10:12:17Z — **24/24 PASS** (XML SHA-256 `79a245f3…76fdd9`) |
| `UatLocal008CredentialsTest` (12), `UatLocal011WorkflowControlTest` (12), `UatLocal009TopStepsTest` (7), `UatLocal007SandboxProfileTest` (6), `UatLocal012ErrorHandlingTest` (5), `UatLocal005EnvSpecialCharsTest` (5), `UatDsl005TimeoutGrammarTest` (4), `UatDsl003ParallelTest` (4), `UatDsl001JenkinsFamiliarityTest` (4), `UatLocal013MilestoneTimingTest` (4), `CompatibilityCorpusTest` (5), and single-failure suites | ~85 | **Origin UNCLASSIFIED** — plausibly a mix of (a) the documented F-1..F-8 withCredentials line and (b) regressions introduced by the uncommitted EM-2/EM-3 dispatcher/typed-shell rewrites (timeout-grammar and milestone-timing failures are consistent with EM-3 being incomplete). Per testing rule 16, base-vs-head classification is REQUIRED in the verify phase of this cycle before any fix or release claim |

## Fresh green evidence captured this cycle (2026-09-06, XML canary-verified)

| Suite | Result |
|---|---|
| `Spike016DurableScriptedReplayTest` (widened E1..E7 + E2a..E5c + N1..N6) | 24/24 |
| `DurableTaskTerminalContractTest` | 1/1 |
| `ShellInvocationResultTest` | 2/2 |
| `DurableShellTerminalAdapterTest` | 7/7 |
| `StepExecutionBoundaryTest`, `CanonicalDurableRunCoordinatorTest` (12), `CanonicalShellNodeDispatcherTest` (8), `CanonicalErrorNodeDispatcherTest` (1), `DurableShellCommandTest` (1) | green |
| `ScriptedScopeTest` | 13/13 |
| `CanonicalCoreStepDecoderTest` | 8/8 |
| `KotlinScriptedSourceMapperTest` (after script-parse fix) | 1/1 |
| `CompiledScriptedEntryPointHostTest` | 1/1 |
| L0 compile, 5 affected modules | green |

## Constraints recorded

- No test was weakened, skipped, or ignored to reach any green above.
- The unclassified ~85 failures MUST NOT be released as "pre-existing"
  without the verify-phase base-vs-head reconciliation.
- MANIFEST regeneration is executed once, at verify close of this cycle.
