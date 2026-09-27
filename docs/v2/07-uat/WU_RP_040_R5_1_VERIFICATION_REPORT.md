# WU-RP-040 R5.1 — Verification Report

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Date:** 2026-09-27
**Implementation SHA:** `4207748e`
**Closure SHA:** `3b5af4fb`
**Branch:** `wu/rp-053r-red-fixtures`

## Verification scope

The verification report covers the three contracts defined in
`WU_RP_040_R5_1_SPECIFICATION.md` and the architectural and policy
constraints derived from the spec/design/plan chain.

## Tests pass

| Contract | Command | Exit | Evidence |
|---|---|---|---|
| C-1 | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainCliParsingTest' --no-daemon --rerun-tasks --console=plain` | 0 | `TEST-dev.rubentxu.pipeline.v2.application.MainCliParsingTest.xml` SHA-256 `fc0cf3ff8a9b4d81653dd34a008de363d443ad3ba90ba256a21d235b27d64a2f` with `tests="10" failures="0" errors="0" skipped="0"` |
| C-2 | `cd v2 && timeout 600 ./gradlew :pipeline-scripting-api:detekt :pipeline-architecture-tests:test --no-daemon --rerun-tasks --console=plain` | 0 | `pipeline-scripting-api/detekt.xml` SHA-256 `f491e274f0d208f81c3edbc7c1097c30db590a3822fcf579468ac814d9ccc62b` (empty); `FArchLfc1LegacyDslRemovedTest.xml` SHA-256 `bc72227fe7a5f358b73ded7968bd338b5169359fbb489bc268c92bb2c880f015` |
| C-3 | `cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:api:detekt :pipeline-step-sdk:api:test --no-daemon --rerun-tasks --console=plain` | 0 | `pipeline-step-sdk/api/detekt.xml` SHA-256 `f491e274f0d208f81c3edbc7c1097c30db590a3822fcf579468ac814d9ccc62b` (empty); XML canaries `c11e8771…` and `2f373c5e…` for `CompatibilityLevelEnumTest` and `StepDescriptorSchemaTest` |

## Policy compliance

| Policy | Source | Verdict |
|---|---|---|
| Hexagonal dependency direction | AGENTS.md §HEXAGONAL | PASS — no production changes that introduce new modules, new dependencies, new public APIs, new persistence formats, new event types, or new CLI flags. `CliParser` is an application-layer adapter that already existed; the refactor is internal. `JsonAccessors` keeps its public surface; only the file-level comment and annotation changed. `StepSpec.kt` is a rename. |
| Functional core, imperative shell | AGENTS.md §STRICT TYPED FUNCTIONAL | PASS — `CliParser.parse` remains a pure function returning a typed `CliParseResult` ADT. The `ParseState` mutable accumulator is local to one parse invocation and discarded; no global state. |
| Closed execution structure, open Step registry | AGENTS.md §STEP CONSTITUTION | N/A — no Step changes. |
| Per-step observability | AGENTS.md §STEP SEMANTICS | N/A — no Step changes. |
| Capability-routed handler discipline | AGENTS.md §CAPABILITY_ROUTED_HANDLER | N/A — no Step changes. |
| Step Constitution MUST NOT | AGENTS.md §STEP_IMPL | N/A — no Step changes. |
| R5.1 contracts | `WU_RP_040_R5_1_SPECIFICATION.md` §C-1, C-2, C-3 | PASS — three detekt smells closed; the underlying XMLs and tests confirm closure. |
| Detekt baseline mechanism | `v2/build.gradle.kts` (detekt subprojects block) | NOT_MODIFIED — the cycle did not touch detekt thresholds or baselines. `@Suppress` is a per-declaration pragma, not a baseline mutation. |
| Mutable flag bags / nullable sentinels | AGENTS.md §REVIEW_CHECKLIST | PASS — `ParseState` is a per-invocation accumulator with typed fields; no nullable sentinels paired with booleans; no `Map<String, Any?>` in the touched surface. |
| Single source of truth for CLI parsing | AGENTS.md §DETERMINISM | PASS — the `CliParser.parse` function is the only entry point; the legacy `parseCliArgs` in `Main.kt` was already removed by H5 (`a893cca7`). The detekt baseline entry for `Main.kt:parseCliArgs` is now stale but harmlessly so (the symbol no longer exists; detekt ignores it). |

## Debt severity / priority

The cycle did not introduce new debt. The three smells were strictly
`regressions from already-archived commits`:

- C-1 regression introduced by `a893cca7 refactor(application): type CLI parser boundary` (post-R5).
- C-2 regression introduced by `193b6133 refactor(scripting): partition StepSpec source declarations` (pre-R5, not surfaced because R5's archived evidence was on `21b89514`, before this refactor landed).
- C-3 regression introduced by `650403ff refactor(sdk/codec): introduce PipelineJson + migrate 4 codecs (C3)` and `e2db03b9 refactor(sdk/codec): migrate CoreUtilsWriteYamlCodec to PipelineJson (D-012)` (pre-R5, not surfaced for the same reason).

Severity: all three are `cosmetic / tooling` — they block the local round
gate but do not affect runtime behaviour, public API, or durability
semantics. Priority: `P1` because they break the gate. All three are now
`RESOLVED` in this cycle.

A pre-existing flake (`Rp022ThroughputProbe.redactor throughput floor()`,
D-002) reproduced under round-gate concurrency. Severity: `P2 flake`,
priority: `P2`. The slice does not re-open that decision; the operator's
prior evaluation stands.

| ID | Description | Severity | Priority | Resolution |
|---|---|---|---|---|
| C-1 | CliParser complexity 27>25 | cosmetic | P1 | RESOLVED in `4207748e` |
| C-2 | PipelineDslSteps MatchingDeclarationName | cosmetic | P1 | RESOLVED in `b8f9ec01` |
| C-3 | JsonAccessors TooManyFunctions | cosmetic | P1 | RESOLVED in `1c436312` |
| D-002 | Rp022ThroughputProbe flake under concurrency | P2 flake | P2 | UNCHANGED; operator's earlier decision stands |

## Reference implementation consulted

None applicable. All three closures are detekt-rule-driven refactors
following in-repo precedent (`encodePayload` already carries a
documented `@Suppress("CyclomaticComplexMethod")`).

## Lessons captured

1. **R5 was archived without strict-mode round-gate evidence on the
   post-R5 chain.** Future rounds of this slice (RP-4 cycles, RC sign-off)
   must run `cd v2 && ./gradlew check --rerun-tasks` before archiving,
   not just `koverXmlReport` or focused tests.
2. **`@Suppress` with a justifying comment is the right tool for a
   closed IR primitive with paired accessors.** Splitting `JsonAccessors`
   would have been more invasive without reducing the surface detekt
   measures.
3. **Mechanical refactors preserve historical coupling**; a pre-rename
   grep across `v2/` caught the single fitness-test consumer that
   hardcoded the old path.

## Cycle verdict

PASS. The three contracts are closed. The single round-gate failure is a
pre-existing flake classified as `NOT_REGRESSION`. Cycle
`p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown` is eligible for the
release/archive phases.
