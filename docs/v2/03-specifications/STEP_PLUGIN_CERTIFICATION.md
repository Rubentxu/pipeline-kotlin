# Spec: Step & Plugin Certification

Authority: ADR-0074 (a Step is done only when CERTIFIED). Reconciled from the reference package
`SPEC-LFC-019` (input only). Detail/design: `openspec/changes/lfc2-step-constitution-plugin-seam/`.

## Purpose

Provide one common Definition-of-Done suite for core and external Steps so a Step is CERTIFIED only
with evidence of behavior, not presence of code.

## R1 — States

`DESIGNED` → `IMPLEMENTED_UNCERTIFIED` → `CERTIFIED`, with orthogonal `QUARANTINED` / `RETIRED`.
`DONE/PASS` is never used for an uncertified Step.

## R2 — Certification dimensions (C01..C19, shared)

contract/descriptor, input codec, output codec, positive DSL compile, negative DSL compile, canonical
IR, registry resolution, capability admission, handler success, typed failure, observability,
cancellation, replay, body contract, credentials/security, real distribution, Jenkins
compatibility, executable scenario.

## R3 — Suites

- `StepContractSuite` — one Step family across the applicable dimensions at the chosen HF level.
- `PluginContractSuite` — an external plugin passes the same dimensions with zero core changes.

## R4 — Honesty

Quarantining or disabling a mandatory UAT is not completion; LFC-2 stays OPEN while any mandatory
UAT is disabled/quarantined.

## Acceptance

`echo`, `sh` and an external reference plugin are CERTIFIED through the common suite; no Step is
reported done without it.
