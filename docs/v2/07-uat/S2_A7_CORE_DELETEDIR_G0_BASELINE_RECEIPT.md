# S2-A7 / G0 — `core.deleteDir` Baseline Characterization

**Cycle:** `lfc2-e1-s2-a7-core-deletedir`
**Branch:** `cycle/lfc2-e1-delete-dir`
**Base:** `f5e4003d` (LFC-2E1 harness — LegacyResidualSnapshot: single residual authority)
**Date:** 2026-09-12T14:33Z
**Status:** CHARACTERIZATION COMPLETE — STOP after G0; G1..G3 follow.

## 1. Scope

`core.deleteDir` step burn-down from LEGACY_PLUGIN_IDS to REGISTRY_PRIMARY.
This G0 captures the pre-existing failure baseline at base SHA `f5e4003d`.

## 2. Pre-existing failures (documented, NOT acted on)

Per AGENTS.md §G0 baseline requirements, the following pre-existing failures
are documented as valid failures at this base SHA:

| ID | Failure | Evidence | Status |
|----|---------|----------|--------|
| UAT-PRE-1 | `fixture14CredentialsBindings` | UatLocal007.SB-S-008/010 | VALID FAILURE (out of scope for deleteDir) |
| UAT-PRE-2 | `UatLocal008` credential events | INC-002 carry-forward | VALID FAILURE (unrelated to deleteDir) |
| UAT-PRE-3 | `UatLocal009` archiveArtifacts | INC-002 carry-forward | VALID FAILURE (unrelated to deleteDir) |
| UAT-PRE-4 | `fixture15` error handling | Out of scope for this slice | VALID FAILURE (unrelated to deleteDir) |

## 3. Additional pre-existing failure discovered at this base SHA

| ID | Failure | Evidence | Status |
|----|---------|----------|--------|
| ARCH-PRE-1 | `Lfc0GlobalStateFitnessTest: production code does not access the controller user directory property` | `Capabilities.kt:76` has `System.getProperty("user.dir")` which is forbidden by Lfc0 | VALID FAILURE (unrelated to deleteDir, but present in codebase) |

Details of ARCH-PRE-1:
```
failure message: Forbidden production global-state access: 
  [Finding(file=Capabilities.kt, line=76, 
    token=System.getProperty("user.dir", 
      excerpt=* reading `System.getProperty("user.dir")` or `Paths.get(".")` directly.)]
```

## 4. `core.deleteDir` legacy state at base SHA

```kotlin
// CanonicalCoreStepDecoder.kt:209
private const val DELETE_DIR_PLUGIN_ID = "core.deleteDir"

// CanonicalCoreStepCommand.kt:138-142
data class DeleteDir(
    val path: String = ".",
) : CanonicalCoreStepCommand {
    override val pluginId = "core.deleteDir"
}

// LEGACY_PLUGIN_IDS still contains "core.deleteDir"
val LEGACY_PLUGIN_IDS: Set<String> = setOf(
    "core.milestone",
    "core.deleteDir",  // <-- target for this burn-down
    "core.cleanWs",
    "core.load",
    "core.waitUntil",
    "core.archiveArtifacts",
)

// CanonicalCoreStepMetadata.kt:22
"core.deleteDir" to StepMetadata(
    setOf(Effect.WRITES_WORKSPACE), 
    ReplayPolicy.MEMOIZED
),
```

## 5. Legacy dispatcher structure

**Source:** `CanonicalDeleteDirNodeDispatcher.kt`
**Lines:** 72 lines total

Key behavior:
1. Uses `DeleteDirExecutor` from `pipeline-step-sdk:files`
2. Resolves workspace via `WorkspaceResolver(controlDirRoot)`
3. Emits `DirDeleted` event with path, deletedCount, sha256
4. Returns `StepOutcome.Success`

## 6. Legacy decoder structure

**Source:** `CanonicalCoreStepDecoder.kt:239-245`

```kotlin
DELETE_DIR_PLUGIN_ID -> {
    require(payload.requiredString("kind") == "deleteDir") {
        "Payload kind must be 'deleteDir' for '${node.id.value}'"
    }
    CanonicalCoreStepCommand.DeleteDir(
        path = payload["path"]?.jsonPrimitive?.contentOrNull ?: ".",
    )
}
```

## 7. deleteDir semantics (PATH_B legacy truth)

| Property | Value |
|----------|-------|
| StepKey | `core.deleteDir` |
| Input payload | `{"kind":"deleteDir","path":"."}` |
| Default path | `"."` (current stage workspace) |
| Effect | `WRITES_WORKSPACE` |
| ReplayPolicy | `MEMOIZED` |
| Idempotent | Yes (checks `.deleted` marker) |
| Event emitted | `DirDeleted` |
| Outcome | `StepOutcome.Success` |

## 8. Events consumed by deleteDir

None. `deleteDir` does not require capabilities — it works directly with
`WorkspaceResolver` and `DeleteDirExecutor`.

## 9. Baseline test evidence

| Suite | Tests | Failures | Errors | Timestamp | SHA-256 |
|-------|-------|----------|--------|-----------|---------|
| `UatLocal011WorkflowControlTest` | 13 | 0 | 0 | 2026-09-12T14:31:12.351Z | `3c1c1aa49f3a1604d3d08bbcd1c3ff3bccee5fb6613fdee7b92dabb4c5f0aa05` |

**Test SC-011-04** (deleteDir emits DirDeleted with sha256): **PASSED**

```bash
timeout 600 ./v2/gradlew -p v2 :pipeline-application:test \
  --tests 'UatLocal011WorkflowControlTest.SC-011-04*'
# BUILD SUCCESSFUL in 10s
```

## 10. Architecture fitness status

| Test | Result |
|------|--------|
| `Lfc2RegistryFamilyFitnessTest` | PASSED (67 tests) |
| `S3EchoLegacyRemovedFitnessTest` | PASSED |
| `S3EmitEventLegacyRemovedFitnessTest` | PASSED |
| `S3ErrorLegacyRemovedFitnessTest` | PASSED |
| `S3IsUnixLegacyRemovedFitnessTest` | PASSED |
| `S3PwdLegacyRemovedFitnessTest` | PASSED |
| `S3SleepLegacyRemovedFitnessTest` | PASSED |
| `S3WriteFileLegacyRemovedFitnessTest` | PASSED |
| `Lfc0GlobalStateFitnessTest` | FAILED (1/2) — pre-existing ARCH-PRE-1 |

## 11. Counters at base SHA

```
LEGACY_PLUGIN_IDS.size = 6
deleteDir in LEGACY_PLUGIN_IDS = true
deleteDir REGISTERED = false
deleteDir REGISTRY_PRIMARY = false
deleteDir LEGACY_UNREACHABLE = false
deleteDir LEGACY_REMOVED = false
deleteDir CERTIFIED = false
```

## 12. Observations for G1 design

1. **deleteDir requires NO capabilities** — the legacy dispatcher works directly
   with `WorkspaceResolver` and `DeleteDirExecutor`. This is simpler than
   `core.pwd` which required `WORKSPACE_IDENTITY_CAPABILITY`.

2. **MEMOIZED replay** — the executor checks for `.deleted` marker to provide
   idempotency. This is the same pattern used by other workspace cleanup steps.

3. **Path safety** — `DeleteDirExecutor` enforces workspace-root guard.
   The registry candidate must preserve this invariant.

4. **Effects: WRITES_WORKSPACE** — deleteDir modifies the filesystem.
   This distinguishes it from `core.pwd` (READ_ONLY) and `core.isUnix` (READ_ONLY).

5. **Event: DirDeleted** — emitted with path, deletedCount, sha256.
   The handler must emit this event.

## 13. G1 design proposal (subject to G1 confirmation)

```kotlin
// CoreDeleteDirStep.kt design

data class DeleteDirInput(val path: String = ".")

data class DeleteDirOutput(
    val path: String,
    val deletedCount: Int,
    val sha256: String,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

val descriptor = StepDescriptor(
    stepId = "core.deleteDir",
    name = "deleteDir",
    configRef = "",
    executionLocation = ExecutionLocation.AGENT,
    effects = listOf(Effect.WRITES_WORKSPACE),
    replayPolicy = ReplayPolicy.MEMOIZED,
)

// deleteDir requires no capabilities — it uses WorkspaceResolver directly
val definition = StepDefinition(...)
```

## 14. Stop

G0 ends here. The next slice (`S2-A7 / G1` — registry candidate) requires
explicit GO after review of this receipt.

Re-entry path for `S2-A7 / G1`:
```text
G1 → CoreDeleteDirStep.kt (input/output codec + handler)
   + DeleteDirExecutor already exists in pipeline-step-sdk:files
   + WorkspaceResolver wired via capability or direct dependency
   + CoreStepRegistryFactory.registerInto(this)
G2 → differential contract freeze
G3 → readiness assessment + contract suite
STOP (no G4 in this wave)
```
