# WU-LPR-043 Receipt — Console Hot Path + Observation Gate

**Status:** DONE
**Date:** 2026-09-18
**Scope:** decouple sink invocation from process pump threads in
`ProcessDurableTaskRuntime` + observation-gate assessment.

## What changed

`ProcessDurableTaskRuntime.execute` now routes chunks through a bounded
in-coroutine `Channel<OutputChunk>` (capacity 4096) drained by a dedicated
renderer coroutine:

- Pumps (`drain`) only `send()` chunks; the user sink (`append`) runs on
  the renderer coroutine, no longer bridged into the pump thread via
  `runBlocking` per chunk.
- Order preserved per pump; stream identity unchanged.
- Memory stays O(capacity), not O(total output).
- Lifecycle: pumps await EOF -> channel close -> renderer join before
  returning the exit code. Cancellation path unchanged (destroyTree in
  finally).

## Overload policy (honest)

A slow consumer now slows only the renderer. Past channel capacity the
producers co-operate (suspend on send) instead of dropping: pipeline
output is lossless. Producers can still couple beyond capacity; that is
the documented bounded + lossless policy (no infinite non-blocking
buffer is claimed).

## Observation gate scorecard (criteria vs evidence)

| Criterion | Status | Evidence |
|---|---|---|
| SQLite sequence survives reopen | PASS | WU-LPR-041 (D3 maxAfter=1000 dups=0) |
| Event commits << event count | PASS | WU-LPR-042 batched COMMITs (per_event 11-16µs) |
| Persistent connection | PASS | WU-LPR-042 single writer connection |
| Memory O(configured buffers) | PASS | 8 KiB chunks; 100 MiB run OK (P1) |
| Completion decoupled from renderer | PASS | WU-LPR-043 channel; P4 slow sink ms=8, task completes |
| Slow consumer isolation | PASS | events D4 (10µs/event with slow drain); output P4 |
| Streaming secret redaction | **NOT DONE** | P5: no redaction on output sink; tracked as follow-up (needs transcript/redaction seam work, PRODUCT scope) |
| No event/console large-payload duplication | PARTIAL | EchoOutputCaptured still persists content; removal gated on compat/parity proof per trunk law (tracked WU-LPR-042-FOLLOWUP) |
| Outcome/journal/fingerprint identical across observation configs | PASS | all 187 runtime tests + 139 events tests green across configs |

Gate verdict: **6 PASS / 1 NOT DONE / 1 PARTIAL** — the two open items
are explicitly out of the observation/performance scope (they are
product semantics: redaction seam and duplication removal with parity
proof), not performance debt. The performance plane (WU-LPR-041/042/043
core) is closed.

## Performance evidence (fresh XML)

| Metric | Before train | After train |
|---|---|---|
| event append/event | 476-513µs | 11-16µs |
| 100k events wall | 47.7s | 1.4s |
| slow sink impact (P4, 1 MiB) | coupled drain | ms=8, decoupled |
| 100 MiB stdout (P1) | 141ms | 194ms (no regression) |
| 8 parallel 10 MiB (P3) | ~290ms each | ~318ms each (no regression) |

## Pre-existing red found and repaired (not caused by this train)

`CoreDeleteDirStepUnitTest` and `CoreIsUnixStepUnitTest` counter pins
still asserted the 2-2-2 shape after WU-LPR-301/G5 converged the
burn-down to 0/0/0 (commit 022d38a8, pre-dating this train, left those
two stale). Repair follows the project's own pattern: old pin preserved
verbatim as `@Disabled` historical snapshot + new `0-0-0` pin asserting
the post-WU-LPR-301-G5 invariant. No assertion weakened; the new pins
are strictly stronger (empty set + empty metadata table + zero residual
dispatcher sources). Base-vs-head evidence: worktree at train base
881829fc reproduced ALL THREE failures identically (CompatibilityCorpus
fixture05 + fixture14, CoreArchiveArtifactsStepUnitTest 2-2-2 pin) —
pre-existing reds, not train regressions. The two counter pins were
repaired in this WU; the two corpus fixture failures remain OPEN as the
known pre-existing compatibility/UAT failures family (out of observation
train scope, tracked in the prior checkpoint).

## Verification

- L4 `:pipeline-step-sdk:runtime:test`: 187/187 green.
- L4 `:pipeline-events:test`: 139/139 green (from WU-LPR-042 gate, unchanged since).
- L4 `:pipeline-application:test`: see train checkpoint receipt for the full-round result.
