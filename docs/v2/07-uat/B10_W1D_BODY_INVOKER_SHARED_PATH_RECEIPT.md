# B10 / W1d — one shared body path and typed durable identities (receipt)

**Slice:** `cycle/lfc2-e1-bodyinvoker-w1d`
**Base:** `main == 45b26c49` (slice parent, the differential reference)
**Scope:** LFC-2 / B10 burn-down of `pipeline-kotlin`, fourth slice (W1d) after PR #50 (W1b, typed
body execution policy), PR #51 (AGENTS.md rules 7–14) and PR #52 (W1c, policy routing).
**Verifier:** `docs/v2/07-uat/evidence/b10-w1d/verify-b10-w1d-receipt.py` (historical + independent,
12 negative controls).

## 1. What this slice changes

W1c removed name-keyed body routing, but the coordinator still held three *copies* of the body-child
loop, and the W1a ledger still counted four items: the credential bypass plus the two literals
`core.parallel` and `core.retry`. W1d removes the copies, and disposes of the two literals by
**reclassifying** them, not by deleting them.

| Layer | Change |
| --- | --- |
| `pipeline-domain` | new `StepBody.None` / `StepBody.Declared(invocation, execution, introduces, catchesInterruptions)` + `BodyExecution(owner, policy)`; `StepDescriptor` keeps ONE `body: StepBody = StepBody.None` and loses all six independent body fields; all 13 `StepDescriptorRegistry` rows rewritten to the ADT, each body row declaring owner, shape and cardinality explicitly; new `BodyAggregateIdentity` (`RetryControlRow`/`core.retry`+ADR-0075, `ParallelStageAggregate`/`core.parallel`+ADR-0076, `AggregateDurableRole`, pinned `ALL`, `fingerprintKey`) |
| `pipeline-application` | ONE `invokeBodyChildren(...)` loop replaces three copies (plain body, retry attempt, credential body); `executeCredentialLeasedBody` is now a preamble: acquire → re-enter the shared loop → release in `finally` → fold the two typed outcomes through the pure `mergeBodyAndCleanup`; `dispatchWithCredentialsBlock` and `dispatchAcquiredWithCredentialsBody` removed; credential bindings decoded once in the pure projection as a typed `InvalidInput` before any effect |
| `pipeline-architecture-tests` | pinned concrete-routing ledger lowered `4 → 0` (`HISTORICAL_CEILING` untouched at `18`, **IMMUTABLE**); new structural law `BodyChildLoopInventory(loopDefinitions = 1, credentialAcquisitions = 1)`; `ConcreteBodyRoutingVerdict.decide` now REQUIRES the loop inventory; new `Lfc2DurableAggregateIdentityFitnessTest` |

### 1.1 The ledger reaches zero by RECLASSIFICATION, not by deletion

This is the substance of the slice, and the reason a syntactic burn-down is not accepted here:

```text
W1a pinned debt      18   (provenance: HISTORICAL_CEILING = 18, never lowered)
pinned debt after W1c 4   ({core.parallel, core.retry}, DISPATCH_WITH_CREDENTIALS_BLOCK, 0 ids, 0 switches)
pinned debt after W1d 0   (living state; the pin is what must equal the re-scan)
```

The four items were disposed of as follows, and every one is still defended by a guard:

| Item | Disposition | Guard |
| --- | --- | --- |
| `DISPATCH_WITH_CREDENTIALS_BLOCK` | the second body path is gone; the lease is a preamble of the ONE shared loop | `BodyChildLoopInventory` (duplicating the loop fails even with no literal anywhere) + guard control K1 |
| `core.retry` | reclassified as the RETRY-D control-row identity | `BodyAggregateIdentity.RetryControlRow`, ADR-0075, `Lfc2DurableAggregateIdentityFitnessTest` |
| `core.parallel` | reclassified as the PAR-D stage-aggregate identity | `BodyAggregateIdentity.ParallelStageAggregate`, ADR-0076, same guard |
| `bodyStepIds` / switches | already retired by W1c; stay retired | `BodyRoutingSite.CANONICAL_BODY_STEP_IDS` / `PROJECT_SHELL_SCOPE` still detected |

Deleting the two literals without the identity model would have hidden two real durable keys; leaving
them in the routing ledger would have counted them as routing debt forever. The empty ledger is a
**measurement with content**: every item is now undeclared, so the first `"core.*"` literal, the first
`when (stepId)`, the first `dispatch*Block`, the first hard-coded body id set, the first second
body-child loop and the first second credential acquisition all fail immediately.

## 2. Behaviour preservation and one real repair (the differential claim)

The differential reference is the **pre-slice** coordinator read at `45b26c49`, not a restatement of
the new code.

| Claim | Base (`45b26c49`) | W1d | Checked by |
| --- | --- | --- | --- |
| the env overlay of the credential body is the same expression | `executionContext.pushed(ContextOverlay.Environment(...))` | identical | parsed from both refs |
| the child ShOptions of the credential body are the same expression | `stageShOptions.copy(env = stageShOptions.env + scope.env)` | identical | parsed from both refs |
| the child loop is fail-on-first-Failure/Unstable | `dispatchAcquiredWithCredentialsBody` loop | `invokeBodyChildren` | parsed from both refs |
| the lease is released | `scope.close()` **inside the `try`**, after the loop | `cleanup = leased.close()` **inside `finally`** | parsed from both refs, control K6 |
| the credential payload is decoded once | inside `dispatchWithCredentialsBlock` | inside the pure projection | parsed from both refs |
| a dead local | `val childContext = StepLifecycleContext(...)` constructed and never read | removed | parsed from both refs |

**The repair is a leak, not a refactor.** At base, a child dispatch that THROWS skips
`scope.close()` (it sits inside the `try`, after the loop), so the acquired scope is never released
and the exception surfaces at the run boundary as `INFRASTRUCTURE`. That is exactly what
`CanonicalDurableRunCoordinatorTest > withCredentials cleanup failure folds a successful body to failure()`
measured at base:

```text
base: org.opentest4j.AssertionFailedError: scope close must still have run ==> expected: <1> but was: <0>
head: PASS
```

W1d releases the lease in `finally`, so the scope is closed on the throwing path too. The test file is
**byte-identical** at base and head (`git diff 45b26c49..HEAD -- <test>` is empty), so this is a
repaired production behaviour, not an edited assertion. Honest scope of the claim: the
`INFRASTRUCTURE` kind on that path still comes from the pre-existing child-dispatch exception
(`Unexpected error during pipeline run: No canonical core metadata registered ...`), not from the
cleanup fold — the shared loop rethrows it rather than folding it, which is correct for a
framework-boundary exception. The broader `withCredentials` body path remains pre-existing red
(3 failing names at base unchanged at head), and W1d does not claim otherwise.

## 3. Debt ledger

```text
HISTORICAL_CEILING            18   INMUTABLE — provenance/high-water mark, never raised, never lowered
pinned debt before W1d         4
pinned debt after  W1d         0   living state
discovered debt at W1d         0   must equal the pin (re-derived in Python by the verifier)
```

The ceiling is deliberately not lowered in the same commit as the ledger (that is the W1c user
decision, restated for W1d): the pin is living state, the ceiling is provenance, and collapsing the
two would destroy the only record of the original debt. The verifier asserts the literal `18`
separately from the literal `0` (control K8 lowers the ceiling and fails).

The structural law that replaced the credential-bypass site:

```text
BodyChildLoopInventory.EXPECTED = (loopDefinitions = 1, credentialAcquisitions = 1)
```

Counted from the coordinator source, so a duplicated body path fails **even when it contains no
literal and no dispatch*Block identifier** — the blind spot the name-based W1a scanner had.

## 4. Evidence

### 4.1 Gates executed

Round gate, both worktrees, identical argv
(`./v2/gradlew -p v2 check --continue --rerun-tasks`, JDK 24.0.2):

| Gate | argv | Result |
| --- | --- | --- |
| L5 head | `timeout 1500 ./v2/gradlew -p v2 check --continue --rerun-tasks` (slice worktree) | `BUILD FAILED in 18m 39s`, 107/107 tasks executed — pre-existing reds only (§4.2) |
| L5 base | same argv in `../pipeline-w1d-base` (`45b26c49`, XMLs cleared first) | see §4.2 |

Result truth is the JUnit XML under `v2/<module>/build/test-results/test/`, archived under
`docs/v2/07-uat/evidence/b10-w1d/raw/xml/` (`build-evidence-archives.sh` records the exact selection;
`suite-inventory.py` is byte-identical to the W1c copy, sha256 `2a50f03c…`).

Recorded digests (sha256, first 16 hex; full XML trees, not console transcripts):

```text
head worktree HEAD          bdb22c7204c78d56df1d8178abe57afc39650519
base worktree HEAD          45b26c495f86b54987f09854ed0aaf741bc37cee
raw/xml/module-suites-xml.tar.gz        afe7372cbe16295b…   (head: every module's XML)
raw/xml/base-module-suites-xml.tar.gz   58199fe90fcedc4a…   (base: the same module set)
raw/head-inventory.json                 35a09487f5d878ce…
raw/base-inventory.json                 56601ab8bedc2f02…
/tmp/w1d-gate-head.log                  44c74bcc359f79b0…   (21:02Z, BUILD FAILED 18m 39s, 107/107)
/tmp/w1d-gate-base.log                  542035ea24b33362…   (21:24Z, BUILD FAILED 18m 39s, 107/107)
```

Both logs contain an interleaved `BUILD SUCCESSFUL in 1s` around line 128: that is a nested Gradle
build run *by a test* (testkit), not this round gate. The gate result is the final summary line.

### 4.2 Zero new regressions, whole repository

| Module | base `45b26c49` | W1d head | delta |
| --- | --- | --- | --- |
| `pipeline-domain` | 395 / 0 / 0 | 397 / 0 / 0 | +2 tests, 0 reds |
| `pipeline-architecture-tests` | 262 / 1 / 0 | 272 / 1 / 0 | +10 tests, red unchanged (`Lfc0GlobalStateFitnessTest`) |
| `pipeline-application` | 1392 / 36 / 0 | 1392 / 35 / 0 | 0 tests, **−1 failure** (the repaired leak) |
| other 17 modules | all green | all green | 0 |

`CanonicalDurableRunCoordinatorTest` — the class this slice rewrites around:

```text
base 45b26c49 : 26 tests, 11 failures, 0 errors
W1d           : 26 tests, 10 failures, 0 errors
head failing names - base failing names = {}                      (no new failure)
base failing names - head failing names = { "withCredentials cleanup failure folds a successful body to failure()" }
```

The verifier re-derives both of those sets from the archived XML and requires the subtraction to be
exactly that one name. No class red at head is green at base.

`suite-inventory.py diff` reports `regression signals: 1` and exits 1 for this slice. That signal is
the *class with a changed failing set*, which the tool classifies pessimistically as a re-baseline
because it cannot see the direction. The direction is the whole claim here and it is checked
explicitly: the head set is a strict SUBSET of the base set, one name smaller, and a re-baseline
(the failure that changes is a *new* one) would fail both of the verifier's subtraction checks.

### 4.3 Guard and migration suites

| Suite | Result |
| --- | --- |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` | 6 / 0 |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest$ViolationFixture` | 10 / 0 |
| `Lfc2BodyExecutionPolicyFitnessTest` | 10 / 0 |
| `Lfc2DurableAggregateIdentityFitnessTest` | 5 / 0 |
| `Lfc2DurableCoordinatorScopeFitnessTest`, `Lfc2RegistryFamilyFitnessTest` | 4 / 0, 3 / 0 |
| `BodyExecutionPolicyTest` (6 nested classes), `StepDescriptorRegistryTest`, `StepDescriptorBodyMetadataTest`, `CompiledPipelineValidatorTest` | 28 / 0, 11 / 0, 6 / 0, 7 / 0 |

### 4.4 Independent verification

`verify-b10-w1d-receipt.py` is HISTORICAL (every code claim read from the slice commit via `git show`)
and INDEPENDENT (the body-declaration model, the 13 descriptor rows, the shared-loop inventory, the
aggregate identities and the remaining routing debt are all re-derived in Python from Kotlin sources
and compared against expectation tables stated in the verifier, never read back from the ledger or
from the Kotlin guards). 12 negative controls mutate the working tree and require the owning law to
go red, then restore it:

```text
K1  second body-child loop (no literal at all)      K7  pinned ledger raised above zero
K2  concrete Step literal in the coordinator        K8  HISTORICAL_CEILING lowered
K3  re-introduced body id allowlist                K9  durable aggregate key renamed
K4  re-introduced dispatch*Block                   K10 identity dropped from the pinned ALL
K5  second credential acquisition site             K11 execution owner defaulted
K6  release moved out of the finally               K12 removed StepDescriptor body field returns
```

Two verifier defects were found and fixed by the controls themselves, which is the point of running
them: a function extractor that stopped at the first inner `}` (K6 initially "passed" nothing) and one
that rejected a receiver function (`fun BlockStepNode.decodeCredentialBindings()`).

## 5. Boundaries of the claim

- The two durable identities are **pinned, not proven replay-correct** here: W1d types and guards
  them; changing a key remains a replay-breaking change owned by ADR-0075/ADR-0076, not by this slice.
- The scanner still cannot see routing that builds a Step name at runtime, routes on an enum ordinal
  or passes a concrete id in from a caller. It over-reports in exchange (prose is scanned on purpose).
- The four pre-existing failures outside B10 scope remain out of scope and are not regressions:
  `UatLocal008CredentialsTest` (CP-001 corpus byte-identity, CR-BD-027), `UatLocal009` archiveArtifacts,
  `WithCredentialsCompileIntegrationTest` (present at both base and head).
- `installDist` digests are non-deterministic in this repo, so the differential is stated over parsed
  source and per-class JUnit XML, both reproducible.

## 6. Exit criteria

- [x] one body-child loop; one credential acquisition (`BodyChildLoopInventory.EXPECTED`, guarded).
- [x] pinned ledger `0`; `HISTORICAL_CEILING` still `18`.
- [x] durable identities typed, pinned and separately guarded; no literal in the coordinator.
- [x] no defaulted owner/shape/cardinality; `StepDescriptor` has one body value.
- [x] zero new regressions (module inventory diff, base vs head); one pre-existing failure repaired.
- [x] receipt written; verifier historical + independent; controls red on a pristine tree.
