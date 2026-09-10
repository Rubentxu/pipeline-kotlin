

## Active Change — RETRY-D durable retry reconciliation (2026-09-10, HEAD `e4cca233`)

**Status: DESIGN GATE / NO PRODUCTION EDITS.** The public installed-distribution retry acceptance previously reproduced a real defect: `fail → success → rerun` with the same DB/control state appended a second `retry-ok`. Timeout and parallel installed UATs remain green, but E-EM-11 must remain OPEN.

**Grounded SUT:** `CanonicalDurableRunCoordinator.dispatchBody` derives deterministic child identities using `parentBodyPath + BlockSegment(attempt, "retry-attempt") + child segment`, then calls canonical `dispatch()`. It has no retry-control journal fact and starts at attempt 1 on each fresh coordinator invocation. `OperationJournal` has `beginOperation`, `append`, and exact `get(opId, attempt)`, but no transaction or fault seam.

**Design artifacts, uncommitted:** `openspec/changes/retry-d-durable-reconciliation/{proposal,design,tasks}.md` and proposed `docs/v2/04-adrs/ADR-0075-retry-control-durable-reconciliation.md`. They require a retry control row keyed by the full inherited canonical OpId plus journal attempt ordinal, a pure reconciliation ADT, canonical child re-entry only, and an R5 test-only `OperationJournal` decorator that throws after delegating the exact child terminal append. No production fault port and no events as durable authority.

**Verification executed:** docs-only `git diff --check` PASS. No Gradle run was relevant because production/test sources are unchanged.

**Next after explicit design/ADR approval:** implement only the typed retry reconciliation, then progressively run R1/R3-R6 focused tests and the real `installDist` R2 acceptance. Do not add RetryAttemptFinished/TimeoutScheduled, change timeout/parallel, reopen grammar UATs, alter `StepExecutors.kt`, or commit before R2, R5, and R6 are green.
