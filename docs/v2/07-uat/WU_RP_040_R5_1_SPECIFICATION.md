# WU-RP-040 R5.1 — Specification: detekt burn-down contracts

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Phase:** Specify
**Base SHA:** `5c262bf9` (exploration commit on `wu/rp-053r-red-fixtures`)

This slice targets three detekt findings on `ab5bec80`. Each is contractually
closed by a typed observable: the local round gate `cd v2 && ./gradlew check
--rerun-tasks` exits 0 and produces three fresh XML reports (or detekt
sub-reports) that contain zero entries for the corresponding rule.

## C-1 — CliParser CyclomaticComplexMethod

**Baseline:** `:pipeline-application:detekt` reports complexity 27 at
`CliParser.kt:64` (`fun parse`); threshold 25.

**Contract:**
- The detekt run on `:pipeline-application` reports zero
  `CyclomaticComplexMethod` issues against `CliParser.kt`.
- The detekt checkstyle XML at `v2/pipeline-application/build/reports/detekt/detekt.xml`
  contains no `<error>` element whose `source` attribute equals
  `detekt.CyclomaticComplexMethod` and whose `file` attribute equals
  `pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt`.
- Existing tests still pass: `MainCliParsingTest` 10/0/0/0 (XML SHA-256
  preserved from previous green run, target ≤ current
  `7c397ae6d8023bd7e37b220ded233a68585eac0f0204375f937d8ed0d394b5e2`).
- The public surface (`CliParser.parse(args): CliParseResult`,
  `CliCommand`, `CliFlags`, `CliError`, `CliParseResult`,
  `DurableRunPolicy`, `DurableRunSelection`) is unchanged.

**Approach:** extract a private helper `applyOption(option, index, args, state): Pair<ParsedOption?, Int>`
that handles the inner `when` cases (`--db`, `--resume`, `--rerun`,
`--control-root`/`--workspace`/`--plugin-jar`, `--sandbox-profile`). The public
`parse` function keeps the loop and the early returns; the per-option logic
moves into `applyOption`. The new complexity target for `parse` is ≤ 22; for
`applyOption` ≤ 12.

## C-2 — PipelineDslSteps file name

**Baseline:** `:pipeline-scripting-api:detekt` reports
`MatchingDeclarationName` at `PipelineDslSteps.kt:15` (top-level
`sealed interface StepSpec`).

**Contract:**
- The detekt run on `:pipeline-scripting-api` reports zero
  `MatchingDeclarationName` issues.
- The file containing the DSL `sealed interface StepSpec` is named
  `StepSpec.kt` and the only top-level declaration matches the file name.
- The architecture test `FArchLfc1LegacyDslRemovedTest` (path hardcoded to
  `PipelineDslSteps.kt` at line 25) is updated to assert against the new
  filename, or removed entirely if its purpose was to lock the partition
  outcome that is now canonical.
- Existing tests for `StepSpec` still pass:
  `:pipeline-architecture-tests:test` zero regressions.

**Approach:** `git mv` the source file and update the single hardcoded test
path. No semantic change to the `StepSpec` hierarchy.

## C-3 — JsonAccessors TooManyFunctions

**Baseline:** `:pipeline-step-sdk:api:detekt` reports `TooManyFunctions` at
`PipelineJson.kt:100` (`object JsonAccessors`); 12 functions, threshold 11.

**Contract:**
- The detekt run on `:pipeline-step-sdk:api` reports zero `TooManyFunctions`
  issues against `PipelineJson.kt`.
- The `JsonAccessors` object keeps its public surface unchanged: every
  extension function currently used by the six codec consumers
  (`GitCheckoutCodec`, `JUnitReportCodec`, `CoreUtilsReadJsonCodec`,
  `CoreUtilsWriteJsonCodec`, `CoreUtilsWriteYamlCodec`, and any other
  consumer discovered by grep) remains importable as
  `dev.rubentxu.pipeline.v2.sdk.JsonAccessors.<name>`.
- Codec consumers compile and run unchanged.

**Approach:** apply `@Suppress("TooManyFunctions")` to the `JsonAccessors`
declaration with a justifying comment that explains why 12 paired
`requiredX`/`XOrNull` extension functions for six primitive types plus
`requiredObject` + `requiredArray` are better kept together than split. This
mirrors the precedent set by `DslCompiledPipelineCompiler.encodePayload`
which already carries `@Suppress("CyclomaticComplexMethod")` for the same
reason (33>25 closed dispatch).

The split-into-two-objects alternative is rejected because it would force
six codec consumers to update their imports without reducing the surface
detekt measures (the rules operate on a single file per object).

## Acceptance gate

A single command captures all three contracts:

```bash
cd v2 && timeout 1200 ./gradlew check --no-daemon --rerun-tasks --console=plain
```

The run must:
- Exit 0.
- Generate fresh XML for `MainCliParsingTest`, `:pipeline-scripting-api:test`,
  and `:pipeline-step-sdk:api:test` (timestamp newer than the cycle commit).
- Produce detekt XML for `pipeline-application`, `pipeline-scripting-api`,
  `pipeline-step-sdk/api` with zero entries matching the three smells.

If any contract fails, the slice is not closed; the failure is documented
in `WU_RP_040_R5_1_CLOSURE_RECEIPT.md` §Failure-mode as a typed outcome
(`RegressionRecurrence` | `NewRegression` | `TestRegression`).

## Out of scope (unchanged)

- A/B/D mutation categories remain classified per
  `RP040_R8_MUTATION_SURVIVOR_TRIAGE.md`; not touched.
- R3.4 Dependabot remains `BLOQUEADO_EXTERNO`; not touched.
- R5 `coverage-all` job is verified by the fresh XML outputs in the closure
  receipt; its CI-side wiring is not re-rendered.
- No public API change to `CliParser`, `PipelineJson`, `JsonAccessors`, or
  the `StepSpec` hierarchy.

Reference implementation consulted: none applicable.
