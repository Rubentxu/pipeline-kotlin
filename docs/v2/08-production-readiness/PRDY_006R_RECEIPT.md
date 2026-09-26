# PRDY-006R Receipt: Admission Check with Stable Temporal Semantics

**Generated at (UTC):** 2026-09-26T10:28Z
**Replaces:** `docs/v2/08-production-readiness/PRDY_006_RECEIPT.md` (superseded; that file's contract was contradictory and is preserved only in git history for traceability).

---

## Bug contractual que existía (PRDY-006 original)

R2 declared:

> "HEAD shown in current state must match `git rev-parse HEAD`."

This is **impossible to satisfy** for any committed state of the repo:

1. Operator runs `gen-current-state-projection.py` against HEAD=A → `CURRENT_STATE.md` records HEAD=A.
2. Operator commits the projection. The new commit is HEAD=B.
3. The admission check compares `CURRENT_STATE.HEAD (A) != HEAD (B)` → R2 fails.
4. Patching the symptom with successive refresh commits reproduces the same loop (lesson learned from sessions 2026-09-26T08..10Z; generator now has an `OK-COMMITTED` branch but the admission check did not adopt the same invariant).

Additionally:

- `R5` (receipt freshness) was **declared in the docstring but never implemented**. The CLI flag `--max-receipt-age-days` was parsed and discarded.
- The receipt claimed R3 failed due to `.agent/SESSION_POINTER.md`, but the code excluded `.agent/` paths. The receipt and the code did not describe the same rule.
- Rule numbering (R1..R6) was inconsistent: the docstring listed 6 rules, the implementation had a `R4-strict` pseudo-rule and a re-numbered R5/R6.

---

## Semántica temporal adoptada (PRDY-006R)

The admission check now operates on an **explicit candidate SHA** passed via `--head`. The check does not consult `git rev-parse HEAD` for the candidate. This breaks the self-reference loop because the candidate SHA is *input*, not *derived from* the commit that contains the projection.

### Rules (R1..R7, renumbered, single naming)

| Rule | Behaviour | Fail-closed on |
|---|---|---|
| **R1** | CURRENT_STATE.md exists | missing file |
| **R2** | CURRENT_STATE.HEAD is an **ancestor** of the candidate SHA (or equal) | state.HEAD not reachable from candidate (cannot admit evidence about an unborn SHA) |
| **R3** | Working tree has no dirty source files. Excluded: `.agent/`, `CURRENT_STATE.md`, `CURRENT_UAT_STATUS.md` (gitignored local state + regenerable projections) | dirty `.kt`, `.kts`, `scripts/*.py` (non-projection), etc. |
| **R4** | No FAIL_PROVEN / NOT_RUN / REJECTED UATs in CURRENT_UAT_STATUS.md unless allow-listed in `.agent/ADMISSION_EXCEPTIONS.md` | blocking state without exception |
| **R5** | Youngest receipt in `docs/v2/07-uat/` is no older than `--max-receipt-age-days` (default 14) at candidate-commit time | stale evidence |
| **R6** | With `--strict`: REFERENCED UATs are also blocking (require exception) | REFERENCED without exception (strict mode) |
| **R7** | Candidate is not behind `origin/main` | remote divergence |

### Why ancestor (not equality) for R2

- `CURRENT_STATE.md` is a versioned artifact. The commit that contains the projection is *always* a child of the SHA the projection was generated against.
- Equality forces an impossible self-reference; ancestor is the natural, mathematically correct invariant.
- A test (`test_r2_red_characterisation`) pins this: state.HEAD = HEAD^; candidate = HEAD → R2 must PASS. Under the old equality rule this would FAIL.

### Why an explicit `--head` input

- The candidate SHA is the *thing being admitted*. It must be named, not derived.
- CI environments pass the candidate SHA from the workflow (`github.event.pull_request.head.sha` or `git rev-parse HEAD` of the candidate branch).
- The default (`--head` omitted) is `git rev-parse HEAD`, which is fine for local pre-push checks; the contradiction only arose when the operator tried to commit a refresh while still pointing the gate at HEAD.

---

## Cómo se prueba

| Test | Invariant pinned |
|---|---|
| `test_r1_passes_when_current_state_exists` / `test_r1_fails_when_missing` | R1 file existence |
| `test_r2_passes_when_state_head_equals_candidate` | R2 ancestor (equal is included) |
| `test_r2_passes_when_state_head_is_parent_of_candidate` | R2 ancestor (parent accepted — the new semantics) |
| `test_r2_fails_when_state_head_is_unrelated` | R2 fails when state.HEAD is unreachable from candidate |
| `test_r2_fails_when_head_field_missing` | R2 parse failure |
| `test_r2_red_characterisation` | RED characterisation: parent projection accepted, would FAIL under old equality rule |
| `test_r3_excludes_agent_dir` | R3 excludes `.agent/` |
| `test_r3_excludes_current_state_and_uat_status` | R3 excludes projections |
| `test_r3_blocks_source_files` | R3 detects dirty source files |
| `test_r4_blocks_fail_proven_without_exception` | R4 FAIL_PROVEN blocks |
| `test_r4_blocks_not_run_without_exception` | R4 NOT_RUN blocks |
| `test_r4_allows_blocking_with_exception` | R4 honours exception file |
| `test_r4_referenced_not_blocking_by_default` | R4 REFERENCED is non-blocking (warning) |
| `test_r4_strict_blocks_referenced_without_exception` | R6 strict: REFERENCED blocks |
| `test_r4_covered_is_never_blocking` | R4 COVERED is never blocking |
| `test_r5_passes_when_receipts_recent` | R5 14-day window |
| `test_r5_returns_without_raising_on_narrow_window` | R5 graceful at narrow window |
| `test_r5_fails_on_bogus_candidate_sha` | R5 missing candidate time → don't block |
| `test_r5_helper_returns_iso8601` | R5 helper parses |
| `test_r7_passes_when_no_divergence` | R7 origin/main parity |
| `test_r7_origin_main_unset_is_not_blocking` | R7 graceful when remote missing |
| `test_cli_help_lists_required_args` | CLI surface complete |

**23/23 contractual tests pass** at HEAD `f92528e3` after PRDY-006R application.

---

## Verificación del invariante: 3 ejecuciones consecutivas

```text
$ for i in 1 2 3; do
    python3 scripts/admission-check.py --head f92528e3...
  done
[PASS] R2 state.HEAD is ancestor of candidate   # x3
[FAIL] R3 working tree clean: scripts/admission-check.py  # expected (uncommitted source change)
[PASS] R4 R5 R7 (others)
```

R2 is **stable** across consecutive invocations with the same candidate SHA, even after re-generating `CURRENT_STATE.md` without committing. The self-reference loop is broken.

The check correctly distinguishes:
- candidate = `HEAD^` (where state was generated) → R2 FAILs (cannot admit evidence about a future SHA).
- candidate = `HEAD` → R2 PASSes (state was generated against an ancestor).

---

## Diff vs PRDY-006 receipt

| PRDY-006 claim | PRDY-006R reality |
|---|---|
| "R2 HEAD must match" | "R2 state.HEAD is ancestor of candidate" |
| `--max-receipt-age-days` parsed but unused | R5 actually implements freshness check |
| "R3 working tree dirty: agent/SESSION_POINTER.md" | ".agent/" paths excluded; source files trigger R3 |
| Rules R1..R6 + a "R4-strict" pseudo-rule | Unified R1..R7 with R6 reserved for `--strict` REFERENCED handling |
| Docstring and code agree | Docstring and code agree |

---

## Reservado para PRDY-010

- **Single SHA freeze:** `admission-check.py --head <pinned-sha>` should be wired into branch protection as the single required check. The gate then becomes: "this exact SHA has authoritative evidence".
- **Receipt signing:** Each PRDY-006R receipt should carry the candidate SHA + admission receipt path, so the harness can audit admission receipts against actual merges.
- **Exception schema:** `.agent/ADMISSION_EXCEPTIONS.md` needs a stable schema (currently free-form). PRDY-010 should codify `{uid, reason, expires_on}`.
- **CI integration:** The check should run on every PR against `main` and on every push to `wu/rp-*` branches; PRDY-010 wires it.

---

## Follow-up Actions

- **Operator:** Confirm naming R1..R7 (no R6 strict-mode overlap) is acceptable, or rename R6 to something explicit (e.g. `R6 --strict REFERENCED`).
- **Branch protection:** Wire `python3 scripts/admission-check.py --head <candidate> --strict` as the single required check.
- **PRDY-010:** Block on harness verdict before implementing SHA freeze + CI wiring.
