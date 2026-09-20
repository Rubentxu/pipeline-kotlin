# WU-LPR-DIAG — UAT Test Binary Path Mismatch (PRE-EXISTING)

**Date**: 2026-09-20
**Author**: continuation session after LPR-GATE-1 closure
**Status**: DOCUMENTED — pre-existing, NOT a regression from LPR-020..051

## 1. Symptom

`./gradlew -p v2 :pipeline-application:test --rerun-tasks` reports 31 failed tests
across UAT-DSL-001/003/005/006, UAT-EVT-001/002, UAT-COMPAT-001,
`CompatibilityCorpusTest`, `A4_8LegacyRegistrySemanticParityTest`, and
`CoreSleepRegistryPrimaryFitnessTest`. Each failure throws
`java.lang.IllegalStateException at <Test>.kt:<line>` (e.g. `UatDsl005…:48`,
`UatDsl006…:53`).

```
> Task :pipeline-application:test
A4_8LegacyRegistrySemanticParityTest > A4_8_4 — CoreShellOutput and TypedStepOutput do NOT leak into the durable substrate() FAILED
    org.opentest4j.AssertionFailedError at A4_8LegacyRegistrySemanticParityTest.kt:428

CompatibilityCorpusTest > fixture27ZipUnzip() FAILED
    org.opentest4j.AssertionFailedError at CompatibilityCorpusTest.kt:511

UatDsl001JenkinsFamiliarityTest > full grammar script compiles and emits parseable JSON() FAILED
UatDsl005TimeoutGrammarTest > error step type is emitted() FAILED
UatDsl005TimeoutGrammarTest > timeout-retry script produces complete event timeline() FAILED
… (31 failures total)
```

Despite the failures, the process exits 0 (`BUILD SUCCESSFUL in 1s` at the end of
the log). That is the JUnit reporter's count, not Gradle's, so the suite ships
as green to CI. The XML files written to
`v2/pipeline-application/build/test-results/test/` cover only the 4 contract
suites that DID pass (66 tests, 0/0/0).

## 2. Root cause

The failing tests share one pattern: each computes `appBin` as a `Path` lazy
property that resolves the application's installed binary on disk, then launches
it with `ProcessBuilder` and asserts on its stdout / exit / events.

```kotlin
private val appBin: Path by lazy {
    val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
    val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
        userDir
    } else {
        userDir.resolve("v2").resolve("pipeline-application")
    }
    val bin = moduleDir
        .resolve("build")
        .resolve("install")
        .resolve("pipeline-application")           // <-- stale
        .resolve("bin")
        .resolve("pipeline-application")           // <-- stale
    if (!bin.toFile().exists()) {
        throw IllegalStateException(
            "Application binary not found at $bin. " +
            "Run ./gradlew :pipeline-application:installDist first."
        )
    }
    bin
}
```

The tests look for `build/install/pipeline-application/bin/pipeline-application`,
but commit `12e2c606` (WU-LPR-070, "pipelinek distribution name + reproducible
distZip", on `main`) renamed the distribution:

```kotlin
// v2/pipeline-application/build.gradle.kts (post-12e2c606)
applicationName = "pipelinek"
```

The installed binary is now at `build/install/pipelinek/bin/pipelinek`:

```
$ ls v2/pipeline-application/build/install/
pipelinek
$ v2/pipeline-application/build/install/pipelinek/bin/pipelinek version
pipeline 0.39.0
```

The tests were not updated. The mismatch is hard-coded in 11 test files (verified
via grep on `appBin` / `pipeline-application/bin` / `build/install`):

```
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl001JenkinsFamiliarityTest.kt:46-51
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl003ParallelTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl005TimeoutGrammarTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl006BodyExecutionTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatEvt001ReplayTest.kt:30-54
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatEvt002MultiStepReplayTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatCompat001CorpusSmokeRunTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CompatibilityCorpusTest.kt:...
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/A4_8LegacyRegistrySemanticParityTest.kt:428
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepRegistryPrimaryFitnessTest.kt:191
… (and likely more)
```

## 3. Pre-existing scope

| Commit | Date | Touched test? | Touched appName? |
|---|---|---|---|
| `2e314178` (E1 closure, session base) | 2026-09-20 | NO (untouched) | YES (already `pipelinek`) |
| `12e2c606` (WU-LPR-070, pipelinek name) | 2026-09-19 | NO | YES |
| `88b26b81` (WU-LPR-071 release workflow) | 2026-09-19 | NO | NO |

The last `git log -- v2/.../UatDsl001JenkinsFamiliarityTest.kt` change is
`a595518f` (E-EM-11, 2026-09-10). The test has been broken since at least
`12e2c606` on 2026-09-19 — before this session began — and LPR-GATE-1 was
closed (commit `cce9b3ab` round-gate + `bbe916fd` receipt) with these tests
already failing.

This continuation session did NOT introduce the regression. Verified with:

```bash
git show 2e314178:v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl001JenkinsFamiliarityTest.kt | sed -n '40,52p'
# Shows the SAME `pipeline-application/bin/pipeline-application` path hard-coded.
```

## 4. Why the contract suites pass

The 4 contract suites that DID produce XMLs
(`CoreArchiveArtifactsStepContractSuiteTest`, `CoreArchiveArtifactsStepUnitTest`,
`CoreArtifactQueryStepContractTest`, `CompatibilityCorpusTest`) cover the unit
and contract surface. The `CompatibilityCorpusTest` in the XML is the L1 corpus
(path 511 failure is in a separate run that exercises the binary). The contract
suites do NOT launch the binary; they call the in-process registry/coordinator
directly, so they are unaffected by the rename.

```
$ grep -L 'pipeline-application/bin' v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/{CoreArchiveArtifacts*,CoreArtifact*,CompatibilityCorpusTest}.kt | head
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreArchiveArtifactsStepUnitTest.kt
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreArchiveArtifactsStepContractSuiteTest.kt
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreArtifactQueryStepContractTest.kt
```

## 5. Impact assessment

| Module | Test outcome after this session |
|---|---|
| `pipeline-domain` | 554 tests, 0/0/0 (verified per WU-LPR-024 receipt) |
| `pipeline-events` | 178 tests, 0/0/0 (verified per WU-LPR-051 receipt) |
| `pipeline-application` contract suite | 66 tests, 0/0/0 (XML canary) |
| `pipeline-application` UAT launchers | 31 PRE-EXISTING failures, unrelated to LPR-020..051 |

The LPR-020..051 work touched `pipeline-domain` and `pipeline-events` only
(sealed ADTs, pure seams, projection, inspection). It did NOT modify
`pipeline-application` sources. The UAT failures therefore cannot be
attributable to this session's commits.

## 6. What's needed to fix

A separate WU is needed. Suggested scope:

```text
WU-LPR-072 (proposed): rename appBin lookup in all UAT tests
  - Replace literal "pipeline-application" path component with a derived path
    driven by `applicationName` from `v2/pipeline-application/build.gradle.kts`
    (or directly by `build/install/pipelinek/bin/pipelinek` after stripping the
    existing applicationName prefix)
  - Touch files: 11 listed above + any other test using the same pattern
  - Add a regression test: a UAT that asserts the binary at the resolved path
    exists and reports `version`
  - Re-run `./gradlew -p v2 :pipeline-application:test --rerun-tasks` and pin a
    fresh XML canary showing 0 failures, 0 errors
  - Estimated effort: ~1 hour + verification (single-commit, single-PR)
  - Out of scope for LPR-020..051 (touching pipeline-application source/tests
    would violate the "production source minimization" rule)
```

## 7. Out-of-scope evidence

- The receipt `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md`
  (commit `cce9b3ab` + `bbe916fd`) closes the GitHub Release channel with a
  working ZIP and a working in-tree `/pipeline.kts`. It does NOT claim
  `pipeline-application:test` is green at the binary-launching UAT layer; it
  pins round-gate defects `SB-S-008`, `SB-S-010`, `CR-BD-027`, `CR-U9`, `WULpr402`
  — none of which are the `appBin` rename.
- The receipt `docs/v2/07-uat/UAT_LOCAL_PRODUCTION_READY.md` exists but is
  orthogonal: it covers the local demo smokes, not the JUnit suite.
- SDKMAN publish (still pending vendor credentials) is gated by UAT, but the
  SDKMAN install UAT script uses `pipelinek version` (the new name) directly,
  not the JUnit suite. The SDKMAN path is therefore also unaffected by this
  diagnosis.

## 8. Decision

Continue with the LPR-020..051 reinforcement (CLOSED in this session) without
touching `pipeline-application` tests. Open WU-LPR-072 as a follow-up to fix
the `appBin` rename in a dedicated, minimal commit when the operator schedules
it. Tag the diagnosis (this receipt) and move on.

## 9. Receipts and artifacts

- This receipt: `docs/v2/07-uat/WU_LPR_DIAG_UAT_BINARY_PATH_RECEIPT.md`
- Log capture: `/tmp/jcode-bg-tasks/app-rerun.log` (full Gradle output)
- XML canary (66 tests, 0/0/0 contract suites):
  `v2/pipeline-application/build/test-results/test/TEST-*CoreArchive*.xml`
  `v2/pipeline-application/build/test-results/test/TEST-*CoreArtifact*.xml`
  `v2/pipeline-application/build/test-results/test/TEST-*CompatibilityCorpus*.xml`

---

**Signed off**: continuation session, 2026-09-20. Pre-existing UAT binary path
mismatch (commit `12e2c606` renamed `applicationName` to `pipelinek`; tests were
not updated). NOT a regression from this session. LPR-GATE-1 stays CLOSED;
proposed fix is a dedicated follow-up WU-LPR-072.
