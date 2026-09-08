---
type: adr
id: ADR-0070
title: "Closed execution structure with an open Step registry; core and external plugins share one execution path"
status: proposed
date: 2026-09-08
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0069  # step semantics policy: fail-closed coverage; mechanism becomes registry-driven admission
  - ADR-0064  # local-foundation scope; LFC-0..10 ordering
  - ADR-0066  # call-site identity determinism
  - ADR-0067  # persisted schema versioning
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
---

# ADR-0070 — Closed execution structure with an open Step registry; one execution path for core and plugins

> Reconciled from the reference package `ADR-LFC-018` + `ADR-LFC-019` (input only, not authoritative).
> Openspec change: `openspec/changes/lfc2-step-constitution-plugin-seam`.

## Context

The runtime needs two properties at once:

1. the interpreter must exhaustively know the **execution structure** (for replay, journal, events,
   cancellation, fail-closed admission);
2. the Step catalogue must stay **open** to third-party plugins (LFC-3 Plugin API).

Today fail-closed coverage is enforced through a **closed** concrete registry
(`CanonicalCoreStepCommand.ALL_PLUGIN_IDS`, ADR-0069) and a sealed core step hierarchy. That is a
correct mechanism for the invariant, but a third-party plugin cannot register a Step without
touching core, and core Steps enjoy a privileged path distinct from any generic plugin path. The
result is asserted, not demonstrated, extensibility.

## Decision

Adopt the functional invariant **closed world for interpreter structure, open world for plugin
operations**.

- The executable IR uses a **closed** structural ADT (`ExecutionNode` / `StepBodies`) that the
  engine exhaustively matches; it never `when`s over plugin Step classes.
- An **open `StepRegistry`** resolves `StepKey → StepDefinition → StepHandler`.
- Core Steps are a **standard bundled plugin set** from an execution standpoint: core and external
  plugins run through the exact same path (no privileged core, no separate generic plugin).

Conceptual shapes (names may vary; invariants do not):

```kotlin
@JvmInline value class StepKey(val value: String)

sealed interface ExecutionNode {          // CLOSED structure
  data class Invoke(id: StepId, step: StepKey, input: EncodedInput,
                    bodies: StepBodies, source: SourceLocation?) : ExecutionNode
}
sealed interface StepBodies {             // CLOSED
  data object None; data class Single(nodes); data class Named(branches)
}

interface StepDefinition<I:Any,O:Any> {   // OPEN registry payload
  val key: StepKey; val contract: StepContract
  val inputCodec: StepCodec<I>; val outputCodec: StepCodec<O>
  val handler: StepHandler<I,O>
}
```

Uniform path:

```text
typed DSL façade
 -> canonical Invoke
 -> StepRegistry
 -> erased typed adapter
 -> StepHandler<Input, Output>
 -> declared capabilities / context bridge (capability admission)
 -> durable engine (journal / events / replay / cancellation)
 -> typed StepResult + typed domain events
```

## Forbidden

- adding a case per Step to a central dispatcher;
- adding a central sealed type per plugin;
- KSP with semantic `when(stepName)`;
- a plugin requiring a change in domain/application/compiler/dispatcher;
- `Map<String, Any?>` as a public Step contract;
- a privileged core execution path distinct from the plugin path.

## Consequences

- Fail-closed coverage (ADR-0069) is preserved: unknown/incompatible `StepKey`, schema mismatch and
  body-shape mismatch are rejected before effects, enforced by registry-driven admission on every
  run path.
- A Step's declared capabilities are exactly the capabilities it may use; there is no omnipotent
  global context.
- The external-plugin proof is the acceptance gate: `echo` and `sh` run through
  `Invoke → Registry → Handler`, their concrete dispatcher cases are removed, and an independent
  plugin runs with zero core changes.

## Validation (definition of proof)

1. `echo` and `sh` use `Invoke → Registry → Handler`.
2. The central dispatcher no longer knows `echo`/`sh` concretely.
3. An external plugin executes with no core edit.
4. Unknown `StepKey` / schema mismatch fails before side effects.
