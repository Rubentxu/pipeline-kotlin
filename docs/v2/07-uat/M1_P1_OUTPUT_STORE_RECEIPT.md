# M1-P1 — Output Store / S2

**Date:** 2026-10-04 · **Branch:** `m1/output-plane` (base `d0fa34e8`) · **Module:** `v2/pipeline-output`
**ADR:** `ADR-M1` §D2, §D3, §D4 · **Disposition of RCE branches:** `M1_RCE_DISPOSITION_SUPERSEDED_BY_UPSTREAM.md`

---

## 1. What exists now

```text
v2/pipeline-output/                                    a new module, no dependency on :pipeline-events
  OutputCursor.kt        OutputStreamId, OutputCursor, OutputPage
  OutputRefusal.kt       the closed refusal ADT + OutputReadResult
  OutputPorts.kt         OutputAppendPort / OutputReadPort / OutputRecoveryPort
  OutputAdoption.kt      the S2 obligation contract, ported into src/main
  SegmentOutputStore.kt  strategy D, file-backed
```

The module graph carries the independence rather than a convention: `build.gradle.kts` has **no**
dependency on `:pipeline-events` and **no** dependency on `:pipeline-domain`, so event vocabulary
cannot leak back in through a shared type. `[ADR-M1 D3]` is enforced by the build, not by review.

## 2. Strategy D, in bytes

RCE's spike was stated over **frames carrying a sequence**. A product output cursor is a **committed
byte offset**, so the obligations are unchanged and the unit is not. What survived of D:

| D property | Where it lives here |
|---|---|
| per-writer segments, sequential append, no shared index, no cross-writer lock | one segment set per `OutputStreamId`, one `ReentrantLock` per stream |
| global order recovered by merging segments, not by consulting a global index | `readRangeLocked` walks sealed segments then the current one, in offset order |
| **unused reservation released on recovery**, so the order stays dense | `recover()` deletes `cur.res` and the next reservation reuses the base |
| reservation durable before the first byte | `Reserve.init` writes `cur.res` and returns afterwards |
| committed offset is its own record, not a file size | `cur.cmt` |

The three obligations are where the ADR says they are: **O1** in `Reserve.init`, **O2** in `cur.cmt`,
**O3** in `recover()` — reads before it are refused with `IllegalStateException` naming the missing
entry point, not served optimistically.

## 3. Evidence

```text
25 tests, 0 failures, 0 errors, 0 skipped      gradle :pipeline-output:test
7 of 7 mutations killed                          ./m1-mutation-prove.sh
```

The mutation harness is committed alongside the code and is re-runnable. Each mutation breaks one
property and names the test that must go red:

| | mutation | guard that catches it |
|---|---|---|
| M1 | reads no longer require recovery | O3 |
| M2 | a foreign cursor is clamped instead of refused | foreign-stream refusal |
| M3 | recovery keeps the uncommitted tail | stale-tail after reattach |
| M4 | recovery does not release the reservation | I3 + I6 density |
| M5 | committed offset read from file size | O2 |
| M6 | `read` ignores `maxBytes` | bounded page |
| M7 | segment rotation rewinds the committed offset | segmented read-back |

**Four defects were found this way, three of them in this slice's own model, not in the tests:**

1. **`OutputPage` had a vacuous invariant.** It required `bytes.size == committedEnd - from`, but
   `committedEnd` is the extent of the *whole stream*, not the end of the page — so no bounded page
   could ever satisfy it. It would have read as a guarantee while constraining nothing. Replaced with
   two real ones: the page may not run past the committed extent, and `next` must name the byte
   immediately after the last one delivered.
2. **Segment rotation rewound the committed offset.** `rotateLocked` wrote `currentBase` into
   `cur.cmt` where it had to write the *global* `committed`. The effect was that every byte committed
   before the first rotation was silently discarded, with no error anywhere — a 10 MiB stream read
   back 2 MiB. Found by the segmented read-back test, which is the only test that crosses a seam.
3. **`copyFrom` could not serve a producer larger than one reservation.** A process transcript's
   length is not known in advance, so the single-reservation operation was not the one the product
   needs. Added `OutputStreamHandle.appendFrom`, which takes one reservation per window and commits
   each in turn, so a tailing reader sees the transcript as it is produced.
4. **The harness's first run was measuring nothing.** `-q` suppressed the per-test `FAILED` lines,
   so all seven mutations reported "wrong". And on the first build of the module,
   `useJUnitPlatform()` was missing, so Gradle fell back to the JUnit 4 runner, discovered zero
   tests, and reported **BUILD SUCCESSFUL with an empty result set**. Both are the same failure this
   project has hit before: a green gate that ran nothing.

## 4. One finding about the store itself

There are two truncations of an uncommitted tail: one in `recover()` and one in `Reserve.init`. They
are **redundant with each other**. Mutating the reservation-time one leaves the suite green, because
recovery has already done the work and reads are bounded by the committed offset regardless.

Which one is load-bearing was settled by instrumenting the store and printing the segment contents at
each step, not by reasoning about it — reasoning had it backwards. `recover()` is the load-bearing
one; the reservation-time truncation is kept as a second line of defence and its KDoc says so, so
nobody later "simplifies" it believing a test needs it.

## 5. What is deliberately NOT claimed

```text
PROVEN:     a process that dies loses nothing it acknowledged.
NOT PROVEN: durability across power loss.
```

`SegmentOutputStore` issues writes and does not `fsync`. Bytes already issued to a file descriptor
survive process death in the page cache, which is the fault model the strategy was measured under.
The refusal lives in three places so it cannot erode by silence: `ADR-M1` §D4.1,
`OutputNotEstablished.POWER_LOSS_DURABILITY`, and the class KDoc. No `fsync` discipline is
authorised by this slice, and no artefact here may claim the stronger property.

A second refusal: this store makes **no claim about order across streams**. D recovers order within
a stream; two streams have no defined interleaving because the Output Plane does not impose one. A
global console order is a presentation decision, and putting it in the store would reintroduce the
coupling the Output Plane exists to remove.

## 6. What is NOT in this slice

- **P2** — `ShExecution` / `DurableShellExecutor` do not write through the port yet. The store is
  not yet on the product's write path, so it is a contract that works, not a contract in use.
- **P3** — the read API is in place, but nothing consumes it; `MainEventsCli` still reads console
  through the event cursor.
- **P4** — no soak, no restart-mid-stream, no slow reader. The crash properties above are proven
  against *reproduced durable state*, not against a killed process: this slice simulates a crash by
  leaving the reservation and uncommitted bytes on disk and recovering through a fresh store
  instance. That reproduces what recovery reads, and it is what makes the test runnable on every
  change — but a real `halt()` matrix is P4's job and has not been run.
