# UAT/E2E retirement inventory — 29981033 (2026-10-15)

> **Discovery ledger, not deletion approval.** This is a snapshot of `main` at `29981033ee1374f89e5711c7d5c2bc4b620e1b2a`. No tests or production files have been modified. Every entry starts at `PENDING_REVIEW`.

## Static discovery results

- **85 explicit UAT/E2E/EndToEnd/Acceptance test-source filenames** (including `AbstractE2ETest`, a support class).
- **27 additional filenames** plausibly carrying process/integration/installed-distribution acceptance behavior; inspection of bodies is required.
- **5 associated support files** whose callers must be traced before deletion.
- **9 runner/status scripts** whose invocations need to be traced.
- **43 further test classes discovered through implementation code search** for AppBinSupport, OwnedSubprocess.run or ProcessBuilder; some may be legitimate unit tests and must be classified by inspecting their behavior.
- **169 total ledger entries** in [uat-e2e-retirement.csv](uat-e2e-retirement.csv).
- **920 Kotlin/Java files** under `src/test` in this snapshot; filename discovery alone does **not** establish that all acceptance behavior has been found.

Explicit matches by module:

- `v2/pipeline-application`: 74
- `pipeline-cli`: 6
- `v2/pipeline-scripting-kotlin24`: 3
- `v2/pipeline-release`: 1
- `v2/pipeline-testkit`: 1

### Classification contract

| Field | Meaning |
|---|---|
| `source_kind` | EXPLICIT_UAT_E2E: filename declares acceptance; POTENTIAL_PROCESS_ACCEPTANCE: must inspect body; OBSERVED_PROCESS_USAGE: source uses a relevant process API but may still be unit-level; TEST_SUPPORT: fixtures/base class, not necessarily runnable; SCRIPT_RUNNER: external test script |
| `scope` | V2_GRADLE is in the included build; LEGACY_NOT_IN_DEFAULT_V2_BUILD is outside the root composite build; SCRIPT_ENTRY_POINT is manually invoked |
| `retirement_policy` | Proposed **review priority only**, never a finding of redundancy |
| `status` | PENDING_REVIEW by default. No row says DELETE_READY |
| `replacement_evidence` | Must become a specific contract test path, scenario identifier, fidelity and fresh green result for the same commit |

### Evidence-based findings to address before deleting tests

1. **Production impact can be under-selected.** `ImpactResolver.isProductionPath()` covers `.gradle.kts` and Kotlin/Java `src/main`; it does not generically recognize resources, Proto, shell scripts and other behavioral inputs. `ContractCatalogue.CONTRACTS` contains eight capability rows, far fewer than the V2 product paths. Unlisted recognized production files refuse; unrecognized file categories select nothing.
2. **JUnit evidence can be non-executed.** `EvidenceReader.classNameFrom()` checks errors/failures but not `tests > 0` nor `skipped < tests`; make a deliberately all-skipped/zero-test report a rejecting mutation.
3. **Fidelity discrepancy.** Normative ADR-0072 specifies HF0–HF6. `ContractCatalogue.Fidelity` only provides HF0–HF4, while `Gate` checks suites, not proof that their required fidelity was achieved.
4. **General unit test cost is tied to integration.** `:pipeline-application:test` has `installDist` and external-plugin task dependencies and uses `maxParallelForks=1`, `forkEvery=1`. Separate HF0/HF1 from installation/restart/sandbox suites.
5. **Order-dependent UAT.** `UatLocal010SmokeE2ESandboxOfflineTest` states that `UatLocal010SmokeE2ESandboxTest` must first warm the Gradle cache. Replace with explicit cache-fixture provisioning.
6. **Legacy V1 lane.** The root build includes `v2`; `core/` and `pipeline-cli/` legacy test suites are not run by the default V2 `check` merely because they exist in Git.

### Retirement protocol

1. For each CSV row, identify the concrete **user-visible behavior** and mapping to an existing spec/scenario; label the producing lane (HF0..HF6) and running task.
2. Search call sites of each fixture/runner; capture the specific fault or mutation previously caught. A safety or recovery guarantee **must not disappear** with the historical test.
3. Move reusable assertions into concise HF0/HF1 contract tests; retain one minimal real-process/restart/sandbox/distribution canary per non-simulable boundary, scoped to merge or release.
4. For redundant feature-cycle UATs, run replacement tests at the same commit, record the evidence in `replacement_evidence`, and change status to `DELETE_READY` **only after** mutation/negative-path and consumer coverage agree.
5. Delete tests and orphaned fixtures as one atomic commit, remove Gradle/task/script references, and verify selected gates and the release candidate. Do not delete all tests in a folder by glob.
6. Regenerate this snapshot at each removal; verify that no new UAT/E2E/installed canary appears unclassified.

### Established specifications and implementation anchors

- `docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md`
- `docs/v2/03-specifications/EXECUTABLE_SCENARIO_CORPUS.md`
- `docs/v2/04-adrs/ADR-0072-layered-test-harness-fidelity.md`
- `docs/v2/06-quality/TEST_STRATEGY.md`
- `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`
- `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/catalog/ContractCatalogue.kt`
- `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ImpactResolver.kt`
- `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/EvidenceReader.kt`

**Limit:** Name/path census plus indexed GitHub code-search probes (at most 100 matches per query); the code probes are not exhaustive and do not replace a full AST/source scan. This is, not an executed coverage audit. Confirm contents of `POTENTIAL_PROCESS_ACCEPTANCE` and inspect non-matching tests with subprocesses before claiming exhaustiveness. Do not use this snapshot to infer passed/failed tests.
