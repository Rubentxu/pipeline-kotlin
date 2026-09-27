# TRAIN-040-FINAL F2 Official Plugin Certification Receipt

**Status:** CLOSED_GREEN for the certification target SHA
**Cycle:** `p-733fb505b5a6bd2d/train-040-final`
**WorkItem:** `5e2be1b8-7db0-40b0-a556-a4ff6b0ad4d2`
**Certification target SHA:** `417b02218b137dbfdfcc75090c08c50b583cfebd`
**Declared version:** `0.40.0-rc8`

## Decision

F2 certifies the frozen official plugin surface at the target SHA:

- `scm-git.checkout`
- `junit.results`
- `core-utils.findFiles`
- `core-utils.readJson`
- `core-utils.writeJson`
- `core-utils.readYaml`
- `core-utils.writeYaml`
- `core-utils.zip`
- `core-utils.unzip`
- `core-utils.sha256`

All plugins use the same registry, typed codec, capability admission, durable
boundary, replay policy, and installed distribution path. No plugin-specific
coordinator route or compiler case was added.

F2 also closed one correctness gap discovered by the real installed scenario:
inside `dir("hello-world")`, `junit.results` with `workspaceRoot = "."`
resolved against the pipeline root instead of the current nested directory.
The fix makes `WORKSPACE_IDENTITY_CAPABILITY` follow the immutable
`ShOptions.workingDirectory` projection. It applies equally to core workspace
projection and official plugins. This was a permitted 0.40 freeze correctness
fix, not a new feature or plugin classification change.

## Exact target evidence

### Application boundary and plugin contracts

Fresh XMLs at the target SHA were observed between
`2026-09-27T14:30:45Z` and `2026-09-27T14:30:46Z`:

| Suite | Tests | Skipped | Failures | Errors |
|---|---:|---:|---:|---:|
| `F5_1_ScmGitNegativePathsTest` | 11 | 0 | 0 | 0 |
| `F5_1_ScmGitProviderProvenanceTest` | 4 | 0 | 0 | 0 |
| `F5_1_ScmGitStepContractTest` | 10 | 0 | 0 | 0 |
| `F5_2_JUnitStepContractTest` | 24 | 0 | 0 | 0 |
| `ScmGitWorkspaceIsolationTest` | 3 | 0 | 0 | 0 |
| `WcScmE2EBothPluginsIntegrationTest` | 3 | 0 | 0 | 0 |
| `Lfc2PolicyReadinessFitnessTest` | 16 | 0 | 0 | 0 |
| `CanonicalRuntimeCapabilityAccessTest` | 5 | 0 | 0 | 0 |
| **Total** | **76** | **0** | **0** | **0** |

The rows cover identity, typed codecs, schema, capability admission, negative
paths, provenance, workspace isolation, concurrent workspace separation,
canonical dual-plugin execution, and the nested working-directory regression.

### SDK module evidence

The plugin modules were unchanged by the target-SHA runtime fix. Their fresh
module evidence was executed at parent SHA `56770d20` and is reusable because
no plugin module, codec, contributor, fixture, or test input changed in
`417b0221`:

| Suite | Tests | Skipped | Failures | Errors |
|---|---:|---:|---:|---:|
| `CoreUtilsStepContractSuiteTest` | 112 | 0 | 0 | 0 |
| `YamlSafetyCharacterisationTest` | 6 | 0 | 0 | 0 |
| `JUnitReportParserTest` | 8 | 0 | 0 | 0 |
| `CoreScmGitCheckoutStepContractSuiteTest` | 20 | 0 | 0 | 0 |
| `GitCheckoutCredentialsRefFailClosedTest` | 1 | 0 | 0 | 0 |
| `GitCheckoutExecutorTest` | 4 | 4 | 0 | 0 |
| `GitPollExecutorTest` | 2 | 2 | 0 | 0 |
| `GitChangelogWriterTest` | 2 | 2 | 0 | 0 |

The eight skipped SCM executor rows are legacy executor tests marked skipped in
this repository. The certification rows are the contract, fail-closed
credential, provenance, workspace, canonical engine, and installed distribution
proofs. No skipped row was reclassified as a failure or silently claimed green.

### Installed distribution and discovery

The real installed distribution at the target SHA was executed with a fresh
local bare Git fixture and a fresh `.pipeline.kts` scenario. No `--plugin-jar`
flags were supplied. The binary reported:

```text
Discovered external Step plugins: scm-git, junit, utilities
Pipeline finished with SUCCESS
```

The observed event sequence included:

```text
CompilationStarted -> CompilationFinished -> RunStarted
-> StepStarted[scm-git] -> StepFinished[scm-git]
-> DirEntered[workspace/hello-world]
-> StepStarted[sh] -> StepFinished[sh]
-> StepStarted[junit] -> StepFinished[junit]
-> DirExited -> StageFinished success -> RunFinished success
```

The first run on the parent candidate exposed the defect and failed closed
with `FailureKind.USER` because it looked for
`workspace/build/test-results/test/results.xml` instead of the nested
`workspace/hello-world/...` path. After the fix, the same installed fixture
completed successfully at `2026-09-27T14:31:03Z`.

The compatibility corpus was also executed at the parent candidate with
`CompatibilityCorpusTest`: 30 tests, 0 skipped, 0 failures, 0 errors. The
official utility fixtures are unchanged by the runtime fix and remain valid
for the target candidate.

## Inventory reconciliation

The machine inventory generator previously recognized only core and historical
G8 receipt names. That left all SDK plugins as `REGISTERED` even when their
current contract and installed evidence were green. This F2 receipt is now an
explicit receipt authority for all ten frozen official plugin keys, and the
generator maps each of those keys to this receipt. Historical receipts remain
historical evidence and are not promoted by inference.

Expected post-reconciliation inventory state:

```text
SDK plugin keys:        10
F2 certified SDK keys:  10
Official plugin state:  CERTIFIED_AT_SHA
```

## Scope and freeze compliance

- No new Step was added.
- No existing Step moved between core and plugin.
- No new DSL feature was added.
- No plugin-specific runtime route was added.
- The only production change was the correctness fix for the existing typed
  nested workspace seam.
- The external project matrix remains the responsibility of the external
  release harness.

## Release closure answers

- Reference implementation consulted: existing project Jenkins adaptation
  receipts and the current F5.1/F5.2 contract documentation. No external code
  was copied.
- Behaviour adopted: official plugins resolve typed relative paths against the
  active immutable workspace scope and execute through the canonical registry.
- Intentional deviations: none for the frozen official plugin surface.
- Security implications reviewed: YAML safety, ZIP Slip protection, JUnit XML
  hardening, credential-reference redaction, capability admission, and
  fail-closed discovery were covered by the listed suites.
- Tests demonstrating the contract: the exact XML suite rows and installed
  event sequence in this receipt.

## Remaining F2 follow-up

F2 is complete for its scope. F3 is responsible for the complete local product
UAT profile. F4 must package the immutable release candidate and manifest. The
external harness remains the authority for stable promotion.
