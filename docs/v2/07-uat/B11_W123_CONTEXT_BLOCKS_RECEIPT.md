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
| `e48096a7e7fbf1c639685331d2718af871b37e05` | docs(lfc2-b11): family receipt for W1 W2 W3 context-only blocks |
| `cf541f40eca5f4ae9a7ff6d6176735d5557ab0a7` | fix(lfc2-b11): compile WithEnv and Timestamps body in blockStepNode (W3b) |
| `cf541f40eca5f4ae9a7ff6d6176735d5557ab0a7` (HEAD) | `refactor/lfc2-e1-b11-context-blocks` |

## 3. Diff stat `base..HEAD`

### 3.1 Total `a66d7f6c..cf541f40` (B11 + W3b)

```text
 docs/v2/07-uat/B11_W123_CONTEXT_BLOCKS_RECEIPT.md                                    | 182 +++++++
 docs/v2/07-uat/evidence/b11-w1/G0-baseline.txt                                       |   5 +
 v2/pipeline-application/src/main/kotlin/.../DslCompiledPipelineCompiler.kt            |   2 +
 v2/pipeline-application/src/main/kotlin/.../durable/CanonicalBodyInvokerAdapter.kt   | 113 +++++
 v2/pipeline-application/src/main/kotlin/.../durable/CanonicalDurableRunCoordinator.kt |  39 ++
 v2/pipeline-application/src/main/kotlin/.../durable/CanonicalNodeDispatcher.kt        |  14 +-
 v2/pipeline-application/src/main/kotlin/.../durable/CanonicalRuntimeCapabilityAccess.kt | 11 +
 v2/pipeline-application/src/test/kotlin/.../durable/B11ContextBlocksRuntimeTest.kt    | 500 ++++++++++++++++++++
 v2/pipeline-application/src/test/kotlin/.../durable/CanonicalBodyInvokerAdapterTest.kt | 256 ++++++++++
 v2/pipeline-architecture-tests/src/test/kotlin/.../Lfc2B11ExternalScopedRoutingDefenseFitnessTest.kt | 431 +++++++++++++++++
 v2/pipeline-architecture-tests/src/test/kotlin/.../Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.kt | 316 +++++++++++++
 v2/pipeline-domain/src/main/kotlin/.../step/BodyExecutionContextDerivation.kt         | 196 ++++++++
 v2/pipeline-domain/src/main/kotlin/.../step/BodyInvoker.kt                           |  14 +
 v2/pipeline-domain/src/test/kotlin/.../step/BodyExecutionContextDerivationTest.kt     | 524 +++++++++++++++++++++
 14 files changed, 2602 insertions(+), 1 deletion(-)
```

### 3.2 W3b-only `e48096a7..cf541f40` (companion fix)

```text
 v2/pipeline-application/src/main/kotlin/.../DslCompiledPipelineCompiler.kt                              |   2 +
 v2/pipeline-architecture-tests/src/test/kotlin/.../Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.kt | 316 +++++++++++++
 2 files changed, 318 insertions(+)
```

W3b does NOT touch `BodyInvoker`, `BodyExecutionContextDerivation`, `BodyExecutionPolicy`,
`CanonicalDurableRunCoordinator`, `CanonicalBodyInvokerAdapter`, or
`CanonicalRuntimeCapabilityAccess`. The B11 critical path is preserved.

### 3.3 B11-only `a66d7f6c..e48096a7`

```text
 12 files changed, 2284 insertions(+), 1 deletion(-)
  - production: 5 files, +387 lines
  - tests:      5 files, +1720 lines (incl. defense fixture)
  - evidence:   1 file,    +5 lines (G0-baseline.txt)
  - docs:       1 file,  +182 lines (this receipt)
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
| `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` (W3b contract test) | 7 | 0 | 0 | new — Timestamps/WithEnv bodies lowered 1:1 at compile; empty-body rejection for body-bearing variants |
| `Lfc2BodyExecutionPolicyFitnessTest` (B10 carry-over) | 10 | 0 | 0 | unchanged |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest` (B10 carry-over) | 16 | 0 | 0 | `BodyChildLoopInventory == EXPECTED`, debt == pin |
| `Lfc2DurableAggregateIdentityFitnessTest` (B10 carry-over) | 5 | 0 | 0 | unchanged |
| `Lfc2DurableCoordinatorScopeFitnessTest` | 4 | 0 | 0 | unchanged |
| `Lfc2RegistryFamilyFitnessTest` | 3 | 0 | 0 | unchanged |
| **TOTAL** | **137** | **0** | **0** | W1–W3 = 130, W3b added 7 |

### 4.1 Family invariants (post-B11 + W3b)

```text
BodyChildLoopInventory.discovered       = (loopDefinitions = 1, credentialAcquisitions = 1)
BodyChildLoopInventory.EXPECTED         = (1, 1)
PinnedConcreteBodyRoutingDebt.value.total = 0
PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING = 18   (UNCHANGED, IMMUTABLE)
ConcreteBodyRoutingDebt.scan(coordinator) = empty
BodyExecutionPolicy vocabulary         = no "core.*" / "example.*" literal
BodyExecutionContextDerivation         = no "core.*" / "example.*" literal, no when(stepKey)
CanonicalDurableRunCoordinator         = no when(stepKey), no when(name), no "core.*" / "example.*" in `when`, no example.projectX / example.withEnv literal anywhere

W3b re-assertion (post-cherry-pick, no regression):
ConcreteBodyRoutingDebt.value.total = 0        (unchanged)
HISTORICAL_CEILING                  = 18       (unchanged, IMMUTABLE)
BodyChildLoopInventory              = (1, 1)   (unchanged)
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
| `blockStepNode` lowers `StepSpec.Timestamps(steps)` to a `BlockStepNode` whose body contains every child node 1:1 (no silent `emptyList()`) | dynamic | `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest > Timestamps compiles to BlockStepNode with non-empty body` |
| `blockStepNode` lowers `StepSpec.WithEnv(overrides, steps)` to a `BlockStepNode` whose body contains every child node 1:1 | dynamic | `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest > WithEnv compiles to BlockStepNode with non-empty body` |
| Closed-family invariant: every body-bearing `StepSpec` variant in the factory is lowered 1:1; adding a new body-bearing variant without wiring the inner-when fails the test | reflection + dynamic | `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest > closed family: outer dispatch routes exactly the body-bearing variants the factory covers` + per-variant compile cases |

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

## 8. STOP / NEXT

The slice is **CERTIFICATION-READY after W3b**:

- **Fresh gate:** `/tmp/lane-e/verify-new-head.sh <NEW_B11_HEAD>` -> PASS in equivalent form
  (HF0 + HF1 + LFC-2 family + W3b contract test + debt 0 / ceiling 18 assertions); see
  Section 9 for the actual evidence captured on `cf541f40`.
- **Round gate:** `./gradlew -p v2 check --continue` reproduces the EXACT pre-existing red
  set observed at base `a66d7f6c` (worktree method, fresh runs on each side); see Section 9.7
  for the pre-existing red set table. W3b introduces zero new failures and does not widen the
  pre-existing red set.
- **Verify matrix:** 137 tests, 0 failures, 0 errors (Section 4) — B11 + W3b surfaces, all
  OUTSIDE the pre-existing red set, all green.
- **Remaining phases:**
  - sddk-verify (full evidence review + final architecture review)
  - sddk-debt-verify (architecture/debt ceiling re-assertion on `cf541f40`)
  - sddk-release (publication to origin, tag, PR)
  - Archive (CAS delta spec sync)

Hard STOP conditions remain in force from the cycle preamble; nothing in B11+W3b trips them.

## 9. Companion fix (W3b) — compiler body exhaustiveness

### 9.1 Classification

`PRE_EXISTING_BUT_B11_ACCEPTANCE_RELEVANT` — **blocking for B11 certification**.

### 9.2 Defect

`DslCompiledPipelineCompiler.blockStepNode()` extracted body children via an inner
`when` that handled `CatchError` / `WarnError` / `TimeoutBlock` / `RetryBlock` / `Dir` /
`WithCredentialsBlock` but fell through to `else -> emptyList()` for `StepSpec.Timestamps`
and `StepSpec.WithEnv`. Both types carry `steps: List<StepSpec>`. On the real DSL compile
path:

```text
timestamps { sh("...") }   -> BlockStepNode(pluginStepId=core.timestamps, body=[])
withEnv    { sh("...") }   -> BlockStepNode(pluginStepId=core.withEnv,    body=[])
```

The block entered and exited (events fired, overlay pushed) while zero user steps were
compiled or executed. Silent, fail-open child loss on 2 of the 3 B11 surface blocks.

### 9.3 Pre-existing at base

Present at `a66d7f6c28ea5aa5e9c0c81b3a55f5d4ac06fb12`
(`DslCompiledPipelineCompiler.kt` SHA-256
`f4d19b681c377687b48719e57873550928fcac6bd16fd3d8216d21cb5ee1544b`, defective lines 252–259).
No B11 commit (`7d29fb2f..e48096a7`) touched this file. B11's HF1 runtime tests exercised
the coordinator projection seam with hand-built `BlockStepNode` inputs and therefore could
not see a compile-time drop one seam earlier.

### 9.4 Why it blocked B11

The slice is named "context blocks" (`dir`, `withEnv`, `timestamps`); the defect breaks 2 of
those 3 forms end-to-end. Decision rule applied:

```text
blocker  iff  pre-existing defect
              AND intersects the acceptance surface of the slice being certified
```

### 9.5 Fix

Companion commit `cf541f40eca5f4ae9a7ff6d6176735d5557ab0a7`
(`fix(lfc2-b11): compile WithEnv and Timestamps body in blockStepNode`, author `lane-c-b11`).
Lane C authored the fix on branch `fix/lfc2-b11-context-block-compiler` (parent
`e48096a7`, worktree `pipeline-b11-compiler`); the orchestrator fast-forward cherry-picked
the commit onto `refactor/lfc2-e1-b11-context-blocks`. Adds the two missing
`Timestamps` / `WithEnv` cases to the inner-when and a closed-family invariant test
(`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`).

### 9.6 Scope firewall

The W3b commit does NOT touch the B11 critical path:
`BodyInvoker`, `BodyExecutionContextDerivation`, `BodyExecutionPolicy`,
`CanonicalDurableRunCoordinator`, `CanonicalBodyInvokerAdapter`, and
`CanonicalRuntimeCapabilityAccess` are byte-identical between `e48096a7` and `cf541f40`.
Only `DslCompiledPipelineCompiler.kt` (+2 lines, missing cases) and the new architecture
test (316 lines) are touched. B11's five original commits are byte-identical (parentage
preserved, no rebase).

### 9.7 Evidence

`docs/v2/07-uat/evidence/b11-w1/W3b-compiler-fix.txt` captures:
- defect summary + pre-existing proof (Section 1, 2)
- the fix diff (Section 3)
- three real `.pipeline.kts` smoke runs with observable files (`/tmp/b11c-withenv.txt`,
  `/tmp/b11c-timestamps.txt`, `/tmp/b11c-nested-{pwd,env,ts}.txt`) and JSONL
  `StepStarted`/`StepFinished` events for the inner children (Section 4)
- per-suite test counts and XML SHA-256 digests, canary-verified on `cf541f40` (Section 5)
- invariants (debt, ceiling, body-child-loop) re-asserted post-W3b (Section 6)
- pre-existing red set table with fresh base-vs-head evidence for every failure
  (Section 7)
- scope-firewall diff stat (Section 8)

### 9.8 Pre-existing red set (worktree method, fresh runs on both sides)

| Pre-existing failure | Base `a66d7f6c` | `cf541f40` (W3b) | B11/W3b introduced? |
| --- | --- | --- | --- |
| `PipelineDslSealedHierarchyTest > sealed_hierarchy_is_exhaustive_with_28_kinds` | FAIL | FAIL | no |
| `Lfc0GlobalStateFitnessTest > production code does not access the controller user directory property` (line 44) | FAIL | FAIL | no |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test > family classifier routes core sh through Registry` (line 115) | FAIL | FAIL | no |
| `CoreLegacyStepMetadataResolverTest > ...` (line 42, `IllegalArgumentException`) | FAIL | FAIL | no |
| `RegistryStepMetadataResolverTest > a remaining legacy core key delegates to the legacy core authority even when absent from the registry` (line 77) | FAIL | FAIL | no |
| `ScriptTextEscaperTest` (3 tests, lines 178, 204, ...) | FAIL | FAIL | no |
| `WithCredentialsCompileIntegrationTest` (4 tests, lines 60, 84, 111, 184) | FAIL | FAIL | no |
| `CompatibilityCorpusTest` (2 tests, incl. `allCorpusFixturesAreDiscoverable`, line 154) | FAIL | FAIL | no |
| `UatCompat001CorpusSmokeRunTest` (2 tests, lines 50, 93) | FAIL | FAIL | no |
| `UatLocal005CheckoutGitTest > SC-007 poll detects changed SHA and emits GitPollChanged` (line 714) | FAIL | FAIL | no |
| `UatLocal005CorpusUntouchedTest > CP-002 corpus has exactly 13 valid fixture files` (line 119) | FAIL | FAIL | no |
| `UatLocal007SandboxProfileTest > SB-S-008 parallel branches have isolated cwds` (line 551) | FAIL | FAIL | no |
| `UatLocal007SandboxProfileTest > SB-S-010 resume with profile change none-to-local re-attaches` (line 719) | FAIL | FAIL | no |
| `UatLocal008CredentialsTest > CR-BD-027 CredentialUsed per use` | FAIL | FAIL | no |
| `UatLocal008CredentialsTest > UAT-L8-CP-001 original 4 corpus files byte-identical to cycle base` | FAIL | FAIL | no |
| `UatLocal009TopStepsTest > CR-U9-001 writeFile readFile round-trip with sha256 event` (line 142) | FAIL | FAIL | no |
| `UatLocal009TopStepsTest > CR-U9-002 fileExists true after writeFile` | FAIL | FAIL | no |
| `UatLocal009TopStepsTest > CR-U9-003 writeFile atomic write succeeds` (line 201) | FAIL | FAIL | no |
| `UatLocal009TopStepsTest > CR-U9-004 writeFile cross-fs fallback documented in atomicallyMoved` (line 229) | FAIL | FAIL | no |

Total pre-existing failures: 26 (1 + 1 + 1 + 1 + 1 + 3 + 4 + 2 + 2 + 1 + 1 + 2 + 2 + 4).
W3b does not introduce any new failure and does not widen the pre-existing red set. The B11
+ W3b verify matrix (137 tests, Section 4) sits ENTIRELY outside this pre-existing red set,
all green.

These are the EXACT pre-existing failures documented in the cycle preamble (the four
UAT-L008/L009 failures are explicitly out-of-RET-RY-D scope and known to predate B11 by
multiple cycles).

Round-gate reproduction method: fresh `./gradlew -p v2 check --continue` on each side, JUnit
XML inspection (rule 25 canary: every B11 XML was deleted before its Gradle stage, then
verified to regenerate).

### 9.9 Follow-up (NOT in B11; tracked separately as a compiler-2 finding)

After B11+W3b release, open a separate `bug` / `compiler-2` finding:
- replace the inner `else -> emptyList()` with an exhaustive `when` over the closed block
  family so the NEXT block form cannot compile with a dropped body (fail-closed at compile
  time, per AGENTS.md STRICT TYPED DESIGN rule 1);
- audit `rewriteWorkflowControl` inner-scope handling for the same silent-drop shape;
- consider a corpus fixture that compiles every block form with a child and asserts the
  child node count end-to-end.

WU4 is documentation-only / evidence-only; compiler hardening is out of scope.
