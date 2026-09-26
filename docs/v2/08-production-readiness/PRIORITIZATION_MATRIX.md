# Prioritization Matrix

Scales:
- Impact: 1 low, 5 critical.
- Effort: XS <= 4h, S = 4–8h, M = 1–2d, L = 2–4d, XL > 4d.
- Priority: P0 immediate gate, P1 high, P2 medium, P3 opportunistic.

| Hallazgo | Impacto | Esfuerzo | Prioridad | Fase |
|---|---:|---:|---:|---|
| Current-state documents drift from `main` | 5 | M | P0 | 1 |
| `SESSION_POINTER` may resume from stale SHA/state | 5 | S | P0 | 1 |
| UAT matrix/current-state contradictions | 5 | M | P0 | 1 |
| 25 open PRs with overlapping WUs | 4 | M | P0 | 1 |
| 14 Dependabot PRs / dependency drift | 3 | L | P1 | 1 |
| Branch protection has no required checks | 5 | M | P0 | 1 |
| Tech-debt ledger contains stale resolved items | 3 | S | P2 | 1 |
| Full-suite evidence reports 41 failures | 5 | L | P0 | 2 |
| HAR-007 failure semantics gap | 5 | L | P0 | 2 |
| Candidate SHA is not frozen as the single admission subject | 5 | M | P0 | 2 |
| HEAD lacks fresh CI/admission evidence | 5 | M | P0 | 2 |
| Coverage evidence is historical, not candidate-current | 4 | M | P1 | 2 |
| SAST/secrets/SBOM/SCA need candidate-current evidence | 4 | M | P1 | 2 |
| Mutation scores/triage are historical | 3 | M | P2 | 2 |
| Performance RSS ~11 GB without SLO | 4 | M | P1 | 2 |
| Final RP-5 gate not accredited | 5 | M | P0 | 2 |
| `CanonicalDurableRunCoordinator` = 1857 LOC / 28 functions | 5 | XL | P1 | 3 |
| Compatibility seams remain distributed in coordinator construction | 4 | L | P1 | 3 |
| Semantic cherry-pick incompatibility already observed | 4 | M | P1 | 3 |
| POSIX permission constants duplicated | 1 | XS | P3 | 3 |
| Coverage signal should be risk-weighted, not global-only | 3 | M | P2 | 3 |

## Classification

### Quick wins

- PR-001 current-state generator
- PR-002 session pointer reduction
- PR-003 UAT current view reconciliation
- PR-004 PR supersession/closure pass
- PR-007 debt ledger reconciliation
- PR-021 POSIX constants cleanup

### Critical

- PR-006 release admission required check
- PR-008 41-failure classification
- PR-009 HAR-007 closure
- PR-010 candidate SHA freeze
- PR-011 fresh full candidate gate
- PR-012 candidate security/supply-chain recertification
- PR-013 candidate coverage recertification
- PR-015 memory SLO
- PR-016 final RP-5 admission receipt

### Strategic

- PR-005 dependency batch reconciliation
- PR-014 targeted mutation recertification
- PR-017 lifecycle extraction
- PR-018 body engine extraction
- PR-019 recovery/invocation deep boundaries
- PR-020 coordinator collapse + semantic integration gate
