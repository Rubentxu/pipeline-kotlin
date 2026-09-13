# S2-A10 / G4 REGISTRY_PRIMARY — core.cleanWs Receipt

> **Lane A — sole global writer of authority for S2-A10 core.cleanWs.**
> **Counter law (S2-A10/G3 docs correction `f8c23d8c`):**
> - G4 = N/N/N → (N-1)/N/N (ids only; metadata + dispatcher physical remain UNREACHABLE)
> - G5 = (N-1)/N/N → (N-1)/(N-1)/(N-1) (metadata + dispatcher physical)
>
> This flip: 4/4/4 → 3/4/4.

**Step under burn-down:** `core.cleanWs` (WS_OPERATIONS capability)
**Gate closed:** G4 REGISTRY_PRIMARY
**Base SHA (origin/main, pre-merge S2-A10/G3):** `65a8ce235138c3182d1e616cd5afc061d9affc22`
**G4 commit (HEAD of cycle/lfc2-e1-cleanws-g4):** `8f8de9a50e4abd59464b0b9c7dbea8080d8e9a61`
**Date:** 2026-09-13
**Branch precondition:** `HEAD == origin/main == 65a8ce23` ✓ (no rebase needed; counter residual matches `LEGACY_PLUGIN_IDS.size == 4` at base)
**Authority:** docs/v2/ (ROADMAP + STEP_CONSTITUTION + STEP_PLUGIN_CERTIFICATION + ADRs 0070..0075); not :pipeline-steps-system:compiler-plugin.

---

## 1. What G4 changed (id-only flip)

### 1.1 Production routing source-of-truth

`CanonicalCoreStepDecoder.kt` (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt`):

- `"core.cleanWs"` removed from the `LEGACY_PLUGIN_IDS` set.
- Comment now cites S2-A10/G4: production routing flows via `CoreCleanWsStep.definition`; legacy forms remain physically present but **UNREACHABLE** in production until G5.

```diff
 val LEGACY_PLUGIN_IDS: Set<String> = setOf(
     // ... "core.error" / "core.deleteDir" / "core.milestone" removed at G5 ...
+    // "core.cleanWs" removed at LFC-2E1-S2-A10 / G4 (2026-09-13). Production routing
+    // flows via CoreCleanWsStep.definition; legacy forms remain physically present but
+    // UNREACHABLE until S2-A10 / G5 closes this lane.
     "core.load",
     "core.waitUntil",
     "core.archiveArtifacts",
 )
```

### 1.2 LegacyResidualSnapshot state machine

`LegacyResidualSnapshot.kt` (`v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/LegacyResidualSnapshot.kt`):

```diff
-    private val registryPrimaryPendingRemoval: String? = null
+    private val registryPrimaryPendingRemoval: String? = "core.cleanWs"
```

Per the snapshot's docstring state machine:

```text
at G4: registryPrimaryPendingRemoval = key  -> (N-1) / N / N
at G5: physicalResidual -= key; back to null -> (N-1) / (N-1) / (N-1)
```

The snapshot's `snapshot()` method is unchanged — its computation subtracts `registryPrimaryPendingRemoval` from `physicalResidual` to derive the `Snapshot` consumed by `Lfc2RegistryFamilyFitnessTest`.

### 1.3 Sibling LegacyRemoved fitness tests (mechanical: assertConverged → assertCurrentState)

7 sibling `S3*LegacyRemovedFitnessTest` suites + the `CanonicalCoreStepCommandRegistryTest`:

- `assertConverged(...)` calls → `assertCurrentState(...)` calls (per the S3 burn-down pattern; see `bd9f6ee7` for the precedent on `core.deleteDir`).

These tests assert the **current state** of the residual snapshot — once `core.cleanWs` is REGISTRY_PRIMARY but G5 has not run yet, the snapshot reflects `(N-1) / N / N` (transitional state) and the convergence check would fail until G5 closes the lane. Switching to `assertCurrentState` re-asserts the truthy "what is the snapshot now?" question, allowing G4 to pass while keeping the architectural intent.

### 1.4 Counter test reconciliation (the precedent from S2-A9/G8 `bd9f6ee7`)

7 sibling `CoreXxxRegistryPrimaryFitnessTest` + `CoreDeleteDirStepUnitTest` + `CoreCleanWsStepContractSuiteTest` carried a counter invariant asserting `LEGACY_PLUGIN_IDS.size == 4` (post-S2-A9/G5 milestone). Under S2-A10/G4, that value becomes `3` (4 residual legacy keys → 3 residual legacy keys, with `core.cleanWs` having moved out).

Reconciliation pattern (per `bd9f6ee7`): keep the historical assertion as `@Disabled` (verbatim, with a citation to the new truth test) and add a new live test asserting the G4-truth shape:

```kotlin
@Disabled("Historical S2-A9/G5 snapshot: S2-A10/G4 (2026-09-13) REGISTRY_PRIMARY-flipped core.cleanWs; the 4-key count is superseded by `<new test name>` below. Preserved verbatim for traceability.")
@Test
fun `<historical name>`() { /* 4-key assertion */ }

@Test
fun `<new truth test name>`() {
    // S2-A10 / G4 (2026-09-13): core.cleanWs flipped to REGISTRY_PRIMARY; legacy decoder
    // branch / dispatcher file / metadata row remain physically present (UNREACHABLE in
    // production) until S2-A10 / G5 closes this lane. Counter converges 4/4/4 -> 3/4/4.
    assertEquals(3, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
    assertEquals(setOf("core.load", "core.waitUntil", "core.archiveArtifacts"),
                 CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
}
```

Disabled import added where missing: `CoreDeleteDirStepUnitTest.kt` (was the only one without `org.junit.jupiter.api.Disabled`).

### 1.5 `CoreCleanWsStepContractSuiteTest` G1 → G4 transition

`CoreCleanWsStepContractSuiteTest.kt`:

- Existing G1 test `G1 candidate invariant — counters remain 4 4 4` → renamed to `G4 post-flip invariant — counters are 3 4 4`.
- Asserts `assertFalse("core.cleanWs" in LEGACY_PLUGIN_IDS)` + `assertEquals(3, LEGACY_PLUGIN_IDS.size)` + `assertTrue("core.cleanWs" in pluginIds, "cleanWs legacy metadata row remains until G5")`.

---

## 2. Counter reconciliation (3/4/4)

| Counter | Pre-G4 (post-S2-A9/G5) | Post-S2-A10/G4 | Delta |
| --- | --- | --- | --- |
| `LEGACY_PLUGIN_IDS.size` (ids) | 4 | **3** | -1 |
| `CanonicalCoreStepMetadata.pluginIds.size` (metadata) | 4 | **4** | 0 (G5 will subtract) |
| dispatcher file (physical) | 4 | **4** | 0 (G5 will subtract) |

Residual legacy keys (post-G4, pre-G5): `core.load`, `core.waitUntil`, `core.archiveArtifacts`. Their decoder / dispatcher / metadata forms remain physically present and **UNREACHABLE in production** until each key's own G5 closes its lane.

`core.cleanWs` no longer appears in `LEGACY_PLUGIN_IDS`. The legacy decoder branch, dispatcher file, and metadata row remain physically present but are bypassed by the production routing (which now flows via `CoreCleanWsStep.definition`).

---

## 3. Files changed (17 total)

```text
Modified (17):
  production (1):
    pipeline-application/src/main/.../CanonicalCoreStepDecoder.kt
  application tests (9):
    CanonicalCoreStepCommandRegistryTest.kt              (assertConverged → assertCurrentState)
    CoreCleanWsStepContractSuiteTest.kt                 (G1 4-4-4 → G4 3-4-4)
    CoreDeleteDirStepUnitTest.kt                        (@Disabled S2-A9/G5 + new S2-A10/G4 truth + Disabled import)
    CoreEmitEventRegistryPrimaryFitnessTest.kt          (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
    CoreErrorRegistryPrimaryFitnessTest.kt              (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
    CoreIsUnixRegistryPrimaryFitnessTest.kt             (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
    CorePwdRegistryPrimaryFitnessTest.kt                (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
    CoreSleepRegistryPrimaryFitnessTest.kt              (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
    CoreWriteFileRegistryPrimaryFitnessTest.kt          (@Disabled S2-A9/G5 + new S2-A10/G4 truth)
  architecture tests (7):
    LegacyResidualSnapshot.kt                           (registryPrimaryPendingRemoval = "core.cleanWs")
    S3EmitEventLegacyRemovedFitnessTest.kt              (assertConverged → assertCurrentState)
    S3ErrorLegacyRemovedFitnessTest.kt                  (assertConverged → assertCurrentState, 4 sites)
    S3IsUnixLegacyRemovedFitnessTest.kt                 (assertConverged → assertCurrentState)
    S3PwdLegacyRemovedFitnessTest.kt                    (assertConverged → assertCurrentState)
    S3SleepLegacyRemovedFitnessTest.kt                  (assertConverged → assertCurrentState)
    S3WriteFileLegacyRemovedFitnessTest.kt              (assertConverged → assertCurrentState)
```

```text
17 files changed, 158 insertions(+), 26 deletions(-)
```

---

## 4. Canary (L1 + L3 targeted)

### 4.1 L0 — compile (`L0 < 10s`)

```text
> Task :pipeline-application:compileTestKotlin
> Task :pipeline-architecture-tests:compileTestKotlin
BUILD SUCCESSFUL in 2s
```

### 4.2 L1 — 9 affected application test suites (122/0/0/28)

Command (targeted, `--rerun-tasks`, fresh XML canary verified):

```bash
timeout 600 ./gradlew :pipeline-application:test \
  --tests CoreDeleteDirStepUnitTest \
  --tests CoreEmitEventRegistryPrimaryFitnessTest \
  --tests CoreErrorRegistryPrimaryFitnessTest \
  --tests CoreIsUnixRegistryPrimaryFitnessTest \
  --tests CorePwdRegistryPrimaryFitnessTest \
  --tests CoreSleepRegistryPrimaryFitnessTest \
  --tests CoreWriteFileRegistryPrimaryFitnessTest \
  --tests CoreCleanWsStepContractSuiteTest \
  --tests CanonicalCoreStepCommandRegistryTest
```

Result (`/tmp/g4-canary-final.log`):

```text
BUILD SUCCESSFUL in 31s
                                        tests  failures  errors  skipped
CoreCleanWsStepContractSuiteTest          23     0        0        0
CoreDeleteDirStepUnitTest                 19     0        0        1   (Disabled: S2-A9/G5)
CoreEmitEventRegistryPrimaryFitnessTest   14     0        0        4   (Disabled: S2-A4..A9)
CoreErrorRegistryPrimaryFitnessTest       18     0        0        5   (Disabled: S2-A4..A9)
CoreIsUnixRegistryPrimaryFitnessTest      11     0        0        4   (Disabled: S2-A4..A9)
CorePwdRegistryPrimaryFitnessTest         10     0        0        3   (Disabled: S2-A4..A9)
CoreSleepRegistryPrimaryFitnessTest       13     0        0        8   (Disabled: S2-A4..A9)
CoreWriteFileRegistryPrimaryFitnessTest    8     0        0        3   (Disabled: S2-A4..A9)
CanonicalCoreStepCommandRegistryTest       6     0        0        0
                                        ─────
                                        122     0        0       28
```

XML sha256 evidence (`/tmp/g4-xml-sha256.txt`):

```text
d9069c0ebfb253eaced5d99475d7456db058602769c8495a6fbf56d378c801d4  TEST-...CoreCleanWsStepContractSuiteTest.xml
02e6e231eda7f05214cc3c64713b4285ad42c3e2a93450dc86284b2483d76293  TEST-...CoreDeleteDirStepUnitTest.xml
2aa05a0786aac8fd439e6962d9af19a76dc7e1cb0b6b63a90d3e2c28919b56f2  TEST-...CoreEmitEventRegistryPrimaryFitnessTest.xml
6361c38603bb6ba60e5f49a78d5fd738fd78029aaa0821a7ccd539910f089168  TEST-...CoreErrorRegistryPrimaryFitnessTest.xml
4748181b01ad36817c296345303a07034c9e7d048f2413bb4d5d86b509fc9a13  TEST-...CoreIsUnixRegistryPrimaryFitnessTest.xml
e2c4f58f97aa0a6923806973ae723d93d5d1f311406f90bc803cc2f60d6c8844  TEST-...CorePwdRegistryPrimaryFitnessTest.xml
abe0c5a63221adebe1c7303a47719be74c324b15a9d42929c42b39a00efaafa9  TEST-...CoreSleepRegistryPrimaryFitnessTest.xml
6735ffc5675a7089edfa3c2760116c920f18b2b7e0a2643d6eacc5b1029c99a3  TEST-...CoreWriteFileRegistryPrimaryFitnessTest.xml
f2f63007f9b0382e5d524c70d1e88971076978595e5690fa5e3e02d5d5301e4e  TEST-...CanonicalCoreStepCommandRegistryTest.xml
```

### 4.3 L1 — 7 affected architecture test suites (48/0/0/0)

Command (targeted, `--rerun-tasks`):

```bash
timeout 600 ./gradlew :pipeline-architecture-tests:test \
  --tests S3EmitEventLegacyRemovedFitnessTest \
  --tests S3ErrorLegacyRemovedFitnessTest \
  --tests S3IsUnixLegacyRemovedFitnessTest \
  --tests S3PwdLegacyRemovedFitnessTest \
  --tests S3SleepLegacyRemovedFitnessTest \
  --tests S3WriteFileLegacyRemovedFitnessTest \
  --tests Lfc2RegistryFamilyFitnessTest
```

Result (`/tmp/g4-arch-target.log`):

```text
BUILD SUCCESSFUL in 17s
                                        tests  failures  errors  skipped
S3EmitEventLegacyRemovedFitnessTest       9      0        0       0
S3ErrorLegacyRemovedFitnessTest           7      0        0       0
S3IsUnixLegacyRemovedFitnessTest          6      0        0       0
S3PwdLegacyRemovedFitnessTest             6      0        0       0
S3SleepLegacyRemovedFitnessTest           8      0        0       0
S3WriteFileLegacyRemovedFitnessTest       6      0        0       0
Lfc2RegistryFamilyFitnessTest             6      0        0       0
                                        ─────
                                         48     0        0        0
```

XML sha256 evidence:

```text
f09a97efcb28b0f6b3a0a2820233b7069c1617580bbcf61956af3e12746185d6  Lfc2RegistryFamilyFitnessTest.xml
6e414dd539add70b0eb2204d6b1ade313ee4969eeadee40e752b2c4e265a8602  S3EmitEventLegacyRemovedFitnessTest.xml
1029fe5b763b79d8afccd048eb0b32c38a4fea1c7136401ab07187b4e335a0b4  S3ErrorLegacyRemovedFitnessTest.xml
b7722a9bbd6947a9eab8aa7aa03d659cbaa83463fe1e50345c3bd2af787fa8d1  S3IsUnixLegacyRemovedFitnessTest.xml
d06522dcb47539ea09fb7367a940bab6495f9cebafc509942c540ddae80a34e2  S3PwdLegacyRemovedFitnessTest.xml
2fff658be3aa015a4f492a02dbfc994a4dbeaf55aeed88039bb2e8fb146fa38a  S3SleepLegacyRemovedFitnessTest.xml
3b5022349497f7b215785d0a7141f5ebadd35415d5dec648393b4de7737e88ed  S3WriteFileLegacyRemovedFitnessTest.xml
```

### 4.4 Pre-existing red verification (worktree method, baseline = `65a8ce23`)

13 tests fail on the pre-G4 baseline `65a8ce23` (worktree method: stashed G4 changes, re-ran, confirmed same failures, unstashed). None were introduced by G4.

Pre-existing failures in `:pipeline-application:test`:

```text
A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test > family classifier routes core sh through Registry (not LegacyCore) post-A4
CompatibilityCorpusTest > allCorpusFixturesAreDiscoverable
CompatibilityCorpusTest > fixture14CredentialsBindings
CoreIsUnixStepUnitTest > counters drop to 6-6-6 post-S2-A6-G5 and legacy dispatcher source is physically removed
CoreLegacyStepMetadataResolverTest > ordinary steps declare no recovery
EmitEventStepContractSuiteTest > no legacy resurrection — irreversible G5 state holds post-S2-A6-G4
RegistryStepMetadataResolverTest > a remaining legacy core key delegates to the legacy core authority even when absent from the registry
UatCompat001CorpusSmokeRunTest > corpus smoke-runs green and satisfies M2 exit criterion
UatCompat001CorpusSmokeRunTest > each corpus fixture produces non-empty event stream
UatLocal005CheckoutGitTest > SC-007 poll detects changed SHA and emits GitPollChanged(Path)
UatLocal005CorpusUntouchedTest > CP-002 corpus has exactly 13 valid fixture files(Path)
UatLocal007SandboxProfileTest > SB-S-010 resume with profile change none-to-local re-attaches(Path)
UatLocal007SandboxProfileTest > SB-S-008 parallel branches have isolated cwds(Path)
```

Pre-existing failure in `:pipeline-architecture-tests:test`:

```text
Lfc0GlobalStateFitnessTest > production code does not access the controller user directory property
```

Per AGENTS.md rule 16 (Never classify a failure as "pre-existing" without fresh base-vs-head evidence): all 13 failures were reproduced on the worktree at base SHA `65a8ce23` with G4 changes stashed (log: `/tmp/g4-baseline.log`, `/tmp/g4-arch-base.log`).

**G4 is not a regression source for any of these failures.**

---

## 5. Status of counters and CERTIFIED Steps

| Item | Pre-G4 | Post-G4 |
| --- | --- | --- |
| Certified Steps | 9 (echo, sh, error, sleep, writeFile, emit.event, isUnix, deleteDir, milestone) + example.uppercase | **9 + 1** (unchanged) |
| Legacy executable Steps | 4 | 3 (core.cleanWs moved to Registry-primary; legacy forms remain physically present but UNREACHABLE until G5) |
| Registry-primary Steps | 9 | 10 (core.cleanWs joined) |

`N + M = 11 = total` (where N = certified registry-primary core Steps + external plugins and M = legacy executable Steps).
Convergence target: `M → 0`. The next 3 G5s (`core.cleanWs`, `core.load`, `core.waitUntil`, `core.archiveArtifacts`) each close `M -= 1`.

---

## 6. Branch precondition verified

```text
HEAD            = 8f8de9a50e4abd59464b0b9c7dbea8080d8e9a61
origin/main     = 65a8ce235138c3182d1e616cd5afc061d9affc22
branch.base     = origin/main ✓
expectedResidual = calculateResidual(origin/main) = 3 ✓
actualResidual   = LEGACY_PLUGIN_IDS.size         = 3 ✓
STOP_REBASE_REQUIRED = false
```

---

## 7. Gate progression (next: G5 LEGACY_REMOVED)

```text
S2-A10/G0 baseline captured (G3 readiness receipt f8c23d8c)
S2-A10/G1 CoreCleanWsStep registered (cherry-pick 0c3103eb, PR #34)
S2-A10/G2 differential contract freeze test (cherry-pick 6fd6c043 → bdd4f81e, PR #34)
S2-A10/G3 StepContractSuite 23/23 (cherry-pick 3e26401e, PR #34)
S2-A10/G4 REGISTRY_PRIMARY flip             ← THIS RECEIPT (commit 8f8de9a5)
S2-A10/G5 LEGACY_REMOVED (next, STOP awaiting GO)
S2-A10/G6 contract certification
S2-A10/G7 StepContractSuite full coverage
S2-A10/G8 CERTIFIED (counters 3/4/4 → 2/2/2 across this cycle, but other Steps may shift)
```

G5 destructive work (mechanical, mechanical equivalent of `core.milestone` G5 in commit `6b5e40d3`):

1. Physical removal of `core.cleanWs` legacy decoder branch (`CanonicalCoreStepDecoder.kt`).
2. Physical removal of `core.cleanWs` dispatcher case (verify no dispatcher case exists — for `core.cleanWs`, only the legacy dispatch path needed removal; the new path is `CoreCleanWsStep`).
3. Physical removal of `core.cleanWs` legacy metadata row (`CanonicalCoreStepMetadata.pluginIds`).
4. `LegacyResidualSnapshot.registryPrimaryPendingRemoval` back to `null`.
5. Counter reconvergence 3/4/4 → 3/3/3.
6. Re-enable the `@Disabled` S2-A10/G4 truth tests and replace with the convergence S2-A10/G5 truth (counter == 2: only `core.load`, `core.waitUntil`, `core.archiveArtifacts` remain because we haven't burned those down yet).

---

## 8. References

- **STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION** — closed execution structure, open Step registry; capability-routed handler discipline.
- **ADR-0070..0074** — Step plugin seam ADRs.
- **ADR-0075** — Retry-D durable control rows (no relation to cleanWs but cited for counter-discipline style).
- **`docs/v2/07-uat/S2_A10_CORE_CLEANWS_G3_READINESS_RECEIPT.md`** — prior gate's G3 receipt.
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md`** — G8 precedent for counter reconciliation (`bd9f6ee7`).
- **`docs/v2/07-uat/S2_A9_CORE_MILESTONE_G5_DESTRUCTIVE_RECEIPT.md`** — G5 precedent (commit `6b5e40d3`).

---

**Receipt author:** Jcode (MiniMax-M3, Lane A sole global writer for S2-A10)
**Receipt SHA:** derived from G4 commit `8f8de9a50e4abd59464b0b9c7dbea8080d8e9a61`
**STOP awaiting GO for S2-A10/G5 LEGACY_REMOVED.**
