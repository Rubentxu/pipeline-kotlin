# C1-D StepSpec partition receipt

**Date:** 2026-09-26
**Work item:** C1-D residual DSL source partition
**Base SHA:** `9e26cc65902fdb0b4e381cc36e4f6ca4acd1762f`
**Scope:** physically move the complete `StepSpec` hierarchy from `PipelineDsl.kt` to `PipelineDslSteps.kt`, preserve the package and nested fully-qualified names, and update source-based fitness only where the source partition requires it.

## Decision and boundaries

- The accepted authority for this slice is `docs/v2/04-adrs/ADR-0094-dsl-source-partition-without-semantic-change.md`.
- The repository's existing ADR-0078 is the unrelated ResourceRef identity decision. The historical C1 isolation note used that number for a proposed DSL split, so this slice does not treat that historical label as authority.
- `PipelineDslTypes.kt` was already present from `ce2a7abe`; it was not re-extracted.
- `StageScope`, validation behavior, lowering behavior, public package names, and runtime semantics remain outside this slice.

## Changes

- Added `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDslSteps.kt`.
- Removed the complete `StepSpec` declaration block from `PipelineDsl.kt`.
- Preserved all 33 StepSpec declarations and their public property/method signatures. A source comparison found 33 declarations in both the original and current hierarchy and no diff in the extracted signature list.
- Updated `FArchLfc1LegacyDslRemovedTest` to scan the DSL source set rather than assuming all StepSpec declarations remain in one file. This is a test-source path correction, not a weakened assertion.

## Verification evidence

All commands ran from `v2` with the repository Gradle wrapper. Logs are under `/home/rubentxu/.jcode/scratch/`.

| Check | Result | Evidence |
|---|---|---|
| Focused DSL tests | PASS | `:pipeline-scripting-api:test --tests 'dev.rubentxu.pipeline.v2.dsl.PipelineDslSealedHierarchyTest' ... --fail-fast --console=plain`; exit `0`; digest `1af117b7d52e5eb6b4cff1b7f6f4911e386f3d4d65dde6da15611183796b0798` |
| Scripting API test compilation | PASS | `:pipeline-scripting-api:compileTestKotlin --console=plain`; exit `0`; digest `2d41b3b8a90516d7647df60f211e960cebf778d3a581bc547254605c54ed59f0` |
| Kotlin scripting host tests | PASS | `:pipeline-scripting-kotlin24:test --tests '*WithCredentialsCompileIntegrationTest' --tests '*CompiledScriptedEntryPointHostTest' --fail-fast --console=plain`; exit `0`; digest `39b00f8fd4ab3f21428d435390f2c6bd3ef24d04c64bc3537519abad5b7652d8` |
| Architecture fitness, first run | EXPECTED RED | `FArchLfc1LegacyDslRemovedTest` failed because it hard-coded `PipelineDsl.kt`; no semantic failure was reported |
| Corrected fitness class | PASS | `:pipeline-architecture-tests:test --tests 'dev.rubentxu.pipeline.v2.architecture.FArchLfc1LegacyDslRemovedTest' --fail-fast --console=plain`; exit `0`; digest `9ae57e1a8101e37e7ce66ec27fcecc07380b826a94cddc2d07a3860b52960548` |
| Affected architecture set | PASS | seven selected fitness classes under `:pipeline-architecture-tests:test`; exit `0`; digest `f19886d930c74983aa10397efb169ff9e1be8e0fd460ddb7bd4b9ead2408a6a9` |
| Diff hygiene | PASS | `git diff --check` produced no output |
| Module API task | NOT APPLICABLE | `:pipeline-scripting-api:apiCheck` does not exist in this module. The Gradle build config wires API checks only for the four BCV modules, not `pipeline-scripting-api`. No API drift is claimed from this absent task. |

## Acceptance result

- **Physical partition:** verified.
- **StepSpec identity and signatures:** verified by source comparison and compilation.
- **DSL behavior and scripting visibility:** verified by focused DSL and host tests.
- **Architecture fitness:** verified after adapting the obsolete file-path assumption.
- **Public API drift:** no drift was observed in the tested signatures; the module-specific BCV task is not configured, so the claim is bounded accordingly.
- **Full repository `check`:** not run. It is outside the change-scoped loop and remains a release/gate concern.
- **External release harness:** not run. It belongs to the separate harness project.

Reference implementation consulted: none applicable. This is a source-organization refactor with no equivalent external runtime behavior.

Behaviour adopted: keep the complete typed StepSpec hierarchy under the same package and nested names while moving its source to a dedicated file.

Intentional deviations: no `StageScope` extraction and no validation/lowering façade were introduced because they are outside this residual slice.

Security implications reviewed: no effect, process, credential, persistence, or network behavior changed. The change only moves declarations and updates a source-location fitness assumption.

Tests demonstrating the contract: `pipeline-scripting-api` focused DSL tests, `pipeline-scripting-kotlin24` host tests, and the selected `pipeline-architecture-tests` fitness classes listed above.
