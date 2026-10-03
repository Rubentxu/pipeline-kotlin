# S4-IDENTITY (I1) — Falsification: what the invocation ordinal actually guarantees

**Status: CHARACTERIZED. No production code changed by this slice.**
**Destination of the defect: S4-IDENTITY (I2), the structural-identity design.**

Falsification first, as the block plan requires. Nothing here proposes a fix — the
point is to know what is true before designing against it.

---

## 1. What was searched, and what it found

`ScriptedScope.scoped(ScriptedDynamicScopeId)` exists and is exercised. But grepping
the whole `pipeline-scripting-kotlin24` module for loop handling — `for`, `loop`,
`scoped`, `visitForExpression` — returns **nothing**. The lowering emits no loop
scope at all.

The test at `ScriptedScopeTest` named `generated loop scopes keep repeated call
sites distinct and replayable` therefore generates nothing: its entry point is
hand-written Kotlin that calls `steps.scoped(ScriptedDynamicScopeId(...))` itself.
The name states an intent the compiler does not fulfil.

**Consequence:** in a real `.pipeline.kts`, a `for` loop produces no structural
scope. The only thing distinguishing one iteration from the next is
`ScriptedScope.nextOrdinal` — a process-local counter keyed by
`(callSiteId, dynamicScopePath)`. SPIKE-016's `passed` status does not cover this:
its harness is a test-only `Scope`/`Runtime` reimplementation, not production.

---

## 2. The claim under test

The durable identity is `callSiteId × dynamicScopePath × invocationOrdinal`, and
the ordinal counts **arrivals at this call site**, not the loop iteration. Replay is
therefore correct only while a resumed execution reaches each call site the same
number of times, in the same order.

---

## 3. What the measurement found — two wrong hypotheses first

### 3.1 Hypothesis A — "a wrong durable value is served silently". REFUTED.

I expected a resume whose branch moved from item-B to item-A to reach `read` for
A as ordinal 0 — the identity the journal already holds for B — and be served B's
persisted value with no complaint.

It does not. The resume fails:

```text
PipelineStepException: scripted registry step input diverged
```

`ScriptedRegistryCall` folds `encodedInput` into the durable fingerprint, so
`value-of-A` and `value-of-B` are different operations and the reuse is refused.

**This is a protection, and it is stronger than it looks:** a renumbered ordinal
can at worst *refuse*, never lie, as long as the argument varies. The first
hypothesis was wrong because it ignored the fingerprint.

### 3.2 Hypothesis B — "a distinct-input branch shift is survivable". REFUTED, same way.

Same construction, different framing: with distinct inputs the run does not
complete. Recorded as a measured property, not a defect, because a refusal is the
correct outcome.

### 3.3 The real defect — a silent no-op when the argument does not vary

The fingerprint cannot help when the guarded call site's **argument is the same in
every iteration**. Then all arrivals are one operation, a reduced arrival count
renumbers silently, and the reuse looks entirely legitimate.

```text
for (item in items) {
    if (predicate(item)) {
        sh("value-of-x", returnStdout = true)   // SAME argument every iteration
    }
}
```

```text
first execution   branch taken twice  ->  ordinals 0, 1   both effects performed
resume            branch taken once   ->  ordinal 0 reused, ordinal 1 NEVER VISITED
result            the run SUCCEEDS. Nothing diverges. Nothing is refused.
                  one owed effect is simply not performed, and its journal row is
                  orphaned with no report.
```

A **silent no-op**, which the Semantic Constitution §2 lists as a forbidden defect
class. Not a wrong value — a *missing* one.

### 3.4 Control — the case that already works

With a predicate that is a fact about the source (`it != "C"`), the ordinals
`0, 1` reproduce exactly and the resume reuses both rows. So the defect is not a
constant; it needs a branch whose decision is not a journalled runtime value.

---

## 4. The precise statement S4-IDENTITY (I2) must design against

```text
same source + same durable inputs
    + every branch decided from a journalled runtime value
        -> same invocation identities            (holds today, control proves it)

branch decided from anything else (env, clock, filesystem, host state)
    + repeated call site
        + argument identical across iterations
            -> an owed effect is silently skipped, and its row is orphaned
```

Two consequences for the design, both of which argue for a **structural**
occurrence identity rather than a counter:

1. **The ordinal cannot be the primary identity.** A structural occurrence path
   would make a skipped arrival *visible* — the resume would visit occurrence 1,
   find no row, and either execute it or refuse. A counter makes the omission
   invisible because nothing records that occurrence 1 was expected.
2. **A non-journalled branch decision must be rejected or made durable**, not left
   to be re-evaluated. SPIKE-016's `I3` law states the right shape; this
   measurement shows what enforcing it buys.

The runtime already supports the structural half: `scoped(ScriptedDynamicScopeId)`
with a per-iteration id produces exactly the `loop:items[0..2]` paths that make
identities stable. **The missing piece is that the lowering never emits it.**

---

## 5. Verification

```text
command     cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
            && ./gradlew :pipeline-application:test
               :pipeline-architecture-tests:test --rerun-tasks --console=plain
exit        0
duration    29m 47s
tasks       82 actionable, 82 executed
result      BUILD SUCCESSFUL
S4IdentityOrdinalFalsificationTest   3 tests, 0 failures
modules     376 test classes, 2595 tests, 131 skipped, 0 failures, 0 errors
            pipeline-application      292 classes, 2194 tests, 121 skipped
            pipeline-architecture-tests 84 classes,  401 tests,  10 skipped
FAILED matches 0
log         /var/home/rubentxu/.local/state/pipelinek-gates/s4id-module.log
            sha256:2d04baa62e466d3160853d709bb2b0b8241321b2e99e8fb0ae78fd6bd4f79c24
```

This is a **surgical module gate over a test-only slice**, not a block gate. The
BLOQUE 2 gate comes when the block closes, not at every slice — per the cadence:
`characterize → atomic commits → surgical tests → next slice`, full suite only at
S4-PRODUCT.

The three tests are **pinned characterizations**: they assert the behaviour as
measured, so the defect cannot change silently and the suite goes RED the day
someone fixes it — which is the signal to rewrite it to assert the fix.

---

## 6. What is NOT claimed

- **Not a defect in the fingerprint.** §3.1 shows it working.
- **Not proven against a real distribution.** This is production
  `ScriptedArtifactRuntime` / `ScriptedRegistryInvoker` / journal wiring with a
  stubbed `ShellOperations` through the declared capability — not a forked
  installed build. The kill-window and distribution questions remain S4-PRODUCT.
- **No CI** exists here (`.github/workflows/` empty since `754ddda0`).
- **No fix is proposed.** The destination is I2's design, and this slice
  deliberately does not pre-empt it.
