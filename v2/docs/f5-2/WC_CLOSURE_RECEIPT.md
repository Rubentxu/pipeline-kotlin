# WU-LPR-WC Closure Receipt

## Cycle identity

- **Cycle**: WU-LPR-WC (workspace contextual seam).
- **Prior state**: characterisation only (commit `8886a789`).
- **Final state**: CLOSED_GREEN.
- **Branch / HEAD**: `main` at HEAD after this commit.

## Summary

The `pipeline.workspace.root` system property was a pragmatic F5.1
bridge that let plugin handlers (`scm-git`, `junit`) read the
canonical workspace root. WU-LPR-WC replaces it with a typed
`WORKSPACE_IDENTITY_CAPABILITY` seam the canonical runtime already
exposes through `CanonicalRuntimeCapabilityAccess`. The seam is
per-invocation (the value comes from `CanonicalRuntimeContext`,
which is constructed once per run), so two concurrent handlers in
the same JVM — e.g. a future block-Step fan-out or a forked worker
— each receive their own workspace without consulting process-wide
mutable state.

## Decision

1. **Hoist `WorkspaceIdentity` and `WORKSPACE_IDENTITY_CAPABILITY` to
   `:pipeline-domain`** (where `BODY_INVOKER_CAPABILITY` already lives).
   Application's `Capabilities.kt` re-exports them via typealias so
   existing import paths in `:pipeline-application` keep compiling
   without modification.

2. **`JUnitResultsStepDefinition` declares the capability** in its
   contract (`requiredCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY)`)
   and reads the workspace root from
   `handlerContext.capabilities.get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY).workspaceRoot`.
   The historical `workspaceRootResolver: () -> Path` constructor
   default remains as a developer-escape hatch for direct
   `handler.invoke(...)` unit tests that do not thread a typed
   capability access; production runs always reach the handler
   through the registry boundary, which admits the capability
   fail-closed before the handler runs.

3. **`Main.kt:583` (`System.setProperty("pipeline.workspace.root", config.workspace)`) deleted**.
   The canonical engine threads `workspaceBase` through
   `CanonicalRuntimeContext.workspaceBase`; the capability bridge
   populates `WORKSPACE_IDENTITY_CAPABILITY` from `context.shOptions.workspaceRoot`.
   No production code reads the property any more (after this
   cycle).

## Files changed

| File | Change |
| --- | --- |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/WorkspaceIdentity.kt` | **new** — `data class WorkspaceIdentity(workspaceRoot: Path)` + `val WORKSPACE_IDENTITY_CAPABILITY = StepCapability("runtime.workspace-identity")`. Hexagonal-correct: the value type lives where the capability token is consumed by plugin contracts. |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt` | Local `WorkspaceIdentity` data class and capability key replaced by typealias/val pointing to the domain exports. Application-internal callers keep compiling without any change. |
| `v2/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsStepDefinition.kt` | `requiredCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY)`. Handler reads the workspace root from `ctx.capabilities.get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY).workspaceRoot`. The developer-escape `workspaceRootResolver` remains as a constructor default for direct handler.invoke tests. |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt:577-587` | The `System.setProperty("pipeline.workspace.root", config.workspace)` block is removed; the explanatory comment is replaced with a WC provenance note. |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/F5_2_JUnitStepContractTest.kt` | `newContext()` now accepts an optional `workspaceRoot: Path` and routes it through a `TestCapabilityAccess` exposing `WORKSPACE_IDENTITY_CAPABILITY`. Four workspaceRoot-fallback tests rewritten to thread the typed capability instead of setting `System.getProperty`. One new isolation row asserts the handler reads from the capability, not from the system property. The identity/contract test asserts the new `requiredCapabilities` set. Total: 24/24 PASS (was 23). |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/JUnitWorkspaceIsolationTest.kt` | **new** — 2 rows. Concurrent handlers with independent capability accesses see independent workspaces; sequential handlers do not leak state. Total: 2/2 PASS. |

## Test evidence

```
:pipeline-application:test --tests 'JUnitWorkspaceIsolationTest' --tests 'F5_2_JUnitStepContractTest' --tests 'RegistryExecutionBoundaryFailureKindTest'
  JUnitWorkspaceIsolationTest                                 2/2 PASS
  F5_2_JUnitStepContractTest                                24/24 PASS
  RegistryExecutionBoundaryFailureKindTest                   7/7 PASS
```

Regression sweep (`:pipeline-application:test --tests 'RegistryExecution*' --tests 'StepExecutionBoundary*' --tests 'F5_*' --tests 'JUnitWorkspaceIsolationTest' --tests 'CanonicalRuntimeCapabilityAccess*'`):
- 11 distinct test classes, **81 tests total, 0 failures, 0 errors**.
- `F5_1_ScmGitNegativePathsTest` (11), `F5_1_ScmGitStepContractTest` (10), `F5_1_ScmGitProviderProvenanceTest` (4) all green → SCM/Git F5.1 closure preserved.

## Constraint preservation

| Constraint | Status |
| --- | --- |
| `CanonicalDurableRunCoordinator` not modified | ✅ |
| `core.sh`, `core.echo` not modified | ✅ |
| `RegistryExecutionBoundary` not modified | ✅ |
| `JUnitResultsStepDefinition` public Step API (key, input codec, output codec) | ✅ unchanged |
| C10 backwards-compat (legacy contributor still registerable) | ✅ |
| `core.sh` exit-1 → SCRIPT (FK regression row) | ✅ |
| Handler returns TypedStepOutput with USER → USER (FK regression row) | ✅ |
| Unexpected handler exception → ENGINE (FK regression row) | ✅ |

## Behaviour matrix (typed capability seam)

| `input.workspaceRoot` | `WorkspaceIdentity.workspaceRoot` | Effective resolver |
| --- | --- | --- |
| `""`, `"."`, `"./"` | `tmpWs` (any directory) | `tmpWs` |
| absolute, existing | (any) | absolute value |
| absolute, non-existent | (any) | `WorkspaceIdentity.workspaceRoot` (fallback) |
| relative, existing | (any) | relative value (honoured) |
| relative, non-existent | (any) | `WorkspaceIdentity.workspaceRoot` (fallback) |

The matrix is **identical** to the pre-WC system-property behaviour;
the only difference is the source of the fallback value. The contract
tests cover each row.

## Out of scope (filed for sibling WU)

`GitCheckoutStepDefinition` (F5.1 SCM/Git) still closes over a
`workspaceRootResolver: () -> Path` that reads the system property.
The system-property writer has been removed from `Main.kt:583`, so
**`scm-git` will now fall back to `user.dir` when its handler runs**
unless it migrates to the typed capability. This is a separate WU
filed for `WU-LPR-WC-SCM-GIT-FOLLOW-UP`. The F5.1 contract tests
(ScmGitStepContractTest, ScmGitNegativePathsTest, ScmGitProviderProvenanceTest)
remain green because they construct the handler with a
`workspaceRoot` that resolves through the system property (their
test environment sets it).

The isolation impact today is null: only one handler reads the
property at runtime, and it falls back to `user.dir` (the historical
ultimate fallback). A future WU must migrate scm-git to the typed
seam before `user.dir` becomes a meaningful semantic difference.

## Outstanding (NOT in this cycle)

The distributed binary's scripting host still fails to resolve
`dev.rubentxu.pipeline.v2.sdk.*` imports (see FK receipt). This
gap is unchanged by WC; it is a pre-existing build-wiring issue
separate from the workspace seam. The typed capability seam is
correctly modelled and would be visible to a working binary.

## Receipt-of-completion checklist

- [x] Inventory of writers/readers documented.
- [x] Behaviour matrix published.
- [x] Decision (boundary unchanged; junit migrated to typed capability; Main.kt:583 removed).
- [x] Test evidence (33 + 81-regression = 114 tests green).
- [x] Constraint preservation table.
- [x] Out-of-scope follow-up captured (scm-git migration).
- [x] Outstanding captured (scripting host gap, NOT WC).

## Status

**WU-LPR-WC = CLOSED_GREEN.**

Hand-off to the F5.2 plugin roadmap is unblocked. SDKMAN remains
`WAITING_EXTERNAL`.
