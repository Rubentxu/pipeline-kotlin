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
- **The journal-bound production path had no composition coverage.** This
  needs its denominator stated, because an earlier version of this section
  said the engines had no in-process coverage at all, and that was false:
  `WULpr302RetryEngineTest` (`RetryEngine(` at :535) and
  `WULpr302WaitUntilEngineTest` (`WaitUntilEngine(` at :581) construct and
  exercise each engine directly, in isolation from the coordinator: each
  names `BlockStepNode` and constructs **zero**
  `CanonicalDurableRunCoordinator(`. They are 2 of the 22 such files in the
  sweep below, not a population of their own.

  What was actually missing is narrower and is what this block closed: **no
  in-process test drove a coordinator across the branch in production
  composition**, i.e. binding the journal and letting `BodyExecutionEngine`
  select the engine. Both engines were unit-tested and the seam between them
  and the dispatcher was not.
- **The population, and the instrument that measures it.** Over every
  `src/test/kotlin` under `v2/` (two module levels included), excluding
  `**/build/**`:

  | predicate | count |
  | --- | --- |
  | files mentioning `BlockStepNode` | 33 |
  | …that also construct a `CanonicalDurableRunCoordinator(` | 11 |
  | …that also name `core.retry` or `core.waitUntil` | **4** |

  The third row is the denominator §4 acts on; §4 names the four classes and
  owns the decision about each one. The instrument is stated because the
  two-term version is **misleading by a factor of nearly three**: 7 of the 11
  coordinator files construct `BlockStepNode` for a *different* Step key —
  external body Steps, `LockFeasibilityProofTest`, `B11ContextBlocksRuntimeTest`
  — so "a test that builds a BlockStepNode and a coordinator" would pull in 7
  classes that never reach a retry or waitUntil branch. An earlier version of
  this section quoted the two-term figure and its "14 further candidates";
  both numbers were wrong (22 of the 33 mention `BlockStepNode` without a
  coordinator, and the two-term intersection is 11, not 4).

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
| `Lfc2WaitUntilCanonicalReentryFitnessTest` | `WaitUntilScope` | **bound** — verified 2026-10-18, see §7 |

**Why the last row is not "just bound".** Binding a journal is a change whose
evidence is indistinguishable from no change at all: both branches run the body
and both report `RunOutcome.Success`. So the row also asserts the artefact only
the production engine leaves behind — one `.attempts.json` under
`{controlRoot}/wait-until-control/`. The discriminating power is structural,
not asserted: `FileBasedWaitUntilControlJournal.kt:39-42` is the **only** code
in `src/main` that resolves and creates `wait-until-control`, `:46` is the only
writer of the `.attempts.json`, and the inline branch
(`BodyExecutionEngine.kt:484`) does not mention the journal at all. Remove the
binding and the directory is never created, `controlRows` is empty, and
`assertEquals(1, 0)` fails. That witness was established by measurement for the
cost of one grep, instead of the ~29 min a lane run costs — see §8.

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

## 6. Status of the open items the H3 receipt left deliberately open

`H3_CREDENTIALS_STORE_PATH_RECEIPT.md` §6 recorded three boxes. That receipt is
**evidence for its own SHA and is not rewritten**; this census is the surface
where its *plan* is corrected, so the status lives here.

| §6 item | state | by |
| --- | --- | --- |
| 1. Certification-surface durability | **corrected + closed** | §1 corrects the instrument and the counts; §4 records which of the 4 real sites are bound, and why `BodyExecutionCharacterizationTest` must stay unbound |
| 2. Environment-variable discoverability | **closed** | `6b5c7344` — every behaviour-changing env var is now documented by the CLI |
| 3. `System.exit(3)` in a composition helper | **open, by judgement** | one caller (`Main.kt:664`). Fail-fast from a function that otherwise returns a value is hostile to composition, but it is a preference, not a defect; no row fails because of it |

Item 1 was the only one whose premise this census was written to correct, and
the correction ran in the direction of **more** surface than claimed (22
`*StepContractSuiteTest.kt` sites, not 9), not less.
## 7. Verification of the bound row, and its cost

Evidence is the JUnit XML, not the exit code — the two `timeout 600` attempts
that preceded it both reported `exit 0` from the harness while producing **no
XML at all**.

```text
task    :pipeline-application:installedContractTest
command timeout 1800 v2/gradlew -p v2 :pipeline-application:installedContractTest \
          --tests '*Lfc2WaitUntilCanonicalReentryFitnessTest*'
canary  v2/pipeline-application/build/test-results/installedContractTest/
          TEST-dev.rubentxu.pipeline.v2.application.Lfc2WaitUntilCanonicalReentryFitnessTest.xml
        tests=4 failures=0 errors=0 skipped=0   timestamp 2026-10-18T18:03:26.673Z
rows    GREEN core dot waitUntil is registered in the production factory   0.001s
        GREEN core dot waitUntil descriptor declares the sub-shape         0.679s
        GREEN waitUntil succeeds through the registry path                1.780s
        GREEN installed CLI emits WaitUntilPolled and WaitUntilCompleted  8.405s
```

The row's hermeticity was repaired in the same change: without
`PIPELINE_STORE_PASSPHRASE` supplied explicitly, the installed-CLI row exits 3
**before the pipeline starts** ("Passphrase required …"), so its verdict
depended on the operator's ambient environment while claiming to report on
waitUntil. `Subprocess.run` merges its `environment` into the inherited one, so
the fix costs nothing and is hermetic in both directions.

## 8. The lane cost, measured — a harness-economics finding

The same run, from the same log:

```text
suite time (sum of the 4 rows)   10.871 s
lane time (BUILD SUCCESSFUL in)  29 m  9 s  = 1749 s
overhead                        ~161x
```

The lane's cost is not `installDist`, which was `UP-TO-DATE`; it is the ~15
non-caching `publishSdk*` publication tasks the task depends on. **Three of the
four rows are pure in-process assertions** (registry membership, descriptor
shape, coordinator run) that need no installed binary at all, and they pay the
full lane because the `@Tag("installed-contract")` is declared at CLASS level.

The lane model in `pipeline-application/build.gradle.kts` states the rule that
makes this fixable rather than an abuse:

```text
"Membership is declared, not negotiated: a class is in exactly one lane by
 carrying exactly one tag, and the two tasks partition on it."
```

So the split is lawful: rows 1-3 belong in a class without the tag, row 4 in a
class with it. It is **not** recorded here as done, because it has three
dependents that were measured and not yet moved:

1. `Lpr301BurnDownCharacterizationTest.kt:21` records this class as "4 rows".
2. `PermanentContractArchitecturTest.kt:60` maps
   `Lfc2WaitUntilCanonicalReentryFitnessTest.kt` to `ObservedProcessApi.AppBinSupport`;
   after the split, the file that no longer spawns the binary must lose that entry.
3. `InstalledContractTagMembershipFitnessTest` is the guard that requires any
   class calling `AppBinSupport.discover(` to carry the tag.

Not done unilaterally, and the reason is not caution about the build: the
product-level consequence is that `UAT_GATE_CLASSIFICATION.md:114` records this
class as **RED and BLOCKING for the `core.waitUntil` STEP-CERT**, so it is on a
certification surface, and a fitness data-table change belongs to a block that
states its own exit criterion rather than to a census correction.
