# H4 batch 3 wave 3 — KEEP Subprocess.kt decision (2026-10-16)

## Verdict

```text
H4 batch 3 wave 3 decision: KEEP Subprocess.kt in src/test/.

Rationale: the 8 architectural-fitness tests use Subprocess.run(...)
for the WAITFOR-3 fix (drained child process). Inlining the process
management would duplicate code, not simplify. The 13 UATs are
already @Disabled, so Subprocess.kt's only remaining dependents
are the 8 fitness tests, all of which legitimately need it.
```

## Why this is the right decision

The 8 architectural-fitness tests that depend on `Subprocess.kt`
all use `Subprocess.run(...)` to launch the `pipelinek` CLI as a
child process and capture its output:

| Test | Uses |
|---|---|
| `S0SemanticWitnessMatrixTest.kt` | 3 calls to `Subprocess.run` |
| `S3EnvironmentSemanticWitnessTest.kt` | 2 calls |
| `Lfc2WaitUntilCanonicalReentryFitnessTest.kt` | 1 call |
| `F5_1_ScmGitStepContractTest.kt` | 1 call |
| `TrapFormNegativeFixtureTest.kt` | 1 call |
| `CliCompileErrorExitsOneTest.kt` | 2 calls |
| `CliDslConstructionFailureSurfacesTest.kt` | 2 calls |
| `CliNonCanonicalInMemoryExitsTwoTest.kt` | 1 call |

Each call has the comment "WAITFOR-3: drained while the child
runs; see support/Subprocess.kt." The `Subprocess.run` helper
implements the canonical four-obligation child-process pattern
(launch, drain, bound the wait, kill on timeout) that the
`Subprocess.kt` KDoc documents. Without it, the tests would
need to inline ~50 lines of process management each, which is
duplication, not simplification.

The alternative (option (a) in the plan at `c934b7d0` — move
`Subprocess.kt` to `src/main/`) is rejected: `Subprocess.kt`
is a test-harness utility per its own KDoc ("One owner for
every child process a test starts"). Moving it to
`src/main/` would expose it as a public API without
production consumers, expanding the surface area without
benefit. The category-correct location is `src/test/`.

## What this means for the 5 V2 test-support files

| File (PK-RET) | Dependents | Decision |
|---|---|---|
| `Subprocess.kt` (0066) | 8 fitness tests (`Keep` per manifest) | KEEP in `src/test/` |
| `SubprocessOutcome.kt` (0069) | `Subprocess.kt` + `SubprocessHarnessTest` | KEEP — coupled to `Subprocess.kt` |
| `SubprocessFixtureProgram.kt` (0067) | `OwnedSubprocessRunTest` | review separately; not blocking H4 batch 3 |
| `CorpusSnapshotDiffer.kt` (0041) | `CorpusSnapshotDifferTest` | review separately; not blocking H4 batch 3 |
| `CorpusNormalizer.kt` (0039) | 3 dependents | review separately; not blocking H4 batch 3 |

`Subprocess.kt` and `SubprocessOutcome.kt` are kept because the
8 architectural-fitness tests legitimately need them. The other
3 (`SubprocessFixtureProgram.kt`, `CorpusSnapshotDiffer.kt`,
`CorpusNormalizer.kt`) are a separate H4 batch 3.x decision
not addressed in this receipt.

## Net H4 inventory after waves 1+2+3

- 19 retired (H4 batches 1+2, commits `b3fc8f7`, `002ccc9`).
- 1 plan correction (commit `3aa64648`).
- 1 decision manifest (commit `aec3a777`).
- 13 UATs MigratePending in code (commits `f33dd0ae`, `2fa36077`).
- 1 wave 2 verification (commit `a7db0041`).
- 8 architectural-fitness tests KEEP (per this receipt).
- 2 V2 test-support files KEEP with rationale (`Subprocess.kt`,
  `SubprocessOutcome.kt`).
- 142 certifier-blocked entries (TRIAGE_IS_ACTUAL_END_TO_END +
  TRIAGE_CONSOLIDATION buckets) unaddressed.
- H2 TODO 1 DRAFT ADR at `8903b5f0` awaiting user review.
- v0.62.0-rc5 last published RC; rc6 publication not authorized
  in this session.

H4 batch 3 is **CLOSED in source** with the decision documented
in this receipt. The retirement cascade for `Subprocess.kt` and
`SubprocessOutcome.kt` is no longer pending: both are KEEP.

## Cross-references

- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Decision manifest: `docs/v2/06-evidence/h4-batch-3-deletion-decisions-2026-10-16.md` (commit `aec3a777`).
- Wave 2 verification: `docs/v2/06-evidence/h4-batch-3-wave-2-verification-2026-10-16.md` (commit `a7db0041`).
- Plan (with the corrected claim): `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- AGENTS.md §1 (UAT migration to harness).
- AGENTS.md §3 (architectural-fitness tests stay).
- `Subprocess.kt` KDoc: "One owner for every child process a test starts."
