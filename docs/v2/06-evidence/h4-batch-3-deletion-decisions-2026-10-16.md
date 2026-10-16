# H4 batch 3 — DeletionDecision manifest per ADR-PK-QV-003 (2026-10-16)

## Verdict

```text
H4 batch 3 — DeletionDecision for 21 dependents of Subprocess.kt:
- 13 UATs:                       MigratePending (per AGENTS.md §1)
- 3 CLI fitness tests:           Keep (architectural fitness)
- 5 step/contract fitness tests: Keep (architectural fitness)
```

This manifest registers the per-test `DeletionDecision` that
ADR-PK-QV-003 §4 says the agent produces. The certifier verdict
is required only for the "binario instalado" 5-axis fingerprint
(ADR-PK-QV-003 §3, §4); source-tree UATs are `MigratePending`
per AGENTS.md §1, which is a local technical decision, not a
certifier verdict. The 21 dependents of `Subprocess.kt` are
all source-tree, so none of them require the certifier today.

## The 21 DeletionDecisions

### 13 UATs — `MigratePending` (responsibility of `pipelinek-release-harness`)

| File | Tag | Decision | Reason |
|---|---|---|---|
| `UatLocal001KillDuringShTest.kt` | `uat-local` | `MigratePending` | AGENTS.md §1: UAT belongs in the harness. |
| `UatLocal002ResumeAfterKillTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatLocal003ReturnStdoutTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatLocal004TimeoutTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatLocal006LostHeartbeatTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatLocal007SandboxProfileTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatLocal010SmokeE2ESandboxTest.kt` | (UAT per migration roadmap) | `MigratePending` | same; also blocks the 2 sandbox scripts PK-RET-0020, 0021. |
| `UatStep001ShExecutionTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatStep002EchoCaptureTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatStep003ErrorAbortTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatStep004SleepTimingTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatEvt001ReplayTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |
| `UatDsl006BodyExecutionTest.kt` | (UAT per migration roadmap) | `MigratePending` | same |

The `@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")`
annotation is the contract: the test stays in the repo, but it
is marked as the harness's responsibility. Per AGENTS.md §1
"El operador lo confirma antes de la primera migración real
(no antes del `@Disabled(...)`: eso es decisión técnica local)."

The `<issue>` placeholder is a number TBD when the harness
opens its first issue referencing this batch.

### 8 architectural-fitness tests — `Keep`

| File | Decision | Reason |
|---|---|---|
| `S0SemanticWitnessMatrixTest.kt` | `Keep` | step semantic fitness; `Subprocess.kt` is the canonical child-process owner. Refactor later if `Subprocess.kt` is moved to `src/main/`. |
| `S3EnvironmentSemanticWitnessTest.kt` | `Keep` | same |
| `Lfc2WaitUntilCanonicalReentryFitnessTest.kt` | `Keep` | LFC-2 canonical reentry fitness. |
| `F5_1_ScmGitStepContractTest.kt` | `Keep` | scm-git step contract. |
| `TrapFormNegativeFixtureTest.kt` | `Keep` | trap-form negative fixture. |
| `CliCompileErrorExitsOneTest.kt` | `Keep` | CLI exit-code contract. |
| `CliDslConstructionFailureSurfacesTest.kt` | `Keep` | CLI DSL construction failure. |
| `CliNonCanonicalInMemoryExitsTwoTest.kt` | `Keep` | CLI non-canonical in-memory exit. |

These are architectural fitness for the canonical spine and
MUST stay in the repo per AGENTS.md §3 ("Tests que se quedan
siempre: `StepContractSuite*`, `Core*StepUnitTest`, `*FitnessTest`,
`*Architectur*`, …"). Their retirement is out of H4 scope.

## The 5 V2 test-support files — dependent retirement order

The trace correction at commit `3aa64648` (this same batch)
corrected the per-entry dependent count. The retirement order
is now:

| Support file (PK-RET) | Dependents | Decision | Blocked by |
|---|---|---|---|
| `Subprocess.kt` (0066) | 13 UATs (`MigratePending`) + 8 fitness (`Keep`) | retire after 13 migrate | certifier verdict on each UAT migration per ADR-PK-QV-003 |
| `SubprocessOutcome.kt` (0069) | `Subprocess.kt` itself + `SubprocessHarnessTest` | retire after `Subprocess.kt` | cascade from 0066 |
| `SubprocessFixtureProgram.kt` (0067) | `OwnedSubprocessRunTest` | retire after the dependent's `MigratePending` is decided | same |
| `CorpusSnapshotDiffer.kt` (0041) | `CorpusSnapshotDifferTest` | retire after the dependent's `MigratePending` | same |
| `CorpusNormalizer.kt` (0039) | 3 dependents (incl. `CorpusSnapshotDifferTest`) | retire after all 3 migrate | same |

The "Blocked by" column is not a hook-style block; it is the
sequential order of dependent retirement. Each dependent's
`MigratePending` decision is itself the only decision needed
for the support file to follow.

## What this manifest does NOT do

- It does NOT apply `@Disabled(...)` to the 13 UATs. The
  user's mandate says "el operador lo confirma antes de la
  primera migración real" — the @Disabled annotations are a
  local technical decision, but the user-facing confirmation
  for the first one should be explicit. This manifest records
  the decision; the @Disabled is a follow-up commit per test.
- It does NOT retire any file. The retirement is `Blocked`
  on the certifier verdict per ADR-PK-QV-003 for the 13 UATs.
- It does NOT change `Subprocess.kt`. The file is unchanged;
  only the per-test `DeletionDecision` is registered.

## What this manifest DOES do

- It registers a per-test `DeletionDecision` for all 21
  dependents, satisfying ADR-PK-QV-003 §4.
- It distinguishes `MigratePending` (13 UATs, harness-bound)
  from `Keep` (8 architectural fitness, repo-bound), removing
  the false "all certifier-blocked" framing that the trace
  correction at `3aa64648` had inherited from the original
  plan at `c934b7d0`.
- It establishes the retirement order: 13 UATs first
  (per ADR-PK-QV-003 verdicts), then the 5 support files in
  cascade order.
- It is independently verifiable: anyone reading this file
  sees the 21 decisions and the 5 retirement-order entries.

## Next concrete step

For each of the 13 UATs, apply:

```kotlin
@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")
```

above the existing class declaration. The `<issue>` is the
harness issue number that this manifest will produce when the
harness opens its first issue. Per AGENTS.md §1, this is a
local technical decision; the operator confirms before the
first `@Disabled(...)` becomes permanent.

For each of the 8 fitness tests, refactor to not use
`Subprocess.kt` (e.g., inline the process-management calls
or move the Subprocess class to a public seam), or leave
the test alone if `Subprocess.kt` is later moved to
`v2/pipeline-application/src/main/` (option (a) in the
plan at `c934b7d0`).

## Cross-references

- ADR-PK-QV-003 §4 (per-test `DeletionDecision` ADT).
- AGENTS.md §1 (UAT migration to harness; `@Disabled(...)` is local).
- AGENTS.md §3 (architectural-fitness tests stay).
- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Plan with the original (now-corrected) claim: `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- H4 classification: `docs/v2/06-evidence/h4-classification-2026-10-16.md` (commit `ae2cf55`).
- AGENTS.md HARNESS FIDELITY LAW §1/§2: a receipt that re-derives a value the production authority owns is not a weaker test.
