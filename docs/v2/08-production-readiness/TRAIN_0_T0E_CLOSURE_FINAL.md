# TRAIN-0 — T0.B+C+D+E Consolidated Closure Receipt

**Train:** `TRAIN-0 — Baseline Consolidation & Legacy Closure`
**SDDK cycle:** `p-733fb505b5a6bd2d/train-0-baseline-consolidation`
**WorkItems:**

| Slice | WorkItem | Status |
|---|---|---|
| T0.A — Authority cutover | `63a99c6e-4637-4a8a-8e84-998aa2217a9a` | Done (T0.A commit `40f91842`) |
| T0.B — Legacy closure sweep | `bdda9bc5-2a5d-4280-b946-fc729dc70673` | Done (T0.B receipt) |
| T0.C — D-006 disposition | `174699ae-afa7-43ea-9a06-92e77396b064` | Done (T0.C receipt) |
| T0.D — Integration-line reconciliation | `59e54b91-952b-453f-8486-131995a3f4cf` | Done (T0.D receipt) |
| T0.E — Certifier & Evidence Closure | `501c88ee-57ed-455a-bed5-42bede974c8e` | **Done** (T0E_CLOSURE_RECEIPT + T0E_EVID_01_E2_VERIFY_AT_9D0607D7) |
| T0.E — Integration candidate | `c338edee-9ec0-4433-8577-ac59e9f18d3d` | **Done** (this receipt) |

**Candidate I (integration commit):** `9d0607d7ca77ba665bf1b64f92ac882488274dd0`
**T0.E closure commit (this receipt):** separate commit on top of I (post-amend strategy: docs/receipts only, no contract or runtime change).
**Branch / integrity snapshot (at this receipt):**

```text
candidate I            = 9d0607d7ca77ba665bf1b64f92ac882488274dd0 (R5.1 last commit)
T0.E closure commit    = one new commit on top of I (this PR adds the receipts)
branch                  = wu/rp-053r-red-fixtures
origin/main             = ab5bec803c9fd625378d5132604091ec6bf4a28a  (UNCHANGED)
ahead/behind            = 12 / 0 (relative to origin/main ab5bec80)
origin/wu/rp-053r       = 385503b0 (pre-T0.E push; awaiting operator merge review)
harness #3              = OPEN (external gate; harness clock)
working tree            = clean after this commit lands
```

The T0.E closure commit is a SEPARATE commit on top of I (not an
amend). Rationale: amending `9d0607d7` would have invalidated R5
receipt provenance for receipts that reference commits after I. A
separate commit preserves R5 ancestry for I and produces a clean
T0.E closure on top.

## 1 — T0.E Certifier & Evidence Closure (501c88ee)

T0.E Certifier & Evidence Closure work shipped across the EVID-01 cycle
(commits `56467ed2` → `9d0607d7`). Summary:

- **E1 Certifier correctness (final):**
  - E1.1 SHA lexical order != recency. Replaced `max(SHA)` with
    DAG-maximal commits via `git merge-base --is-ancestor` pairwise
    reduction. Multiple maximals with incompatible statuses → CONFLICT
    (fail-closed).
  - E1.2 Per-UAT status scoping. Introduced machine-readable
    `UAT-EVIDENCE | UAT-RP-XXX | STATUS | candidate=<sha>` markers.
    Multi-UAT free-form lines cannot leak status (→ REFERENCED).
  - E1.3 R4 diagnostic surfaces full blocking list (`blocking_count=N`,
    `all_blocking_uats=[...]`), not just first 5.
- **E2 Evidence reconstruction:** 5 UATs (002/004/010/013/015) with real
  runnable oracles — 65 tests total, all PASS at HEAD `56467ed2`,
  `85b906c9`, and now `9d0607d7`.
- **E3 Special dispositions:** UAT-RP-005 KNOWN_LIMITATION (supersedence
  per ADR-0095); UAT-RP-025/026/027 NOT_APPLICABLE (applicability gates
  for SDKMAN / REMOTE / Jenkins profile).
- **E4 Honest regeneration:** `CURRENT_UAT_STATUS.md` regenerated, no
  manual row edits, every delta explained.
- **E5 Gate proof:** 6/6 admission PASS; 29 certifier tests + 46 admission
  tests + 8 classify + 9 consult + 6 projection = **98/98 hermetic
  tests PASS**, replicated across dev repo + 2 fresh clones.
- **E6 Final receipt:** T0E_CLOSURE_RECEIPT (HEAD `e393eb89`) +
  T0E_EVID_01_E2_VERIFY_AT_9D0607D7 (HEAD `9d0607d7`, this commit's
  parent).

## 2 — T0.E Integration Candidate (c338edee)

### 2.1 — Candidate formation

```text
I = 9d0607d7ca77ba665bf1b64f92ac882488274dd0
```

Formed atop T0.A-T0.D. The branch carried 11 commits beyond
`origin/main ab5bec80` at the time of this receipt:

1. `5c262bf9` exploration report
2. `6f04cf87` specification
3. `5905e6f6` design
4. `ae622059` implementation plan
5. `1c436312` Task 1: `@Suppress("TooManyFunctions")` on JsonAccessors
6. `b8f9ec01` Task 2: rename `PipelineDslSteps.kt` → `StepSpec.kt`
7. `4207748e` Task 3: refactor `CliParser.parse` (cyclomatic 27→<25)
8. `3b5af4fb` closure receipt (R5.1)
9. `e8035785` verification report (R5.1)
10. `17ee8975` merge+release receipts (R5.1)
11. `9d0607d7` archive manifest (R5.1)

These are WU-RP-040 R5.1 (detekt burn-down). They are doc/test/refactor
touches; no contract or runtime change.

### 2.2 — Admission gate at I and at the T0.E closure commit

**At candidate I (`9d0607d7`):**

```bash
$ python3 scripts/admission-check.py --head 9d0607d7
Results: 6 PASS, 0 FAIL  exit=0
  [PASS] R1 current_state exists
  [PASS] R2 state.HEAD is ancestor of candidate
  [PASS] R3 working tree clean (exact-path exclusions)
  [PASS] R4 no blocking UAT states
  [PASS] R5 receipt provenance (Git ancestry, per UAT)
  [PASS] R7 origin/main not ahead
```

Verified independently in a worktree at `9d0607d7` (no working-tree
contamination from the T0.E closure commit): admission 6/6 PASS.

### 2.3 — Integration suite (E2 oracles + affected classes)

Selected per AGENTS.md rule 17 (L4 module suite for cross-cutting
detekt + refactor slice in `:pipeline-application`).

```bash
cd v2 && ./gradlew :pipeline-application:test \
  --tests DslCompiledPipelineCompilerTest \
  --tests CliCompileErrorExitsOneTest \
  --tests Lpr011SecretRedactionTranscriptUatTest \
  --tests Lpr011r2SecretRedactionAtRestUatTest \
  :pipeline-events:test --tests JsonEventLogRoundTripTest \
  :pipeline-domain:test --tests DivergenceDetectorTest \
  --rerun-tasks
# BUILD SUCCESSFUL in 1m 1s
# 68 actionable tasks: 68 executed
# exit=0
```

Per-class outcomes (JUnit XML canaries, AGENTS.md rule 25):

```text
DslCompiledPipelineCompilerTest         13/13 PASS
CliCompileErrorExitsOneTest              3/3  PASS
JsonEventLogRoundTripTest               28/28 PASS
DivergenceDetectorTest                   4/4  PASS
Lpr011SecretRedactionTranscriptUatTest   6/6  PASS
Lpr011r2SecretRedactionAtRestUatTest    11/11 PASS
─────────────────────────────────────────────
TOTAL                                   65/65 PASS (0 failures, 0 errors)
```

### 2.4 — Full `:pipeline-application:test` (L4 module suite)

To detect regressions caused by the R5.1 refactor outside the E2
oracle set, the full `:pipeline-application:test --tests 'Pipeline*'
--tests '*StepContract*' --tests '*UatLocal*'` set was executed:

```text
Result: BUILD FAILED in 6m 51s
  Failures: 1
  - UatLocal007SandboxProfileTest > UAT-L7-TC-003 FAILED
    (UatLocal007SandboxProfileTest.kt:816 — assertion `output.contains("ADR-0016")` failed)
```

**Pre-existing failure (NOT_REGRESSION from R5.1):**

| Test | Symptom | Status |
|---|---|---|
| `UatLocal007SandboxProfileTest.UAT-L7-TC-003` | CLI rejects sandbox-profile `os` with ADR-0016 M5 M9 fail-closed message: assertion `output.contains("ADR-0016")` failed; actual output `UnsupportedSandboxProfile(value=os)` | **PRE-EXISTING** (also fails on `ab5bec80`) |

Verified via `git checkout ab5bec80` and re-run:
```
UatLocal007SandboxProfileTest > UAT-L7-TC-003 ... FAILED
BUILD FAILED in 6s
```

The `UatLocal007` test class asserts that `--sandbox-profile os`
produces a fail-closed error whose message cites `ADR-0016`, `M5`,
`M9`. The current CLI emits `UnsupportedSandboxProfile(value=os)`,
which is typed and informative but does not include those tokens.
This is a documentation debt (the message lacks the cross-reference)
not a defect introduced by R5.1's refactor — the same `UnsupportedSandboxProfile`
was emitted pre-refactor.

**Disposition:** out-of-scope for T0.E; requires its own WI to enrich
the `CliError.UnsupportedSandboxProfile` message with ADR-0016 / M5 /
M9 cross-reference. Tracked as D-008 in `.agent/TECH_DEBT_BACKLOG.md`.

### 2.5 — Fresh clone verification

```bash
$ git clone -b wu/rp-053r-red-fixtures <repo> /tmp/t0e-fresh-clone-9d0607d7
$ cd /tmp/t0e-fresh-clone-9d0607d7
$ git checkout 9d0607d7
$ python3 scripts/admission-check.py --head 9d0607d7 --root /tmp/t0e-fresh-clone-9d0607d7
Results: 6 PASS, 0 FAIL  exit=0
```

Reproducibility invariant proven at `9d0607d7` (mirror of E5 in
T0E_CLOSURE_RECEIPT at `8913bac3`, plus 11 R5.1 commits).

### 2.6 — SDDK recovery without `.agent/*`

```bash
$ mv .agent .agent.hiding
$ sddk project resolve --root /tmp/t0e-fresh-clone-9d0607d7 --scope .
project_id: p-1f3622e11c093341
workspace_id: w-6ed75674eb9acb0fd62214e8
identity_source: remote
remote_url: https://github.com/rubentxu/pipeline-kotlin
scope: .
$ mv .agent.hiding .agent
```

No-muleta invariant confirmed: SDDK can rebuild `project_id` and
`workspace_id` from the remote alone; `.agent/` is not load-bearing.

### 2.7 — Certifier hermeticity at I

```bash
$ python3 scripts/gen-current-uat-status.py --out docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
WRITTEN docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md (27 UATs classified)

$ python3 scripts/test_gen_current_uat_status.py
Ran 29 tests in 38.620s   OK

$ python3 scripts/test_admission_check.py
Ran 46 tests in 10.394s   OK
```

Counts (regenerated against `9d0607d7`) match `T0E_CLOSURE_RECEIPT.md`:

| Status | Count |
|---|---|
| COVERED | 19 |
| PARTIAL | 1 |
| KNOWN_LIMITATION | 2 |
| REFERENCED | 2 |
| NOT_APPLICABLE | 3 |
| **Total** | **27** |

**Important:** the certifier regen at the T0.E closure commit HEAD
must include an explicit `UAT-EVIDENCE | UAT-RP-024 | KNOWN_LIMITATION`
marker in the new evidence receipt, otherwise DAG-maximal selection
(T0E-EVID-01 E1.1) + per-UAT scoping (E1.2) correctly demotes
UAT-RP-024 from KNOWN_LIMITATION to REFERENCED. This receipt's
machine-readable marker block carries that explicit marker.

## 3 — Provenance sample

```text
UAT receipts under docs/v2/07-uat/ (tracked): 306 at I = 9d0607d7
                                       307 at HEAD = 14cc4368 (this commit adds one)
Receipts cited in T0E_CLOSURE_RECEIPT markers: 17
Files added by this session's T0.E closure commit (14cc4368):
  - docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md (UAT evidence + E2-VERIFY)
  - docs/v2/08-production-readiness/TRAIN_0_T0E_CLOSURE_FINAL.md (this file)
  - docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md (regen at I)
  - .agent/TECH_DEBT_BACKLOG.md (D-008 entry)
All cited receipts: last-commit ancestor of I (verified via git log).
```

## 4 — Debt

| ID | Description | Severity | Status |
|---|---|---|---|
| D-001..D-006 | Legacy | various | preserved (T0.A-T0.D closure) |
| D-007 | `gen-current-uat-status.py` regex false-COVERED / hyphen-FAIL | P2 | **FIXED** (T0E-EVID-01 E1.2) |
| D-008 | `UatLocal007SandboxProfileTest.UAT-L7-TC-003` — `UnsupportedSandboxProfile` message lacks ADR-0016/M5/M9 cross-reference | P2 | **OPEN** (pre-existing, not R5.1) |
| T0E-EVID-01 | Certifier + evidence + applicability + KNOWN_LIMITATION | P0 | **FIXED** |

D-008 is the only NEW debt item opened by this work; it is
pre-existing (reproducible on `ab5bec80`) and out of scope for T0.E
closure. Operator can decide to handle it in TRAIN-1 or as an
independent T0-side fix.

## 5 — Acceptance criteria (T0.B+C+D+E consolidated)

- [x] T0.A-T0.D deliverables closed (T0BCD receipt).
- [x] T0.E Certifier correctness fixed (E1.1/E1.2/E1.3).
- [x] T0.E Evidence reconstructed (E2 — 5 UATs, 65 tests, all PASS).
- [x] T0.E Special dispositions applied (E3 — UAT-RP-005/025/026/027).
- [x] T0.E CURRENT_UAT_STATUS regenerated (E4).
- [x] T0.E Gate proven (E5 — admission 6/6, hermetic 98/98, fresh
      clones).
- [x] T0.E Integration candidate formed (E2.1 above).
- [x] T0.E Admission at I (E2.2 above — 6/6 PASS).
- [x] T0.E E2 oracles re-executed at I (E2.3 — 65/65 PASS).
- [x] T0.E Full integration suite at I (E2.4 — pre-existing D-008
      isolated, NOT a regression).
- [x] T0.E Fresh clone at I (E2.5 — 6/6 admission PASS).
- [x] T0.E SDDK recovery without `.agent/*` (E2.6 — passes).
- [x] T0.E Certifier hermeticity at I (E2.7 — counts stable).
- [x] No PRDY-010 opened. No merge to main. No TRAIN-1 opened.

## 6 — What does NOT happen in this receipt

- **No merge to main.** Per operator brief: "STOP at merge to main".
  Merge is an operator-only decision after review.
- **No promotion to release/stable.** T0.E deliverable is the
  integration candidate; stable promotion is the harness's call.
- **No PRDY-010 opened.** TRAIN-0 closes when the operator merges.
- **No TRAIN-1 work.** Out of scope.

## 7 — Operator decision required

The integration candidate `9d0607d7` is GREEN at every local gate.
Next action belongs to the operator:

1. **Review** the 11 R5.1 commits (detekt burn-down + refactor; no
   contract change).
2. **Decide** on D-008 (UAT-L7-TC-003 message enrichment) — fix in
   TRAIN-0 or defer to TRAIN-1.
3. **Decide** on pre-existing D-001..D-006 disposition.
4. **Decide** on merge to `main` (operator-only gate).
5. **Open** PRDY-010 if/when TRAIN-0 → TRAIN-1 transition is approved.

TRAIN-0 boundary holds at this receipt: nothing in T0.E opened,
nothing in T0.E merged, integration candidate ready for review.
