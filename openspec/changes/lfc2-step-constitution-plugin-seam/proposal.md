# Proposal: LFC-2 reconstitution — step constitution, plugin seam and certification

## Status

OPEN / design + reconciliation phase. No implementation, canonical-authority edit, unskip or
commit to production in this phase. Produces proposal + design + tasks + reconciliation matrix +
ADR disposition as the openspec review gate. Reference package (frozen, not authoritative):
`docs/pipeline-kotlin-lfc2-step-testkit-evolution/` (baseline `5af901c7`). The final authority
remains the canonical `docs/v2` ADR/spec/roadmap/backlog/UAT and the openspec changes they adopt.

## Intent

Close LFC-2 ("Honest Jenkins-like DSL with no fake runtime values") only when the Step
architecture is demonstrably **extensible**, not merely when a DSL façade returns values. A Step
is not done because a façade function, a decoder and a handler exist; it is done when its typed
contract, canonical invocation, registry resolution, declared capabilities, shared execution path,
events/replay/cancellation semantics and an executable scenario are all **CERTIFIED**.

This reconstitution pulls into the LFC-2 gate the minimum LFC-3/LFC-4/LFC-5 slices needed to prove
extensibility without migrating dozens of Steps first:

> typed DSL façade → canonical Invoke → StepRegistry → typed adapter/StepHandler → declared
> capabilities → durable engine → typed result/events

with the invariant **closed world for execution structure, open world for Steps/plugins**, and a
single execution path shared by core and external plugins.

## Why this change exists

- The current canonical registry is a **closed** concrete-hierarchy mechanism
  (`CanonicalCoreStepCommand.ALL_PLUGIN_IDS`, ADR-0069). It enforces fail-closed coverage well, but
  a third-party plugin cannot register a Step without touching core. LFC-2 honesty therefore cannot
  be proven today: extensibility is asserted, not demonstrated.
- The DSL-surface honesty gaps (seven disabled UAT methods) are tracked and quarantined by the
  `lfc-2-honest-dsl-closure` openspec change and `E-EM-11`. They are necessary but not sufficient:
  LFC-2 must not close as "honest DSL" while the runtime can only run a closed, core-authored Step
  set.
- The reference package proposes a coherent answer. This change reconciles it into canonical
  authority **without importing its literal taxonomy or creating a parallel source of truth**.

## Relationships (criterion 5)

| Authority | Relationship |
|---|---|
| `lfc-2-honest-dsl-closure` (openspec) | DSL-surface sub-track (T0..T4). This reconstitution composes with it; both must be green before LFC-2 closes. Its `design.md` slice H01 (fail-closed admission) is retained. |
| `E-EM-11` (em11-canonical-m2r1-runtime) | retry/timeout/parallel real semantics live here. This change **steers** E-EM-11 D1/D2/D3 toward BodyInvoker/BranchInvoker and composable `parallel` instead of one-off `dispatchRetryBlock`/`dispatchTimeoutBlock` dispatcher cases. |
| ADR-0069 (Step Semantics Policy, accepted) | Its fail-closed invariant is preserved; its closed-registry mechanism becomes registry-driven admission (open registry). |
| ADR-0064 / LOCAL_FOUNDATION_CONSOLIDATION | LFC-0..LFC-10 ordering + EM↔LFC-4/5/6 map remain the roadmap frame; this change only advances the minimum slices into LFC-2's gate. |
| LFC-3 Plugin API | Canonical `STEP_PLUGIN_SDK.md` spec + Epic `E8`; the external-plugin proof slice is the LFC-3 seam, advanced only far enough to prove LFC-2 extensibility. |
| LFC-4 single execution spine / LFC-5 block semantics | Canonical EM map (`LFC-4.4` body, `LFC-5.x` timeout/retry/context); BodyInvoker/BranchInvoker refines ADR-0054 and BLOCK_STEP_EXECUTION.md. |
| Plugin TestKit | Canonical in-process harness `openspec/specs/pipeline-test-rule` (becomes HF1) + sandbox ADRs (ADR-0048/0053) folded into the HF0..HF6 ladder. |

## Scope

### In scope (reconciliation + design deliverables only)
- Reconstitution matrix package → canonical authority (MERGE / AMEND / NEW / DISCARD).
- HF0..HF6 Harness Fidelity naming (resolves the `T0..T6` collision; canonical LFC-2 `T0..T4` untouched).
- ADR disposition: list of existing ADRs to AMEND + genuinely new canonical ADR-00xx candidates.
- Design: closed execution structure / open StepRegistry; single core+plugin path; executable
  scenario corpus; layered harness; BodyInvoker/BranchInvoker; composable parallel; certification
  states; execution order; dependency graph; UAT/gates.
- Task breakdown that the openspec/apply cycle will execute against canonical authority.

### Out of scope (belongs to natural milestones)
- Full plugin SDK delivery (LFC-3 beyond the proof slice).
- Full EM body/timeout/retry/parallel delivery beyond the slices E-EM-11 already owns and this
  change steers.
- Release (LFC-9), ecosystem (LFC-8).
- Editing canonical `docs/v2` ADR/spec/roadmap/AGENTS now: those are apply-phase actions taken only
  after this change is accepted.

## Definition of honest LFC-2 closure (criterion 13/18)

LFC-2 stays OPEN until both conditions hold:
1. The `lfc-2-honest-dsl-closure` gate is green (no DSL-surface obligation disabled/quarantined as
   "done").
2. This reconstitution's extensibility proof is CERTIFIED: an external reference plugin runs through
   the same execution path as `echo`/`sh` with no core edit, on a real distribution, and passes the
   common StepContractSuite.

A Step transitions DESIGNED → IMPLEMENTED_UNCERTIFIED → CERTIFIED (or QUARANTINED / RETIRED).
`DONE/PASS` is never applied to an uncertified Step.

## Success criteria (this phase)
- [ ] Reconstitution matrix + ADR disposition present and consistent with canonical authority.
- [ ] HF0..HF6 mapping documented; no canonical LFC-2 `T0..T4` renamed.
- [ ] Design covers the target architecture, BodyInvoker/BranchInvoker, composable parallel, and the
      execution order; no one-off `dispatch*Block` dispatcher collection.
- [ ] tasks.md orders work per the declared slice sequence with gates.
- [ ] No production code or canonical-authority doc edited in this phase.
