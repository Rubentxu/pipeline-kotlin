# B1 — Admission gate: zero-evidence fail-closed hardening

- **Date:** 2026-10-15
- **HEAD at authorship:** `52b9c4915c8a2403750c62deed4abf5afe2f8323`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/admission-check.py` + `scripts/test_admission_check.py`. Rule
  semantics are hardened (stricter), never relaxed; no rule was removed and no existing
  pass/fail decision on a well-formed repo changes.

Companion to `B1_HARNESS_EVIDENCE_RELIABILITY_RECEIPT.md`: same failure class — a number
that looks plausible while proving less than it claims — found in the admission gate.

---

## 1. Defects (each reproduced before the fix)

| # | Defect | Consequence |
|---|---|---|
| A1 | `check_r4_no_blocking_uats` treated `rows == []` as "no blocking UATs" | A truncated or format-drifted `CURRENT_UAT_STATUS.md` admitted the candidate while verifying zero UATs; R5 was vacuously true for the same reason |
| A2 | The driver evaluated R2 only `if state_text:`, but R1 passes when the file merely exists | An existing **empty** `CURRENT_STATE.md` produced no R2 verdict at all: PASS by absence of the rule, not by verification of ancestry |

## 2. Observed RED (before the fix, real CLI/library entry points)

```text
FAIL: test_empty_uat_status_blocks
  AssertionError: True is not false : zero parseable rows must fail closed

FAIL: test_empty_current_state_fails_r2_instead_of_skipping_it
  AssertionError: '[FAIL] R2' not found in
  'Admission check for candidate=1d730...\n\nResults: 3 PASS, 2 FAIL\n
   [PASS] R1 current_state exists\n ... [PASS] R7 origin/main not ahead\n'
```

The second output is the defect made visible: R2 simply was not among the results.

## 3. Closure

- R4 now refuses a status file with zero parseable UAT rows:
  `R4 CURRENT_UAT_STATUS.md contains no parseable UAT rows (zero evidence cannot be admitted)`.
- R2 is now evaluated whenever `CURRENT_STATE.md` exists. A file that exists but is empty
  yields an explicit `[FAIL] R2 ... cannot parse HEAD` instead of being skipped.
- No other rule changed. The real repository's status file has 27 parseable rows, so A1
  does not alter its verdict.

## 4. Observed GREEN (after the fix)

```text
python3 scripts/test_admission_check.py <the two new tests>
Ran 2 tests in 2.029s
OK

python3 scripts/test_admission_check.py            # full suite
Ran 48 tests in 58.188s
OK
```

## 5. What this does NOT claim

- The admission gate is not relaxed anywhere; the changes only convert silent PASS paths
  into explicit FAIL paths for empty evidence.
- `R7` still returns PASS when `origin/main` is absent (documented pre-existing choice, out
  of scope here).
- No CI surface is claimed; `PRODUCT-GATE` is untouched.

## End-of-work-unit closure

```text
Reference implementation consulted: PRDY-006R2 contract in the tool's own docstring;
    the zero-evidence guard pattern from the run-scoped report fix in the same session.
Behaviour adopted:                  zero evidence is not evidence of success: R4 refuses
                                    an unparseable status surface and R2 is never skipped.
Intentional deviations:             A1 lives in R4 (the status-file rule) rather than a
                                    new rule, so the R0..R7 numbering stays stable.
Security implications reviewed:     none; git plumbing and local files only, no network,
                                    no credential handling.
Tests demonstrating the contract:    scripts/test_admission_check.py
                                    (2 new methods; 48/48 in the full suite).
```
