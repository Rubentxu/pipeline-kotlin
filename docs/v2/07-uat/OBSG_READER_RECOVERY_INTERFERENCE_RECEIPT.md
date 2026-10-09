# OBS-G — a read-only open of the Output Plane destroys a live writer's bytes

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `1b8b339c` |
| **Status** | **DEFECT OBSERVED** — reproduced across two processes, not composed from reading code |
| **Harness level** | HF3 — two real OS processes, genuine writer and genuine `console` verb |
| **Blocks** | admitting `observe` / `console` as multi-process capability (plan step I2) |

## The finding

`OutputPlaneProvider.storeFor()` recovers the store on open. `SegmentOutputStore.recover()`
reconciles every stream directory, and `reconcile()` contains:

```kotlin
if (readableEnd > committed) {
    truncateTo(layout.segmentFile, onDisk - (readableEnd - committed))
```

`readableEnd` counts bytes the writer has written; `committed` counts bytes it has acknowledged.
Between a writer's `write` and its `commit` the two differ, and a reader's recovery treats that
difference as crash debris and truncates it.

`SegmentOutputStore` has **no inter-process lock**: `recoveryLock` and `withStreamLockFor` are both
in-process `ReentrantLock`s. So nothing coordinates two JVMs.

Both `observe` and `console` call `storeFor()` as the **first statement of their read path** — before
the query has found anything. The destructive step therefore does not depend on the reader
succeeding, and does not depend on the reader asking for that stream.

## What was measured

A forked writer takes a real reservation through the real store, writes 4096 acknowledged bytes,
takes a second reservation, writes 4096 more, and parks between `write` and `commit`. A second
forked process runs the genuine `MainConsoleCli`, which **succeeds with exit 0** and serves the 4096
acknowledged bytes. The writer is then released and its own `commit()` returns **8192** with no error.

A third, fresh reader then sees:

| Quantity | Value |
|---|---|
| Bytes the writer wrote and acknowledged | 8192 |
| Bytes the writer's own `commit()` reported | 8192 |
| Bytes visible to a fresh reader afterwards | **4096** |
| `bytesUnbacked` reported by recovery | **4096** |
| Bytes of the truncated range observable by any reader | **0** |

So the failure is precise and bounded, and both halves matter:

- **Already-committed bytes survive.** `reconcile` truncates to `committed - currentBase`, so the
  acknowledged prefix is preserved. The audit's own wording — "elimina las reservas pendientes" —
  understated it, but not in the direction of more loss than that.
- **The live writer's written-but-unacknowledged bytes are destroyed**, and the writer's subsequent
  `commit()` then leaves a commit record that outruns its payload by exactly those 4096 bytes. The
  store reports that as `CORRUPT_COMMIT_RECORD` and deliberately does not repair it, so those bytes
  are **unrecoverable, not merely late**. The writer is never told: its commit succeeded and returned.

**A successful, ordinary, read-only `console` query costs a running job 4096 bytes of output.**

## The crash case is unaffected, and must stay that way

`RECOVER-2` asserts the law at a real `kill -9`, and it is **green**:

- the acknowledged prefix survives byte for byte;
- the unacknowledged tail does not appear;
- `bytesUnbacked == 0`;
- the open reservation is released exactly once;
- the stream remains writable immediately afterwards, resuming at the committed offset.

This matters for the repair, and it is why the repair cannot be "stop truncating".

## Why the fix is not "delete the truncation"

Mutation **M-G1** disables the truncation in `reconcile()`. It turns **both** rows RED:

- `INTERFERE-1` goes RED because the bytes now survive — proving the characterisation is measuring the
  truncation and nothing else;
- `RECOVER-2` goes RED because an unacknowledged tail would then remain readable after a crash.

So the truncation is simultaneously the cause of the defect and load-bearing for a guarantee the
project actually wants. It is not a bug to be deleted; it is a **decision that is missing a subject**.
`reconcile()` answers "is there a crashed writer's debris here?" with a yes/no, and the honest
question is "is there a crashed writer, or a live one?". Nothing in the on-disk state currently
distinguishes them.

## Fidelity

HF3. Two real OS processes per row. The writer drives the store's own public port
(`OutputAppendPort.open` → `reserve` → `write` → `commit`), the same sequence `appendFrom` performs
internally; nothing in the store is re-implemented. The reader is a forked JVM running the genuine
`MainConsoleCli.main`. The barrier is a file the writer creates after `write` and before `commit`, so
"parked in the window" is an event rather than a sleep, and every wait has a deadline and an
assertion on the premise. `@TempDir`, no wall-clock assertions, no pipes.

**Not HF2.** `MainConsoleCli` is driven directly, not through `installDist`. This row is about the
library's read path, not the installed image.

## Row status, stated plainly

| Row | Status | Meaning |
|---|---|---|
| `RECOVER-2` | **GREEN — a law** | crash recovery is correct today and must stay correct |
| `INTERFERE-1` | **GREEN — a characterisation of a defect** | pins the broken behaviour, so closing it is a deliberate inversion rather than a silent deletion |

`INTERFERE-1` does not assert that the product is right. Its assertion messages say `DEFECT
OBSERVED`, name the value a fix must produce, and say that applying the fix inverts the line. A
future reader who sees it green must not read it as a passing guarantee.

## Mutations

| Mutation | Change | Rows turned RED |
|---|---|---|
| M-G1 | `if (false && readableEnd > committed)` in `reconcile` | **both** — the characterisation and the law |
| M-G2 | skip releasing `cur.res` in `recover` | RECOVER-2 only |

M-G1 is the load-bearing result: it shows the defect and the guarantee are two faces of one
mechanism, so a repair has to separate a live writer from a dead one rather than remove the
truncation.

## Two harness defects this block paid for

Recorded because each nearly faked a result in the direction of a false alarm:

1. **Stream identity computed two ways.** The writer used `op0`; the reader used
   `OpId(runId, 0, 0).format()`. The first run reported "only 0 bytes survived", which reads exactly
   like a total-loss defect and was nothing of the kind — the reader was looking at a different
   stream. The identity is now defined once.
2. **Recovery report read from a second `recover()`.** `storeFor()` recovers internally, so the
   second pass had nothing to release and reported a clean store. A correct product fact was read as
   a harness defect.

A third, earlier: the observer's exit line was written to stderr while the harness redirected stderr
to `DISCARD`, so the control line was thrown away and the row failed for want of it.

## What a repair has to decide

The code must be able to tell a live writer's open range from a dead writer's. Options, none of them
cheap and none of them decided here:

- an inter-process lock on the plane, so recovery and reservation cannot overlap;
- a liveness record written by the writer and cleared on clean close, so recovery skips ranges whose
  owner is provably alive;
- making a reader open the plane **non-recovering**, with recovery owned solely by the writing side,
  which matches the audit's own phrasing: "una operación de lectura ordinaria no debería recuperar
  destructivamente las reservas de un productor que sigue vivo".

Each changes durable behaviour, so each is an ADR decision rather than a patch. This block produces
the evidence and stops there.

## NOT_RUN

- STEP-CERT and PRODUCT-GATE on this SHA.
- Installed-distribution (HF2) reproduction of the same interference.
- Any fix. No product file is modified by this block.