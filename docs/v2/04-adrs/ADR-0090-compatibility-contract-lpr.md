---
type: adr
id: ADR-0090
title: "The first LPR release freezes a bounded CLI/DSL/event compatibility contract"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0067
  - ADR-0078
  - ADR-0082
  - ADR-0088
  - ADR-0089
---

# ADR-0090 — LPR compatibility contract

## Context

Dogfooding is only useful if project pipelines do not break on every release. Conversely, freezing every experimental surface now would trap existing design debt.

## Decision

At LPR-GATE-1 freeze only the explicitly supported contract:

- `local-core-v1` DSL subset;
- CLI commands, view/format names and exit codes;
- project default `pipeline.kts` discovery;
- event envelope major version/cursor semantics already public;
- plugin SDK/version negotiation portions explicitly declared stable;
- release artifact naming/version contract.

Everything else is marked experimental/internal and may evolve with migration notes.

Maintain versioned compatibility fixtures for prior supported releases and run them through the current installed distribution when a change claims backward compatibility.

Breaking changes require explicit migration notes and versioning consistent with the project's release policy; a docs-only claim cannot satisfy compatibility.
