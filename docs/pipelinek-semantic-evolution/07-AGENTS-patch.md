# Proposed AGENTS.md Additions

Merge this material into the existing V2 rules; do not create a second AGENTS authority.

## DSL SEMANTIC CONSTITUTION (MANDATORY)

Every public DSL construct MUST be classified exactly once as one of:

- DECLARATIVE_DIRECTIVE
- ATOMIC_STEP
- BLOCK_STEP
- PURE_BUILDER
- SCRIPTED_RUNTIME_CALL

The classification MUST appear in the machine-readable DSL Surface Manifest and be protected by fitness tests.

### Semantic Conservation Law

A DSL construct is valid only if one of the following is true:

1. its user intent has an explicit typed carrier in IR/metadata/runtime invocation;
2. it is a pure desugar to another supported carrier with semantic equivalence tests;
3. it fails closed before any effect.

Forbidden:

- semantic drop;
- flatten-with-loss;
- silent no-op;
- default success;
- dead semantic parameter;
- metadata accepted but never interpreted;
- retroactive mutation that silently does nothing;
- tests that claim semantic compatibility from compilation alone.

### Pure builders

Configuration builders MUST be referentially transparent with respect to the execution plan: calling a builder cannot append a Step, emit an event, acquire a capability or perform I/O.

`scmGit(...)` is a reference pattern: build `CheckoutSpec`; `checkout(...)` owns the effect.

### Directives are not Steps

`when`, `post`, `agent`, declarative `environment`, `options` and future stage/pipeline orchestration constructs MUST use the Directive model. They MUST NOT be encoded as fake Steps or flattened bodies.

The engine may switch only over the closed `DirectiveExecutionPolicy` ADT, never a concrete `DirectiveKey`.

### Block Steps

Block semantics remain owned by `StepBody` + `BodyExecutionPolicy` + `BodyInvoker`/`BranchInvoker`. Never create a bespoke block dispatcher for a new Step.

### Runtime-returning DSL

A runtime-returning function MUST return the real typed value through the durable scripted seam. It MUST NOT read host global state in the DSL, fabricate placeholders, or lower to an eager value-less Step while pretending to return a value.

### Compiler/lowering law

Compiler/PSI/KSP layers may provide parsing, source identity, metadata generation and typed lowering. They MUST NOT contain concrete Step/directive semantics or a `when(key)` semantic dispatcher.

Any source transformation mismatch MUST fail compilation; never skip a rewrite and continue with altered semantics.

## EVENTS & REACTIVITY (MANDATORY)

1. Events are semantic API, not logs.
2. Every supported Step/directive declares its observable event contract.
3. Exactly one layer owns each event family's emission.
4. Event payloads are typed/versioned and registered through an open EventRegistry.
5. Events MUST NOT contain credential material.
6. External observers cannot change run outcome.
7. Control-affecting reactions must be explicit durable reactors/directives producing typed commands.
8. External delivery is at-least-once with idempotent reaction identity; never claim exactly-once delivery.
9. Replay tests must prove no forbidden duplicate semantic events/effects.

## EXTERNAL LIBRARY CONSTITUTION

An external library MAY contribute Steps, directives and events only through public registries/contributor interfaces.

It MUST NOT:

- require a core StepKey/directiveKey branch;
- import coordinator internals;
- access journal/event-store implementation directly;
- create global state;
- bypass capability admission;
- introduce a new body/directive structural shape without a core ADR/version bump;
- register an event without schema/version and emission authority.

A new external construct using existing shapes must require zero semantic changes to core.

## CERTIFICATION LAW

A construct is `CERTIFIED` only when evidence bound to the exact candidate proves:

- typed carrier;
- semantic discriminant;
- negative fail-closed behavior;
- typed outcome;
- event contract;
- replay/resume;
- installed distribution behavior.

Compilation or an old receipt is insufficient.
