# RP034-A — Execution-location characterisation freeze

**Status:** CLOSED (characterisation only; zero production change in this slice)
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin` (replan sequence 19)
**Tree pinned:** `aeae1e4c35a83da78889a7fb3eeeb787058a5506`
**Base:** `a5406ae7cd1bc7cac3ecce02f64a29ed8c33d284`

## Scope

Freeze pre-migration execution-location reality and inventory every consumer of
the overlapping path authorities. No production behaviour is introduced here.

## Consumer inventory (production, observed)

| Authority | Declaration | Consumers |
|---|---|---|
| `WorkspaceResolver` | `durable/WorkspaceResolver.kt:25` | `ArchiveArtifactsOperations.kt:151`, `PublishHtmlOperationsAdapter.kt:66`, `StashOperationsAdapter.kt:46,185`, `WorkspaceOperations.kt:87,121,145`, `CleanWsOperationsAdapter.kt:57`, `DeleteDirOperationsAdapter.kt:57`, `ShExecution.kt:430`, `CanonicalDurableRunCoordinator.kt:694` |
| `workspaceBase: Path?` | threaded into all of the above | same set; the null/non-null choice is the implicit CLI-mode switch |
| `ShOptions.workspaceRoot` | `pipeline-step-sdk/runtime/.../ShOptions.kt:33` | shell launch, file Steps |
| `ShOptions.workingDirectory: Path?` | same | `ShExecution.kt:280`, `CanonicalRuntimeCapabilityAccess.kt:143`, `CanonicalStructuralDecisions.kt:414`, `CanonicalDurableRunCoordinator.kt:1701` |
| `WORKSPACE_IDENTITY_CAPABILITY` | `Capabilities.kt:92` | `CorePwdStep.kt:178,209`, `CoreStepRegistryFactory.kt:99`, `CanonicalRuntimeCapabilityAccess.kt:144` |
| `ProjectCheckoutDetector` | `durable/ProjectCheckoutDetector.kt:44` | `DeleteDirOperationsAdapter.kt:80`, `CleanWsOperationsAdapter.kt:72` |

### The two conflations this WU removes

**1. Root and cwd collapsed** — `CanonicalRuntimeCapabilityAccess.kt:143`:

```kotlin
val effectiveWorkspaceRoot = context.shOptions.workingDirectory ?: context.shOptions.workspaceRoot
```

`WorkspaceIdentity` cannot distinguish the workspace root from the current
directory. RP034-C replaces this with a typed `ExecutionLocation`.

**2. Ownership inferred, not typed** — `DeleteDirOperationsAdapter.kt:79` and
`CleanWsOperationsAdapter.kt:72`:

```kotlin
protectWorkspaceRoot = workspaceBase != null &&
    ProjectCheckoutDetector.isProjectCheckout(workspaceBase)
```

Ownership is derived from a VCS marker, and the guard only engages when
`--workspace` was passed explicitly. RP034-G replaces it with a typed lease.

## Observed characterisation (all on the real CLI process boundary)

`WorkspaceExecutionLocationCharacterizationTest` — **4/4 green**, 24.35s.

| Case | Kind | Observed |
|---|---|---|
| no `--workspace` | **inverting RED** | shell CWD is under `<controlRoot>/workspace/<stage>-<n>`, not the invocation directory |
| relative project path, no flag | **inverting RED** | `cat build-marker.txt` → `UNREACHABLE` (the WU-RP-034 defect, reproduced) |
| explicit `--workspace` | stable | shell CWD is exactly that workspace |
| workspace vs control root | stable | workspace root does not collapse into the control root (INV-WS-004) |

The two inverting REDs are expected to fail after RP034-H. They are marked in
the test so a future reader cannot mistake that inversion for a regression.

## Regression baseline restored by this slice

The premature local-first flip at `8e838e6d` was reverted in `aeae1e4c`.
Measured on that tree:

| Suite | Result |
|---|---|
| `UatLocal007SandboxProfileTest` | **14/14** green |
| `UatLocal003ReturnStdoutTest` | **2/2** green |
| `WorkspaceExecutionLocationCharacterizationTest` | **4/4** green |
| `:pipeline-application:detekt` | BUILD SUCCESSFUL |
| `:pipeline-application:compileTestKotlin` | exit 0 |

Before the revert, five of these were failing at `a5406ae7`:
`SB-S-001`, `UAT-L7-TC-004`, `SB-S-006`, `SB-S-008`, and the `returnStdout` case.

## Gate A

- [x] Reality frozen against the real CLI boundary
- [x] Consumers inventoried with file:line
- [x] Both correct behaviour and current drift demonstrated
- [x] REDs verified discriminating (not vacuously green)
- [x] Zero production change beyond the safety revert

## Remaining

RP034-B..I untouched. Six of the eleven L5 failures recorded at `a5406ae7` are
not re-identified here: the originating log
`/home/rubentxu/.jcode/scratch/s2d-l5-check.log` was deleted and cannot be
recovered. They must be re-observed by a fresh full check.
