# INC-007 — em-0 carry-over CanonicalDurableRunCoordinator.dispatch god-method

| Field | Value |
|---|---|
| destination | `docs/debt/INC-007-em0-carryover-1f743f.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `d2f7d35d760c2292b974439bc48680313d274bad68ccd484d45b789587789e48` |
| cluster_id | `CL-02` |
| severity | `medium` |
| priority | `P2` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "CanonicalDurableRunCoordinator.dispatch is a 130-LOC method mixing 7 concerns — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

CanonicalDurableRunCoordinator.dispatch is a 130-LOC method mixing 7 concerns.
`dispatch(step, runId, stageName, stageIndex, stepIndex, stageShOptions): StepOutcome`
mixes (1) typed decoding, (2) scope tracking, (3) replay decision
(SKIP/ABORT/RERUN), (4) journal append/begin, (5) divergence check,
(6) boundary execution, (7) cursor advance + outcome correlation. 130 LOC,
3 nesting levels.
Locations: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt:181`.

Impact: 130-LOC method with 3 nesting levels in the try-decode arm makes the
boundary between 'decode failure' vs 'replay decision' vs 'boundary execution'
hard to unit-test in isolation. Adding a new effect type touches multiple
concerns inside one method.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| source | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt:181-310` | Round-2 audit baseline: dispatch method spans lines 181-310 (130 LOC, unchanged). The 7c2d572 refactor reduced one when-block to a companion function call (line 251) but did not extract helpers. | n/a |

## Remediation

target=backlog -- Extract 4 private helpers: `fn decodeTypedCommand`
(replaces lines 189-216), `fn applyScopeTracking` (replaces lines 219-235),
`fn decideReplay` (replaces lines 267-278), `fn recordAndReturn` (replaces
lines 298-309). dispatch becomes ~30 LOC orchestrator.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog
god-method extractions. Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-1F743F`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-008 (DurableShellExecutor.executeCore god-method), INC-009 (ShExecution.executeNonDurableInvocation)
