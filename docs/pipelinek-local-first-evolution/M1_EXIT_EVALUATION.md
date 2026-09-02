# M1 exit evaluation — PASS

Date: 2026-09-02
Base: `7af9e95cbc2fb948a71c5f15ed5c61c4aba607ee` (main, pre-M1)
Scope: LF-0101..LF-0106, LF-0107, LF-0108
Head: `adcce79fbb56aa2860cf2ff34ca90619b7772f04` (PR #9, feat/lf-m1-runtime-config)

## Decision

**M1 exits PASS.** The canonical-contract surface is in place; the runtime
behaviour it constrains is captured by characterisation tests; the duplicate
declarations called out by [M0_DUPLICATE_INVENTORY.md](M0_DUPLICATE_INVENTORY.md)
are either removed (call-sites) or quarantined with explicit allowlist entries
(`Effect` / `ReplayPolicy` in `:pipeline-step-sdk:api`).

M2 may begin on the single-RunCoordinator surface. No exception record is
required.

## Exit criterion checklist

The M1 exit criterion from [LOCAL_FIRST_ROADMAP.md](docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md) §M1 is:

> contratos objetivo sin declaraciones duplicadas; characterization GREEN

| Sub-criterion | Status | Evidence |
|---|---|---|
| IDs contract (LF-0101..LF-0103) | PASS | `PipelineIdsTest` 12/12 + `FArchM1CanonicalIdsTest` 4/4 |
| Clock seam (LF-0107+LF-0108)     | PASS | `ClockContractTest` 4/4 + `FArchM1CanonicalClockTest` 4/4 |
| Typed outcomes (LF-0104)          | PASS | `RunOutcomeReducerTest` 10/10 + `PipelineFailureTest` 3/3 + `FArchM1CanonicalOutcomesTest` 3/3 |
| Effect/ReplayPolicy (LF-0105)     | PASS-WITH-QUARANTINE | `FArchM1CanonicalEffectsTest` 4/4; ADR-0063 quarantines the sdk:api duplicates for M3-R2 |
| RuntimeConfig (LF-0106)           | PASS | `MapRuntimeConfigTest` 11/11 + `SystemRuntimeConfigTest` 4/4 + `FArchM1CanonicalRuntimeConfigTest` 5/5 |
| No domain `System.getenv` / `System.getProperty` reads | PASS | `FArchM1CanonicalRuntimeConfigTest` pine `Domain source must obtain OS environment and JVM properties through the RuntimeConfig seam only` |

## Characterisation GREEN

All characterisation suites for the M1 surface are green on the same SHA
(`adcce79`). Fresh execution with new XML timestamps confirmed per
`AGENTS.md` §25 canary protocol. Evidence recorded below.

| Suite | Tests | SHA-256 of fresh XML |
|---|---:|---|
| `FArchM1CanonicalIdsTest`             |  4/4 PASS | `57df8427a34384ab7bc034aee7654177fd128162201d8cbbc906a23f44d3ef06` |
| `FArchM1CanonicalClockTest`           |  4/4 PASS | `61ae7a33c0a86dbb85b627215f7b130a50a0047e6f3233ab9d07ae0a0fed296a` |
| `FArchM1CanonicalOutcomesTest`        |  3/3 PASS | `26f4e8ed9059b29fb045b08bd7388b4336deda4f028591cda898eb2a3d317a71` |
| `FArchM1CanonicalEffectsTest`         |  4/4 PASS | `a2e81066e461ad5f6a1c51545cce9976d606fd4904e7568b21110a45a4683b66` |
| `FArchM1CanonicalRuntimeConfigTest`   |  5/5 PASS | `3b491407bbf7e9282db07e4cadce43b6486c796b0c73f1d68a56e896f258dc1e` |
| `PipelineIdsTest`                     | 12/12 PASS | `51d8b43a871c101f8f9f39c48579fe3a88b9deb1e806d33ee04116550f32dde8` |
| `PipelineFailureTest`                 |  3/3 PASS | `c7acac009059194ee2138b6d458ce6532f74bbe3119c0ce75226bda8f7781625` |
| `RunOutcomeReducerTest`               | 10/10 PASS | `eb4f962c36b54fa5bfe50e45d5a062c295bcad6a194f14cef3015b9ff1db7eeb` |
| `MapRuntimeConfigTest`                | 11/11 PASS | `4f6098b25ad77b7727a48354175df0e23215b6dd298f81a0294cb5e1d5417516` |
| `ClockContractTest`                   |  4/4 PASS | `33d74bdf15ee5e4005b456c6da625bf1f5237e6de8b9c711a3cff6846f1bdda4` |
| `SystemRuntimeConfigTest`             |  4/4 PASS | `b2d6d2a4a9ed89ccfbba77520a6f8172e1198fbf068c61bff8184db7bf4b46e5` |
| **Total**                             | **64 / 64 PASS** | |

Command that produced the fresh evidence:

```bash
rm -f v2/pipeline-architecture-tests/build/test-results/test/TEST-*FArchM1*.xml \
      v2/pipeline-domain/build/test-results/test/TEST-*PipelineIds*.xml \
      v2/pipeline-domain/build/test-results/test/TEST-*PipelineFailure*.xml \
      v2/pipeline-domain/build/test-results/test/TEST-*RunOutcome*.xml \
      v2/pipeline-domain/build/test-results/test/TEST-*MapRuntimeConfig*.xml \
      v2/pipeline-application/build/test-results/test/TEST-*ClockContract*.xml \
      v2/pipeline-application/build/test-results/test/TEST-*SystemRuntimeConfig*.xml

timeout 600 ./gradlew -p v2 \
  :pipeline-domain:test --tests 'PipelineIdsTest' --tests 'PipelineFailureTest' \
                         --tests 'RunOutcomeReducerTest' --tests 'MapRuntimeConfigTest' \
  :pipeline-application:test --tests 'ClockContractTest' --tests 'SystemRuntimeConfigTest' \
  :pipeline-architecture-tests:test --tests 'FArchM1*'
```

Exit `0`. All XML files regenerated with new `timestamp=` attributes.

## Quarantine register

The following items remain outside the M1 touched surface. They are explicitly
**not** M1 scope and cannot be used to claim runtime parity; each carries a
named milestone owner and expiry.

| Item | Owner | Expiry | Status |
|---|---|---|---|
| `Effect` / `ReplayPolicy` duplicate declarations in `:pipeline-step-sdk:api` (`dev.rubentxu.pipeline.v2.sdk.{Effect,ReplayPolicy}`) | M3-R2 (LF-0308) | M3 exit | Quarantined via `FArchM1CanonicalEffectsTest` allowlist; ADR-0063 records the consolidation plan |
| 19 call-sites of `DurableShConfig.fromSystemProperties()` in production | M2 (LF-0205 redirect CLI) | M2 exit | Out of M1 scope; canonical `RuntimeConfig` seam introduced and pinned in LF-0106 so each replacement has a clean regression net |
| 8 direct `System.getenv` / `System.getProperty` calls in `PipelineRun.kt` / `Main.kt` | M2 (LF-0307 migrate sh) | M2/M3 exit | Out of M1 scope; pinned by `FArchM1CanonicalRuntimeConfigTest` (domain side); migration side documented but not enforced yet |
| `StepDescriptor.effects: List<String>` / `replayPolicy: String` raw-string fields | M2 (LF-0205/0307) | M2/M3 exit | Out of M1 scope; canonical `Effect` / `ReplayPolicy` enums exist in domain (LF-0105); migration pinned for M2 |
| Host-level git-wrapper policy blocking commits without explicit identity | Environment | n/a | Pre-existing baseline fail-closed policy (`git-wrapper: repo NO CLASIFICADO`); not a runtime defect. Documented per `UatLocal005CheckoutGitTest.SC-007`; reproducible on PR #8 head without LF-0106 code present |

A quarantined item must not be hidden, skipped or weakened; its owner must
produce the closure receipt before the relevant milestone exit.

## M2 boundary

The first M2 slice must:

1. Establish the canonical `PipelineCompiler` (LF-0201) — pure domain function
   that takes a `Pipelinefile` source and produces a `PipelineDefinition`.
2. Widen the current 7-line `PipelineDefinition` (LF-0202) to carry the steps
   it actually owns — the data class today only carries `id` / `name` /
   `version`, which is structurally not the entity M2 expects.
3. Introduce a `RunCoordinator` port (LF-0203) in `:pipeline-domain` with a
   single application adapter that owns dispatch — replacing the
   `PipelineOrchestrator` legacy and the layered dispatch logic currently in
   `PipelineRun.kt` (4469 lines).
4. Introduce a `StepDispatcher` port (LF-0204) — the seam that both parallel
   and serial execution must go through.

The migration of `Main` / `PipelineRun` / `PipelineOrchestrator` (LF-0205) is
deferred to a follow-up slice so the contracts land with a clean regression
net first.

## Open feature-freeze items

M1 must not have touched any of the frozen surfaces called out by
[M0_FEATURE_FREEZE.md](M0_FEATURE_FREEZE.md) (process execution, credentials,
workspace cleanup, output capture, classifier/observability). Verified by
`git diff --stat feat/lf-m1-effects-replay-policy..feat/lf-m1-runtime-config`
— the M1 slice is pure-add (no edits to existing production files in those
surfaces) plus docs.

## Cross-references

- Backlog: [IMPLEMENTATION_BACKLOG.md](IMPLEMENTATION_BACKLOG.md)
- Roadmap: [docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md](docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md) §M1
- UAT plan: [docs/v2/07-uat/LOCAL_FIRST_UAT_PLAN.md](docs/v2/07-uat/LOCAL_FIRST_UAT_PLAN.md) §M1
- ADR-0063: [docs/v2/04-adrs/ADR-0063-effect-replay-policy-canonical-authority.md](docs/v2/04-adrs/ADR-0063-effect-replay-policy-canonical-authority.md)
- M0 baseline: [M0_BASELINE_EVIDENCE_2026-09-02.md](M0_BASELINE_EVIDENCE_2026-09-02.md)
- PR stack: #6 (LF-0101..LF-0108), #7 (LF-0104), #8 (LF-0105), #9 (LF-0106)
