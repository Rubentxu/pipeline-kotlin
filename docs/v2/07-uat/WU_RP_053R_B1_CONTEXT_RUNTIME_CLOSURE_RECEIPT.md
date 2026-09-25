# WU-RP-053R · B1 — Composed parallel+dir+kill+resume RED→GREEN closure

**Status:** CLOSED — vertical green (RED → GREEN)
**Date:** 2026-09-25
**Branch:** `wu/rp-053r-red-fixtures` (worktree)
**HEAD SHA:** `ff9603bf` (C3 BLOCK base) → new commit to follow
**Operator mandate:** GO continuo B1→B4 (2026-09-25T12:19Z)

---

## 1. Goal

Close B1 (the composed parallel+dir+kill+resume RED-GREEN closure) by
introducing the **single** missing DSL primitive that the operator's
discriminante requires: `dir()` callable inside a `branch("...") { ... }`
body. The original RED discovered that the canonical Jenkins DSL nesting
`parallel { branch("left") { dir("a") { sh("...") } } }` did not compile
because `BranchScope` only exposed `echo / sh / error / sleep`. This
vertical promotes a **minimal** capability (`BranchScope.dir`) and
strengthens a single end-to-end test that verifies:

1. **Detached survival under kill.** A branch's `sh("echo …; sleep 15; echo …-done")`
   survives a SIGKILL of the JVM (`jvm1.destroyForcibly()`) because
   `DurableShellExecutor` launches its children via `setsid` into a new
   session/pgid.
2. **Branch-isolated cwd.** `dir("left")` and `dir("right")` mutate cwd
   per-branch without cross-contamination.
3. **Replay idempotence under resume.** After the JVM kill, a second
   `pipelinek run` on the same `--db / --control-root` MUST NOT re-execute
   successful child bodies (no duplicate markers).

The test is the canonical proof of CTX-P / PAR-D / RETRY-D cooperating
under a single composed script.

---

## 2. RED → fix → GREEN

### 2.1 RED (L1, before this WU)

`B1WURp053rContextRuntimeClosureTest > B1 composed - parallel+dir+kill+resume ...`
FAILED at compile-time: `BranchScope` did not expose `dir`.

```text
e: Unresolved reference: dir
```

### 2.2 Fix

**Production change:** `v2/pipeline-scripting-api/.../PipelineDsl.kt`

```kotlin
class BranchScope {
    // ... existing echo/sh/error/sleep ...
    fun dir(path: String, block: BranchScope.() -> Unit) {
        val inner = BranchScope()
        inner.block()
        steps.add(StepSpec.Dir(path = path, steps = inner.steps.toList()))
    }
}
```

This is the **only** production change. No coordinator/dispatcher/registry
modification. No new capability. The receiver is `BranchScope.()`, mirroring
`StageScope.dir()` (line 1638) so the call site reads identically. The
nested `BranchScope()` enforces the same body-shape contract that
`StageScope.dir()` already does for stages.

### 2.3 GREEN

L1 (this fix) — `B1WURp053rContextRuntimeClosureTest` passed in 26.092 s.
L2 (sibling) — `B1 + B11ContextBlocksRuntime + CtxPConcurrencyOwnership
+ CanonicalCoordinatorScopeStack + WULpr302Phase1b + UatParallelBlockDurable`
= 32 tests, 0 failures.
L3 (owning module) — `:pipeline-application:test` = 1749 / 0 / 0.
L4 (cross-module) — `./gradlew -p v2 check` = 3560 / 0 / 0 in 2 m 18 s
(256 tasks: 182 executed + 19 cached + 55 up-to-date).

---

## 3. Subtle bug surfaced during RED

The first L1 GREEN pass attempted `jvm1.descendants().forEach { it.destroyForcibly() }`
**before** `jvm1.destroyForcibly()`. Java's `Process.descendants()` walks
**all** descendants of the JVM, INCLUDING those in different sessions.
`DurableShellExecutor` uses `setsid` to give the detached wrapper its
own session/pgid; nevertheless, `descendants()` traverses them and the
test was killing them before they had a chance to write their "*-done"
markers. The assertion became:

```
Detached left shell must complete; got: left\nright\n
```

(i.e., only the start markers, never the done markers).

**Fix:** kill **only** the JVM (`jvm1.destroyForcibly()`). The detached
shells live in their own session and complete naturally. After the fix
the assertion held and the test passed in 26 s.

This is documented inline in the test and is a deliberate lesson: **to
exercise detached survival, you must NOT clean up descendants of the
JVM — only the JVM itself.**

---

## 4. Test — `B1WURp053rContextRuntimeClosureTest.kt`

- **Path:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/B1WURp053rContextRuntimeClosureTest.kt`
- **Size:** ~290 LOC.
- **Timeout:** `@Timeout(360)` (the .kts compiler warm-up + compile +
  start + 6 s warm-up + 120 s poll + 60 s detached wait + 5 s cleanup ≈ 200 s; 360 s leaves slack).
- **Scenarios:**
  - Run #1 — fresh execution of `parallel { branch("left"){dir("left"){sh(...)}}; branch("right"){dir("right"){sh(...)}} }`
    with `sleep 15` markers. Poll until both branches have written "left"
    and "right" markers (warm-up 6 s, deadline 120 s). Kill the JVM
    (`destroyForcibly()`) **without killing its descendants**. Wait until
    both branches complete ("left-done" + "right-done"). Assert:
    P1+P2: both started.
    P5: each branch contributed exactly 2 markers (started+done).
    Detached survival: the kill did not cancel the children.
  - Run #2 — re-execute `pipelinek run` against the same `--db /
    --control-root`. Assert: marker count is still 4 (2 per branch). The
    resume reuses the durable completion, no duplicate effect.

### Reference implementation consulted
None applicable. The `dir()` capability is a copy of `StageScope.dir()`
(line 1638) — same dispatch model, same body shape, same `StepSpec.Dir`
canonical envelope. No Jenkins equivalent exists for
`branch("x") { dir("y") { ... } }` because Jenkins `parallel` does not
expose nested `dir`; the closest semantics are provided by `withContext`
wrappers, which we do not model.

---

## 5. Evidence

- **L1 (B1 only):** `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.B1WURp053rContextRuntimeClosureTest.xml`
  - 1 test, 0 failures, 0 errors, 26.092 s.
- **L2 (B1 + sibling):** aggregate 32 tests, 0 failures, 0 errors.
- **L3 (`:pipeline-application:test`):** 1749 / 0 / 0 / 14 m 57 s.
- **L4 (`./gradlew -p v2 check`):** 3560 / 0 / 0 / 2 m 18 s (incremental).
- **No orphan processes:** `pgrep -af "sleep 15"` after the test is empty
  (the only `sleep 15` processes are `systemd-inhibit --what=sleep:handle-lid-switch`
  jcode wrappers, unrelated).

---

## 6. End-of-Work-Unit closure check

```text
Reference implementation consulted: none (capability derived from existing StageScope.dir)
Behaviour adopted:                  BranchScope.dir(path, BranchScope.()->Unit) → StepSpec.Dir
Intentional deviations:             none
Security implications reviewed:     setsid detach must NOT be cancelled by test cleanup;
                                   descendant-kill removed (lesson in §3)
Tests demonstrating the contract:   B1WURp053rContextRuntimeClosureTest (1), B11ContextBlocksRuntime (7),
                                   CtxPConcurrencyOwnership (5), CanonicalCoordinatorScopeStack (5),
                                   WULpr302Phase1b (11), UatParallelBlockDurable (3)
```

---

## 7. Files changed in this vertical

```text
v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt
    +14 LOC: BranchScope.dir(path, BranchScope.()->Unit)
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/B1WURp053rContextRuntimeClosureTest.kt
    NEW: composed B1 test (~290 LOC)
```

Both changes are within the operator-pre-approved B1 scope. No
cross-block boundary touched. No public API change. No new capability.
