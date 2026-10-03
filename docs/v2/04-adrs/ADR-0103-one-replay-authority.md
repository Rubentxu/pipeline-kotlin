---
type: adr
id: ADR-0103
title: "One replay authority: address and semantics are separate concerns"
status: accepted
date: 2026-10-03
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0065
  - ADR-0066
  - ADR-0084
  - ADR-0093
  - docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md
  - docs/v2/07-uat/S4_R0_REPLAY_IDENTITY_CONFLICT_MEMO.md
  - docs/v2/08-spikes/SPIKE-016-DURABLE-SCRIPTED-REPLAY.md
  - docs/v2/08-spikes/SPIKE-017B-S4-R-KERNEL.md
---

# ADR-0103 — One replay authority: address and semantics are separate concerns

## Context

Two ADRs already establish the direction. **ADR-0065** D2 makes durable replay
deterministic identity against the journal, and its Rollback clause is explicit:
*"If deterministic scripted replay cannot prove stable operation identity, stop
at the last accepted EM gate and keep scripted runtime experimental."*
**ADR-0093** makes runtime-returning DSL Steps truthful façade calls.

What neither settles is a question both depend on: **when a Step is reached
again, who decides what happens?**

Measured, on `fd7e45a7` and `10e5c4c2`, the answer today is *two different
answers*, and they do not agree.

`ScriptedRegistryInvoker.invoke` implements its own durable protocol: it computes
the fingerprint under a literal `ReplayPolicy.MEMOIZED`, reads the journal, and
interprets `OperationStatus` with its own table. The canonical path delegates the
same decision to `DurableInvocationResolver` + `EffectReplayPolicy.decide`.

S4-R-POL executed both against the same Step contracts and the same journal
states and recorded the divergence:

| Step | journal | canonical | scripted |
|---|---|---|---|
| `core.sh` (RERUN) | `FAILED` | re-executes, repairs the row to `SUCCEEDED` | `REPLAY_COMPATIBILITY`, row stays `FAILED` **permanently** |
| `core.sh` (RERUN) | `RUNNING` | recovery branch, row leaves `RUNNING` as `LOST` | never leaves `RUNNING`, cannot reconverge |
| `core.sh` (RERUN) | fresh | fingerprint under the descriptor's policy | fingerprint under a hardcoded `MEMOIZED` |

The third row is the one that decides this ADR. The scripted fingerprint is a
function of the whole `OperationInput`, and the scripted `OperationInput`
describes the operation in its own namespace, so the two hashes must differ. But
the **policy axis** must not: a fingerprint that encodes a policy other than the
one that governs reconciliation is a lie in the durable record, and it is what
lets a `RERUN` step be treated as memoizable.

There is a second, deeper finding. The replay semantics in use are **not** the
ones the vocabulary claims:

```text
ReplayPolicy.RERUN is documented as "Always re-executes regardless of cached output"
DefaultEffectReplayPolicy implements  RERUN + SUCCEEDED journaled -> SKIP
```

`EffectReplayPolicy` publishes a decision matrix on its interface that says the
opposite, and the implementation's own comment calls it *"naming debt"*. The
first unit of work in this sequence found three honoured-looking declarations
that were not; this is the fourth, and it is the one that had no test behind it.

A fifth follows from writing the normative table below: the precedence between
the policy branches and `ABORTS_PIPELINE` is implemented one way and documented
the other, and the implemented way lets a policy name pre-empt a containment
effect. That is a defect, not a naming issue, and D1 changes it.

## Decision

### RPL-1 — Separate address, shared semantics

```text
ADDRESSING / IDENTITY SPACE
    declarative → core.sh
    scripted    → scripted.core.sh
    MAY differ. Today it does, deliberately.

REPLAY SEMANTICS
    effects · replayPolicy · recoveryPolicy
    fingerprint semantics
    journal-state interpretation
    recovery decision
    MUST come from one authority.
```

A frontend MAY own its address namespace. A frontend MUST NOT own replay
semantics. The `scripted.` namespace is preserved verbatim and is not unified
with the declarative one.

### RPL-2 — The descriptor is the sole authority

`effects`, `replayPolicy` and `recoveryPolicy` come from the `StepDefinition` /
`StepDescriptor`, resolved by Step key through `RegistryStepMetadataResolver`.
No execution surface may substitute a literal policy for a declared one.

### RPL-3 — Fingerprint honesty

A durable fingerprint MUST encode the policy that actually governs
reconciliation for that operation. This is the rule the measured `core.sh`
divergence violates today.

### RPL-4 — Decisions do not materialise frontend values

The replay decision selects reuse / execute / abort / recovery. Materialising a
typed value is a **subsequent** step, owned by the consumer, through the Step's
declared `outputCodec`. `DurableInvocationResolver` answers *"what must
happen?"*, never *"what value does the frontend want?"*.

Consequently `InvocationReconciliation.ReuseCompleted` is **not** widened to
carry an output. S4-R-KERNEL measured that the caller already holds the
`journaled` row, so no signature change is required and the second channel is
avoided.

### RPL-5 — An address namespace is not semantic policy

The `"scripted."` prefix MUST NOT change replay or recovery semantics. This
holds for future frontends — a remote worker, an MCP surface, a Jenkins agent.
Four frontends must not become four kernels.

### D1 — The normative replay table

Frozen order per invocation: **divergence → recovery → replay**. Authority:
`DurableInvocationResolver` + `EffectReplayPolicy`. The table is normative and
overrides any enum name.

Writing this table surfaced a second contradiction in the same authority. The
implemented precedence is **`RERUN` → `NEVER` → `ABORTS_PIPELINE`**: both policy
branches return before the effects are consulted. The decision matrix published
on `EffectReplayPolicy` states `any | ABORTS_PIPELINE | any | any | ABORT`,
which the code does not implement. A Step declaring `RERUN + ABORTS_PIPELINE`
against a `SUCCEEDED` row is **skipped**, not aborted. No current Step triggers
it — `CoreErrorStep` is `NEVER + ABORTS_PIPELINE`, and the `NEVER` branch
happens to abort for the same reason — so only an external plugin reaches it
today.

**The first amendment to this ADR narrowed that finding, and it matters.**
`ABORTS_PIPELINE` does not mean "abort the decision"; it means *this Step, when
executed, aborts the pipeline*. Making it outrank every branch, including fresh
execution, would stop `core.error` from ever running, and a Step that never runs
never aborts. So containment is **not** a decision that precedes admission.

The replay layer therefore distinguishes two states, and the distinction is part
of the contract:

```text
hasJournalEntry = false
    the replay layer MUST NOT suppress the legitimate first execution.
    ABORTS_PIPELINE alone does not suppress it: the abort is the effect
    being executed, so refusing to execute would silently drop it.

hasJournalEntry = true + ABORTS_PIPELINE
    the layer MUST NOT serve an aborting effect from cache, nor re-run it
    under a weaker policy branch. Fail closed.
```

The precedence is therefore **`fresh` first**, then containment, then the policy
branches. Note that hoisting the fresh check to the front changes no decision
the code currently makes — `MEMOIZED`, `RERUN` and `NEVER` all already execute
on a fresh invocation — so the reordering carries no compatibility consequence,
and the fingerprint is unaffected because it encodes the *declared* policy, not
the decided one.

| # | condition | decision |
|---|---|---|
| 1 | no journal entry (first execution) | `EXECUTE` |
| 2 | journalled **and** `ABORTS_PIPELINE` in effects | `ABORT` |
| 3 | journalled and `replayPolicy = NEVER` | `ABORT` |
| 4 | journalled, `RERUN`, outcome `SUCCEEDED` | `SKIP` |
| 5 | journalled, `RERUN`, outcome not `SUCCEEDED` | `EXECUTE` |
| 6 | journalled, `MEMOIZED`, effects purely `READ_ONLY`, outcome `SUCCEEDED` | `SKIP` |
| 7 | journalled, `MEMOIZED`, effects purely `READ_ONLY`, outcome not `SUCCEEDED` | `EXECUTE` |
| 8 | journalled, `MEMOIZED`, effects include `EXECUTES_SUBPROCESS` or `WRITES_WORKSPACE` | `EXECUTE` |

A mixed effect set is **not** memoisable: only a set that is purely `READ_ONLY`
may `SKIP`. The descriptor's `effects: List<Effect>` makes mixed sets
representable, so a Step declaring `READ_ONLY + EXECUTES_SUBPROCESS` executes.

**Row 2 is the only behaviour that changes**, and before it changes it must be
witnessed against three cases, so a fix cannot trade one defect for another:

```text
fresh core.error                 → handler MUST run (row 1)
existing core.error              → ABORT  (row 2, by NEVER as well as by effect)
external fixture RERUN + ABORTS_PIPELINE + SUCCEEDED → ABORT (row 2, the defect)
```

### D2 — The misleading name is fixed by contract and test; the rename is a separate durable epoch

**D2a — semantics, in force now.** The table in D1 is normative; the enum name
is not. `ReplayPolicy.RERUN` means "reuse a journalled `SUCCEEDED` result",
which is the opposite of what its name and documentation claim. Given that this
repository has already had four honoured-looking declarations that were not, and
that this one survived because no test exercised it, the name must stop
misleading. It is **not** renamed here, and a fitness test pins the whole D1
table against `EffectReplayPolicy.decide` so the meaning cannot drift back
silently. The name is documentation of the table; the table is the authority.

**D2b — the rename, deferred to an explicit durable compatibility epoch.**
`ReplayPolicy.RERUN` → `REUSE_ON_SUCCESS` is **not** a Kotlin API rename, and the
first draft of this ADR treated it as one. It is a change to the durable
protocol, because the policy is a field of the fingerprint payload:

```text
FingerprintPayload(stepId, params, runId, attempt, replayPolicy)
        → JSON.encodeToString(…) → SHA-256
```

kotlinx.serialization writes an enum **by name**, so the string `RERUN` sits
inside every hash. Renaming it changes the fingerprint of every operation that
declares it — `core.sh`, the retry and `waitUntil` related rows, the SDK `sh`,
any external plugin that declares it — across **both** the canonical and the
scripted histories, not only the scripted one this ADR is about.

That is a global durable migration, not a side effect of unifying replay, and
mixing the two would convert a well-founded local correction into a protocol
migration. It is deferred to its own decision, at a declared compatibility epoch,
with its own gate. It may happen before v0.47.0, but only if that cut is made
consciously rather than inherited.

### D3 — `RunPolicy` is a different axis

`DurableRunPolicy` (`ReusePriorRun`, `ResumePriorRun`, `StartFreshRun`) selects
run identity. It MUST NOT be inferred from `ReplayPolicy`, and `ReplayPolicy`
MUST NOT select run identity. `--rerun` produces a new run id through
`StartFreshRun`; it does not reinterpret prior history.

### D4 — Scripted keeps its address, loses its protocol

`ScriptedRegistryInvoker` may, after this change: obtain the `journaled`
`DurableOperation` the canonical seam needs, read `OperationInput`, know
`ScriptedCallSiteId`, `ScriptedScopeIdentity`, `dynamicScopePath`,
`invocationOrdinal`, its namespace and `StepKey`, and adapt typed input and
output.

It may **not**: interpret `OperationStatus` into a reuse/reject decision, carry
a `ReplayPolicy` literal, select recovery, or hold its own replay table.

A source-level guard that forbids `journal.get` in that class would be a
**design error**: the frontend legitimately obtains durable facts in its own
namespace. What is forbidden is interpreting the durable protocol.

### D5 — The output channel is restored, and it is not a scripted concession

`DurableStepExecutor.executeAndJournal` currently persists the `encodedOutput`
and then returns `StepOutcome`, discarding it. It returns `CommonExecutionResult`
instead, which already exists and already exists precisely so a consumer does
not need a second channel.

This is not a concession to scripted: `ParallelStageEngine` hand-decodes JSON
today because the canonical path has no typed channel either. The narrowing
penalises both frontends. The canonical caller reads `.outcome` and is otherwise
unchanged.

### D6 — Compatibility: fail closed, never migrate silently

The scripted fingerprint changes when it stops using the `MEMOIZED` literal.
Existing `scripted.*` rows therefore diverge and the existing divergence gate
already returns `FailureKind.REPLAY_COMPATIBILITY` for them. That is the desired
outcome: an explicit incompatibility rather than a silent re-execution of effects
believed to be fresh.

No row is rewritten, rehashed or migrated. `ScriptedArtifactIdentity.runtimeCompatibilityVersion`
is bumped (`r3-runtime-v1` → the next value) because that is the dimension which
already exists to declare a durable model change; no parallel schema version is
introduced.

## Addendum to ADR-0093

ADR-0093 states that SPIKE-016 proved loops and that this is *"proven
infrastructure, not a bet"*. SPIKE-016 itself records that compiler-derived
call-site IDs and durable journal storage were *"not properties established by
this spike"*, and S4-IDENTITY I1 showed its harness was a test-only reimplementation
of the runtime, not production. The claim overstates what was established. This
addendum corrects it without rewriting the original:

> SPIKE-016 proved the **feasibility** of deterministic script replay with loops
> in its own harness. It did **not** establish the production authority for loop
> iteration identity. Production identity is governed by ADR-S4-R2.

## Consequences

- The scripted frontend keeps its identity space and loses its durable protocol.
- `ParallelStageEngine` and the canonical caller gain a typed output channel they
  do not have today.
- Prior scripted history fails explicitly instead of being reinterpreted.
- `ReplayPolicy.RERUN` stops being undocumented truth and becomes documented
  contract, with its decision column pinned by a test rather than by a comment.
  The rename is deferred to a future SDK deprecation cycle.
- Dynamic iteration identity is explicitly **out of scope** here and remains
  governed by ADR-S4-R2, which may only be written after ADR-0093's overstatement
  is corrected and the replay semantics above are in force.

## Out of scope

Loop iteration identity; `nextOrdinal`; `dynamicScopePath` composition; the
`while` and braceless-`for` laws; `PLUGIN_LOCK_DIGEST`; the `Unstable` outcome
carrier. Each has its own owner and none of them may be settled by implementing
this ADR.

## Acceptance

Accepted 2026-10-03 by the product owner **with two amendments**:

1. **D1 refined before implementation.** The first draft of this table put
   `ABORTS_PIPELINE` above every branch, including a fresh first execution. That
   is wrong: the effect means *this Step, when executed, aborts the pipeline*, so
   refusing to execute it would drop the abort silently. Containment is now
   scoped to journalled history, and the three witnesses in D1 must be witnessed
   before the precedence changes.
2. **D2 split.** Semantics are fixed now by contract and test (D2a). The rename
   is a separate durable compatibility epoch (D2b) because the enum name is
   inside the fingerprint hash, so renaming it would migrate every operation that
   declares it, on both the canonical and the scripted history.

## Acceptance gate

Implementation must not be certified until the S4-R-POL differential passes for
every row of D1 on both surfaces, and until the D1 table is pinned by a fitness
test against `EffectReplayPolicy.decide`.
