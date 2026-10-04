# M1-P2 — shell single byte authority

**Date:** 2026-10-04 · **Branch:** `m1/output-plane` · **ADR:** `ADR-M1` §D2 · **Follows:** `M1_P1_OUTPUT_STORE_RECEIPT.md`

---

## 1. What was removed

The product rendered a process transcript **twice and independently**. The shell wrapper wrote
`console.log`; the JVM then re-rendered its content into an `EchoOutputCaptured` event built from
in-memory buffers. Same redactor, two overloads (`redactFile(Path)` / `redactStream(InputStream)`),
two sources, and **no property asserting they agreed** — so a parity test on that path would have
compared two implementations rather than one writer against what it wrote. There was no oracle.

```text
REMOVED   ShExecution.emitTranscriptChunked      64 MiB chunking into events  (dead in production)
REMOVED   ShExecution.emitTranscriptStreaming    the second rendering
ADDED     ShExecution.ingestTranscriptIntoOutputPlane
ADDED     OutputPlaneProvider                    one recovered store per control-dir root
```

Net: **224 lines deleted, 64 added** in the modified files. It is a removal slice.

## 2. The law, and why it is not the obvious one

```text
For any one execution, the transcript bytes exist in exactly ONE place.
```

Not "the event type is never emitted". `ShExecution` has a **non-durable fallback** that runs with
no control directory at all — no filesystem privileges, or a non-Linux host — so there is no Output
Plane to write into. A guard phrased "no `EchoOutputCaptured` for `sh`" would have pushed someone
into deleting the only observable console that path has, which is a regression dressed as a fix.

The second-authority problem is never "two renderings exist". It is "**both** exist for the same
bytes". On the fallback path the event is the only rendering, so it is not a second anything.

`console.log` is therefore a **staging buffer, not an authority**: written by the wrapper, read
once, ingested, deleted on success. The fitness test asserts it does not survive, because bytes
left in the filesystem are a second durable copy that a guard counting events would never notice.

## 3. Why a provider and not a capability

The bytes are produced by a **child process** writing `console.log`. The JVM cannot be the writer
without inventing a pipe protocol between the wrapper and the store. Ingesting the finished file
through `OutputPlaneProvider` is the honest shape for the current wrapper; the day the wrapper grows
a pipe, the provider disappears and the child writes the store directly. The KDoc says so.

`OutputPlaneProvider` caches one **recovered** store per control-dir root, because recovery is an
entry point (O3) and is not free. A new process gets a new provider and recovers again — that is
the crash path, and P4 exercises it rather than caching across it.

## 4. What was NOT touched

- `CoreEchoStep` keeps its `EchoOutputCaptured`. That is the semantic `core.echo` event, and D2
  says explicitly that it stays.
- `returnStdout` is untouched: still an exact typed `ShellInvocationResult.Stdout`, still read from
  `output.txt`, still **not** re-emitted into the transcript. The test pins the separation, and
  also pins that the transcript holds stderr and not the captured value.
- `classifyShellTerminal`, `toStepOutcome` and the S4 recovery/`Unstable` semantics are untouched.
  D1 leaves that with S4 while F1-C is open.

## 5. Evidence

```text
5 tests, 0 failures      OutputSingleAuthorityFitnessTest, real sh processes
5 of 5 mutations killed  m2-mutation-prove.py
2 of 2 checks fired      M1OutputPlaneIndependenceFitnessTest (see below)
```

| | mutation | guard |
|---|---|---|
| M8 | the console event is put back | single authority |
| M9 | the ingest becomes a no-op | single authority |
| M10 | `console.log` retained after success | single authority |
| M11 | the typed value is folded back into the transcript | returnStdout separation |
| M12 | the plane ingests fewer bytes than the transcript | losslessness |

Two test files died with the emitters they tested (`TranscriptChunkingTest`,
`TranscriptStreamingEmissionTest`). They were **not** deleted and their property **not** dropped:
contiguous, ordered, lossless chunks moved to the store — `MAX_TRANSCRIPT_CHUNK_CHARS` became
`appendFrom`'s per-window reservation — and the property is now re-asserted from the outside, by
running a real 300-line transcript through a cursor and reading it back at two different page sizes.

## 6. The independence guard, and the vacuous guard it replaced

`M1OutputPlaneIndependenceFitnessTest` reads `:pipeline-output`'s declared dependencies and its
production sources. Its first version filtered on `line.startsWith("project(\"")` — and **no real
dependency line satisfies that**: they read `implementation(project(":..."))`. Adding
`implementation(project(":pipeline-events"))` left the suite **green**.

A guard that cannot match its own subject is worse than no guard, because it reads as coverage. It
now matches the call, not the line start, and is proven by mutation — with a **negative control**:
adding a permitted dependency does not fire it.

The source half of that guard is **not independently reachable**, and the harness says so rather
than pretending otherwise: adding an event-plane import to `pipeline-output` fails at
*compilation* first, because the module graph forbids it. The source scan is defence in depth for
the case where someone adds the dependency — and the dependency check fires before it.

Comments are stripped before the source scan. Without that, `OutputStreamId`'s KDoc — which names
`EventCursor` in order to explain why it is *not* one — would satisfy the law it documents. The same
trap closed `SingleDurableAuthorityFitnessTest` in the other direction, and it is why
`pipeline-output` has no dependency on `:pipeline-events` **or** `:pipeline-domain`:
`:pipeline-application` writes to the plane, and the plane reaches back into neither.

## 7. What is NOT in this slice

- **P3** — nothing consumes the read API yet. `MainEventsCli` still reads console through the
  event cursor, so the Output Plane is written but not read by the product.
- **P4** — no soak, no real process kill, no slow reader. The store's crash properties are proven
  against *reproduced durable state*, not against a `halt()`ed process.
- The **non-durable fallback** still emits a console event, by the argument in §2. That boundary is
  written in the KDoc at the emission site rather than asserted by a test, because the test would
  have to read a `.kt` file that is not on the test classpath — which is how a guard becomes one
  that gets deleted to make a build green.
