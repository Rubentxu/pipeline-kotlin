# PRDY-006R2 Receipt: Hermetic Admission Gate with R0 Precondition and R5 Provenance

**Generated at (UTC):** 2026-09-26T11:03Z
**Replaces:** None. PRDY-006R2 supersedes PRDY-006R; the latter remains valid for the R2 ancestor invariant it introduced.

---

## Summary

PRDY-006R2 converts the admission check from a session-state-dependent helper
into a hermetic, fail-closed, reproducible gate. Four defects are closed;
all evidence is derived from Git-versioned data (commit ancestry, receipt
provenance), not from filesystem mtime or working-tree dirtiness of the
session running the test.

The gate is now an executable contract: two clones of the same candidate
SHA produce identical decisions, three consecutive runs on the same clone
are stable, bogus SHAs are rejected consistently, and receipts without
Git history are fail-closed.

## Defects closed

### Hallazgo 1 — R3 was not hermetic (tests depended on dev repo state)

`test_r3_blocks_source_files` required `scripts/admission-check.py` to be
dirty in the development repo. The moment the operator committed the gate,
the test broke.

**Fix:** Tests now build a `TempGitRepo` (real `git init` in a temp dir) and
touch files inside it. The test never depends on the dev repo's working
tree or HEAD. **42/42 tests pass in a fresh temp repo**, never assuming
the surrounding checkout's state.

### Hallazgo 2 — Bogus candidate SHA made several rules fail-open

`test_r5_fails_on_bogus_candidate_sha` actually pinned that behaviour as
acceptable. The test name was misleading: a candidate that does not exist
in the repo degrades several rules (R5, R7) to PASS because `git show` and
`git merge-base --is-ancestor` silently return failure on missing
commits.

**Fix:** New **R0** precondition. Before any other rule runs, the gate
calls `git cat-file -e <candidate>^{commit}`. Bogus, malformed, or
non-commit inputs exit 2 (R0 ERROR). Short SHAs (≥4 hex) are still
accepted because Git resolves them natively when unambiguous.

### Hallazgo 3 — R5 freshness used filesystem mtime

R5 compared the youngest receipt's `path.stat().st_mtime` against the
candidate commit time minus `--max-receipt-age-days`. mtime is not in
Git, can change on copy/restore, and is not reproducible across clones.
A single fresh receipt could make the entire suite PASS even when
relevant evidence was stale.

**Fix:** **R5** now derives provenance from Git. The rule reads
`CURRENT_UAT_STATUS.md`, finds the receipt declared for each UAT, and
runs `git log --format=%H -n 1 -- <receipt-path>` to obtain the receipt's
last-modifying commit. R5 fails closed if:

- the receipt has no provenance (never committed),
- the receipt's provenance commit is not an ancestor of the candidate
  (evidence for a SHA that does not yet exist).

`--max-receipt-age-days` CLI flag is removed because freshness is now
expressed as ancestry, not as a window.

### Hallazgo 4 — R3 exclusions used substring matching

The previous code did `excl in path_norm` where `excl in {".agent/",
"CURRENT_STATE.md", ...}`. A file like `pkg/agent_router/foo.kt`
contained the substring `agent/` and was accidentally excluded. The same
problem affected `CURRENT_STATE.md.bak` and any other path that happened
to contain the substring.

**Fix:** **R3** now uses exact-path matching:

- prefix `.agent/` (covers `agent/foo`, `agent/scratch/x.md`, etc.),
- exact files `docs/v2/08-production-readiness/CURRENT_STATE.md` and
  `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md`.

Plus `--untracked-files=all` so Git's porcelain output reports
untracked files individually instead of collapsing them to the parent
directory (a separate bug that broke a different test).

## Reproducibility invariant

The decision of admission depends only on:

```text
candidate SHA C
+ content/versioning of repository R
+ explicit configuration (--root, --strict)
```

NOT on:

- filesystem mtime,
- dirty files left by the session running the test,
- accidental filesystem ordering,
- previous state of another agent.

**Proven by:** `test_two_clones_same_decision` (clone the same repo
twice, run admission on each, assert identical output) and
`test_three_consecutive_runs_same_decision` (three runs in a row on the
same clone, assert identical output).

## Verification matrix

| Scenario | Expected | Observed | Where |
|---|---|---|---|
| Bogus SHA `0`×40 | exit 2 (R0 ERROR) | exit 2, stderr `R0 ERROR` | `test_r0_driver_exits_2_on_bogus`, manual run |
| Malformed SHA `not-a-sha` | exit 2 | exit 2 | `test_r0_rejects_malformed_sha` |
| Empty SHA | exit 2 | exit 2 | `test_r0_rejects_empty_sha` |
| Blob SHA (not a commit) | exit 2 | exit 2 | `test_r0_rejects_commitish_pointing_to_blob` |
| Real commit SHA | proceeds to R1..R7 | proceeds | `test_r0_accepts_real_commit_sha` |
| state.HEAD == candidate | R2 PASS | PASS | `test_r2_passes_when_state_head_equals_candidate` |
| state.HEAD == parent of candidate | R2 PASS | PASS | `test_r2_passes_when_state_head_is_parent_of_candidate` |
| state.HEAD unrelated | R2 FAIL | FAIL | `test_r2_rejects_unrelated_head` |
| Clean worktree | R3 PASS | PASS | `test_clean_repo_returns_empty` |
| `.agent/SESSION_POINTER.md` dirty | R3 PASS (excluded) | PASS | `test_excludes_dot_agent_prefix` |
| `pkg/agent_router/foo.kt` dirty | R3 FAIL (must block) | FAIL | `test_blocks_unrelated_path_containing_agent_substring` |
| `docs/v3/CURRENT_STATE.md` dirty | R3 FAIL (only canonical path is excluded) | FAIL | `test_blocks_exact_CURRENT_STATE_at_other_location` |
| All receipts reachable | R5 PASS | PASS | `test_passes_when_receipt_provenance_is_ancestor_of_candidate` |
| Receipt on side branch | R5 FAIL (provenance not ancestor) | FAIL | `test_fails_when_receipt_not_reachable_from_candidate` |
| Receipt on disk but uncommitted | R5 FAIL (no provenance) | FAIL | `test_fails_when_receipt_has_no_provenance` |
| Mixed: one fresh, one stale | R5 FAIL (only stale reported) | FAIL | `test_mixed_provenance_some_fresh_some_stale` |
| UAT row without receipt | R5 FAIL | FAIL | `test_absence_of_receipt_in_status_blocks` |
| NOT_RUN UAT | R5 PASS (no receipt to verify) | PASS | `test_skip_not_run_uats` |
| Strict mode + REFERENCED | R4 FAIL | FAIL | `test_r4_strict_blocks_referenced_without_exception` |
| Two clones of same candidate | identical decision | identical | `test_two_clones_same_decision` |
| 3 consecutive runs same clone | identical decision | identical | `test_three_consecutive_runs_same_decision` |
| Bogus SHA 3 consecutive runs | exit 2 each time | exit 2, 2, 2 | `test_bogus_sha_is_rejected_consistently` |

42/42 tests pass in ~9 s on this machine.

## Test inventory

| Test class | Coverage |
|---|---|
| `R0CandidateExistenceTests` | 7 tests: real commit, zero SHA, malformed, empty, short (≥4 hex), blob, driver exit code |
| `R2AncestryTests` | 4 tests: equal, parent, unrelated, missing HEAD field |
| `R3ExactPathExclusionTests` | 10 tests: clean repo, `.agent/` prefix (top + nested), exact `CURRENT_STATE.md`, exact `CURRENT_UAT_STATUS.md`, substring `agent/` MUST NOT exclude, substring `CURRENT_STATE.md` MUST NOT exclude, source `.kt`, `scripts/*.py`, exact `CURRENT_STATE.md` at other location, rename normalisation |
| `R4BlockingUatTests` | 6 tests: FAIL_PROVEN blocks, NOT_RUN blocks, exception overrides, REFERENCED only strict, COVERED never blocks, missing status file blocks |
| `R5ProvenanceTests` | 6 tests: ancestor PASS, unreachable FAIL, no provenance FAIL, mixed FAIL, missing receipt in status FAIL, NOT_RUN skipped |
| `R7OriginMainTests` | 3 tests: unset, at/ancestor PASS, behind FAIL |
| `DriverSmokeTests` | 2 tests: `--help` lists args, hermetic driver against temp repo |
| `ReproducibilityInvariantTests` | 3 tests: two clones same decision, three consecutive same, bogus SHA consistent |

## Lessons

1. **Porcelain default collapses untracked subdirs.** `git status
   --porcelain` reports `pkg/agent_router/foo.kt` as `pkg/` when nothing
   inside is tracked. Use `--untracked-files=all` for R3 to evaluate the
   real path.
2. **Git peeler syntax requires braces.** `^{commit}` not `^commit`. The
   unbraced form returns "Not a valid object name".
3. **mtime is not provenance.** Receipt freshness must derive from Git
   ancestry, not from filesystem timestamps. mtime changes on copy,
   restore, re-mount, and is not preserved by `git clone --no-local`.
4. **`--root` must reach file paths too.** Without `_paths(cwd)` threading
   the repo root through every rule, R4/R5 silently read the dev repo's
   `CURRENT_UAT_STATUS.md` regardless of the clone being evaluated.

## Scope reserved for PRDY-010

PRDY-010 (operator-authorised) will wire this gate into branch protection
as a required status check. That is a separate scope; per operator
directive this WU does not start it.

## Source precedence

```text
ADRs 0070-0074 (gate semantics)
> PRDY-006R2 (this receipt)
> PRDY-006R (R2 ancestor invariant, superseded by PRDY-006R2 only for
  the new R0/R3/R5 rules; R2 contract preserved unchanged)
> receipts in docs/v2/07-uat/
```

## Material identity

- HEAD after this commit: `283b21804978fc864dd451f61fe75943bc42aa5e`
- Branch: `wu/rp-053r-red-fixtures`
- origin/main: `acc903875d70f939713786d71a6331bb6ccf7dc9` (UNTOUCHED)
- 2 files changed, 1100 insertions(+), 333 deletions(-)