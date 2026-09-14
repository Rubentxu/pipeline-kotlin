# B11 / W1+W2+W3 — context-only blocks via BodyInvoker (receipt)

**Slice:** `cycle/lfc2-e1-b11-context-blocks` (cycle_id `p-733fb505b5a6bd2d/lfc2-e1-b11-context-blocks`)
**Base:** `a66d7f6c28ea5aa5e9c0c81b3a55f5d4ac06fb12` (B10 W1d evidence digests commit)
**Worktree:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-b11`
**Subject branch:** `refactor/lfc2-e1-b11-context-blocks`
**Authoritative artifacts (CAS):**
- `proposal.md` sha256 `72df5a2bd2723e89cc72306260daeed0de2eb6ac3fcb0ecbdeeb85755f02a98a` → `art-72df5a2bd272-2ccdb7ee`
- `spec.md` sha256 `3fc1a098a76bf750ae44463fbdc70eeb7b6fdda681babfd60fba156af438d9c4` → `art-3fc1a098a76b-3d1b69b7`
- `tasks.md` sha256 `8a171c9222c7d59ad5ba3fa2821da94f0a0ed6046344686a2018774029f063ce` → `art-8a171c9222c7-73f24fc3`

**Verifier:** H0/H1/harness in `pipeline-domain` + `pipeline-application` + `pipeline-architecture-tests`,
mechanical LFC-2 family gate, no custom external script (the B11 invariants are
already mechanically checked by the W1d + B11 fixtures).

## 0. Pre-flight evidence

| Reference | Value |
| --- | --- |
| `docs/v2/07-uat/evidence/b11-w1/G0-baseline.txt` | already recorded at G0: `fork_point=a66d7f6c28ea5aa5e9c0c81b3a55f5d4ac06fb12`, `subject_branch=refactor/lfc2-e1-b11-context-blocks`, `worktree=/var/home/rubentxu/Proyectos/kotlin/pipeline-b11`, `ledger_event_count=1` |
| `HISTORICAL_CEILING` (B10) | `18` — **IMMUTABLE** across B11 |
| `PinnedConcreteBodyRoutingDebt.value.total` (pre-B11) | `0` (W1d's reclassified empty ledger) |
| `BodyChildLoopInventory.EXPECTED` | `(loopDefinitions = 1, credentialAcquisitions = 1)` |
| `BodyChildLoopInventory.discovered` (post-B11) | `(loopDefinitions = 1, credentialAcquisitions = 1)` (comment-grep scanner) |

## 1. What this slice changes

B11 lifts context-only blocks (`dir`, `withEnv`, `timestamps`) onto the **pure** child-context
derivation the W1d body engine already iterates: the projection branch lives in `pipeline-domain`,
the dispatcher in `pipeline-application` becomes a thin interpreter over `BodyExecutionPolicy`,
and a new `CanonicalBodyInvokerAdapter` binds the seam under `BODY_INVOKER_CAPABILITY`.

| Layer | Change |
| --- | --- |
| `pipeline-domain` | new `BodyExecutionContextDerivation.kt` (~196 lines) — `deriveChildExecutionContext(parent, projection, runtime)` plus the closed `BodyContextProjection` / `BodyRuntimeValue` / `BodyContextDerivation` / `BodyContextRejection` ADT; new `BodyDecorator` ADT (Timestamps vs None) alongside `BodyContextProjection`. `BodyInvoker.kt` extended with `BodyInvocationContext.decorator: BodyDecorator` (default `None` is bit-equivalent at every legacy call site). |
| `pipeline-application` | new `CanonicalBodyInvokerAdapter.kt` (~115 lines) — implements `BodyInvoker`, run-local `Map<BodyRef, suspend () -> StepOutcome>`, unknown `BodyRef` → typed `Cancelled(ParentCancelled)`, **never iterates body children itself** (BodyChildLoopInventory law preserved). `CanonicalRuntimeContext.bodyInvoker: CanonicalBodyInvokerAdapter?` (default null, every legacy call site bit-equivalent). `CanonicalRuntimeCapabilityAccess.buildProvided` exposes `BODY_INVOKER_CAPABILITY` only when `bodyInvoker != null` (fail-closed admission). `CanonicalDurableRunCoordinator.bodyInvokerAdapter: CanonicalBodyInvokerAdapter = CanonicalBodyInvokerAdapter()` (positional, default = no-op) and `dispatchBody` registers a single-shot runner closure that calls `invokeBodyChildren(...)` before the existing `try`, closes it in the existing `finally`. |
| `pipeline-application` tests | new `CanonicalBodyInvokerAdapterTest.kt` (~256 lines, 16 tests in 5 nested classes) — OpenCloseContract, InvokeContract, BodyRefEncoding, RunnerClosureSemantics, SingleSharedLoopLaw; new `B11ContextBlocksRuntimeTest.kt` (~500 lines, 7 in-process tests) — `dir` cwd propagation, `withEnv` env overlay propagation, `timestamps` is a decorator (no CWD/ENV overlay), compound `dir { withEnv { timestamps { sh } } }` nesting, sibling-after-dir no-global-cwd-restore, capability binding proof (custom adapter, `openBodyCount == 0` after run), outer-in/inner-out event ordering. |
| `pipeline-domain` tests | new `BodyExecutionContextDerivationTest.kt` (~524 lines, 25 tests in 9 nested classes) — ParentImmutability, WorkingDirectoryDeterminism, EnvironmentDeterminism, NestedWorkingDirectoryComposition, NestedEnvironmentShadowing, SiblingIsolation, InvalidProjectionTyped, SeamAlgebra, ProjectionDoesNotInspectStepKey. |
| `pipeline-architecture-tests` | new `Lfc2B11ExternalScopedRoutingDefenseFitnessTest.kt` (~431 lines, 8 tests) — defense fixture: pure resolver admits `example.projectX` declaring `Scoped(Environment)` exactly as `core.withEnv`; pure derivation produces the same child for two distinct StepKeys; registry is extensible; production coordinator contains no `when(stepKey)`/`when(name)` switch and no `example.*` literal reference. |

### 1.1 Hexagonal architecture preserved

The seam direction is unchanged from W1d:

```text
BodyInvoker (domain port)
    <- CanonicalBodyInvokerAdapter (application adapter implementing BodyInvoker)
    <- CanonicalRuntimeContext.bodyInvoker (capability, fail-closed absent)
    <- CanonicalRuntimeCapabilityAccess.buildProvided (exposes BODY_INVOKER_CAPABILITY only when bound)
    <- CanonicalDurableRunCoordinator.dispatchBody (interpreter, single shared loop)
```

The pure derivation lives in `pipeline-domain`; the coordinator is an interpreter over the typed
decision. No domain or application code depends on adapter implementations of a different seam.

### 1.2 Reusing the body-machine invariant

B11 does not add a second body-child loop or a parallel dispatch path. The new adapter closes
its single-shot runner closure in the existing `finally` of `dispatchBody` — exactly mirroring the
existing `DirExited` / `TimestampsExited` emission semantics. `BodyChildLoopInventory` stays at
`(1, 1)`.

## 2. Commits

| SHA | Subject |
| --- | --- |
| `7d29fb2ffeaf9cd0df10393ed8616cd638dc1871` | chore(b11-g0): record B11 fork-point evidence and worktree identity |
| `5e1f15278ef72940accbf8e1e717f6499e6666b5` | feat(lfc2-b11): add pure child-context derivation + BodyDecorator |
| `1ce3813b4e8486da868bfb7b3f9683740766dccd` | feat(lfc2-b11): bind BodyInvoker adapter under BODY_INVOKER_CAPABILITY |
| `51f82a36e44bb6afc7f4df9f9dba45ad7cccb973` | feat(lfc2-b11): open-world defense fixture for Scoped(Environment) routing |
| `51f82a36` (HEAD) | `refactor/lfc2-e1-b11-context-blocks` |

## 3. Diff stat `base..HEAD`

```text
 docs/v2/07-uat/evidence/b11-w1/G0-baseline.txt                        |   5 +
 v2/pipeline-application/src/main/kotlin/.../CanonicalBodyInvokerAdapter.kt              | 113 +++++
 v2/pipeline-application/src/main/kotlin/.../CanonicalDurableRunCoordinator.kt           |  39 ++
 v2/pipeline-application/src/main/kotlin/.../CanonicalNodeDispatcher.kt                 |  14 +-
 v2/pipeline-application/src/main/kotlin/.../CanonicalRuntimeCapabilityAccess.kt         |  11 +
 v2/pipeline-application/src/test/kotlin/.../B11ContextBlocksRuntimeTest.kt              | 500 +++++++++++++
 v2/pipeline-application/src/test/kotlin/.../CanonicalBodyInvokerAdapterTest.kt          | 256 ++++++++
 v2/pipeline-architecture-tests/src/test/kotlin/.../Lfc2B11ExternalScopedRoutingDefenseFitnessTest.kt | 431 ++++++++++++
 v2/pipeline-domain/src/main/kotlin/.../step/BodyExecutionContextDerivation.kt          | 196 ++++++
 v2/pipeline-domain/src/main/kotlin/.../step/BodyInvoker.kt                              |  14 +
 v2/pipeline-domain/src/test/kotlin/.../step/BodyExecutionContextDerivationTest.kt      | 524 ++++++++++++
 11 files changed, 2102 insertions(+), 1 deletion(-)
```

## 4. Fitness summary

| Suite | Tests | Failures | Errors | Notes |
| --- | --- | --- | --- | --- |
| `BodyExecutionContextDerivationTest` (HF0 pure) | 25 | 0 | 0 | 9 nested classes, all green |
| `BodyInvokerSeamTest` | 8 | 0 | 0 | unchanged from W1d |
| `BodyExecutionPolicyTest` | 28 | 0 | 0 | unchanged from W1d |
| `CanonicalBodyInvokerAdapterTest` (HF1) | 16 | 0 | 0 | new |
| `B11ContextBlocksRuntimeTest` (HF1 in-process) | 7 | 0 | 0 | new |
| `Lfc2B11ExternalScopedRoutingDefenseFitnessTest` (B11 defense) | 8 | 0 | 0 | new |
| `Lfc2BodyExecutionPolicyFitnessTest` (B10 carry-over) | 10 | 0 | 0 | unchanged |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` (B10 carry-over) | 16 | 0 | 0 | `BodyChildLoopInventory == EXPECTED`, debt == pin |
| `Lfc2DurableAggregateIdentityFitnessTest` (B10 carry-over) | 5 | 0 | 0 | unchanged |
| `Lfc2DurableCoordinatorScopeFitnessTest` | 4 | 0 | 0 | unchanged |
| `Lfc2RegistryFamilyFitnessTest` | 3 | 0 | 0 | unchanged |
| **TOTAL** | **130** | **0** | **0** | |

### 4.1 Family invariants (post-B11)

```text
BodyChildLoopInventory.discovered       = (loopDefinitions = 1, credentialAcquisitions = 1)
BodyChildLoopInventory.EXPECTED         = (1, 1)
PinnedConcreteBodyRoutingDebt.value.total = 0
PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING = 18   (UNCHANGED, IMMUTABLE)
ConcreteBodyRoutingDebt.scan(coordinator) = empty
BodyExecutionPolicy vocabulary         = no "core.*" / "example.*" literal
BodyExecutionContextDerivation         = no "core.*" / "example.*" literal, no when(stepKey)
CanonicalDurableRunCoordinator         = no when(stepKey), no when(name), no "core.*" / "example.*" in `when`, no example.projectX / example.withEnv literal anywhere
```

## 5. Behavioral invariants added by B11

| Invariant | Type | Tested by |
| --- | --- | --- |
| `BodyExecutionContextDerivation` is total over the closed `BodyContextProjection` family (no `else`) | static + dynamic | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > policy vocabulary and projection branch never name a step key or step name` |
| `deriveChildExecutionContext` does NOT read `PluginStepId` (parent + projection + runtime only) | signature + dynamic | `BodyExecutionContextDerivationTest > ProjectionDoesNotInspectStepKey` |
| `BodyExecutionContextDerivation` is pure (no I/O, no clock, no global mutation) | dynamic | `BodyExecutionContextDerivationTest > ParentImmutability` |
| Parent immutability: child derivation never mutates parent.overlays | dynamic | `BodyExecutionContextDerivationTest > ParentImmutability` |
| Nested env shadowing: inner env override visible to inner steps, hidden from outer | dynamic | `BodyExecutionContextDerivationTest > NestedEnvironmentShadowing` |
| Nested dir composition: inner dir composes with outer | dynamic | `BodyExecutionContextDerivationTest > NestedWorkingDirectoryComposition` |
| Sibling isolation: two siblings derived from same parent are equal but distinct values | dynamic | `BodyExecutionContextDerivationTest > SiblingIsolation` |
| Invalid projection typed: `Environment` payload routed to `WorkingDirectory` projection → typed rejection | dynamic | `BodyExecutionContextDerivationTest > InvalidProjectionTyped` |
| `timestamps` projection does NOT introduce `ContextKind` (closed family has no timestamps kind) | structural + dynamic | `BodyExecutionContextDerivationTest > SeamAlgebra` |
| `dir { withEnv { timestamps { sh } } }` runs under projected cwd + env overlay, with timestamps decorator | dynamic | `B11ContextBlocksRuntimeTest > nested context blocks propagate cwd and env with timestamps decorator` |
| `dir { failingStep(); followingStepOutsideDir() }` does NOT restore cwd globally (CTX-P) | dynamic | `B11ContextBlocksRuntimeTest > sibling-after-dir does not restore cwd globally` |
| Capability binding proof: `openBodyCount == 0` after run, custom adapter receives only declared capability | dynamic | `B11ContextBlocksRuntimeTest > capability binding proof` |
| Outer-in / inner-out event ordering preserved | dynamic | `B11ContextBlocksRuntimeTest > dir enter exits in correct order with nested scopes` |
| `CanonicalBodyInvokerAdapter.invoke` over unknown `BodyRef` returns typed `Cancelled(ParentCancelled)`, never iterates body children | dynamic | `CanonicalBodyInvokerAdapterTest > InvokeContract > unknown body ref is rejected typed` |
| `CanonicalBodyInvokerAdapter` never iterates body children itself (BodyChildLoopInventory) | structural | `CanonicalBodyInvokerAdapterTest > SingleSharedLoopLaw` |
| Pure resolver admits `example.projectX` declaring `Scoped(Environment)` exactly as `core.withEnv` | dynamic | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > pure resolver returns Scoped Environment for example projectX exactly as for core withEnv` |
| Pure derivation produces same child for two distinct StepKeys with same shape + payload | dynamic | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > derivation produces the same child context for two distinct StepKeys with the same shape` |
| Standard descriptor registry is extensible: external mirror resolves identically to core | dynamic | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > standard registry resolves core withEnv and an external Scoped Environment plugin identically` |
| Production coordinator has no concrete `when(stepKey)`/`when(name)` switch and no `example.*` literal | static | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > production coordinator has no concrete when-switch over PluginStepId or stepName` + `production coordinator contains no reference to example projectX or example withEnv literals` |
| Incoherent plugin declaration (Scoped projection without matching ContextKind) rejected fail-closed | dynamic | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > pure resolver rejects incoherent plugin declaration fail closed` |

## 6. Hard-STOP compliance

- **No new switch by StepKey/name:** none introduced. Defended by `Lfc2B11ExternalScopedRoutingDefenseFitnessTest > production coordinator has no concrete when-switch over PluginStepId or stepName`.
- **No new `dispatch*Block` collection:** none. BodyInvoker routing uses the shared `invokeBodyChildren(...)` path; defended by `CanonicalBodyInvokerAdapterTest > SingleSharedLoopLaw` + `Lfc2ConcreteBodyRoutingDebtFitnessTest > the body child path is shared and defined exactly once`.
- **No modify retry/timeout/withCredentials/parallel:** none. The pre-existing retry path uses `core.retry`'s `Retrying(RetryPolicy)` declared policy, the same seam; deadlines (`core.timeout`) still flow through `ShOptions.timeoutMs` via `HandledOutsideOverlay`; credential leasing stays the preamble of the shared loop.
- **BodyInvoker does not need to know `core.dir/withEnv/timestamps`:** the adapter receives `BodyRef`s and `BodyInvocationContext` (parent, projection, decorator) — it has no switch on core keys.
- **No second BodyExecutionPolicy authority:** the policy authority is `BodyExecutionPolicy` and `BodyContextProjection`; `deriveChildExecutionContext` is total over the closed family; the policy vocabulary has no `core.*` / `example.*` literal.
- **No global cwd/env mutation:** parent `ExecutionContext` is immutable; derivation is `(parent, projection, runtime) -> child`; sibling isolation holds.
- **DSL does not execute effects:** `registryStep(...)` (already present) is the only Step primitive; the `B11ContextBlocksRuntimeTest` instantiates the coordinator with the production registry.
- **ConcreteBodyRoutingDebt:** `0` (unchanged).
- **HISTORICAL_CEILING:** `18` (unchanged, IMMUTABLE).
- **Pre-existing red set:** not widened.
- **ADR-0073 / ADR-0081:** preserved. The B11 seams are body-machine extensions, not new dispatchers.

## 7. Diff stat summary (sources only)

```text
11 files changed, 2102 insertions(+), 1 deletion(-)
  - production: 5 files, +387 lines (3 adapter wirings + 1 new adapter + 1 derivation module + BodyInvoker extension)
  - tests:      5 files, +1720 lines (1 new derivation suite + 2 new runtime suites + 1 new defense fixture + 1 new adapter suite)
  - evidence:   1 file, +5 lines (G0-baseline.txt)
```

## 8. STOP / NEXT

The slice is ready for verification.

- **Pre-merge verification (verify phase):** not yet invoked. Recommended commands:
  - L0 compile: `timeout 600 ./gradlew -p v2 :pipeline-domain:compileTestKotlin :pipeline-application:compileTestKotlin :pipeline-architecture-tests:compileTestKotlin`
  - L1 WU1/WU2/WU3: `timeout 600 ./gradlew -p v2 :pipeline-domain:test --tests 'Body*' :pipeline-application:test --tests 'B11*' --tests 'CanonicalBodyInvokerAdapterTest' --tests 'BodyInvokerSeamTest' --tests 'BodyExecutionPolicyTest' :pipeline-architecture-tests:test --tests 'Lfc2B11ExternalScopedRoutingDefenseFitnessTest'`
  - L4 LFC-2 family: `timeout 600 ./gradlew -p v2 :pipeline-architecture-tests:test --tests 'Lfc2*'`
  - L5 round gate: `timeout 1270 ./gradlew -p v2 check`
- **sddk-verify:** candidate after the round gate.
- **sddk-debt-verify:** candidate after sddk-verify.
- **sddk-release:** candidate after sddk-debt-verify.
- **Archive:** after the release receipt is captured.

Hard STOP conditions remain in force from the cycle preamble; nothing in B11 trips them.
