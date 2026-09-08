# Spec: Pipeline Test Harness (HF0..HF6)

Authority: ADR-0072 (layered test-harness fidelity). Reconciled from the reference package
`SPEC-LFC-018` (input only). Detail/design: `openspec/changes/lfc2-step-constitution-plugin-seam/`.

## Purpose

Expose `pipeline-testkit` harness capabilities at explicit fidelity levels so tests pick the
minimum faithful level. HF naming avoids collision with the canonical LFC-2 `T0..T4` items (which
are not renamed).

## R1 — Fidelity ladder

| HF | Name | Boundary | Typical purpose |
|----|------|----------|-----------------|
| HF0 | Pure Contract | no process | ADTs, codecs, validation |
| HF1 | In-Process | in-process | DSL/IR/handler integration (`pipeline-test-rule`) |
| HF2 | Forked Real Distribution | forked distribution | CLI, classpath, plugin loading |
| HF3 | Restart/Resume | kill/restart | journal, replay, resume |
| HF4 | Rootless Sandbox | Podman/hardened | isolation / security (ADR-0048) |
| HF5 | Service Sandbox | HF4 + services | Git/HTTP/DB/artifacts isolated |
| HF6 | Online Smoke | OSS / network | ecosystem compatibility (ADR-0053) |

## R2 — Capabilities

`PipelineExtension` (HF1), `RealPipelineExtension` (HF2), `PipelineSessionExtension` (HF3),
`SandboxPipelineExtension` (HF4/HF5); plus `StepContractSuite`/`PluginContractSuite` and
workspace/process/git/credentials/plugin fixtures and failure injection.

## R3 — Isolation

Each run gets a unique workspace, control root, output/event/journal stores, credential store,
plugin dir, run ID and process group. Teardown kills descendants and preserves diagnostics.

## R4 — Determinism

Clock, IDs, randomness and failure injection are substitutable at HF0/HF1 when real time is not the
object.

## Acceptance

- A property testable at a lower HF must not be escalated to a container.
- StepContractSuite passes for `echo`, `sh` and the external reference plugin at the minimum
  faithful level.
