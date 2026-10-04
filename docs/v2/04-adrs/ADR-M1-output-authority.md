# ADR-M1 — Output authority, output cursor, and S2 adoption

**Status:** ACCEPTED · **Date:** 2026-10-04 · **For:** the M1 line · **Supersedes:** the four open
product decisions in `pipelinek-runtime-contract-evolution` (`P1`..`P4`)

This ADR records decisions that were **product decisions** and are now **settled**. It does not
implement them; the implementation is sliced in `M1-P1`..`M1-P4`. Nothing here changes S4's
`Unstable` or recovery semantics.

---

## D1 — `Unstable`: operational status and semantic outcome are different axes

```text
No OperationStatus.UNSTABLE is added.
No semantic collapse of Unstable to FAILED or to ABORTED is performed by M1.
```

`StepOutcome.Unstable → OperationStatus.FAILED` and `BranchTerminal.Unstable → OperationStatus.ABORTED`
are two *projections of different axes*: the first is a durable operational status, the second an
aggregate terminal. Collapsing either would destroy information a projection surface needs —
`Jenkins Result.UNSTABLE` cannot be reconstructed from `FAILED`.

**Owner:** S4. S4 owns this evolution **and** `R14`/recovery. M1 does not build an alternative and
does not touch the mapping. If M1 ever needs a terminal `Unstable` materialised on recovery, that is
a **F1-C dependency on S4**, not M1 work.

## D2 — Output authority: one durable authority for transcript bytes

```text
Process output of `sh` enters the Output Plane exactly once.
EchoOutputCaptured remains for semantic `core.echo` and will NOT carry process stdout/stderr.
```

Today the product renders console bytes **twice, independently**: `console.log` read whole via
`redactFile(Path)`, and a separate `EchoOutputCaptured` event built from in-memory buffers via
`redactStream(InputStream)`. Same redactor, two overloads, two sources, and **no property asserting
they agree** — so there is no oracle and a parity test would compare two implementations rather than
one writer against what was written.

The Output Plane is the single authority. Everything else indexes, references or presents its bytes;
**nobody re-fabricates them.**

`returnStdout` is untouched by this: it remains an exact typed value, separate from the transcript.
Taking the value does not remove the console, and the console is not re-derived from the value.

## D3 — Output cursor: independent of `EventCursor`

```text
OutputCursor = stream identity + committed byte offset
Reads are bounded and range-addressable.
No event sequence may act as an output cursor.
```

`EventCursor` is `it.sequence > after` over a store-assigned event sequence. That is a *different
order*: event #143 is not byte #143 of the log, and a hole in an event sequence is indistinguishable
from a hole that was never emitted. A console read that advances on the event sequence can therefore
be perturbed by unrelated event traffic.

The cursor is opaque to consumers in the sense that the store's internal representation is not part
of the contract — but the *pair* (stream identity, committed offset) is, because it is what makes
resume well-defined after a crash. Reads never observe a byte the store has not committed, and the
same committed offset always yields the same byte.

## D4 — S2 adopted in full: O1 ∧ O2 ∧ O3, on strategy D

```text
O1  reserve before writing bytes; the acknowledgement IS the reservation
O2  a reader resumes from a committed index, never from a file's size
O3  recovery is a distinct entry point that reconciles before any reader is served
```

A **conjunction, not a score.** "Two of three" is a product that loses acknowledged bytes sometimes,
which is worse than one that never claims the property.

Strategy **D** (segment reservation with recovery), as decided by RCE `ADR-0002` on measured
contention and commit-window grounds: per-writer segments, sequential append into a segment the
writer owns, no shared index write and no cross-writer lock, global order recovered by merging
segments, and an **unused reservation released on recovery** so a claimed-but-unused range leaves no
permanent hole.

### D4.1 The refusal that survives adoption

```text
PROVEN:     a process that dies loses nothing it acknowledged.
NOT PROVEN: durability across power loss / kernel loss.
```

`halt()` models process death; bytes already issued to a file descriptor live in the page cache. The
stronger claim requires an `fsync` discipline (data, then metadata/index) that this store does not
yet implement. **No conformance artefact may strengthen this without the `fsync` discipline, and this
ADR does not authorise one.**

---

## Slices

| Slice | Delivers |
|---|---|
| `M1-P1` | Output Plane separate from `pipeline-events`; append/read/recovery ports; Segment/WAL D |
| `M1-P2` | `ShExecution`/`DurableShellExecutor` write through the authority; no process `EchoOutputCaptured` |
| `M1-P3` | `OutputCursor` read API: bounded page, closed refusal ADT, arbitrary byte range |
| `M1-P4` | Conformance: soak, restart mid-stream, partial tail, slow reader, redaction, `returnStdout` separation |

## Ownership boundary with S5

S5 owns envelope, causation, correlation and content identity. **S5 may reference an
`OutputStreamId`; it may never transport a transcript.** An event that carried output bytes would
recreate D2's second authority, and would also make the event store the second holder of bytes it
does not own. If S5 needs to point at console content, it points at the stream.
