---
type: adr
id: ADR-0082
title: "Local Production Ready becomes the active product-priority lane"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0046
  - ADR-0064
  - ADR-0070
  - ADR-0074
  - docs/v2/05-roadmap/LOCAL_PRODUCTION_READY_ROADMAP.md
---

# ADR-0082 — Local Production Ready becomes the active product-priority lane

## Context

The repository has accumulated several correct but competing sequences: LFC consolidation, execution-model closure, honest DSL, Step ecosystem expansion and Event Spine. The current priority chain after EVT-3 points at completing the broad LFC-2E ecosystem before returning to other work. The nearer product goal is different: install and use `pipeline-kotlin` on real software projects as a local CI/CD tool, distribute it independently, and learn from dogfooding.

Completing the full E2..E10 plugin expansion before releasing would delay feedback while not reducing the principal product risks: execution responsibility, DSL honesty, CLI UX, observability performance, packaging and release reliability.

## Decision

Create `Local Production Ready (LPR)` as the active P0 product lane.

LPR does not supersede the architectural decisions in LFC-2/EVT. It **supersedes their sequencing as product priority** until LPR-GATE-1.

Priority becomes:

```text
architecture truth + CI baseline
  ↓
execution/body hardening
  ↓
honest supported DSL
  ↓
high-performance local observation
  ↓
local-core certification + real projects
  ↓
versioned distribution + GitHub Release
  ↓
SDKMAN + dogfooding
  ↓
LPR-GATE-1
  ↓
resume ecosystem expansion by evidence
```

## Consequences

- E2..E10 plugin expansion is deferred, not cancelled.
- EVT-4 remote relay stays deferred; local live observation is an LPR read-side feature.
- `load`, `waitUntil`, full `when/post` do not block first LPR unless promoted to the supported profile.
- Existing unsupported/fake surfaces must be rejected or marked experimental.
- Real-project use becomes a release gate earlier than broad feature parity.

## Rejected alternatives

1. Complete all LFC-2E before release — maximizes feature count before user feedback.
2. Release current CLI immediately — packages unresolved execution/DSL/observability debt as public contract.
3. Fork a new product line — duplicates authorities; LPR instead reuses V2 canonical spine.
