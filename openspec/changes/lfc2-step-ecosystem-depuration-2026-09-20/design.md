# Design: lfc2-step-ecosystem-depuration-2026-09-20

This change is documentation-only; no design decisions for new code are
introduced. The design notes below capture the rationale for the
depurated list and how it interacts with existing artifacts.

## 1. Why this is a spec-only change

The user explicitly requested:

> "registralo todo en los documentos necesarios para que en las proximas
>  iteraciones y ciclos implementemos solo los steps que has propuesto"

Translation: register the depurated list in the canonical documents so
future cycles implement only the proposed Steps. No production code
change. No new code path.

This change touches only:

- `openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/*` (new)
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` (update)
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (regenerate machine-derived)
- `docs/v2/01-product/STEP_REGISTRY_PLAN.md` (new)
- `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md` (update)
- `docs/v2/07-uat/WU_LPR_082_*` (new receipt)

No `v2/**` source files.

## 2. Anchoring to existing artifacts

| Artifact | Role | Action |
|----------|------|--------|
| `STEP_PLUGIN_CERTIFICATION.md` | certification law (R1..R4) | reference only |
| `STEP_CONSTITUTION.md` | constitutional rules | reference only |
| ADR-0070..0074 | Step Constitution + plugin seam | reference only |
| `STEP_INVENTORY_LFC2E0.md` | machine-derived source of truth | **regenerate** to 2026-09-20 |
| `STEP_ECOSYSTEM_MATRIX.md` | planning hypothesis | **correct** to depurated list |
| `LFC2_STEP_ECOSYSTEM_EXPANSION.md` | roadmap | **update** to depurated list |
| `LPR_WORK_UNITS.md` | LPR cycle ledger | reference only |
| `CoreStepRegistryFactory.kt` | registry composition | reference only (no change) |
| `CanonicalCoreStepDecoder.kt:LEGACY_PLUGIN_IDS` | legacy membership counter | reference only (currently empty) |

## 3. The depurated list as a binding plan

The list in `proposal.md` is a binding plan, not a wishlist. Concretely:

- A future LFC-2E1/2E2/.../2E10 cycle that adds a Step **not on the list**
  must justify it in an ADR first.
- A Step **on the list** must be brought to `CERTIFIED` (G8 + fitness +
  contract suite + real installDist) — not `IMPLEMENTED_UNCERTIFIED` or
  `LEGACY_IMPLEMENTED_UNCERTIFIED` — before the cycle closes.
- A Step **on the REJECTED list** must NOT be added to core. If a user
  requests it, the response is "plugin external" or "use `sh`".

## 4. Validation set per Step (binding)

Every Step on the depurated list must pass:

1. **All 19 certification dimensions C01..C19** of
   `STEP_PLUGIN_CERTIFICATION.md §R2`.
2. **All fitness tests relevant to the Step family** (named in
   `proposal.md §Strict Validation Set`).
3. **`StepContractSuite`** with at least the 16/17 standard rows.
4. **installDist + real binary + real fixture** with replay verification.
5. **Receipt** with argv, exit code, XML counters, SHA-256 fingerprint.

This validation set is the gate. No Step closes its cycle without all
five.

## 5. Tier ordering

Tier A first (the 6-7 Steps already in registry, finishing their G8).
Then Tier B (the 7 new generic Steps). Then Tier C (optionals, only on
demand). Tier D and E are excluded from the plan entirely.

Tier D's `tool`/Maven/npm/pip/dotnet/Go/docker rows are explicit
rejections with reasons captured in `STEP_ECOSYSTEM_MATRIX.md` and
`STEP_REGISTRY_PLAN.md` so that the user request "no npm/maven" is
codified and future cycles won't re-debate it.

## 6. State invariants after this change

Before:
- `STEP_ECOSYSTEM_MATRIX.md` claims 12 LEGACY keys (stale, 2026-09-11).
- `STEP_INVENTORY_LFC2E0.md` counts registry=3, legacy=12, external=1.

After:
- `STEP_ECOSYSTEM_MATRIX.md` reflects the depurated list with
  CERTIFIED counts and explicit REJECTED section.
- `STEP_INVENTORY_LFC2E0.md` counts registry=17, legacy=0, external=1,
  G8=8, G6-only=4, contract-only=3, missing=2 (machine-derived).
- `STEP_REGISTRY_PLAN.md` is the single source of truth for "what's next
  in core".

## 7. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Tier B Step (e.g. `stash`) turns out to need more than 1-2 WUs | estimate is honest but may slip; plan is non-binding on time, binding on outcome (CERTIFIED or REJECTED with reason) |
| Vendor community asks for a rejected Step (e.g. Docker) | response: SDK is public; reference plugin is `example.uppercase`; community can build `pipeline-docker` external; no core change |
| A Step reaches G8 with a fitness regression | cycle pauses; the fitness gate is more important than the cycle calendar |
| `STEP_REGISTRY_PLAN.md` becomes stale again | regenerate on each LFC-2Ex cycle; reference `STEP_INVENTORY_LFC2E0.md` machine-derived table as the canonical counter |

## 8. Out-of-scope reminders

- Plugin marketplace, signing, hot-reload, remote repository — all
  explicitly REJECTED as future scope per AGENTS.md "Explicitly out of
  scope".
- Cedar policy binding — LFC-2E3 future; data shape is already frozen
  per `STEP_ECOSYSTEM_MATRIX.md §Provider / Release / Policy-surface
  columns`.
- M4 (controller/remote) — DEFERRED until local-first freeze.
