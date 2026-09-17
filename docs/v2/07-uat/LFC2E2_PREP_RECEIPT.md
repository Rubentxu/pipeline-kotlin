# LFC-2E2-PREP — Preparation Receipt for the First Official Plugin

**Cycle:** LFC-2E2-PREP
**Date:** 2026-09-17
**Authority:** `docs/v2/00-governance/CORE_STEP_ADMISSION.md` + `docs/v2/00-governance/PLUGIN_AUTHORING.md` + `docs/v2/status/step-certification.yaml` (v4)
**Status:** **CLOSED — frozen plugin authoring surface ready for first OFFICIAL_PLUGIN**

---

## Purpose

LFC-2E2-PREP is the **preparation slice** before the first official plugin ships.
It establishes:

1. The data shapes plugins use to declare identity (`ResourceKind.STEP_DEFINITION`, `ResourceKind.PLUGIN_RELEASE`).
2. The typed manifest a plugin ships (`PluginManifest` + `PluginFamily` + `PluginFamilyShape`).
3. The typed admission policy a plugin passes to be admitted (`PluginAdmissionPolicy`).
4. The fitness test that mechanically enforces all the above (`Lfc2E2PrepFitnessTest`).

The actual first plugin (readJSON + writeJSON + sha256) ships in **FASE 6**.

---

## What was added (C1..C10)

### C1 — ResourceKind.STEP_DEFINITION + ResourceRefs.stepDefinition(...)
- File: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/identity/ResourceKind.kt`
- File: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/identity/ResourceRefs.kt`
- Identifies a registered Step family — typed identity for `StepDefinition`.

### C2 — ResourceKind.PLUGIN_RELEASE + PluginReleaseRef
- File: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginManifest.kt`
- Identifies a single plugin release — typed identity for `PluginManifest.releaseRef()`.

### C3/C5/C6 — PluginManifest typed ADT with invariants
- File: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginManifest.kt`
- Immutable, typed declaration of a plugin release.
- Invariants: non-empty families OR contributors; no duplicate StepKeys.
- `declaredCapabilities: Set<StepCapability>` (computed, O(families)).
- `stepKeys: List<PluginStepId>` (computed, in declaration order).

### C4 — PluginFamilyCapabilityFamily sealed ADT
- File: `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/plugin/PluginAdmissionPolicy.kt`
- Closed ADT classifying families by capability shape: `Pure`, `WorkspaceUser`, `ProcessExecutor`, `EventEmitter`, `CredentialConsumer`, `ArtifactProducer`, `Mixed`.
- `PluginFamilyCapabilityFamily.classify(family)` is a pure decision (no I/O).

### C5 — PluginAdmissionPolicy sealed ADT
- `Ready(declaredCapabilities)` — admission granted.
- `MissingCapabilities(missing, declared)` — runtime cannot supply declared capabilities.
- `Malformed(reason)` — manifest is structurally invalid.
- Each case carries its own typed payload; the caller cannot accidentally collapse them.

### C7 — No duplicate StepKeys within a manifest
- Enforced at `PluginManifest.init {}` (fail-closed at construction).
- Pure `manifestStepKeysAreUnique(manifest)` exposed for tests/validators.

### C8 — Events carry STEP_DEFINITION ResourceRef
- (Deferred to FASE 6 — plugin event metadata. The `ResourceKind.STEP_DEFINITION`
  + `ResourceRefs.stepDefinition` are ready, but no event currently emits a
  STEP_DEFINITION ref. FASE 6 will wire this for the first plugin.)

### C9 — PolicyReadinessFitness
- File: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2E2PrepFitnessTest.kt`
- 10/10 GREEN. One assertion per C1..C10 invariant.

### C10 — Core backwards-compatibility
- Lfc2E2PrepFitnessTest C8/C10 assertion: production coordinator, dispatcher, and
  compiler do NOT reference any E2-PREP type. E2-PREP is pure pipeline-domain
  additions; the production core adopts plugins via the existing
  `StepDefinitionContributor` SPI, not by direct reference.
- Lfc2E2PrepFitnessTest C10 assertion: `LEGACY_PLUGIN_IDS` remains empty
  (no regression of LFC-2E0 closure).

---

## Test results at LFC-2E2-PREP close

| Suite | Result | Notes |
|---|---|---|
| `Lfc2E2PrepFitnessTest` | 10/10 GREEN | C1..C10 mechanical enforcement |
| `Lfc2UniversalCoreFreezeFitnessTest` | 6/6 GREEN | No regression of universal-core freeze |
| `Lfc2E0GlobalClosureFitnessTest` | 12/12 GREEN | No regression of LFC-2E0 closure |
| `Lfc2ZeroLegacyResidualFitnessTest` | 7/7 GREEN | No regression of LFC-2E0 zero-residual |
| `PluginManifestTest` (domain) | 20/20 GREEN | PluginManifest + PluginAdmissionPolicy + ResourceKind + ResourceRefs |

All 55 tests across the four suites + one domain suite pass.

---

## Cross-doc consistency

| Document | Path | Status |
|---|---|---|
| Authoring guide | `docs/v2/00-governance/PLUGIN_AUTHORING.md` | ACTIVE |
| Core admission record | `docs/v2/00-governance/CORE_STEP_ADMISSION.md` | ACTIVE (frozen) |
| Step Certification Matrix | `docs/v2/status/step-certification.yaml` (v4) | CONSISTENT |
| Universal-core freeze receipt | `docs/v2/07-uat/LFC2E1_UNIVERSAL_CORE_FREEZE_RECEIPT.md` | CLOSED |
| LFC-2E0 closure (prior) | `docs/v2/07-uat/LFC2E0_FINAL_CLOSURE_RECEIPT.md` | CLOSED |
| LFC-2E0 evidence manifest (prior) | `docs/v2/07-uat/LFC2E0_EVIDENCE_MANIFEST.md` | CLOSED |

---

## What is frozen (the LFC-2E2-PREP invariant)

Going forward, the following CANNOT change without an ADR + new Milestone:

1. **No production code change** to coordinator, dispatcher, compiler for plugin
   admission. Plugins register via the existing `StepDefinitionContributor` SPI;
   the E2-PREP types (`PluginManifest`, `PluginAdmissionPolicy`,
   `PluginFamilyCapabilityFamily`, `PluginReleaseRef`) live in `pipeline-domain`
   and are adopted by adapters at composition time.
2. **`PluginAdmissionPolicy` is closed**. Adding a new case (e.g.
   `IncompatibleRuntime` for a future capability-version mismatch) requires an
   ADR.
3. **`PluginFamilyCapabilityFamily` is closed**. Adding a new case (e.g.
   `NetworkUser` for a future network-aware plugin) requires an ADR.
4. **`PluginFamilyShape` is closed**. The five cases (`ATOMIC`, `BODY_BEARING`,
   `SCOPED`, `RETRYING`, `PARALLEL`) are the closed family a plugin can declare.
5. **`PluginManifest` invariants** are enforced at construction: no duplicate
   StepKeys; non-empty families OR contributors; valid coordinates and versions.
6. **`LEGACY_PLUGIN_IDS` remains empty** (zero regression of LFC-2E0 closure).
7. **No Cedar**, no remote protocol, no Kubernetes, no Jenkins adapter — all
   explicitly out of scope for LFC-2.

---

## Counter convergence at LFC-2E2-PREP close

| Counter | Value |
|---|---|
| Production Step keys | 15 (unchanged from LFC-2E1) |
| CORE_PRIMITIVE | 11 |
| ORCHESTRATION_BLOCK_STEP | 1 |
| CORE_PRIMITIVE_STOPPED_G7 | 2 |
| CORE_PRIMITIVE_REJECTED | 1 |
| EXTERNAL_PLUGIN_REFERENCE | 1 |
| Legacy residual (N+M) | 0+0+0 |
| OFFICIAL_PLUGIN | 0 (FASE 6 will ship the first) |
| ADMITTED total | 16 |
| E2-PREP fitness coverage | C1..C10 (10/10 GREEN) |
| Lfc2UniversalCoreFreezeFitnessTest | 6/6 GREEN (no regression) |

**LFC-2E2-PREP CLOSED — ready for FASE 6 (first OFFICIAL_PLUGIN).**
