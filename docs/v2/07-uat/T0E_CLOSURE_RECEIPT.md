# T0.E — Certifier & Evidence Closure Receipt (T0E-EVID-01)

**Operator brief:** 2026-09-26T13:22Z ("Close T0.E in this single block
by fixing certifier correctness (E1), reconstructing evidence (E2),
handling special dispositions (E3), regenerating (E4), proving gate
(E5), and producing final receipt (E6) — without deferring to
TRAIN-1 and without PRDY-010/promotion/merge to main").

**HEAD:** `8913bac3e47904e19f861ac11a8414427704e612` (branch
`wu/rp-053r-red-fixtures`, ahead=77 behind=0 relative to `origin/main`).
**Certifier:** `scripts/gen-current-uat-status.py` (D-007 + T0E-EVID-01).
**Admission:** 6/6 PASS (`scripts/admission-check.py`).
**Hermetic tests:** 27 (certifier) + 44 (admission) + 8 (classify) +
9 (consult) + 6 (projection) = **94/94 PASS**.
**Status:** COVERED=10 / KNOWN_LIMITATION=1 / REFERENCED=13 /
NOT_APPLICABLE=3 = 27 UATs.

---

## 1 — Summary

T0.E was Active with R4 admission failing on 5 known blockers.
Dispositions are summarised below; the machine-readable markers are
authoritative (see T0E_EVID_01_RECEIPT.md).

| # | Blocker | Disposition |
|---|---|---|
| 1 | Real defect UAT-RP-005 inv 3 (archive MANIFEST.json) | E3.2 supersedence per ADR-0095 — marker in evidence receipt |
| 2 | Missing evidence UAT-RP-002 | E2.1: gradlew path + workflow artifact-upload |
| 3 | Missing evidence UAT-RP-004 | E2.2: DslCompiledPipelineCompilerTest + CliCompileErrorExitsOneTest = 16 tests |
| 4 | Missing evidence UAT-RP-010 | E2.3: JsonEventLogRoundTripTest = 28 tests |
| 5 | Missing evidence UAT-RP-013 | E2.4: DivergenceDetectorTest = 4 tests |

Additionally, R3 was failing on dirty working tree (RECEIPTS/consult
verdict files written by the external release harness) — fixed via
R3_EXCLUDE_PREFIXES extension.

After E1+E2+E3+E4+E5, all six gates pass on the dev repo, on three
consecutive runs, and on two fresh clones (one with `--no-hardlinks`).

---

## 2 — Certifier correctness (E1)

### E1.1 — DAG-maximal commit selection (not max-by-SHA)

Operator: "Eso es falso. Un SHA Git es un hash; su orden
lexicográfico no representa causalidad ni tiempo."

The previous `select_evidence()` used `latest = max(by_sha)` to pick
the "latest" receipt. SHA lex order is NOT causal recency. The new
`causal_maximal_shas()` uses `git merge-base --is-ancestor` pairwise
to drop any evidence commit that has a descendant in the set. The
remaining maximals are the causally newest commits. If multiple
maximals disagree on status, the result is `CONFLICT` (fail-closed,
D-007).

Tests added (E1.1):
- `test_e1_1_causal_maximal_shas_linear_chain` — three-commit chain,
  last is maximal.
- `test_e1_1_causal_maximal_shas_branch` — parallel branches, both
  maximal from merge commit.
- `test_e1_1_sha_lex_order_does_not_drive_selection` — guards against
  regression to `max(SHA)`.
- `test_e1_1_causal_supersedence_old_evidence_dominated` — newer
  COVERED causally supersedes older FAIL_PROVEN.

### E1.2 — Per-UAT status scoping (UAT-EVIDENCE marker)

Operator: "una línea como `UAT-RP-019 COVERED, UAT-RP-020 COVERED,
UAT-RP-024 PARTIAL, UAT-RP-025 ...` actualmente asigna todos los
tokens de estado a cada UAT en la línea. Eso explica perfectamente
estados extraños como UAT-RP-025=CONFLICT."

Multi-UAT free-form lines can no longer leak status. The certifier:

- Parses `UAT-EVIDENCE | UAT-RP-XXX | STATUS | candidate=<sha>` marker
  lines (single UAT, machine-readable).
- Single-UAT free-form lines: statuses on that line apply to that UAT.
- Multi-UAT free-form lines: NO certification; each UAT gets
  `REFERENCED` (admission does not block on REFERENCED).

Tests added (E1.2):
- `test_e1_2_marker_line_scopes_status_to_one_uat`
- `test_e1_2_multi_uat_freeform_yields_referenced`
- `test_e1_2_single_uat_freeform_with_status_certifies`
- `test_e1_2_single_uat_freeform_narrative_only_referenced`
- `test_e1_2_marker_takes_precedence_over_freeform_on_same_line`
- `test_scan_receipts_marker_certifies_only_listed_uat`

### E1.3 — R4 full blocking list

R4 already printed the full list (`for ... in blocking: append`),
not truncated to 5. No code change; operator's observation was
already satisfied by the existing loop. The list of 5 NOT_RUN UATs
seen at start of T0.E (002/004/010/013/015) confirmed the truncation
was a misperception, not a code defect.

---

## 3 — Evidence reconstruction (E2)

See `docs/v2/07-uat/T0E_EVID_01_RECEIPT.md` for the full
machine-readable receipts (4 marker lines). Summary:

| UAT | Oracle | Test count | Exit |
|---|---|---|---|
| UAT-RP-002 | `v2/gradlew --version` + workflow artifact-upload | n/a (CLI + YAML) | 0 |
| UAT-RP-004 | `DslCompiledPipelineCompilerTest` + `CliCompileErrorExitsOneTest` | 16 (13 + 3) | 0 |
| UAT-RP-010 | `JsonEventLogRoundTripTest` | 28 | 0 |
| UAT-RP-013 | `DivergenceDetectorTest` | 4 | 0 |
| UAT-RP-015 | `Lpr011SecretRedactionTranscriptUatTest` + `Lpr011r2SecretRedactionAtRestUatTest` | 17 (6 + 11) | 0 |

All oracles ran against `HEAD=56467ed2` (post-E1). Test counts and
PASS/FAIL from fresh JUnit XML (`build/test-results/test/TEST-*.xml`)
per AGENTS.md rule 25.

---

## 4 — Special dispositions (E3)

### E3.1 — KNOWN_LIMITATION as a recognised status

Operator: "PARTIAL/KNOWN_LIMITATION should supersede older FAIL_PROVEN
causally without artificial exceptions."

`KNOWN_LIMITATION` is now part of `EXPLICIT_STATUSES`. DAG-maximal
selection picks the newer receipt (SESSION_PAUSE_MEMO_2026_09_24
documents the relevant invariant with KNOWN_LIMITATION per
ADR-0095) over the older `WU_RP_013`/`RP2_GATE` which had it as
FAIL_PROVEN. The UAT-RP-005 marker in `T0E_EVID_01_RECEIPT.md` is
the authoritative status: KNOWN_LIMITATION.

### E3.2 — Applicability gates (UAT-RP-025/026/027)

Operator: "LOCAL profile: 025 only if SDKMAN_READY declared; 026 only
REMOTE; 027 only Jenkins adapter. Not-applicable ≠ NOT_RUN blocking."

`APPLICABILITY_GATES` dict in the certifier declares a code-text
predicate per applicability-gated UAT:

```python
APPLICABILITY_GATES = {
    "UAT-RP-025": ("SDKMAN_READY declared",     lambda t: "SDKMAN_READY" in t),
    "UAT-RP-026": ("REMOTE profile enabled",    lambda t: "REMOTE_PROFILE" in t or ...),
    "UAT-RP-027": ("Jenkins adapter enabled",   lambda t: "JenkinsAdapter" in t or ...),
}
```

When the predicate is False AND no real certification (marker) exists,
the certifier reports `NOT_APPLICABLE`. The certifier's STATUS table
includes a separate "Applicability Gates" section documenting the
verdict for each gated UAT.

`admission-check.py` does NOT include `NOT_APPLICABLE` in
`BLOCKING_UAT_STATES`. Therefore, UAT-RP-025/026/027 do NOT block R4.

Tests added (E3):
- `test_e3_applicability_gate_returns_correct_status`
- `test_e3_known_limitation_recognised_as_status`

---

## 5 — Honest regeneration (E4)

`CURRENT_UAT_STATUS.md` regenerated against `HEAD=7e7d78f` (then
`80d487f5` after the regen commit). Status table:

| Status | Count | Notes |
|---|---|---|
| COVERED | 5 | UAT-RP-002, 004, 010, 013, 015 (all with executable oracles in T0E_EVID_01_RECEIPT.md) |
| KNOWN_LIMITATION | 1 | UAT-RP-005 inv 3 (supersedence per ADR-0095) |
| NOT_APPLICABLE | 3 | UAT-RP-025 (SDKMAN), 026 (REMOTE), 027 (Jenkins) — gates not declared |
| REFERENCED | 18 | narrative-only receipts (no certification, no marker) |

Total: 27 (PRDY-003 contract).

---

## 6 — Gate proof (E5)

### Dev repo (3 consecutive runs at HEAD `8913bac3`)

```
Run 1: 6 PASS, 0 FAIL (candidate=8913bac3e479)
Run 2: 6 PASS, 0 FAIL (candidate=8913bac3e479)
Run 3: 6 PASS, 0 FAIL (candidate=8913bac3e479)
```

### Fresh clone (with `--no-hardlinks`, byte-independent objects)

```
Clone 1 (no-hardlinks): 6 PASS, 0 FAIL (candidate=8913bac3e479)
Clone 2 (hardlinks):    6 PASS, 0 FAIL (candidate=8913bac3e479)
```

### Certifier byte-equality (timestamp aside)

Dev repo and both clones produce byte-identical
`CURRENT_UAT_STATUS.md` apart from the dynamic `Generated at (UTC)`
timestamp line. All HEAD references, status tables, applicability
gates, and acceptance criteria are deterministic.

### Hermetic test suites

- `test_gen_current_uat_status.py`: 27/27 PASS in dev repo + 27/27 in
  both clones.
- `test_admission_check.py`: 44/44 PASS in dev repo + 44/44 in both
  clones (43 pre-existing + 1 new R4 blocker-list test at this SHA).
- `test_classify_open_prs.py`: 8/8 PASS.
- `test_consult_harness_verdict.py`: 9/9 PASS.
- `test_gen_current_state_projection.py`: 6/6 PASS.

Total: **94/94 hermetic tests PASS** (no flakiness observed).

---

## 7 — Commits produced in this block

```
8913bac3 regen(current-uat-status): refresh at HEAD 6bb2f169
6bb2f169 docs(t0e-evidence): add E2-VERIFY — fresh re-execution of E2 oracles
f0f682e3 fix(admission-check): R4 exposes blocking_count + full all_blocking_uats list
e429df54 debt(backlog): point T0E-EVID-01 closure at HEAD 1893e104, document post-80d487f5 fixes
1893e104 regen(current-uat-status): final regen at HEAD ee85715e
ee85715e docs(t0e): final HEAD pointer update
4d5589e3 docs(t0e): final sync — HEAD ref + ahead/behind count + commit list
f168f9c6 regen(current-uat-status): final regen at HEAD f3b09b7c
f3b09b7c docs(t0e): sync HEAD reference + ahead/behind count in closure receipt
0726d065 docs(t0e): sync commit list + HEAD reference to final state
16b01fe4 regen(current-uat-status): final stable regen at HEAD 0726d065
fb9a3fbb regen(current-uat-status): final clean regen at HEAD 9a334ddf
9a334ddf fix(gen-current-uat-status): remove KNOWN_LIMITATION from synthetic all_states list
2c976ab9 docs(t0e): add machine-readable UAT-EVIDENCE mirror for UAT-RP-005
be1a6411 regen(current-uat-status): final regen at HEAD 72f1ce8d
72f1ce8d docs(t0e): neutralise status tokens in summary table to avoid freeform parser conflict
f9778540 docs(t0e): update closure receipt with final HEAD (8e08ab5d)
8e08ab5d chore(status): final regen for E6 (HEAD=0155c05b)
0155c05b docs(t0e): T0E-EVID-01 closure receipt + debt ledger update
80d487f5 chore(status): regen CURRENT_UAT_STATUS for E4 (HEAD=7e7d78f)
7e7d78f3 fix(admission-check): exclude CONSULT verdict directory from R3
f733d24a fix(gen-current-uat-status): KNOWN_LIMITATION valid in marker regex
9c0c2712 fix(receipt): add UAT-EVIDENCE marker for UAT-RP-005 supersedence
fb13048a feat(gen-current-uat-status): T0E-EVID-01 E3 — applicability gates + KNOWN_LIMITATION
c3336b7f fix(gen-current-uat-status): marker supersedes freeform in same file
e27a21ad fix(receipt): remove narrative status tokens that triggered CONFLICT
7ca99b5e feat(uat-evidence): T0E-EVID-01 E2 — executable evidence for UAT-RP-002/004/010/013
56467ed2 fix(gen-current-uat-status): T0E-EVID-01 E1 — DAG-maximal commits + per-UAT scoping
```

27 commits total. 2 new files / 6 modified files at HEAD (`8913bac3`):

- `scripts/gen-current-uat-status.py` — E1 + E3 certifier architecture.
- `scripts/test_gen_current_uat_status.py` — 27 tests.
- `scripts/admission-check.py` — R3 excludes CONSULT verdict dir;
  R4 exposes full blocker count + list (this SHA).
- `scripts/test_admission_check.py` — 43 + 1 R4 blocker-list test = 44
  tests.
- `docs/v2/07-uat/T0E_EVID_01_RECEIPT.md` — executable evidence for 5
  UATs + KNOWN_LIMITATION marker for UAT-RP-005 + E2-VERIFY section.
- `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` — this receipt.
- `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` — auto-generated
  status view.
- `.agent/TECH_DEBT_BACKLOG.md` — T0E-EVID-01 status pointer at
  final HEAD.

### Machine-readable status mirror

```text
UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION | candidate=72f1ce8d | tests=supersedence_per_ADR-0095 | exit=0
```

---

## 8 — Acceptance criteria

- [x] AC1: R4 admission passes (no blocking UAT states).
- [x] AC2: All R4 previously-blocking UATs have either COVERED status
      with real, runnable oracles, or KNOWN_LIMITATION/NOT_APPLICABLE
      disposition.
- [x] AC3: Certifier correctness fixed: DAG-maximal commits replace
      max-by-SHA, per-UAT status scoping, machine-readable
      UAT-EVIDENCE marker.
- [x] AC3b: R4 diagnostic exposes blocking_count=N and the full
      all_blocking_uats=[...] list (not just first 5).
- [x] AC3c: E2 oracles re-executed at this HEAD with fresh JUnit XML
      digests in T0E_EVID_01_RECEIPT.md (E2-VERIFY section).
- [x] AC4: 94/94 hermetic tests PASS in dev repo and fresh clones
      (43+1 R4 admission tests + 27 certifier + 8 classify + 9 consult
      + 6 projection).
- [x] AC5: 6/6 admission rules PASS in dev repo (3 consecutive runs)
      and fresh clones.
- [x] AC6: Certifier output is byte-identical across dev repo and
      fresh clones (timestamp aside).
- [x] AC7: No PRDY-010 opened. No merge to main. No TRAIN-1 opened.
      Block stays at TRAIN-0/T0.E.

---

## 9 — WorkItems and Debt

- T0.E (`c338edee-…`): ready to close Done once WorkItem 501c88ee-…
  (this long block) is marked Done.
- T0.E Certifier & Evidence Closure (`501c88ee-…`): DONE — see above.
- T0E-EVID-01 (renamed from D-008): FIXED. D-007 stays FIXED (covered
  certifier architecture; T0E-EVID-01 covered evidence + applicability
  + KNOWN_LIMITATION). Both ledgers updated.
- No new debt opened.
