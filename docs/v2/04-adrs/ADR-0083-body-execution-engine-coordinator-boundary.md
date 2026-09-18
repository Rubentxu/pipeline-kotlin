---
type: adr
id: ADR-0083
title: "BodyExecutionEngine is the single interpreter of body execution policy"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0070
  - ADR-0073
  - ADR-0075
  - ADR-0076
  - ADR-0081
---

# ADR-0083 — BodyExecutionEngine is the single interpreter of body execution policy

## Context

The code already has a strong Step registry, `BodyExecutionPolicy` and durable retry/parallel reconciliation, but `CanonicalDurableRunCoordinator` still imports and coordinates too many concrete mechanisms: body policies, retry/waitUntil journals, parallel reconciliation, credential scopes, event emission, operation journal, replay and runtime context. This creates connascence-of-change and makes block/plugin extensibility harder to prove.

ADR-0073/0081 define BodyInvoker re-entry, but not the ownership boundary that interprets all body policies.

## Decision

Introduce one application-level `BodyExecutionEngine` that consumes a resolved generic plan and body references. It interprets closed execution shapes, never concrete Step keys.

Initial LPR cases:

```text
Sequential
Scoped(projection)
Retrying(policy)
Parallel(policy)
```

Add `RepeatUntil(policy)` before `waitUntil` is promoted to SUPPORTED.

The engine composes BodyInvoker/BranchInvoker and durable control/reconciliation services. The coordinator delegates body execution and does not implement policy loops once migrated.

A generic body carrier represents `None`, `Single`, `Named` around durable BodyRefs. No `RegistryFooBlockSpec` subtype per plugin.

## Coordinator fitness

After migration the coordinator MUST NOT:

- switch on concrete Step names for body semantics;
- implement retry/parallel/repeat loops directly;
- decode concrete plugin payloads;
- acquire body-specific credentials directly;
- construct StepDefinitions.

It may traverse closed structural nodes, own run/stage lifecycle and delegate.

## Incremental migration

This ADR is intentionally emergent:

1. characterize current behavior;
2. extract Sequential/Scoped;
3. migrate retry/timeout;
4. migrate parallel;
5. prove external body plugin;
6. add RepeatUntil only if/when waitUntil is promoted.

No big-bang coordinator rewrite is required.
