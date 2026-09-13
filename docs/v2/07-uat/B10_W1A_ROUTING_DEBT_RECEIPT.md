# B10 / W1a — concrete block-Step routing debt, pinned

> status: **CLOSED** — fitness only; no semantic migration
> lane: `cycle/lfc2-e1-bodyinvoker-w1a`
> base: `da594bb6` (= `origin/main` after PR #48, B10 W1 G0)
> authority: ADR-0073 (block-step re-entry), ADR-0081 (accepted); `AGENTS.md` "no central
> concrete-Step switch"
> milestone: B10 — "block Steps re-enter engine; no `dispatch*Block` collection"

## 1. What W1a is for

W1's exit criterion is that block Steps re-enter the engine through the body machinery. The
G0 pre-flight (`B10_W1_PREFLIGHT.md`) established that the coordinator routes on concrete
block Step identities at three structural sites, and that the existing guard could not see
it: `Lfc2DurableCoordinatorScopeFitnessTest` asserts `core.sh` and `core.echo` are absent,
and neither is routed there, so it is green while the debt stands.

W1a does **not** fix that. It makes the debt measurable and unextendable, so that W1b..W1d
can demonstrate it falling against a guard that can actually detect it. The alternative
order — migrate first, guard later — would mean migrating with no test able to show whether
the migration removed the debt or just moved it.

## 2. The law

```text
new concrete step name in the coordinator   -> FAIL
new step-id switch                          -> FAIL
new dispatch*Block bypass                   -> FAIL
removing a known routing site               -> FAIL until the ledger is lowered
raising the pinned total                    -> FAIL (ceiling is a high-water mark)
```

The ledger is **enumerated, not counted**. The rejected alternative was a bare
`literalCount <= 15`, which admits a false green: delete two literals in one site, add two
Step-specific names in another, total unchanged, guard happy. Here every item is named, and
the guard asserts `discovered - pinned == {}` **and** `pinned == discovered`, so the ledger
cannot carry slack either.

```text
PinnedConcreteBodyRoutingDebt(
    concreteStepNames = {core.dir, core.timeout, core.retry, core.withCredentials,
                         core.timestamps, core.withEnv, core.parallel},   // 7
    bodyStepIds       = {core.dir, core.timeout, core.retry, core.withCredentials,
                         core.timestamps, core.withEnv},                 // 6
    sites             = {CANONICAL_BODY_STEP_IDS, PROJECT_SHELL_SCOPE,
                         DISPATCH_WITH_CREDENTIALS_BLOCK},               // 3
    blockBypasses     = {dispatchWithCredentialsBlock},                  // 1
    stepIdSwitches    = 1,                                              // 1
)
total = 18 == HISTORICAL_CEILING
```

`HISTORICAL_CEILING` is never raised. Burning debt down lowers both numbers in the same
commit; that edit is the explicit gate.

## 3. Coverage boundary

Stated in the model's KDoc rather than implied, because a guard's silent limit is how false
confidence is manufactured:

```text
SEES:  any "core.<name>" literal · any when (...stepId...) switch ·
       any dispatch*Block identifier · three named structural sites
NOT:   a Step name built at runtime (concatenation, lookup, value from a caller);
       routing on an enum ordinal or a numeric id
```

The scan is conservative in the other direction: an identifier appearing anywhere in the
file counts, including a call site or a comment. It over-reports rather than under-reports,
and over-reporting fails the guard.

## 4. Slice contents

| File | Kind |
| --- | --- |
| `.../architecture/ConcreteBodyRoutingDebt.kt` | new — sealed `ConcreteRoutingDebtItem`, `BodyRoutingSite`, pinned ledger, pure scanner and verdict |
| `.../architecture/Lfc2ConcreteBodyRoutingDebtFitnessTest.kt` | new — 11 tests: 4 real-source laws + 7 violation fixtures |

Zero production files. Zero edits to existing tests. `Lfc2DurableCoordinatorScopeFitnessTest`
is untouched and still green.

## 5. Evidence

```text
:architecture-tests:test  tests=252 failures=1 errors=0     (base: 241/1)
Lfc2ConcreteBodyRoutingDebtFitnessTest                      11/0/0
```

The `+11` is exactly the new guard. The one red is `Lfc0GlobalStateFitnessTest`, unchanged
and **byte-identical after checkout-path normalization** to the Lane R base XML: a scanner
false positive flagging `Capabilities.kt:76 System.getProperty("user.dir")`, which appears in
KDoc prose stating handlers must NOT do that. `Capabilities.kt` is untouched by this lane.

Archived: `evidence/b10-w1a/raw/xml/architecture-suite-xml.tar.gz`, `raw/logs/l2-architecture-suite.log`.

## 6. Verifier and controls

`evidence/b10-w1a/verify-b10-w1a-receipt.py` re-derives the ledger **independently**: it
parses the pinned values out of the Kotlin model and re-scans the coordinator in Python under
the same rules, so a pin edited to match a drifted source is caught by the re-scan rather
than by agreement between two copies of the same numbers.

Controls mutate a temporary worktree and must make the verifier fail for the intended reason;
see the control table in §7.

## 7. Controls

Six controls, each run **independently from the pristine commit** with a real temporary
commit, and each verified to touch exactly one file relative to that commit:

| Control | Mutation | Observed | Intended? |
| --- | --- | --- | --- |
| WA1 | inject a `"core.echo"` literal into the real coordinator | pin-vs-re-scan mismatch, total 19, ceiling mismatch, plus the production-touch claim | yes |
| WA2 | raise `HISTORICAL_CEILING` to 19 | ceiling != measured total, alone | yes |
| WA3 | delete the `dispatch*Block` violation fixture | that law's fixture check, alone | yes |
| WA4 | corrupt the archived suite XML totals | the four suite-total rows, alone | yes |
| WA5 | append a comment to a production file | the fitness-only rows (two phrasings of one fact) | yes |
| WA6 | add a name to the **pin** that the source does not contain | pin-vs-re-scan mismatch, alone | yes |

WA6 is the isolating control that matters: it is the "pin edited to match a drifted source"
scenario, and it fails on the re-scan rather than on agreement between two copies of the same
numbers. WA1 covers the opposite direction (source drifts, pin does not) but also trips the
production-touch rows, so it is not the isolating control for the re-scan.

### Lesson recorded: controls must reset to the pristine commit

The first pass ran the controls with `git checkout --detach HEAD`, which stays on the current
commit, so each control inherited the previous one's mutation. Every control "failed", and
none of it meant anything. Re-run with `git reset --hard <pristine>` and with the changed-file
set printed per control to prove independence. This is the same trap the G0 pass hit; it is
now recorded twice because it is evidently easy to reintroduce.
