# RP034-E — Extended workspace consumers

**Status:** CLOSED — every extended consumer reads the shared execution location
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin`
**Base:** `19577730b8fc260030d0f9766ae2c6be1cd35e79`
**Authority:** ADR-0100 (Accepted), `04-step-path-anchor-matrix.md`

## Scope

Eleven Steps stopped asking for `WORKSPACE_IDENTITY_CAPABILITY`:

| Step | Anchor | Commit |
|---|---|---|
| `core.pwd` | `CURRENT_DIRECTORY` | `19577730` |
| `core-utils.readJson` / `writeJson` / `readYaml` / `writeYaml` / `sha256` / `findFiles` / `zip` / `unzip` | `CURRENT_DIRECTORY` | `b797ca27` |
| `scm-git.checkout` | `CURRENT_DIRECTORY` | `4d615fff` |
| `junit.results` | caller-selected base, confined | `4d615fff` |
| `core.stash` / `core.unstash` | `CURRENT_DIRECTORY` source/target | `7cf249a5` |

The legacy capability value was always `workingDirectory ?: workspaceRoot`, so
these Steps were *already* resolving against the cwd. This slice did not change
which paths they produce at the root; it changed the authority they declare, so
`dir(...)` moves the base by contract instead of by accident.

## The capability value was the wrong type

`CanonicalRuntimeCapabilityAccess` registered an `ExecutionLocationCapability`
interface instance under `EXECUTION_LOCATION_CAPABILITY`, while the documented
contract says the value is the domain `ExecutionLocation`. Only
`:pipeline-application` could see the interface, so every plugin that read the
key failed at runtime:

```text
class ShOptionsExecutionLocationAdapter cannot be cast to class
dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
```

This surfaced only at the module boundary. All 119 utilities unit tests passed
against a hand-built `StepHandlerContext` while six corpus fixtures and the new
anchor test failed. **A contract test with a synthetic context does not prove
which value the bridge registers** — only the end-to-end path does.

The interface is deleted. `ShOptionsExecutionLocationAdapter` is now a pure
derivation returning the domain ADT, and core and plugins share one type for one
concept.

## Confinement corrected in two Steps

`core-utils.zip` and `core-utils.unzip` guarded containment with
`path.startsWith(workspaceRoot)` where `workspaceRoot` was the effective working
directory, not the workspace boundary. Under a `dir(...)` scope the two diverge,
so:

- a path legitimately inside the workspace was **refused**;
- an unnormalised `sub/../..` path **passed** the prefix check.

`WorkspacePathResolver` decides both cases before any byte is read.

## Caller-selected base is caller authority

`junit.results` carries its own `workspaceRoot` input. Confining that against
the pipeline workspace root broke its documented behaviour, and two real tests
caught it during the migration. The settled rule, now stated in
`WorkspacePathAnchors.against`:

> Naming a base declares **where the Step operates**, so that base is also its
> boundary.

`..` traversal and stray absolute host paths stay refused; a CI caller can still
retarget the lookup.

## Evidence

| Suite | Result |
|---|---|
| `pipeline-step-sdk:utilities` | 119/119 |
| `pipeline-step-sdk:scm-git` | 47/47 |
| `pipeline-step-sdk:junit` | 8/8 |
| `CoreScmGitCheckoutStepContractSuiteTest` | 47/47 (8 skipped by design) |
| `F5_1_ScmGitNegativePathsTest` / `ProviderProvenance` / `StepContract` | 11/11, 4/4, 10/10 |
| `ScmGitWorkspaceIsolationTest` | 3/3 |
| `F5_2_JUnitStepContractTest` | 24/24 |
| `JUnitWorkspaceIsolationTest` | 2/2 |
| `WcScmE2EBothPluginsIntegrationTest` | 3/3 |
| `CoreStashStepContractSuiteTest` | 11/11 |
| `StashOperationsAdapterUatTest` | 7/7 |
| `CorePwd*` suites | 95/95 |
| `ShOptionsExecutionLocationAdapterTest` | 11/11 |
| `ShellFilesystemCwdDivergenceTest` | 2/2 |
| `CompatibilityCorpusTest` | 30/30 |
| detekt (`application`, `api`, `utilities`, `junit`, `scm-git`) | clean, `--rerun-tasks` |

### DF-UNSTASH-001 validated in both directions

Stash from `producer/`, unstash inside `consumer/`, assert the payload lands in
`consumer/` and nowhere at the root. The test was run with the location wiring
neutralised (**FAILED**) and restored (**passed**), so it discriminates rather
than merely observing.

## Behaviour changes (BREAKING)

1. `core.pwd` reports the cwd in `path`; use `workspaceRoot` for the root.
2. `core-utils.*` no longer honour an absolute path outside the workspace root
   or a `..` traversal beyond it.
3. `scm-git.checkout` normalises `localPath`: a `relativeTargetDir` of `"."`
   reports the root without a redundant `.` component.
4. `junit.results` refuses a report path escaping its selected base.

All four are the semantics ADR-0100 and ADR-0102 already mandate; the prior
behaviour was the collapsed-identity defect.

## Remaining

- `executionLocation` is nullable in the stash, checkout and junit adapters and
  falls back to `WorkspaceResolver`. Retiring those fallbacks, and
  `ProjectCheckoutDetector`, belongs to RP034-I.
- `archiveArtifacts` and `publishHTML` still rebuild their base and are
  `DIFFERENTIAL_REQUIRED`: RP034-F.
