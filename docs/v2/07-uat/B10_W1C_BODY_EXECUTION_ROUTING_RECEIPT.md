# B10 / W1c — body execution policy routing (receipt)

**Slice:** `cycle/lfc2-e1-bodyinvoker-w1c`
**Base:** `main == bd82e1eb` (slice parent, the differential reference)
**Scope:** LFC-2 / B10 burn-down of `pipeline-kotlin`, third slice (W1c) after PR #50 (W1b, typed body
execution policy) and PR #51 (AGENTS.md rules 7–14).
**Verifier:** `docs/v2/07-uat/evidence/b10-w1c/verify-b10-w1c-receipt.py` (historical + independent,
negative controls included).

## 1. What this slice changes

W1b introduced a declared `BodyExecutionPolicy` but the coordinator still decided *which engine owns
a body* by matching on the concrete Step identity (`when (pluginStepId.value)` over five Step names,
plus a `pluginStepId.value == "core.withCredentials"` bypass). W1c removes that name-keyed routing
from the production path and moves the decision into the declared model.

| Layer | Change |
| --- | --- |
| `pipeline-domain` | `BodyExecutionOwner { CANONICAL_ENGINE, LEGACY_LINEAR }`; `StepDescriptor.bodyExecutionOwner` (default `CANONICAL_ENGINE`); `StepDescriptorRegistry.bodyStepIds(owner)`, `.bodyPolicyResolver(support)`, `.bodyPolicy(key, support)`; `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING`; new `core.timestamps` descriptor row (`Scoped(Timestamps)`, no invented context kind); `core.catchError` / `core.warnError` declare `LEGACY_LINEAR` |
| `pipeline-application` | `canonicalBodyStepIds` derived from declared ownership (was a hard-coded `setOf` of six names); `projectShellScope(pluginStepId.value)` replaced by `projectBodyExecution(policy)` + `projectScopedBody(projection)`; new sealed `BodyExecutionProjection { Scope, CredentialLifecycle, InvalidInput, Unimplemented }`; credential lifecycle routed **by policy**; malformed input returned as typed `SCHEMA` failure instead of thrown |
| `pipeline-architecture-tests` | pinned concrete-routing ledger lowered `18 → 4` (`HISTORICAL_CEILING` untouched at 18); W1b firewall law inverted (the coordinator must resolve body policies through the port); guard control fixtures updated |

The declared-policy model is the migration frontier, not a silent rewrite: `core.parallel` still has
**no** descriptor row (PAR-D's branch aggregate is a stage-level operation, so declaring it body-
bearing would be a lie), and every shape the coordinator interprets is admitted by
`SCOPED_SEQUENTIAL_RETRYING`. A retrying declaration still reaches the canonical engine (W1b); a
`Parallel` declaration is rejected fail-closed rather than silently degraded.

## 2. Behaviour preservation (the differential claim)

The only source of truth for "the migration changed no behaviour" is the **pre-slice** coordinator,
read at `bd82e1eb`, not a restatement of the new code. The verifier parses the old keyed arms and
requires each family's declared policy to match:

| Step | pre-W1c routing (measured at `bd82e1eb`) | W1c declared policy |
| --- | --- | --- |
| `core.dir` | `BlockShellScope.Directory` | `Scoped(WorkingDirectory)` |
| `core.timestamps` | `BlockShellScope.TimestampsScope` | `Scoped(Timestamps)` |
| `core.withEnv` | `BlockShellScope.EnvScope` | `Scoped(Environment)` |
| `core.timeout` | `BlockShellScope.Timeout` | `Scoped(Deadline)` |
| `core.retry` | `BlockShellScope.Retry` | `Retrying` |
| `core.withCredentials` | name-keyed bypass | `Scoped(CredentialLease)`, routed by projection |

Landing the slice on the deterministic rather than the timings layer is deliberate: `installDist`
digests are non-deterministic here, so the differential is stated over parsed source and over
per-class JUnit XML, both of which are reproducible.

## 3. Debt ledger

```text
HISTORICAL_CEILING            18   INMUTABLE — provenance/high-water mark
pinned debt before W1c         18
pinned debt after  W1c          4   living state
discovered debt at W1c          4   must equal the pin
```

The two numbers answer different questions and are deliberately not collapsed: the ceiling
records how much concrete routing debt existed when the guard was introduced, the pin is the
current measured state. The W1b note that the ceiling should be lowered in the same commit as
the ledger is **superseded by W1c** (user decision, 2026-09-13): lowering it would destroy the
only record of the original debt. The guard keeps the two laws separate — a ledger raised above
the ceiling fails as `LedgerRaisedBeyondCeiling`, and a fine-grained pin test asserts the
literal 4 against an independent re-scan.

The four remaining items are one genuine routing site and two concrete identities that are not body
routing at all:

```text
sites         = { DISPATCH_WITH_CREDENTIALS_BLOCK }        (still a separate execution path)
concreteNames = { core.parallel, core.retry }              (PAR-D row, RETRY-D control row)
bodyStepIds   = { }                                        (derived from declared ownership)
stepIdSwitches= 0
```

`BodyRoutingSite` keeps the two retired cases (`CANONICAL_BODY_STEP_IDS`, `PROJECT_SHELL_SCOPE`) so
that re-introducing either shape fails the guard instead of passing as "no site". The guard's total
is not a literal: it pins the ledger to the scan in total **and** item by item, and the ledger is the
living state. The independent 4 is re-derived in Python by the verifier from the coordinator source,
and the pinned ledger is separately required to agree with that re-derivation.

## 4. Evidence

### 4.1 Gates executed

| Gate | argv | Result |
| --- | --- | --- |
| L4 application module | `./v2/gradlew -p v2 :pipeline-application:test` | 1392 tests, 36 failures, 0 errors — all 36 pre-existing (§4.2) |
| L4 domain module | `./v2/gradlew -p v2 :pipeline-domain:test` | 395 tests, 0 failures, 0 errors |
| L4 architecture module | `./v2/gradlew -p v2 :pipeline-architecture-tests:test` | 262 tests, 1 failure, 0 errors — `Lfc0GlobalStateFitnessTest` pre-existing |
| L5 round gate | `./v2/gradlew -p v2 check` | see §4.3 |

Result truth is the JUnit XML in `build/test-results/test/`, archived under
`docs/v2/07-uat/evidence/b10-w1c/raw/xml/` (`build-evidence-archives.sh` records the exact file
selection).

### 4.2 Zero new regressions

The `CanonicalDurableRunCoordinatorTest` class — the class this slice modifies around — is red in
11 of 26 tests **at the slice parent** with the identical failing test names, so the slice neither
causes nor masks them:

```text
base bd82e1eb : 26 tests, 11 failures, 0 errors
W1c           : 26 tests, 11 failures, 0 errors   (same 11 names, byte-identical set)
```

The whole application module was measured the same way: the 14 red classes and their 36 failing test
names are identical at base and at W1c, and no class red at W1c is green at base. The verifier
re-derives this comparison from the archived XML rather than trusting the table above.

### 4.3 Round gate

`check` was run in **both** worktrees with the same argv, because a plain `check` is not a
whole-suite oracle in a tree with pre-existing reds: the first attempt failed in 1 m 34 s at
`:pipeline-scripting-api:test` and Gradle then short-circuited the rest of the graph, so
`:pipeline-application:test` never ran. `--continue` executes every module's `test` task and is
what makes the inventory below complete.

```text
argv (head):  timeout 1270 ./v2/gradlew -p v2 check --continue
argv (base):  timeout 1800 ./v2/gradlew -p v2 check --continue      # in ../pipeline-w1c-base, detached at bd82e1eb
```

| Run | Budget derivation | Observed | Result |
| --- | --- | --- | --- |
| head | last green round gate 977 s × 1.3 = 1270 s | **18 m 18 s (1098 s)** | BUILD FAILED — 18 pre-existing red classes |
| base | cold worktree (nothing compiled); budget clamped to the documented ceiling 1800 s | **18 m 11 s (1091 s)** | BUILD FAILED — the same 18 red classes |

The base budget is the ceiling rather than `977 × 1.3` because the base worktree starts with no
build output at all, which is the regime the ceiling exists for. It finished well inside it, so
no timeout was raised mid-run and no run was killed.

### 4.4 Zero new regressions, whole repository

`suite-inventory.py` derives a per-module inventory from the XML of both runs and diffs them.
Modules are restricted to the 20 project paths declared in `v2/settings.gradle.kts`; a stale
`v2/pipeline-protocol/build/` directory (removed from the build graph, XMLs from 2026-09-03)
is excluded because it is not part of the current build.

```text
modules compared            20
regression signals           0
suite-total changes          pipeline-domain 388 -> 395  (+7, intended)
                             pipeline-architecture-tests 261 -> 262  (+1, intended)
                             every other module: identical, test for test
```

The 18 red classes are red at base with identical failing test names:

```text
pipeline-application        14 classes / 36 failing tests   (incl. CanonicalDurableRunCoordinatorTest 26/11)
pipeline-architecture-tests  1 class  /  1 failing test     Lfc0GlobalStateFitnessTest
pipeline-scripting-api       1 class  /  1 failing test
pipeline-scripting-kotlin24  2 classes/  7 failing tests
```

### 4.5 Independent verification

```text
python3 docs/v2/07-uat/evidence/b10-w1c/verify-b10-w1c-receipt.py             # 71/71 checks passed
python3 docs/v2/07-uat/evidence/b10-w1c/verify-b10-w1c-receipt.py --controls  # 10/10 controls red
```

The verifier reads every code claim from `git show <slice>:<path>` (the slice SHA is resolved
from this receipt's own history, so a later slice editing these files cannot move it), re-derives
the policy table, the ownership table, the canonical body set and the coordinator debt in Python,
and compares against expectation tables stated inside the verifier. Negative controls mutate the
working tree (verified clean first) and must each turn the verifier red for the intended law:

```text
K1  catchError declares canonical ownership          -> legacy body set check
K2  the timestamps row is deleted                    -> canonical body set check
K3  core.parallel gains a descriptor row             -> declared gap check
K4  a hard-coded body id allowlist is re-introduced  -> allowlist check
K5  the projection switches on the Step identity     -> switch check
K6  withCredentials loses the credential lease       -> differential check
K7  source drift AND the ledger edited to match it   -> re-derived total check
K8  the engine support admits PARALLEL               -> support check
K9  the descriptor default owner becomes legacy      -> canonical default check
K10 eligibility compares string names again          -> typed id check
```

K7 is the important one: the pin is not an authority, so an edit that makes the ledger agree with
a drifted source still fails against the verifier's own number.

## 5. Open items carried forward

1. `dispatchWithCredentialsBlock` is the last genuine routing site and the next burn-down target.
2. `core.parallel` has no descriptor row by design; PAR-D remains the owner of the stage branch
   aggregate.
3. The 36 pre-existing application reds (compatibility corpus, UAT-L8/L9, seam characterization,
   `ExecutionBoundaryFactoryTest`, legacy metadata resolvers) are out of W1c scope. They were not
   re-baselined and not widened.
4. `Lfc0GlobalStateFitnessTest` remains the single pre-existing architecture red at base and at W1c.

### W1d entry criteria (from the W1c review)

1. **Burn the credential dispatcher.** `dispatchWithCredentialsBlock` is the last routing site that
   the ledger still counts. Removing it must lower the pin to 2 (`core.parallel`, `core.retry`) and
   keep `HISTORICAL_CEILING` at 18.
2. **Make the incoherent declaration unrepresentable.** `StepDescriptor.bodyExecutionOwner`
   currently defaults to `CANONICAL_ENGINE`, so a new `takesBody = true` row that omits the owner
   would silently acquire canonical semantics without demonstrating them. The target is a model in
   which "this Step takes a body and nobody owns it" cannot be expressed — ownership required for
   body Steps, or ownership carried by a type that only body Steps can use. Non-blocking for W1c
   because every current row is characterised and the routing is already registry-derived.
3. `core.parallel` / `core.retry` are durable identity questions (PAR-D row, RETRY-D control row),
   not body-routing questions; retire them from the ledger by giving them a durable identity
   declaration rather than by re-filing them as routing debt.
