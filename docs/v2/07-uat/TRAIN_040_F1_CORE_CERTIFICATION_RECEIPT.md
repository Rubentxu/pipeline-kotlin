# TRAIN-040-FINAL F1 Core Certification Receipt

**Status:** CLOSED_GREEN for the certification target SHA
**Cycle:** `p-733fb505b5a6bd2d/train-040-final`
**WorkItem:** `1bfc0668-4b16-4e7e-b066-b09ede24fa0e`
**Certification target SHA:** `6cb6377d99c29258fc47d0a8db90cec29da75753`
**Certification target tree:** `904e60848e320a47f320c7faf8ddf915a4b80bcb`
**Declared version:** `0.40.0-rc8`
**Inventory regenerated at target SHA:** `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`

## Decision

F1 certifies the accepted core product surface at the target SHA. The work
changed no production Step, public DSL contract, registry routing, durability
policy, or plugin classification.

The only correction was a certification-gap repair in
`WULpr402RuntimeHonestDslFitnessTest`: D-026 had moved the `pwd` and `isUnix`
DSL bodies from `PipelineDsl.kt` to `PipelineDslStageScope.kt`, while the
fitness test still read the old path. The correction makes the guard inspect
the authoritative source file. It does not alter runtime behavior.

Official plugin contract suites were also executed as F1 boundary evidence.
Their product certification remains the F2 responsibility.

## Exact-SHA evidence

All evidence below was executed against the certification target source tree.
The Gradle test-result XMLs were checked for `failures="0"` and
`errors="0"`. The application test task replaces its result directory between
runs, so timestamps below identify the observed execution windows rather than
claiming that every XML remains present after a later module run.

### Core and application contract suites

Fresh application XMLs observed between `2026-09-27T14:06:59Z` and
`2026-09-27T14:07:13Z`:

- 20 suites
- 375 tests
- 3 expected skipped rows
- 0 failures
- 0 errors

The suites cover the accepted execution, filesystem, context, artifact, control,
and infrastructure surfaces, including `unstash` through
`CoreStashStepContractSuiteTest`.

### Official plugin boundary suites

- `CoreUtilsStepContractSuiteTest`: 112 tests, 0 skipped, 0 failures, 0 errors
  at `2026-09-27T14:06:00.103Z`.
- `CoreScmGitCheckoutStepContractSuiteTest`: 20 tests, 0 skipped, 0 failures,
  0 errors at `2026-09-27T14:05:43.791Z`.
- `F5_2_JUnitStepContractTest`: 24 tests, 0 skipped, 0 failures, 0 errors at
  `2026-09-27T14:07:06.162Z`.
- `JUnitWorkspaceIsolationTest`: 2 tests, 0 skipped, 0 failures, 0 errors at
  `2026-09-27T14:07:05.860Z`.

These rows prove the current typed contract and workspace boundary. F2 must
still issue the official-plugin certification receipt and reconcile the
machine inventory's existing SCM receipt heuristic.

### Architecture and registry fitness

Fresh XMLs observed at `2026-09-27T14:13:01Z` through
`2026-09-27T14:13:03Z`:

- `Lfc2RegistryFamilyFitnessTest`: 3/3
- `Lfc2BodyExecutionPolicyFitnessTest`: 10/10
- `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`: 8/8
- `WULpr402RuntimeHonestDslFitnessTest`: 7/7

Total: 28 tests, 0 skipped, 0 failures, 0 errors.

The previously observed WULpr402 failure was the stale source path. It is
closed by commit `6cb6377d` and remains green after forced uncached execution.

### Installed-distribution product scenarios

The real `installDist` binary scenarios were forced with
`--rerun-tasks`. Fresh XMLs observed between `2026-09-27T14:08:49Z` and
`2026-09-27T14:10:30Z`:

- `UatStep001ShExecutionTest`: 2/2
- `UatStep002EchoCaptureTest`: 1/1
- `UatStep003ErrorAbortTest`: 1/1
- `UatStep004SleepTimingTest`: 1/1
- `UatDsl003ParallelTest`: 9/9
- `UatDsl005TimeoutGrammarTest`: 7/7
- `UatDsl006BodyExecutionTest`: 4/4
- `UatEvt001ReplayTest`: 3/3
- `UatEvt002MultiStepReplayTest`: 2/2

Total: 30 tests, 0 skipped, 0 failures, 0 errors. The Gradle log ended with
`BUILD SUCCESSFUL in 2m 38s`.

## F1 classification

| Surface | F1 state | Evidence |
|---|---|---|
| Accepted core runtime and local primitives | `CERTIFIED_AT_SHA` | 20 application contract suites and installed product scenarios at target SHA |
| `pwd` / `isUnix` runtime-return honesty guard | `CERTIFIED_AT_SHA` | WULpr402 7/7 after stale-path correction |
| Closed execution and body routing | `CERTIFIED_AT_SHA` | 28 architecture fitness rows green |
| `scm-git.checkout` | `F2_PENDING` | Current exact contract suite green, official receipt still owned by F2 |
| `junit.results` | `F2_PENDING` | Current exact contract and workspace rows green, official receipt still owned by F2 |
| `core-utils.*` | `F2_PENDING` | Current exact utility suite green, official receipt still owned by F2 |
| `example.uppercase` | `REFERENCE_CERTIFIED` | Reference fixture only, not a headline product feature |

## Scope and freeze compliance

- No new Step was added.
- No existing Step moved between core and plugin.
- No new DSL feature was added.
- No runtime or durable semantics changed.
- The only source change was a test path repair required to make existing
  certification evidence truthful after the D-026 extraction.
- The full external project matrix remains the responsibility of the external
  release harness.

## Release closure answers

- Reference implementation consulted: none applicable for this certification
  guard repair.
- Behaviour adopted: keep the existing `pwd` and `isUnix` registry/runtime
  behavior and point fitness at the extracted DSL source authority.
- Intentional deviations: none.
- Security implications reviewed: no runtime or privilege surface changed.
- Tests demonstrating the contract: the exact suites and XML windows listed in
  this receipt.

## Remaining F1 follow-up

F1 is complete for its scope. F2 must certify the official plugins with the
same exact-SHA discipline, reconcile the SCM receipt inventory heuristic, and
not promote historical receipts as current candidate evidence.
