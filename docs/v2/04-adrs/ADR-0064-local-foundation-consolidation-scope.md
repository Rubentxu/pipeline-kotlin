---
type: adr
id: ADR-0064
title: "V2 local-first is the active product line"
status: accepted
date: 2026-09-03
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0046
  - docs/v2/00-governance/DOCUMENT_AUTHORITY.md
  - docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md
  - docs/pipeline-kotlin-local-foundation-consolidation/docs/v2/04-adrs/ADR-LFC-001-v2-local-first-is-the-active-product.md
---

# ADR-0064 — V2 local-first is the active product line

> **Former ID:** ADR-LFC-001

## Context

The repository contains useful V2 local-runtime work, a prior local-ecosystem
reprioritization (ADR-0046), V1 material, and remote/controller planning. Their
coexistence makes it unclear which work is active and enables implementation
against a speculative distributed future.

## Decision

V2 local-first CI/CD is the sole active product critical path until the Local
Foundation Consolidation milestones and their 1.0 gate close. The active
sequence is LFC-0 through LFC-10.

Protocol/controller work, Jenkins runtime integration, remote scheduling,
leases, heartbeats, and remote command streams are deferred. They must not
shape the local hot path or remain in the active build when unconsumed. V1 is
legacy/history, not a parallel repair track. V2 must not depend on
`:pipeline-steps-system:compiler-plugin`.

Each implementation change must trace to an LFC milestone, ordered backlog
item, exit criterion, gate owner, and UAT. New functionality outside that
closure requires an accepted ADR and a new milestone.

## Consequences

ADR-0046 remains accepted for its durable local-process decisions. Its former
M4/ML scheduling is historical evidence where it conflicts with the LFC
sequence. Existing roadmaps and exit receipts remain preserved rather than
rewritten; document precedence is defined in `DOCUMENT_AUTHORITY.md`.

The LFC programme starts with repository truth and scope freeze. No major
refactor may start before its gate is fully specified and verified.

## Gate status

`LFC0-001` is accepted by this ADR and the document-authority record. LFC-0 is
still open. In particular, `UAT-GOV-003` and `UAT-GOV-004` are referenced by
the roadmap but absent from the UAT catalogue, so the governance gate is not
yet fully testable.
