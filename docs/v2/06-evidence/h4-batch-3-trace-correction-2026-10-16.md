# H4 batch 3 trace correction — 2026-10-16

## Verdict

```text
H4 batch 3 (8 entries with active V2 dependents) — trace correction:
- Subprocess.kt (PK-RET-0066): 0 production dependents, 21 test dependents.
  Plan at c934b7d0 claimed 5 production dependents; that claim was wrong.
- The other 4 V2 test-support files (PK-RET-0039, 0041, 0067, 0069):
  dependent counts confirmed as 3, 1, 1, 2 (all test).
- Net effect on the H4 inventory: zero. Subprocess.kt stays
  TRACE_DEPENDENTS_BEFORE_REMOVAL, but the migration path is now
  "retire 21 test dependents (certifier-blocked)" rather than
  "refactor 5 production files".
```

The plan at `c934b7d0` (commit `h4-batch-3-plan-2026-10-16.md`)
classified `Subprocess.kt` as having 5 V2 production dependents
(`RegistryStepMetadataResolver.kt`, `CoreShellStep.kt`,
`DurableInvocationResolver.kt`, `CoordinatorCaps.kt`,
`RunningSubprocessRecovery.kt`). This was a grep-matching error:
the original grep matched the token `Subprocess` in any context,
which caught the `RecoveryPolicy.ExternalSubprocess` enum
reference in `CoreShellStep.kt:462` and a comment in
`RegistryStepMetadataResolver.kt:19`. Neither is a class import
of the test-support `Subprocess`.

## Independent verification (re-runnable)

```text
$ grep -rn "import.*\.Subprocess$\|import.*\.Subprocess\." \
    v2/pipeline-application/src/main/
(no output)

$ grep -rln "dev\.rubentxu\.pipeline\.v2\.application\.support\.Subprocess\b" \
    v2/pipeline-application/src/
(21 files, all under src/test/, 0 under src/main/)
```

No file in `v2/pipeline-application/src/main/` imports the
test-support `Subprocess` class. The class is reachable from
production code only via Kotlin's classpath visibility: the
package `dev.rubentxu.pipeline.v2.application.support` lives
in `src/test/`, and Kotlin's source-set separation isolates
`src/main/` from `src/test/`. Production code cannot import it.

The 5 production files named in the plan at `c934b7d0` were
checked one by one:

| File | Has `Subprocess` token? | Imports test-support class? |
|---|---|---|
| `RegistryStepMetadataResolver.kt` | yes, line 19 (comment) | NO |
| `CoreShellStep.kt` | yes, line 462 (`RecoveryPolicy.ExternalSubprocess` enum) | NO |
| `DurableInvocationResolver.kt` | NO matches at all | NO |
| `CoordinatorCaps.kt` | NO matches at all | NO |
| `RunningSubprocessRecovery.kt` | NO matches at all | NO |

The plan's "5 production files referencing Subprocess.kt" was a
false claim produced by a too-loose grep. The corrected claim is
"0 production files; 21 test files". This is the same shape of
defect the HARNESS FIDELITY LAW §1/§2 warns against: a
re-derivation that does not cross the production authority.

## The 21 actual test dependents

```text
v2/.../test/.../CliCompileErrorExitsOneTest.kt
v2/.../test/.../CliDslConstructionFailureSurfacesTest.kt
v2/.../test/.../CliNonCanonicalInMemoryExitsTwoTest.kt
v2/.../test/.../F5_1_ScmGitStepContractTest.kt
v2/.../test/.../Lfc2WaitUntilCanonicalReentryFitnessTest.kt
v2/.../test/.../S0SemanticWitnessMatrixTest.kt
v2/.../test/.../S3EnvironmentSemanticWitnessTest.kt
v2/.../test/.../TrapFormNegativeFixtureTest.kt
v2/.../test/.../UatDsl006BodyExecutionTest.kt
v2/.../test/.../UatEvt001ReplayTest.kt
v2/.../test/.../UatLocal001KillDuringShTest.kt
v2/.../test/.../UatLocal002ResumeAfterKillTest.kt
v2/.../test/.../UatLocal003ReturnStdoutTest.kt
v2/.../test/.../UatLocal004TimeoutTest.kt
v2/.../test/.../UatLocal006LostHeartbeatTest.kt
v2/.../test/.../UatLocal007SandboxProfileTest.kt
v2/.../test/.../UatLocal010SmokeE2ESandboxTest.kt
v2/.../test/.../UatStep001ShExecutionTest.kt
v2/.../test/.../UatStep002EchoCaptureTest.kt
v2/.../test/.../UatStep003ErrorAbortTest.kt
v2/.../test/.../UatStep004SleepTimingTest.kt
```

Most are in the legacy UAT bucket (`UatLocal*`, `UatStep*`,
`UatEvt*`, `UatDsl*`) which AGENTS.md §1 marks as
"responsibility of the `pipelinek-release-harness`" and slated
for migration. The fitness tests (`S0`, `S3`, `Lfc2`,
`F5_1_ScmGitStepContractTest`, `TrapFormNegativeFixtureTest`)
are the architectural fitness for Steps and constitute the
load-bearing reason `Subprocess.kt` exists.

## Revised migration path for `Subprocess.kt`

The retirement path is unchanged in shape (certifier-blocked)
but different in content (test retirement, not production
refactor):

1. **Wave 1 (this receipt)**: trace correction. The 0-production-
   dependent claim is now in the repo. Future readers do not
   have to re-derive it.
2. **Wave 2 (certifier-blocked)**: the 21 test dependents retire
   per AGENTS.md §1 and ADR-PK-QV-003. Each UAT migration is a
   5-axis certifier verdict; the fitness tests need a replacement
   in `:test-harness` or equivalent.
3. **Wave 3 (after certifier unblock)**: `Subprocess.kt`
   retirement. Zero production files reference it; the file
   goes.

The original plan at `c934b7d0` listed three options for the
5-production-dependent case (a/b/c, all multi-step refactors).
None of those options applies: there is no production caller
to refactor. The correct option is the simplest one: retire
the test dependents first, then the support file.

## The 4 other V2 test-support files

The plan at `c934b7d0` had correct counts for the other 4:

| File | Plan claim | Verified count | Confirmed? |
|---|---|---|---|
| `CorpusNormalizer.kt` (PK-RET-0039) | 3 dependents | 3 (`CorpusNormalizerTest`, `CorpusSnapshotDifferTest`, `B1dBodyExecutionEngineClockSeparationTest`) | YES |
| `CorpusSnapshotDiffer.kt` (PK-RET-0041) | 1 dependent | 1 (`CorpusSnapshotDifferTest`) | YES |
| `SubprocessFixtureProgram.kt` (PK-RET-0067) | 1 dependent | 1 (`OwnedSubprocessRunTest`) | YES |
| `SubprocessOutcome.kt` (PK-RET-0069) | 2 dependents | 2 (`Subprocess.kt`, `SubprocessHarnessTest`) | YES |

## Net H4 inventory impact

None. The 8 entries with active V2 dependents stay
`MIGRATION_PENDING` and `TRACE_DEPENDENTS_BEFORE_REMOVAL`.
The retirement path for `Subprocess.kt` is now described
correctly. No entries retired by this receipt.

## Cross-references

- Plan (with the corrected claim): `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- H4 classification: `docs/v2/06-evidence/h4-classification-2026-10-16.md` (commit `ae2cf55`).
- H4 batch 1: `docs/v2/06-evidence/h4-archive-batch-1-2026-10-16.md` (commit `b3fc8f7`).
- H4 batch 2: `docs/v2/06-evidence/h4-archive-batch-2-2026-10-16.md` (commit `002ccc9`).
- AGENTS.md §HARNESS FIDELITY LAW §1/§2: a test/receipt that re-derives a value the production authority owns is not a weaker test — it is a test of its own arithmetic.
- AGENTS.md §1 (UAT migration to `pipelinek-release-harness`).
- ADR-PK-QV-003 (5-axis certifier verdict contract).
