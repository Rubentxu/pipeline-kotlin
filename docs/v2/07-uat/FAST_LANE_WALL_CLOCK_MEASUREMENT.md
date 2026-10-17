# Fast-lane wall clock — measured 2026-10-17

**Base SHA:** `15640e98` (`main`, clean tree)
**Command:** `v2/gradlew -p v2 :pipeline-application:test --rerun -Plane=fast`
**Budget:** 1800 s (`timeout`), **observed: 1418 s (23m38s)**, `BUILD FAILED`
**Verdict:** completes, **not fast**. No CI exists for this SHA (see AGENTS.md
2026-10-03 policy); this run is the whole verification.

## Why this receipt exists

A prior claim in team memory read: the fast lane *should* be fast now that the
`installed-contract` tag split landed, but the wall clock had never been
re-measured. An unmeasured claim is not a green. This closes it — in the
negative direction.

Two earlier attempts (900 s, then 1500 s) were **killed**, so neither produced a
verdict. This is the first completion. That means it is **not** evidence that the
split made anything faster: a killed run cannot be compared against a completed
one. It is evidence about the current state only.

## Result

```text
BUILD FAILED in 23m 38s
70 actionable tasks: 4 executed, 66 up-to-date

384 classes   2871 tests   5 failures   0 errors   230 skipped (8.0%)
```

## Where the 23 minutes actually go

```text
sum of per-class time   1408.6 s     vs wall clock 1418 s  ->  the classes ARE the cost
median class            0.02 s
mean class              3.67 s
classes over 10 s       29
top 15 classes          71.6 % of total
```

Two consequences, both load-bearing:

1. **This is not fork overhead.** Class time accounts for essentially the whole
   wall clock, so per-class JVM spawning is not the cost. It was a plausible
   suspect (`forkEvery`) and it is measurably not this.
2. **It is a long tail, not a broad regression.** The median class takes 20 ms.
   15 classes out of 384 account for more than 71% of the time.

| class | time | tests |
| --- | ---: | ---: |
| `GitCheckoutExecutorAdversarialTest` | 168.8 s | 7 |
| `UatLocal008CredentialsTest` | 146.4 s | 27 |
| `UatLocal011WorkflowControlTest` | 94.3 s | 15 |
| `UatLockBlockDurableTest` | 86.0 s | 9 |
| `UatLocal009TopStepsTest` | 70.5 s | 13 |
| `UatInputBlockDurableTest` | 64.6 s | 9 |

### The population the lane split did not touch

The tag split covers the two doors that require an **installed distribution**:
`AppBinSupport` and `MainKt`. These 29 slow classes mostly do neither. They are
in-process harnesses that fork `git`, `bash`, or the runtime by other routes, and
nothing declares them as expensive, so nothing excludes them.

So the split was correct and incomplete: it removed the installed-binary forks
and left the real-subprocess forks paying full price in the inner loop. That is a
statement about **where the cost is**, not a proposal to retag anything — the
right seam for a second slow lane is a design decision this receipt does not make.

## The 5 failures, by class

### 1. Sandbox ledger staleness — FIXED in `e22c814f`

`TestSandboxFitnessTest` failed its own `stale` assertion: `UatDsl006BodyExecutionTest.kt`
remained in `KNOWN_SANDBOX_LEAKS` while no longer forking anything. Verified against
the file (no `ProcessBuilder`, no `Runtime.getRuntime().exec`; the only `/tmp`
mentions are `dir("/tmp")` DSL steps that *scope* the run). Entry removed, with a
comment recording why, so a later reader can tell a repaid debt from an oversight.

Re-verified on the committed bytes: `tests=1 failures=0 errors=0 skipped=0`, fresh
XML, 2 executed, exit 0.

### 2. Output Plane live ingress — 4 failures, FIXED in `1ab6cd0d`

**Resolution (supersedes the classification below).** These were a live defect, not pre-existing.
`RedactingInputStream.read`'s early-return guard sat *after* the step that refills its input buffer
by blocking on `source.read`, so it could never fire: the pump asked for a whole 64 KiB window and
the wrapper filled it or blocked. Moving the guard before the refill fixes all four rows. See
[[not a memory file — see the commit]] `1ab6cd0d` for the full evidence, including the mutation
that kills the new regression test and the assertion that had encoded the old behaviour.

The original text is kept below because the *numbers* are what made the defect legible: `65536 of
131072` is exactly one window, and a 15-byte payload committing nothing is the same fact at the other
end of the scale.

<details>
<summary>Original triage text (superseded)</summary>

| test | assertion |
| --- | --- |
| `ObsBLiveOutputIngressTest.live visibility is bounded by the redaction lookahead` | only 65536 of 131072 bytes committed mid-step |
| `ObsBLiveOutputIngressTest.a short transcript is visible mid-step` | a 15-byte payload committed nothing mid-step |
| `ObsBJvmDeathOutputRecoveryUatTest.acknowledged sanitized bytes survive a JVM kill` | no output was ever committed while the step ran |
| `ObsCChannelAndTailCharacterisationTest.a running step reports an Open tail` | mid-step page reported a negative committed extent (-1) |

These are **not** classified as pre-existing. AGENTS.md rule 16 forbids that
without fresh base-vs-head evidence, and no such comparison was run: doing it
means a second ~24-minute full-module run on the pre-split base, which is a work
unit, not an aside. They are also **not** called a new defect. They are an open
triage item.

They share a shape worth naming for whoever picks them up: every one is about
whether bytes become visible *while the step is still running*, and three of four
observe **nothing committed at all** or **exactly half committed**. That is a
window/drain symptom, adjacent to the recorded Output-Plane ring and first-write
history — stated as a lead, not as a diagnosis.

</details>

## What this receipt does NOT establish

- That the lane split improved the wall clock. No base-vs-head measurement exists.
- That the lane is green. The 5 failures here were fixed in `e22c814f` and `1ab6cd0d`, but no
  full-module run has been executed on the fixed bytes; the per-class evidence is scoped, not global.
- A fix for the 29-class tail. Only where the cost is.
- Any statement about `:installedContractTest`; this run is `-Plane=fast` only.
- Anything about a release candidate. No RC was published; all of this is internal
  harness/build quality, and an empty candidate would be forbidden anyway.