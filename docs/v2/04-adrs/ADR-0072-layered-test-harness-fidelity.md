---
type: adr
id: ADR-0072
title: "Layered test-harness fidelity (HF0..HF6)"
status: proposed
date: 2026-09-08
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0048  # sandbox-profile-local → HF4/HF5
  - ADR-0053  # smoke-e2e-sandbox → HF5/HF6
  - ADR-0074  # certification runs against a chosen HF level
  - openspec/specs/pipeline-test-rule  # the in-process harness → HF1
---

# ADR-0072 — Layered test-harness fidelity (HF0..HF6)

> Reconciled from the reference package `ADR-LFC-021` (input only, not authoritative).
> Resolves the naming collision: the package called these levels `T0..T6`; they are **HF**
> (Harness Fidelity) so they do not collide with the canonical LFC-2 item list `T0..T4`, which is
> not renamed.

## Context

In-process tests are fast but can hide real errors in classpath, ServiceLoader/plugin discovery,
process/signals, restart and sandbox. Running everything in containers is too expensive. We need an
explicit fidelity ladder and a rule to pick the minimum faithful level.

## Decision

`pipeline-testkit` defines these levels:

| HF | Name | Boundary | Canonical anchor | Typical purpose |
|----|------|----------|------------------|-----------------|
| HF0 | Pure Contract | no process | — | ADTs, codecs, validation |
| HF1 | In-Process | in-process | `openspec/specs/pipeline-test-rule` | DSL/IR/handler integration |
| HF2 | Forked Real Distribution | forked distribution | CLI / distribution | CLI, classpath, plugin loading |
| HF3 | Restart/Resume | kill/restart | durable journal/resume (ADR-0040/0029) | journal, replay, resume |
| HF4 | Rootless Sandbox | Podman/hardened | ADR-0048 | isolation / security |
| HF5 | Service Sandbox | HF4 + services | ADR-0048/0053 | Git/HTTP/DB/artifacts isolated |
| HF6 | Online Smoke | OSS / network | ADR-0053 | ecosystem compatibility |

Rule: use the minimum level that faithfully proves the property under test.

## Isolation

Each execution receives a unique workspace, control root, output/event/journal stores, credential
store, plugin dir, run ID and process group. Teardown kills descendants and preserves diagnostics
before cleanup.

## Determinism

Clock, IDs, randomness and failure injection may be substituted at HF0/HF1 when real time is not the
object of the test.

## Consequences

- `pipeline-testkit` exposes `PipelineExtension` (HF1), `RealPipelineExtension` (HF2),
  `PipelineSessionExtension` (HF3), `SandboxPipelineExtension` (HF4/HF5), plus
  `StepContractSuite`/`PluginContractSuite` and workspace/process/git/credentials/plugin fixtures and
  failure injection.
- Sandbox authorities (ADR-0048/0053) are tagged with their HF levels; there is one harness-fidelity
  taxonomy.
- The canonical LFC-2 `T0..T4` items are untouched.
