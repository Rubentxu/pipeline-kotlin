# WU-LPR-WC — Characterisation

## Inventory of `pipeline.workspace.root` consumers and writers

### Writer (single site)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt:583`

```kotlin
if (config.workspace != null) {
    System.setProperty("pipeline.workspace.root", config.workspace)
}
```

Order: the property is published immediately before the canonical
durable run begins. There is exactly one writer. The decision to use
`System.setProperty` was a deliberate F5.1 / F5.2 mitigation: the
canonical engine passes `workspaceBase` to core Steps through
`CanonicalRuntimeContext.workspaceBase`, but the SCM/Git plugin
(F5.1) and the JUnit plugin (F5.2) were built before the capability
seam that would expose this value to a plugin handler. Both plugins
close over a `() -> Path` resolver that reads the system property
instead of reaching the typed context.

### Readers (three sites in production code)

1. `v2/pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/step/GitCheckoutStepDefinition.kt:64`

   ```kotlin
   private val workspaceRootResolver: () -> Path = {
       Path.of(System.getProperty("pipeline.workspace.root")
           ?: System.getProperty("user.dir")
           ?: ".")
   },
   ```

   Used inside `GitCheckoutStepDefinition.handler` to resolve relative
   `relativeTargetDir` paths when the caller does not explicitly
   provide an absolute path. SCM/Git F5.1 UAT-closure precedent.

2. `v2/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsStepDefinition.kt:53-54`

   ```kotlin
   private val workspaceRootResolver: () -> Path = {
       Path.of(System.getProperty("pipeline.workspace.root")
           ?: System.getProperty("user.dir")
           ?: ".")
   },
   ```

   Used by the handler to resolve a relative `input.workspaceRoot`
   against the active pipeline workspace. F5.2 closure introduced
   this fallback after the original cycle proved it was necessary.

3. Tests (4 rows in `F5_2_JUnitStepContractTest`, all setting and
   clearing the system property explicitly to verify the resolver).

   This is a TESTING-only consumer; not a production read.

### What the system property does NOT cover

The system property is read by each handler closure at handler-execute
time. Two concurrent `pipelinek run` invocations against different
workspaces **cannot coexist in the same JVM** because the property is
shared mutable state. The current production model is one binary = one
run = one JVM, so this is a latent failure mode, not an active one.

A handler running inside a forked worker process (future WU) would not
see the property at all, because the launcher does not propagate it
across the fork boundary.

## Behaviour matrix under the existing seam

| Workspace argument (`input.workspaceRoot`) | Pipeline workspace (`pipeline.workspace.root`) | Resolver result |
| --- | --- | --- |
| `""` or `"."` or `"./"` | (any) | pipeline workspace (via resolver) |
| absolute, existing directory | (any) | absolute value (honoured) |
| absolute, non-existent | (any) | pipeline workspace (resolver) |
| relative, existing directory | (any) | relative value (honoured) |
| relative, non-existent | (any) | pipeline workspace (resolver) |

The matrix is the SAME for both plugins. F5.1 and F5.2 converged on
this exact set of rules, independently of each other — a clear sign
that the resolver should be a shared seam, not per-plugin logic.

## Concurrent-runs characterisation (the question WU-WC asks)

Question: do two concurrent `pipelinek run` invocations against
different workspaces interfere?

Today: NO. The binary runs each invocation in its OWN JVM (each `java`
process is single-tenant). The system property is set on
`System.setProperty` in Main.kt:583 — that affects the calling
process only, not the rest of the system.

The risk model WU-WC is checking against is **not** "two `pipelinek`
invocations running at once" — that already works. The risk is
**future** "two plugin handlers within the same JVM, each holding its
own workspace" (e.g. a block-Step that fans out to forked workers).
In that future, the system property becomes a single source of truth
shared between threads, and a worker for run-A may read run-B's
workspace by accident.

The migration to `WorkspaceProvider` is a defensive measure for that
future: the value comes from the immutable step context (which is
per-invocation, not global), so two handlers in the same JVM can each
hold their own resolver without seeing each other.

## What already exists in the capability layer

`CanonicalRuntimeCapabilityAccess` already exposes:

- `WORKSPACE_IDENTITY_CAPABILITY` → `WorkspaceIdentity(workspaceRoot = context.shOptions.workspaceRoot)` (S2-A6 / G1).
- `WORKSPACE_OPERATIONS_CAPABILITY` → `WorkspaceOperationsAdapter` (S2-A3 / G1).
- `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` → `TemporaryWorkspaceOperationsAdapter`.
- `DELETE_DIR_OPERATIONS_CAPABILITY` and `CLEAN_WS_OPERATIONS_CAPABILITY` → both bind `context.workspaceBase`.

These capabilities are derived purely from `CanonicalRuntimeContext`,
which is per-invocation. There is NO system-property read inside
`buildProvided`. The plugin handler can already request
`WORKSPACE_IDENTITY_CAPABILITY` and receive the typed workspace root.

The seam exists. The plugins do not use it.

## Migration shape

The migration is a 3-file change:

1. `JUnitResultsStepDefinition.kt`:
   - Add `WORKSPACE_IDENTITY_CAPABILITY` to `requiredCapabilities`.
   - Replace `workspaceRootResolver()` with
     `handlerContext.capabilities.get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY).workspaceRoot`.
   - Drop the system-property fallback; the typed capability is
     always available in production. Tests that constructed the
     StepDefinition without a `CanonicalRuntimeCapabilityAccess`
     need to supply one — already covered by the contract tests.

2. `GitCheckoutStepDefinition.kt`: same shape (F5.1 follow-up).

3. `Main.kt:583`: remove the `System.setProperty("pipeline.workspace.root", ...)`.
   The property is no longer read anywhere.

Constraint preserve:
- `CanonicalDurableRunCoordinator` not modified.
- `core.sh`, `core.echo` not modified.
- `junit.results` public Step API unchanged (input codec, contract
  shape, output codec, plugin discovery).
- `JUnitResultsStepDefinition` constructor signature changes to take
  an injected capability access (or an injected resolver). The
  existing tests that supply a system property will be migrated to
  supply a synthetic capability access.

## Tests to migrate (lock-in baseline + target)

Baseline (PASS today, before migration):
- The 4 rows in `F5_2_JUnitStepContractTest` that drive
  `System.setProperty("pipeline.workspace.root", ...)` and assert
  the handler uses it.

Target (PASS after migration, same semantic):
- The same 4 rows, but instead of setting a system property they
  pass a `WorkspaceIdentity`-bearing capability access to the
  handler's `StepHandlerContext.capabilities`.

Isolation pair:
- baseline (no plugin installed) — junit.results key is unavailable.
- target (plugin installed) — junit.results key resolves through the
  new seam, no system property is consulted.

Concurrent-runs test (fitness):
- Two `JUnitResultsStepDefinition` instances, each with its own
  capability access pointing at a different workspace, run in
  parallel via `Thread { ... }.start()` and `Thread.join()`. Each
  resolves its own report path correctly. No shared state is
  mutated during the resolution.

## Risks

1. The plugin SDK currently exposes capability access via
   `StepHandlerContext.capabilities`. The capability access is
   injected at the boundary level (`RegistryExecutionBoundary.coexecute`).
   Plugins that call the handler directly (tests, internal scripting
   adapters) must construct their own capability access. The
   `StepHandlerContext` constructor is public; this is not a contract
   break.

2. Removing `System.setProperty("pipeline.workspace.root", ...)` from
   `Main.kt:583` is a one-shot delete. Any plugin that STILL reads
   the property will break at runtime. The audit identifies
   `GitCheckoutStepDefinition` and `JUnitResultsStepDefinition` as
   the only production readers. Both will migrate in this cycle.

3. `junit.results` already has 23 contract tests, 4 of which depend
   on the system property. They will be rewritten to use the
   capability access. The semantic invariant — `input.workspaceRoot`
   resolution behaviour — is preserved.
