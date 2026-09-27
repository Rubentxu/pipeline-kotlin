# WU-RP-040 R5.1 — Implementation Plan

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Phase:** Plan
**Base SHA:** `5905e6f6` (design commit on `wu/rp-053r-red-fixtures`)

Three tasks, ordered by independence and risk. Each task produces one
commit. Verification between tasks runs the relevant focused tests and
detekt module reports.

## Task 1 — D-3 @Suppress on JsonAccessors

**Dependencies:** none.
**Files:**
- `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/PipelineJson.kt`
  (one annotation line + comment).

**Steps:**
1. Add `@Suppress("TooManyFunctions")` with the justifying comment block
   from D-3 on `object JsonAccessors` (line 100).
2. `cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:api:detekt --no-daemon --rerun-tasks --console=plain`
   (must exit 0; zero `TooManyFunctions` errors in detekt XML).
3. `cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:api:test --no-daemon --console=plain`
   (no regression).
4. Commit with conventional message:
   `chore(pipeline-step-sdk:api): suppress TooManyFunctions on JsonAccessors with documented rationale`.
5. Capture XML canary SHA-256 for `:pipeline-step-sdk:api:detekt` and `:pipeline-step-sdk:api:test`.

## Task 2 — D-2 rename PipelineDslSteps.kt → StepSpec.kt

**Dependencies:** Task 1 (independent but ordered for incremental safety).
**Files:**
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDslSteps.kt`
  → `StepSpec.kt`.
- `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/FArchLfc1LegacyDslRemovedTest.kt`
  (line 25 path string).

**Steps:**
1. `git mv` the file.
2. Update the hardcoded path in `FArchLfc1LegacyDslRemovedTest.kt`.
3. `cd v2 && timeout 600 ./gradlew :pipeline-scripting-api:detekt :pipeline-architecture-tests:test --no-daemon --rerun-tasks --console=plain`
   (must exit 0; zero `MatchingDeclarationName` errors in
   `pipeline-scripting-api`; architecture test green).
4. Commit with conventional message:
   `refactor(pipeline-scripting-api): rename PipelineDslSteps.kt to StepSpec.kt; update fitness path`.
5. Capture XML canary SHA-256 for `:pipeline-scripting-api:detekt` and
   `:pipeline-architecture-tests:test`.

## Task 3 — D-1 CliParser complexity reduction

**Dependencies:** Task 1, Task 2 (independent but ordered: smaller fixes
first to keep the diff easy to review).
**Files:**
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt`.

**Steps:**
1. Add internal `ParseState` class and private `applyOption` function per
   D-1.
2. Refactor `parse(args)` to drive the loop, delegate each option to
   `applyOption`, and short-circuit on `Rejected`/`Conflict` results.
3. `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainCliParsingTest' --no-daemon --rerun-tasks --console=plain`
   (must exit 0; `MainCliParsingTest` 10/0/0/0).
4. `cd v2 && timeout 600 ./gradlew :pipeline-application:detekt --no-daemon --rerun-tasks --console=plain`
   (must exit 0; zero `CyclomaticComplexMethod` errors against
   `CliParser.kt`).
5. Commit with conventional message:
   `refactor(application): reduce CliParser.parse complexity below 25 via per-option helper`.
6. Capture XML canary SHA-256 for `MainCliParsingTest` and `:pipeline-application:detekt`.

## Task 4 — Final round gate

**Dependencies:** Tasks 1, 2, 3 all green.
**Steps:**
1. `cd v2 && timeout 1200 ./gradlew check --no-daemon --rerun-tasks --console=plain`
   (must exit 0).
2. Aggregate XML scan via `find v2 -name 'TEST-*.xml' -newer 5905e6f6 -path '*/build/test-results/*'`.
3. Aggregate detekt scan via the three checkstyle XMLs.
4. Commit any remaining documents (closure receipt).
5. Emit `WU_RP_040_R5_1_CLOSURE_RECEIPT.md` with the round-gate evidence
   bundle.

## Failure handling

If any task fails:
- Document the failure in the next task's commit body.
- If the failure is unrelated to the current task (e.g. a flake), retry
  with `--rerun-tasks`; if still red, escalate to the operator before
  continuing.
- If the failure reveals a hidden coupling (e.g. another test references
  `PipelineDslSteps.kt`), stop and classify the new debt; do not silently
  extend the slice.

## Out of scope for this plan

- Any change to detekt thresholds in `v2/config/detekt/detekt.yml`.
- Any re-render of the `lpr0-ci.yml` workflow.
- Any modification of detekt baselines (D-3 is targeted, not a baseline
  rewrite).
- Any new tests beyond the existing `MainCliParsingTest`.

Reference implementation consulted: none applicable.
