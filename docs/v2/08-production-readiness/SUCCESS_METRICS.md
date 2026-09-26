# Success Metrics

| Acción | KPI | Objetivo | Método de medición |
|---|---|---|---|
| PR-001 | Current-state determinism | 100% reproducible | run generator twice; identical digest |
| PR-002 | Pointer drift | 0 stale SHA references | compare pointer SHA to `git rev-parse HEAD` |
| PR-003 | UAT ambiguity | 0 UATs with multiple current states | schema/lint over current view |
| PR-004 | PR ambiguity | 0 overlapping unclassified product PRs | reconciliation table |
| PR-005 | dependency drift | all open dependency PRs dispositioned | GitHub PR query |
| PR-006 | release protection | 1 mandatory admission check | branch protection inspection |
| PR-007 | debt accuracy | 100% active items evidence-backed | debt ledger audit |
| PR-008 | full-suite unknowns | 0 unclassified failures | test-disposition report |
| PR-009 | HAR-007 | PASS | external harness scenario + local contract tests |
| PR-010 | candidate identity | exactly 1 frozen SHA/artifact tuple | manifest validation |
| PR-011 | mandatory full gate | 0 failures/errors | JUnit aggregation |
| PR-012 | security | SAST/secret/SCA/SBOM current | artifact timestamps + candidate SHA |
| PR-013 | coverage | no unexplained critical-package regression | Kover diff |
| PR-014 | mutation | 0 unexplained survivors in critical policy | PIT triage |
| PR-015 | memory | RSS <= approved SLO | reproducible perf harness |
| PR-016 | product gate | `RP-5 PRODUCT_GATE_GO` | final immutable receipt |
| PR-017 | coordinator lifecycle responsibility | lifecycle moved; behavior diff = 0 | golden tests |
| PR-018 | body semantics ownership | coordinator no longer implements body traversal | architecture test/source scan |
| PR-019 | compatibility seam concentration | compatibility adapters only at composition root | architecture fitness |
| PR-020 | coordinator depth | <600 LOC or equivalent complexity budget, <=12 constructor deps target | source metric + architecture check |
| PR-021 | permissions duplication | 0 duplicated permission literals in audited modules | source scan |

## Project-level KPIs

### Release confidence

- exact candidate full-gate PASS rate: 100%
- mandatory UAT PASS: 100%
- unresolved P0/P1 defects in supported local profile: 0
- external harness candidate mismatch: 0
- reproducibility: 2 independent builds => identical artifact digest

### Engineering throughput

- open product PRs without disposition: 0
- current-state recovery time for a new agent: <5 minutes
- full gate reserved for integration/release, not development loop
- affected-test loop median target: <5 minutes where current test topology permits

### Architecture

- no new concrete Step branching in coordinator
- no new global/service-locator state
- compatibility logic outside composition root: 0
- deep runtime boundaries: 4 maximum principal orchestration interfaces
