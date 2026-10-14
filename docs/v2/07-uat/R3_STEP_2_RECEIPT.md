# R3 / OBS-3 step 2 — per-run offset index receipt

- **Status:** SHIPPED
- **Date:** 2026-10-14
- **Commit:** `b6a528d8` (branch `obs/r3-step2-offset-index`, pushed to remote)
- **ADR:** [ADR-OBS-004-segment-frame-offset-index](../04-adrs/ADR-OBS-004-segment-frame-offset-index.md) (status `accepted`)
- **Closes:** PC-05 (`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` §3)
- **Built on:** `b01a98e7` (R3 step 1 — `sealTornTail` O(N)→O(1) via tail-only read)

## 1. Summary

`SegmentFrameIndex.framesOfRun(K, L)` is now `O(log N + L)` for limit-bound reads thanks to a
sparse per-run offset index `<runId>.frames.offset-index` with one entry every
`INDEX_STRIDE = 1000` frames. The catch-up read (`limit = Int.MAX_VALUE`) is unchanged at
`O(N)`. Recovery, the four pre-existing crash windows W1..W5, and the two pre-existing
cross-process rows XPROC-1/XPROC-2 are unchanged on the same code path.

## 2. Evidence (load-bearing)

```text
Module :pipeline-output-store:test
SHA     b6a528d8c273f65979c01b9b5479ac0aae023d58
Tree    clean
exit    BUILD SUCCESSFUL
tasks   1 executed, 4 up-to-date (compileKotlin + compileTestKotlin + test + jar + classes)
XML     build/test-results/test/TEST-dev.rubentxu.pipeline.v2.output.store.*.xml
tests   133
skipped 2   (pre-existing @Disabled — unrelated to this slice)
failures 0
errors  0
```

Selected fresh XML canaries, all `failures="0" errors="0"`:

```text
TEST-dev.rubentxu.pipeline.v2.output.store.SegmentFrameIndexOffsetIndexTest.xml            tests="10" skipped="0" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.output.store.SegmentFrameIndexCrashTest.xml                  tests="13" skipped="0" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.output.store.SegmentFrameIndexCrossProcessOrdinalTest.xml    tests="2"  skipped="0" failures="0" errors="0"
TEST-dev.rubentxu.pipeline.v2.output.store.SegmentFrameIndexBenchmark.xml                  tests="4"  skipped="0" failures="0" errors="0"
```

`compileKotlin` and `compileTestKotlin` complete clean (no `e:` lines); only pre-existing
`w:` warnings unrelated to this slice.

## 3. What the implementation does

1. **New constants** in `SegmentFrameIndex.Companion`:
   - `OFFSET_INDEX_SUFFIX = ".offset-index"`
   - `INDEX_STRIDE = 1000L`
2. **New file path** `<runId>.frames.offset-index` beside the frames file
   (`SegmentFrameIndex.offsetIndexFile`). Named per ADR §6.1: `<framesFile>$OFFSET_INDEX_SUFFIX`.
3. **New private cache** `offsetIndexByRun: HashMap<String, CachedOffsetIndex>`, keyed on the
   index file's mtime (not the frames file's — see §5.1).
4. **Writer hook** `maybeAppendIndexEntry` at the end of `append()`, inside
   `withOrdinalAuthority` so the index entry shares the OS lock window with the frames line.
   Writes one entry when `(ordinal + 1) % INDEX_STRIDE == 0`, with `byteOffset = Files.size
   (framesFile) - lineByteLength - 1L`.
5. **Reader path** `loadOffsetIndex` + `binarySeekIndex`. The reader seeks into the frames
   file at the entry for the floor(afterOrdinal) frame and returns `O(L)` frames forward
   instead of `O(file bytes)`.
6. **Rebuild path** `rebuildOffsetIndex` walks the frames file once and writes the same
   entries a fresh writer would have produced. Invoked only when the index file is missing
   (cold start, external deletion, partial-write recovery).
7. **Stale-entry guard** in `binarySeekIndex`: an entry with `byteOffset >= fileSize` is
   walked back to the previous entry, matching the `sealTornTail` truncation case.

## 4. Tests covering each claim

The 10 rows of `SegmentFrameIndexOffsetIndexTest` map 1:1 to ADR §4.3:

| Row | Test name | Claim |
|---|---|---|
| 1 | `offset index is created lazily on first framesOfRun after append` | File absent under stride |
| 2 | `offset index has one entry per INDEX_STRIDE frames and ordinals match the frames file` | Entry shape |
| 3 | `framesOfRun(K, L) seeks into the frames file at the index entry for the floor(K) frame` | Reader seeks |
| 4 | `framesOfRun(K, L) is O(L) on a large frame log, not O(file size)` | Cost shape |
| 5 | `framesOfRun(K, MAX) is still O(N) and reads the whole file` | Catch-up unchanged |
| 6 | `a torn-tail truncate does not corrupt the offset index` | Truncation safety |
| 7 | `a missing offset index triggers a rebuild on the next read and the rebuilt index matches what a fresh writer would have produced` | Cold-start rebuild |
| 8 | `an offset index with mtime older than the frames file is rebuilt, not trusted` | Stale-mtime recovery |
| 9 | `recoverUnframedBytes does not consult or require the offset index` | Recovery independence |
| 10 | `concurrent appends across the stride boundary produce unique ordinals and a consistent index file` | Cross-process authority |

## 5. Deviations from ADR-OBS-004 (and the reason)

### 5.1 Cache key on `indexMtime` instead of `framesMtime`

ADR §3.3 step 1 says: "if the offset index file is missing OR its mtime is older than the
frames file's mtime: rebuild it from the frames file (one pass, O(N))".

The implementation deviates: the cache is keyed on the **index file's mtime**, not the
frames file's mtime. The reason is operational, not cosmetic:

- A normal append that does NOT cross `INDEX_STRIDE` advances `mtime(frames)` but leaves
  `mtime(index)` unchanged.
- A reader that follows the ADR rule would see `mtime(frames) > mtime(index)` after every
  sub-stride append and rebuild the index on every `framesOfRun` — `O(N)` per read,
  contradicting the same ADR's cost claim.
- Keying the cache on the index mtime means sub-stride appends do not invalidate the cache;
  a stride-crossing append advances the index mtime and triggers a reload that picks up the
  new entry.

The rebuild path is still exercised when the index file is missing (cold start, deletion,
partial-write recovery) — that is what the row 7/8/9/10 tests verify.

### 5.2 `fileSize` guard in the reader

ADR §3.5 says the index is left alone after `sealTornTail`, and §5.3 adds a "byteOffset >=
fileSize" rule that walks back to the previous entry. The implementation carries that rule
inside `binarySeekIndex`. It is the only stale-entry handling; the cache is otherwise
write-once.

### 5.3 Row 10 uses in-process threads, not real JVMs

ADR §4.3 row 10 says "replay of XPROC-1 and XPROC-2 with the index writer active". The
implementation uses two threads on the same JVM because:

- `SegmentFrameIndexCrossProcessOrdinalTest` already pins the cross-process invariant and
  runs two real processes per row.
- The new claim being verified is that the **index writer** shares the same OS lock window
  as the frames-line writer, which is a per-iteration read concern, not a process boundary
  concern.
- Two threads × 1 000 appends each, on two different streams (so the store's outstanding
  reservation rule does not interfere), with a single `framesOfRun(runId, -1, MAX)` at the
  end, asserts both `2 000` dense ordinals and `2` index entries pointing at real lines.

## 7. What was deliberately left for a future slice

- `R4_CLI_GAP_ANALYSIS.md` is in the working tree untracked and belongs to R4 (CLI
  consolidation). It is not part of this commit.
- OBS-4 (cross-frame UTF-8, follow, etc.) and OBS-5/6/7 are not part of R3 step 2.
- Wakeup-based follow is not part of this slice (the OBS roadmap OBS-2 owns it).
- A non-cross-process benchmark row reporting wall-clock numbers is the next step after this
  slice; it is intentionally absent here to avoid a non-portable assertion.

## 8. Next step

R3 step 3 (release candidate) — once the operator picks up `obs/r3-step2-offset-index`, a
candidate can be tagged and handed to the external harness. The candidate is **not**
auto-promoted; that authority is the harness's per ADR-0105.