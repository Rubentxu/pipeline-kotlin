# B1 — Harness evidence reliability (fail-closed JUnit adjudication)

- **Date:** 2026-10-15
- **HEAD at authorship:** `52b9c4915c8a2403750c62deed4abf5afe2f8323`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** harness tooling only (`scripts/`). No production code, no gate scope change,
  no UAT moved.

This receipt records three reliability defects in
`scripts/run-scoped-junit-report.py` (the tool defined by
`W5_SKIP_CLASSIFICATION_AND_GATE_PROVENANCE.md`) and their closure. The W5 receipt remains
historical evidence for its own SHA and is NOT rewritten; this document supersedes only its
skip-verdict and unreadable-file statements.

---

## 1. Defects (each reproduced before the fix)

| # | Defect | Consequence |
|---|---|---|
| D1 | Unreadable/invalid XML was a warning only; the verdict ignored it | One corrupt `TEST-*.xml` beside clean results produced `VERDICT: PASS` (exit 0) over incomplete evidence |
| D2 | Non-numeric or negative count attributes were coerced to 0 | Malformed counters read as an empty, successful suite |
| D3 | Only the first `<testsuite>` of a standard `<testsuites>` document was read | Failures in later suites disappeared from the gate report |
| D4 | `skipped` tests counted as tests | An all-skipped result set could produce `VERDICT: PASS` |

D1–D4 are all the same class of failure the W5 receipt rejected on the other axis: a number
that looks plausible while proving less than it claims.

## 2. Observed RED (before the fix, real CLI, exit codes read)

```text
Ran 3 tests in 0.649s
FAILED (failures=3)
  AssertionError: 0 != 1 : an unreadable fresh result must make evidence FAIL
  AssertionError: 0 != 1 : an invalid JUnit count must fail evidence adjudication
  AssertionError: 0 != 1 : a failure in any child suite must fail the report
```

## 3. Closure

- `read_xml` now aggregates every `<testsuite>` child of `<testsuites>`, rejects unknown
  roots, empty suite lists, non-integer and negative counters (`None` → unreadable).
- `totals` reports `executed = max(0, tests - skipped)` and counts invalid files.
- Verdict is `PASS` only when `failures == 0 AND errors == 0 AND unreadable_files == 0 AND
  executed > 0`. A fresh set with zero executed tests is refused (exit 2, no report body).
- The text report prints an `executed` column; skips stay visible and are never counted as
  executed evidence.

## 4. Observed GREEN (after the fix)

```text
python3 scripts/test_run_scoped_junit_report.py
----------------------------------------------------------------------
Ran 17 tests in 3.371s
OK
```

Real integration path (fresh Gradle XML, not a fixture):

```text
cd v2 && ./gradlew :pipeline-event-harness:test          # BUILD SUCCESSFUL in 21s
python3 scripts/run-scoped-junit-report.py --head "$(git rev-parse HEAD)" \
    --results-dir v2 --boundary-file <boundary-created-before-the-run> \
    -- ./gradlew :pipeline-event-harness:test

head        : 52b9c4915c8a2403750c62deed4abf5afe2f8323  (--head match: True)
                         classes     tests  executed  failures    errors   skips
THIS RUN                       2        18        18         0         0       0
carried-over                   0         0         0         0         0       0
VERDICT: PASS
```

## 5. What this does NOT claim

- The tool is still **invocable, not wired into any gate**. `releaseGate`,
  `candidateAdmission` and `admission-check.py` are unchanged; no mandatory verification was
  replaced or weakened.
- `PRODUCT-GATE` remains `BLOCKED_EXTERNAL` (no CI surface). No gate changes state.
- The `forkEvery = 1` pause in `:pipeline-application:test` is untouched: un-pausing it
  requires the structural fix for the installed-CLI UAT wedge, measured, not inferred.

## End-of-work-unit closure

```text
Reference implementation consulted: Ant/Maven JUnit XML schema (testsuite/testsuites
    attributes); W5 receipt for the boundary-based fresh/carried-over split.
Behaviour adopted:                  fail-closed adjudication: malformed, incomplete or
                                    non-executed evidence fails the verdict.
Intentional deviations:             D1-D3 raise the strictness of a tool that was
                                    invocable-only; no caller contract existed.
Security implications reviewed:     none; reads local test XML only, no network, no
                                    credential handling.
Tests demonstrating the contract:    scripts/test_run_scoped_junit_report.py (17 methods,
                                    executed as a subprocess against the real CLI).
```
