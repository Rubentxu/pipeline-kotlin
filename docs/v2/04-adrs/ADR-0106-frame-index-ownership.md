---
type: adr
id: ADR-0106
title: "One frame-index writer per run, arbitrated by an OS file lock"
status: accepted
date: 2026-10-09
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-OBS-002
  - ADR-M1
  - docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md
---

# ADR-0106 — Frame-index ownership across processes

> **Renumbered during the `main` reconciliation.** This was drafted as ADR-0105, but `main` had
> already published an ADR-0105 (admission check publication authority, 2026-10-08) with eight
> inbound references. Two accepted ADRs cannot share an id, so this one took the next free number.
> Nothing referenced the draft under its original id, so the renumbering touches no inbound link.

## Context

OBS-R1 §1.3 requires deciding the ownership contract of `SegmentFrameIndex` **with measurement**, and
names two admissible strategies:

- one index writer per run, guaranteed by interprocess exclusivity;
- several writers with an atomic allocate-and-publish transaction for ordinals.

It also states the starting condition: "a `ReentrantLock` and a counter cached per JVM are not a
demonstration of global exclusivity." That is correct as far as it goes, and this ADR records what
the measurement actually found, because the interesting part is not where the counter lives.

### What the code looked like

`SegmentFrameIndex.nextOrdinal` reads `lastOrdinalByRun`, a plain `HashMap`, under a
`private val lock = ReentrantLock()`. Both are per JVM instance. Read in isolation, that looks like
two processes will hand out the same ordinal. **That reading was wrong**, and it is worth recording
why, because the mistake is easy to repeat.

`append` calls `sealTornTail(runId)` *before* `nextOrdinal`. `sealTornTail` performs a full
`readFrames` of the durable log and then sets

```kotlin
lastOrdinalByRun[runId] = maxOf(lastOrdinalByRun[runId] ?: -1L, highest)
```

So the map is a **cache over the durable file**, refreshed from it on every append, and the durable
file is the authority. The sequential cross-process case is therefore safe, and
`XPROC-1` pins it: a second JVM that appends after a first one continues past it rather than
reusing its ordinal. A long-lived JVM cannot hand out a number a restarted writer already used.

### What is actually broken

What re-reading cannot cover is the read-modify-write that happens when two processes read the log
**before either writes**. The durable file is the authority, but nothing makes reading it and
appending to it atomic across processes.

`SegmentFrameIndexCrossProcessOrdinalTest` releases six real `java` processes together from a
filesystem barrier. Three independent runs:

```text
[9, 8, 7, 9, 9, 6]   4 distinct ordinals out of 6 writes
[7, 6, 8, 5, 8, 7]   4 distinct
[5, 5, 3, 3, 6, 4]   4 distinct
```

Two writers take the same ordinal every time. The consequence is byte loss, not untidiness: a reader
paging with a limit stops after the first of two frames sharing an ordinal, and the other is
committed output no console can show again. That is UAT-R1-07 violated, and it is a STOP criterion
of OBS-R1.

### Why not the other strategy

The event plane already solved the identical question in this same repository, and solved it the
other way: `RunExecutionLease` plus `UNIQUE(run_id, sequence)` in `SqliteEventStore`, so the
**database rejects** a writer that would duplicate a committed sequence, with cross-process proof in
`Rp020CrossInstanceSequenceAuthorityTest`. That is strategy two, and it is the better answer where a
database exists.

The frame index is an append-only flat file beside the bytes it describes. Adopting the event
plane's shape would mean introducing transactional allocation to a file format, or adding a second
store. OBS-R1 §0.1 forbids a second canonical storage, and OBS-R3 §3.1 forbids introducing a
database merely to avoid implementing seek. Choosing strategy two here would put the output plane's
authority in a component the output plane does not own.

## Decision

**Strategy one: one index writer per run, guaranteed by interprocess exclusivity.**

Ordinal allocation runs inside `withOrdinalAuthority(runId)`, which takes, in this order:

1. the existing per-JVM `ReentrantLock`, then
2. an exclusive `FileLock` on `<root>/frames/<safeRunId>.authority`.

Both `append` and `recoverUnframedBytes` allocate under it. Recovery is included because it allocates
ordinals by the same door, and it holds one run's authority at a time rather than every run's, so it
cannot pin the whole plane while waiting on a writer.

### Why that lock order

The `ReentrantLock` first is load-bearing, not cosmetic. It serialises threads inside the JVM, so
only one thread at a time reaches `channel.lock()` and it can never observe an
`OverlappingFileLockException` from a sibling thread. `SegmentOutputStore` handles that same-JVM
case by **skipping** the stream, and skipping is not available here: this is a write that has
already committed bytes, so declining would convert a moment of contention into lost output. The OS
lock is therefore blocking.

### Why `FileLock` and not a heartbeat

Per ADR-OBS-002. The kernel releases the lock the instant the owning process dies and refuses it to
a second process while the first lives, which answers "is a writer alive?" with no clock and no
staleness policy to get wrong. A PID or an mtime is a guess about liveness; this is the kernel's
own statement of it.

### Why not a read-after-write verification

Detecting a duplicate after the fact would report a collision, but by then both writers already
believe they won and both frames are durable. Rejecting after the fact loses the choice of who was
right; excluding beforehand is the only answer that keeps ordinals strictly increasing.

## Consequences

- `SegmentFrameIndex` gains one file per run under `frames/`. It is deliberately **not** matched by
  `runsWithDeclaredStreams`, which selects on the `.streams` suffix, and it is invisible to pruning,
  which walks `root/streams/` — a different directory (`FRAMES_DIR = "frames"`, `STREAMS_DIR =
  "streams"`). Verified, not assumed.
- Every ordinal allocation across processes now serialises. The wait is one file read plus one
  append. The cost is not yet measured under a deliberately slow writer, and that measurement is
  outstanding.
- The in-JVM `lastOrdinalByRun` cache and the durable re-read are both kept. They are what make the
  sequential case safe and cheap, and `XPROC-1` fails if either is removed.

## Verification

- `XPROC-1` — deterministic, green before and after. It fails if the durable re-read is ever removed
  or short-circuited.
- `XPROC-2` — a race, and labelled as one in its own failure message. Red on the previous commit in
  three of three runs; green in five of five after this decision. A green run here means the window
  is closed in practice, not that the invariant is proved, and the row says so.
- `pipeline-output-store` with `--rerun-tasks`: 66 tests, 0 failures, 0 skipped. detekt green with no
  threshold raised, no baseline widened and no suppression added.