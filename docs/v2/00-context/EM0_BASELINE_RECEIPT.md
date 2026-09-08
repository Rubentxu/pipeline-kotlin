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
| `Spike016DurableScriptedReplayTest` (widened E1..E7 + E2a..E5c + N1..N6; harness stabilized 2026-09-06 with .done-barrier fix) | 24/24 — XML SHA-256 `675ee420613d8a1961e3d43fa707271375b788df83e7b49cf4a94a00188e4f25` (3 consecutive 24/24 runs confirmed) |
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

---

## Addendum 2026-09-08 — v0.33.1 base-vs-head classification (cycle corpus-closure)

> **Cycle:** `p-733fb505b5a6bd2d/corpus-closure`
> **Base SHA:** `202598e7` (v0.33.0)
> **Head SHA:** `f2e8dc6b` (v0.33.1)
> **Method:** Worktree at `202598e7` + scoped L1 runs on representative failing tests
> **Worktree:** `/tmp/v0.33.0-verify` (removed after classification)

### Per test class base-vs-head result (worktree-verified)

| Test class | v0.33.0 base (`202598e7`) | v0.33.1 head (`f2e8dc6b`) | Delta | Classification |
|---|---|---|---|---|
| `DomainEventRoundTripTest` | 1 fail | 0 | −1 | PRE-EXISTING (FIXED in v0.33.1 by `f2e8dc6b`) |
| `ScriptTextEscaperTest` | 3 fails | 3 | 0 | PRE-EXISTING |
| `WithCredentialsCompileIntegrationTest` | 4 fails | 4 | 0 | PRE-EXISTING |
| `UatLocal008CredentialsTest` | 12 fails | 15 | +3 | MIXED (12 PRE-EXISTING + 3 NEW regressions INC-022) |
| `ErrorHandlingTest` | 5 fails | 5 | 0 | PRE-EXISTING |
| `UatDsl001JenkinsFamiliarityTest` | 4 fails | 4 | 0 | PRE-EXISTING |
| `UatDsl003ParallelTest` | 4 fails | 4 | 0 | PRE-EXISTING |
| `UatDsl005TimeoutGrammarTest` | 5 fails | 5 | 0 | PRE-EXISTING |
| `UatEvt001ReplayTest` | 1 fail | 1 | 0 | PRE-EXISTING |
| `UatEvt002MultiStepReplayTest` | 1 fail | 1 | 0 | PRE-EXISTING |
| `UatLocal005CheckoutGitTest` | 1 fail | 1 | 0 | PRE-EXISTING |
| `UatLocal007SandboxProfileTest` | 2 fails | 1 | −1 | 1 PRE-EXISTING (SB-S-010) + 1 FIXED (SB-S-008) |
| `UatLocal009TopStepsTest` | 7 fails | 3 | −4 | 3 PRE-EXISTING (CR-U9-008/011/012) + 4 FIXED (CR-U9-005/006/007/010) |

### Totals

| Category | Count |
|---|---|
| v0.33.0 base failures (worktree-confirmed) | **50** |
| v0.33.1 head failures (after `f2e8dc6b`) | **48** |
| PRE-EXISTING baseline carried into v0.33.1 | **45** |
| FIXED by v0.33.1 corpus-closure | **5** (1 `DomainEventRoundTripTest` + 1 `UatLocal007` SB-S-008 + 4 `UatLocal009` CR-U9-005/006/007/010) |
| NEW regressions in v0.33.1 (per AGENTS.md rule 16) | **3** (INC-022: `UatLocal008` CR-BD-023/024/025 wipe-tests) |

### Honest accounting per AGENTS.md rule 16

The 45 PRE-EXISTING failures are confirmed by the worktree method: they fail
on `202598e7` with the SAME failure mode as on `f2e8dc6b`. They are
**carried baseline, not v0.33.1 regressions**.

The 3 NEW regressions are confirmed by the worktree method too: they PASS on
`202598e7` and FAIL on `f2e8dc6b`. They are NOT pre-existing. They are
documented in commit `e62eb0e4` and tracked in `INC-022`. The cycle author
made an explicit decision to ship v0.33.1 with these regressions documented
rather than blocking the release on the wipe-test fix.

### Specific new-regression failures (to be fixed in v0.33.2)

- `UatLocal008CredentialsTest.CR-BD-023` — ssh key wipe after block exit
- `UatLocal008CredentialsTest.CR-BD-024` — secret file wipe after block exit
- `UatLocal008CredentialsTest.CR-BD-025` — certificate keystore wipe after block exit

### Specific fixed-by-v0.33.1 (corpus-closure GAINS)

- `DomainEventRoundTripTest.sealed hierarchy contains 43 variants` (commit `f2e8dc6b`)
- `UatLocal007SandboxProfileTest.SB-S-008 parallel branches have isolated cwds`
- `UatLocal009TopStepsTest.CR-U9-005 withEnv PATH+ prepend order`
- `UatLocal009TopStepsTest.CR-U9-006 withEnv JAVA_HOME carry-forward`
- `UatLocal009TopStepsTest.CR-U9-007 withEnv nested writeFile sees override`
- `UatLocal009TopStepsTest.CR-U9-010 archiveArtifacts empty passes when allowEmptyArchive true`
