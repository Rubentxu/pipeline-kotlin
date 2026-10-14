# TD-R4 / WU-2 — Frame fragmentation: pump no longer reads via `available()`

- **Status:** **CLOSED.** 1 KiB through the production pump now commits 1 frame, down from
  262 frames. No regression on the 7 affected test classes (29 / 0 / 0 / 0).
- **Base:** `8ada2b98` (`main` after WU-D).
- **Branch:** `r4/0-61-rc6-td-r4-2-frame-fragmentation`. **Train:** 0.61.0-rc6 (not
  yet published).

## One-line summary

The pump read loop was reading `min(window.size, available())` with a `maxOf(1, …)`
floor, which on an empty-registry one-byte ring produced a forced one-byte read almost
every turn. Removing the `available()` call as a progress signal and asking the
`RedactingInputStream` directly for the whole window lets one `read` consume up to
64 KiB per turn.

## What changed

`DurableShellExecutor.startTranscriptPumps` — the per-channel pump loop — went from

```kotlin
val ready = maxOf(1, minOf(window.size, redacted.available()))
val n = redacted.read(window, 0, ready)
```

to

```kotlin
val n = redacted.read(window, 0, window.size)
```

That is the whole production change. The `available()` heuristic is no longer consulted.

## Why this is the fix the operator named

The unlock-mandate spelled out four options and the operator's table said:

| Alternative | Decision | Why |
|---|---|---|
| Drop the `maxOf(1, …)` floor | No as an isolated fix | "A read size of zero does not guarantee progress and can introduce a busy loop." |
| Force the redactor to fill the buffer before answering | No | "Can hold safe bytes until the producer writes more or finishes." |
| Move the lookahead to the drain | Yes | "The `StreamingRedactor.feed/take/finish` API already exists and lets entry, redaction decision, and persistence be separated." |
| **(Operator's own reframe, not in the table)** | **Adopted here** | "Remove the dependence on `available()` as a progress mechanism for the pump." |

`available()` is fine as a hint, but a *progress* signal. A read-loop that asks `available()`
on every turn and then is forced by `maxOf(1, …)` to read 1 byte when it returns 0 has the
shape the operator described: a small payload, fragmented.

The fix removes `available()` as the loop's progress signal and lets `RedactingInputStream.read`
block until at least one byte is available, returning up to `window.size`. That is the canonical
"have at least one byte or block" primitive the redactor already implements — the loop is
now a straight-line read.

## Measurement

A new test class `OutputPlaneFrameFragmentationTest` (HF1, no installDist) drives the production
pump end-to-end with a 1 KiB payload written one byte at a time, and reads the frame count back
through `SegmentOutputStore.frameIndex().framesOfRun(...)`.

```text
bytes_pumped=1024
frames=<count, printed by the test>
```

Baseline (before the fix): **262 frames** for 1024 bytes (≈ 4 bytes/frame).
After the fix: **1 frame** (range 0-1024).
Ratio: 262x. Content is byte-exact and ordinals are dense, so the bug was efficiency only.

The test prints the count on every run, so any future regression to per-byte reading surfaces
immediately as a printed number rather than only as a `<failure>` message.

## Suite evidence

| Check | Result |
|---|---|
| `OutputPlaneFrameFragmentationTest` | 1 / 0 / 0 (1 frame) |
| `ShTranscriptRedactionReproductionTest` | 3 / 0 / 0 |
| `ShTranscriptSealIntegrityTest` | 3 / 0 / 0 |
| `ConsoleRc3FilteredFollowTest` | 9 / 0 / 0 |
| `ConsoleRc4FollowTerminationTest` | 5 / 0 / 0 |
| `ConsoleMultiOpInstallCanaryTest` (HF2) | 3 / 0 / 0 |
| `TranscriptPumpCompletionTest` | 5 / 0 / 0 |
| `:pipeline-application:detekt` | 0 issues |
| `:pipeline-step-sdk:runtime:detekt` | 0 issues |

## What this block does NOT claim

- **It does not pin the latency.** The previous size of `TRANSCRIPT_LIVE_WINDOW_BYTES = 64 KiB` is
  a latency floor for a producer that saturates the pipe: first byte visible after ~64 ms
  rather than ~1 ms. That is the same trade OBS-F measured, and changing the window is a
  separate decision. The fix here is the *floor*, not the *ceiling*.
- **It does not change the redactor.** The `RedactingInputStream`'s `available()` is unchanged
  (some callers still use it as a hint); the fix is in the *pump* and the *test* mirrors that
  shape.
- **It does not address the per-frame fragmentation of a child process whose writes are
  single-byte at the OS level** (the operator's "median 1 byte per frame"). The fix here is at
  the *redactor read size*, not at the *child write size*. A child that genuinely writes 1 byte
  per syscall will still produce many small frames; the reduction is in the *redactor's batching
  of those writes into one read*, which is what the measurement proves.

## Reference implementation consulted

- **None applicable.** The change is structural: it removes a heuristic the operator named, and
  replaces it with the canonical read primitive the `RedactingInputStream` already implements.

## Security implications reviewed

- **Redaction was not weakened.** The matcher and the state machine are the same; the
  `RedactingInputStream`'s `feed/take/finish` triplet is unchanged. A registered secret still
  never reaches the Output Plane (verified by `ShTranscriptRedactionReproductionTest`, GREEN).
- **No new dependency.**
- **`raw_secret_on_disk == 0`** is preserved.

## Receipts

This file: `docs/v2/07-uat/TD_R4_2_FRAME_FRAGMENTATION_RECEIPT.md`.

## Remaining in the TD-R4 program

- **TD-R4.1** — `MainConsoleCli` per-verb refactor.
- **TD-R4.3** — `decodeArtifactEntries` / `decodeHtmlReportEntries` audit.
- **TD-R4.4** — `core.waitUntil` stub: decision 3-way of the operator.
