# INC-004 — em-0 carry-over InterruptionKind uninstantiated variants

| Field | Value |
|---|---|
| destination | `docs/debt/INC-004-em0-carryover-141721.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `0192b757eafde8c117080241b36f9a8d98c38a15da96a81de3fb3f19d71c5bb8` |
| cluster_id | `CL-05` |
| severity | `medium` |
| priority | `P2` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "InterruptionKind persists 3 uninstantiated variants (USER_ABORT, SUPERSEDED, SHUTDOWN) — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

InterruptionKind persists 3 uninstantiated variants. InterruptionKind carries
USER_ABORT, SUPERSEDED, SHUTDOWN variants that are never instantiated in
production code. The enum is @Serializable and embedded in InterruptionRecord;
the unused variants pay serde + schema-validation cost without runtime benefit.
Locations: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/DurableTaskTerminal.kt:70`.

Impact: Wire-stable contract surface carries 3 dormant variants. Future readers
may assume a dispatch exists; the EM_DEAD_CODE_AUDIT.md (A8 keep-adapter)
acknowledges FAILED_TIMEOUT but does not enumerate the unused InterruptionKind
variants.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff in cycle em-3 (`git diff 99e9920..HEAD -- 'v2/*/src/main/**'` empty); finding unchanged since em-0 round 2 (99e9920). | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| command | n/a | em-0 round 2 baseline: only TIMEOUT and PARENT_CANCELLED are instantiated in production; USER_ABORT/SUPERSEDED/SHUTDOWN appear only in domain declaration. | n/a |

## Remediation

target=backlog -- Either (a) collapse to {TIMEOUT, PARENT_CANCELLED} and
migrate with #[Deprecated] over the planned variants, or (b) document in
ADR-0068 that USER_ABORT/SUPERSEDED/SHUTDOWN are wire-stable-only with
#[Deprecated] markers so future readers see the intent.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog
god-method extractions + dead enum variants. Not introduced by em-3; carry-
forward per cycle-7b MUST-emit-incidence rule.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3. Not in scope of
em-3's positive claim (which is contract-lock + 4 NEW SDK tests + 5 doc rows).

## Cross-references

- debt-report: `FIND-141721`
- original debt-report (em-0 round 2): `debt-report.json` sha256 `e98dd135f96748398ae84f79c59451604ea9b22937180cb067a8a74fae5bafd7` (superseded)
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- verify-report: `verify-report.md` sha256 `0bfac1040de597b77b5d64ab976a4c5f790140ce04c5870cc24dda0933e53e9d`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-005 (FailureOrigin unused variants), INC-006 (deprecated ctor)
