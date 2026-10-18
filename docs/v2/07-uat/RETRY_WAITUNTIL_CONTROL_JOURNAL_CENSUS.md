# Census — `retryControlJournal` / `waitUntilControlJournal` null-branches

**Measured:** 2026-10-18, at `6b5c7344`
**Purpose:** correct the measurement recorded in
`H3_CREDENTIALS_STORE_PATH_RECEIPT.md` §6 item 1, and replace its framing of
the work. That receipt is evidence for its own SHA and is **not** rewritten;
this note supersedes its *plan*, not its *evidence*.

## 1. The correction

The H3 receipt recorded:

```text
~24 construction sites in tests, including 9 *StepContractSuiteTest.kt files
```

Measured, repo-wide, excluding `**/build/**`:

| population | count |
| --- | --- |
| `CanonicalDurableRunCoordinator(` in `src/main` | 4 |
| — of which real constructions | **1** (`CompositionRoot.kt:123`) |
| `CanonicalDurableRunCoordinator(` in `src/test` + `src/pluginContractTest` | **97** |
| `*StepContractSuiteTest.kt` sites among those | **22**, across **14** files |

So the suite surface named by AGENTS.md as the certification instrument is
twice the size I stated, and the Step-contract subset is 22 sites, not 9.

## 2. The framing was wrong, and that is the part that matters

I described the inline loop as "the retry legacy loop (no
`retryControlJournal`)". Reading the code, that sentence is false.

`BodyExecutionEngine.kt` has **four** branches, not three:

| line | condition | target |
| --- | --- | --- |
| 425 | `scope is Retry && retryControlJournal != null` | `RetryEngine.execute` |
| 455 | `scope is WaitUntilScope && waitUntilControlJournal != null` | `WaitUntilEngine.execute` |
| 484 | `scope is WaitUntilScope` (journal null) | `executeWaitUntilInline(...)` |
| 499 | everything else | the shared body-once loop |

`BlockShellScope` is a closed ADT of **7** cases (`None`, `Directory`,
`TimestampsScope`, `EnvScope`, `Timeout`, `Retry`, `WaitUntilScope`). The
`else` at 499 is therefore **not** a retry legacy loop. It is the shared
single-invocation body path: `attemptCount` is `1` for every non-`Retry`
scope (lines 499-503), and the retry behaviour is a `when (scope)` /
`if (scope is BlockShellScope.Retry)` **inside** that shared loop —
`attemptSegment` (509-511), the `RetryAttemptStarted` emission (514-529), and
the `continue@bodyLoop` on failure (545-548).

**Consequence.** Deleting or "unifying" the loop at 499 would delete the body
path for five of seven scopes. The lawful reduction, if it is ever attempted,
is to remove the retry *specialisation* inside it — and the three other
scopes (`None`, `Directory`, `EnvScope`, `TimestampsScope`, `Timeout`) keep
the loop regardless. This is the difference between "delete a legacy
duplicate" (cheap, correct) and "refactor the shared body path" (large,
dangerous), and I had planned for the wrong one.

## 3. Where the branches actually stand

- **Production is correct.** `CompositionRoot.kt:187,191` binds both journals;
  the reachable callers are `Main.kt:665` and `Main.kt:1120`. The two
  null-branches are therefore **unreachable in `src/main`**.
- **The test surface is not.** 97 of 98 construction sites bind neither
  journal, so those tests exercise `executeWaitUntilInline` and the inline
  retry specialisation — paths production never takes.
- **A fitness pins the branches' existence, not their behaviour.**
  `pipeline-architecture-tests/.../Lfc2WULpr302BodyControlSeamFitnessTest.kt`
  asserts `"is BlockShellScope.Retry" in engine` (:286) and
  `"executeWaitUntilInline" in engine` (:311). It will fail if either branch
  is removed, and it never checks what the branch does.
- **The one suite that certifies `core.waitUntil` is among the 22.**
  `WaitUntilStepContractSuiteTest.kt:107` binds no journal. Of the 22
  Step-contract sites, it is the only one whose Step exercises retry or
  waitUntil semantics; the rest need a coordinator for unrelated reasons and
  are unaffected.
- **The journal-bound production path has in-process coverage only where a
  test forks the real CLI** — `UatRetryBlockDurableTest` (`WL-R2`, `WL-R3`)
  and `UatLocal011WorkflowControlTest` (`SC-011-07/08/12`). No in-process
  test crosses `RetryEngine`/`WaitUntilEngine` in production composition.

## 4. The block this actually unblocks

Not "remove the null-branches from `src/main`" — production already binds
them. The block is narrower and is about certification integrity:

> **Bind a `waitUntilControlJournal` in `WaitUntilStepContractSuiteTest`**, so
> the rows that certify `core.waitUntil` exercise the engine the product
> actually runs.

One construction site. The risk is that rows legitimately flip: the suite
currently certifies the inline loop, and switching engines changes which code
the assertions describe. Those flips are the measurement, not a regression to
be suppressed — per AGENTS.md, a suite may not be weakened to keep a gate
green, and a `StepFailed`-shaped red that is actually an admission failure
must be classified by kind.

The remaining 21 Step-contract sites are category (ii) — they need a
coordinator but exercise no block scope — so they are **out of scope** for this
block and re-measuring them would be scope inflation.

## 5. Not measured here

`pk-rc2/` is a git submodule holding an identical copy of `v2/`. It was
excluded. If it is a live second source set, every figure above doubles and
the same site is bound at `pk-rc2/.../CompositionRoot.kt:187,191`.