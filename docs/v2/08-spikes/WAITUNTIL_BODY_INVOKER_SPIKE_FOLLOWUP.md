# SPIKE FOLLOW-UP — BodyInvoker seam designed + first slice landed

- Date: 2026-09-12
- Worktree: `pipeline-bodyinvoker` (branch `cycle/lfc2-e1-bodyinvoker`)
- Extends: `docs/v2/08-spikes/WAITUNTIL_BODY_INVOKER_SPIKE.md` (sibling worktree `pipeline-waituntil`, branch `cycle/lfc2-e1-wait-until`)
- Status: WAITUNTIL_BODY_INVOKER blocker → DESIGN_PROPOSED (ADR-0081) + inner contract implemented

## What landed in this slice

1. **ADR-0081** (`docs/v2/04-adrs/ADR-0081-body-invoker-continuation-model.md`, status
   `proposed`): concretizes ADR-0073's `BodyInvoker` seam. Key decisions D1–D10:
   body re-entry ONLY via `BODY_INVOKER_CAPABILITY`; serializable `BodyRef`
   value-identity derived from the engine-owned `bodyPath` (`BlockSegment`s);
   closed `BodyOutcome` algebra (`Completed(StepOutcome)` / `Cancelled(reason)`);
   explicit immutable `BodyInvocationContext` (attempt segment + typed
   `ExecutionContextPatch`, CTX-P compliant); replay via per-attempt durable
   control rows anchored on the `BodyRef` (ADR-0075 pattern); cancellation is a
   typed outcome, never an INFRASTRUCTURE failure (ADR-0076/0068).
2. **Inner contract implemented** (`pipeline-domain`,
   `dev.rubentxu.pipeline.v2.domain.step.BodyInvoker.kt`): `BodyInvoker`,
   `BodyRef` + `BodyRefs` factories, `BodyOutcome`, `CancellationReason`,
   `BodyInvocationContext` / `AttemptSegment` / `ExecutionContextPatch`,
   `BODY_INVOKER_CAPABILITY`. NO dispatcher changes, NO waitUntil handler
   rewrite, NO legacy mutation — inner seam only, as constrained.
3. **HF0 contract tests** (`BodyInvokerSeamTest`, 8/8 green, JUnit XML
   `tests="8" failures="0" errors="0"`): ref determinism, blank rejection,
   branch/named derivation, closed outcome algebra, attempt-index invariant,
   JSON round-trip, capability token identity, suspend re-entry fold.

## Spike gap reclassification after this slice

| Spike gap | Status after ADR-0081 + slice |
|---|---|
| G1 Body shape | RESOLVED by design: waitUntil stays `OpaqueStepNode`; no ADT change. |
| G2 Condition capability | Resolved by design: separate typed capability (e.g. `conditionEvaluation`) bound at dispatch time; never serialized. ADR-0081 D2 explicitly separates BodyRef (identity) from runtime content (capability). |
| G5 Durable poll-state | RESOLVED by design (spike risk #1 answered): per-poll control rows `(attempt, lastPolledAt, conditionResult)` keyed on the deterministic BodyRef; terminal `WaitUntilCompleted` row is the memoization anchor; resume-mid-wait becomes a journal read. Not the weaker "terminal-only" anchor. |
| G3/G4/G6 | Unchanged; implementation phases W1/W3 as written in the spike, now executing against the landed port. |

## Lift reassessment for `core.waitUntil`

SMALL. The remaining work is: (W1) declare
`BODY_INVOKER`-free `conditionEvaluation` capability + transplant the
characterized poll loop from `CanonicalWaitUntilNodeDispatcher.dispatch` into
`CoreWaitUntilStep` behind capabilities (suspend delay, not `Thread.sleep`);
(W2) add the wait control-row journal mirroring `FileBasedRetryControlJournal`
with the reconcile invariant from ADR-0075; (W3) DSL honesty + standard G0..G8
burn-down. No coordinator branch on `waitUntil` anywhere; the capability bridge
binding is the only production wiring change.
