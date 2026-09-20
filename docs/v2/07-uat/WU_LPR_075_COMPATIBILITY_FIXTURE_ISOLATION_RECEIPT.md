# WU-LPR-075 — Compatibility fixture isolation and reclassification

**Date**: 2026-09-20
**Status**: CLOSED
**Authority**: LPR-GATE-1 follow-up, V2 testing rules 7–10 and 17–25

## Trigger and diagnosis

The LPR-073 application regression exposed three independent failures in
`CompatibilityCorpusTest`:

- `05-scripted-if.pipeline.kts` was classified as an historical compile
  failure, but a direct installed-binary invocation returned exit `0`.
  The current scripting surface supports its ordinary Kotlin `if` and nested
  `echo` calls, so the old negative expectation had become stale.
- `25-yaml-roundtrip.pipeline.kts` and `27-zip-unzip.pipeline.kts` used the
  checked-in `v2/compatibility` directory as their mutable workspace. Their
  default `overwrite=false` contracts correctly rejected outputs left by a
  previous run (`build/utils/config.yaml` and `build/utils/source.zip`).

The defects were in the test harness expectation and workspace ownership, not
in the pipeline runtime. This is an improvement over the already closed
LPR-GATE-1 evidence: it makes the installed-distribution corpus repeatable.

## Change

- Reclassified fixture 05 as a passing compatibility fixture and removed its
  now-unused historical compile-failure helper/classification.
- Added a small `copyFixtureInto` test helper. Fixtures 25 and 27 now receive
  a JUnit `@TempDir`, copy their source `.pipeline.kts` there, and pass that
  directory as `--workspace`.
- The checked-in corpus is therefore input-only for mutable fixture runs. The
  production runtime, DSL, Step registry and fixture source files are unchanged.

## Verification

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:compileTestKotlin --rerun-tasks
# BUILD SUCCESSFUL in 33s

timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CompatibilityCorpusTest.fixture05ScriptedIf' --rerun-tasks
# BUILD SUCCESSFUL in 39s

timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CompatibilityCorpusTest.fixture25YamlRoundtrip' --rerun-tasks
# BUILD SUCCESSFUL in 39s

timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CompatibilityCorpusTest.fixture27ZipUnzip' --rerun-tasks
# BUILD SUCCESSFUL in 40s

timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CompatibilityCorpusTest' --rerun-tasks
# BUILD SUCCESSFUL in 3m 6s
```

Fresh XML canary:

```text
CompatibilityCorpusTest: tests="30" skipped="0" failures="0" errors="0"
```

Evidence SHA-256:

```text
compile:     e61764b61ddbd3c1eaff5be1c7d95947b2dc3f754eea81810122c762d1a106a7
fixture 05:  ba88690f4fde584f0dc718e674e4a7fddfd0803a71ddc0aba2744b4b327c1f25
fixture 25:  650ec93755d844895ce7269b7c381231f8278925b7595b51529c70da6df61119
fixture 27:  fa621e52012b24db132075aa28c8146f231e3c7c3366d1fc8a72650e4b07e263
full class:  a955c7883fd90b977060cc1a91b055205627a9737586504466f9215cf964978f
```

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; JUnit 5 @TempDir lifecycle
Behaviour adopted:                  mutable installed-binary fixtures own isolated temporary workspaces
Intentional deviations:             fixture 05 is now correctly a supported passing script program
Security implications reviewed:     test-generated YAML and ZIP outputs remain in JUnit-managed temp directories
Tests demonstrating the contract:    CompatibilityCorpusTest (30/0/0/0)
```
