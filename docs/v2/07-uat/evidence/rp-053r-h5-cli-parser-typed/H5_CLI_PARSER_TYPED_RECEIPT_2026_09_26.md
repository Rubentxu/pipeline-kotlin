# H5 CLI parser typed boundary receipt

**Work item:** H5 from the 2026-09-26 architecture audit
**Cycle:** `p-733fb505b5a6bd2d/rp-053r-h5-cli-parser-typed`
**Base SHA:** `b6f124168fab7cb2aafea8422bbdee883e8c207c`
**Implementation SHA:** `a893cca781ecc9464b9599490ccc5e106ab680f9`
**Status:** `CANDIDATE_VERIFIED`

## Objective

Extract the mutable CLI argument loop from `Main.kt` into a pure, typed parser without changing the accepted command-line behavior for `validate`, `run`, durable selection flags, sandbox profile selection, workspace, control root, or plugin JARs.

## Change

- Added `CliParser.parse(Array<String>): CliParseResult`.
- Added the closed command model `CliCommand`.
- Added typed `CliFlags` with a non-null script path after successful parsing.
- Added typed `CliError` cases for invalid commands, missing values, conflicting durable flags, unsupported sandbox profiles, unknown options, and missing scripts.
- Connected `Main` to the parsed ADT and removed the nullable parser sentinel.
- Kept durable execution and validation behavior outside the parser boundary unchanged.
- Preserved the shared non-canonical bridge rejection message and moved it beside the CLI boundary because it is consumed by the application entry point.

## Verification

| Check | Exact argv | Exit | Evidence |
|---|---|---:|---|
| Production/test compilation | `timeout 600 ./gradlew :pipeline-application:compileTestKotlin --console=plain` | 0 | `/home/rubentxu/.jcode/scratch/h5-cli-compile-3.log`, SHA-256 `9bd2dc52c5074e75698366f45ea75bc543de9dd2c455bf7605fcb6a529373e33` |
| Fresh focused test | `timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainCliParsingTest' --rerun-tasks --console=plain` | 0 | `/home/rubentxu/.jcode/scratch/h5-cli-test-fresh.log`, SHA-256 `844ff85fc7678b5ec267786733172d66b81b6b4d60fd3a915d4e987664557a8e` |
| JUnit XML | `pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.MainCliParsingTest.xml` | 10/0/0/0 | SHA-256 `7c397ae6d8023bd7e37b220ded233a68585eac0f0204375f937d8ed0d394b5e2` |
| Diff hygiene | `git diff --check` | 0 | no output |

The focused class ran 10 tests with 0 failures, 0 errors, and 0 skipped tests. The `--rerun-tasks` run was required because the first post-commit invocation was served from Gradle cache and retained an old XML timestamp. The fresh rerun produced the current timestamp `2026-09-26T23:24:18.488Z`.

## Scope limits

- Full repository `check`: `NOT_RUN`, outside this surgical slice.
- External `pipelinek-release-harness`: `NOT_RUN`, owned by the separate harness repository.
- Installed distribution and release candidate: deferred to the release step after main integration.
- `agent-session`: unavailable in the environment, command exits 127. SDDK CLI cycle state and Git evidence were used instead.

## Reference and quality closure

Reference implementation consulted: none applicable. This is an internal CLI admission boundary, not a Jenkins behavior change.

Behaviour adopted: invalid CLI input is rejected as a typed value before execution, while successful input produces immutable command flags for the existing runtime.

Intentional deviations: no clikt migration; ADR-0077 remains deferred. This slice only removes the mutable parser loop and nullable parse sentinel.

Security implications reviewed: parsing remains fail-closed, rejects unsupported sandbox profiles and unknown options, and performs no filesystem, process, network, persistence, or credential effects.

Tests demonstrating the contract: `MainCliParsingTest` at the implementation SHA, including durable policy selection, invalid command, missing script, conflicting flags, unsupported sandbox, and ordered plugin JAR handling.
