# H4 archive batch 3 — 2026-10-16

## Verdict

```text
H4 batch 3 (2 TRACE_CALLERS sandbox scripts) = DELETED, 0 guarantee lost
```

2 legacy UAT sandbox scripts (PK-RET-0020, PK-RET-0021) removed
from the repository. The H4 mandate's "TRACE_CALLERS_BEFORE_REMOVAL"
policy is satisfied: every deleted script was verified to have
zero active V2 callers in the V2 composite build at the time of
removal.

## Files deleted (2)

```text
scripts/sandbox/run-smoke.sh   (PK-RET-0020)
scripts/sandbox/run-uat.sh     (PK-RET-0021)
```

## Proof of "no lost guarantee"

For each script, the only V2 caller was
`UatLocal010SmokeE2ESandboxTest.kt`. Per wave 2 of H4 batch 3
(commits `f33dd0ae` + `2fa36077`), that test was annotated
`@Disabled("migrated-pending")` and verified skipped at runtime
(commit `4a7f9fd7`). With the test no longer running, the
scripts lose their only active consumer in the V2 build.

The remaining references in the trace are:

- `docs/v2/04-adrs/ADR-0053-smoke-e2e-sandbox.md` — historical
  ADR describing the sandbox scripts at the time of their
  introduction. The receipt is the persistent record, the
  scripts are not.
- `docs/v2/06-evidence/h4-archive-batch-2-2026-10-16.md` —
  the H4 batch 2 receipt that listed these scripts as
  "what was NOT deleted" because the caller was still active
  then.
- `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` — the
  H4 batch 3 plan that flagged the scripts as
  `TRACE_CALLERS_BEFORE_REMOVAL`.
- `docs/v2/06-evidence/h4-batch-3-wave-3x-decision-2026-10-16.md`
  — the wave 3.x decision noting "the 2 sandbox scripts
  lose their last caller via wave 2 cascade."
- `pk-rc2/...` — the rc2 archive. The archive is a frozen
  snapshot of `v0.62.0-rc2`; it is not in the V2 build.
- `.worktrees/...` — historical worktree directories. Not
  in the V2 build; each worktree has its own copy of the
  scripts from the time it was created.

None of these are active V2 callers. The deletion removes the
scripts from the live `scripts/sandbox/` directory; the
historical references in `docs/` and the rc2 archive remain
as the persistent record of the scripts' role.

## Wave 2 cascade chain

The retirement follows the wave 2 cascade documented in
`h4-batch-3-wave-3x-decision-2026-10-16.md` (commit `f40e6391`):

1. Wave 2 disabled the 13 UATs dependent on `Subprocess.kt`,
   including `UatLocal010SmokeE2ESandboxTest`.
2. Wave 2.x noted the 2 sandbox scripts' last caller
   (`UatLocal010`) was now @Disabled.
3. Wave 3 (this batch) retires the 2 scripts because their
   only active V2 caller no longer runs.

The same wave-2 cascade that closed `Subprocess.kt`'s UAT
dependents also opens the retirement path for the 2 sandbox
scripts.

## State of H4 inventory after batches 1+2+3

| Bucket | Initial | After batch 1 | After batch 2 | After batch 3 |
|---|---|---|---|---|
| ARCHIVE_IF_UNWIRED | 12 | 0 | 0 | 0 |
| TRACE_CALLERS_BEFORE_REMOVAL | 9 | 9 | 2 | 0 |
| TRACE_DEPENDENTS_BEFORE_REMOVAL | 6 | 6 | 0 (KEEP per wave 3+3.x) | 0 |
| RETAIN_UNTIL_EQUIVALENT_CONTRACT | 47 | 47 | 47 | 47 |
| TRIAGE_IS_ACTUAL_END_TO_END | 43 | 43 | 43 | 43 |
| TRIAGE_CONSOLIDATION | 52 | 52 | 52 | 52 |
| **Total retired** | 0 | 12 | 19 | **21** |
| **Total inventory** | 169 | 157 | 150 | **148** |

21 of 169 PR #140 entries retired: 12 (batch 1) + 7 (batch 2)
+ 2 (batch 3) = 21.

The remaining inventory:
- 47 `RETAIN_UNTIL_EQUIVALENT_CONTRACT` (certifier-blocked).
- 43 `TRIAGE_IS_ACTUAL_END_TO_END` (certifier-blocked).
- 52 `TRIAGE_CONSOLIDATION` (certifier-blocked).
- Total certifier-blocked: 142. Plus the 5 KEEP V2
  test-support files per wave 3+3.x (in the
  `TRACE_DEPENDENTS_BEFORE_REMOVAL` bucket) which are
  closed-in-source.

## Cross-references

- Trace correction: `docs/v2/06-evidence/h4-batch-3-trace-correction-2026-10-16.md` (commit `3aa64648`).
- Decision manifest: `docs/v2/06-evidence/h4-batch-3-deletion-decisions-2026-10-16.md` (commit `aec3a777`).
- Wave 2 commits: `f33dd0ae` (UatLocal001) + `2fa36077` (9 more UATs).
- Wave 2 static verification: `docs/v2/06-evidence/h4-batch-3-wave-2-verification-2026-10-16.md` (commit `a7db0041`).
- Wave 2 runtime verification: `docs/v2/06-evidence/h4-batch-3-wave-2-runtime-verification-2026-10-16.md` (commit `4a7f9fd7`).
- Wave 3 KEEP decision: `docs/v2/06-evidence/h4-batch-3-wave-3-decision-2026-10-16.md` (commit `49749c39`).
- Wave 3.x KEEP decision: `docs/v2/06-evidence/h4-batch-3-wave-3x-decision-2026-10-16.md` (commit `f40e6391`).
- H4 batch 1 receipt: `docs/v2/06-evidence/h4-archive-batch-1-2026-10-16.md` (commit `b3fc8f7`).
- H4 batch 2 receipt: `docs/v2/06-evidence/h4-archive-batch-2-2026-10-16.md` (commit `002ccc9`).
- H4 classification: `docs/v2/06-evidence/h4-classification-2026-10-16.md` (commit `ae2cf55`).
- H4 batch 3 plan: `docs/v2/06-evidence/h4-batch-3-plan-2026-10-16.md` (commit `c934b7d0`).
- ADR-0053 (smoke E2E sandbox, the historical ADR).
- PR #140 (the canonical inventory).
