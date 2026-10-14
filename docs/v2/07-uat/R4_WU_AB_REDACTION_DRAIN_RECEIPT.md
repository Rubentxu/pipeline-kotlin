# R4 / WU-A+B — Redactor drain and Output Plane population

- **Status:** the fourth defect is **CLOSED**. `pipelinek run` populates the Output Plane, verified
  against `installDist`. This does not by itself certify `console --follow` (Block C remains), but
  it removes the blocker that stopped every block since rc1.
- **Base:** `1784d64b` (`main`, after #126, #127, #129).
- **Branch:** `r4/0-61-rc4-redaction-drain`. **Train:** 0.61.0-rc4 (not yet published).
- **Architecture decision (operator):** use the existing `StreamingRedactor.feed/take/finish` state
  machine. Do not block safe bytes waiting for a buffer to fill; do not emit pending bytes that
  could begin a secret.

## One-line summary

The Output Plane was empty because the redactor's read loop could not make progress with a full
lookahead ring — a 100% CPU spin that consumed no bytes and, on the empty-registry path, hit
almost immediately. Three defects in `pipeline-credentials-api`, all confirmed by reading the
sources before changing anything.

## The three defects

### 1. The spin — the whole bug

```kotlin
val chunk = minOf(inputLimit - inputOffset, len - written, state.maxLiteral - state.ringCount)
if (chunk > 0) { feed(state, inputBuffer, inputOffset, chunk); inputOffset += chunk; continue }
```

The feed is bounded by the ring's **free space**. With the ring full and nothing decidable at its
head, `chunk == 0`, the feed was skipped — and the early-return also required room:

```kotlin
if (written > 0 && !state.sourceExhausted && state.ringCount < state.maxLiteral && ...) return written
```

**No branch advanced `written`.** The loop spun at 100% CPU while the child kept writing. That is
the `user 25s` against `real 16s` recorded in the rc2 receipt, and it is why the transcript was
truncated to whatever happened to flush before the 10 s join timed out.

`feed` already had the remedy — its step 3 emits a full ring's head — but the wrapper **could never
call `feed` with a full ring**, so the remedy was unreachable from the path that needed it.

**Fix.** Extract `relieveFullRing` / `emitHeadAsPlain` from `feed` and call it from `read` when
`space == 0`. One implementation, two callers, which is what stops them drifting.

The emitted head is safe *by construction*: it is emitted precisely because no literal starts with
it, so no secret can span it.

### 2. EOF truncation

```kotlin
val final = this@StreamingRedactor.finish(state)
val n = minOf(final.size, len - written)
System.arraycopy(final, 0, b, off + written, n)
written += n
return ...
```

`finish` empties **and zeroes** the state's output queue, so anything that did not fit the caller's
buffer was gone: the bytes left the pipe, the state was cleared, and the next `read` reported "no
more data" about a transcript that still had more in it. A one-byte read at EOF truncated by
everything it could not hold.

**Fix.** Retain `finalFlush` / `finalFlushOffset` and serve the remainder as the first thing any
subsequent read does. `close()` zeroes the retained buffer along with the rest — decided output can
still be secret-shaped, so leaving it in a discarded object is exactly the leak the class prevents.

### 3. The fiction

`literalSeam()` returned `listOf(SCRUB_MARKER)` for an empty registry — a real four-byte literal
that real output can contain (`****` is an ordinary thing for a child process to print) — and
`maxLiteralByteLength()` returned `MIN_SECRET_WINDOW` (7), forcing a seven-byte lookahead on a
stream that had no secret to look for.

**Fix.** An empty registry is a **passthrough**, and it says so by having no literals at all;
its lookahead is 1, because a one-byte ring is fully decided the moment its byte arrives (no
literal can start with it, because there are no literals). It is 1 rather than 0 because
`feed`/`take` need a byte to move.

This is also *why* the empty case was the maximally exposed one: with no literals almost no head is
ever decidable, so the ring filled after a handful of bytes and defect 1 became near-certain.
`REDACTOR-2` lost **every** byte; `REDACTOR-3` lost all but one.

## What changed, and what did not

- `SecretPatternRegistry.literalSeam` / `maxLiteralByteLength` — empty-registry path only.
  `MIN_SECRET_WINDOW` and the non-empty path are untouched.
- `StreamingRedactor.RedactingInputStream.read` — the progress branch and the retained flush.
- `StreamingRedactor.feed` / `drainOneHead` — refactored to share the new helpers. **The matcher
  and the state machine are the same ones**; no second redaction algorithm was introduced.
- `close()` additionally zeroes `inputBuffer`. That assertion already existed in
  `StreamingRedactorTest`; the fix made the branch reachable by letting the loop leave the buffer
  partly consumed, and closing the gap is correct on its own terms — `inputBuffer` holds raw bytes
  straight off the pipe.

## A fourth defect the PRODUCT exposed and no unit test could have

`ConsoleOperationSelection.resolve` traversed the event sequence twice — once for
`StageStarted`, once for `StepStarted`. `SqliteEventStore.eventsFor` returns a sequence that can be
consumed **only once**, so the second traversal threw
`IllegalStateException: This sequence can be consumed only once`.

Every unit row passed `journal.asSequence()`, which is not constrained, so the function looked
total. Against the real distribution, `--stage NAME` and `--kind` both died. Fix: materialise
once, which also makes the function total over any sequence. `ConsoleOperationSelectionTest.RC3-10`
pins a `constrainOnce()` sequence so the gap cannot reopen.

**The acceptance criterion that caught it was "comprobar `console --frames` ... filtros por nombre y
kind" run against a real binary.** No amount of HF0/HF1 would have.

## Evidence

### Regressions re-enabled, and what they now say

| Row | Before | After |
|---|---|---|
| `REDACTOR-1` (no registry, control) | GREEN | GREEN |
| `REDACTOR-2` (empty registry) | `@Disabled` **RED** | **GREEN** |
| `REDACTOR-3` (registry with a secret) | `@Disabled` **RED** | **GREEN** |
| `ConsoleFollowTest` installDist canary | `@Disabled` | **GREEN, 0 skipped** |

`REDACTOR-2` measured `console-refused: unknown-stream …` (never declared) and `REDACTOR-3` read
back `'t'` — one byte of sixteen. Both are now full transcripts.

The `ConsoleFollowTest` canary is the one worth reading twice: it had been **green while observing
nothing**, because the empty Output Plane and the exit-0-on-exhaustion contract cancelled each
other. RC4 separated those two outcomes, which turned it red and made the cause visible; this
block closed the cause, so it is green on its own terms (22 tests, 0 skipped).

### Against the installed distribution

```text
$ pipelinek run --db … --control-root … <200-line pipeline>
$ console --control-dir … <runId> <runId>-s0-0 --frames --format jsonl
frames: 5   reassembled: 6000 / 6000 bytes   EXACT MATCH: True   ordinals dense 0..4: True
```

```text
# with a registered secret (pipelinek.redaction.canaries=true)
$ console --control-dir … <runId> <runId>-s0-0 --frames --format text
token=[****] done                                  # 18 bytes
$ grep -r 'GHS6_CANARY_7f3a9c2e1b4d5e6f' <controlDir>   # -> no match
```

```text
$ console … --db <journal> --frames --format jsonl --stage main     -> 5 frames
$ console … --db <journal> --frames --format jsonl --kind sh       -> 5 frames
$ console … --db <journal> --frames --format jsonl --stage nope
Error: no stage named 'nope' in this run (known: main)              # exit 2
$ console … --frames --format jsonl --kind sh                        # no --db
Error: --kind name stage/step/kind, which are recorded only in the
       events journal — pass --db <path> …                           # exit 2
```

### Suite evidence

| Check | Result |
|---|---|
| `pipeline-credentials-api` (full module) | GREEN |
| `ShTranscriptRedactionReproductionTest` | 3 / 0 / 0 — **0 skipped** |
| Broad sweep (`Obs*`, `Lpr*`, `Console*`, `ShTranscript*`, `ObsPc2*`, adversarial, in-memory CLI) | **325 tests, 3 failed, 2 skipped** |
| Base-vs-head for the 3 failures | **7 tests / 3 failed at BOTH**, same three names |
| `ConsoleFollowTest` | 22 / 0 / 0 |

The three remaining failures are `ObsBJvmDeathOutputRecoveryUatTest`,
`ObsC23NoChannelFusionFitnessTest` and `ObsPcReadRecoverySeamFitnessTest`. Each was re-run at base
by stashing this block: **identical counts and identical test names**, so this block introduces no
regression. (The sweep also went from 6m49s to a comparable run in 12 s for the same classes —
the spin was costing wall-clock as well as the transcript.)

## Harness fidelity

- `StreamingRedactorTest` (23) and `SecretPatternRegistryTest` (9) are HF0/HF1 against the real
  state machine. The blocker cases are driven at every fragmentation offset, not one.
- `ShTranscriptRedactionReproductionTest` is HF1 with a **real `sh`**, and it is the controlled
  pair: same harness, one variable (the registry). Without `REDACTOR-1` the other two would be
  ambiguous between "the redactor broke it" and "this harness cannot observe anything".
- The distribution evidence above is HF2 and is the only thing that can show the Output Plane
  populated end to end.

## Reference implementation consulted

- **Reference implementation consulted:** none applicable — the mandate selected the existing
  `StreamingRedactor` state machine over an external implementation, and the decision is recorded
  in the operator's architecture note.
- **Behaviour adopted:** incremental `feed → take → finish` drain; a full ring is relieved by
  emitting a head that provably begins no literal.
- **Intentional deviations:** none. No alternative algorithm was considered, per the mandate.
- **Security implications reviewed:** redaction was **not** weakened. A registry with no secrets is
  a passthrough because it has nothing to decide — that is a statement about the input, not a
  relaxation of the rule. A registered secret still never reaches the Output Plane (verified by
  grep over the whole control dir). The retained EOF flush is zeroed on `close()` for the same
  reason the state is.
- **Tests demonstrating the contract:** `StreamingRedactorTest`, `SecretPatternRegistryTest`,
  `ShTranscriptRedactionReproductionTest`, `ConsoleFollowTest`.

## Remaining

1. **Pump exceptions are swallowed** (`catch (_: Exception)` with only a comment) and
   **`join(10_000)` is not followed by an `isAlive` check**, so the runtime can still seal streams
   and classify the step while a pump is alive. `transcriptRefusal()` already exists and is the
   right channel; not yet wired for pump failure.
2. **Frame fragmentation**: median one byte per frame. The pump asks for
   `maxOf(1, minOf(window.size, available()))`, and with a one-byte ring `available()` is 0 after
   each drain, so every other read is a forced single-byte blocking read. Content is byte-exact and
   ordinals are dense, so this is efficiency, not correctness — but it inflates the frame index.
3. **`v2/build.gradle.kts` says `version = "0.60.0"`** while the train has published
   `v0.61.0-rc1`. That is a `PRODUCT IDENTITY LAW` problem for Block D, and it is why the
   installed binary reports `pipeline 0.60.0`.
4. Block C: re-enable and certify the remaining installDist canaries; the console filter
   acceptance criteria are now testable for the first time.
