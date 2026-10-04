# Handoff — S5 ↔ M1: OutputStreamId as a reference, never as a carrier

**From:** M1 (output plane) · **To:** S5 (event envelope) · **Date:** 2026-10-04
**Binding:** `ADR-M1` §"Ownership boundary with S5" · **Status:** a boundary, not a task

---

## The one-paragraph version

S5 may **point at** console content with an `OutputStreamId`. S5 may **never carry** the transcript
itself. An event that carried output bytes would recreate the second authority that M1-P2 just
removed, and would additionally make the event store a second holder of bytes it does not own. If
S5 needs a consumer to find console, it gives them the stream; the consumer asks the Output Plane
for bytes.

## What S5 owns, unchanged

```text
envelope        correlation, causation
content identity
the event wire format
sequencing and its cursor
```

M1 does not touch any of it. M1's own module declares **no** dependency on `:pipeline-events` or
`:pipeline-domain`, and `M1OutputPlaneIndependenceFitnessTest` enforces that — so this boundary is
structural, not a promise. Adding a reverse dependency to make S5's life easier would turn the
guard red on the next run, which is the intended outcome.

## What S5 may do

```kotlin
// Permitted: a reference. The envelope names the stream; it does not hold its bytes.
data class OutputStreamReference(
    val stream: OutputStreamId,   //  {runId}/{opId}/transcript
    val committedAtEmit: Long,    // bytes committed when the envelope was written
)
```

`committedAtEmit` is **advisory** and is the whole reason the reference is useful: it lets a
consumer size its first read without one. It is not a promise that the stream still holds those
bytes, because a stream can be released or a run pruned. A consumer that needs a guarantee asks the
Output Plane, not the envelope.

## What S5 may not do

```kotlin
// FORBIDDEN: the event now holds the bytes, and the Output Plane is no longer the authority.
data class EchoOutputCaptured(
    /* ... */,
    val content: String,   // <- process transcript bytes in an event envelope
)
```

Concretely, the following are all the same mistake wearing different hats:

1. Putting stdout/stderr bytes in an envelope payload.
2. Putting a whole transcript in an envelope payload because it "is only a few KB".
3. Base64-ing it so it fits a size limit.
4. Chunking it across envelopes — that is the exact mechanism M1-P2 deleted, and re-adding it
   under a new name restores the second authority and the missing oracle.
5. Caching a copy of the bytes "for offline replay" and reading from the cache.

The `core.echo` semantic event is a **different** thing and stays: it carries what a program
*printed deliberately through the echo step*, which is program data, not process output. M1-P2
removed process output from it, not the echo step's own event.

## How a consumer actually gets the bytes

Two equivalent routes, both off the Output Plane:

```text
CLI          pipeline console --control-dir <path> <runId> <opId> --after-cursor out-cursor-v1:...
In-process   ConsoleReadService.read(controlDirRoot, runId, opId, after, maxBytes)
```

The continuation token is `out-cursor-v1:<stream>:<offset>`. It is **not** `evt-cursor-v1:`. Pasting
an event cursor into the console reader is a decode failure rather than a plausible offset into the
wrong bytes, and that is deliberate: the two planes are different orders and the wire format says so.

A consumer that wants a run-wide console must decide its own **presentation order across streams**,
because the store deliberately does not define one. See `OutputNotEstablished.CROSS_STREAM_GLOBAL_ORDER`.
The Output Plane recovers order *within* a stream; a global console order is a rendering decision and
belongs to whoever renders.

## If S5 genuinely needs an answer it cannot get from a reference

Open an explicit dependency rather than reaching for a byte. The concrete case already identified is
materialising a **typed terminal `Unstable` on recovery**, which is `F1-C` territory and owned by S4
(see `ADR-M1` §D1). M1 will not implement an alternative, and this document is the standing proof
that the omission was a decision rather than an oversight.

## Checking this boundary has not eroded

```text
M1OutputPlaneIndependenceFitnessTest     2 checks, mutation-proven with a negative control
M1OutputPlaneIndependence (build file)   :pipeline-output declares no project(":pipeline-events")
```

If S5 needs a field in an envelope that *names* console, add it in `pipeline-events` and keep
`pipeline-output` ignorant of it — that direction is free. The direction that is not free is the one
that would let the event plane read the bytes.
