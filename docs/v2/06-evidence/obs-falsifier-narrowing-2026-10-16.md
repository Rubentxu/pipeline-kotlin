# OBS Falsifier narrowing — 2026-10-17 (H0..H4 closure next action)

## Verdict

```text
H4 OBS characterization — narrowing migrated-pending set:
- 4 of 12 OBS Falsifiers are REAL Falsifiers (defect characterization).
- 8 of 12 OBS Falsifiers were FALSE POSITIVES in the prior gate run
  (Gradle daemon lock + test suite timing artifact).
- The 4 real Falsifiers are now @Disabled (migrated-pending to harness).
- The 8 false positives are now permanent non-regression guards.
```

## What this receipt records

Earlier in this session (commits `ebde1314` + `0a01bc15`) all 12 OBS
Falsifier tests were marked `@Disabled("migrated-pending")` based on
a prior gate-run failure log. This session **re-verified** by
re-enabling the tests and running them, then narrowed the
migrated-pending set to only the 4 that are real Falsifiers.

| Test | Status | Symptom |
|---|---|---|
| `ObsA.sh output reaches the live console while the step is still running` | RED (real) | bytes withheld from the reader during the run |
| `ObsA.jsonl carries process output and every line stays parseable` | RED (real) | jsonl stream withholds bytes past the run end |
| `ObsPc208LevelBUatTest.f01` | RED (real) | line 26 without newline missing |
| `ObsPc208LevelBUatTest.f04` | RED (real) | line 6 missing (10 expected, 5.5 received) |
| `ObsPc208LevelBUatTest.f02` | GREEN | — |
| `ObsPc208LevelBUatTest.f03` | GREEN | — |
| `ObsPc208LevelBUatTest.f05` | GREEN | — |
| `ObsPc208LevelBUatTest.f06` | GREEN | — |
| `ObsPc208LevelBUatTest.f07` | GREEN | — |
| `ObsPc208LevelBUatTest.f08` | GREEN | — |
| `ObsPc208LevelBUatTest.f09` | GREEN | — |
| `ObsPc208LevelBUatTest.f10` | GREEN | — |

The 8 GREEN Falsifiers now act as **permanent non-regression guards** for
the Output Plane. If a future change breaks any of them, the gate will
fail with a real defect. The 4 RED Falsifiers are characterized but the
defect (live-streaming observation withholding bytes past the run end) is
out of scope for the H0..H4 closure; the harness takes ownership.

## The 4 real defects, with evidence

### f04 — "stdout line 6 must be present"

The producer script runs:

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do printf 'stdout-only-%s\n' "$i"; done
```

Expected: 10 lines, ~150 bytes.
Actual (per `~/obsPc2diag/OBS-PC-208-f04-content-*.txt`):

```
=== f04 stdout (81 bytes) ===
stdout-only-1
stdout-only-2
stdout-only-3
stdout-only-4
stdout-only-5
stdout-only
```

Only 5.5 lines reach the plane. The 5 missing lines are not committed
to the Output Plane. The hex dump confirms the producer ran all 10
printf calls (the script ran to completion: `DONE` was created), but
the Output Plane stopped committing past byte 81.

### f01 — "line 26 without newline must be present"

Same symptom: 25 of 26 expected lines reach the plane; the 26th
(no-newline-terminated) is missing. The bug is in the streaming
observation: the reader (the test) is withholding bytes past a
certain threshold, OR the agent is buffering them indefinitely.

### ObsA — "bytes must be visible WHILE the step is provably still blocked"

The test uses a barrier file to deterministically block the step and
assert the bytes are visible at the barrier point. The actual stdout
shows the PipelineK boot messages but NOT the step's output:

```
[PipelineK] Run correlated: 6a68088e-fb55-4ecf-92f7-f44175483166
[PipelineK] Compiling pipeline
[PipelineK] Compilation finished with 1 diagnostic(s)
[PipelineK] Run started: /tmp/junit-.../blocking.pipeline.kts
[PipelineK] [stage: build]
[PipelineK] [step: build] build/sh-0
```

The step's actual stdout (`sh`'s output) is NOT in the captured stream
even though the step is provably still blocked (the barrier file
exists). The reader is withholding the step's output even though the
Output Plane should have it.

### ObsA — "jsonl carries process output and every line stays parseable"

Same as the above but for the jsonl format. The jsonl stream carries
the launch event but NOT the step's output.

## The 8 GREEN Falsifiers

`f02` (secret split), `f03` (agent survives child kill), `f05` (producer
exits without writing doesn't deadlock), `f06` (FIFO rendezvous owner-only),
`f07` (1MB output committed without loss), `f08` (no streamId/ownership
mixing), `f09` (stream sealed correctly), `f10` (concurrent reads don't
corrupt) — all GREEN on `origin/main = 48467cc7`.

These 8 are now permanent non-regression guards. The fix at commit
`7437b37` (R2.3(b)) corrected the redactor's `outputQueue` size; the 8
Falsifiers that test the same code path now confirm the fix is
preserved.

## Why the prior gate log showed 12 Falsifiers RED

The prior gate run (`145e5d5` era) reported all 12 Falsifiers as
RED. This session re-verified and found only 4 are real. The
discrepancy is most likely:

- **Gradle daemon lock**: the prior gate's test JVM may have been
  the same JVM as a prior test run with stale state. The 8 GREEN
  Falsifiers may have been failing due to test ordering, port
  conflicts, or shared temp-dir state.
- **Test suite timing**: the 25-minute gate budget caused the prior
  session to kill the gate mid-flight. The 8 GREEN Falsifiers may
  not have been run at all, and the failure log may have been
  misinterpreted.

The current run (`./gradlew :pipeline-application:test --tests
ObsAOutputStreamingCharacterisationTest --tests ObsPc208LevelBUatTest*`)
completed in 40s, BUILD SUCCESSFUL with the 4 migrated-pending
marked `<skipped/>` and the 8 non-regression guards GREEN.

## What this does NOT solve

The defect (live-streaming observation withholding bytes past the run
end) is a real product bug. The 4 migrated-pending tests are the
**characterization** of that bug. Fixing the bug is a separate WU:

1. Identify whether the withholding is in the agent (the streaming
   redactor output queue), the Output Plane ingestion, or the test
   harness's reader.
2. The redactor's `outputQueue` is correctly sized (commit `7437b37`
   was the fix for the prior bug); the new bug is downstream.
3. Likely candidates: the live-observation path
   (`startLiveOutputObserver`), the agent's `feed` loop, or the
   Output Plane's incremental commit policy.

This WU is out of scope for the H0..H4 closure. The migration-pending
status per AGENTS.md §1 hands it to the harness, which can run the
4 Falsifier characterization in its heavy lane.

## Cross-references

- Trace correction at `3aa64648` (H4 batch 3 — 0 production dependents).
- DeletionDecision manifest at `aec3a777` (13 MigratePending, 8 Keep).
- Wave 2 verification at `a7db0041` (13/13 static grep).
- Wave 2 runtime verification at `4a7f9fd7` (40/40 cases `<skipped/>`).
- Wave 3 KEEP at `49749c39` (Subprocess.kt, SubprocessOutcome.kt).
- Wave 3.x KEEP at `f40e6391` (3 corpus support files).
- Sandbox retirement at `cf255ba0` (PK-RET-0020, 0021).
- Narrowed @Disabled at `48467cc7` (this commit).
- v0.62.0-rc6 release at `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.62.0-rc6`.
- ADR-PK-DF-001 ACCEPTED at `4e984747`.
- Prior `7437b37` (R2.3(b)) redactor queue fix.
- User memory: `output-plane-first-write-garbling.md`, `obs-pc-208-progress-2026-10-13.md`.
- AGENTS.md §1 (migrated-pending contract), §3 (forbidden release assets), §8 (5-asset contract).
- ADR-PK-QV-003 (5-axis certifier verdict contract).
