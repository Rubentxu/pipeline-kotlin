# B1 — UAT status generator: marker binding and verifiable `--check`

- **Date:** 2026-10-15
- **HEAD at authorship:** `4566c449af53fa01a0b531456ecf51b7b70c30b9`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/gen-current-uat-status.py` + `scripts/test_gen_current_uat_status.py`.
  Certification becomes harder, never easier; the real tree's rendered statuses are
  unchanged by this fix (measured below).

Found by the parallel audit worker (`audit uat status generator`) and reproduced with
hermetic temp-repo tests.

---

## 1. Defects

| # | Defect | Consequence |
|---|---|---|
| F1 | `_MARKER_PATTERN` was anchored at `^\s*UAT-EVIDENCE`, so a marker wrapped in backticks did not parse as a marker and fell through to `_parse_freeform`, which certifies on one UID + one status word and **ignores `candidate=`** | Real occurrence: `docs/v2/07-uat/RP2_GATE_RECEIPT.md:57` (`` `UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION | candidate=72f1ce8d` ``) certified UAT-RP-005 without the candidate check |
| F2 | The marker branch recorded `git.last_commit_for(receipt)` as the evidence SHA | A cosmetic edit to a receipt re-dated every certification it carries to a commit that never ran the scenario |
| F3 | `--check` had no test that could fail: the e2e test accepted `rc in (0, 1)`; the header test re-implemented the normaliser inline instead of calling production | A `--check` that always says STALE passed the suite (verified: mutation M2, see §3) |

## 2. Observed RED (4 new tests against the original generator)

```text
Ran 4 tests in 1.819s
FAILED (failures=3, errors=1)
  TypeError: scan_receipts() got an unexpected keyword argument 'malformed_markers'
  FAIL test_backticked_marker_is_bound_to_its_candidate
       AssertionError: 'a98e4cd7…' != 'e288d0f1…'   # file commit vs claimed candidate
  FAIL test_backticked_marker_with_unknown_candidate_does_not_certify
  FAIL test_marker_shaped_line_without_candidate_does_not_certify
```

## 3. Closure

- `_MARKER_PATTERN` tolerates a leading emphasis wrapper (`` ` ``, `"`, `'`, `>`); a
  wrapped marker is still a marker and goes through the candidate binding.
- New `_MARKER_LEAD`: a line that *starts* with `UAT-EVIDENCE` but does not parse as a
  marker certifies **nothing** and is appended to `malformed_markers`; `render_markdown`
  emits a "Malformed marker-shaped lines (refused; no certification)" section. On the
  real tree this discloses two narrative lines that were previously silent no-ops.
- Markers record the **claimed candidate** as the evidence SHA, after validating
  `cand == file_commit or is_ancestor(cand, file_commit)`.
- `scan_receipts(..., malformed_markers=[...])` is the disclosure channel; `main()` wires it.

## 4. Observed GREEN + mutation evidence

```text
python3 scripts/test_gen_current_uat_status.py          # 35 tests (33 + 2 new)
Ran 35 tests in 93.913s
OK
```

M2 mutation (`--check always STALE`, planted and then removed; restore verified
byte-identical to backup):

```text
new CheckModeEndToEndTests under M2 : FAILED — "1 != 0 : --check must be identical right
                                              after write"
old test_check_mode_endtoend_fresh_clone under M2 : OK (1 test in 81.004s)
```

The old suite could not see the mutation; the new production-path test does.

## 5. Measured effect on the real tree (no churn, no silent regeneration)

Pre-fix vs post-fix regeneration are row-identical:

```text
diff <(pre-fix regen rows) <(post-fix regen rows)   -> IDENTICAL rows
diff <(pre-fix UAT-RP-005 row) <(post-fix …)        -> IDENTICAL row content
```

The committed `CURRENT_UAT_STATUS.md` (generated at `69368bc`) diverges from a fresh
regen (`COVERED` 19 → 13, `REFERENCED` 8, strict blockers 2 → 8) **before and after** this
fix; the divergence is pre-existing staleness, NOT introduced here. Regenerating the
committed file and wiring `--check` into admission are governance decisions (they would
flip R4/R6 for six UATs) and were deliberately not taken.

## 6. What this does NOT claim

- F4 (`_collect_repo_text` samples 200 of 1940 files in rglob order), F5 (excerpt uses
  `triples[0]` rather than the selected triple) and F6 (`KeyError` on unknown `UAT-RP-\d{3}`
  ids) remain open.
- No gate runs `--check` yet.

## End-of-work-unit closure

```text
Reference implementation consulted: the generator's own marker contract (T0E-EVID-01
    E1.2) was the contract F1/F2 violated.
Behaviour adopted:                  marker-shaped text certifies only through the marker
                                    path, and the recorded SHA is the claimed candidate.
Intentional deviations:             unparseable marker-shaped lines are disclosed in the
                                    rendered document instead of aborting regeneration.
Security implications reviewed:     none; local git plumbing and text scanning only.
Tests demonstrating the contract:    scripts/test_gen_current_uat_status.py (35 methods;
                                    4 RED before the fix; M2 mutation kill measured).
```
