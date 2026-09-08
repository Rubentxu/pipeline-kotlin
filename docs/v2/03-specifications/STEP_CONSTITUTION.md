# Spec: Step Constitution

Authority: ADR-0070 (closed execution structure, open Step registry). Reconciled from the reference
package `SPEC-LFC-016` (input only). Companion to `STEP_PLUGIN_SDK.md`. Detail/design:
`openspec/changes/lfc2-step-constitution-plugin-seam/`.

## Purpose

Define what constitutes a runnable, registerable Step in the open StepRegistry, and the closed
execution structure the engine interprets.

## R1 — Closed execution structure

- The executable IR uses a closed structural ADT (`ExecutionNode`, `StepBodies`) that the engine
  exhaustively matches; it never `when`s over plugin Step classes.
- Body shapes are `None` / `Single` / `Named`.

## R2 — Open Step registry

- `StepKey` resolves a `StepDefinition<I,O>` carrying `contract`, `inputCodec`, `outputCodec` and
  `handler`.
- Registration is open to external plugins; core Steps register through the same mechanism.

## R3 — Uniform execution path

Core and external Steps run through:
`canonical Invoke → StepRegistry → erased typed adapter → StepHandler → declared capabilities →
durable engine → typed StepResult + typed domain events`.

## R4 — Capability admission

A Step declares exactly the capabilities it may use. Unknown/incompatible `StepKey`, schema
mismatch and body-shape mismatch fail closed before effects on every run path.

## R5 — Forbidden shapes

- No per-Step case in a central dispatcher; no central sealed type per plugin.
- No KSP semantic `when(stepName)`.
- No `Map<String, Any?>` public Step contract.
- No privileged core path distinct from the plugin path.

## Acceptance / certification

Covered by the common suite (ADR-0074): contract, input/output codec, positive/negative DSL,
canonical IR, registry, capability admission, handler, typed failure, observability, cancellation,
replay, bodies, security, real distribution, Jenkins compatibility, executable scenario. The
extensibility proof requires `echo` + `sh` on the seam and an external plugin with zero core changes.
