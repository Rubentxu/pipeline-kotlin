# S2-A2 / G5 — core.sleep LEGACY_REMOVED receipt

Date: 2026-09-11T13:47Z · Base SHA: `02ab1a72` · Branch: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`

## Change (19 files, +22/−301)

Three legacy source-of-truth forms physically removed for `core.sleep`:

1. `CanonicalCoreStepMetadata["core.sleep"]` row — deleted.
2. `CanonicalCoreStepDecoder` sleep branch (11 lines) — deleted.
3. `CanonicalSleepNodeDispatcher.kt` + `CanonicalSleepNodeDispatcherTest.kt` +
   `CoreSleepLegacyCharacterizationTest.kt` (214 lines) — deleted.

Tests updated to registry-aware composition; historical G3 differential/
readiness classes marked `@Disabled` (frozen evidence, superseded by G4
`CoreSleepRegistryPrimaryFitnessTest`).

New: `S3SleepLegacyRemovedFitnessTest` (4 tests) — static absence proof of all
three legacy forms.

## Scoped verification (fresh XML, canary-verified)

| Evidence | Result |
|---|---|
| `S3SleepLegacyRemovedFitnessTest` | PASS 4/4/0 |
| `CoreSleepStepUnitTest` | PASS 10/0/0 |
| `CoreSleepRegistryPrimaryFitnessTest` | PASS 6/0/0 |
| `CoreSleepCoordinatorCharacterizationTest` | PASS 4/0/0 |
| G3 historical classes (`DifferentialParity`, `MigrationReadiness`) | skipped 4+3 (`@Disabled`, intentional) |
| `CompatibilityCorpusTest.fixture16Sleep` (fresh CLI) | PASS, exit 0, `RunFinished success` |

## CLI fresh/replay (16-sleep.pipeline.kts, same `--db` + `--control-root`)

- fresh: `sleep` executed by registry, `StepStarted → StepFinished`, `echo("woke")`,
  `RunFinished success`, exit 0.
- replay: sleep step events are the journaled fresh events (identical
  `eventId`/`sequence`/timestamps — MEMOIZED reuse, no re-execution),
  `RunFinished success`, exit 0.

## Counters

```text
LEGACY_PLUGIN_IDS = 10
metadata rows     = 10
dispatchers       = 10   (CanonicalErrorNodeDispatcher removed at S2-A1/G5)
```

## Round gate (L5 `check`, incremental)

`BUILD FAILED` with 9 failures, ALL pre-existing:

| # | Test | Prior evidence |
|---|---|---|
| 1 | `UatLocal005CheckoutGitTest.SC-007` | LB02_G0 #1; S2_5_7 #13-adjacent (host flake) |
| 2 | `UatLocal007SandboxProfileTest.SB-S-008` | LB02_G0 #2 |
| 3 | `UatLocal007SandboxProfileTest.SB-S-010` | S2_5_7 #14 (line 719 identical) |
| 4 | `UatLocal008CredentialsTest.UAT-L8-CP-001` | LFC2E0 E14; S2_5_7 #16 |
| 5 | `UatLocal008CredentialsTest.CR-BD-027` | LFC2E0 E14; S2_5_7 #15 |
| 6-8 | `UatLocal009TopStepsTest.CR-U9-008/011/012` | LB02_G0 #3-5 (line 335 identical) |

Rule-16 fresh base-vs-head evidence: identical 9-failure set reproduced at base
SHA `02ab1a72` in worktree `/tmp/base-g5-sleep` (log `/tmp/base-g5-uat.log`,
base XML SHA-256: 005 `2d074874d007…`, 007 `5f9e9c7cd3de…`, 008 `97253d0ba4d3…`,
009 `6508ed070905…`). **Zero regressions from the G5 diff.**

## Gate state

```text
REGISTERED         = true
REGISTRY_PRIMARY   = true
LEGACY_UNREACHABLE = true
LEGACY_REMOVED     = true
CONTRACT_SUITE     = true    ← G6 (2026-09-11T13:56Z)
CERTIFIED          = false   ← G8
```

## G6 — SleepStepContractSuite (2026-09-11T13:56Z)

`SleepStepContractSuiteTest`: **21 tests / 0 failures / 0 errors / 0 skipped**
(fresh XML canary-verified, `:pipeline-application:test --tests SleepStepContractSuiteTest`,
2026-09-11T13:56:02Z).

Coverage: identity, contract completeness (READ_ONLY + MEMOIZED + empty
capabilities), input codec round-trip + rejection (foreign kind / missing
seconds / negative seconds), output codec round-trip + non-SUCCESS rejection,
canonical byte-identical dsl-v1 envelope, production registry resolution +
fresh-factory consistency, capability declaration, admission with empty
available set, missing-capability fail-closed rejection (admission seam,
mirroring Echo suite), success, typed handler-exception failure, fresh durable,
MEMOIZED replay (StepStarted count and journal row stay at 1 — no re-execution),
divergence (changed seconds → typed failure), observability
(StepStarted/StepFinished pair), real DSL scenario
(`pipeline { stage { sleep(1); echo("woke") } }`).

Architecture-fitness row delegated to `S3SleepLegacyRemovedFitnessTest` +
`CoreSleepRegistryPrimaryFitnessTest` (G5 evidence above).
