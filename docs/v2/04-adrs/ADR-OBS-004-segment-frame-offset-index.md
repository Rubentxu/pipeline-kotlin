# ADR-OBS-004 — Per-run offset index makes `framesOfRun` O(L) on limit-bound queries

- **Status:** accepted
- **Date:** 2026-10-14
- **Accepted:** 2026-10-14, R3 / OBS-3 step 2 implementation landed
- **Owner decision:** owner of OBS-3 (R3 step 2 of the Output-Plane perf train)
- **Reconciles:** `ADR-OBS-001` (Output Plane is the durable byte authority), `ADR-OBS-002` (recovery is exclusive), `AGENTS.md` §STEP CONSTITUTION · StepDescriptor (registry metadata is the source of truth, no concrete-key branching)
- **Built on:** R3 step 1 commit `b01a98e7` (sealed tail read is now O(1))
- **Implements:** OBS-3 PC-05 (`docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` §3)
- **Evidence (load-bearing):** `SegmentFrameIndexBenchmark.kt`, `SegmentFrameIndexCrashTest.kt`, `SegmentFrameIndexCrossProcessOrdinalTest.kt`

---

## 1. Context

### 1.1 The half of R3 that is done

R3 step 1 (`b01a98e7`) made `SegmentFrameIndex.append` O(1). The previous `sealTornTail` re-read the
whole frames file via `BufferedReader`; the new path reads at most `TAIL_READ_BYTES = 4 KiB` from the
end (`SegmentFrameIndex.kt:341–399`). A 1 GiB / 10⁷-frame index no longer pays N² on cumulative
appends; the worst case is now four kilobytes of I/O plus one line of decode per write.

That closes OBS-3 PC-04 ("escritura e indexación costosas con ventanas pequeñas") and leaves PC-05
unresolved.

### 1.2 The half that is not done — and the numbers

`framesOfRun(runId, afterOrdinal=K, limit=L)` still calls `readFrames(runId, sealTornTail = false)`
(`SegmentFrameIndex.kt:123–132`), and `readFrames` reads the **whole** log via `BufferedReader`
(`SegmentFrameIndex.kt:408–429`), then filters and takes. Cost is `O(file bytes)` regardless of `L`.

For a run that has streamed for an hour at 3 kHz, the file is in the 10⁶–10⁷ frame range. The Live
Follow consumer in OBS-3 polls the index on every tick. A tick rate measured against the segment
follow path (`SegmentOutputFollower.kt`) is 40 Hz; a 60 s read per tick means every tick falls
60 ticks behind. That is not a follow, it is a periodic back-fill, and the operator's "follow must
catch up by reading dead history" complaint in the R3 brief is exactly this.

`SegmentFrameIndexBenchmark.kt` (`baseline — framesOfRun cost grows linearly with N`) demonstrates
the curve at small sizes; the operator's brief pins the production order of magnitude as
`1 GiB / 10⁷ frames / 60+ s per poll`. The benchmark stays in place after the fix to prove the
"after" shape (`O(limit)` for limit-bound reads, `O(N)` only for catch-up reads where `L == MAX`).

### 1.3 What the user asked for, and what this ADR is

> Corregir `SegmentFrameIndex.framesOfRun()` y el coste de `append()/sealTornTail()` para evitar
> recorrer todo el histórico en cada consulta o commit.

`append`/`sealTornTail` is R3 step 1 (`b01a98e7`). This ADR is the `framesOfRun` half, plus the
two other readers that today pay the same `readFrames` tax for the same reason
(`recoverUnframedBytes` at `SegmentFrameIndex.kt:170`, `loadOrdinals` at `SegmentFrameIndex.kt:295`).

### 1.4 The principle this ADR inherits

`AGENTS.md` §STEP CONSTITUTION: registry metadata is the source of truth, and the engine never
branches on concrete StepKey. The same principle applies here: per-run durability metadata lives
on a sibling file the index owns, and the index's public API takes its decisions from that
metadata rather than from concrete runId strings. There is one offset-index file shape, one reader
for it, and one updater; the runId is a parameter, not a switch.

---

## 2. Decision

### 2.1 Primary option (CHOSEN) — Sparse per-run offset index

A per-run file `<root>/frames/<runId>.frames.offset-index`, holding one entry per `INDEX_STRIDE`
frames, in the form `ordinal\tbyteOffset\n`. The index reader does a binary search on the entries
to locate the highest `ordinal ≤ K`, then `seek(byteOffset)` on the underlying frames file and
reads forward until `limit` frames past `K` are collected or the file ends.

```text
<root>/frames/<runId>.streams       — declared streams per run (existing)
<root>/frames/<runId>.frames        — the frames themselves, in publication order (existing)
<root>/frames/<runId>.frames
             .offset-index          — NEW: sparse (ordinal, byteOffset) entries
<root>/frames/<runId>.authority     — cross-process ordinal lock (existing)
```

Costs:

| Axis | Cost |
|---|---|
| Read of `framesOfRun(K, L)` | `O(log N)` seek + `O(L)` scan + filter; total `O(L)` when `L ≪ N` |
| Catch-up read (`L == MAX`) | `O(N)` (unchanged) |
| Append | `O(1)` amortised; an index entry is appended every `INDEX_STRIDE` frames, and only when the boundary is crossed |
| Memory for the index | `O(N / INDEX_STRIDE)` entries × ~16 bytes = `~16 KB` at `N = 10⁶`, `~160 KB` at `N = 10⁷` |
| Recovery cost | unchanged — `recoverUnframedBytes` still reads frames; it gains nothing from the index and pays nothing extra for it |
| Cross-process ordinal authority | unchanged — `<run>.authority` `FileLock` continues to mediate ordinal allocation |

`INDEX_STRIDE` is `1000` for the first implementation, revisited at the OBS-3 measurement gate.

### 2.2 Backup option — cursor in the public API

`framesOfRun(afterOrdinal=K, limit=L, fromByteOffset=N)`. The caller passes the byte offset it
finished at; the library seeks there. Zero on-disk cost, zero index file.

Rejected: the cursor is caller-owned state, and the OBS-3 reader side is across processes (the
agent writes, the CLI reads, the SDK reads in a third process). A library without a durable cursor
forces every consumer to invent its own, and three independent cursors are three opportunities to
disagree on where the next read starts. It also makes "first read after a restart" the slow case
on every CLI invocation, which is the wrong shape for a follow tool that exists to be cheap on
restart. Recorded as the cheapest fallback if the primary option proves impossible to keep in sync
under crash; see §5.4.

### 2.3 Other options, recorded and rejected

**In-memory cache** (`HashMap<runId, TreeMap<ordinal, byteOffset>>`). First read after every
restart is O(N); the Live Follow starts cold on every CLI invocation. Rejected.

**Sealed-segment rotation** (frames file is rolled into segments every N frames; index maps
segment → ordinal range). Bigger change, cleaner answer, deferred to the OBS-3 measurement gate
if `INDEX_STRIDE = 1000` proves inadequate. The sparse index is the smallest change that meets
the cost target.

**Adaptive read with skip buffer** (open the file with a 1 MiB read-ahead, skip the buffer at
periodic frames, binary-search recorded positions). Zero on-disk cost; O(N) on first read; OS
page-cache dependent. Rejected: the cold-start cost is the case OBS-3 has to win, and page-cache
warmth is a property of the box, not the product.

---

## 3. Data flow

### 3.1 Index file format (normative)

```text
<root>/frames/<runId>.frames.offset-index

line := ordinal '\t' byteOffset '\n'
ordinal    := INT64   (decimal, same ordinal namespace as the frames file)
byteOffset := INT64   (decimal; offset from the start of the frames file where the
                       frame with `ordinal` BEGINS, in bytes; UTF-8)
```

- One entry every `INDEX_STRIDE = 1000` frames.
- Entries are append-only in ordinal order; the index is never re-ordered.
- The entry's `byteOffset` points at the **first byte of the line** that holds the frame, so
  `seek(byteOffset)` lands on the `o` of the ordinal and not mid-line.
- The index does not encode its own size, mtime, or a header. `mtime` and `size` are read from the
  filesystem; the frames file's own `size` is the upper bound for any `byteOffset` the index
  contains, and stale entries above that size are treated as missing.

### 3.2 Writer — on every `append`

The writer is `SegmentFrameIndex.append` (`SegmentFrameIndex.kt:100–121`). After the existing
sequence (seal torn tail → next ordinal → `appendLine(framesFile, …)`) returns, the writer
computes:

```text
if ((ordinal + 1) mod INDEX_STRIDE == 0) {
    byteOffset = bytesBeforeLine(framesFile, just-appended-line)
    appendLine(offsetIndexFile, "${ordinal}\t${byteOffset}")
}
```

`bytesBeforeLine` is the byte count of all complete lines before the one that just landed,
which is `filesize - len(line) - 1` (one trailing newline). It MUST be computed after the
`appendLine` returns, under the same lock that already serialises writes
(`SegmentFrameIndex.lock` at `SegmentFrameIndex.kt:72`), so the index can never claim an offset
that is not on disk.

The index entry is written with the same `force(false)` discipline as the frames line
(`SegmentFrameIndex.kt:434–450`): the per-line cost is one `fsync`, which is the existing
durability budget. One index entry every 1000 frames is `0.1%` extra `fsync`s over a run, which
is in the noise of the existing durability cost.

The writer MUST hold the cross-process ordinal lock (`<run>.authority`, already held by
`withOrdinalAuthority`) for the entire `append + index-update` window, so two processes cannot
write the frames file and the index file out of order. **This is non-negotiable:** see §5.1.

### 3.3 Reader — on every `framesOfRun(K, L)`

```text
1. if the offset index file is missing OR its mtime is older than the frames file's mtime:
       rebuild it from the frames file (one pass, O(N))
       (a missing index is a normal first-read condition; an out-of-date index is a recovery)
2. binary-search the in-memory entries (loaded once per JVM, refreshed when mtime changes)
   for the highest entry with ordinal <= K
3. if none exists: byteOffset = 0
   else:        byteOffset = entry.byteOffset
4. open the frames file; seek to byteOffset; read line-by-line; filter (ordinal > K);
   take(L); return
```

Cost: `O(log N)` for the seek (10⁷ frames at stride 1000 → 10⁴ entries → 14 comparisons), then
`O(L)` for the read. The catch-up case (`L == MAX`) still scans the whole file, and the ADR
documents that as the intended behaviour for catch-up reads; see §7 open question 2.

### 3.4 Recovery — on every `recoverUnframedBytes`

The recovery path (`SegmentFrameIndex.kt:161–194`) does not need the index. It needs to know each
stream's last indexed end, which it computes by iterating frames. The new offset index does not
change this; if anything, it makes the per-recovery iteration cheaper for the limit-bound case
(the iteration could be replaced by an index lookup), but that is an optimisation on top of this
ADR and is out of scope. **Recovery must continue to read the frames file directly** because it
must close gaps that the index cannot describe (frames between the last indexed entry and the
real end), and because the index does not survive a partial write (§5.3).

### 3.5 Truncate — on every `sealTornTail`

`sealTornTail` truncates the frames file back to the last whole line (`SegmentFrameIndex.kt:341–399`).
The offset index MUST be left alone after that truncation. Reasoning:

- The index only has entries every `INDEX_STRIDE` frames. A torn last line cannot coincide with
  an indexed ordinal (the writer wrote the index entry on a frame that landed whole), so the
  last index entry is by construction still valid: its `byteOffset` still points at a whole
  line.
- A torn line is at most ~120 bytes; the next `append` will land a whole line in the next
  ~100 bytes. The index's next entry is `INDEX_STRIDE` frames later, and the byte offset it
  records will be measured **after** the next `INDEX_STRIDE` frames land, not now.
- If the offset is stale after the truncate (a torn line removed bytes the index was pointing
  past), the reader's `seek` would land mid-line. Recovery: the reader treats `byteOffset >
  fileSize` as "missing", and falls back to the entry just before it. Same rule as a missing
  index.

This is the failure mode the reader already pays for, because `sealTornTail` is the only writer
that shrinks the file, and the next reader always sees the up-to-date size.

---

## 4. Consequences

### 4.1 What this decision locks in

- The cross-process ordinal authority at `<run>.authority` continues to be the OS-level guard
  against two writers assigning the same ordinal. The offset index is a **derived** structure
  under that lock, never a parallel authority.
- `framesOfRun(K, L)` is `O(L)` for `L ≪ N`. The catch-up case (`L == MAX`) remains `O(N)`,
  and the benchmark row `baseline — framesOfRun catch-up read is O(N)` stays green and unaltered
  to prove the contract.
- `recoverUnframedBytes` continues to iterate frames directly; the index does not become a
  recovery input, and a missing or corrupt index MUST NOT prevent recovery. The
  `SegmentFrameIndexCrashTest` rows W1–W5 must continue to pass on the SAME code path
  (`SegmentFrameIndex.recoverUnframedBytes` at `SegmentFrameIndex.kt:161–194`).
- The on-disk shape gains one file per run. The file is small (16 KB at 10⁶ frames,
  160 KB at 10⁷ frames) and is not materialised until the first frame is appended, so the
  empty-run case continues to pay nothing.
- The typed-error contract on `framesOfRun` is unchanged: malformed frames still produce
  a refusal-shaped result via `decode`, not an exception.
- No secret material is written to the index file. The index is `(ordinal, byteOffset)` pairs;
  the stream identity lives in the frames file at the recorded offset. The no-secret-in-disk
  invariant (ADR-OBS-001 §4) is preserved by construction.

### 4.2 What this decision does NOT change

- The single byte authority (ADR-OBS-001, ADR-M1 §D2). Bytes still flow through the Output Plane.
- Recovery is exclusive and scoped (ADR-OBS-002). No reader path reconciles durable state; the
  index reader is a pure read.
- The `BufferedReader`/`decode` contract (`SegmentFrameIndex.kt:408–429`) — torn-line refusal,
  truncation up to last good end — is unchanged.
- The four pre-existing crash windows (W1–W5) are not relaxed. The offset index is an
  *acceleration*, not a new authority, and the crash invariants hold independently of it.
- The OS lock pattern on `<run>.authority` (ADR-OBS-002 §2) is unchanged.

### 4.3 Tests added (acceptance)

The new test class lives in the same package as the index:

```text
SegmentFrameIndexOffsetIndexTest.kt
```

with these rows (one test per row, named for the claim):

1. `offset index is created lazily on first framesOfRun after append`
2. `offset index has one entry per INDEX_STRIDE frames and ordinals match the frames file`
3. `framesOfRun(K, L) seeks into the frames file at the index entry for the floor(K) frame`
4. `framesOfRun(K, L) is O(L) on a large frame log, not O(file size)` — the production
   benchmark row, re-tagged as a regression row.
5. `framesOfRun(K, MAX) is still O(N) and reads the whole file`
6. `a torn-tail truncate does not corrupt the offset index` — direct state arrangement,
   same pattern as `SegmentFrameIndexCrashTest` W3.
7. `a missing offset index triggers a rebuild on the next read; the rebuilt index matches
   what a fresh writer would have produced`
8. `an offset index with mtime older than the frames file is rebuilt, not trusted`
9. `recoverUnframedBytes does not consult or require the offset index` — runs recovery
   after deleting the index file and asserts recovery still closes the same gap as today.
10. `cross-process ordinal authority is preserved when the offset index is updated under the
    same FileLock` — replay of `SegmentFrameIndexCrossProcessOrdinalTest`'s `XPROC-1`
    and `XPROC-2` rows with the index writer active.

Rows 1–4 prove the O(L) cost claim. Rows 6–9 prove the index does not introduce a new failure
mode that crashes or recovery didn't already have. Row 10 proves the cross-process authority
is not weakened by an extra file.

### 4.4 Tests that must remain green, unmodified

The four pre-existing crash windows, in `SegmentFrameIndexCrashTest.kt`:

- W1 (`a declared stream with no bytes produces no frame`) — empty run, index does not exist.
- W2 (`bytes committed without a frame are closed by recovery`) — recovery path, index not
  consulted.
- W3 (`a torn frame line is discarded and the index stays writable`) — the truncation at
  `lastGoodEnd` must leave the index valid.
- W4 (`frames tile each channel without gaps or overlap`) — three appends is well under
  `INDEX_STRIDE`, so the index is empty and the path is unchanged.
- W5 (the structural claim that a frame cannot name uncommitted bytes) — orthogonal.

Plus the two cross-process rows in `SegmentFrameIndexCrossProcessOrdinalTest.kt`:

- `XPROC-1` (two JVMs appending sequentially never share an ordinal).
- `XPROC-2` (two JVMs released together do not hand out one ordinal twice).

Both rows exercise the production write path. With the offset index added, the rows MUST remain
green on the SAME code path; the ADR's row 10 above is the variant that proves the index writer
is included in the same lock window.

---

## 5. Risks and mitigations

### 5.1 Two writers race the frames file and the index file

If process A appends a frame and process B appends a frame in the same cross-process window, the
ordinal lock (`<run>.authority`) already serialises them. The offset-index update MUST happen
inside the same lock window — `withOrdinalAuthority` at `SegmentFrameIndex.kt:245–253` — so two
writers cannot interleave "frame A on disk, no index entry for A" with "frame B on disk, index
entry for B". The mitigation is the existing lock; the contract is that the new index writer
must use it, and that the lock must span both file writes for one append.

Mitigation: the index append is the LAST statement of the `withOrdinalAuthority` block in
`append`, after the frames-line `appendLine` returns. Tests in `SegmentFrameIndexCrossProcessOrdinalTest`
(rows above) exercise this.

### 5.2 A partial write updates one file but not the other

A crash between `appendLine(framesFile, …)` and `appendLine(offsetIndexFile, …)` leaves a frame
on disk with no index entry. The reader sees `mtime(frames) > mtime(index)` and rebuilds. The
rebuild iterates the frames file and produces the correct index, so the next read is correct.

The reverse — index updated, frame missing — cannot happen: the frames line is written first and
the index entry's `byteOffset` is computed from `Files.size(framesFile)` **after** the write.

A torn index line: the reader treats the index as append-only and binary-searches by `ordinal`;
a torn last line is `decode`d as garbage and discarded. If the last line is torn, the reader
falls back to the previous whole entry. Same shape as `sealTornTail`'s discard rule.

Mitigation: rows 6 and 8 of `SegmentFrameIndexOffsetIndexTest` reproduce both partial-write
windows and assert the rebuild path returns the correct index.

### 5.3 A torn-tail truncate invalidates an offset entry

`sealTornTail` truncates the frames file (`SegmentFrameIndex.kt:452–457`). The last index entry
points at a frame that is still on disk (a torn line is at most one frame, and the index entry
is at most `INDEX_STRIDE - 1` frames back). After the truncate, the frames file is shorter by
~120 bytes, and the next reader sees the new size first. The reader's `byteOffset > fileSize`
rule discards the entry and falls back to the previous one. The next `INDEX_STRIDE` frames will
re-populate the index correctly because the writer measures offsets **after** the truncate, not
before.

Mitigation: row 6 of `SegmentFrameIndexOffsetIndexTest` reproduces this; the W3 row of
`SegmentFrameIndexCrashTest` is preserved as the upstream invariant.

### 5.4 The reader is asked for a `K` past the highest indexed entry

`framesOfRun(K, L)` with `K` past the last indexed ordinal but below the highest in-memory ordinal
(counter cached by `loadOrdinals`): the reader reads from the highest entry's offset forward,
which is correct — the binary search is on `ordinal ≤ K`, so "no entry past K" is "use the last
entry".

`K` past the in-memory `lastOrdinal` (`SegmentFrameIndex.kt:78`): the reader returns an empty
list, same as today. The contract is "frames with `ordinal > K`", and there are none.

Mitigation: this is the existing `framesOfRun` contract; no new failure mode.

### 5.5 Recovery consults the index by accident

A future change must not let `recoverUnframedBytes` short-circuit on "the index says we are
caught up". The recovery path MUST iterate frames directly, because the index cannot describe
the gap between its last entry and the actual stream end (the gap is the whole point of
recovery, and is at most `INDEX_STRIDE - 1` frames wide — the very thing the index skips).

Mitigation: row 9 of `SegmentFrameIndexOffsetIndexTest` deletes the index file before recovery
and asserts recovery still closes the gap. The mechanical fitness `SegmentFrameIndexCrashTest`
W2 row is preserved.

### 5.6 The index file is rebuilt on every cold start, defeating the cache

The mtime check (`mtime(index) < mtime(frames)`) is conservative: any write to the frames file
since the index was last touched invalidates it. A reader that ran in the same JVM as the
writer sees the up-to-date mtime and uses the index; a reader in a fresh JVM sees the frames
file's mtime is the same as the index's mtime (they were written in the same writer window),
and uses the index. The rebuild only fires when the index is genuinely stale.

Mitigation: a benchmark row that opens a fresh JVM against an existing run's index and asserts
the cold read uses the index without rebuilding.

### 5.7 The benchmark numbers change on different boxes

`SegmentFrameIndexBenchmark.kt` reports durations without asserting thresholds (the existing row
already does this — see `report(...)` at lines 155–171). The "after" benchmark row
(`framesOfRun(K, 10) is O(L)`) reports the durations and reads them off stdout, exactly like the
"before" row. The test class continues to assert that all timings are positive and that the
ratios are flat in `L`, not in `N`. No wall-clock threshold ever enters the assertion.

---

## 6. Acceptance criteria

The ADR is implementable when the next agent can sit down and produce code without making an
architectural decision. Every fact below is normative.

### 6.1 File layout

```text
<root>/frames/<runId>.frames.offset-index
```

Same directory as the existing frames file (`SegmentFrameIndex.kt:481–501`). The index is named
`<framesFile>$OFFSET_INDEX_SUFFIX`; the constant `OFFSET_INDEX_SUFFIX = ".offset-index"` lives in
the existing companion (`SegmentFrameIndex.kt:481`).

### 6.2 Line format

```text
ordinal '\t' byteOffset '\n'
```

Both decimal. `ordinal` matches the frames file's ordinal namespace. `byteOffset` is the byte
offset of the FIRST byte of the frame's line in the frames file, in UTF-8.

### 6.3 Stride

```text
INDEX_STRIDE = 1000
```

Recorded as a constant in the companion. Revisited at the OBS-3 measurement gate.

### 6.4 Writer API (internal)

```kotlin
private fun maybeAppendIndexEntry(runId: String, justAppendedOrdinal: Long)
```

Called at the end of `append(...)`, inside the `withOrdinalAuthority` block, after the frames
line `appendLine` returns. The function:

- computes `byteOffset = Files.size(framesFile(runId)) - (lineBytes + 1)`
- appends the entry if `(justAppendedOrdinal + 1) mod INDEX_STRIDE == 0`
- uses the same `appendLine` helper (`SegmentFrameIndex.kt:434–450`) for the index file

### 6.5 Reader API (internal)

```kotlin
private fun loadOffsetIndex(runId: String): List<IndexEntry>
private fun binarySeekIndex(entries: List<IndexEntry>, atOrBefore: Long): Long  // returns byteOffset
```

`loadOffsetIndex` consults the index file's mtime against the frames file's mtime; if the index
is missing or stale, it rebuilds from the frames file in one pass and writes back. The rebuilt
index is cached for the lifetime of the JVM (refreshed on mtime change).

### 6.6 Production benchmark row (regression)

`SegmentFrameIndexBenchmark.kt` gains one new row:

```text
after-fix — framesOfRun(K, 10) on a 10_000-frame run reports a duration
           within an order of magnitude of a 100-frame run
```

This is the row that proves the cost claim; it MUST be derived from the existing benchmark
fixture, not a new fixture, so the comparison is apples-to-apples.

### 6.7 Recovery preservation row

`SegmentFrameIndexOffsetIndexTest` row 9 above (recovery runs without the index). The
pre-existing `SegmentFrameIndexCrashTest` W2 row remains green on the same code path.

### 6.8 Cross-process preservation row

`SegmentFrameIndexOffsetIndexTest` row 10 above replays `XPROC-1` and `XPROC-2` with the index
writer active. The two existing rows in `SegmentFrameIndexCrossProcessOrdinalTest` are NOT
modified; this is an additional run, not a replacement.

### 6.9 The exact files this ADR touches (when implementation lands)

- `v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentFrameIndex.kt`
  — new constants, new reader methods, new writer hook at the end of `append`.
- `v2/pipeline-output-store/src/test/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentFrameIndexOffsetIndexTest.kt`
  — new test class with the 10 rows above.
- `v2/pipeline-output-store/src/test/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentFrameIndexBenchmark.kt`
  — one new benchmark row.

No other file is touched. The crash and cross-process tests are unchanged.

---

## 7. Open questions

These are decisions the operator needs to make before code lands. They are stated as questions
because the ADR should not invent a default the operator might want to change.

1. **Should the index file live beside the frames file (same directory) or in a separate
   sub-directory?** This ADR proposes beside, to keep one file open per read and to make
   `Files.list(framesDir)` the only directory walk. A sub-directory would let a future sealing
   strategy drop the index together with its sealed frame; that is OBS-3's
   sealed-segment strategy, deferred (§2.3). Default if no answer: beside.

2. **Should `framesOfRun(afterOrdinal=-1, limit=Int.MAX_VALUE)` bypass the index and stream the
   file via `Files.lines(...)` for the catch-up case?** The benchmark row `framesOfRun catch-up
   read is O(N)` already exercises this, but the read uses `BufferedReader` (line-by-line
   blocking). A streaming path could let a CLI consumer process frames as they arrive instead of
   waiting for the full list. Default if no answer: keep the current list-returning contract; the
   streaming variant is a future ADR.

3. **Should the index store `byteOffset` only, or also a `lineByteLength` so the reader can
   validate the entry without parsing the line?** `byteOffset` alone is sufficient (the reader
   seeks, reads a line, decodes; if decode fails, the entry is stale). A length would let the
   reader skip the decode-and-discard cost on the first entry after the seek but adds 8 bytes
   per entry. Default if no answer: `byteOffset` only.

4. **Should `INDEX_STRIDE` be adaptive (smaller for hot runs, larger for cold ones)?** A fixed
   stride keeps the index boring and the reader cheap. An adaptive stride lets a 10⁸-frame run
   keep the index under 1 MB but complicates the rebuild path. Default if no answer: fixed
   `1000`, revisited at the OBS-3 measurement gate.

---

## 8. References

- `b01a98e7` — R3 step 1 (sealed tail read is O(1)); the precedent for the writer-path cost
  reduction that this ADR parallels on the reader side.
- `SegmentFrameIndex.kt:123–132` — `framesOfRun`, the public method this ADR accelerates.
- `SegmentFrameIndex.kt:170` — `recoverUnframedBytes` reads frames; not changed by this ADR.
- `SegmentFrameIndex.kt:295` — `loadOrdinals` reads frames; not changed by this ADR.
- `SegmentFrameIndex.kt:341–399` — `sealTornTail` truncates the frames file; the index must
  survive this.
- `SegmentFrameIndex.kt:408–429` — `readFrames` (the O(N) reader); the only call site the ADR
  eliminates for `framesOfRun`.
- `SegmentFrameIndex.kt:434–450` — `appendLine`; reused for the index file with no change.
- `SegmentFrameIndex.kt:245–253` — `withOrdinalAuthority`; the lock window the index writer MUST
  use.
- `SegmentFrameIndexCrashTest.kt` — W1–W5; the crash contract preserved by this ADR.
- `SegmentFrameIndexCrossProcessOrdinalTest.kt` — `XPROC-1`/`XPROC-2`; the cross-process
  contract preserved by this ADR.
- `SegmentFrameIndexBenchmark.kt` — the "before" rows and the new "after" row.
- `ADR-OBS-001` — single byte authority (Output Plane); index does not change this.
- `ADR-OBS-002` — recovery is exclusive and scoped; the index reader is a pure read.
- `AGENTS.md` §STEP CONSTITUTION — registry metadata is the source of truth; per-run metadata
  here lives on a sibling file the index owns.
- `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` §3 — PC-05 (the defect this ADR
  closes).