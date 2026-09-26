# Executive Summary

## Objective

Move PipelineK from the audited state to a verifiable production-ready state, then reduce the structural risk that would make the next release expensive.

## Audit baseline

- `main`: `acc903875d70f939713786d71a6331bb6ccf7dc9`
- stable release: `v0.39.0`
- latest observed prerelease: `v0.39.1-rc4`
- current product gate: **OPEN / not accredited**
- principal product blockers:
  - no fresh full admission evidence for the exact current candidate;
  - HAR-007 is not closed;
  - recent full-suite evidence reported 3187 tests / 41 pre-existing failures;
  - current-state documents drift from repository state;
  - open PR population is substantially larger than the session memo suggests;
  - `CanonicalDurableRunCoordinator` remains a structural concentration point.

## Plan size

**21 executable actions** grouped into three phases:

- Phase 1 — Quick Wins / truth restoration: 7 actions
- Phase 2 — Critical production-readiness closure: 9 actions
- Phase 3 — Strategic hardening: 5 actions

## Estimated effort

| Phase | Estimated effort |
|---|---:|
| Phase 1 | 56–78 h |
| Phase 2 | 86–130 h |
| Phase 3 | 86–112 h |
| **Total** | **228–320 h** |

This is approximately **29–40 person-days**.

## Calendar estimate

With:
- 1 senior Kotlin/runtime engineer full-time,
- 1 DevOps/release engineer 40–60%,
- 1 QA/test-infrastructure role 30–50%,

the expected elapsed timeline is **5–7 weeks**.

A production-ready candidate can be reached earlier, at the end of **Phase 2 (about 2–3 calendar weeks)** if no new P0/P1 defect is discovered. Phase 3 is deliberately non-blocking for the first production-ready gate unless a Phase 2 test reveals a coordinator defect.

## Strategic direction

Do not redesign PipelineK.

The architecture already contains the right primitives:
- typed ADTs;
- open Step registry;
- capability routing;
- durable journal and replay;
- architecture fitness;
- external release harness.

The required move is to eliminate transitional ambiguity:
- one materialized current-state view;
- one release-admission authority;
- one candidate SHA;
- one reconciled PR line;
- four deep runtime boundaries instead of a 1857-line orchestration concentration.
