# H4 archive batch 2 — 2026-10-16

## Verdict

```text
H4 batch 2 (7 TRACE_CALLERS_BEFORE_REMOVAL scripts) = DELETED, 0 guarantee lost
```

7 legacy UAT/sdkman scripts (PK-RET-0014..0019, 0022) removed
from the repository in one atomic commit. The H4 mandate's
"TRACE_CALLERS_BEFORE_REMOVAL" policy is satisfied: every
deleted script was verified unwired from any active build
before removal.

## Files deleted (7)

```text
scripts/gen-current-uat-status.py
scripts/release/cheat-sheet-uat.sh
scripts/release/cli-exit-contract-uat.sh
scripts/release/sdkman-install-uat.sh
scripts/release/sdkman-uat/mirror.py
scripts/release/sdkman-uat/run-spike.sh
scripts/test_gen_current_uat_status.py
```

## Proof of "no lost guarantee"

For each script, `grep -rln` of the basename across
`*.kt`/`*.kts`/`*.sh`/`*.py`/`*.yml`/`*.yaml`/`*.md` in the
parent directory returned only `.md` references (historical
documentation) and one sibling-script reference:

| Script | Active callers | Doc references |
|---|---|---|
| `gen-current-uat-status.py` | 0 | 3 (PRODUCTION_READY_UAT_MATRIX, T0E_CLOSURE_RECEIPT, T0E_EVID_01) |
| `cheat-sheet-uat.sh` | 0 | 3 (user/cheat-sheet, DISTRIBUTION_RELEASE_SPEC, WU_LPR_071) |
| `cli-exit-contract-uat.sh` | 0 | 2 (WU_LPR_071, WU_LPR_080) |
| `sdkman-install-uat.sh` | 0 | 3 (README, LPR_GATE_1, WU_LPR_071) |
| `sdkman-uat/mirror.py` | 1 sibling: `sdkman-uat/run-spike.sh` | 2 (WU_LPR_080, WU_LPR_090) |
| `sdkman-uat/run-spike.sh` | 1 sibling: `mirror.py` | 2 (WU_LPR_080, WU_LPR_090) |
| `test_gen_current_uat_status.py` | 0 | 3 (T0E_EVID_01, T0E_CLOSURE_RECEIPT, PRDY_003) |

The `.md` references are historical evidence (a
`WU_*_RECEIPT.md` records the script's role at the time of
issue closure; the receipt is the persistent record, the
script is not). No Kotlin, KTS, shell, Python, or YAML file
in the V2 composite build invokes any of the 7 scripts.

The two SDKMAN-sibling references (`mirror.py` ↔
`run-spike.sh`) are removed together: deleting `mirror.py`
without `run-spike.sh` would have left a dangling call; both
are removed in this commit and no other reference exists.

The 4 SDKMAN scripts are also retired because AGENTS.md §8
records `.github/workflows/` as empty and explicitly retired
the `sdkman-publish.yml` workflow on 2026-09-30
(`754ddda0 chore(ci): remove dead GitHub Actions workflows`).
SDKMAN is not in the V2 distribution policy
(`DISTRIBUTION_ROADMAP.md`); the scripts are V1-era and
unwired.

## What was NOT deleted (and why)

- **`scripts/sandbox/run-smoke.sh` (PK-RET-0020)** and
  **`scripts/sandbox/run-uat.sh` (PK-RET-0021)** — referenced
  by `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal010SmokeE2ESandboxTest.kt`
  (an active V2 test). The trace shows the test calls both
  scripts; deleting either would break the test. Their
  retirement is a `TRACE_CALLERS_BEFORE_REMOVAL` task that
  requires first removing the V2 test that uses them, which
  is out of scope for H4 batch 2 (the V2 test is in the
  `TRIAGE_CONSOLIDATION` bucket; its retirement requires
  certifier verdict per H4 rule).
- **`AbstractE2ETest.kt` (PK-RET-0006)** and the 5 V2 test-support
  Kotlin files in `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/`
  (`CorpusNormalizer.kt`, `CorpusSnapshotDiffer.kt`,
  `Subprocess.kt`, `SubprocessFixtureProgram.kt`,
  `SubprocessOutcome.kt`) — all have active V2 dependents:
  - `CorpusNormalizer` is referenced by `CorpusNormalizerTest`,
    `CorpusSnapshotDifferTest`, and
    `B1dBodyExecutionEngineClockSeparationTest`.
  - `CorpusSnapshotDiffer` is referenced by
    `CorpusSnapshotDifferTest`.
  - `Subprocess` is referenced by 5 production code files
    in `v2/pipeline-application/src/main/`
    (`RegistryStepMetadataResolver`, `CoreShellStep`,
    `DurableInvocationResolver`, `CoordinatorCaps`,
    `RunningSubprocessRecovery`) — its removal would break
    V2 production code.
  - `SubprocessFixtureProgram` is referenced by
    `OwnedSubprocessRunTest`.
  - `SubprocessOutcome` is referenced by `Subprocess.kt`
    itself and `SubprocessHarnessTest`.
  Their retirement is a `TRACE_DEPENDENTS_BEFORE_REMOVAL`
  task that requires migrating the dependents first; out of
  scope for H4 batch 2.

## State of H4 inventory after batches 1+2

| Bucket | Initial | After batch 1 | After batch 2 |
|---|---|---|---|
| ARCHIVE_IF_UNWIRED | 12 | 0 | 0 |
| TRACE_CALLERS_BEFORE_REMOVAL | 9 | 9 | 2 |
| TRACE_DEPENDENTS_BEFORE_REMOVAL | 6 | 6 | 6 |
| RETAIN_UNTIL_EQUIVALENT_CONTRACT | 47 | 47 | 47 |
| TRIAGE_IS_ACTUAL_END_TO_END | 43 | 43 | 43 |
| TRIAGE_CONSOLIDATION | 52 | 52 | 52 |
| **Total retired** | 0 | 12 | **19** |
| **Total inventory** | 169 | 157 | **150** |

## Cross-references

- PR #140 (open): the canonical inventory.
- `docs/v2/06-evidence/h4-classification-2026-10-16.md` (H4 classification at commit `ae2cf55`).
- `docs/v2/06-evidence/h4-archive-batch-1-2026-10-16.md` (batch 1, commit `b3fc8f7`).
- `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md` line 71 (H4 mandate).
- AGENTS.md §8 (`.github/workflows/` empty since `754ddda0`, 2026-09-30).
