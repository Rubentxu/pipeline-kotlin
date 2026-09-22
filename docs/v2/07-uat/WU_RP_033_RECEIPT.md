# WU-RP-033 RECEIPT — External Step with body through the generic registry (RP-3 exit criterion)

**Status:** IMPLEMENTED + VERIFIED (local round gate; CI pending on push)
**Base SHA:** 04180921 (WU-RP-032 r2)
**Roadmap:** RP-3 — external Step with/without body via the same generic registry,
with admission/replay/typed errors and ZERO new concrete branching in the coordinator.

## What was delivered

### 1. DSL/IR: `StepSpec.RegistryBlockSpec` + `registryBlock(...)` (S1)

- `PipelineDsl.kt`:
  - New closed variant `StepSpec.RegistryBlockSpec(stepKey, schemaVersion, encodedInput, body: List<StepSpec>, retry, timeoutMillis)`.
    Sealed IR grows by ONE intentional, generic form (not a concrete Step).
  - `StageScope.registryBlock(stepKey, encodedInput, retry, timeoutMillis) { body }` builder:
    captures body children declaratively (data construction only; no I/O, no runtime values),
    mirrors the certified `registryStep(...)` primitive shape.
  - retry-modifier `when` extended to cover `RegistryBlockSpec` (exhaustive, no `else`).
- `DslCompiledPipelineCompiler.kt`: generic lowering `RegistryBlockSpec → BlockStepNode`
  with recursive body compile; verbatim pluginStepId; no concrete-key knowledge anywhere.
- `BlockStepFlattener.kt` (pipeline-step-sdk/api): flatten case for `RegistryBlockSpec`
  children so characterization corpus tooling sees the body steps.

### 2. Spine: composed body-policy authority (S2)

- `CanonicalDurableRunCoordinator.kt`:
  - Ctor param `injectedBodyPolicyResolver: BodyPolicyResolver? = null` APPENDED LAST
    (positional call-site compatibility preserved).
  - Class-body `bodyPolicyResolver` composes: **open registry first**
    (`RegistryBodyPolicyResolver` over the injected `StepRegistry` via a read-only
    `NoopStepRegistry` adapter; support = SCOPED_SEQUENTIAL_RETRYING)
    → on `BodyPolicyRejection.UnknownStep` falls back to the canonical core table
    (`StepDescriptorRegistry.standard()`).
  - No `when(stepKey)` / no per-Step branch introduced; resolution reads declared
    descriptor metadata exactly like core Steps do.
  - Fail-closed: unknown/incoherent block keys are rejected before any effect
    (proven by proof-test case (b): typed Failure, ZERO journal rows).

### 3. Proof: `ExternalStepWithBodyRegistryProofTest` (pipeline-application)

Three rows, all GREEN:
- **(a) with-body external Step** — external plugin key with descriptor declaring
  `StepBody.Declared` (`BodyExecutionOwner.CANONICAL_ENGINE`, `Sequential`,
  `introduces = null`) runs its echo child through the canonical durable engine.
- **(b) fail-closed unknown block key** — `registryBlock` with an unregistered key
  fails closed with a typed error and writes NO durable rows.
- **(c) parity** — atomic `registryStep` (opaque node) still succeeds unchanged.

### 4. DSL lowering tests: `RegistryBlockDslLoweringTest` (pipeline-scripting-api)

Three rows GREEN: key/encodedInput/body captured verbatim; declaration-order body
capture; atomic `registryStep` remains a leaf (`RegistryStepSpec`).

### 5. Sealed-hierarchy pin updated

`PipelineDslSealedHierarchyTest`: 30 → 31 variants (intentional closed-IR growth,
documented in the test KDoc as WU-RP-033).

## Architecture compliance

- `RegistryBlockSpec` is a GENERIC structural form (like `RegistryStepSpec`), not a
  concrete Step subtype. Plugin authors lower their typed DSL extension to it.
- Zero concrete-key branching: compiler, flattener, coordinator and flattener all
  treat the form generically.
- Fail-closed admission preserved (ADR-0069 invariant); body-policy resolution is
  descriptor-driven (StepDescriptor as pre-decode metadata authority).
- No legacy path re-opened; no public contract changed (no ADR required:
  additive DSL surface + optional ctor param).

## Verification evidence (fresh, this SHA-tree, pre-commit worktree)

| Suite | Result |
|---|---|
| `ExternalStepWithBodyRegistryProofTest` | 3/3 PASS |
| `RegistryBlockDslLoweringTest` | 3/3 PASS |
| `:pipeline-scripting-api:test` (full) | 50/50 PASS |
| `:pipeline-application:test` (full) | 1726/1726 PASS |
| `:pipeline-domain:test` (full) | 554/554 PASS |
| `:pipeline-step-sdk:api:test` (full) | 393/393 PASS |
| `:pipeline-architecture-tests:test` (full) | 313/313 PASS (allow-list extended for the new generic RegistryBlockSpec lowering, documented in-test) |
| Round gate `./gradlew check` (incremental) | BUILD SUCCESSFUL (first pass caught the arch fitness pin, fixed, re-run green) |

## Closure check (reference implementation rule)

```text
Reference implementation consulted: core block Steps (retry/timeout/durable body
                                    machinery, ADR-0073) + certified registryStep
                                    pattern (LB-02 / example.uppercase)
Behaviour adopted:                  body-owning external Step lowers to generic
                                    RegistryBlockSpec; body execution re-enters the
                                    canonical engine via declared BodyExecutionPolicy
Intentional deviations:             none (policy space limited to declared support
                                    SCOPED_SEQUENTIAL_RETRYING; broader policies are
                                    future work behind the same seam)
Security implications reviewed:     admission is fail-closed before effects; unknown
                                    keys write no durable state (proven)
Tests demonstrating the contract:   ExternalStepWithBodyRegistryProofTest,
                                    RegistryBlockDslLoweringTest,
                                    PipelineDslSealedHierarchyTest (31 variants)
```

## Remaining for RP-3 exit

- Declaration-vs-execution audit (descriptor policy actually honored at dispatch) —
  partially covered by proof row (a); full audit pending.
- Cancellation semantics for in-flight body children — separate WU.
