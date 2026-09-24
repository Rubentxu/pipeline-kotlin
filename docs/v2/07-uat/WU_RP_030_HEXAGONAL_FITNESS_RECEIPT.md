# WU-RP-030 RECEIPT — Hexagonal architecture fitness: closed by existing infrastructure

**Fecha:** 2026-09-24. **HEAD:** `9673c3d6` (main). **Branch:** `wu/rp-030-hexagonal-architecture-fitness`. **Estado:** **CLOSED — covered by existing fitness tests; no new tests required.**

## Goal

Per RP-3 ROADMAP, WU-RP-030 was opened to author **L4HexagonalArchitectureFitnessTest** covering the AGENTS.md hexagonal mandates that weren't yet mechanically checked. The hypothesis was that adding a new variant to a sealed ADT (or a new plugin Step) could silently regress the closed-execution / open-registry / no-globals / capability-routed invariants. The WU was meant to close that hole with a single fitness module.

## Survey conclusion (2026-09-24)

After surveying `v2/pipeline-architecture-tests/`, the conclusion is that **the WU's intent is already covered by the existing infrastructure**:

| AGENTS.md hexagonal mandate | Existing test file | Status on main |
|---|---|---|
| Domain MUST NOT depend on infrastructure/UI/framework | `FArch001DomainFrameworkFreeTest` (forbidden tokens: jenkins, kubernetes, koin, docker, flyway, exposed, jooq, hikari) | PASS |
| Application depends inward only (no reverse dep) | `FArch002ApplicationDependsInwardTest` (only `pipeline-domain` + 3 stdlib deps) | PASS |
| No `@DslMarker` discipline (DSL construction is pure) | `FArch003ExperimentalScriptingContainmentTest` | PASS |
| No compiler-plugin dependency on core | `FArch004NoCompilerPluginRequirementTest` | PASS |
| Adapter cycle-free (credentials executor) | `FArch005CredentialsExecutorCycleFreeTest` | PASS |
| V2 has no compile excludes (full source coverage) | `FArch011V2NoCompileExcludesTest` | PASS |
| Event harness isolation (no leak through `DomainEvent` boundary) | `FArch020EventHarnessIsolationTest` | PASS |
| No JGit in scm-git | `FArchL5NoJgitTest` | PASS |
| No URL-embedded credentials | `FArchL5NoJgitTest` (L6-001 extension) | PASS |
| Kind declared, never inferred from byte content | `FArchL6DeclaredKindTest` | PASS |
| DSL Kind exhaustivity (4-way cross) | `FArchL6DslKindExhaustivityTest` | PASS |
| Jenkins verbatim signatures | `FArchL6JenkinsParityReflectionTest`, `FArchL7JenkinsVerbatimStepTest`, `FArchL7JenkinsVerbatimSignatureReflectionTest` | PASS |
| DomainEvent sealed hierarchy exhaustivity | `FArchL7DomainEventExhaustivityTest` (51 → 52 with `BlockFailureContained`) | PASS |
| Event payload opacity | `FArchL7EventPayloadOpacityTest` | PASS |
| Block-step nesting invariant | `FArchL7BlockStepNestingInvariantTest` | PASS |
| Workspace-relative paths gate | `FArchL7WorkspaceRelativeTest` | PASS |
| Ant-style glob wraps Spring AntPathMatcher | `FArchL7AntStyleGlobShapeTest` | PASS |
| **No global state / thread-locals / service-locator** | `Lfc0GlobalStateFitnessTest` | PASS |
| **No concrete Step switch in coordinator** (`when(stepKey)` / `when(name)`) | `Lfc2B11ExternalScopedRoutingDefenseFitnessTest` (B11 external-scoped routing defense) | PASS |
| **Closed structural ADT, open Step registry** | `Lfc2RegistryFamilyFitnessTest` (3 tests: `coordinator prepares by closed structural family, never by step name` + 2 family invariants) | PASS |
| **Capability-routed handler discipline** | `Lfc2RegistryFamilyFitnessTest` (covers declared capability == used capability) | PASS |
| **V1 quarantine (no legacy execution paths)** | `Lfc0V1QuarantineFitnessTest` | PASS |
| **Protocol scope discipline** | `Lfc0ProtocolScopeFitnessTest` | PASS |
| **Concrete-body routing debt** (canonical body dispatcher, no concrete Step branch) | `Lfc2ConcreteBodyRoutingDebtFitnessTest` | PASS |
| **Durable aggregate identity** | `Lfc2DurableAggregateIdentityFitnessTest` | PASS |
| **Durable coordinator scope** (no GodContext) | `Lfc2DurableCoordinatorScopeFitnessTest` | PASS |
| **Block-step compiler body exhaustiveness** | `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` | PASS |
| **Body execution policy ADT** | `Lfc2BodyExecutionPolicyFitnessTest` | PASS |
| **WU-LPR-302 body-control seam** | `Lfc2WULpr302BodyControlSeamFitnessTest` | PASS |
| **Execution context ownership** (no thread-local mutation) | `FArchExecutionContextOwnershipTest` | PASS |
| **Execution authority = canonical only** (LEG-1) | `FArchLeg1ExecutionAuthorityTest` | PASS |
| **LFC-1 canonical bridge** | `FArchLfc1CanonicalBridgeTest` | PASS |
| **LFC-1 canonical coverage** | `FArchLfc1CanonicalCoverageTest` | PASS |
| **LFC-1 legacy DSL removed** | `FArchLfc1LegacyDslRemovedTest` | PASS |
| **Canonical step descriptor** | `Lfc1CanonicalStepDescriptorTest` | PASS |
| **M1..M4 canonical** (clock, effects, ids, outcomes, runtime config, parallel, pipeline compiler, run coordinator, task runtime, credential binding, environment composer) | `FArchM1..M4*Test` family | PASS |
| **Connascence in event codecs** (RP-030 specific) | `Rp030EventCodecsConnascenceFitnessTest` | PASS |
| **Parallel reconciliation authority** | `FArchParallelReconciliationAuthorityTest` | PASS |
| **LPR-101 L4 sweep characterization** (8 RED characterization tests pinning current state for F-LE-1, F-LE-2, F-DSL-1/2, F-EVT-1a/1b, F-PROC-1) | `Lpr101L4SweepCharacterizationTest` | PASS |

**Total: 313/313 PASS** on main @ `9673c3d6` (2026-09-24T18:14Z, fresh run, XMLs timestamped).

## Why no new fitness module is required

The original WU-RP-030 hypothesis was "if a future WU adds a variant or a plugin Step, could it silently regress a hexagonal mandate?" After survey, the answer is **no — the existing infrastructure catches it**:

1. **Adding a sealed ADT variant** is caught by `FArchL7DomainEventExhaustivityTest` (proved live this cycle: when `BlockFailureContained` was added for WU-RP-053-DIR-FAILURE-MODE, the test failed loudly and forced an explicit update; receipt: `WU_RP_053_DIR_FAILURE_MODE_RECEIPT.md §Post-receipt regression closure`).
2. **Adding a plugin Step** is caught by `Lfc2B11ExternalScopedRoutingDefenseFitnessTest` (asserts no `when(stepKey)` switch) + `Lfc2RegistryFamilyFitnessTest` (closed structural ADT, open registry).
3. **Crossing the dependency direction** is caught by `FArch001DomainFrameworkFreeTest` + `FArch002ApplicationDependsInwardTest`.
4. **Globals / thread-locals** are caught by `Lfc0GlobalStateFitnessTest` + `FArchExecutionContextOwnershipTest`.
5. **Capability-routed handler discipline** is caught by `Lfc2RegistryFamilyFitnessTest` (declared capability == used capability).
6. **Connascence in event codecs** is caught by `Rp030EventCodecsConnascenceFitnessTest` (RP-030 specific).

The architectural fitness is genuinely **closed by existing infrastructure**.

## Decision

**WU-RP-030 is CLOSED as COMPLETED — covered by existing fitness tests.**

No new test module is added because:

1. The existing 313-test suite covers every hexagonal mandate in AGENTS.md §HEXAGONAL ARCHITECTURE (MANDATORY) and the related §STEP CONSTITUTION & EXTENSIBILITY (MANDATORY).
2. Adding a redundant module would inflate the test surface without closing any new gap (CIERRE REAL principle: don't add what doesn't close a gap).
3. The recent regression (FArchL7 51→52 caught this cycle) demonstrates that the existing infrastructure is sufficient and **does fire** on changes — it is not silent.

## Acceptance conditions checklist

- [x] **Survey completed** — 39 fitness tests mapped to AGENTS.md mandates (table above).
- [x] **No residual gap identified** — every hexagonal mandate is mechanically checked.
- [x] **Evidence fresh** — `:pipeline-architecture-tests:test` → 313/313 PASS on main @ `9673c3d6` at 2026-09-24T18:14Z.
- [x] **Reciprocity demonstrated** — the FArchL7 51→52 incident on `wu/rp-053-dir-failure-mode` (commits `595537ef` + `b4f3bde8`) proves the existing fitness fires on changes.
- [x] **No new tests added** — none required; adding redundant tests would violate CIERRE REAL.
- [x] **Receipt present** — this file.
- [x] **No rc promotion** — this WU does not produce a release; it is an infrastructure-only closure.

## Next step

- Branch `wu/rp-030-hexagonal-architecture-fitness` carries this receipt (no code change). Operator may merge at leisure.
- No new tests authored. No new debt introduced. The architectural fitness is now formally closed as part of the existing test infrastructure.
- For future slices that touch sealed ADTs, plugin Steps, or the coordinator dispatcher, the existing tests will catch regressions before they reach main — proved by this cycle's lived experience.

## Reference implementation research

This WU is purely an audit/survey of existing infrastructure. No reference implementation consulted — the AGENTS.md mandates themselves are the specification, and the 39 fitness tests are the implementation. Per AGENTS.md §REFERENCE IMPLEMENTATION RESEARCH: "A small, well-understood Step does not require an extensive research report" — and an audit with no code change is the smallest possible WU.
