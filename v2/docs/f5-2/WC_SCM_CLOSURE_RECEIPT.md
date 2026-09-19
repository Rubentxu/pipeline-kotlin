# WU-LPR-WC-SCM — Closure Receipt

## Closure state

CLOSED_GREEN.

| Item | Value |
| --- | --- |
| Decision | Migrate `scm-git.checkout` from system-property / `user.dir` to the typed `WORKSPACE_IDENTITY_CAPABILITY` seam, mirroring the WU-LPR-WC migration for `junit.results` |
| Migration shape | `GitCheckoutStepDefinition.contract.requiredCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY)` + handler reads from `ctx.capabilities.get<WorkspaceIdentity>(...).workspaceRoot`; constructor default `workspaceRootResolver` survives ONLY as a developer-escape hatch for direct unit-test invocation |
| Characterisation commit | `2b17a0cb` WC-SCM.characterisation |
| Apply commit | `<this-commit>` WC-SCM.apply |
| Close commit | (this file) |

## What was observed before the migration

After WU-LPR-WC removed the `Main.kt:583` `System.setProperty("pipeline.workspace.root", ...)`
writer, `GitCheckoutStepDefinition.workspaceRootResolver()` still defaulted to
`System.getProperty("pipeline.workspace.root") ?: System.getProperty("user.dir") ?: "."`.
For any canonical-boundary run that did not set the system property, the handler
fell back to `user.dir` and wrote the checkout there. The F5.1 contract tests hid
this because they explicitly set `pipeline.workspace.root` in their setup.

## What was observed after the migration

- `scm-git.checkout` reads the canonical workspace from the typed
  `WORKSPACE_IDENTITY_CAPABILITY` seam (mirrors the JUnit migration).
- The handler NEVER queries `user.dir` or the system property in production.
  The `workspaceRootResolver` constructor default survives as a developer-escape
  hatch only — consulted when the typed capability points at a directory that
  no longer exists (rare; covers direct unit-test construction outside the
  canonical bridge).
- `relativeTargetDir` semantics preserved: absolute paths honoured verbatim;
  relative paths resolve against the typed workspace root (NOT against `user.dir`).

## Test evidence (fresh)

```
CoreEchoSeamTest                                        tests=7 failures=0 errors=0
CoreShellStepTest                                       tests=12 failures=0 errors=0
durable.RegistryExecutionBoundaryFailureKindTest        tests=7 failures=0 errors=0
durable.RegistryExecutionBoundaryTest                   tests=6 failures=0 errors=0
durable.RegistryExecutionOutcomeTest                    tests=4 failures=0 errors=0
durable.RegistryExecutionPreparationTest                tests=5 failures=0 errors=0
F5_1_ScmGitNegativePathsTest                            tests=11 failures=0 errors=0
F5_1_ScmGitProviderProvenanceTest                       tests=4 failures=0 errors=0
F5_1_ScmGitStepContractTest                             tests=10 failures=0 errors=0
F5_2_JUnitStepContractTest                              tests=24 failures=0 errors=0
JUnitWorkspaceIsolationTest                             tests=2 failures=0 errors=0
ScmGitWorkspaceIsolationTest                            tests=3 failures=0 errors=0   (NEW)
WcScmE2EBothPluginsIntegrationTest                      tests=3 failures=0 errors=0   (NEW)
TOTAL: 98 tests, 0 failures, 0 errors
```

### What the new tests prove

| Test | Row | Guarantee |
| --- | --- | --- |
| ScmGitWorkspaceIsolationTest | `WC-SCM-01 relativeTargetDir resolves against typed workspace identity, not cwd` | The typed capability access carries the workspace we passed; the handler MUST NOT fall back to `user.dir` in the canonical path. |
| ScmGitWorkspaceIsolationTest | `WC-SCM-02 two concurrent handlers observe independent workspaces` | Two threads with independent typed capability accesses see their own workspace; no shared mutable state. |
| ScmGitWorkspaceIsolationTest | `WC-SCM-03 developer-escape hatch is consulted only when typed workspace is missing on disk` | The guard `Files.isDirectory(capabilityWorkspaceRoot)` controls the fallback; production never reaches the escape hatch. |
| WcScmE2EBothPluginsIntegrationTest | `WC-SCM E2E scm-git checkout lands under the typed workspaceBase never user dir` | Real canonical-engine run; checkout output is under `workspaceBase/hello-world`, NOT under `user.dir`. |
| WcScmE2EBothPluginsIntegrationTest | `WC-SCM E2E junit results lands under the typed workspaceBase never user dir` | Same engine, second OFFICIAL_PLUGIN; junit.results consumes an XML produced under `workspaceBase` and confirms no `user.dir` contamination. |
| WcScmE2EBothPluginsIntegrationTest | `WC-SCM E2E two scm-git runs with different workspaceBase do not cross-contaminate` | Two separate runs with two distinct typed workspaces each land in their own tree; wsA and wsB checkouts are distinct, neither run polluted the other. |

### Why the E2E runs through the canonical engine directly (NOT the installed binary)

The pre-existing scripting-host gap (recorded in the FK / WC closure receipts):
the installed binary's `Kotlin24ScriptingHost` calls
`dependenciesFromCurrentContext(wholeClasspath=false)`, which exposes only a
curated subset of the runtime classpath to the script compiler. The
`dev.rubentxu.pipeline.v2.sdk.*` packages — although present in
`install/pipelinek/lib/` — are not visible to the script compiler. Even the
generic `registryStep(stepKey = PluginStepId(...), encodedInput = ...)` form
fails because `PluginStepId` / `EncodedStepValue` are also outside the curated
list. The gap reproduces with the canonical F5.2 E2E scenario from before
WU-LPR-WC closed (same diagnostic message: "Cannot access class
'dev.rubentxu.pipeline.v2.domain.PluginStepId'").

The JUnit harness used by the new E2E launches the same
`CanonicalDurableRunCoordinator` directly with the production registry +
production plugins + the typed `workspaceBase`. This is **the same canonical
engine** MainKt drives in production; we skip the scripting-host layer that
the binary blocks, but every other seam (registry, capability bridge, journal,
event sink, replay) is identical.

The pre-existing scripting-host gap is **NOT** a regression of WC-SCM. It
predates WU-LPR-WC and was already documented in the F5.2 close-out receipt.
A follow-up WU is required to expose `sdk.*` and the generic types to the
script compiler; that work is out of scope here.

## Constraint preservation

| Constraint | Status |
| --- | --- |
| `CanonicalDurableRunCoordinator` | untouched |
| `core.sh` | untouched |
| `core.echo` | untouched |
| `RegistryExecutionBoundary` | untouched |
| `junit.results` public Step API (key, codecs) | unchanged |
| F5.1 historical certification | preserved (receipt remains in `docs/v2/07-uat/`); the contract shape migrates from empty capability set to `WORKSPACE_IDENTITY_CAPABILITY`, matching the new typed seam |
| FK lock-in tests (`TypedStepOutput` carrier contract) | still green |
| WC junit.results lock-in tests (`JUnitWorkspaceIsolationTest`, `F5_2_JUnitStepContractTest`) | still green |
| C10 backwards-compat | intact |

## What was NOT changed

- `Main.kt` — the canonical engine entry point (no changes; `--workspace` was
  already threaded through `workspaceBase` upstream).
- `Kotlin24ScriptingHost` — the binary scripting-host gap is pre-existing;
  addressing it is out of WC-SCM scope.
- The `install/pipelinek` binary — same as above.
- SDKMAN — `WAITING_EXTERNAL`.

## Hexagonal preservation

`WorkspaceIdentity` and `WORKSPACE_IDENTITY_CAPABILITY` already lived in
`:pipeline-domain` (where `BODY_INVOKER_CAPABILITY` also lives). WU-LPR-WC-SCM
reuses that location — no second representation of the workspace was created.
The plugin SDK depends only on `:pipeline-domain` and the public SDK surface;
it does NOT import `:pipeline-application` types.

## Regressions discovered & corrected in this cycle

None. The migration is purely additive: the contract's capability set changes
(empty → `WORKSPACE_IDENTITY_CAPABILITY`), the handler's source-of-truth for
the workspace root changes (system property → typed capability), and the
historical F5.1 contract tests are updated to assert the new invariant (still
called `F5_1_ScmGit*`; the test names describe the new invariant). No other
behaviour changes; no production semantics regress.

## Roadmap hand-off

WC-SCM closes the integration check across the two OFFICIAL_PLUGINs. The
roadmap continues with the next plugin family without further STOP.

| Item | Status |
| --- | --- |
| F5.1 SCM/Git | historically certified; contract migrated to typed seam in WC-SCM |
| F5.2 JUnit | CLOSED_GREEN |
| FK (typed failure propagation) | CLOSED_GREEN |
| WC (workspace contextual of JUnit) | CLOSED_GREEN |
| WC-SCM (workspace contextual of SCM/Git) | CLOSED_GREEN |
| Pre-existing scripting-host gap (binary distribution) | open follow-up, NOT a regression |
| SDKMAN | WAITING_EXTERNAL |
