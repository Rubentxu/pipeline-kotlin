# WU-LPR-WC-SCM Characterisation (pre-migration)

## What is observed today

`GitCheckoutStepDefinition` (`v2/pipeline-step-sdk/scm-git/.../GitCheckoutStepDefinition.kt`)
declares `requiredCapabilities = emptySet()` and reads the workspace
root from a `workspaceRootResolver: () -> Path` constructor default:

```kotlin
private val workspaceRootResolver: () -> Path = {
    Path.of(System.getProperty("pipeline.workspace.root")
        ?: System.getProperty("user.dir") ?: ".")
},
```

The system property was published by `Main.kt:583` until WU-LPR-WC
removed it. After WC closed, the writer is gone; the reader falls back
to `user.dir` for any production run that does not thread a different
resolver explicitly.

This means: after WC closed, a real `pipelinek run --workspace
/tmp/some/dir script.pipeline.kts` invoking `scmGitCheckout(...)` would
NOT honour `--workspace` if the handler is admitted through the
canonical boundary. It would write into `user.dir` (process cwd).

The F5.1 contract tests still pass because they construct
`GitCheckoutStepDefinition()` with the default resolver, then
`System.setProperty("pipeline.workspace.root", ...)` to redirect it
(see `F5_1_ScmGitStepContractTest`, `F5_1_ScmGitNegativePathsTest`).
The tests are validating the OLD bridge, not the typed seam.

## Behaviour matrix (pre-WC-SCM, after WC closed)

| Runtime context | Resolver result | Real checkout destination |
| --- | --- | --- |
| `pipelinek run --workspace /tmp/ws` (no system property) | `user.dir` | process cwd (wrong) |
| `pipelinek run --workspace /tmp/ws` + `System.setProperty(...)` set elsewhere | system property | depends on what set it |
| Unit test with default resolver + `System.setProperty("pipeline.workspace.root", X)` | X | X (test-only) |

## Why this matters for the dual-plugin UAT

The UAT scenario is:

```
checkout → dir(proyecto) → sh(tests) → junit.results(XML generado en el mismo run)
```

`junit.results` reads from `WORKSPACE_IDENTITY_CAPABILITY` (WC
closed-green). `scm-git.checkout` reads from `user.dir`. If the user
runs:

```
pipelinek run --workspace /tmp/myproject script.pipeline.kts
```

the checkout writes into `/tmp/myproject/hello-world` only when the
process cwd is `/tmp/myproject`. Otherwise the checkout lands in
`user.dir` and `dir("hello-world")` either fails to find it or
operates on a different tree. The two plugins would not be reading
from the same workspace.

## Migration shape

Mirror the JUnit migration exactly:

1. `GitCheckoutStepDefinition.contract.requiredCapabilities` →
   `setOf(WORKSPACE_IDENTITY_CAPABILITY)`.
2. The handler reads the canonical workspace from
   `ctx.capabilities.get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY).workspaceRoot`.
3. The `workspaceRootResolver` constructor default stays as a
   developer-escape hatch for direct `handler.invoke(...)` unit tests,
   with the same caveat JUnit has: production runs must thread the
   typed capability; the fallback only exists for tests that bypass
   the registry boundary.
4. The handler NEVER queries `user.dir` in the production path.
   Tests that need a different workspace MUST supply it via the
   typed capability access in the `StepHandlerContext`.

## Distinguish `dir(...)` block workspace from base workspace

`dir("subdir")` is a per-block scope transition; it changes the cwd
of subsequent `sh` invocations, but it is NOT the canonical
`pipeline.workspace.root`. The SCM/Git checkout's `relativeTargetDir`
is resolved against the canonical workspace (today's resolver reads
the system property, which equals `--workspace`). After the
migration, `relativeTargetDir` will be resolved against the typed
`WorkspaceIdentity.workspaceRoot`. A `dir("subdir")` block does NOT
change `WorkspaceIdentity`; the checkout destination stays the same.
That is the correct semantics: the checkout produces a tree at
`{workspaceRoot}/{relativeTargetDir}` and the subsequent block
operates inside it via the runtime cwd flip.

## Tests to migrate (lock-in baseline + target)

Baseline (PASS today, before migration): all 11/10/4 rows of the
F5.1 SCM/Git contract/negative/provenance suites. They set
`pipeline.workspace.root` explicitly.

Target (PASS after migration, same semantic):

- Rewrite the resolver-set rows to use a `TestCapabilityAccess`
  pointing at the desired workspace.
- Drop the `System.setProperty` lines.
- New: a row asserting that when neither the system property nor the
  capability carry a valid workspace, the handler fails closed at
  capability admission (NOT silently falls back to `user.dir`).
- New: a row asserting that a checkout with
  `relativeTargetDir = "hello-world"` produces the checkout at
  `<typedWorkspaceRoot>/hello-world`, not at `<cwd>/hello-world`.

## Concurrent-workspace isolation

The same `JUnitWorkspaceIsolationTest` shape applies: two
`GitCheckoutStepDefinition` instances with independent capability
accesses must each see their own workspace when run in parallel.
For SCM/Git the test uses a different shape — a checkout writes to
disk, so concurrent runs of the SAME repo path would race. The
isolation test for SCM/Git must use two distinct repos or two
distinct `relativeTargetDir` values inside the same repo.

## Risks

1. Removing the implicit `user.dir` fallback in the production path
   means any test that does NOT supply a typed capability access will
   fail closed at capability admission. This is the desired
   behaviour; the contract tests must be rewritten.
2. `relativeTargetDir` was historically resolved relative to the
   workspace root, but if a caller passed an absolute path the
   resolver would have honoured it (the
   `GitCheckoutExecutor.execute` accepts absolute paths and the
   previous code did NOT normalise). The migration must preserve
   this: if `relativeTargetDir` is absolute, resolve to it verbatim;
   otherwise resolve against the typed workspace identity.
