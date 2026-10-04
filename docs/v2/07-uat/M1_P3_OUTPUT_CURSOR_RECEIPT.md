# M1-P3 — the read side is consumable

**Date:** 2026-10-04 · **Branch:** `m1/output-plane` · **ADR:** `ADR-M1` §D3
**Follows:** `M1_P1_OUTPUT_STORE_RECEIPT.md`, `M1_P2_SINGLE_BYTE_AUTHORITY_RECEIPT.md`

---

## 1. What P3 actually was

All five bullets of the P3 brief — independent cursor, stream id + committed offset, bounded page,
closed refusal ADT, arbitrary byte range — were delivered by `SegmentOutputStore` in **P1**. There
was nothing left to build.

What was missing is the reason that did not count as done: **nothing called any of it.** A contract
no consumer can reach is a design, not a product. The store was written, tested, mutation-proven and
then never exercised by anything outside its own test — which is exactly the shape of a component
that rots quietly.

So P3 is the consumer surface, and it is written against real `sh` processes rather than against a
store a test populated by hand.

## 2. What was added

```text
OutputCursor.encode() / OutputCursor.decode()     a continuation token, in pipeline-output
ConsoleReadService                                 the in-process read surface
MainConsoleCli                                     the CLI surface
```

```text
pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN]
pipeline console --control-dir <path> <runId> <opId> --range FROM:TO
```

The transcript goes to **stdout as bytes** and the continuation token to **stderr**, which is the
convention `MainEventsCli` already uses. Bytes, not rendered lines: a consumer that has to strip a
CLI's framing out of a transcript will eventually strip the wrong thing, and the transcript is not
text-shaped in general.

## 3. The cross-plane refusal — the part worth having

```text
evt-cursor-v1:<runId>:<sequence>     the EVENT plane's token
out-cursor-v1:<stream>:<offset>      the OUTPUT plane's token
```

Pasting an event cursor where an output cursor belongs is *the* mistake this contract exists to
prevent: the event sequence is a plausible-looking integer, and resuming at that byte offset in the
transcript would return a real, wrong answer rather than an error. Two different token prefixes turn
it into a decode failure.

The stream id is percent-escaped because it contains `/`; an unescaped separator would make the token
ambiguous about where the stream ends. Eight malformed shapes decode to `null` rather than to a
plausible offset.

## 4. A defect from P1's own area, found by re-reading

`EventViewProjection`'s KDoc claimed `MainEventsCli` "accepts `--view <mode>` and `--format <fmt>`
and delegates the projection" there. **It accepts neither flag, and the projection has zero
production callers** — only its own test. RCE's promotion of OUT-B recorded this; it is confirmed
here against the current HEAD and is now corrected in the file.

`ViewMode.CONSOLE` is the sharper half. It selects `EchoOutputCaptured` envelopes, which since
M1-P2 carry the semantic `core.echo` event and **not** process output. A "console" view that can
only return a subset of console is the dangerous kind of wrong: it looks like it works and silently
drops whatever a process printed. It is now `@Deprecated` with the reason and a `replaceWith`
pointing at `ConsoleReadService`.

**Not deleted, deliberately.** It is another team's delivered artefact (WU-LPR-050) and removing it
unilaterally is the same process violation as merging to `main`. Deprecating it removes the trap,
documents the truth, and leaves the call to its owner.

## 5. Evidence

```text
10 tests, 0 failures        ConsoleReadServiceTest, all against real sh processes
4 of 4 mutations killed     m3-mutation-prove.py (2 candidates retired, see below)
```

| | mutation | guard |
|---|---|---|
| N1 | a foreign continuation token is clamped instead of refused | stream separation |
| N2 | the read is no longer bounded by `maxBytes` | bounded page |
| N3 | an unknown stream is served as an empty page | refusal is not empty success |
| N4 | any cursor prefix decodes | cross-plane refusal |
| N5 | the token is printed to stdout | the two planes do not collide |
| N6 | a byte range is served as the whole stream | range addressing |

**N1 was VACUOUS and the duplicate is now gone.** `ConsoleReadService` carried its own
foreign-stream check, and disabling it left the suite green — because `SegmentOutputStore.read`
already refuses with the identical `OutputRefusal`. The service's copy was unreachable, so it was
removed rather than kept "for clarity": two implementations of one refusal rule is how they start
disagreeing. The store is the single owner.

**N6 found a genuine test gap.** The `--range` branch of `MainConsoleCli.main` had no test at all —
the range test called the service directly and bypassed the argv parser entirely. The branch could
serve the whole stream and nothing noticed. The test now drives the real `main` with a real argv.

**The harness was also caught leaving a mutation behind.** A previous run reported N2 as "target not
found" while the file it had supposedly never written to still carried the mutation, so a later run
measured against a broken baseline. The harness now asserts that both files are byte-identical to
their pre-run state before it exits, and reports `RESTORE FAILED` if not.

Every test drives a real `sh` process and reads the result back. A cursor contract proven only
against a store populated by a test is a contract proven against a fiction — and the P1 tests were
exactly that, which is why P3 re-proves the properties at the seam a consumer actually touches.

## 6. What is NOT in this slice

- **P4** — no soak, no real process kill, no slow reader, no partial tail.
- **Nothing wires the CLI into a distribution entry point** yet. `MainConsoleCli.main` is callable
  and tested through `emit`, but the packaging that maps a `pipeline console` argv onto it is
  integration-gate work.
- **No cross-stream console order**, by design. The store recovers order within a stream; a run-wide
  console is a presentation decision, and `OutputNotEstablished.CROSS_STREAM_GLOBAL_ORDER` says so.
  A consumer that needs one has to choose it.
- The **`WULpr050EventViewProjectionTest`** still exercises `ViewMode.CONSOLE` and therefore now
  carries a deprecation warning. It was left compiling rather than edited, because the test belongs
  to the artefact's owner; silencing it here would be the change I just declined to make.
