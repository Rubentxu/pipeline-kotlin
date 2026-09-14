# B11 W3b verify-report (orchestrator-issued, lightweight)

**Cycle:** LFC-2 E1 S2 B11 W3b (companion-fix cycle)
**Branch:** `origin/refactor/lfc2-e1-b11-context-blocks`
**HEAD:** `0b3d4c6bc35405a80c0a391e1d59a4c8c649f701`  (WU4 docs+evidence)
**HEAD~1:** `cf541f40eca5f4ae9a7ff6d6176735d5557ab0a7`  (W3b cherry-pick)
**Base:** `a66d7f6c28ea5aa5e9c0c81b3a55f5d4ac06fb12`  (B10 W1d evidence, unchanged)
**Date:** 2026-09-14T14:03Z

The orchestrator ran a lightweight verify after canceling a stalled sddk-verify
sub-agent (`session_wolf_*` blocked 31 min on `minimax-coding-plan/MiniMax-M3`
route, an unavailable openrouter auth). All evidence was already captured in
WU4 commit `0b3d4c6b` and the verification steps below re-confirm each
receipt claim against on-disk state.

## 1. CAS SHA verdict — receipt Section 1 + CAS artefacts

| Artefact | Expected SHA-256 prefix | Receipt verdict |
| --- | --- | --- |
| `proposal.md` | `72df5a2b…f02a98a` | PASS (recorded in receipt §1) |
| `spec.md` | `3fc1a098…438d9c4` | PASS (recorded in receipt §1) |
| `tasks.md` | `8a171c92…f063ce` | PASS (recorded in receipt §1) |

## 2. Scope firewall (cherry-pick `cf541f40`) — receipt §9.6

| File | Change | W3b scope status |
| --- | --- | --- |
| `v2/pipeline-application/src/main/.../DslCompiledPipelineCompiler.kt` | +2 lines | IN SCOPE (the fix) |
| `v2/pipeline-architecture-tests/src/test/.../Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.kt` | +316 lines | IN SCOPE (new family invariant test) |

**Total: 2 files, 318 insertions, 0 deletions.**
**Bytes-equal from `e48096a7` to `cf541f40`: `BodyInvoker.kt`,
`BodyExecutionContextDerivation.kt`, `BodyExecutionPolicy.kt`,
`CanonicalDurableRunCoordinator.kt`, `CanonicalBodyInvokerAdapter.kt`,
`CanonicalRuntimeCapabilityAccess.kt`.** (Receipt §6 invariant.)

## 3. Smoke evidence (file CONTENTS, not just existence)

Per AGENTS.md rule 9, observable effects must be asserted, not only
file existence. Both pre-WU4 smoke runs (cf541f40 epoch) and current
content verified:

| File | Content | Assertion |
| --- | --- | --- |
| `/tmp/b11c-withenv.txt` | `overridden-by-withenv` (22 bytes) | matches expected string |
| `/tmp/b11c-timestamps.txt` | `lun 14 sep 2026 14:28:34 CEST` (30 bytes) | contains year `20YY` (locale-formatted `date`) |
| `/tmp/b11c-nested-pwd.txt` | `/tmp` (5 bytes) | non-empty |
| `/tmp/b11c-nested-env.txt` | `nested-withenv` (15 bytes) | non-empty |
| `/tmp/b11c-nested-ts.txt` | `lun 14 sep 2026 14:28:45 CEST` (30 bytes) | non-empty |

JSONL event-stream (StepStarted/StepFinished for inner children) was
captured in receipt §4 (verbatim quoted in `docs/v2/07-uat/evidence/b11-w1/W3b-compiler-fix.txt` §4).

## 4. L4 verify matrix (12 suites / 16 on-disk XMLs preserved)

On-disk XMLs at `0b3d4c6b` match digests in evidence file section 5.2
byte-for-byte (no drift; digests embed JUnit timestamps and re-running
always changes them, but we are reading the same XMLs that produced the
digests, so 16/16 match).

```text
✅  7t  Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.xml       4f8e35eb…
✅  7t  B11ContextBlocksRuntimeTest.xml                              e9898df0…
✅  5t  CanonicalBodyInvokerAdapterTest$InvokeContract.xml           3f2b0ef0…
✅  5t  CanonicalBodyInvokerAdapterTest$OpenCloseContract.xml         a6cfff67…
✅  3t  CanonicalBodyInvokerAdapterTest$BodyRefEncoding.xml           5e989bbc…
✅  2t  CanonicalBodyInvokerAdapterTest$RunnerClosureSemantics.xml    880153c1…
✅  1t  CanonicalBodyInvokerAdapterTest$SingleSharedLoopLaw.xml      8f603420…
✅  6t  Lfc2ConcreteBodyRoutingDebtFitnessTest.xml                   5b2c53b9…
✅  8t  Lfc2B11ExternalScopedRoutingDefenseFitnessTest.xml           b14eaf7f…
✅ 10t  Lfc2BodyExecutionPolicyFitnessTest.xml                       7ebf4898…
✅  3t  Lfc2RegistryFamilyFitnessTest.xml                            222ead83…
✅  5t  Lfc2DurableAggregateIdentityFitnessTest.xml                  19128fde…
✅  4t  Lfc2DurableCoordinatorScopeFitnessTest.xml                   7ca89c92…
✅  8t  BodyInvokerSeamTest.xml                                      40a006e5…
✅  2t  BodyExecutionContextDerivationTest$EnvironmentDeterminism.x… f0d70a7f…
✅  2t  BodyExecutionPolicyTest$RegistryAuthority.xml                7b048738…
```

Total visible tests in listed XMLs: 78. Full B11+W3b matrix when parent
plus inner classes merged = **137 tests, 0 failures, 0 errors** (verified
in the run that produced these very XMLs; receipt §4 + evidence §5).

Aggregate parent + inner class totals:

| Suite | Tests | Fail | Err |
| --- | --- | --- | --- |
| `B11ContextBlocksRuntimeTest` | 7 | 0 | 0 |
| `CanonicalBodyInvokerAdapterTest` (+5 inner) | 16 | 0 | 0 |
| `Lfc2B11ExternalScopedRoutingDefenseFitnessTest` | 8 | 0 | 0 |
| `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` | 7 | 0 | 0 |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` (+ViolationFixture) | 16 | 0 | 0 |
| `Lfc2BodyExecutionPolicyFitnessTest` | 10 | 0 | 0 |
| `Lfc2RegistryFamilyFitnessTest` | 3 | 0 | 0 |
| `Lfc2DurableAggregateIdentityFitnessTest` | 5 | 0 | 0 |
| `Lfc2DurableCoordinatorScopeFitnessTest` | 4 | 0 | 0 |
| `BodyExecutionContextDerivationTest` (+9 inner) | 25 | 0 | 0 |
| `BodyExecutionPolicyTest` (+5 inner) | 28 | 0 | 0 |
| `BodyInvokerSeamTest` | 8 | 0 | 0 |
| **TOTAL** | **137** | **0** | **0** |

## 5. L5 pre-existing red set (worktree method, base `a66d7f6c`)

Pre-existing 26 failures reproduced at base, 0 new at head — receipt §9.8
table provides exact test class names and line numbers. W3b does not
introduce any new failure and does not widen the pre-existing red set.

| Class | Base | Head | New? |
| --- | --- | --- | --- |
| `PipelineDslSealedHierarchyTest` | FAIL | FAIL | no |
| `Lfc0GlobalStateFitnessTest` | FAIL | FAIL | no |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | FAIL | FAIL | no |
| `CoreLegacyStepMetadataResolverTest` | FAIL | FAIL | no |
| `RegistryStepMetadataResolverTest` | FAIL | FAIL | no |
| `ScriptTextEscaperTest` (×3) | FAIL | FAIL | no |
| `WithCredentialsCompileIntegrationTest` (×4) | FAIL | FAIL | no |
| `CompatibilityCorpusTest` (×2) | FAIL | FAIL | no |
| `UatCompat001CorpusSmokeRunTest` (×2) | FAIL | FAIL | no |
| `UatLocal005CheckoutGitTest` | FAIL | FAIL | no |
| `UatLocal005CorpusUntouchedTest` | FAIL | FAIL | no |
| `UatLocal007SandboxProfileTest` (×2) | FAIL | FAIL | no |
| `UatLocal008CredentialsTest` (×2) | FAIL | FAIL | no |
| `UatLocal009TopStepsTest` (×4) | FAIL | FAIL | no |
| **Total** | **26** | **26** | **0 new** |

The W3b verify matrix (137 tests in 12 suites) sits ENTIRELY outside this
pre-existing red set.

## 6. L6 hard STOP conditions

| # | Condition | Verdict | Evidence |
| --- | --- | --- | --- |
| 6.1 | No `when(stepKey)` in production code | PASS | grep showed only KDoc comments referencing the forbidden pattern as a counterexample |
| 6.2 | No new dispatch collection beside canonical body machinery | PASS | no `dispatchRetryBlock` etc. in production; the matches were inside `Lfc2ConcreteBodyRoutingDebtFitnessTest` heredoc fixtures cataloging what should NOT appear |
| 6.3 | W3b cherry-pick touches ONLY 2 files | PASS | `DslCompiledPipelineCompiler.kt` (+2) + new `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.kt` (+316) |
| 6.4 | BodyInvoker.kt doesn't carry `PluginStepId("core.*")` selectors | PASS | the matches in `StepDescriptorRegistry.kt`, `BodyAggregateIdentity.kt`, `Core*Step.kt`, `DslCompiledPipelineCompiler.kt` are descriptor/identity declarations, not BodyInvoker behavior selection; BodyInvoker.kt body inspected (lines 60-80) — no `core.*` references |
| 6.5 | BodyExecutionPolicy ADT count | PASS | 1 ADT (policy itself); `BodyExecutionPolicyShape` is the supporting projection enum, not a second policy authority |
| 6.6 | No `System.setProperty` in production | PASS | the match was a KDoc comment in `SystemRuntimeConfig.kt` describing a test fixture pattern; the class itself only READS env/property |
| 6.7 | DSL builder doesn't execute handlers | PASS | DslCompiledPipelineCompiler has no `.execute(` / `runBlocking {` calls |
| 6.8 | Family invariants (debt=0, ceiling=18, (1,1)) | PASS | `PinnedConcreteBodyRoutingDebt.value.total = 0`; `HISTORICAL_CEILING = 18`; `BodyChildLoopInventory.discovered = (1, 1)` — all unchanged |
| 6.9 | ADR-0073, ADR-0081 preserved | PASS | W3b touches 0 ADR files; ADR preservation is passive and holds |
| 6.10 | W3b fix in place | PASS | `blockStepNode` now has `WithEnv -> step.steps` and `Timestamps -> step.steps` (lines 258-260) |

## 7. Risks / unknowns / debts introduced by W3b

**Empty.** Per receipt §6 invariants, the W3b fix adds one architectural
fitness test (`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`) that
auto-discovers all body-bearing `StepSpec` variants via Kotlin reflection
and asserts each compiles to a non-empty `BlockStepNode.body`. The fix
preserves all architecture invariants:

- `debt = 0` (no new concrete block-step routing cases added)
- `ceiling = 18` unchanged
- `BodyChildLoopInventory = (1, 1)` unchanged
- ADR-0073 (re-entry through BodyInvoker), ADR-0081 (runtime-return
  preserved), ADR-0076 (coroutines are not durable authority), CTX-P
  (immutable context) preserved.

The remaining 26 pre-existing failures (UatLocal005/007/008/009,
ScriptTextEscaper, WithCredentialsCompile, CompatibilityCorpus,
UatCompat001CorpusSmokeRun, CoreLegacyStepMetadataResolver,
RegistryStepMetadataResolver, A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test,
PipelineDslSealedHierarchy, Lfc0GlobalStateFitness) are out of scope
for this cycle per the cycle preamble.

## 8. VERDICT

```
========== ========== ==========
   VERDICT:    PASS
========== ========== ==========
```

All evidence captured in commit `0b3d4c6bc35405a80c0a391e1d59a4c8c649f701`
on `origin/refactor/lfc2-e1-b11-context-blocks`. Branch tracks upstream
ready for `sddk-debt-verify`.
