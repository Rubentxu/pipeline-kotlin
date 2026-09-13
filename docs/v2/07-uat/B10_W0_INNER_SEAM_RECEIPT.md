# B10 / W0 — `BodyInvoker` inner seam (design + domain contract)

> code-under-test: `a9aca7aa`
> branch: `cycle/lfc2-e1-bodyinvoker`
> base: `fbcbc51f` (= `origin/main` after Lane R, PR #46, landed)
> evidence: PR — carried by the evidence commit, whose SHA the PR body records
> ADR-0081 status: **proposed** — acceptance is a user gate, not claimed here

## 1. What this slice is

B10 is "block Steps re-enter the engine; no `dispatch*Block` collection"
(`IMPLEMENTATION_BACKLOG.md`, gate HF1/HF3, authority ADR-0073). Its roadmap sequencing
note reads: *"LEG-1 precedes B10..B13 (BodyInvoker/retry/timeout/parallel) so those are
designed against a single execution architecture."*

**LEG-1 is DONE** (all five slices, ledger `LB02_LEG1_PIPELINE_RUN_BURN_DOWN.md`:
`PipelineRun.kt` and `PipelineOrchestrator.kt` deleted, `FArchLeg1ExecutionAuthorityTest`
green). The sequencing precondition therefore holds and B10 is unblocked. `PipelineRun.kt`
and `PipelineOrchestrator.kt` do not exist in the tree at this commit.

This slice is **W0**: the design plus the inner contract. It is deliberately the smallest
part of B10 that can be verified on its own.

Added, nothing modified and nothing deleted (`git diff --name-status fbcbc51f..a9aca7aa`
is four `A` rows):

```text
A  docs/v2/04-adrs/ADR-0081-body-invoker-continuation-model.md
A  docs/v2/08-spikes/WAITUNTIL_BODY_INVOKER_SPIKE_FOLLOWUP.md
A  v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvoker.kt
A  v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInvokerSeamTest.kt
```

The contract lives in `pipeline-domain`: it is a port, so it cannot depend on the
coordinator, the dispatcher, the journal, or any adapter, and it does not. The slice
adds no capability provider, no dispatcher case, no DSL surface, and no step. Nothing
in production reads `BODY_INVOKER_CAPABILITY` yet, so no behaviour changes.

## 2. Traceability (scope firewall)

Milestone → slice → exit criterion → gate. W1..W3 are **not** part of this PR and are
recorded here so the next slices cannot be invented ad hoc.

| Slice | Milestone → exit criterion | Gate |
| --- | --- | --- |
| **W0** (this PR) | B10 → ADR-0081 written; typed seam compiles in `pipeline-domain`; `BodyRef` determinism, closed `BodyOutcome`, attempt-index invariant and JSON round-trip proven at HF0 | HF0; ADR-0073; contract suite |
| W1 | B10 → the engine calls `BodyInvoker.invoke` for a block body; `BlockSegment` path derives the `BodyRef`; **no** `dispatchRetryBlock`/`dispatchTimeoutBlock` collection exists | HF1; ADR-0073 |
| W2 | B10 → per-attempt durable control rows anchored on `BodyRef` (ADR-0075 pattern); resume-mid-body is a journal read, not a re-execution | HF3; ADR-0075 |
| W3 | B10 → `core.waitUntil` and a second consumer run through the shared body machinery with zero coordinator change per consumer | HF1/HF3; BLOCK_STEP_EXECUTION |

Why W0 can land alone: the exit criterion is a property of the domain module, and it is
checkable without any wiring. Why it must land before W1: W1 rewires the engine seam, and
doing that against an unreviewed contract would make the contract's defects
indistinguishable from the wiring's.

## 3. Evidence

Fresh runs on `a9aca7aa`, JUnit XML as the result truth, whole modules rather than
single classes because this slice adds a production file:

```text
module                        tests  failed  errors
pipeline-domain                 368       0       0
pipeline-architecture-tests     241       1       0
```

`BodyInvokerSeamTest` — the HF0 contract suite for this slice — is `8/0/0`.

The single architecture failure is pre-existing and unrelated. It is
`Lfc0GlobalStateFitnessTest`, reporting `Capabilities.kt:76 System.getProperty("user.dir")`,
a token that lives in KDoc prose describing what handlers must **not** do. That file is
untouched by this branch, and the evidence is reused rather than re-run:

```text
git diff --stat 2b391e76 HEAD -- …/application/Capabilities.kt   ->  empty
```

`2b391e76` is where the failure was first characterised and where the base XML of that
exact class was archived (`docs/v2/07-uat/evidence/lane-r/raw/xml/module-suites/`). The
inputs that decide the failure — `Capabilities.kt` and the fitness test — are identical
at that commit and at this one, so the archived result still describes this tree. The
class is *not* re-run to manufacture a matching number for a file nobody touched.

## 4. Verifier controls

`docs/v2/07-uat/evidence/b10-w0/verify-b10-w0-receipt.py` asserts properties rather than
string presence, and is therefore only worth something if mutating what it protects makes
it fail. Ten controls were run, each expected to fire for a specific reason; the reason
was read, the artefact was restored, and the next control started from a green run. The
ledger is §7. A control that fails for an unintended reason proves nothing about the
intended one and is re-run rather than accepted.

## 5. What this slice does not claim

- **ADR-0081 is not accepted.** It is `proposed`. Accepting it is a design-authority act
  and belongs to the ADR's own gate, not to a merge.
- No step becomes CERTIFIED. The counters are unchanged: certified 11, legacy-executable
  2, registry-primary 11. This slice certifies no step and burns down no legacy id.
- No block step re-enters the engine yet. W1 is where that happens, and the exit criterion
  "no `dispatch*Block` collection" is a W1 property, not a W0 one.
- No durable behaviour changes. The replay/recovery semantics the contract describes are
  design; W2 implements them.

## 6. Reproduce

```bash
./v2/gradlew -p v2 --continue :pipeline-domain:test :pipeline-architecture-tests:test
python3 docs/v2/07-uat/evidence/b10-w0/verify-b10-w0-receipt.py
```

## 7. Verifier control ledger

```text
1  delete the contract from pipeline-domain
2  rename a BodyRef factory so the ref surface is no longer derivable
3  give BodyOutcome a Boolean member instead of the closed algebra
4  drop the attempt-index invariant from BodyInvocationContext
5  wire BODY_INVOKER_CAPABILITY into a second production file
6  introduce a forbidden dispatch*Block collection
7  silently promote ADR-0081 from `proposed` to `accepted`
8  corrupt a module total inside the archived tarball
9  edit the reused base XML so the pre-existing failure looks green
10 commit a change to an existing engine file, making the slice non-additive
```

Controls 5 and 6 carry the most weight, because "no production wiring yet" and "no
`dispatch*Block` collection" are the two properties the whole slice exists to establish
and the two a future W1 could quietly break. Control 7 is not about code at all: it
guards the design-authority boundary, since accepting an ADR is a gate of its own and a
merge must not be able to do it by accident. Control 10 is the only one that required a
commit; it was reverted with `reset --hard` to the recorded SHA and the verifier was
re-run green before the next control.

Control 9 is the reused-evidence guard. This receipt deliberately does **not** re-run
`Lfc0GlobalStateFitnessTest`; it cites evidence captured earlier, which is only sound
while `Capabilities.kt` is unchanged. The verifier asserts that condition directly, so a
green-looking base XML cannot be substituted for a real one.
