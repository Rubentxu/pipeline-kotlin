# PipelineK — Evolution Bundle: V1 value recovery on top of V2

**Date:** 2026-10-06  
**Repository:** `Rubentxu/pipeline-kotlin`  
**Code baseline inspected:** `main @ 569a088cc76f1a826c619577eefa1475404c3fb4`  
**Revision:** Gradle-informed refinement r2; all new decisions remain Proposed.  
**Purpose:** turn the useful product ideas found in the historical V1 implementation into a V2-native evolution plan without reintroducing V1 architecture, duplicate authorities, stubs, placeholders or hidden semantics.

## Executive decision

This bundle proposes **additive evolution on V2**, not a V1 port. The durable runtime, Step registry, capability admission, output plane, event spine, plugin identity, scripting host and current semantic roadmap remain authoritative.

The programme introduces five main product capabilities:

1. **Shared Libraries 2.0** — reusable authoring code, strictly separated from runtime plugins.
2. **PipelineK Affordance API** — a HATEOAS-inspired, machine-readable CLI discovery API for humans, agents, MCP and LSP.
3. **Pre-classloading artifact admission** — static plugin/library manifests, digest/provenance and trust evidence before executable plugin code is loaded.
4. **Unified introspection, diagnostics and LSP** — StepRegistry, descriptors, provider metadata and LSP metadata projected through one read-only tooling model.
5. **Compiler execution hardening** — explicit compilation/loading/evaluation phases, one effective compiler profile, v2 content identity and measured artifact/session reuse decisions. Real memory, disk and class-loading reuse have separate gates; evaluated run state is excluded.

**Explicit non-goal:** Docker/Kubernetes agent execution is excluded from this programme.

The programme ends with a mandatory terminal milestone: **remove V1 and all repository legacy residue** after migration and certification are complete. This includes legacy source modules, legacy scripts, obsolete build wiring, stale examples and legacy documentation. Historical material that must be preserved for archaeology is exported outside the repository before deletion; Git history remains the ultimate audit trail.

## Bundle map

- `01-executive-proposal.md` — refined product proposal and scope.
- `02-current-state-and-guardrails.md` — current V2 seams and invariants that may not be duplicated.
- `03-target-architecture.md` — target architecture and dependency rules.
- `specifications/` — normative capability specifications.
- `adrs/` — architectural decisions to adopt before implementation.
- `plans/11-roadmap.md` — ordered milestones and value blocks.
- `plans/12-work-units.md` — implementation work units with exit criteria.
- `quality/13-UAT-master-plan.md` — user acceptance tests.
- `quality/14-AAT-master-plan.md` — architecture acceptance tests.
- `quality/15-risk-register.md` — risks and controls.
- `16-migration-and-compatibility.md` — compatibility and rollout strategy.
- `17-v1-retirement-and-repository-cleanup.md` — final removal plan for all V1/legacy residue.
- `18-definition-of-done.md` — programme-level completion checklist.
- `REFERENCES.md` — current repository anchors and version-pinned primary references.
- `22-gradle-refinement-review.md` — Spanish review, changes and implementation order.
- `specifications/19-script-compilation-and-evaluation.md` — phase/profile/JVM and diagnostic contracts.
- `specifications/20-compiled-artifact-cache.md` — conditional memory/persistence mechanics.
- `specifications/21-compiler-session-lifecycle.md` — optional warm-session ownership.
- `quality/gradle-traceability.md` — requirement/ADR/WU/UAT/AAT mapping.
- `quality/gradle-benchmark-protocol.md` — reproducible performance/GO protocol.
- `quality/gradle-primary-evidence.json` — inspected source identities and evidence limits.

## Governing rule

> New tooling surfaces may **observe, explain, plan or invoke** existing V2 authorities, but may not become new semantic authorities.

Concretely:

- one `StepRegistry` authority;
- one runtime capability admission path;
- one durable execution spine;
- one output plane;
- one event spine;
- one plugin/provider identity model;
- one script dependency composition plan;
- no plugin-specific branch in the coordinator;
- no parallel CLI/MCP/LSP registries;
- no persistent configuration written into user repositories by default.

## Refinement status and entry point

Start with [the refinement review](22-gradle-refinement-review.md). This revision changes specifications/planning only; it does not integrate source code, register SDDK work, certify a cache or move the active TRAIN. The historical baseline remains explicit. `docs/v2/05-roadmap/ROADMAP.md` remains the repository sequencing authority.

The canonical scripting spec already owns compile/evaluate ports; S6 owns the unified plugin manifest and frozen registries. Adopt these refinements through those authorities. M0–M10 IDs remain intact and M10 remains last. Focused compiler-warning correction can attach to authorized active work; optional cache/session slices are decided at M7.
