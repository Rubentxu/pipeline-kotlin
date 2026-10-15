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

---

## R5 — Reconciliation status (2026-10-15): the ladder is named, not yet typed

**Authority unchanged.** This section records observed state against R1–R4; it
does not ratify ADR-0072 (still `proposed`) and it does not create a second
taxonomy. It exists because B1 asked for the reconciliation and because "the
levels exist" was assumed rather than checked.

### What is observed

```text
pipeline-testkit (v2/pipeline-testkit/src/main/kotlin/.../testkit)
  HelloPipelineFixture.kt, PipelineFixture.kt, StepDescriptorAssertions.kt,
  pipelineDefinitionShould.kt
  -> NO HF0..HF6 type, annotation, registry or constant anywhere in the repo
     (`rg 'HF[0-6]' v2 --glob '*.kt'` returns nothing)

Kotlin suites are named by scenario, not by level:
  UatLocal*, UatDsl*, UatStep*, UatEvt*, UatDurable*, Lpr011*, PublishHtml*,
  StashOps*  (the UAT battery, which this repo no longer runs locally)

scripts/ harness suites (hermetic python) are executed by `just harness`;
  none of them records an HF level either.
```

### Mapping by the R1 definitions (observed correspondence, proposal only)

| HF | What exists today that satisfies the boundary | Level recorded? |
|----|-----------------------------------------------|-----------------|
| HF0 | every `scripts/test_*.py` suite (pure contract, no product process) | no |
| HF1 | `pipeline-testkit` fixtures + in-process DSL/handler tests | no |
| HF2 | Kotlin tests that fork the installed distribution (`AppBinSupport`, `Console*`, corpus) | no |
| HF3 | kill/resume characterisations (`UatDurable*`, `UatRunConcurrencyCharacterisationTest`) | no |
| HF4/HF5 | `scripts/sandbox/*.sh` + devbox/podman smoke (invoked manually, via `just`) | no |
| HF6 | the external `pipelinek-release-harness` (this repo delegates it) | n/a here |

### Divergences (each is a fact, not an opinion)

1. ADR-0072's Decision reads "`pipeline-testkit` defines these levels" — it does
   not. The ladder is a naming convention in documents and UAT names.
2. Because no level is recorded, R4's rule ("a property testable at a lower HF
   must not be escalated") cannot be enforced mechanically; only by review.
3. `ADR-0072.status = proposed`. Until it is accepted, no gate may depend on the
   taxonomy, and no code may claim conformance to it.
4. `HF6` is out of scope by construction: online smoke belongs to the external
   harness.

### Minimal options to close the gap (owner decision; none applied here)

```text
A. Accept ADR-0072 as written and implement `HarnessFidelity` (sealed ADT or
   enum with per-level metadata) in :pipeline-testkit, then tag suites. Largest
   change; makes R4 mechanical.
B. Amend ADR-0072 to describe the ladder as a REVIEW vocabulary (no type), and
   delete the claim that testkit defines the levels. Smallest honest change.
C. Keep it as documentation and never let a gate depend on HF names (status
   quo): reconciliation complete as a fact, taxonomy unenforced.
```

Until the owner picks A or B, option C is what this repository actually is, and
no receipt in `docs/v2/07-uat/` may claim HF-level conformance.
