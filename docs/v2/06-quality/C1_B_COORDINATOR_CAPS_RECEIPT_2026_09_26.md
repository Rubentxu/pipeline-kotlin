# C1-B — CoordinatorCaps Compatibility Seam — Receipt (2026-09-26)

## Scope

C1-B of the isolated H1/H2/H3 partition plan from
`docs/v2/06-quality/C1_ISOLATION_2026_09_26.md`.

The repository audit and isolated analysis identified that
`CanonicalDurableRunCoordinator` had accumulated **22** constructor
parameters. Before this slice, no `CoordinatorCaps` implementation existed
in production code. The WIP bundle was introduced only after a repository-wide
symbol search confirmed the gap.

## Change

- Added `CoordinatorCaps` as an immutable typed data class in the durable
  application package.
- Added a secondary `CanonicalDurableRunCoordinator(CoordinatorCaps)`
  constructor.
- Preserved the existing 22-parameter constructor unchanged for named and
  positional compatibility.
- Forwarded all 22 fields one-to-one, including optional durable journals,
  policy resolver, artifact index, and body invoker adapter.
- Added `CoordinatorCapsTest` covering:
  - optional default values,
  - legacy default implementations,
  - structural data-class equality,
  - actual coordinator construction through the new overload.

No dispatcher, journal, replay, registry, capability, or step semantics were
changed. `dispatchBody`, `dispatch`, and `runParallelStage` remain untouched,
as required by the C1 plan.

## Completion audit

| Item | Observed result |
|---|---|
| D-013 already complete? | Yes, commit `aed82670` plus resolution receipt `ae94e5ec`; no duplicate threshold implementation added. |
| C1-A already complete? | Yes, commit `0f75dc2d`; `CanonicalRuntimeContext.kt` exists at the canonical FQN. |
| CoordinatorCaps already implemented? | No. Search found only the new WIP implementation and its test before this slice. |
| Legacy constructor preserved? | Yes. The primary constructor was not rewritten or removed. |
| New overload wired? | Yes. `CanonicalDurableRunCoordinator(caps)` compiles and is exercised by the focused test. |

## Verification

Commands and fresh evidence:

1. `cd v2 && timeout 600 ./gradlew :pipeline-application:compileKotlin :pipeline-application:compileTestKotlin --console=plain`
   - exit code: 0
   - observed: `BUILD SUCCESSFUL in 11s`
2. `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.CoordinatorCapsTest' --console=plain`
   - exit code: 0
   - JUnit XML: 4 tests, 0 skipped, 0 failures, 0 errors
   - XML SHA-256: `0e4f135a66255ac57d01f56b4c715cb372214d047172ebb6efcf7eb7a3888f88`
   - console log SHA-256: `51345ecb43990e4953a7900d22e1dc0a296cc7a820bee3b59e169937a482f23e`
3. `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest' --console=plain`
   - exit code: 0
   - JUnit XML: 26 tests, 0 skipped, 0 failures, 0 errors
   - XML SHA-256: `7e0f621c6ada7816d0578e968926e897375ad4586a94d90789cdaca9d2aaefec`
   - console log SHA-256: `c03c5650d5b88f2f10da3c8723f9d896ad016a6f72d06c6df9cff6d9fb5ab00f`

The full repository `check` gate was not rerun because this slice adds one
application constructor overload and a focused contract test. No build file,
shared domain/event contract, or generated API baseline changed.

## Reference implementation and design closure

- Reference implementation consulted: none applicable. This is an internal
  typed dependency-bundle refactor, not a Jenkins-compatible behavior change.
- Behaviour adopted: preserve the legacy constructor while exposing one typed
  immutable capability bundle for new composition roots.
- Intentional deviations: no migration of existing call sites in this slice;
  the overload is additive to keep the change low-risk.
- Security implications reviewed: no new capability is granted. The bundle
  forwards the exact capabilities already accepted by the legacy constructor.
- Tests demonstrating the contract:
  `CoordinatorCapsTest`, `CanonicalDurableRunCoordinatorTest`.

## Remaining C1 work

C1-B is complete. C1-C (`Main.runCanonicalPipeline` extraction) and C1-D
(`PipelineDsl` partition) remain separate work items and were not started by
this receipt.
