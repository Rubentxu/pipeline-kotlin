---
type: adr
id: ADR-0074
title: "A Step is done only when CERTIFIED"
status: proposed
date: 2026-09-08
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0069  # step semantics policy (Jenkins familiarity, per-step events, fail-closed)
  - ADR-0070  # open registry / single path that certification validates
  - ADR-0072  # certification runs at a chosen HF level
  - docs/v2/05-roadmap/MILESTONES.md  # Definition of Done
---

# ADR-0074 — A Step is done only when CERTIFIED

> Reconciled from the reference package `ADR-LFC-023` (input only, not authoritative).

## Context

Many regressions originate from calling a feature "implemented" when only one of its layers exists
(a façade, a descriptor, a decoder or a handler). `DONE/PASS` then records presence of code rather
than evidence of behavior.

## Decision

A Step has formal states:

- `DESIGNED`
- `IMPLEMENTED_UNCERTIFIED`
- `CERTIFIED`
- `QUARANTINED`
- `RETIRED`

`DONE/PASS` is never used for an uncertified Step. If part of a Step is incomplete, it is
`IMPLEMENTED_UNCERTIFIED` or `QUARANTINED`, never an artificial PASS.

## Certification dimensions

Applicable per Step and shared by core and external plugins (same suite):
contract/descriptor, input codec, output codec, positive DSL compile, negative DSL compile, canonical
IR, registry resolution, capability admission, handler success, typed failure, observability,
cancellation, replay, body contract, credentials/security, real distribution, Jenkins
compatibility, executable scenario.

## Consequences

- The roadmap measures evidence of behavior, not presence of code.
- A Step that is partially wired is not `DONE`; it stays `IMPLEMENTED_UNCERTIFIED` / `QUARANTINED`.
- Mandatory UAT that is disabled/quarantined keeps LFC-2 OPEN; quarantining is not completion.
