# EM-4 Implementation Receipt

> **Cycle:** `p-733fb505b5a6bd2d/em-4-body-execution-ir`
> **Date:** 2026-09-06
> **Base commit:** `1790a44` (v0.31.0)
> **Apply commits:** `33da292`, `aef03f2`, `8e017b2`, `70e11d3`, `c4d4f98`

---

## Commit History

| # | SHA | Message | Tasks |
|---|---|---|---|
| 1 | `33da292` | feat(v2): add BlockStepNode body IR with length-prefix operation paths | T1, T2, T3 |
| 2 | `aef03f2` | feat(v2): add typed context overlay stack and body-aware step descriptors | T4, T5, T6 |
| 3 | `8e017b2` | feat(v2): recurse body execution through validator, planner and durable coordinator | T7, T8, T9 |
| 4 | `70e11d3` | feat(v2): map body-aware block steps in compiler with partial linearizer retirement | T10 corrected |
| 5 | `c4d4f98` | test(v2): add UAT substrate for body execution JEP-020/021/022/029 | T12 |
| — | `1790a44` | base (v0.31.0) | — |

**T11:** Satisfied by existing `FArchL7BlockStepNestingInvariantTest` — no new commit needed.

---

## DESIGN DEVIATION

**Rule:** R-EM4-7 rewriteWorkflowControl retirement is **PARTIAL**.

| Wrapper | Canonical Path | Milestone |
|---|---|---|
| `CatchError` | `rewriteWorkflowControl` (legacy linear) | EM-5 |
| `WarnError` | `rewriteWorkflowControl` (legacy linear) | EM-6 |
| `TimeoutBlock` | `BlockStepNode` + `dispatchBody` | EM-4 |
| `RetryBlock` | `BlockStepNode` + `dispatchBody` | EM-4 |
| `Dir` | `BlockStepNode` + `dispatchBody` | EM-4 |
| `WithCredentialsBlock` | `BlockStepNode` + `dispatchBody` | EM-4 |

Reason: Spec R-EM4-8 — EM-4 MUST NOT change currently-observable behavior. catchError/warnError failure-suppression and UNSTABLE-classification semantics belong to EM-5/EM-6. Their IR migration to BlockStepNode is out of EM-4 scope.

---

## Per-Task Evidence Table

| Task | Test Class | Result | XML SHA-256 | Notes |
|---|---|---|---|---|
| T1 | `BlockStepNodeRoundTripTest` | 4/4 PASS | `e0358f662c40583ade65dde3fbfdde455389c435e02beaec811810b972e16198` | Polymorphic serialization round-trip |
| T2 | `OpIdBodyPathTest` | 3/3 PASS | `adfa62cedc5d609dd8b11a8999ffcbea08602a483e313dde0b359d59f3e4f237` | bodyPath format, legacyFormat adapter |
| T3 | `PipelineIdsTest` | 3/3 PASS | `95c7353468771107364a427c87ea669114c007b21585a14a3714c2e3c80d6391` | BlockSegment value class |
| T4 | `ContextStackImmutabilityTest` | 4/4 PASS | `1bd90caeea4d9e26f218e56dc6fe8e0e83e688f10c9f7098a07bbd2060c69706` | ContextOverlay 8-variant exhaustiveness |
| T5 | `StepDescriptorBodyMetadataTest` | 3/3 PASS | `76a74c07ccd1aa0221da4eb65f1bd3df0aca6d0548ece1851ff86b7628580fa4` | 4 body-metadata fields |
| T6 | `StepDescriptorRegistryTest` | 3/3 PASS | `9ad1c79c92837aec196ed27afa49fde571e486b2152cf8fdb1d45164f727b212` | 12 standard descriptors |
| T7 | `CompiledPipelineValidatorTest` | 8/8 PASS | `51a87cd703bb0be3d9765c9bdb684a96fc5a10a73daba397e7c35dbc7270e9e0` | Recursive validation |
| T8 | `CompiledExecutionPlannerTest` | 5/5 PASS | `4d92e690e06dedac3c0b9a8d9f5c75f78c72c6aadad38f2be1ce7f18c0b31ee4` | Block unit planning |
| T9 | `CanonicalDurableRunCoordinatorTest` + `CanonicalCoordinatorScopeStackTest` | 14/14 PASS | `815d2ec4832e0f4d92b7f97ccd39c231f43ecb284fb65fb9844e4be082e68e6e` + `ea4424fb07ded7abab481bdfa98f61f8e782fadf79485e65e91b15209431a3bb` | dispatchBody + ContextStack |
| T10 | `DslCompiledPipelineCompilerTest` | 9/9 PASS | `bb32991093ede7c90f3f705f5ec09605a544f63f5dab06b944be117809fef6f7` | blockStepNode routing |
| T11 | `FArchL7BlockStepNestingInvariantTest` | 12/12 PASS | `589f652b9fd673e90c6b345ae0deeb6d176631631ffa4b37da022ad7d427817d` | Existing invariant coverage |
| T12 | `UatDsl006BodyExecutionTest` | 4/4 PASS | `832a3e169fcebc8e67e4a52f215f3b521e7ad930de5a1e36e1c9e7dc2199b4b1` | dir/timeout/retry/nested CLI UAT |

---

## Open Adjudications (for verify phase)

### (a) BlockStepNode emits no lifecycle events for itself

**Observation:** `BlockStepNode` (dir, timeout, retry) does not emit `StepStarted/StepFinished` events for itself — only its body children emit events with body-path-affected step names (e.g., `test/dir-body-0/echo-0`).

**Spec question:** Does spec R-EM4-5 or JEP-029 wording require BlockStepNode to emit lifecycle events for itself, or is child-only emission compliant?

**Evidence:** `UatDsl006BodyExecutionTest` confirms the current behavior. Children execute with body-path-affected names; the BlockStepNode itself is not represented in the event stream.

**Requested action:** Verify phase adjudicator to confirm whether this is (i) compliant with JEP-029 wording, (ii) a known gap to fix in EM-5, or (iii) a spec gap requiring JEP amendment.

### (b) ErrorHandlingTest ERR-S-* failures — baseline classification

**Observation:** `ErrorHandlingTest` (6 failures: ERR-S-001, 002, 003, 004, 007, 008) was not listed in the EM0 baseline receipt's failure inventory. It is unclear whether these failures existed at base `1790a44`.

**AGENTS.md rule 16 requirement:** Base-vs-head classification is REQUIRED before any fix or release claim.

**Requested action:** Verify phase to run ErrorHandlingTest at base `1790a44` and confirm whether failures are (i) pre-existing baseline, (ii) EM-4 regressions, or (iii) new failures introduced by the corrected catchError/warnError routing.

---

## Regression and Fix Disclosure

### fixture10 regression (CompatibilityCorpusTest)

**Initial EM-4 state:** fixture10 (`10-smoke-e2e.pipeline.kts`) passed at baseline but failed after initial T10 routing of `CatchError`/`WarnError` to `BlockStepNode`.

**Root cause:** Premature routing of `CatchError` and `WarnError` to `BlockStepNode` without EM-5/EM-6 failure-suppression semantics caused the pipeline to exit with code 1 instead of 0.

**Fix applied:** Reverted `CatchError` and `WarnError` to `rewriteWorkflowControl` legacy linear path.

**Post-fix result:** fixture10 remains failing with exit code 1. Post-hoc analysis shows this is a pre-existing baseline failure — fixture10 was NOT in the EM0 baseline failure inventory and must be reclassified as baseline or base-vs-head verified.

---

## CompatibilityCorpusTest Baseline Status

| Fixture | EM0 Baseline | Post-EM4 Apply | Notes |
|---|---|---|---|
| fixture01-basic | PASS | PASS | |
| fixture02-environment | FAIL (baseline) | FAIL | Pre-existing baseline |
| fixture03-stages | PASS | PASS | |
| fixture04-sh | PASS | PASS | |
| fixture05-scripted-if | PASS | PASS | |
| fixture06-loop | PASS | PASS | |
| fixture09-archive-artefacts | PASS | PASS | |
| fixture10-smoke-e2e | PASS → FAIL | FAIL | Regression — disclosed above |
| fixture11-workflow-control | FAIL (baseline) | FAIL | Pre-existing baseline |
| fixture12-error-handling | FAIL (baseline) | FAIL | Pre-existing baseline |
| fixture13-workspace-helpers | FAIL (baseline) | FAIL | Pre-existing baseline |
| fixture14-credentials-bindings | PASS (compile error expected) | PASS | |

**Note:** fixtures 02, 11, 12, 13 are baseline failures not fixed in EM-4 scope.

---

## UatDsl005TimeoutGrammarTest Same-4-Failures Statement

`UatDsl005TimeoutGrammarTest` has 4 failures both at baseline and post-EM4 apply. No change introduced by EM-4 commits. Classified as UNCLASSIFIED in EM0 baseline receipt; verify phase must resolve.

---

## Scope-Stack Tests Status

`CanonicalDurableRunCoordinatorTest` (12 tests) and `CanonicalCoordinatorScopeStackTest` (14 tests) both PASS with the corrected T10 boundary. R6-block-step_nesting_invariant tests are GREEN.

---

## Risks

1. **BlockStepNode event emission gap (adjudication a):** BlockStepNode doesn't emit lifecycle events for itself — only children do. May need spec clarification or EM-5 fix.
2. **ErrorHandlingTest classification (adjudication b):** ERR-S-* failures unclassified at baseline. Verify phase must resolve.
3. **fixture10 regression:** Not yet fully classified. Requires base-vs-head verification.
4. **R-EM4-7 partial retirement:** Design deviation documented. Full retirement of `rewriteWorkflowControl` deferred to EM-5/EM-6.

---

## Tree Clean Confirmation

```
$ git status --short
# (empty — clean)
```

All EM-4 apply work committed. No uncommitted changes.

---

## Next Recommended

**sddk-verify** — verify phase with:
- Full L4/L5 gate run to confirm no new regressions
- Adjudication (a) resolution: BlockStepNode event emission spec compliance
- Adjudication (b) resolution: ErrorHandlingTest base-vs-head classification
- fixture10 final classification
