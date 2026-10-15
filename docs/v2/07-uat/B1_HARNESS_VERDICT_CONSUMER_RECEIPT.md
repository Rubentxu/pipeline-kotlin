# B1 — Harness verdict consumer: typed, minimal-evidence, hermetic

- **Date:** 2026-10-15
- **HEAD at authorship:** `c6dbe827da5c36f0c590a7e45bfebc4cca4dd41e`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/consult-harness-verdict.py` + `scripts/test_consult_harness_verdict.py`.
  The consumer contract is tightened (more INVALID, never fewer); PASS becomes harder,
  never easier.

Found by the parallel audit worker (`audit consult-harness-verdict`) and reproduced with
hermetic tests against the real module.

---

## 1. Defects

| # | Defect | Consequence |
|---|---|---|
| V1 | `elif result_field.upper() == "FAIL"` treated ANY missing/unknown/case-shifted `failure_kind` as `FAIL_NON_REPRODUCIBLE` + **exit 0** | A FAIL verdict with no discriminator exited 0, the same code as PASS; the documented exit 3 for "missing critical fields" was unreachable for this field |
| V2 | `.upper()`/`.lower()` on raw JSON values | `{"result": 1}` or a top-level JSON list raised `AttributeError` out of `main()`: exit 1 with a traceback, no receipt, outside the documented 0/2/3/4/75 |
| V3 | PASS was classified from `result` alone; digests were compared as strings without shape validation | A verdict with empty `scenario`/`harness_sha` was PASS exit 0, and `"abc" == "abc"` produced `zip_match=True` |
| V4 | Sidecar selection accepted any `*.zip.sha256` (last match wins) and swallowed fetch errors with `continue` | The digest could come from a different candidate's asset, or a stale earlier value, while the receipt named these bytes |
| V5 | `test_consult_harness_verdict.py` `MissingCase` alone lacked `_patch_receipt_dir()` | Every suite run fabricated `docs/v2/07-uat/RECEIPTS/consult/<candidate>-<ts>/verdict.json` inside the tracked tree (HARNESS FIDELITY LAW §4); the path is also an admission R3 exclusion, so the pollution was invisible to the dirty check |

## 2. Observed RED (original module; stash-verified)

```text
Ran 19 tests in 0.066s
FAILED (failures=6, errors=2)
  ERROR  test_non_string_result_field_is_invalid      # AttributeError escaped main()
  ERROR  test_top_level_json_list_is_invalid          # same
  FAIL   test_fail_without_failure_kind_is_invalid    # exit 0 where 3 is required
  FAIL   test_fail_with_unknown_failure_kind_is_invalid
  FAIL   test_failure_kind_is_normalised
  FAIL   test_pass_requires_scenario_and_harness_sha
  FAIL   test_zip_sha256_must_be_64_hex
  FAIL   test_missing_case_writes_under_the_env_root_only
```

Sidecar subset (with a readable fake body):

```text
Ran 2 tests in 0.029s
FAILED (failures=2)
  AssertionError: 'ffffffff…ffff' is not None   # a FOREIGN digest was returned
```

## 3. Closure

- `failure_kind` is a closed set: `reproducible` → exit 2, `transient` → exit 0
  (`FAIL_NON_REPRODUCIBLE`), anything else - including absent - → INVALID exit 3 with the
  raw value in `detail`. Values are trimmed and lower-cased.
- Verdict documents are decoded with typed extraction (`_text_field`); non-string values
  are absent fields, and a non-object document is INVALID exit 3 with a receipt.
- PASS requires: 64-hex ZIP digest on both sides, non-empty `scenario`, and a
  `harness_sha` shaped like a commit (`^[0-9a-f]{7,40}$`).
- Sidecar selection requires exactly one asset named `pipelinek-<candidate>.zip.sha256`;
  zero, several, or an unreadable sidecar yields `None` → INVALID, and no fetch happens
  for foreign or ambiguous siblings.
- Receipt root is injectable via `PIPELINEK_CONSULT_RECEIPT_ROOT`; the test base class
  points every case at a temp root, so a future case that forgets the mock is still
  hermetic. The receipt dirs fabricated by the RED run were removed.

## 4. Observed GREEN

```text
python3 scripts/test_consult_harness_verdict.py
Ran 19 tests in 0.073s
OK
```

## 5. What this does NOT claim

- **Publisher self-attestation remains.** `verify_publisher` still reads the claimed
  publisher from the document under validation; binding it to the GitHub commit author
  requires resolving `last_commit_sha` through the API and is a contract decision
  (which login is authoritative: author, committer, or the app that produced it).
  Recorded as open, not silently changed.
- No network/gh-auth path was exercised; all tests stub the transport.

## End-of-work-unit closure

```text
Reference implementation consulted: the consumer's own documented exit-code table
    (exit 3 = "verdict.json missing critical fields") was the contract V1 violated.
Behaviour adopted:                  absent or untyped critical fields are INVALID; PASS
                                    needs declared evidence; digests must be 64-hex and
                                    belong to the candidate's own asset.
Intentional deviations:             `transient` is the only non-actionable kind accepted,
                                    because it is the value this repository's own test
                                    documents; other strings fail closed until defined.
Security implications reviewed:     none beyond provenance hardening; no network in tests.
Tests demonstrating the contract:    scripts/test_consult_harness_verdict.py (19 methods,
                                    stash-verified RED before the fix).
```
