# R4 / WU-B — Transcript pump completion: no silent loss, no unearned seal

- **Status:** Block B **CLOSED** at HF0/HF1. Both defects are closed and each has a mutation that
  kills it. One consequence of the fix is a **semantic change the operator should see explicitly**
  (see "Sealing"), and one end-to-end claim is **BLOCKED**, not claimed.
- **Base:** `7c0d0fba` (`main`, after #126, #127, #129, #130).
- **Branch:** see the commit for this block. **Train:** 0.61.0-rc4 (not yet published).
- **Scope note:** this block does NOT touch the redactor, the console CLI, or the harness
  migration. Those are WU-A/B, WU-RC3/RC4 and the post-publication block respectively, and the
  operator's instruction was explicit that the harness migration and the redactor fix not share a PR.

## One-line summary

The runtime could make two claims it had not earned — "the transcript was persisted" and "this
operation's output is complete" — while holding no evidence for either. A pump exception was
discarded with a comment, and a `join` timeout was indistinguishable from a `join` that succeeded.

## Defect 1 — pump exceptions were swallowed

```kotlin
} catch (_: Exception) {
    // Child killed / pipe broken mid-flight: keep whatever
    // sanitized bytes were already persisted.
}
```

That sentence is true of the bytes that arrived and silent about the ones that did not. Three
distinct losses crossed it:

| Path | What is lost | Why it was invisible |
|---|---|---|
| `target.write` throws | every subsequent byte on that channel | `StreamProcessOutputSink.refusal()` returned `null` by contract: *"a stream write either throws to the pump or succeeds, and the pump's own handling covers that"* — a sentence describing the bug |
| `target.close` throws | **the tail, permanently** | `close` is where a store-backed sink commits its final reservation, so this is the worst of the three and had no channel at all |
| `redacted.read` throws | the remaining undecidable bytes | the pump's own `catch`; no seam, no channel |

A transcript missing its tail is indistinguishable at the read side from a step that never printed
one. That is the "short, ordered, plausible" failure this whole path exists to prevent.

### Fix

One new class, `GuardedProcessOutputSink`, wraps every sink and converts any `Throwable` into a
`ProcessOutputRefusal.WriteFailed`. The pump writes and closes **through the guard**, so there is
exactly one place where an exception becomes a refusal — not one per sink, and not the pump's
`catch`. `transcriptRefusal()` already existed and already fed a typed step failure; it was simply
never reachable for these paths.

Two deliberate properties:

- **`close` is never short-circuited** on an existing refusal. `close` publishes the final
  reservation; skipping it would turn a recoverable write failure into an *additional, unreported*
  loss of the tail.
- **`detail` never carries a process byte.** It reaches a failure record, an event and a journal
  row, so a refusal that quoted the transcript would publish the secret the redaction seam withholds
  — through the diagnostic channel instead of the Output Plane. `PUMP-1` asserts this.

## Defect 2 — `join(10_000)` was not a bound

```kotlin
transcriptPumps.forEach { it.join(10_000) }
```

`join(timeout)` returns identically whether the thread finished or the timeout elapsed. The runtime
then projected a transcript it had no evidence was complete, sealed it, and classified the step —
the same artifact defect 1 produces, reached by the opposite route.

### Fix

`drainTranscriptPumps(pumps)` waits **and then asks whether the wait succeeded**, via
`Thread.isAlive` after the join. A pump still alive yields `ProcessOutputRefusal.DrainIncomplete`,
which takes the same typed-failure branch.

## The type change this forced

`DrainIncomplete` has no `Throwable` — it is an *absence*, not a failure. Collapsing it into the
existing `data class ProcessOutputRefusal(detail, cause)` would have forced a fabricated exception
into a non-nullable field. So `ProcessOutputRefusal` became a **sealed interface** with one case per
real shape, each carrying only the payload it has:

```kotlin
sealed interface ProcessOutputRefusal {
    val detail: String
    data class WriteFailed(override val detail: String, val cause: Throwable) : ProcessOutputRefusal
    data class DrainIncomplete(override val detail: String, val waitedMs: Long) : ProcessOutputRefusal
}
```

The consumer `when`s exhaustively over the two cases, so a third shape becomes a compile error rather
than an unhandled one.

## Sealing — a semantic change, stated plainly

`ShExecution.invokeShell` called `sealOperationTail` **unconditionally**. A seal is the durable
statement "this operation's output is complete", and it is the statement `console --follow`
terminates on (`ReachedSealedOutput`). So a refused transcript was sealed anyway, and the refusal
produced an artifact telling every downstream reader the short transcript was final.

For `DrainIncomplete` this was not merely inaccurate but **unsafe**: the pump is still alive and will
keep writing, so sealing races a live writer against the store's finality marker.

The change:

```text
seal the tail   <=>   the terminal does NOT declare the transcript incomplete
```

**What is withheld:** only the completeness claim. The terminal is still known, the step still fails
with an explicit `INFRASTRUCTURE` cause, and the transcript is still readable up to the refusal.

**What is NOT changed:** `Exited(7)`, `Cancelled(TIMEOUT)`, `Lost` and every other failure still
seal. Those operations produced no transcript at all, so their streams are complete precisely
because there was nothing to write — treating them as "incomplete" would unseal every failed and
timed-out step in the product, a far larger change than this defect authorises. `SEAL-3` pins that
boundary.

**Operator-visible consequence:** for a refused step, `console --follow` now reports
`PollBudgetExhausted` (exit 3) rather than `Completed` (exit 0). That is the honest answer — the
runtime genuinely cannot say the output is finished — and it is the same answer `follow` already
gives for any other incomplete run.

**This is the one decision in the block that a reviewer may want to overturn.** It is listed here
rather than buried because it changes what downstream observers see for failed steps.

## The refusal code is one constant

`OUTPUT_PERSISTENCE_REFUSED` now lives next to the refusal it describes. The runtime that produces
it and the caller that acts on it read the same symbol from the same module, so a rename cannot
leave half the contract behind.

## Evidence

### Mutations, 1:1 with the rows they flip

| Mutation | Kills | Leaves green |
|---|---|---|
| `M-PUMP-catch`: restore the bare `target` + `catch (_: Exception) {}` | `PUMP-1`, `PUMP-2`, `PUMP-3` | `PUMP-4`, `PUMP-5` |
| `M-PUMP-join`: `val stillDraining = emptyList<Thread>()` | `PUMP-4` only | `PUMP-1`, `PUMP-2`, `PUMP-3`, `PUMP-5` |

Both were applied, run, and reverted; the restored source was verified by re-running to green.

### Suite evidence

| Check | Result |
|---|---|
| `TranscriptPumpCompletionTest` | **5 / 0 skipped / 0 failed** |
| `ShTranscriptSealIntegrityTest` | **3 / 0 skipped / 0 failed** |
| `ShTranscriptRedactionReproductionTest` | 3 / 0 / 0 |
| `ConsoleRc3FilteredFollowTest` | 9 / 0 / 0 |
| `ConsoleRc4FollowTerminationTest` | 5 / 0 / 0 |

### Harness fidelity

- **`TranscriptPumpCompletionTest` is HF1** and names its entry point: `DurableShellExecutor.executeTerminal`.
  Real `setsid` wrapper, real pipes, real pump threads, real terminal projection. Only the
  destination is composed — and it has to be, or the row cannot inject a failure at all. It receives
  real bytes from a real pipe, and `PUMP-5` runs the same sink class with the failure switched off,
  so the composition is proven able to observe a full transcript.
- **`ShTranscriptSealIntegrityTest`**: `SEAL-1` is HF1 (real `sh`, real store, real reader);
  `SEAL-2`/`SEAL-3` are HF0, which is the faithful level for a pure predicate over the terminal ADT.
- **`SEAL-1` asserts on the consumer, not on a re-seal.** `store.seal(stream)` on an *unsealed*
  stream would seal it and turn a red into a green — a harness able to make its own subject pass.
  It instead asks `runFollowFrames`, which reaches `FollowExit.Completed` only when every stream
  reads back `OutputTailState.Sealed`. Nothing is re-derived.

### BLOCKED — stated, not papered over

**"A real failing store ⇒ no seal", end to end, is NOT proven.** `OutputPlaneProvider` hands out a
concrete `SegmentOutputStore` with no substitution seam, so injecting a store that refuses would mean
opening the provider — wider than the defect and wider than this block authorises.

What IS proven, and is enough to trust the wiring: `TranscriptPumpCompletionTest` (HF1) proves a real
pump failure produces exactly the refused terminal, and `SEAL-2` proves the production predicate
recognises it, and `SEAL-1` proves the real path that consults it still seals. The composition of
those three is the claim; the single-shot integration test that would say so in one row does not
exist. Recorded as `BLOCKED` rather than `PASS`.

## Reference implementation consulted

- **Reference implementation consulted:** none applicable. This block changes when the runtime is
  allowed to claim a transcript is complete, not how any transcript is captured; the capture and
  redaction path is the existing one, untouched.
- **Behaviour adopted:** fail-closed on an unproven completion claim — the same rule the redaction
  seam already follows for bytes.
- **Intentional deviations:** `close` is never skipped after a prior refusal, because the tail is
  the most valuable thing left to publish.
- **Security implications reviewed:** redaction was **not** weakened and no new byte path was added.
  The only new outward-facing string is `ProcessOutputRefusal.detail`, which is asserted in `PUMP-1`
  to contain no process byte — the diagnostic channel must not become a side door around the
  Output Plane. No new dependency was introduced: the guard lives in `pipeline-step-sdk:runtime` and
  the runtime still does not depend on `pipeline-credentials-api`.
- **Tests demonstrating the contract:** `TranscriptPumpCompletionTest`,
  `ShTranscriptSealIntegrityTest`.

## Remaining

1. **Frame fragmentation**: median one byte per frame. The pump asks for
   `maxOf(1, minOf(window.size, redacted.available()))`, and with a one-byte ring on the empty-registry
   path `available()` is 0 after each drain, so most reads are forced single-byte reads. Content is
   byte-exact and ordinals are dense, so this is **efficiency, not correctness** — but it inflates the
   frame index by three orders of magnitude. The operator rejected "drop the `maxOf(1, …)` floor" as
   an *isolated* fix (a zero read size does not guarantee progress), and WU-A has since made progress
   structural, so the sizing question is open again — but it needs its own block and its own
   measurement, not a drive-by edit here.
2. **A `transcriptRedactor` that fails to CONSTRUCT** escapes `launch` as
   `IllegalStateException("Failed to launch durable shell")` rather than becoming a typed terminal.
   Adjacent to defect 1 and worth closing; deliberately out of scope for this block.
3. **`v2/build.gradle.kts` still says `version = "0.60.0"`** while the train has published
   `v0.61.0-rc1`. `PRODUCT IDENTITY LAW` problem for Block D.
4. Block C: re-enable and certify the remaining installDist canaries.