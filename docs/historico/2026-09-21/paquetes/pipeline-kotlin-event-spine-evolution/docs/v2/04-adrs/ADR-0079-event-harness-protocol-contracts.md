# ADR-0079: Event Harness verifies protocol grammar plus scenario contracts

Status: PROPOSED  
Date: 2026-09-10

## Context

Existing UATs repeatedly assert event sequences. Exact total ordering is brittle under parallel execution,
while exit-code-only tests miss lifecycle violations. Real examples should become executable product contracts.

## Decision

1. Event Harness defaults to POST_RUN over a complete event history.
2. A universal protocol grammar checks lifecycle invariants independent of a scenario.
3. Scenario YAML/TOML is decoded into typed `EventConstraint` ADTs.
4. Parallel/concurrent behavior is expressed as partial-order/happens-before constraints.
5. Verification failure changes `AcceptanceOutcome`, not `PipelineOutcome`.
6. Failure output is a minimal counterexample slice.
7. Real `examples/*.pipeline.kts` become final acceptance evidence for supported DSL features.
8. Follow-ups may add event↔journal consistency, replay metamorphic verification, mutation testing and contract coverage.

## Rejected

- exact full trace snapshots as the primary contract;
- a general temporal-logic language in the first implementation;
- running the verifier live when no live result is needed;
- treating compilation success as example acceptance.
