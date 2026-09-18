---
type: adr
id: ADR-0091
title: "Main is implementation authority; historical branches contribute evidence, not merge pressure"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0082
  - AGENTS.md
---

# ADR-0091 — Trunk authority and historical branches

## Context

Several branches contain experiments, WIP, alternative body/control implementations and documentation. Integrating them wholesale would reintroduce architecture that may predate the Step Constitution, BodyInvoker and LPR decisions.

## Decision

`main` is the only implementation authority.

A historical branch may contribute:

- a reproducible bug;
- an invariant;
- a test/fixture/UAT scenario;
- benchmark data;
- a still-valid design idea.

It does not gain integration priority because work is already implemented.

Any code touching architecture firewalls (`Main`, compiler, canonical coordinator, domain execution algebra) is re-derived on current `main` unless a focused review proves it already satisfies current ADRs and fitness gates.

Docs-only commits are not automatically safe: old documentation can encode obsolete architecture.

## Consequences

- no blind rebase/merge of convergent branches;
- no mass docs cherry-pick;
- stashes such as waitUntil WIP are inputs to a new bounded change, not assumed next tasks;
- it is acceptable to discard substantial historical implementation when reimplementation produces a smaller coherent architecture.
