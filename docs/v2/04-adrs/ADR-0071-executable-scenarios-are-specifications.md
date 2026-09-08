---
type: adr
id: ADR-0071
title: "Executable scenarios are first-class product specifications"
status: proposed
date: 2026-09-08
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0070  # step seam that scenarios execute against
  - ADR-0074  # certification; a Step is done only when certified, incl. an executable scenario
  - docs/v2/06-quality/COMPATIBILITY_CORPUS.md
---

# ADR-0071 — Executable scenarios are first-class product specifications

> Reconciled from the reference package `ADR-LFC-020` (input only, not authoritative).

## Context

Examples and UAT fixtures are useful but are not expressed homogeneously in expected IR, events,
stdout/stderr, filesystem effects, replay, failures and requirements. When a test re-implements an
example in Kotlin, documentation and behavior can diverge from the file that is actually executed.

## Decision

Every non-trivial example is represented as an executable **Scenario**. The governing rule:

> The `.pipeline.kts` shown/document is exactly the file the harness executes.

`examples/`, `v2/compatibility/`, `test-fixtures/` and `plugin-fixtures/` share the same
ScenarioRunner but keep distinct responsibilities (pedagogical / Jenkins+DSL corpus / internal
adversarial / real external plugin builds). Invalid programs are first-class fixtures (illegal
receiver, runtime-valued API in declarative, invalid body shape, non-positive retry/timeout,
unknown plugin, missing capability, incompatible schema). The same `.pipeline.kts` is executed by
ScenarioRunner, TestKit, Just and CI.

Layout evolves progressively (no initial mass rename): flat layout first, then sidecar manifests,
then bundles for complex scenarios. Example directory shape:

```text
examples/20-nested-env/
  pipeline.pipeline.kts
  scenario.yaml
  expected/
    ir.json        events.json     stdout.txt
    stderr.txt     filesystem.json replay.json
```

## Consequences

- Examples move from illustrative documentation to executable product specification.
- A `.pipeline.kts` that cannot run through ScenarioRunner is incomplete as documentation.
- Positive and negative corpus become first-class gate inputs (UAT/certification).
