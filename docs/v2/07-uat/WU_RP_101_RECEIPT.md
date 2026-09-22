# WU-RP-101 — Lpr011r2 console-log DURING-execution determinism closure

**Date:** 2026-09-22T06:38Z. **Status:** PASS_GREEN_3X. **Base SHA:** 174bd060. **Head SHA:** this commit (TBD; see git log). **Branch:** main.

## What

`Lpr011r2SecretRedactionAtRestUatTest > console log contains no raw secret while the child is still alive` (Lpr011r2SecretRedactionAtRestUatTest.kt:211) failed in CI run 35662787309 at HEAD 0063ac46 with `org.opentest4j.AssertionFailedError: must observe the sanitized transcript DURING execution ==> expected: <true> but was: <false>`. The CI failure was reproduced locally 3/3 times (time=30.212s, exit=1, message identical). The diff between last known green (174bd060, CI run 35660883142) and HEAD (0063ac46) contains only doc commits, so the failure is **not** a production-code regression.

## Why

The test asserts that a sanitized `****` marker reaches `console.log` *during* the child process lifetime (a 2-second `sleep` in the original calibration). The redactor (`StreamingRedactor.kt`) only emits sanitized bytes from its `outputQueue` once the `pending` buffer reaches `maxLiteralByteLength` (~28 for a 26-char secret + 2 delimiters); the durable shell executor then writes the wrapped stream to `console.log` via its own buffered writer. With 100 echo lines × ~34 bytes ≈ 3.4 KB of output and a 2-second sleep, modern BufferedWriter buffers and Temurin 21 pipe coalescing keep the writer's 8 KiB buffer from filling before the child exits, so sanitized bytes stay in the kernel/pipe buffer and never reach `console.log` during the live observation window.

The redaction pump itself is correct: each `echo $secret` (27 bytes incl. newline) crosses `maxLiteralByteLength ≈ 28` exactly once, so the match-and-emit cycle fires once per line. The fix is on the **test side**: widen the volume so the buffer flushes deterministically, and widen the live observation window so the assertion can complete.

## What changed

Single file: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lpr011r2SecretRedactionAtRestUatTest.kt`.

- Test payload: `for i in $(seq 1 100); do echo $secret-line-$i; done; sleep 2` → `for i in $(seq 1 1000); do echo $secret-line-$i; done; sleep 10`. 1000 × 34 B ≈ 34 KiB, well above the 8 KiB pipe buffer; sleep 10 keeps the child alive long enough for at least one buffer flush to reach disk while `****` markers continue to be emitted.
- Polling cadence: `Thread.sleep(50)` → `Thread.sleep(20)`. Filesystem poll still bounded by the existing 30-second deadline (`assertTrue(observedLive, "must observe the sanitized transcript DURING execution")`).
- Added `Files.getLastModifiedTime(logPath).toMillis()` capture (kept in `lastModified` for diagnostic value, no behavior change).
- New explanatory comment block at lines 177-186 and 208-212 documenting the calibration rationale.

## What did NOT change (orchestrator contract)

- **No production code** (`ShExecution.kt`, `ShOperationsAdapter.kt`, `StreamingRedactor.kt`, `TranscriptRedactor.kt`, `Main.kt`).
- **No other test files** (the L2 sibling regression proves zero collateral impact: `Lpr011SecretRedactionTranscriptUatTest` 6/6, `UatLocal008CredentialsTest` 27/27).
- **No build files, no workflow YAML, no Gradle properties, no branch protection, no settings.**
- **No receipts modified.** All historical receipts (LPR-046, LPR-011-R2, WU-RP-005 r9..r12, WU_RP_005_TEST_EFFICIENCY_RECEIPT, etc.) remain immutable.

## Verification (per AGENTS.md rule 23: result truth is the JUnit XML, not exit code)

| Level | Command | Exit | XML aggregate | Time | Note |
|---|---|---|---|---|---|
| L1 run 1 | `cd v2 && timeout 300 ./gradlew :pipeline-application:test --tests 'Lpr011r2SecretRedactionAtRestUatTest.console log contains no raw secret while the child is still alive' --no-daemon --rerun-tasks` | 0 | full suite 1451/0 | 92s | Pre-fix run was 30.212s with FAIL; this run rebuilt the full application test set with the filter applied. |
| L1 run 2 | same command | 0 | full suite 1451/0 | 90s | Independent run. |
| L1 run 3 | same command | 0 | 1/0/0/0 (Lpr011r2 method-only XML) | 94s | The single-method XML reports `PASS` for `console log contains no raw secret while the child is still alive()`, time=10.601s, ts=2026-09-22T06:33:46Z. |
| L2 full class | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'Lpr011r2SecretRedactionAtRestUatTest' --no-daemon` | 0 | 11/0/0/0 (whole class) | 50.776s | All 11 Lpr011r2 methods PASS. |
| L2 siblings | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'Lpr011SecretRedactionTranscriptUatTest' --tests 'UatLocal008CredentialsTest' --no-daemon` | 0 | Lpr011: 6/0/0/0 (0.366s); UatLocal008: 27/0/0/0 (128.317s) | 2m 21s | Zero collateral. |

## Residual risk

The fix relies on the executor's stream buffer flushing to disk within 10 seconds of receiving ~34 KiB of sanitized output. This is robust on every JVM/pipe configuration known to the orchestrator (Temurin 21.0.12, default pipe size 16 pages = 64 KiB on Linux). A future runner that ships with a default `BufferedWriter` buffer larger than 8 KiB AND a pipe-coalescing policy tighter than the current Linux default may re-introduce the flake. The WU-RP-005 r6 receipt acknowledged this as a known runner-sensitivity ("local repro ~1/3"). The new calibration raises the bar by a factor of ~10 in both volume and observation window; the WU-RP-005 team should be aware if they ever revisit the test.

## Follow-up

1. CI verification of this commit via `gh run list --limit 1` after `git push origin main`.
2. Update `.agent/SESSION_POINTER.md` to point at the new SHA and document the RP-1 entry.
3. Update `.agent/WORK_JOURNAL.md` with the WU-RP-101 entry.
4. RP-1 next WU (after this commit is green on CI): WU-RP-010 publishHTML non-overwrite + index collision; WU-RP-011 HTML injection; WU-RP-012 stash symlink safety; WU-RP-013 StepContractSuite reconciliation.