# H4 batch 3 wave 3.x — KEEP the 3 remaining V2 test-support files (2026-10-16)

## Verdict

```text
H4 batch 3 wave 3.x decision: KEEP all 3 remaining V2 test-support files.

- CorpusNormalizer.kt (PK-RET-0039)        KEEP (3 test dependents)
- CorpusSnapshotDiffer.kt (PK-RET-0041)    KEEP (1 test dependent)
- SubprocessFixtureProgram.kt (PK-RET-0067) KEEP (1 test dependent)
```

H4 batch 3 is **CLOSED in source** with this decision.

## Per-file decisions

### `CorpusNormalizer.kt` (PK-RET-0039) — KEEP

3 test dependents:

| Dependents | Decision | Reason |
|---|---|---|
| `CorpusNormalizerTest.kt` | KEEP | Unit test for the support class. The support class and its test are a unit. |
| `CorpusSnapshotDifferTest.kt` | KEEP | Uses `CorpusNormalizer` to normalize snapshot fixtures before diffing. Removing the support file breaks the test. |
| `durable/B1dBodyExecutionEngineClockSeparationTest.kt` | KEEP | Architectural fitness (B1d clock separation). The test uses `CorpusNormalizer` to project clock-dependent fields out of the comparison. |

The support class is a "deterministic snapshot normalizer for
compatibility corpus diff" per its KDoc. It strips
non-structural fields (eventId, runId, occurredAt; diagnostic
stack traces) and projects to a stable shape. Both fitness
tests use it for legitimate reasons. Inlining the
normalization would duplicate code.

### `CorpusSnapshotDiffer.kt` (PK-RET-0041) — KEEP

1 test dependent:

| Dependent | Decision | Reason |
|---|---|---|
| `CorpusSnapshotDifferTest.kt` | KEEP | Unit test for the support class. |

The support class is a "per-fixture diff classifier into 7
buckets per COMPATIBILITY_CORPUS.md L51-58" per its KDoc. The
test verifies the classification logic. Removing the support
file kills the test.

### `SubprocessFixtureProgram.kt` (PK-RET-0067) — KEEP

1 test dependent:

| Dependent | Decision | Reason |
|---|---|---|
| `support/OwnedSubprocessRunTest.kt` | KEEP | Architectural fitness for owned subprocess runs. The test references `SubprocessFixtureProgram::class.java.name` as a CLI argument. |

The support class is a CLI fixture program used as a known
control for subprocess-run tests. It's a small program that
the test launches as a child process; the test asserts the
ownership/detach semantics. The test is architectural
fitness; the fixture is part of the test contract.

## Net H4 inventory after waves 1+2+3+3.x

- 19 retired (H4 batches 1+2, commits `b3fc8f7`, `002ccc9`).
- 1 plan correction (commit `3aa64648`).
- 1 decision manifest (commit `aec3a777`).
- 13 UATs MigratePending in code (commits `f33dd0ae`, `2fa36077`).
- 1 wave 2 verification (commit `a7db0041`).
- 1 wave 3 KEEP decision (commit `49749c39`).
- 1 wave 3.x KEEP decision (this receipt).
- 8 architectural-fitness tests KEEP (per wave 3).
- 5 V2 test-support files KEEP with rationale (Subprocess.kt,
  SubprocessOutcome.kt, CorpusNormalizer.kt, CorpusSnapshotDiffer.kt,
  SubprocessFixtureProgram.kt).
- 2 sandbox scripts (PK-RET-0020, 0021) out of scope (their
  caller `UatLocal010SmokeE2ESandboxTest` is now @Disabled
  per wave 2, so the scripts lose their last caller; they
  are certifier-blocked by H4 batch 3 wave 2 cascade).
- 1 CSV artifact `AbstractE2ETest.kt` (PK-RET-0006) in V1
  `pipeline-cli/` module, not in V2 build — out of V2 scope.
- 142 certifier-blocked entries (TRIAGE_IS_ACTUAL_END_TO_END +
  TRIAGE_CONSOLIDATION buckets) unaddressed.
- H2 TODO 1 DRAFT ADR at `8903b5f0` awaiting user review.
- v0.62.0-rc5 last published RC; rc6 publication not authorized
  in this session.

H4 batch 3 is **CLOSED in source**. All 8 originally-flagged
entries are now classified with rationale:
- 2 sandbox scripts (PK-RET-0020, 0021) → certifier-blocked
  via wave 2 cascade.
- 6 V2 test-support files (PK-RET-0039, 0041, 0066, 0067, 0069)
  → KEEP with rationale.

## Sandbox scripts status (informational)

`scripts/sandbox/run-smoke.sh` (PK-RET-0020) and
`scripts/sandbox/run-uat.sh` (PK-RET-0021) are referenced by
`UatLocal010SmokeE2ESandboxTest.kt`, which was @Disabled in
wave 2. The scripts' last caller is now @Disabled, so they
lose their only active consumer. They are retirement-ready
in the next H4 batch (4+) once the UAT migration to the harness
is complete. They are NOT in this receipt's KEEP list.

## Cross-references

- Wave 3 decision: `docs/v2/06-evidence/h4-batch-3-wave-3-decision-2026-10-16.md` (commit `49749c39`).
- Wave 2 verification: `docs/v2/06-evidence/h4-batch-3-wave-2-verification-2026-10-16.md` (commit `a7db0041`).
- Decision manifest: `docs/v2/06-evidence/h4-batch-3-deletion-decisions-2026-10-16.md` (commit `aec3a777`).
- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Plan: `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- AGENTS.md §3 (architectural-fitness tests stay).
