# M1 integration re-ground — receipt

**Work item:** `eea09043-96fc-43e1-a03b-686f79198111` (M1), cycle `p-733fb505b5a6bd2d/rp7-sem-s4-scripted-runtime-v2`
**Branch:** `m1/output-plane` — not merged, not promoted
**Re-grounded against:** `origin/s4-a1b-scripted-shell-spine` = `e0500bf811c8aff554fe14ab37d341749d599d7b`
**Base this work started from:** `d0fa34e8`

---

## 1. The re-ground found upstream had moved

At the start of M1 the upstream head was `d0fa34e8` and the branch sat 4 commits ahead, 0 behind. It
does not sit there any more:

```text
$ git rev-list --left-right --count origin/s4-a1b-scripted-shell-spine...HEAD
3	5
```

Three commits arrived on S4 while this line was being written:

| Commit | Subject |
| --- | --- |
| `4429e4ca` | `feat(recovery): el valor recuperado llega al programa` |
| `ce551860` | `docs(adr): conformance de ADR-S4-R1 a SATISFIED tras F1-C` |
| `e0500bf8` | `chore(api): regenerar el dump ABI de pipeline-domain` |

They touch 24 files, ~1981 insertions: `RecoveryInterpretationEngine`, `RunningSubprocessRecovery`,
`RecoveredExecutionMaterializer` (new), `StepDispatchEngine`, `ScriptedRegistryInvoker`,
`RecoveredStepProjection` (new, `pipeline-domain`), the S4 truth-matrix spike, and the ABI dump.

## 2. Zero file overlap — and why that was the wrong thing to be reassured by

```text
$ comm -12 <(upstream files) <(m1 files)
(no output)
```

Not one file is touched by both sides. The instinct is to read that as "the branches are
independent, nothing to reconcile". That reading is wrong, and it is wrong in the direction that
hurts: **the interesting integration risks in this line are exactly the ones that produce no merge
conflict**, because they are about two components agreeing on a fact — where bytes live, who deletes
them, who is allowed to serve them — rather than about two components editing the same lines.

Git can only see the second kind. So the overlap check was used as a signal to go *looking*, not as
a reason to stop.

## 3. What looking found: the Output Plane lives in someone else's scratch space

The shell substrate owns the control-directory lifecycle and is aggressive about it, **inside the
same invocation** that produces the output:

- `ShExecution.kt:333-350` — on a successful exit, deletes `console.log` and then the emptied
  per-operation `controlDir`.
- `DurableShellExecutor.kt:545-566` — on a successful exit, walks the per-operation `controlDir`
  and deletes **everything that is not `console.log`**; in capture mode it calls
  `deleteRecursively(controlDir)`. Every exception in that walk is swallowed.

M1 survives this today by an accident of naming:

```text
controlDir     = {controlDirRoot}/{opId}            <- what S4 deletes
Output Plane   = {controlDirRoot}/output-plane      <- what M1 writes
```

`output-plane` is a **sibling** of the per-operation directories, so the recursive walk never
reaches it. The consequence is the good one: **no committed transcript is lost at integration
today**, and that is now checked rather than assumed.

But nothing asserted it. The invariant held because a constant (`OUTPUT_DIR = "output-plane"`)
happened to differ from an `opId`, and it would have stopped holding the first time anyone decided
the Output Plane belonged with the rest of an operation's control data — a reasonable-looking
refactor, in a different file, invisible to `comm`.

The same coupling points the other way too: nothing in S4's retention policy knows the Output Plane
exists, so the store is never pruned for the life of the control root. That one is a leak rather
than a loss, and it is a product decision, not a defect.

## 4. What was done about it — and what was deliberately not done

Added: `OutputPlaneSurvivalFitnessTest` (3 tests, real `sh` processes) in `pipeline-application`.

It states the invariant the way an operator experiences it — **a later process, with no cached
store, must still read the committed bytes** — because the pre-existing
`OutputSingleAuthorityFitnessTest` reads through the live store that just wrote, and therefore
proves the bytes were *committed* without proving they are *still there*. Those are different
claims and the gap between them is where this defect lived.

The exit-0 precondition is asserted, not assumed, and the test says why that is not decoration:
the cleanup under test only runs when the step exits 0, so a failing step would leave its
transcript on disk for the wrong reason and make the test green without ever exercising the hazard.
`UnitValue` witnesses that exit **by construction** — under `ShellReturnMode.NONE`,
`classifyShellTerminal` maps `Exited` to `UnitValue` if and only if `exitCode == 0`, and to
`scriptFailure()` otherwise.

**Not done, on purpose.** `output.txt` and the recovered `returnStdout` value are S4's business and
were left exactly as found. Reading them confirmed they are *not* a second byte authority:
`readOutputText` defaults to `CaptureRetainPolicy.READ_THEN_DELETE`, so `output.txt` is a staging
buffer deleted on read and the journal is the typed value's home. That is the same
staging-then-authority shape M1 uses for `console.log`, so S4 is already consistent with the
Output Authority decision. No carve-out is needed, and none was invented.

## 5. Q5 and Q8 — both closed, and Q5 was a real contract gap

Both were open rows in the P4 harness. Neither is open any more.

**Q8 was a harness bug, and the guard is real.** The replacement referenced
`reservationsReleased`; the accumulator is `releasedReservations`. A name typo, not a type problem.
Corrected, the mutation is killed: recovery idempotence in the stable field is genuinely asserted.

**Q5 was not a harness bug. It was a missing test.** The old mutation clamped
`readRangeLocked(..., minOf(to, extent))` and the suite stayed green, which the earlier session
recorded as unexplained. The cause is that `readRange` has **no live clamping branch to mutate**:

```kotlin
if (to > extent) return Refused(OffsetBeyondCommitted(to, extent))
readRangeLocked(layout, stream, from, to)
```

The guard directly above pins `to <= extent`, so `minOf(to, extent)` is identically `to`. The
mutation rewrote a term that was already dominated — a tautology, not weak coverage.

Repointed at the mutation it was reaching for — *delete the refusal* — the harness immediately
surfaced the real problem: **nothing tested that `readRange` refuses a range ending past the
committed extent.** The paged path proves the refusal for cursors, and the inverted-range test only
ever reached the `to <= from` branch. A contract branch was indistinguishable from its neighbour.

`a range that ends past the committed extent is refused, never clamped` now covers it, and also
asserts that a range ending *exactly* at the extent is still served, so the refusal is about
exceeding the extent rather than ranges being fragile in general.

## 6. Evidence

Figures are read from the JUnit XML, not from exit codes.

| Suite | Tests | Failures | Skipped |
| --- | --- | --- | --- |
| `OutputPlaneSurvivalFitnessTest` (new, real `sh`) | 3 | 0 | 0 |
| `OutputPlaneConformanceTest` (P4, +1 for the Q5 gap) | 9 | 0 | 0 |
| `SegmentOutputStoreTest` (P1) | 25 | 0 | 0 |
| `m4-mutation-prove.py` | 8 mutations | **8 killed** | 0 vacuous |
| `m5-mutation-prove.py` | 3 mutations | **3 killed** | 0 vacuous |

`OutputPlaneConformanceTest` holds 10 `@Test` methods; the tenth is the `performance`-tagged soak and
is excluded from the standard task by `excludeTags("performance")`, so 9 is what the table counts.

### m5's fourth row was retired, not fixed

`I2` — nesting the store one level deeper, `{root}/output-plane/nested` — left the suite green. The
cause is not weak coverage: that mutation **does not break the property it names**. Nesting deeper
under the root still leaves the Output Plane a sibling of the per-operation directories, so the
invariant genuinely still holds and a green suite is the correct outcome. A mutation that does not
falsify its own claim is not evidence.

Worth being precise about, because the geometry test looks like it might be unfalsifiable: it is
not. `I1` moves the store under the per-operation directory, after which `{root}/output-plane` does
not exist at all and `the Output Plane is not inside any per-operation control directory` fails on
its first assertion. `I1` is the mutation that attacks the geometry, and it is killed.

This is now the third row in this line to be retired for the same reason as `Q2` — a mutation that
cannot fail. Two of the three (`Q5`'s original form, `I2`) turned out to point at something real
just underneath: `Q5` concealed a missing test for the `readRange` refusal. `I2` concealed nothing
beyond its own bad framing.

## 7. Status

M1's contracts are unchanged by this re-ground; nothing in the Output Store, the cursor, the read
API or the S5 handoff moved. What changed is that an integration hazard which was previously an
unstated accident is now a named invariant with a guard and a mutation harness.

Open, and **not** addressed here:

- Merge to `origin/s4-a1b-scripted-shell-spine` and the full integration gate remain human review,
  by design. This branch is 3 behind upstream and 5 ahead; it is not rebased or merged.
- The Output Plane is never pruned. Retention is a product decision with no owner yet.
- The crash tests reproduce the durable **state** a process death leaves; they do not `kill -9` a
  process. Same principle, different artefact, still not built.
- S4 semantics (`Unstable`, R14, recovery interpretation) were not touched. M1 remains blocked on
  S4 only where a decision is genuinely theirs to make, and the S5 handoff is unchanged: S5 may
  reference an `OutputStreamId`, never carry a transcript.
