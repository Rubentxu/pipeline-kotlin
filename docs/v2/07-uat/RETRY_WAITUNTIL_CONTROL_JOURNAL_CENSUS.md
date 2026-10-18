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

**Corrected 2026-10-18, after acting on §4 and finding its target wrong.**
The original text of this section named
`WaitUntilStepContractSuiteTest` as the one construction site to bind. That
was wrong, and the reason is a reachability fact, not a judgement call:

`WaitUntilStepContractSuiteTest` builds `OpaqueStepNode` only — lines 16, 150,
159 and 481, with zero `BlockStepNode`. A `waitUntil` node must be a
`BlockStepNode` for `BodyExecutionEngine` to ever see a
`BlockShellScope.WaitUntilScope`. Binding a journal there is therefore
**inert**: it was measured, the class stayed 18/18 green, and no code path
changed. A green suite is not evidence that a binding took effect.

The four classes that **do** construct `BlockStepNode` for retry or waitUntil
are the real population, and they split by purpose — not all four should be
bound:

| class | branch crossed | action |
| --- | --- | --- |
| `CoreWaitUntilBodyVsHandlerTest` | `WaitUntilScope` | **bound** — its claim ("the body ran") is a claim about the product |
| `WULpr302Phase1bTest.RetryEndToEndBodyPathDivergence` | `Retry` | **bound** — same, via the attempt-event seam |
| `BodyExecutionCharacterizationTest` | `Retry` + `WaitUntilScope` | **deliberately not bound** — see below |
| `Lfc2WaitUntilCanonicalReentryFitnessTest` | `WaitUntilScope` | **deferred** — `@Tag("installed-contract")` at :64, outside the default lane |

`BodyExecutionCharacterizationTest` is not an oversight. Its KDoc states the
purpose: *"CHARACTERIZATION BASELINE of the body-execution loop, pinned BEFORE
any extraction moves it into a BodyExecutionEngine … The extracted
`BodyExecutionEngine` must satisfy these UNCHANGED."* Its `{n}:retry-attempt`
segment assertion (:206-214) describes the **pre-extraction inline** contract
that the engine had to preserve. Binding a journal would move it to
`RetryEngine`, which does not use that segment, and keeping the row green
would require weakening the assertion — forbidden by AGENTS.md. A
characterisation baseline documents a historical path on purpose; converting it
into a production-engine test destroys the artefact rather than repairing it.

The remaining 21 Step-contract sites are category (ii) — they need a
coordinator but exercise no block scope — so they are **out of scope** for this
block and re-measuring them would be scope inflation.

## 5. `pk-rc2/` — the premise was wrong

The original text here read: *"`pk-rc2/` is a git submodule holding an
identical copy of `v2/`. It was excluded. … every figure above doubles."*
Measured, both halves of that are false:

- `git ls-files -s pk-rc2` reports **mode 160000** — a gitlink, i.e. a pointer
  to another repository. `git ls-files pk-rc2 | wc -l` is **1**: nothing inside
  is tracked by this repository. There is also **no `.gitmodules`**, so the
  pointer is dangling.
- Its `v2/` has **33** module directories against the live **34**. It is an
  older snapshot, not an identical copy. (`BodyExecutionEngine.kt` happens to
  be byte-identical between the two — `c155d186…` — so the divergence is
  elsewhere, not in the file this census analyses.)

**The figures in §1 are unaffected**, and the reason is structural rather than
lucky: a sweep that starts at `v2/` never reaches a sibling directory. The
fitness that recomputes the env-var census walks `Paths.get("..")` from
`v2/pipeline-application`, which resolves to `v2/`. `pk-rc2/v2/` is not under
it. The doubling risk was real for a *repo-root* sweep and is worth stating
precisely for that reason.

`pk-rc2/` remains a real hazard — a stale 96.5K `AGENTS.md` plus a full source
tree sitting unversioned in the working directory, with nothing to explain it —
but removing it needs a user decision (it is another repository's checkout) and
it is not part of this block.