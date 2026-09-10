# Event Spine evolution pack disposition

**Status:** accepted disposition

## Decision

`docs/v2/` is the sole current authority for the Event Spine evolution
(EVT) and the policy guardrails learning path (POL). The former
`docs/pipeline-kotlin-event-spine-evolution/` package has been fully
absorbed into the integrated documentation tree at commit `22f199c0`
(cycle `evt-0-grounding`, B-direct, SDDK ledger seq 851) and is retained
only as a historical provenance record. It is not a parallel
specification set and cannot authorize implementation, change a gate,
or supersede `docs/v2`.

## Absorption map (pack path → integrated authority)

| Pack file | Integrated authority |
|---|---|
| `docs/v2/04-adrs/ADR-0077..0080` | `docs/v2/04-adrs/ADR-0077..0080-*.md` (PROPOSED) |
| `docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md` | `docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md` |
| `docs/v2/05-roadmap/ROADMAP_INTEGRATION.md` (merge order) | executed; see `integration/MERGE_GUIDE.md` intent below |
| `docs/v2/06-design/EVENT_SPINE_GROUNDING.md` | `docs/v2/06-design/EVENT_SPINE_GROUNDING.md` |
| `docs/v2/06-design/RESOURCE_REF_MODEL.md` | `docs/v2/06-design/RESOURCE_REF_MODEL.md` |
| `docs/v2/06-design/EVENT_HARNESS_DESIGN.md` | `docs/v2/06-design/EVENT_HARNESS_DESIGN.md` |
| `docs/v2/06-design/POLICY_GUARDRAILS_DESIGN.md` | `docs/v2/06-design/POLICY_GUARDRAILS_DESIGN.md` |
| `docs/v2/07-uat/UAT_EVT_REAL_EXAMPLES.md` | `docs/v2/07-uat/UAT_EVT_REAL_EXAMPLES.md` |
| `docs/v2/00-context/EVT_P4_EX_BASELINE.md` | `docs/v2/00-context/EVT_P4_EX_BASELINE.md` |
| `examples/contracts/*.events.yaml` | `examples/contracts/*.events.yaml` (candidate sidecars) |
| `openspec/changes/event-spine-evolution` | `openspec/changes/event-spine-evolution/` |
| `openspec/changes/policy-guardrails` | `openspec/changes/policy-guardrails/` |
| `integration/ROADMAP_INSERT.md` | applied to `docs/v2/05-roadmap/ROADMAP.md` (EVT/POL sections) |
| `integration/IMPLEMENTATION_BACKLOG_APPEND.md` | appended to `docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md` |
| `integration/MILESTONES.replacement.md` | replaced `docs/v2/05-roadmap/MILESTONES.md` |
| `integration/EXAMPLES_README_APPEND.md` | reconciled into `examples/README.md` |
| `integration/AGENTS_CANDIDATE.md` | deliberately NOT merged into AGENTS.md (merge guide step 6); promotion deferred until EVT/POL receipts prove each law |
| `integration/MERGE_GUIDE.md` | executed step-by-step; retained in git history of the pack |
| `research/GROUNDING_SOURCES.md` | provenance only; superseded by `docs/v2/00-context/EVT_P4_EX_BASELINE.md` |

All absorbed copies were verified byte-identical to their integrated
counterparts before the pack directory removal.

## Integration evidence

- Cycle: `p-733fb505b5a6bd2d/evt-0-grounding` (CLOSED, ledger seq 851)
- Receipt: `docs/v2/07-uat/EVT_0_GROUNDING_RECEIPT.md`
- Baseline: `d0ccf4b5` (CTX-P4-EX 10/10), integrated at `22f199c0`
- `main == origin/main == origin/docs/evt-0-grounding == 22f199c0`
