# ADR-OBS-002 — Passive reading and destructive recovery are different openings

- **Status:** accepted
- **Date:** 2026-10-09
- **Owner decision:** separate passive reading from recovery-with-effects (integration block 1)
- **Reconciles:** `ADR-M1 §D4` O3, `ADR-OBS-001`, `ADR-0088`
- **Implemented by:** OBS-H1 (`StreamOwnership`, reader/writer openings, scoped `recover`)
- **Evidence:** `OBSG_READER_RECOVERY_INTERFERENCE_RECEIPT.md` (the defect), `OBSH1_*` UATs (the repair)

---

## 1. Context

Three accepted decisions collided, and the collision only became visible once a second process could
read the plane.

**ADR-M1 §D4 O3** already said recovery is *a distinct entry point*: "recovery is a distinct entry
point that reconciles before any reader is served". `OutputRecoveryPort` says the same thing in the
type — "Recovery is therefore an explicit call that must complete before any `OutputReadPort` call is
honoured" — and gives the store a typed refusal (`RecoveryNotCompleted`) for the case where it was
not.

**`OutputPlaneProvider.storeFor()` then made it implicit for everyone.** It is
`SegmentOutputStore(root).also { it.recover() }`, and `observe` and `console` both call it as the first
statement of their read path. So a read-only verb performed a global, destructive reconciliation of
every stream in the plane before its query had found anything.

The mechanism that made this lossy rather than merely rude is `reconcile()`:

```kotlin
if (readableEnd > committed) truncateTo(layout.segmentFile, …)
```

`readableEnd` counts bytes a writer has written; `committed` counts bytes it has acknowledged. Between
a writer's `write` and its `commit` those two differ — **that difference is the definition of a writer
in flight, not evidence of a crash.** Nothing on disk distinguished the two cases.

`SegmentOutputStore` has no inter-process coordination at all: `recoveryLock` and
`withStreamLockFor` are both in-process `ReentrantLock`s.

### Measured, not composed

`ObsGReaderRecoveryInterferenceUatTest` reproduced it across two OS processes. A writer committed 8192
bytes and its own `commit()` returned 8192 with no error; after a successful `console` query in a
second JVM, a fresh reader saw 4096 bytes and recovery reported `bytesUnbacked = 4096`.

The already-committed prefix survived — `reconcile` truncates to `committed - currentBase` — so the
loss is exactly the live writer's open range. But the writer's subsequent `commit()` leaves a commit
record ahead of its payload, which the store reports and deliberately does not repair, so those bytes
are **unrecoverable rather than late**. The writer is never told: its commit succeeded and returned.

## 2. Decision

**A reader opens the plane passively. Recovery is an explicit, exclusively-owned, scoped operation
that a reader never performs.**

Three openings, named for what they are allowed to do:

| Opening | Recovers? | Mutates durable state? | Who uses it |
|---|---|---|---|
| `openForReading(root)` | **no** | no | `observe`, `console`, any read-side verb |
| `openForWriting(root, owner)` | only its own scope | yes, bounded by its ownership | `ShExecution`, the pump |
| `recover(scope)` | the named scope only | yes, and only what it owns | an explicit recovery step |

### Ownership is a kernel-held lock, because the kernel already knows the answer

The question recovery must answer is not "is there debris?" but **"is there a writer alive right
now?"**. A file's mtime, a PID, or a heartbeat all need a clock and a policy for a stale one. An
`FileLock` does not: the kernel releases it when the owning process dies, and refuses it to a second
process while the first is alive. That is precisely the distinction the store was missing, and it is
free of policy.

So each stream directory gains an ownership file. `reserve()` takes an exclusive lock on it and holds
it until `commit()` or `abandon()`. Reconciliation **skips any stream whose ownership lock is held**,
and does not truncate it and does not release its reservation.

This is also why the repair cannot simply delete the truncation. Mutation **M-G1** disables the
truncation and turns **both** UAT rows RED — `INTERFERE-1` because the bytes now survive, and
`RECOVER-2` because an unacknowledged tail would stay readable after a crash. The defect and the
guarantee are two faces of one mechanism. Ownership separates them; deletion removes both.

### Recovery is scoped, never global while writers are live

`recover()` takes a scope — a run, a stream, or an explicit list — and reconciles only those streams,
skipping every one whose ownership is held. A run that is still being written is therefore never
reconciled by a reader or by another run's recovery. Two runs sharing one control directory are
independent facts and must not be able to disturb each other.

### The refusals stay distinct

"Nothing here", "still being written", "not recovered" and "corrupt" are four different answers and
collapsing them is how a hole becomes a clean finish. `OutputRefusal` gains an explicit case for a
stream owned by a live writer, alongside the existing `RecoveryNotCompleted`, `UnknownStream` and
`OffsetBeyondCommitted`.

## 3. What this does NOT change

- **The single byte authority.** ADR-OBS-001 and ADR-M1 §D2 are untouched: bytes are redacted before
  persistence and the Output Plane remains the only copy. This ADR is about who may *reconcile*
  durable state, not about who owns bytes.
- **ADR-0088's contract.** `view × format × query`, the refusal of `full`, and the read budgets stand.
- **The crash guarantee.** ADR-M1 §D4's `PROVEN: a process that dies loses nothing it acknowledged`
  is preserved and is now proved with ownership in the picture rather than with a single process in it.
- **Nothing about power loss.** ADR-M1 §D4's `NOT PROVEN` line still holds. This ADR adds no `fsync`
  discipline and does not authorise one.

## 4. The law

```text
No read path may remove or reconcile durable state belonging to a live writer.
```

Enforced by: `OutputPlaneReaderFitnessTest` (no read-side caller reaches a recovering opening) and by
the OBS-H1 UATs, which run real processes rather than threads.

## 5. Why not the alternatives

**Keep recovery implicit and only fix the truncation.** Rejected by M-G1: it removes the crash
guarantee along with the defect.

**A liveness file with a heartbeat and a timeout.** It replaces a fact the kernel already provides
with a clock, a staleness threshold and a policy for what a slow writer looks like. A threshold is
also an invented number that fails on a loaded machine and gets read as a product defect.

**A reader that recovers under an exclusive lock covering the whole plane.** It would serialise every
`observe` against every other `observe`, and it still cannot tell a live writer from a dead one — it
would only know that nobody happened to hold the lock at that instant.

**Per-stream ownership rather than per-plane.** Required rather than chosen: the audit's third UAT —
two independent runs sharing a control directory, one recovering without touching the other — has no
meaning under a plane-wide lock.