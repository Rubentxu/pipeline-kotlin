# INC-005 — em-0 carry-over FailureOrigin unused variants

| Field | Value |
|---|---|
| destination | `docs/debt/INC-005-em0-carryover-6615ef.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `7de48ac64311a30cbd0a6cf3882455f83212c744df8e806c19f6ab919091a298` |
| cluster_id | `CL-05` |
| severity | `low` |
| priority | `P3` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "FailureOrigin has 2 unused variants (DURABLE_TASK, UNKNOWN) — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

FailureOrigin has 2 unused variants (DURABLE_TASK, UNKNOWN).
FailureOrigin.LAUNCHER and RECONCILIATION are instantiated; DURABLE_TASK and
UNKNOWN are never constructed in production code (only declared + tested).
The enum is @Serializable.
Locations: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/DurableTaskTerminal.kt:42`.

Impact: Low; only 2 unused variants and they are not on the failure hot path.
Serde round-trip still encodes them. Classified LOW per docs/debt/SEVERITY.md
(cosmetic / structural).

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| command | n/a | em-0 round 2 baseline: production sites instantiate LAUNCHER and RECONCILIATION only; DURABLE_TASK and UNKNOWN remain declared-but-unconstructed. | n/a |

## Remediation

target=backlog -- Document intent with
`#[Deprecated(since = "1.88", note = "reserved — no current producer; collapse when wire contract allows")]`
on the two variants, OR collapse and migrate.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog.
Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-6615EF`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-004 (InterruptionKind unused variants), INC-006 (deprecated ctor)
