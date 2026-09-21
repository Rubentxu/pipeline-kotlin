# Roadmap Migration — existing programmes → LPR

Status: PROPOSED

## Decision summary

Do **not** delete historical roadmaps. Preserve them for rationale/receipts, but remove conflicting sequence authority.

| Existing authority | Disposition under LPR | Why |
|---|---|---|
| `ROADMAP.md` | retain as historical/top-level chronology; add LPR active-priority pointer | too much valid history to replace wholesale |
| `LOCAL_FOUNDATION_CONSOLIDATION.md` | retain; LPR is product continuation/amendment | architectural laws remain valid |
| `EXECUTION_MODEL_MIGRATION.md` | retain as evidence/remaining debt; map relevant work into LPR-2/3 | no need for parallel active sequence |
| `LFC2_HONEST_DSL_CLOSURE.md` | **ABSORBED** into LPR-3; document remains gap inventory | LPR must close supported honesty before distribution |
| `lfc2-step-constitution-plugin-seam` | retain as architectural authority; product sequencing superseded | ADR-0070..0081 remain core law |
| `LFC2_STEP_ECOSYSTEM_EXPANSION.md` | **DEFERRED AS FULL PRIORITY SEQUENCE**; E0/E1 evidence reused; E2..E10 after LPR-GATE-1 | broad plugin coverage is not release blocker |
| `lfc2-step-ecosystem-expansion` OpenSpec | pause after any already-safe active bounded work; re-open post-LPR by evidence | avoid catalog expansion before product feedback |
| `EVENT_SPINE_EVOLUTION.md` | EVT-0..3 remain CLOSED; EVT-4+ still deferred | LPR local observation is not detached relay |
| `wu-g5-restore` | evidence input; waitUntil not a Gate-1 blocker unless promoted | no forced reuse of WIP architecture |
| convergent/historical branches | reference-only per ADR-0091 | main remains authority |

## What is deprecated vs superseded

Use precise language:

- **not deprecated:** ADR-0070..0081 architectural laws;
- **not deprecated:** EVT-0..3 evidence and contracts;
- **superseded as sequence:** “complete E2..E10 before next product milestone”;
- **absorbed:** standalone Honest DSL closure sequence;
- **deferred:** EVT-4 remote relay, M4 controller, broad plugins, Kubernetes/Jenkins remote integration;
- **historical/reference-only:** branches or package snapshots not on current main.

## Mapping old B milestones

| Old B | LPR home |
|---|---|
| B0-B8 constitution/plugin seam | preserve; prerequisite evidence |
| B9 strict DSL | LPR-3 D1/D2 |
| B10 BodyInvoker/BranchInvoker | LPR-2 E1/E2 |
| B11 context blocks | LPR-2 supported Scoped certification |
| B12 retry/timeout | LPR-2 E3 |
| B13 parallel | LPR-2 E4 |
| B14 runtime values | LPR-3 D2/D3 |
| B15 typed when/post | post-Gate unless needed; must fail-closed if unsupported |
| B16 formal scripting/source fidelity | LPR-3/LPR-6 where product-impacting |
| B17 LFC-2 gate | no longer blocks first product use as a monolith; relevant laws included in LPR-GATE-1 |

## Mapping LFC-2E

- E0 inventory/certification work → LPR-0/LPR-6.
- E1 universal core burn-down → only Gate-1 supported families before release; nonessential utility/load/waitUntil rows can wait.
- E2..E10 → post-LPR ecosystem backlog, reordered by dogfooding.

## Mapping EVT

LPR does not reopen EVT-4. It adds a **local in-process observation plane** using EVT-2 read/tail primitives. A detached second process, remote relay, lag metrics across processes and broker selection remain EVT-4/5.

## Governance action

After ADR-0082 acceptance, add a status banner to old active roadmaps and OpenSpec task files pointing at `LOCAL_PRODUCTION_READY_ROADMAP.md`. Never edit closed receipts to rewrite history.
