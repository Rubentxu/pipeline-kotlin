# Spec: Executable Scenario Corpus

Authority: ADR-0071 (executable scenarios are first-class product specifications). Reconciled from
the reference package `SPEC-LFC-017` (input only). Detail/design:
`openspec/changes/lfc2-step-constitution-plugin-seam/`.

## Purpose

Every non-trivial `.pipeline.kts` shown/document is the file the harness executes, and is a
first-class product specification consumed by ScenarioRunner, TestKit, Just and CI.

## R1 — Four inventories, one runner

- `examples/` — pedagogical / executable product specs.
- `v2/compatibility/` — Jenkins/DSL positive and negative corpus.
- `test-fixtures/` — internal adversarial scenarios.
- `plugin-fixtures/` — real external plugin builds.

All share ScenarioRunner; responsibilities do not collapse.

## R2 — Negative corpus is first-class

Invalid programs are fixtures: illegal receiver, runtime-valued API in declarative, invalid body
shape, non-positive retry/timeout, unknown plugin, missing capability, incompatible schema.

## R3 — Expected artifacts

A scenario may assert IR, events, stdout/stderr, filesystem effects, replay and failures via
sidecar `expected/` artifacts and `scenario.yaml`.

## R4 — Progressive layout

No initial mass rename. ScenarioRunner supports the current flat layout first; sidecar manifests
next; bundles for complex scenarios later.

## Acceptance

- Representative `.pipeline.kts` files run unchanged through ScenarioRunner.
- Positive and negative corpus are gate inputs (UAT + certification).
