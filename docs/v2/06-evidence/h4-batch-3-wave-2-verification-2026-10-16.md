# H4 batch 3 wave 2 — independent verification (2026-10-16)

## Verdict

```text
13 of 13 expected UATs are @Disabled("migrated-pending").
0 missing.
```

This receipt records an independent, single-command verification
of the 10 `@Disabled` annotations applied in commits `f33dd0ae`
and `2fa36077` plus the 3 pre-existing from prior BQ5 work
(UatLocal003, 004, 006).

## Verification (re-runnable)

```text
$ for f in UatLocal001KillDuringShTest UatLocal002ResumeAfterKillTest \
           UatLocal003ReturnStdoutTest UatLocal004TimeoutTest \
           UatLocal006LostHeartbeatTest UatLocal007SandboxProfileTest \
           UatLocal010SmokeE2ESandboxTest UatStep001ShExecutionTest \
           UatStep002EchoCaptureTest UatStep003ErrorAbortTest \
           UatStep004SleepTimingTest UatEvt001ReplayTest \
           UatDsl006BodyExecutionTest; do
    if ! grep -q '@Disabled("migrated-pending")' \
        "v2/pipeline-application/src/test/.../${f}"; then
        echo "MISSING: $f"
    fi
  done
  (no output)
```

All 13 expected files contain the annotation. 0 missing.

## Broader context (informational)

The same grep across the entire `v2/pipeline-application/src/test/`
tree returns 22 files with `@Disabled("migrated-pending")`. The 9
extra files (EmitEventCatchErrorRegistryUatTest, ObsC23ChannelSeparationUatTest,
RetryAcceptanceMatrixTest, Lpr011r2SecretRedactionAtRestUatTest,
Lpr011SecretRedactionTranscriptUatTest, ObsPc2LevelAUatTest,
PublishHtmlOperationsAdapterUatTest, StashOperationsAdapterUatTest,
UatDsl008StageOptionsFailClosedTest) are pre-existing
`migrated-pending` annotations from prior BQ5 work and unrelated
WUs; they are out of h4-batch-3 scope.

The h4-batch-3 wave 2 scope is exactly the 13 UAT dependents of
`Subprocess.kt` (PK-RET-0066) per the trace correction at
`3aa64648`. All 13 are marked.

## Net H4 inventory after this turn

- 19 retired (H4 batches 1+2, commits `b3fc8f7`, `002ccc9`).
- 1 plan correction (commit `3aa64648`).
- 1 decision manifest (commit `aec3a777`).
- 13 UATs MigratePending in code (commits `f33dd0ae`, `2fa36077`).
- 8 architectural-fitness tests `Keep` (per manifest at `aec3a777`).
- 5 V2 test-support files (PK-RET-0039, 0041, 0066, 0067, 0069)
  in retirement cascade — blocked on the 8 fitness tests' refactor
  decision.
- 142 certifier-blocked entries (TRIAGE_IS_ACTUAL_END_TO_END +
  TRIAGE_CONSOLIDATION buckets) unaddressed.
- H2 TODO 1 DRAFT ADR at `8903b5f0` awaiting user review.
- v0.62.0-rc5 last published RC; rc6 publication not authorized
  in this session.

## Cross-references

- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Decision manifest: `docs/v2/06-evidence/h4-batch-3-deletion-decisions-2026-10-16.md` (commit `aec3a777`).
- Plan (with the corrected claim): `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- AGENTS.md §1 (UAT migration to harness; `@Disabled(migrated-pending)` is canonical).
