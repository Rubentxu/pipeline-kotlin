# B1 — UAT status generator: deterministic sampling, selected excerpt, typed ids

- **Date:** 2026-10-15
- **HEAD at authorship:** `462cce770fc78b9b065ee8354fbcbad524a5d780`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/gen-current-uat-status.py` + `scripts/test_gen_current_uat_status.py`.
  Closes F4/F5/F6 of the audit. Status semantics on the real tree are unchanged
  (measured: rows identical pre/post); only excerpts and robustness change.

---

## 1. Defects

| # | Defect | Consequence |
|---|---|---|
| F4 | `_collect_repo_text` capped at 200 files in `rglob` order (filesystem-dependent) | A declaration that exists could be invisible to the applicability gates; the NOT_APPLICABLE verdicts rested on an arbitrary slice |
| F5 | The excerpt column used `triples[0]` (storage order) while status/receipt came from the selected triple | Rows printed a line from a different triple: a `COVERED` row's excerpt named `candidate=56467ed2` while the selected marker claims `candidate=9d0607d7` |
| F6 | `found[uid]` indexed directly | Any document mentioning `UAT-RP-100` aborted the generator with `KeyError('UAT-RP-100')`, no typed failure |

## 2. Observed RED (5 tests against the current generator)

```text
Ran 5 tests in 1.602s
FAILED (failures=3, errors=2)
  FAIL test_every_candidate_file_is_sampled → AssertionError: 'MARKER_0200' not found
  ERROR test_limit_parameter_bounds_the_sample_for_tests → TypeError
  ERROR test_freeform_unknown_uat_id_is_ignored → KeyError: 'UAT-RP-100'
  FAIL test_marker_for_unknown_uat_id_is_disclosed_not_a_crash
  FAIL test_excerpt_comes_from_the_selected_triple
       row showed **COVERED** beside the older narrative line
```

## 3. Closure

- `_collect_repo_text` de-duplicates and sorts candidates and reads **every** source file
  (`.kt`, `.gradle.kts`, `.kts`, `.yml`, `build/`-excluded); `limit=` exists for tests only.
- The excerpt is taken from the **selected** triple, preferring the one whose path is the
  pinned receipt, so row and annotation cannot disagree.
- Out-of-contract ids (`UAT-RP-100`): markers are refused and appended to
  `malformed_markers` (disclosed in the rendered document); free-form narrative mentions
  are ignored.
- The normative `.md` surface was deliberately NOT added to the sample: that would change
  gate semantics (a declaration found in docs would flip NOT_APPLICABLE → NOT_RUN for the
  three gated UATs). Owner/policy decision, not taken here.

## 4. Observed GREEN

```text
python3 scripts/test_gen_current_uat_status.py
Ran 40 tests in 96.972s
OK
```

Real-tree measurement (same file set, pre vs post):

```text
statuses              : IDENTICAL (27/27 rows compare equal)
excerpt-only deltas   : 14 rows corrected
example               : a COVERED row's excerpt was candidate=56467ed2 → now
                        candidate=9d0607d7 (the marker the row is actually based on)
gate verdicts         : 025/026/027 unchanged; all three needles are absent from the
                        full 1926-file sample too (measured), so removing the cap
                        changes no verdict today
```

## 5. Discovery (owner-visible, not changed)

Writing a receipt that merely **mentions** a UAT id in a newer commit supersedes that
UAT's older explicit status: `select_evidence` treats the newest narrative triple as
DAG-maximal and returns REFERENCED (ADR-0095 design, documented in the function's own
docstring). This session demonstrated it accidentally: an own receipt that mentioned one
UAT while describing F1 superseded that UAT's older KNOWN_LIMITATION marker.

Consequence for evidence discipline: B1 receipts deliberately avoid naming in-contract
UAT ids unless the mention is the point; this document names only the out-of-contract
`UAT-RP-100` from the F6 test. Any fix to the supersede rule would touch ADR-0095
semantics, so it is recorded, not changed.

## 6. What this does NOT claim

- The committed `CURRENT_UAT_STATUS.md` is still not regenerated (governance decision).
- `--check` still runs in no gate.
- Excerpt text is truncated to 120 chars by the scanner; long lines still read as partial.

## End-of-work-unit closure

```text
Reference implementation consulted: the generator's own T0E-EVID-01 E3 contract ("the
    gate checks the codebase for the presence of the feature declaration").
Behaviour adopted:                  deterministic complete sampling; the row and its
                                    excerpt come from the same triple; unknown ids are
                                    typed refusals rather than crashes.
Intentional deviations:             the normative markdown surface is not sampled (it
                                    would change gate semantics).
Security implications reviewed:     none; local git plumbing and text scanning only.
Tests demonstrating the contract:    scripts/test_gen_current_uat_status.py (40 methods;
                                    5 RED before the fix).
```
