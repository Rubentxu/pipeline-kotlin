# B10 / W1 — pre-flight (G0) and slice plan

> status: PRE-FLIGHT COMPLETE — implementation NOT started
> lane: `cycle/lfc2-e1-bodyinvoker-w1`
> base: `1afb4799` (= `origin/main` after B10 W0, PR #47, landed)
> authority: ADR-0073 (block-step re-entry), ADR-0081 (accepted), ADR-0075 (control rows)
> milestone: `IMPLEMENTATION_BACKLOG.md` B10 — "block Steps re-enter engine; no
> `dispatch*Block` collection"

This document is the G0 step of the burn-down template: the baseline and the
pre-existing failures are characterised **before** anything is touched, so that a later
slice cannot hide a regression behind them.

## 1. What W1 has to achieve, stated as an observable

```text
block Step handler → declares its body policy on StepContract → engine reads the contract
                    → engine calls BodyInvoker.invoke → body re-enters the canonical spine
```

and, as a negative:

```text
CanonicalDurableRunCoordinator MUST NOT branch on a concrete block Step name.
```

The second sentence is the one with teeth, because the coordinator currently does exactly
that, and the fitness suite currently does not notice.

## 2. G0 baseline

### 2.1 The forbidden pattern is present, in production

`v2/pipeline-application/src/main/kotlin/.../durable/CanonicalDurableRunCoordinator.kt`
contains **15 concrete Step-name literals** at three structural sites:

| Site | Lines | What it is |
| --- | --- | --- |
| `canonicalBodyStepIds` | 103–110 | a hardcoded set of the six block Step ids |
| `projectShellScope` | 293–335 | `when (pluginStepId.value)` over `core.dir`, `core.timestamps`, `core.withEnv`, `core.timeout`, `core.retry` |
| `dispatchBody` | 1341 | a `core.withCredentials` special case that bypasses the scope machinery |
| `dispatchWithCredentialsBlock` | 1823 (called at 1342) | the bypass target: a second block-dispatch seam beside `dispatchBody` |

plus `when (scope)` at 1363–1435, which is the other half of the same switch, and
`"core.retry"` / `"core.parallel"` literals at 1056 and 1798–1810 used to build durable
operation identities.

The `dispatchWithCredentialsBlock` seam was found while writing the verifier for this
document: an earlier draft asserted "no `dispatch*Block` exists at base" and the assertion
failed, correctly. The constitution forbids a `dispatch*Block` **collection**, and there is
none — but the single-function form is present, and it is the same closed-world shape.
W1 must remove it, not preserve it.

This is the "polymorphic dispatcher" failure mode named in `AGENTS.md`: *"the moment a
coordinator's `when` branch discriminates `core.echo` vs `core.sh`, the spine has regressed
to a closed world."* Here it discriminates six block steps instead.

### 2.2 The guard that should catch it does not

`Lfc2DurableCoordinatorScopeFitnessTest` declares the right principle in its own test name
— *"durable coordinator has no concrete core step routing"* — and then asserts three
literals only, by searching the coordinator's source text:

```text
assertFalse(source.contains("isShellPlugin"))
assertFalse(source.contains("\"core.sh\""))
assertFalse(source.contains("\"core.echo\""))
assertFalse(source.contains("CanonicalCoreStepCommand."))
```

It is green at this commit, at 4/4, while the file it guards contains six concrete block
Step names. **A guard whose name is a principle and whose body is an enumeration of two
examples does not enforce the principle.** Nothing scans for `projectShellScope`,
`dispatchBody`, `dispatchWithCredentialsBlock` or `BlockShellScope` either: no architecture
test mentions any of them.

### 2.3 Pre-existing red baseline in the target area

Fresh run at `1afb4799`, JUnit XML as the result truth:

```text
class                                  tests  failed  errors
CanonicalDurableRunCoordinatorTest        26      11       0
CompatibilityCorpusTest                   20       2       0
UatTimeoutBlockDurableTest                 3       0       0
OpIdBodyPathTest                           8       0       0
FileBasedRetryControlJournalTest          11       0       0
WindowCRetryRecoveryProductionWiringTest   1       0       0
Lfc2DurableCoordinatorScopeFitnessTest     4       0       0
RetryAcceptanceMatrixTest / RetryReconciliationDriverTest /
ProductionRetryChildRowReaderTest          nested suites, green
```

The 11 coordinator failures are **already characterised**, in three separate closure
receipts, and this run does not exceed them:

| Receipt | Recorded baseline |
| --- | --- |
| `CTX_P_CLOSURE_RECEIPT.md:132` | `CanonicalDurableRunCoordinatorTest`: 12/26 failing, same rows |
| `E_EM_11_CLOSURE_RECEIPT.md:80` | 12/26 failures, identical set |
| `LB02_A5_45_RECOVERY_AND_UNREACHABLE.md:98` | baseline red did not increase; stays 24/14 |

11 ≤ 12, so the baseline has not regressed. `CompatibilityCorpusTest`'s two failures are
the known corpus accounting defect (asserts 19 fixtures, corpus holds 20), independently
found during Lane R and left unfixed there.

**These 11 failures are not fixed by W1 and must not be widened by it.** They are the
ordinary-vs-registry coordinator construction gap described in
`LB02_A5_3B_COORDINATOR_CONSTRUCTION_CLASSIFICATION.md`: those rows construct the
coordinator without the production registry composition, which AGENTS.md permits only for
explicit fail-closed tests or intentional legacy characterisation.

### 2.4 What pins the current behaviour

Ten test files reference the six block Step ids, including `CompatibilityCorpusTest`, the
retry control-row suites and `UatTimeoutBlockDurableTest`. Any migration must keep the
durable semantics they encode — retry attempt identity, timeout watchdog propagation,
credentials acquire/close — even where the test rows themselves are red for the
construction reason above.

## 3. Why W1 cannot be one commit

The switch is not dead code. It carries the retry control-row contract (ADR-0075), the
timeout watchdog projection, the `dir` scope push and the credentials lifecycle. Moving it
behind the registry means the block Step declares a typed body policy that the engine
reads, which requires:

1. a declared body policy on the block Step contract (domain + application);
2. the engine resolving that policy **by registry lookup, not by step name**;
3. a capability binding that presents `BODY_INVOKER_CAPABILITY` to the handler;
4. the six block Steps declaring their policy and their events;
5. the durable identity construction (`"core.retry"`, `"core.parallel"`) to stop being a
   name the coordinator knows.

Each of those is a behaviour-bearing change in a timing- and durability-sensitive area, so
each gets its own slice and its own gate.

## 4. Slice plan

| Slice | Milestone → exit criterion | Gate |
| --- | --- | --- |
| **W1a** | B10 → the guard enforces the *principle*, not two examples: a counted allowlist of the existing concrete block routing, so any **new** concrete routing fails closed and the debt is a number that can only go down | L4 architecture fitness |
| W1b | B10 → a typed body policy is declared on the block Step contract and resolved by registry lookup; `projectShellScope` reads the contract, not `pluginStepId.value`; `canonicalBodyStepIds` derived from the registry | HF1; ADR-0073 |
| W1c | B10 → the `when (scope)` body-execution switch and the `core.withCredentials` bypass move behind the policy; block Steps emit their own typed events | HF1/HF3 |
| W1d | B10 → durable identity construction stops naming `core.retry`/`core.parallel`; `BodyInvoker` is bound as a capability and one block Step reaches CERTIFIED through it | HF1/HF3; ADR-0074 |

W1a is deliberately separate and first: it converts an unenforced principle into a
measurable counter, which is what makes W1b..W1d's progress visible instead of asserted.
It is the same shape as the G8 counter discipline — name the debt, count it, force it to
zero.

## 5. Sequencing dependencies

- W1b..W1d must not begin while the `CanonicalDurableRunCoordinatorTest` baseline is
  uncharacterised. It **is** characterised (§2.3), so they may begin; they must not
  increase it, and a slice that would need those rows rewired must say so explicitly.
- C (`waitUntil`) and D (`load`) remain stacked behind W1. `core.waitUntil` is the driving
  use case for ADR-0081 and its handler needs the capability binding that W1d lands.
- The `installDist` digest note from G8 is unrelated and stays in its own lane.

## 6. What W1 must not do

```text
- add a dispatch*Block / dispatchRetryBlock / dispatchTimeoutBlock collection;
- teach the coordinator a seventh concrete Step name;
- move retry/timeout semantics and leave the control rows behind (ADR-0075);
- convert the 11 pre-existing coordinator failures into skips or delete them;
- weaken the guard by widening its allowlist to make a slice pass.
```

## 7. Evidence plan

Per slice: fresh JUnit XML with the class XML deleted beforehand, base-vs-head with the
same argv, the pre-existing failure set diffed rather than counted, and a verifier whose
controls are re-derived whenever the verifier itself changes shape — which is what had to
happen to the W0 verifier when it became historical (`B10_W0_INNER_SEAM_RECEIPT.md`).

## 8. Verifier and negative controls

`evidence/b10-w1-g0/verify-b10-w1-preflight.py` re-derives all 36 claims in this document.
It reads every code claim out of the base commit (`git show` / `git grep` against
`W1_G0_BASE`), never off the working tree, so it does not expire when the first W1 slice
lands. Env overrides `W1_G0_BASE` / `W1_G0_DOC` / `W1_G0_TAR` exist so the controls below
can drive it.

Six controls, each run **independently from the clean base** (a control runs cumulatively
over a previous control's commit inherits its failures and proves nothing — the mistake
made on the first pass here), each with a real temporary commit and an env override:

| Control | Mutation | Observed | Correct? |
| --- | --- | --- | --- |
| CA | remove one id from `canonicalBodyStepIds` | C1 (count) + C3 (set size) fail | yes — both claims are about that set |
| CB | make the guard assert a block step name | C5 only fails | yes |
| CC | add a `dispatch*Block` collection | both C7 rows fail | yes |
| CD | remove the `12/26` token from a closure receipt | C9 only fails | yes |
| CE | change the literal count in this document | C11 only fails | yes |
| CF | corrupt the archived XML failure total | C8 fails, and C11 reports doc-vs-XML mismatch | yes |

CF is the control that matters most: it shows the document cannot silently drift from the
XML, because the baseline rows are re-derived rather than transcribed. It does not trip C10,
and should not — 9 is still within the documented ceiling of 12.

Three claims in an earlier draft of this document were **false**, and the verifier is what
caught them:

1. "no `dispatch*Block` exists at base" — there is one, `dispatchWithCredentialsBlock`
   (§2.1). The constitution forbids the *collection* form, which genuinely does not exist;
   the single-function form does, and W1 has to remove it.
2. the guard was described as asserting quoted literals; it asserts on source *text* via
   `assertFalse(source.contains(...))`, so the tokens appear escaped (fixed in §2.2).
3. an early C7 draft filtered `git grep -l` output as if it were line output, so it matched
   whole files. Fixed by using line-level grep.
