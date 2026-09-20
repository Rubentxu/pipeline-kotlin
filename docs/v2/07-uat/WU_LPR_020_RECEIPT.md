# WU-LPR-020 — Generic body carrier proof (closure receipt)

**Status:** CLOSED. Base `2e314178` (post-E1 cycle) → head (single commit).
**Outcome:** Structural proof that the existing closed IR (`BlockStepNode`,
`StageBody`, `StageNode`) already carries body shapes `None`, `Single`, and
`Named` without introducing any plugin-specific node. No engine migration;
tests only.

---

## 1. Scope

> Prove current BodyRef/BlockStepNode can represent `None/Single/Named`
> without plugin-specific node. Add structural tests; no engine migration yet.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-020, L16-18).

The WU produces a structural proof — the migration to a
`BodyExecutionEngine` happens later (WU-LPR-021..024). No production code
modified.

## 2. Body shape definitions

| Shape | Definition | IR carrier |
|-------|------------|-----------|
| **None** | The block exists but holds no children | `BlockStepNode.body = emptyList()` |
| **Single** | The block holds exactly one child | `BlockStepNode.body = listOf(onlyStep)` |
| **Named** | The block holds multiple children, each identified by a stable `StepId` (or `StageNode.name` for `Parallel` branches) | `BlockStepNode.body = listOf(...)` with distinct ids; `StageBody.Parallel(branches = listOf(StageNode(name=..., ...)))` |

All three shapes are expressible with the closed structural IR that already
exists in `CompiledPipeline.kt` (L137-142 for `BlockStepNode`, L110-119 for
`StageBody`). No new node type, no plugin-specific subtype.

## 3. Test surface (8 tests, 0 failures, 0 errors)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/WULpr020GenericBodyCarrierTest.kt`

| Nested group | Test | Shape |
|--------------|------|-------|
| `BlockStepNodeShapes` | `BlockStepNode with empty body represents None` | None |
| `BlockStepNodeShapes` | `BlockStepNode with singleton body represents Single` | Single |
| `BlockStepNodeShapes` | `BlockStepNode with multi-step body represents Named (via StepNode id)` | Named |
| `StageBodyParallelBranches` | `Parallel body with empty branches is None` | None (Parallel) |
| `StageBodyParallelBranches` | `Parallel body with single named branch is Single+Named` | Single+Named |
| `StageBodyParallelBranches` | `Parallel body with multiple named branches represents Named` | Named (Parallel) |
| `NestedBlocks` | `BlockStepNode nesting None-within-Single preserves both shapes` | None-in-Single |
| `StepIdAuthoritative` | `every StepNode carries an id used as its body-position key` | Named (id carrier) |

Each test round-trips the IR through JSON (`@Serializable` codec) and
asserts the shape is preserved. If any future migration regresses the
shape (e.g. collapses `body` to a nullable, drops `StepId`, or replaces
`StageBody.Parallel.branches` with a plugin-specific subtype), the relevant
test fails loudly.

## 4. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-domain:compileTestKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-domain:test --tests 'WULpr020GenericBodyCarrierTest'
    BUILD SUCCESSFUL in 11s

XML: v2/pipeline-domain/build/test-results/test/
      TEST-dev.rubentxu.pipeline.v2.domain.WULpr020GenericBodyCarrierTest*.xml
        - 4 nested groups, 8 tests, 0 failures, 0 errors, 0 skipped
```

(For evidence: `grep '<testsuite' *.xml` shows `tests="N" skipped="0"
failures="0" errors="0"` for each of the 4 nested groups; sum = 8.)

## 5. Background materials

- `docs/v2/07-uat/wu-lpr-020-background/00-cli-transcripts.txt` — raw
  CLI transcripts captured during pre-orientation (not part of this WU's
  evidence; retained for traceability only).

## 6. Findings for follow-up

None new. WU-LPR-020 is a structural-measurement slice. The migration that
actually moves `None/Single/Named` semantics behind a typed
`BodyExecutionEngine` is WU-LPR-021 (Sequential/Scoped) and WU-LPR-022
(Retry/timeout migration).

## 7. Auto-continue

This WU closes the first LPR-2 milestone. Auto-continue to **WU-LPR-021**
(BodyExecutionEngine Sequential/Scoped extraction).

---

**CLOSED — 2026-09-20.**
