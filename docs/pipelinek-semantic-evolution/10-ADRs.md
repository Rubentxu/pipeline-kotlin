# Proposed ADR Set

These ADRs update the existing architecture. They should reference/supersede only the precise clauses they change; do not rewrite history.

## ADR-X01 — DSL Semantic Categories and Conservation Law

Decision:

Every public DSL symbol is exactly one of Directive / Atomic Step / Block Step / Pure Builder / Scripted Runtime Call. Intent must have a carrier, pure desugar, or fail closed.

Supersedes any documentation that treats mere compilation/canonicality as semantic support.

## ADR-X02 — Declarative Directive Registry

Decision:

Directives are not Steps. Use open registry + closed `DirectiveExecutionPolicy` structural shapes.

Consequences:

- external directives possible;
- no key-specific coordinator branches;
- new lifecycle shape is a core versioned evolution.

## ADR-X03 — Typed Stage Predicates

Decision:

Declarative `when` uses `StagePredicate` ADT. Arbitrary string expressions are not stable API. Runtime arbitrary Kotlin decisions belong in Scripted Runtime.

## ADR-X04 — Post as Outcome Finalization Plan

Decision:

`post` is selected from typed StageOutcome and runs bodies via BodyInvoker. It is not part of the main Step list.

## ADR-X05 — Event Registry and Reactive Boundary

Decision:

Events are typed/versioned semantic API. External observers are read-only. Control reactions are durable typed command producers with idempotent reaction identity.

## ADR-X06 — Plugin Constitution v2

Decision:

External libraries can contribute Steps, directives and events through registries. KSP may generate metadata but never semantic dispatch.

## ADR-X07 — Scripted Runtime Lowering Evolution

Decision:

Keep suspend + journal model; reject CPS. Short-term harden PSI lowering. Investigate a K2 call-site metadata injector under strict gates; adopt only if it removes textual surgery without moving semantics into compiler.

This ADR refines ADR-0093. It does not overturn the suspend decision.

## ADR-X08 — Certification Requires Semantic Witnesses

Decision:

A construct is not CERTIFIED from canonicality/compilation alone. Carrier + discriminant + negative + outcome + event + replay + installed witnesses are mandatory.
