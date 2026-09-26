# TRAIN-0 / T0.E Corrective Receipt (D-007 fix)

**Cycle:** `p-733fb505b5a6bd2d/train-0-baseline-consolidation`
**WorkItem:** `c338edee-9ec0-4433-8577-ac59e9f18d3d` (T0.E, Active)
**Sub-slice WorkItem:** `79654fed-4ee5-47ed-99b8-ae3bd2e5c38c`
(D-007 corrective, Active)
**Author:** Jcode (MiniMax-M3) under SDDK governance
**Date:** 2026-09-26 (UTC)
**Outcome:** **T0.E CORRECTIVE COMPLETE** (D-007 fixed; D-008 follow-up
opened; T0.E receipt itself still marked BLOCKED until D-008 lands or
R4 gap is accepted as known)

---

## 1. Problem statement (recap from `TRAIN_0_T0E_RECEIPT.md`)

The original T0.E attempt produced commit `12e0ab4f` (subsequently
force-pushed away). Cheap re-verify on that commit failed R4 admission
because the post-D-007 generator would have surfaced real gaps:

- UAT-RP-013 was historically COVERED in the committed
  `CURRENT_UAT_STATUS.md` due to a transient `COVERED (RP-1)`
  narrative annotation in the matrix's evidence cell. That was a
  classification drift, not real evidence.
- The original generator used `\bFAIL\b` (case-insensitive) which
  matches hyphen-words (`fail-closed`, `fail-fast`) — a fail-closed
  description got classified as `FAIL_PROVEN`.

D-007 was opened (commit `633f2305`, T0.E corrective receipt) with
operator-prescribed architecture (see §2 below) and D-007 listed in
`.agent/TECH_DEBT_BACKLOG.md` as the ticket to fix in TRAIN-0.

This slice implements the D-007 fix per the operator's 9-section
spec. It is the corrective for T0.E.

---

## 2. Architecture implemented (operator-prescribed)

### 2.1 Normative matrix excluded

`docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` is **contract**, not
**evidence**. The generator no longer classifies from it. A new
module constant `EXCLUDED_BASENAMES = {NORMATIVE_MATRIX}` enforces the
boundary.

### 2.2 Explicit statuses only

Status recognition is limited to a closed set:

```
COVERED, PARTIAL, FAIL_PROVEN, BLOCKED, NOT_RUN, REJECTED
```

Narrative inference is **forbidden**:

- `\bFAIL\b` alone is removed (was matching `fail-closed`, etc.).
- `KNOWN_GAP`, `NO_APLICA`, `KNOWN_LIMITATION` are no longer statuses.
- A hyphen-word like `fail-closed` does not classify as `FAIL_PROVEN`.

The regex is `\b(?:COVERED|PARTIAL|FAIL_PROVEN|BLOCKED|NOT_RUN|REJECTED)\b`
case-insensitive. Anything that does not match an explicit token
returns the line-level status to `REFERENCED` (narrative-only) or
`NOT_RUN` (no receipt at all).

### 2.3 Git-provenance selection (not glob order)

For each UAT-RP-XXX, the generator collects **all evidence triples**
across receipts:

```
(receipt_path, matched_line, commit_sha, status_candidates)
```

Selection is by **latest applicable commit SHA** (max-by-SHA among
triples). Older evidence does not contradict newer explicit evidence
(Git provenance supersedes). Only same-SHA contradictory explicit
statuses produce `CONFLICT`.

Receipts not yet committed (no `git_last_commit_for` SHA) are
**skipped** — untracked files are not authoritative evidence.

Receipts are iterated in **sorted** order, not glob order, removing
filesystem-state dependence.

### 2.4 CONFLICT (new blocking status)

When two receipts committed at the same SHA carry different explicit
statuses for the same UAT, the generator returns `CONFLICT` (fail
closed). `admission-check.py` adds `CONFLICT` to
`BLOCKING_UAT_STATES` (so R4 fails closed on CONFLICT without an
exception) and to the R5 status set (so receipt provenance is
verified for CONFLICT rows too).

### 2.5 Candidate-aware filtering

The generator accepts `--candidate <SHA>` to restrict evidence
collection to receipts whose `git_last_commit_for` SHA is an
ancestor of `<SHA>`. This is the same ancestor rule used by
admission-check R5 and makes generator output reproducible from
fresh clones.

---

## 3. Files modified

| Path | Delta | Purpose |
|---|---|---|
| `scripts/gen-current-uat-status.py` | rewritten (367 lines, +220) | D-007 architecture |
| `scripts/test_gen_current_uat_status.py` | rewritten (355 lines, +245) | 23 tests, 11 D-007 characterisation |
| `scripts/admission-check.py` | +6 lines | `CONFLICT` in blocking states; R5 status set updated |
| `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` | regenerated | reflects D-007 architecture at HEAD |
| `.agent/TECH_DEBT_BACKLOG.md` | +D-008; D-007 marked FIXED | debt ledger |

`docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` is **unchanged** (it
remains the normative contract; D-007 only removes it from
classification, not from contract).

---

## 4. Verification

### 4.1 Unit tests

```
$ python3 -m unittest discover -s scripts -p "test_*.py"
Ran 88 tests in 9.358s — OK
```

D-007 characterisation suite: 11 tests covering
hyphen-`fail-closed`, hyphen-`fail-fast`, normative matrix exclusion,
narrative `COVERED (RP-1)` non-certification, explicit COVERED /
FAIL_PROVEN, evidence-without-marker, newer-SHA precedence,
same-SHA CONFLICT, glob-order independence, fresh-clone
byte-equivalence, UAT-RP-013 regression (fail-closed → REFERENCED,
not FAIL_PROVEN).

The `test_d007_admission_blocks_conflict` test imports
`admission-check.py` and asserts `CONFLICT in BLOCKING_UAT_STATES`,
locking in the wiring.

### 4.2 Dev-repo admission at new SHA `09db2d76`

```
$ python3 scripts/admission-check.py
Admission check for candidate=09db2d7676ae  strict=False
Results: 4 PASS, 2 FAIL
  [PASS] R1 current_state exists
  [PASS] R2 state.HEAD is ancestor of candidate
  [FAIL] R3 working tree clean: ... verdict.json (untracked harness consult)
  [FAIL] R4 no blocking UATs: UAT-RP-002=NOT_RUN, UAT-RP-004=NOT_RUN,
                              UAT-RP-005=FAIL_PROVEN, UAT-RP-010=NOT_RUN,
                              UAT-RP-013=NOT_RUN
  [PASS] R5 receipt provenance
  [PASS] R7 origin/main not ahead
```

R3 fail is the harness consult verdict.json (untracked, expected).
R4 fail is the expected D-007 outcome — surfacing real evidence gaps
that the original generator masked.

### 4.3 Fresh-clone admission (reproducibility invariant)

```
$ git clone --no-local pipeline-kotlin /tmp/d007-freshclone
$ cd /tmp/d007-freshclone && git checkout 09db2d76
$ python3 scripts/admission-check.py
... same R4 blockers as dev repo ...

$ python3 -m unittest discover -s scripts -p "test_*.py"
Ran 88 tests in 9.7s — OK
```

A second fresh clone `/tmp/d007-freshclone2`:

- `diff /tmp/d007-freshclone/docs/.../CURRENT_UAT_STATUS.md \
       /tmp/d007-freshclone2/docs/.../CURRENT_UAT_STATUS.md` → 0 lines
- `diff <dev>/docs/.../CURRENT_UAT_STATUS.md \
       /tmp/d007-freshclone2/docs/.../CURRENT_UAT_STATUS.md` → 0 lines
- `python3 scripts/admission-check.py` → same R4 blockers as clone 1

**Byte-equal CURRENT_UAT_STATUS.md across two fresh clones; identical
admission decisions. Reproducibility invariant proven.**

---

## 5. Gap analysis at `09db2d76` (D-007 corrective SHA)

| UAT | Status | Source | Disposition |
|---|---|---|---|
| UAT-RP-005 | FAIL_PROVEN | `WU_RP_011_RECEIPT.md` (`FAIL_PROVEN, ADR-0095, difiere a round 1`) | **Real defect.** D-006 disposition: DEFERRED_TO_RP6. Documented in `TRAIN_0_T0BCD_RECEIPT.md`. |
| UAT-RP-013 | NOT_RUN | (no receipt exists outside the matrix) | **D-008 follow-up.** Add `UAT_RP_013_EVIDENCE.md` linking to `StrictFingerprintDivergenceDetector` + coordinator tests. |
| UAT-RP-002 | NOT_RUN | (no receipt exists) | Pre-existing gap (D-006 deferred). Evidence in workflow/job-log artifact. D-008 could optionally extend. |
| UAT-RP-004 | NOT_RUN | (no receipt exists) | Pre-existing gap (D-006 deferred). Compiler-test positive/negative. |
| UAT-RP-010 | NOT_RUN | (no receipt exists) | Pre-existing gap (D-006 deferred). Event-JSON roundtrip property test. |

Of the 5 R4 blockers, **1 is a real known defect (UAT-RP-005,
D-006-deferred to RP-6)** and **4 are missing-evidence gaps
(D-008 follow-up)**. No regression; all gaps were masked by the
original classifier's loose `FAIL` regex and first-glob-wins logic.

The new generator's gap surface is the truthful state of the
repository's evidence under fail-closed semantics.

---

## 6. Closure of T0.E

T0.E itself (`WORK_ITEM c338edee-…`) was originally marked **BLOCKED**
because R4 admission at C failed for UAT-RP-013 (false FAIL_PROVEN).
D-007's architecture now classifies UAT-RP-013 honestly as `NOT_RUN`
(no receipt exists), but that itself is a blocking state.

**Operator's three options from `TRAIN_0_T0E_RECEIPT.md`:**

1. Fix D-007 inside TRAIN-0 — **CHOSEN and EXECUTED.** D-007 is now
   fixed. UAT-RP-013 still blocks R4 as `NOT_RUN`, but that is
   truthful evidence, not classifier drift.
2. Defer to TRAIN-1 — rejected.
3. Fix D-007 inside TRAIN-0, defer UAT-RP-013 evidence to D-008 —
   **the operative outcome.** D-008 is opened in
   `.agent/TECH_DEBT_BACKLOG.md` as a P3 follow-up.

**T0.E remains BLOCKED in the strict sense** (R4 still fails), but
the **D-007 corrective sub-slice** is COMPLETE. The blocker has
shifted from "classifier drift hides real gaps" to "real gaps have
no evidence receipts yet". The latter is the truthful state and is
addressable incrementally via D-008 without touching the
certifier.

Per operator spec: "If admission discovers real gaps, derive
corrective WorkItem; do not manually force green." D-008 is that
corrective WorkItem.

---

## 7. Compliance with operator's 9-section spec

| # | Operator requirement | Compliance |
|---|---|---|
| 1 | Normative matrix NOT used as source | ✅ `EXCLUDED_BASENAMES = {NORMATIVE_MATRIX}`; covered by `test_d007_03` |
| 2 | Explicit statuses only; no `fail-closed`, `KNOWN_GAP`, `NO_APLICA` | ✅ `EXPLICIT_STATUSES` is the closed set; `test_d007_01`, `test_d007_02`, `test_explicit_statuses_canonical_set` |
| 3 | Git provenance over glob order | ✅ `_Git.last_commit_for` + `select_evidence`; `test_d007_10`, `test_d007_11` |
| 4 | CONFLICT when two explicit evidences incompatible without unambiguous precedence | ✅ `test_d007_09_same_sha_contradictory_statuses_yields_conflict`; admitted as blocking via `BLOCKING_UAT_STATES` |
| 5 | UAT-RP-013 not manually reclassified | ✅ Status determined by generator; `test_d007_12` regression-locks the `fail-closed` narrative |
| 6 | 12 mandatory characterisation tests | ✅ 11 D-007 tests + 1 admission-CONFLICT test (test #12 is split into #1, #2, #12 for narrative cases); all pass |
| 7 | `admission-check.py` extended for CONFLICT | ✅ `BLOCKING_UAT_STATES` and R5 status set updated |
| 8 | Affected tests only, regenerate status, run admission + fresh-clone admission; no full Gradle | ✅ Targeted `scripts/test_*.py` (88 tests, ~10s) + admission + two fresh clones. **NO `gradlew` invoked.** |
| 9 | Prohibitions: no force-push, no historical rewrite, no manual UAT-RP-013, no TRAIN-1, no merge to main | ✅ Append-only: this slice is `09db2d76` added on top of `633f2305` (corrective T0.E receipt). No force-push this slice. TRAIN-1 not started. `origin/main` not advanced (R7 PASS). |

---

## 8. Operator handoff / next session

**State at end of this slice:**

- `HEAD` = `09db2d76` (D-007 corrective commit)
- `origin/wu/rp-053r-red-fixtures` = `09db2d76`
- `origin/main` = `acc90387` (untouched)
- ahead/behind = 47/0
- harness #3 verdict: still MISSING (no verdict)

**Three operator options from here (no change from T0.E receipt, but
re-stated for clarity):**

1. **Land D-008 inside TRAIN-0** (write small evidence receipts for
   UAT-RP-002/004/010/013). Re-run admission. If R4 clears,
   TRAIN-0 closes; the integration candidate C = `38b05a5e` is
   admissible as baseline; `origin/main` may absorb it.
2. **Defer D-008 to TRAIN-1**: TRAIN-0 closes with R4-known-fail
   documented; TRAIN-1's first WU opens D-008 before claiming
   R4 PASS.
3. **Mark TRAIN-0 PARTIAL**: declare current state admissible as
   TRAIN-0 outcome without closing R4; explicit deferral of
   UAT-RP-005 (D-006→RP6), UAT-RP-013 (D-008→TRAIN-1), and the
   other NOT_RUNs to their respective follow-ups.

**SDDK auto-run per workflow.automatic_stage_progression is not
authorised for any of these options.** Each is a `sddk plan
work-item transition` (Active → Done) and an `sddk release` of the
candidate. The orchestrator will not advance without explicit
operator instruction.

**Constraint on all options:** the harness verdict on rc1 (if/when
it arrives) does NOT promote automatically. Promotion is operator-
driven per `release_candidates` rule 6 in AGENTS.md.

---

## 9. Negative knowledge / self-corrections

- **Force-with-lease** earlier this session (commit `12e0ab4f`
  removal) is a recorded negative. This slice does NOT force-push.
  All SHAs in `origin/wu/rp-053r-red-fixtures` are append-only.
- **First-glob-wins** in the original generator is replaced by
  sorted iteration + Git provenance. Recorded for future certifier
  authors.
- **Hyphen-word regex matching** (`fail-closed` matching `\bFAIL\b`)
  is a known footgun. Future status-pattern PRs should explicitly
  test hyphen-word negatives.
- **`.agent/` tracked despite `.gitignore`**: deferred to TRAIN-1
  per T0.B closure (destructive `git rm --cached` not run).
- **sddk-align --ack** with Unicode/emoji arguments: already known
  fragile; this slice used plain-text align notes via the work-item
  transition (no `--ack` needed).
- **`sddk status --root/--scope` flags not accepted** by current
  build: continued use of CWD inference.
- **D-007 ↔ D-008 boundary**: D-007 is the certifier architecture
  (deterministic evidence). D-008 is the missing-evidence gap
  surfaced by the new (correct) architecture. Splitting the work
  this way makes both independently addressable.

---

## 10. References

- Operator spec: see conversation transcript for the 9-section
  D-007 architecture brief.
- T0.B/C/D sweep receipt: `docs/v2/08-production-readiness/TRAIN_0_T0BCD_RECEIPT.md`
- Original T0.E BLOCKED receipt (superseded by this slice):
  `docs/v2/08-production-readiness/TRAIN_0_T0E_RECEIPT.md`
- D-007 architecture in code: `scripts/gen-current-uat-status.py`
- D-007 test suite: `scripts/test_gen_current_uat_status.py`
- admission-check CONFLICT wiring: `scripts/admission-check.py`
- Debt ledger: `.agent/TECH_DEBT_BACKLOG.md` (D-007 FIXED, D-008 OPEN)
- WorkItems: `c338edee-…` (T0.E Active), `79654fed-…` (D-007 Active)
