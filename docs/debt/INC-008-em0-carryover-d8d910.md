# INC-008 — em-0 carry-over DurableShellExecutor.executeCore god-method

| Field | Value |
|---|---|
| destination | `docs/debt/INC-008-em0-carryover-d8d910.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `8d45bb69659e44d7f345c2cbe0030380c41f7beaa409edd6a819f5b475bb2a1e` |
| cluster_id | `CL-02` |
| severity | `medium` |
| priority | `P2` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "DurableShellExecutor.executeCore is a 127-LOC method bundling launch + watchdog + classification — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

DurableShellExecutor.executeCore is a 127-LOC method bundling launch + watchdog
+ classification. `executeCore` (the canonical typed path introduced by the
em-0 cycle) is 127 LOC and bundles (1) control-dir write, (2) wrapper
construction, (3) setsid launch with cookie machinery, (4) watchdog kill path,
(5) heartbeat polling, (6) result-file polling, (7) state classification into
DurableTaskTerminal, (8) cleanup.
Locations: `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutor.kt:907`.

Impact: Long method makes the canonical typed path hard to test in isolation;
minor refactor would expose helpers (`buildWrapper`, `pollResult`,
`classifyTerminal`). The `launch()` companion method is also 140 LOC
(lines 198-337).

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| source | `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutor.kt:907-1033` | Round-2 audit baseline: executeCore spans lines 907-1033 (127 LOC, unchanged). launch() at line 198-337 (140 LOC, unchanged). Refactor (7c2d572) did not touch :pipeline-step-sdk. | n/a |

## Remediation

target=backlog -- Extract private helpers
`fn runWatchdog(timeoutMs, controlDir, cookie)` and
`fn classifyTerminal(controlDir, exitCode, timedOut, cancelled): DurableTaskTerminal`
from executeCore. The helpers are reusable by future task adapters.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog
god-method extractions. Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-D8D910`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-007 (dispatch god-method), INC-009 (executeNonDurableInvocation)
