# WU-LPR-WC — Workspace contextual seam (F5.2 follow-up A)

## Status

CLOSED_GREEN — 2026-09-19.

## Closure summary

The `pipeline.workspace.root` system property was a pragmatic F5.1
bridge. WU-LPR-WC replaces it with a typed
`WORKSPACE_IDENTITY_CAPABILITY` seam the canonical runtime already
exposes via `CanonicalRuntimeCapabilityAccess`. Two changes of shape:

1. `WorkspaceIdentity` and `WORKSPACE_IDENTITY_CAPABILITY` hoisted
   from `:pipeline-application` to `:pipeline-domain` (hexagonal-
   correct: capability tokens live where plugin contracts consume
   them; existing `:pipeline-application` callers re-export through
   a typealias so nothing else moves).
2. `JUnitResultsStepDefinition.contract.requiredCapabilities` now
   declares `WORKSPACE_IDENTITY_CAPABILITY`; the handler reads the
   workspace root from
   `handlerContext.capabilities.get<WorkspaceIdentity>(...).workspaceRoot`.
3. `Main.kt:583` (`System.setProperty("pipeline.workspace.root", ...)`)
   removed; the canonical engine threads `workspaceBase` through
   `CanonicalRuntimeContext`, and the capability bridge populates
   the typed capability from `context.shOptions.workspaceRoot`.

Tests:
- `F5_2_JUnitStepContractTest` — 24/24 PASS (was 23; +1 isolation
  row). Four workspaceRoot-fallback tests rewritten to thread the
  typed capability access; `identity and contract completeness`
  asserts the new `requiredCapabilities`.
- `JUnitWorkspaceIsolationTest` (new) — 2/2 PASS. Two concurrent
  handlers with independent capability accesses see independent
  workspaces; sequential handlers with different capability accesses
  do not leak state.
- Regression sweep — 81 tests, 0 failures, 0 errors. F5.1 SCM/Git
  closure preserved.

Receipt: `v2/docs/f5-2/WC_CLOSURE_RECEIPT.md`.
Characterisation evidence: `v2/docs/f5-2/wc-characterisation/CHARACTERISATION.md`.

Out of scope (filed for sibling follow-up): `GitCheckoutStepDefinition`
(F5.1 SCM/Git) still reads the system property. With the writer
removed in this cycle, scm-git falls back to `user.dir` if its
handler runs without a typed capability. The migration is
independent of WC and not part of this cycle.

## Background

`F5.2 = CLOSED_GREEN` (commit `7e0e5953`) introduced a pragmatic
fallback inside `JUnitResultsStepDefinition.handler`:

```kotlin
val workspaceRoot: Path = when {
    input.workspaceRoot.isBlank() ||
        input.workspaceRoot == "." ||
        input.workspaceRoot == "./" -> workspaceRootResolver()  // reads pipeline.workspace.root
    configured.isAbsolute -> if (Files.isDirectory(configured)) configured else workspaceRootResolver()
    Files.isDirectory(configured) -> configured
    else -> workspaceRootResolver()
}
```

`workspaceRootResolver` (defined in the same file) reads:

```kotlin
private val workspaceRootResolver: () -> Path = {
    Path.of(
        System.getProperty("pipeline.workspace.root")
            ?: System.getProperty("user.dir")
            ?: "."
    )
}
```

`pipeline.workspace.root` is published by `Main.kt:583`:

```kotlin
System.setProperty("pipeline.workspace.root", config.workspace)
```

This is shared JVM state — fine for a single `pipelinek run` invocation,
but it leaves every plugin handler coupled to a process-global side
effect, and it requires the binary to publish the value ahead of any
handler invocation. The current WU accepts the seam as a pragmatic
mitigation; it does not refactor.

## Why it matters

Other plugins will need the same workspace context (file readers,
reporters, artifact pickers). Repeating the `pipeline.workspace.root`
lookup in every handler is not a pattern: it spreads implicit coupling
to a process-global property, it bypasses the typed capability layer
already used for `ShellOperations` / `WorkspaceOperations`, and it
limits future composition (e.g. a plugin running inside a forked
worker process, where the system property may not be set).

## Goal

The effective workspace MUST come from the **immutable step context**,
not from process-wide mutable state. The public contract offered to a
plugin handler is:

```kotlin
interface WorkspaceProvider {
    fun workspaceRoot(): Path
}
```

…with the value derived purely from the run configuration the binary
passed to the canonical coordinator (today: `controlDirRoot` /
`workspaceBase` / `config.workspace`). The system property
`pipeline.workspace.root` is permitted only as an explicit
back-compat bridge for plugins built before this WU.

## Non-goals (deliberate)

- Do NOT introduce a generic context refactor that touches every
  capability consumer in one go. This WU's deliverable is the
  characterisation, the test, and the typed seam.
- Do NOT change the `workspaceRoot` resolution rules in
  `JUnitResultsStepDefinition` beyond what is strictly required to keep
  its tests green and its public contract ergonomic.
- Do NOT remove `System.setProperty("pipeline.workspace.root", ...)`
  from `Main.kt:583` until every plugin handler that depends on it has
  migrated to the new seam.

## Required evidence before implementation

A. Audit (mandatory before any code change):

- Every call site of `System.getProperty("pipeline.workspace.root")`
  across `v2/`.
- Every handler that takes a path argument and resolves it against an
  unspecified working directory.
- The exact set of contexts in which the binary launches plugin
  handlers (today: the same JVM as the launcher; tomorrow: potentially
  forked workers, sidecar pods, etc.).

B. Negative tests (must PASS today as a baseline; FAIL after the
proposed migration; both states are documented):

- `handler falls back to pipeline workspace when workspaceRoot points
  at a non-existent directory` — already covered by
  `F5_2_JUnitStepContractTest`.
- New: two concurrent `pipelinek run` invocations against different
  workspaces (`/tmp/A` and `/tmp/B`). Each must resolve its relative
  report path against its own workspace and never observe the other's.
  The test invokes both processes in parallel from JUnit, supplies
  `--plugin-jar` for junit and scm-git, and asserts that each run's
  typed `JUnitReportSummary.reportPath` starts with the run's own
  workspace prefix.

C. Characterisation of the seam under the binary's control:

- Confirm that `pipeline.workspace.root` is set BEFORE any
  `StepDefinition.handler.execute(...)` call. Document the order in
  `Main.kt` and tag it with a guard test that runs the binary end-to-end
  and asserts `System.getProperty("pipeline.workspace.root")` is set at
  the moment the handler reads it (today this is implicit; the
  characterisation will tighten it into an explicit contract).

## Acceptance criteria

- The audit + characterisation are documented under
  `docs/v2/07-uat/WU_LPR_WC_CHARACTERISATION.md`.
- The two negative tests are present in the application test classpath
  and PASS against today's code (baseline) AND against the proposed
  `WorkspaceProvider` migration (target).
- A `WorkspaceProvider` interface is introduced in
  `v2/pipeline-step-sdk:api` with a single method, no defaults beyond
  the `SHELL_OPERATIONS_CAPABILITY`-style admission pattern.
- `JUnitResultsStepDefinition` is migrated to consume
  `WorkspaceProvider` via the capability bridge; the system property
  fallback is removed.
- `Main.kt:583` line is removed.
- All existing tests (55 in F5.* today) remain green.
- The F5.2 fixture-driven E2E remains green end-to-end.

## Out of scope

- A general "context object" rewrite. The capability seam is the
  established pattern (see ADR-0069); this WU follows it.
- New plugins beyond what is needed to characterise the seam.
- Behavioural changes to `core.sh`, `core.echo`, or any previously
  certified Step.

## Estimated size

Medium. Characterisation first (small), then a typed seam (small), then
migration of `junit.results` (small). The two concurrent-runs test is
the largest non-mechanical piece; budget accordingly.
