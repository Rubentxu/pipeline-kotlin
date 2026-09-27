# T0E-EVID-01 — E2-VERIFY at HEAD 9d0607d7 (post R5.1 burn-down slice)

**Date:** 2026-09-27T07:49Z
**HEAD:** `9d0607d7ca77b9a32cafc...` (`wu/rp-053r-red-fixtures`, 11 ahead of `origin/main ab5bec80`)
**Operator brief:** 2026-09-26T13:22Z (T0.E)
**Generator:** `scripts/gen-current-uat-status.py` (D-007 + T0E-EVID-01 E1 + E3)
**Admission:** 6/6 PASS (`scripts/admission-check.py --head 9d0607d7`)

This file is the FRESH re-execution of the E2 oracles against the
live branch tip, captured AFTER the WU-RP-040 R5.1 detekt burn-down
slice (11 new commits since the rc7 merge). The counts and outcomes
are byte-read from the JUnit XMLs in `build/test-results/test/` per
AGENTS.md rule 25 (canonical test result source).

## Single-batch run (--rerun-tasks)

```bash
cd v2 && ./gradlew :pipeline-application:test \
  --tests DslCompiledPipelineCompilerTest \
  --tests CliCompileErrorExitsOneTest \
  --tests Lpr011SecretRedactionTranscriptUatTest \
  --tests Lpr011r2SecretRedactionAtRestUatTest \
  :pipeline-events:test --tests JsonEventLogRoundTripTest \
  :pipeline-domain:test --tests DivergenceDetectorTest \
  --rerun-tasks
# BUILD SUCCESSFUL in 1m 1s
# 68 actionable tasks: 68 executed
# exit=0
```

## Per-class oracle results (HEAD `9d0607d7`)

```text
DslCompiledPipelineCompilerTest:
  sha256 = 0083e5b927d98aad20130184703a69c85f24fec22e19d2aaac048ff403fe2480
  tests="13" skipped="0" failures="0" errors="0"  → 13/13 PASS

CliCompileErrorExitsOneTest:
  sha256 = d98df8ba58d547a413ee9a0740202d3930b6353be55e8d992cac490302a29baf
  tests="3"  skipped="0" failures="0" errors="0"  → 3/3 PASS

JsonEventLogRoundTripTest:
  sha256 = 551c3a40032a663c6fbeaa944738772b04291880fd2ebdad766b93903dfc7fb8
  tests="28" skipped="0" failures="0" errors="0"  → 28/28 PASS

DivergenceDetectorTest:
  sha256 = 1c29ed0e31780e8bcdd55453145cee09a290837df448b7f2f121cc64f71844af
  tests="4"  skipped="0" failures="0" errors="0"  → 4/4 PASS

Lpr011SecretRedactionTranscriptUatTest:
  sha256 = 8f24ba865798c9737b8e247db2a6097b1917fe5293f97c4d53d5161a9c110c78
  tests="6"  skipped="0" failures="0" errors="0"  → 6/6 PASS

Lpr011r2SecretRedactionAtRestUatTest:
  sha256 = 410d285c35a2a1b902485b5a4c22bf277be035df2431fa11c03d2c1c53dd4707
  tests="11" skipped="0" failures="0" errors="0"  → 11/11 PASS
```

**TOTAL: 65/65 tests PASS** across 6 test classes, 0 failures,
0 errors. Same counts as `T0E_EVID_01_RECEIPT.md` (HEAD `56467ed2`)
and `T0E_EVID_01_RECEIPT.md` § E2-VERIFY@85b906c9 (HEAD `85b906c9`).

## UAT attribution (machine-readable)

```text
UAT-EVIDENCE | UAT-RP-002 | COVERED | candidate=9d0607d7 | tests=oracle_a_gradlew_version+oracle_b_workflow_artifact_upload | exit=0
UAT-EVIDENCE | UAT-RP-004 | COVERED | candidate=9d0607d7 | tests=DslCompiledPipelineCompilerTest:13+CliCompileErrorExitsOneTest:3 | exit=0 | xml-sha256=0083e5b927d98aad20130184703a69c85f24fec22e19d2aaac048ff403fe2480+d98df8ba58d547a413ee9a0740202d3930b6353be55e8d992cac490302a29baf
UAT-EVIDENCE | UAT-RP-010 | COVERED | candidate=9d0607d7 | tests=JsonEventLogRoundTripTest:28 | exit=0 | xml-sha256=551c3a40032a663c6fbeaa944738772b04291880fd2ebdad766b93903dfc7fb8
UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=9d0607d7 | tests=DivergenceDetectorTest:4 | exit=0 | xml-sha256=1c29ed0e31780e8bcdd55453145cee09a290837df448b7f2f121cc64f71844af
UAT-EVIDENCE | UAT-RP-015 | COVERED | candidate=9d0607d7 | tests=Lpr011SecretRedactionTranscriptUatTest:6+Lpr011r2SecretRedactionAtRestUatTest:11 | exit=0 | xml-sha256=8f24ba865798c9737b8e247db2a6097b1917fe5293f97c4d53d5161a9c110c78+410d285c35a2a1b902485b5a4c22bf277be035df2431fa11c03d2c1c53dd4707
UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION | candidate=789e6e01 | tests=supersedence_per_ADR-0095 | exit=0
UAT-EVIDENCE | UAT-RP-024 | KNOWN_LIMITATION | candidate=789e6e01 | tests=structural_partial_1-repo_dogfooding | exit=0 | note=partial 1-repo coverage; ≥2 repos structurally impossible in autonomous session per WU_RP_046_R2 (covers inv1 but not inv2)
```

## Certifier + admission at HEAD `9d0607d7`

```text
$ python3 scripts/gen-current-uat-status.py --out docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
WRITTEN docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md (27 UATs classified)

$ python3 scripts/admission-check.py --head 9d0607d7
Results: 6 PASS, 0 FAIL
  [PASS] R1 current_state exists
  [PASS] R2 state.HEAD is ancestor of candidate
  [PASS] R3 working tree clean (exact-path exclusions)
  [PASS] R4 no blocking UAT states
  [PASS] R5 receipt provenance (Git ancestry, per UAT)
  [PASS] R7 origin/main not ahead

$ python3 scripts/test_gen_current_uat_status.py
Ran 29 tests in 38.620s
OK

$ python3 scripts/test_admission_check.py
Ran 46 tests in 10.394s
OK
```

## Status summary (regenerated against `9d0607d7`)

- **Total UATs (PRDY-003 contract):** 27
- **COVERED:** 19
- **PARTIAL:** 1 (UAT-RP-018 sandbox resource limits)
- **KNOWN_LIMITATION:** 2 (UAT-RP-005 MANIFEST supersedence, UAT-RP-024 ≥2-repos structural impossibility)
- **REFERENCED:** 2 (UAT-RP-001 perf baseline narrative, UAT-RP-016 PERF)
- **NOT_APPLICABLE:** 3 (UAT-RP-025/026/027 — applicability gates)

Counts match `T0E_CLOSURE_RECEIPT.md` exactly: certifier is stable
across HEAD `e393eb89` → `9d0607d7` (11 R5.1 commits added).

## Notes on digest stability

JUnit XML SHA-256 digests are NOT portable across re-runs because
Gradle writes a fresh `<testsuite ...>` wrapper per invocation
(timestamp + hostname + random id). The digests above were captured
at the live branch tip **at the moment of verification**, not at HEAD
`9d0607d7` specifically. They serve as a witness that the E2 oracles
ran and produced the claimed counts. A re-run at any later commit
produces **different digests with identical test counts**
(13/3/28/4/6/11 = 65/65 PASS). The stable invariant is the count +
failures=0 + errors=0, not the specific SHA-256.

## Operator acknowledgement (T0.E BLOCKED → UNBLOCKED)

The earlier `TRAIN_0_T0E_RECEIPT.md` documented T0.E as BLOCKED due
to a regen-drift defect in the certifier (D-007). T0E-EVID-01
delivered E1+E2+E3 to fix the certifier correctness (DAG-maximal
commit selection + per-UAT status scoping + KNOWN_LIMITATION marker
+ applicability gates), and admission R4 has been GREEN at HEAD
`56467ed2` (T0E_EVID_01_RECEIPT), `85b906c9` (T0E_EVID_01 §E2-VERIFY),
and now `9d0607d7` (this receipt). R4 admits the candidate.

## What did NOT happen in this run

- No code modification to certifier or admission.
- No new debt opened.
- No D-007 touched (already fixed by T0E-EVID-01).
- No merge to main.
- No TRAIN-1 work.
- No PRDY-010 opened.
