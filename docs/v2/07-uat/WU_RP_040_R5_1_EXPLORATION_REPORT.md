# WU-RP-040 R5.1 — detekt burn-down of three regressed smells

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Date:** 2026-09-27
**Base SHA:** `ab5bec80` (rc7, `wu/rp-053r-red-fixtures`)
**Investigator:** orchestrator
**Verdict:** REGRESSION_CONFIRMED, BURNDOWN_WORTHWHILE

## Context

`./gradlew -p v2 check --rerun-tasks` over `ab5bec80` (rc7) surfaces three new
detekt issues that break the local round gate. R5 itself was archived as closed
(`21b89514`, 2026-09-24) but detekt strict was never executed on top of the
post-R5 commits that introduced or moved code:

- `650403ff refactor(sdk/codec): introduce PipelineJson + migrate 4 codecs (C3)` — added `JsonAccessors` with 12 extension functions.
- `e2db03b9 refactor(sdk/codec): migrate CoreUtilsWriteYamlCodec to PipelineJson (D-012)` — same object, +1 accessor.
- `193b6133 refactor(scripting): partition StepSpec source declarations` — split `StepSpec` into `PipelineDslSteps.kt`, but the file name no longer matches the top-level declaration.
- `a893cca7 refactor(application): type CLI parser boundary` — moved `parseCliArgs` from `Main.kt` to `CliParser.kt` without updating the detekt baseline, and the new function carries the same cyclomatic complexity.

`pipeline-application` baseline already lists `Main.kt:parseCliArgs` as a
known issue; the symbol no longer exists in source, so the baseline is stale.

## Three issues to fix

1. `:pipeline-application:detekt` — `CliParser.kt:64` `parse` is
   `CyclomaticComplexMethod` (complexity 27, threshold 25).
2. `:pipeline-scripting-api:detekt` — `PipelineDslSteps.kt:15` does not match
   `MatchingDeclarationName` (file name vs top-level declaration).
3. `:pipeline-step-sdk:api:detekt` — `PipelineJson.kt:100` `JsonAccessors` is
   `TooManyFunctions` (12 functions, threshold 11).

## Fix shape

| Issue | Approach | Surface change |
|---|---|---|
| 1 | extract `parseOption` per case in CliParser | `CliParser.kt` ~+30 LOC; no public API change |
| 2 | rename file `PipelineDslSteps.kt` → `StepSpec.kt` and update the hardcoded path in `FArchLfc1LegacyDslRemovedTest.kt` | `git mv` + 1 test update |
| 3 | `@Suppress("TooManyFunctions")` on `JsonAccessors` with a justifying comment (pattern already used for `CyclomaticComplexMethod` in `DslCompiledPipelineCompiler.encodePayload`) | 1 annotation, no consumer break |

The alternative for issue 3 — splitting the object into two — would force every
codec consumer (six files) to update imports, which is more invasive without
reducing the surface that detekt measures.

## Out of scope

- The A/B/D mutation categories (`RP040_R8_MUTATION_SURVIVOR_TRIAGE.md`) remain
  classified as accepted / UAT-covered / low-value. They are not in scope here.
- Dependabot (R3.4) stays `BLOQUEADO_EXTERNO` (operator decision).
- The `koverXmlReport` instrumentation baseline run that R5 promised is NOT
  executed here; that is a separate WU once the round gate is clean.

## Decision

Burn down the three smells and re-run the full local round gate. If green,
emit `WU_RP_040_R5_1_CLOSURE_RECEIPT.md` referencing the L4 result and XML
artifacts. The receipt closes R5.1 without re-opening R3.3 / R3.4 / R5
itself.

Reference implementation consulted: none applicable. The detekt rules
(`CyclomaticComplexMethod`, `MatchingDeclarationName`, `TooManyFunctions`) are
internal static-analysis thresholds.
