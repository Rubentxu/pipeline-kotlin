# B1 — PR classifier receipt: derived facts instead of hardcoded claims

- **Date:** 2026-10-15
- **HEAD at authorship:** `927f7a7c1ba3a488c3d7341e29dd7e69097e1b8f`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/classify-open-prs.py` + `scripts/test_classify_open_prs.py`.
  Only the receipt's claims changed; the PR-classification rules (Dependabot, WU-RP-053,
  #91, #93, docs-only, draft) are untouched.

Found by the parallel audit worker and reproduced against the real module.

---

## 1. Defects

| # | Defect | Consequence |
|---|---|---|
| P1 | `- [x] C1: All 25 OPEN PRs enumerated` was a string literal | The acceptance criterion stayed ticked for any count, including 0 PRs |
| P2 | `zip(prs, classifications)` truncated silently | A receipt could declare 4 PRs, show 3 rows, and sum 3 categories - all ticked |
| P3 | `classify()` default was `KEEP` with "needs manual review" | `KEEP` reads as "mergeable, scope aligned, no superseder"; no check ran, so an unreviewed PR was declared acceptable |
| P4 | `- [x] C4: SHA-stamped provenance` was a literal while the document carried no SHA | Provenance claimed, never present |
| P5 | WU-RP-053 rationale cited "commits 567196c9+" though nothing verified it | An unverified commit claim inside evidence text |
| P6 | `git_log_search` / `git_branch_contains` dead (0 call sites) and `re` import only fed the dead code | Review surface suggesting verification that never happens |

## 2. Observed RED (original module; stash-verified)

```text
Ran 13 tests in 0.006s
FAILED (failures=5, errors=1)
  FAIL test_render_c1_count_is_derived_from_the_enumeration
  FAIL test_render_refuses_length_mismatch
  FAIL test_unknown_pr_is_needs_review_not_keep
  FAIL test_render_c4_unticked_without_sha
  FAIL test_render_markdown_counts_correct        # pinned the fail-open KEEP default
  ERROR test_render_c4_ticked_with_sha            # render had no head_sha parameter
```

## 3. Closure

- `classify()` default returns `NEEDS_REVIEW` ("no rule matched; needs human review").
- `render_markdown` refuses a length mismatch (`ValueError`) instead of truncating.
- C1 prints `{len(prs)} OPEN PRs enumerated`; C2 lists `NEEDS_REVIEW`; the summary
  category loop includes it.
- `render_markdown(..., head_sha=None)`: C4 is ticked only with a real SHA, otherwise
  `[ ] C4` and `**HEAD:** NOT STAMPED`; `main()` passes `git rev-parse HEAD`.
- The WU-RP-053 rationale no longer cites a SHA; dead helpers and the now-unused `re`
  import are deleted.

## 4. Observed GREEN

```text
python3 scripts/test_classify_open_prs.py
Ran 13 tests in 0.004s
OK
```

## 5. What this does NOT claim

- The `Decisions and Discoveries` and `Follow-up Actions` prose still names the PR-004
  moment (PR numbers, PR-005/PR-006 batches); regenerating the receipt today would carry
  that historical context verbatim. Flagged as open, not fixed here.
- `labels` is still fetched and unread; `gh pr list --limit 50` still truncates at the gh
  level (the receipt now states the enumerated count instead of claiming "all 25").
- No network/gh-auth verification was run (the tool's fail-closed gh error path was
  already covered by the audit and is unchanged).

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; PR-004 receipt contract in the
    script's own docstring was the contract it violated.
Behaviour adopted:                  the receipt derives counts, refuses partial tables,
                                    and never declares KEEP without a rule match.
Intentional deviations:             NEEDS_REVIEW added as a sixth category so no PR is
                                    forced into an unfounded bucket; two existing test
                                    expectations updated to the strengthened contract.
Security implications reviewed:     none; gh CLI and local git only.
Tests demonstrating the contract:    scripts/test_classify_open_prs.py (13 methods,
                                    stash-verified RED before the fix).
```
