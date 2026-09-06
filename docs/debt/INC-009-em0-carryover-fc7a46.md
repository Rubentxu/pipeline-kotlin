# INC-009 — em-0 carry-over ShExecution.executeNonDurableInvocation long-method

| Field | Value |
|---|---|
| destination | `docs/debt/INC-009-em0-carryover-fc7a46.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `bf10b67522c25ac03961885ec7d3a68ebd1846547805063c880d43032566ee8e` |
| cluster_id | `CL-02` |
| severity | `low` |
| priority | `P3` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "ShExecution.executeNonDurableInvocation is 97 LOC bundling 4 concerns — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

ShExecution.executeNonDurableInvocation is 97 LOC bundling 4 concerns.
`executeNonDurableInvocation` (the non-durable fallback path) is 97 LOC and
bundles (1) control-dir creation with failure mapping, (2)
ProcessDurableTaskRuntime call, (3) stdout/stderr accumulation, (4)
terminal-classification mapping back to ShellInvocationResult. Lower priority
than dispatch/executeCore.
Locations: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt:335`.

Impact: Lower priority because the method is a fallback path that is only
exercised on non-Linux platforms or when controlDirRoot is null. Not a
refactor blocker; extract when next touched.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| source | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt:335-431` | Round-2 audit baseline: executeNonDurableInvocation spans lines 335-431 (97 LOC, unchanged). 4 distinct concerns visible: temp-dir creation (349-355), runtime call (385-393), output accumulation (376-408), terminal mapping (410-430). | n/a |

## Remediation

target=backlog -- Extract private fn
`classifyRuntimeResult(result, controlDir, opId, accumulatedOutput): ShellInvocationResult`
from executeNonDurableInvocation. Lower-priority cleanup; defer if more
pressing debt exists.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog.
Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-FC7A46`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-007 (dispatch god-method), INC-008 (executeCore god-method)
