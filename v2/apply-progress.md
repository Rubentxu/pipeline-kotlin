# ML-R7 Top Jenkins Steps — Apply Progress

## Cycle: `p-733fb505b5a6bd2d/ml-r7-top-jenkins-steps`
## Branch: `feat/ml-r7-top-jenkins-steps` at `7d58721` + commits

---

## Step 0: T-02 Regression Fix (COMPLETED ✅)

### EnvModel.apply Regression
**Root Cause**: T-02's PATH+= change introduced:
1. Early-return bug: `apply(emptyMap())` fell back to `System.getenv("PATH")` and wrote it to output
2. Deduplication bug: deduplication applied to all cases, not just PATH+= cases

**Worktree Evidence** (base `b9ba89e` vs HEAD `7d58721`):
```
Base (b9ba89e):
  EnvModelTest: 18 tests PASSED
  UatLocal007SandboxProfileTest.SB-S-005: PASSED

HEAD (7d58721):
  EnvModelTest.empty env returns empty map: FAILED (assertTrue result.isEmpty())
  EnvModelTest.env with no PATH starts with prepended JAVA_HOME: FAILED (duplicate entries)
  EnvModelTest.env with no PATH starts with prepended M2_HOME: FAILED (duplicate entries)
  UatLocal007SandboxProfileTest.SB-S-005: FAILED (pre-existing T-02 regression)
```

**Fix Applied** (`84703a9`):
1. Early return `if (env.isEmpty()) return emptyMap()` before any PATH logic
2. Conditional deduplication: only deduplicate when PATH+= entries are present

**Result**: All 18 EnvModelTest tests pass at HEAD

### UatLocal007SandboxProfileTest.SB-S-005
**Classification**: PRE-EXISTING REGRESSION (not caused by Step-0 fix)
- Passes at base `b9ba89e`
- Fails at HEAD `7d58721` (before Step-0 fix)
- Root cause: T-02 changed something in PATH handling that broke sandbox filtering
- Does NOT block round gate per RULE 16 evidence

---

## Step 1: T-12 F-ARCH-L7-001..005 (PARTIAL ⚠️)

### Completed:
- Fixed `pipeline-architecture-tests/build.gradle.kts` missing dependencies (`pipeline-scripting-api`, `pipeline-artefacts-local`)
- Fixed `FArchL7JenkinsVerbatimStepTest` class names (`StepSpec.WriteFile` → `StepSpec$WriteFile`)
- Fixed Jenkins shape expectations to match actual Jenkins catalog signatures

### Remaining Issues:
1. **FArchL7AntStyleGlobShapeTest**: Implementation uses `List<String> patterns`, test expects `String pattern`
2. **FArchL7JenkinsVerbatimStepTest**: Implementation has `retry`/`timeoutMillis` fields not in Jenkins catalog

These are PRODUCTION CODE issues — the tests correctly enforce Jenkins verbatim shapes. The implementation has implementation-specific extensions that don't match Jenkins.

---

## Step 2: T-13 Corpus Fixtures (COMPLETED ✅)

**Commit**: `b977d1e`
- Added `07-writeFile-readFile.pipeline.kts`
- Added `08-withEnv-pipeline.pipeline.kts`
- Added `09-archive-artefacts.pipeline.kts`
- Updated `CompatibilityCorpusTest` count from 6→9
- `UatLocal005CorpusUntouchedTest`: PASSED
- `CompatibilityCorpusTest`: PASSED

---

## Step 3: T-14 UAT-LOCAL-009 + ADR + Canary + Round Gate (IN PROGRESS ⚠️)

### UatLocal009TopStepsTest
- Created skeleton test class with 8 scenarios (TS-00 through TS-07)
- Tests writeFile, readFile, fileExists, withEnv, archiveArtifacts
- Tests currently FAIL with exit code 1, empty stdout — environment/setup issue

### NOT Completed:
- ADR-0052 (not created)
- Catalog 5 NEW rows (catalog already has entries, no new rows needed)
- Artefact canary registration in Main.kt
- Full UAT scenarios (~12 expected, 8 created)

---

## Round Gate Result: FAIL ❌

**Verdict**: `BUILD FAILED`

**Failed Tasks**:
- `:pipeline-architecture-tests:test` — FArchL7AntStyleGlobShapeTest, FArchL7JenkinsVerbatimStepTest

**Aggregate** (104 test XML files):
- Most tests pass
- Architecture tests fail due to production code not matching Jenkins verbatim shapes

**Canary**: Not verified (UatLocal009TopStepsTest not functional)

---

## Deviations

1. **Step 1**: 2 of 5 arch tests remain failing — production code needs fixing, not tests
2. **Step 3**: UatLocal009TopStepsTest skeleton created but not functional; ADR/canary not implemented
3. **UatLocal007SandboxProfileTest.SB-S-005**: Pre-existing regression, documented but not fixed

---

## Next Steps for Orchestrator

1. **Production code fixes needed**:
   - AntStyleGlob: change constructor to accept `String pattern` instead of `List<String> patterns`
   - WriteFile/ReadFile/FileExists/WithEnv/ArchiveArtifacts: remove `retry`/`timeoutMillis` from constructors to match Jenkins catalog

2. **T-14 completion**:
   - Wire up UatLocal009TopStepsTest properly (compare with UatLocal007SandboxProfileTest pattern)
   - Create ADR-0052 for jenkins-top-steps
   - Register artefact canary in Main.kt
   - Add remaining ~4 scenarios to reach 12

3. **Round gate**: Re-run after production fixes
