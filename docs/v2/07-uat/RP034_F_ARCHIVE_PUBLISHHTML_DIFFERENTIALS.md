# RP034-F — Archive and publishHTML differentials + anchor fitness

**Status:** CLOSED — both differentials resolved; no Step rebuilds its own base
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin`
**Base:** `d3dcd3657b6b9a2e3fbd555864a8a935a721a69c`
**Commit:** `1f451b98d19e5660507da2cc89b6ca972812a4ef`
**Authority:** ADR-0100, `04-step-path-anchor-matrix.md`

## The differential and its ruling

`archiveArtifacts` and `publishHTML` were the last two `DIFFERENTIAL_REQUIRED`
rows in the anchor matrix. The matrix forbids changing production before
observing the anchor, so the ruling had to be argued, not assumed.

**Evidence used**

1. The Jenkins docs state the base for both is *the workspace*
   ([core](https://www.jenkins.io/doc/pipeline/steps/core/),
   [htmlpublisher](https://www.jenkins.io/doc/pipeline/steps/htmlpublisher/)).
2. Jenkins states that same default for the sibling path Steps as *the current
   working directory (by default: the workspace)* — `fileExists`, `readFile`,
   `stash` — and `dir` exists to move the current directory.
3. `04-step-path-anchor-matrix.md` §3 permits **no hidden anchor exceptions**.
4. `03-domain-model-and-architecture.md`: the archive **source** is resolved
   through `ExecutionLocation`; the durable **store** is not a workspace anchor.

**Ruling:** `CURRENT_DIRECTORY`. A root-pinned archive is precisely the hidden
exception §3 forbids, and reading the Jenkins default as "the current working
directory" is the only reading consistent with its sibling Steps.

**Limit of the evidence, stated plainly:** no live Jenkins instance was
available in this environment, so this is a documentary ruling plus a
characterisation test, not an observation of a real Jenkins run. The test pins
the decision so a future change to it is deliberate.

## Differential fixtures

Both were validated in **both directions** — they fail with the location wiring
neutralised and pass with it restored.

| Fixture | Shape | Discriminating assertion |
|---|---|---|
| `DF-ARCH-001` | `root.txt` at the root, `inside.txt` inside `dir("sub")`, `archiveArtifacts "*.txt"` | only `inside.txt` is archived; a `WORKSPACE_ROOT` anchor would also take `root.txt` |
| `DF-HTML-001` | decoy `root-report/` next to scoped `sub/report/`, `publishHTML reportDir = "report"` inside `dir("sub")` | the scoped report is published |

## The harness was describing an impossible run

Three `CoreArchiveArtifactsStepContractSuiteTest` cases failed after the
migration, and the cause was not the Step. The suite built its context with
`ShOptions.EMPTY` — whose `workspaceRoot` is a random temp directory — while
seeding files into `control/workspace/build-0`.

Production never has that disagreement: the coordinator resolves
`shOptions.workspaceRoot` to the stage workspace, which is exactly what the
execution location is derived from. The suite therefore described a run that
cannot occur, and the Step correctly matched nothing.

The harness now threads the real stage workspace into `ShOptions`. This is the
same lesson as the RP034-E capability-value defect, in a different costume: a
synthetic context that no production run produces will eventually disagree with
reality, and the disagreement surfaces as a failure that looks like a product
bug.

## Anchor classification fitness

`FArchWorkspaceResolutionFitnessTest` gains ADR-0100's first fitness rule:

> no Step handler may reconstruct a workspace base with `WorkspaceResolver`

The authorised set is named exactly, each with its reason: the allocator, the
durable spine that derives the location (`CanonicalDurableRunCoordinator`,
`ShExecution`), and the adapters that read the location first and fall back only
when none was injected — `WorkspaceOperations`, `StashOperationsAdapter`,
`ArchiveArtifactsOperations`, `PublishHtmlOperationsAdapter`,
`CleanWsOperationsAdapter`, `DeleteDirOperationsAdapter`.

Validated by injecting a `WorkspaceResolver(` construction into
`CoreEchoStep` and confirming the fitness fails, then restoring.

`S3PwdLegacyRemovedFitnessTest` now requires `core.pwd` to declare the typed
location and explicitly **not** regress to the collapsed workspace identity.

## Evidence

| Suite | Result |
|---|---|
| `WorkspaceAnchorScopeEndToEndTest` | 4/4 |
| `CoreArchiveArtifactsStepContractSuiteTest` | 27/27 |
| `CoreArchiveArtifactsStepUnitTest` | 24 (3 skipped by design) |
| `CorePublishHtmlStepContractSuiteTest` | 24/24 |
| `PublishHtmlOperationsAdapterUatTest` | 14/14 |
| `UatLocal009TopStepsTest` | 13/13 |
| `CompatibilityCorpusTest` | 30/30 |
| `pipeline-architecture-tests` (full) | 350/350 |
| detekt (`pipeline-application`, `pipeline-architecture-tests`) | clean, `--rerun-tasks` |

## Remaining for RP034-I

- `executionLocation` is nullable in the stash, checkout, junit, archive and
  publishHTML adapters. Retiring those fallbacks makes the construction
  non-optional and removes the last reconstruction path.
- Once those fallbacks go, `ProjectCheckoutDetector` has no production caller:
  it survives only in the two `?:` branches of the destructive adapters. Its
  removal, and `ProjectCheckoutDetectorTest`, become mechanical.
- Six untracked files in `v2/pipeline-application/` (`VERSION.txt`,
  `artifact.txt`, `out.txt`, `lpr104-readme.txt`, `.cleaned`, `.deleted`) are
  written by tests that run against the invocation directory after the
  local-first flip. Their origin still needs to be identified.
- The full `./gradlew check` has not been run on any WU-RP-034 SHA; evidence to
  date is per-suite.
