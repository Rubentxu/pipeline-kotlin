# R4 / WU-RC2 — Frame integrity + follow correctness (P0 block 1 of the rc1 audit)

- **Status:** PARTIAL — three P0 defects fixed and verified; a FOURTH, deeper defect found
  and left BLOCKED (see § "The fourth defect"). Not CERTIFIED.
- **Base:** `fdcadcfd` (v0.61.0-rc1). **Train:** 0.61.0-rc2 (proposed, not yet published).
- **Provenance:** branch `r4/0-61-rc2-p0-follow`, worktree `pk-rc2`.

## One-line summary

The v0.61.0-rc1 audit found three P0 defects in the published console code. All three are
fixed here and each fix is pinned by a test that fails on the rc1 code. Investigating them
surfaced a FOURTH defect that is deeper than all three: **the Output Plane is never populated
by a real `pipelinek run`,** so `console --frames` and `console --follow` have nothing to read
in production. That defect is documented, not fixed, and it is why this receipt is PARTIAL.

## The three P0 fixes

### P0-A — `readFrames` ignored the requested opId

**Defect.** `readFrames` and `readFramesFiltered` both called
`reader.readOutput(request.runId, request.afterOrdinal, request.frameLimit)` — the run-wide
overload, which has no opId parameter. `console --frames <runId> <opId>` therefore returned
every operation's frames, not the requested operation's.

**Fix.** Added `ObservationOutputReader.readOutput(runId, opId, afterOrdinal, frameLimit)`,
an opId-scoped overload. `readFrames`, `readFramesFiltered` and the `--follow` poll loop all
pass the opId now. The run-wide overload is unchanged and still used by the callers that
legitimately want the whole run (`observe --view console`, `LiveOutputDrain`).

**A defect found while fixing it.** The first version of the opId overload pulled a single
`framesOfRun(runId, cursor, frameLimit)` window and filtered it. That bounds the ANSWER by
the PRE-FILTER window, so in a run where other operations are interleaved, a small
`frameLimit` yields a window containing zero frames of the requested opId — and the caller
sees "nothing new" for an op that has plenty to say. `ConsoleRc2FrameIntegrityTest` P0-4
caught this as a stalled follow. The shipped version loops over index windows, advancing the
cursor past each window's last ordinal, until it has `frameLimit` MATCHING frames or the index
is exhausted. `moreFrames` is then a claim about the op ("there may be more frames of THIS
opId"), not about the run's window.

### P0-B — the `--follow` cursor advanced past a frame

**Defect.** `SegmentFrameIndex.framesOfRun` selects `decoded.ordinal > afterOrdinal` — strictly
greater. The follow loop set `ordinal = page.records.last().frame.ordinal + 1L` after emitting
a page. With `frameLimit = 1` the sequence walked 0 → 2 → 4 and every odd frame was skipped.

**Fix.** `ordinal = page.records.last().frame.ordinal`. An empty page leaves the cursor where it
was, so a poll that sees nothing new can see the next frame.

**Proof.** `ConsoleRc2FrameIntegrityTest` P0-3 drives BOTH cursor arithmetic against a real
store and asserts the rc1 form still skips while the fixed form yields a dense `0..4`.

### P0-C — the JSONL follow writer closed `System.out` between pages

**Defect.** `emitFollowPage` wrapped `out` in `java.io.OutputStreamWriter(out, UTF_8).use { }`.
`use` calls `close()` on exit, and `OutputStreamWriter.close()` propagates to the underlying
stream — so after the first follow page `System.out` was closed and every later page was lost.

**Fix.** `emitFollowPage` wraps `out` in an anonymous `FilterOutputStream` whose `close()` is a
no-op, writes through an `OutputStreamWriter` over it, and calls `writer.flush()` (not `close()`).

**Proof.** `ConsoleRc2FrameIntegrityTest` P0-4 writes TWO pages through a sink that records its
own closure and asserts the sink was never closed and both payloads arrived.

## The fourth defect — the Output Plane is never populated by a real run

**This is the finding that matters most, and it is BLOCKED, not fixed.**

While writing the rc2 canary I discovered that `console --frames` and `console --follow` cannot
be exercised end-to-end at all: after a real `pipelinek run`, the Output Plane contains no
frames.

### Evidence

```text
$ pipelinek run --db /tmp/j.db --control-root /tmp/c --isolated <2-stage 4-step fixture>
exit=0     # every step ran, journal carries StepStarted/StepFinished for all four

$ find /tmp/c -type d
/tmp/c/output-plane/streams        # created, EMPTY
/tmp/c/workspace/main-0
# no output-plane/frames directory at all
```

`SegmentFrameIndex` writes frames under `root/frames/` (`FRAMES_DIR = "frames"`). That
directory does not exist after a real run, so `declareStream` / `appendFrame` were never
called, so `RedactingOutputIngress.handle()` was never reached.

### Root cause — narrowed to the redaction composition

The first hypothesis (a `setsid` fork losing the pipe) was **wrong**. Temporary probes were added
to `RedactingOutputIngress` and `DurableShellExecutor`, run, and reverted (no probe code is
committed). They showed:

```text
PROBE launch:    pumpedThroughJvm=true sinks=[STDOUT, STDERR] redactor=true pumpedViaAgent=false
PROBE pump-gate: pumpedThroughJvm=true agent=false -> runs=true
PROBE pump[...-s0-0/STDERR]: read n=-1 available=0 procAlive=false
```

`sinks=[STDOUT, STDERR]` proves the wiring in `ShExecution` and `ShOperationsAdapter` is intact,
and `runs=true` proves the in-JVM pump path is taken. The discriminator is the third value:
**`redactor=true`**.

### The discriminator, proven by a controlled pair

`ConsoleReadServiceTest` is GREEN and drives a real `sh` through the real
`ShExecution.invokeShell`, then reads the transcript back. It looks like proof that the Output
Plane is populated by a real run. It is not — **that test composes no `secretPatternRegistry`**,
so `transcriptRedactor` is null. The CLI composes one unconditionally (`Main.kt` wires the
registry object into every run; only the canary *secrets* are conditional, at `Main.kt:253`),
so `DurableShellExecutor` reports `pumpedThroughJvm = true` and a `StreamingRedactor` sits
between the child's pipe and the sink.

`ShTranscriptRedactionReproductionTest` is the controlled pair: same harness, same real `sh`,
one variable.

| Row | Registry | Result |
|---|---|---|
| `REDACTOR-1` (control, enabled) | none | **GREEN** — full transcript reads back |
| `REDACTOR-2` (`@Disabled`, RED) | **empty** | `console-refused: unknown-stream run-redaction-false/run-redaction-false-s0-0/stdout` — the stream is never even **declared** |
| `REDACTOR-3` (`@Disabled`, RED) | one secret | transcript reads back as exactly `'t'` — **one byte of sixteen** |

The empty-registry row is the important one. A credential-free `pipelinek run` composes an
*empty* registry, and that already loses the whole transcript. So this is not "redaction is
broken when secrets are present"; the redactor's mere **presence** loses it.

### What the numbers say

```text
$ time pipelinek version                              # JVM baseline
real 0m0.21s
$ time pipelinek run --db ... --control-root ... (one sh step)
real 0m15.97s   user 0m25.1s
```

`user 25s` against `real 16s` is the tell: the run is not waiting, it is **spinning**. The
visible 10s of wall-clock is `DurableShellExecutor.executeCore`'s
`transcriptPumps.forEach { it.join(10_000) }` timing out on a pump that never reaches EOF.

### The mechanism, as far as the evidence reaches

Two facts fit every observation, and the fix has to reconcile both:

1. The pump asks for `maxOf(1, minOf(window.size, redacted.available()))` bytes. With a
   lookahead-holding redactor, `available()` returns `0` until `maxLiteral` bytes have been
   seen, so the floor of `1` makes every read a **one-byte** read that blocks until the child
   produces more. That is a latency floor measured in bytes — the same defect shape the comment
   above that line already warns about, reintroduced on the *read* side.
2. `RedactingOutputIngress.write` returns early on `length <= 0`, so a zero-length read is a
   silent no-op. Combined with (1), a pump that cannot make progress writes nothing, spins, and
   the transcript is truncated to whatever happened to be flushed before the join timed out —
   which is why `REDACTOR-3` lands exactly one byte and `REDACTOR-2` lands none.

### Why this is not fixed in this block

It is a defect in `DurableShellExecutor`'s pump loop and `StreamingRedactor`'s read contract —
the durable-shell substrate that ADR-0072 and ADR-0103 certify, in `pipeline-step-sdk/runtime`
and `pipeline-credentials-api`, not in `pipeline-application`. Fixing it inside a "console frame
integrity" block would be exactly the scope creep this receipt exists to prevent, and the fix
has a real design choice in it (drop the `ready` floor, make the redactor block instead of
short-read, or move the lookahead to a drain step) that deserves its own ADR-visible slice.

### Why this is not fixed in this block

The fix is in the process-launch contract, not in the console verb: either keep a handle on the
real `bash` process, or drain the pipes synchronously before the launcher exits, or drop the
`setsid` fork. All three touch the durable-shell substrate that ADR-0072/ADR-0103 certify, and
all three are a separate slice with its own burn-down. Fixing it inside a "console frame
integrity" block would be exactly the kind of scope creep this receipt exists to prevent.

### Consequence for certification

`console --frames` and `console --follow` are **not functionally certified**, and cannot be until
this defect is closed. The rc1 receipts for CON-2.2 and CON-2.3 stay
`IMPLEMENTED_UNCERTIFIED`; this receipt does not upgrade them.

## Test evidence

`ConsoleRc2FrameIntegrityTest` — 4/4, all crossing the real
`FrameIndexedObservationOutputReader` over a real `SegmentOutputStore`, publishing frames through
the production order (declare → reserve → write → commit → append). No fabricated
`ObservationOutputPage` values: a fabricated page cannot prove cursor arithmetic, because the
test would be supplying the ordinal production then reads.

| Test class | Pass / Fail / Error |
|---|---|
| `ConsoleRc2FrameIntegrityTest` (new) | 4 / 0 / 0 |
| `ConsoleFollowTest` | 22 / 0 / 0 |
| `ConsoleFrameFiltersTest` | 24 / 0 / 0 |
| `MainConsoleCliPerFrameTest` | 14 / 0 / 0 |
| `CliParserUnificationTest` | 20 / 0 / 0 |
| `R4ObservabilityCliInstallDistCanaryTest` | 6 / 0 / 0 |
| `ObsE5TailTest` | 8 / 0 / 0 |
| `LiveOutputDrainTest` | 7 / 0 / 0 |
| **Total** | **105 / 0 / 0** |

## Harness fidelity note

`ConsoleRc2FrameIntegrityTest` is HF1 (in-process, real store), not HF2. It is declared as such
in its own KDoc. The rc1 canary classes used HF2 installDist paths with fabricated frames and an
`assumeTrue` skip; this class deliberately does neither, and says why in the class KDoc: an
installDist canary for the frame read cannot be written while the Output Plane is empty, which is
the fourth defect above.

`ShTranscriptRedactionReproductionTest` is the structural fix for the gap that let the fourth
defect reach rc1. Every pre-existing test that reads frames fabricates them
(`M1DCrossJvmFollowTest.writeOutputFrame`, `ObservationOutputReaderTest`,
`ConsoleFollowTest`); the only tests that drove a real `sh` (`ConsoleReadServiceTest`) did so
with the redactor absent. Nothing in the suite held both variables at once. The new class holds
them together and splits on exactly one.

## Test evidence (including the reproduction class)

| Test class | Pass / Fail / Error / Skip |
|---|---|
| `ConsoleRc2FrameIntegrityTest` (new) | 4 / 0 / 0 / 0 |
| `ShTranscriptRedactionReproductionTest` (new) | 1 / 0 / 0 / 2 (`REDACTOR-2/3` `@Disabled` RED rows) |
| `ConsoleFollowTest` | 22 / 0 / 0 / 0 |
| `ConsoleFrameFiltersTest` | 24 / 0 / 0 / 0 |
| `MainConsoleCliPerFrameTest` | 14 / 0 / 0 / 0 |
| `CliParserUnificationTest` | 20 / 0 / 0 / 0 |
| `R4ObservabilityCliInstallDistCanaryTest` | 6 / 0 / 0 / 0 |
| `ObsE5TailTest` | 8 / 0 / 0 / 0 |
| `LiveOutputDrainTest` | 7 / 0 / 0 / 0 |
| `ConsoleReadServiceTest` (control, crosses a real `sh`) | 12 / 0 / 0 / 0 |

The two `@Disabled` rows are a characterised defect, not passing tests. They are disabled with
the reason inline and a link to this receipt, per `AGENTS.md` ("borrar una UAT antes de la
migracion es prohibido: debe quedar deshabilitada con enlace"). When the defect closes, the rows
are deleted and their assertions promoted — and that transition must be explicit, never a
silent rewrite of what is expected.

## Refusal prefixes

Untouched. `console-refused:` is produced by `ConsoleReadService.renderRefusal` and `Error:` by
`renderConsoleCliRefusal`; neither was modified by this block.

## What the next block (0.61.0-rc3) must do

Per the audit roadmap, block 2 (P0 filters + composition) is blocked behind this one, because a
filter over an empty Output Plane cannot be proven. The order is therefore:

1. **Close the fourth defect** (durable-shell transcript population) — its own slice, its own
   burn-down, its own HF2 canary.
2. **Then** CON-2.2 filters: real `--kind`, stage/step name resolution, follow+filter
   composition.
3. **Then** CON-2.3 termination semantics: `RunFinished` vs `OutputSealed` vs poll-limit
   exhaustion (a poll-limit exhaustion must not exit 0).

## Reference

- Audit: the v0.61.0-rc1 compliance review (2026-10-14), findings P0-1..P0-4 and P1-1..P1-3.
- Reader: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ObservationOutputReader.kt`
- Verb: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt`
- Frame index: `v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentFrameIndex.kt`
- Executor: `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutor.kt`