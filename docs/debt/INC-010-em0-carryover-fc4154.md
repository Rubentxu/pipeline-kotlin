# INC-010 — em-0 carry-over attempt = 1 literal duplication

| Field | Value |
|---|---|
| destination | `docs/debt/INC-010-em0-carryover-fc4154.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `c7117282be3fc2d8df336c5f8a8d2952e92545ed7b6b720d8dc28cefff7ed853` |
| cluster_id | `CL-03` |
| severity | `low` |
| priority | `P3` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "attempt = 1 literal repeated 5 times in CanonicalDurableRunCoordinator.dispatch — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

attempt = 1 literal repeated 5 times in CanonicalDurableRunCoordinator.dispatch.
The literal `attempt = 1` appears 5 times inside dispatch
(lines 197, 207, 243, 260, 305). The attempt=1 is hardcoded because the
canonical coordinator always runs at attempt 1 — retry policy is a per-step
declaration (ReplayPolicy.RERUN) but attempt counter is not yet multi-valued.
A const or named parameter would document the invariant.
Locations: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt:197,207,243,260,305`.

Impact: Cosmetic / connascence of magic value. When the canonical coordinator
grows attempt>1 support, the 5 sites must be updated together. Low severity per
docs/debt/SEVERITY.md (cosmetic / structural).

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| command | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` | Round-2 audit baseline: 5 occurrences of 'attempt = 1' inside `dispatch` method (lines 197, 207, 243, 260, 305). | n/a |

## Remediation

target=backlog -- Introduce `private const val INITIAL_ATTEMPT = 1` (or
co-locate with Fingerprint.compute's attempt parameter) and replace all 5
occurrences. Optional: introduce a `private fun initialAttempt() = 1` helper
as a marker for the future 'compute attempt from policy' site.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog.
Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-FC4154`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-007 (dispatch god-method where the literals live)
