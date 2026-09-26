# TRAIN-0 — T0.E Receipt (corrective — regen-drift detected)

**Train:** `TRAIN-0 — Baseline Consolidation & Legacy Closure`
**SDDK cycle:** `p-733fb505b5a6bd2d/train-0-baseline-consolidation`
**WorkItem:** `c338edee-9ec0-4433-8577-ac59e9f18d3d` (T0.E — Integration candidate)

## Outcome: BLOCKED — corrective action recorded

T0.E reached its full plan and produced an `I` commit (`12e0ab4f`). The cheap re-verify against `I` detected **drift in the regenerated `CURRENT_UAT_STATUS.md`** material to the admission-check R4 verdict (a real semantic difference, not a doc-side cosmetic change). Per the T0.E contingency clause ("revert the receipt commit and emit a corrective WorkItem"), this receipt supersedes the initial success-narrative version and records what actually happened.

## What was actually completed

### Step 1 — Freeze candidate C
```bash
C = 38b05a5e3b46fdf2e768614adc05e2c5d5772be6
```
Frozen. `git merge-base --is-ancestor C origin/wu/rp-053r-red-fixtures` = YES.

### Step 2 — Full integration suite at C
```bash
./v2/gradlew -p v2 :pipeline-application:compileTestKotlin --quiet               # EXIT=0 in 12s
./v2/gradlew -p v2 :pipeline-application:test \
    --tests 'Pipeline*' --tests '*StepContract*' --tests '*UatLocal*'             # EXIT=0 in 6m38s
```

XML canary (sole authoritative source per AGENTS.md rule 25):

```text
TOTAL: tests=2317 failures=0 errors=0
```

The Gradle stream contained one `StepFailed` event in a script-run; XML counter = 0 failures confirms it was an `expected negative-case` `UatLocal*` test (asserts a script-exit failure), not a real defect.

### Step 3 — Admission at C with repo state at C
```bash
python3 scripts/admission-check.py --head 38b05a5e
```
Initial output (before any regen-of-state-projection in T0.E):
```text
Results: 6 PASS, 0 FAIL  EXIT=0
```
Scripts unit suite: 66/66 PASS (4 suites).

### Step 4 — Fresh clone at C
```bash
git clone -b wu/rp-053r-red-fixtures /tmp/t0e-fresh-clone
git checkout 38b05a5e
python3 scripts/admission-check.py --root /tmp/t0e-fresh-clone   # 6/6 PASS, EXIT=0
```
Reproducibility invariant proven at C.

### Step 5 — SDDK recovery without .agent/* (with .agent hidden)
With `mv .agent .agent.hiding` in `/tmp/t0e-fresh-clone`:
```text
project_id       = p-733fb505b5a6bd2d
identity_source  = remote
plan roadmap     -> active_item = c338edee-…; blocked = []
plan work-item   -> 6 work-items visible (all states)
git rev-parse    -> HEAD = C, origin/main = acc90387
```
No-muleta invariant confirmed. `.agent/*` restored, no data loss.

### Step 6 — Provenance sample
283 receipts under `docs/v2/07-uat/` at C. 20/20 sampled show last-commit ancestor of C.

### Step 7 — Receipt + state projection regen + I commit (initial)
First regen of `CURRENT_UAT_STATUS.md` and `CURRENT_STATE.md` produced an I commit (`12e0ab4f`) with the new T0.E receipt, the regenerated CURRENT_STATE, and the regenerated CURRENT_UAT_STATUS.

### Step 8 — Cheap re-verify on I — **DRIFT DETECTED, REVERTED**

```bash
python3 scripts/admission-check.py --head 12e0ab4f
```

Output:

```text
Results: 5 PASS, 1 FAIL  EXIT=1
  [PASS] R1 current_state exists
  [PASS] R2 state.HEAD is ancestor of candidate
  [PASS] R3 working tree clean (exact-path exclusions)
  [FAIL] R4 no blocking UAT states: R4 blocking UATs without exception: UAT-RP-013=FAIL_PROVEN
  [PASS] R5 receipt provenance (Git ancestry, per UAT)
  [PASS] R7 origin/main not ahead
```

Same fresh-clone at I: identical failure. Reproducible across operators.

#### Drift diagnosis (post-mortem)

Compare `git show 38b05a5e:docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (committed at C) with the post-regen at I:

```text
At C (committed):  UAT-RP-013 = COVERED
At I (regenerated): UAT-RP-013 = FAIL_PROVEN
At C (committed):  UAT-RP-001..004 = COVERED
At I (regenerated): UAT-RP-001..004 = REFERENCED
```

Numerous other UATs flipped COVERED → REFERENCED alongside UAT-RP-013.

**Root cause** (verified by direct grep + script logic inspection):

`scripts/gen-current-uat-status.py` matches each UAT ID against `docs/v2/07-uat/` and uses `STATUS_PATTERNS` ordered COVERED → PARTIAL → FAIL_PROVEN → BLOCKED → NOT_RUN → REJECTED, with `re.I` (case-insensitive). For UAT-RP-013, the only mention in `PRODUCTION_READY_UAT_MATRIX.md` is:

```text
| UAT-RP-013 | Divergence | cambiar input/script y reusar runId;
              rechazo fail-closed antes de nuevos efectos
              | error tipado y marker inalterado |
```

The hyphen-word `fail-closed` matches `\bFAIL\b` (regex word boundary + case-insensitive hyphen). The regex returns FAIL_PROVEN for this row alone.

**Why did C report COVERED then?** At C's regeneration time, the `PRODUCTION_READY_UAT_MATRIX.md` (or another receipt reachable from C) contained a literal substring `COVERED (RP-1)` near UAT-RP-013. The generator's first matching line carried COVERED into STATUS first (pattern order). Later commits removed that substring (RP-1 narrative archived to `docs/historico/` or otherwise pruned); on the next regen, FAIL_PROVEN surface fired correctly and the row downgraded.

**Verdict:** this is a **defect in `gen-current-uat-status.py`**, not a product regression. Two failures present:

1. **False COVERED** at C: the regex picked up a transient `COVERED (RP-1)` narrative annotation, classifying a UAT as covered when it was actually divergent.
2. **Real FAIL_PROVEN** at I (correct classification), but wrongly flagged as a new regression by admission-check R4.

Either way, **the regenerated state is materially different from C**, so `C..I = docs-only` fails its semantic check (despite being literal-bytes docs only).

### Step 9 — Corrective action executed

```text
1. Reset HEAD to C (`38b05a5e`).
2. Restored CURRENT_STATE.md and CURRENT_UAT_STATUS.md to C-committed content.
3. Kept TRAIN_0_T0E_RECEIPT.md (this file, rewritten to corrective narrative).
4. Documented D-007 (gen regex false-COVERED/Hungarian-FAIL classification defect).
5. Did NOT commit I.
```

## Debt item

### D-007 — `gen-current-uat-status.py` false-COVERED classifier

**Severity:** P2 (does not block product; blocks certifier state accuracy).

**Evidence:** this receipt. Same defect also caught in the receipt's drift-diagnosis section.

**Symptom:**
- `\bFAIL\b` matches `fail-closed` (and likely `fail-over`, `fail-fast`). False positives on status classification for any UAT description mentioning those phrases.
- Conversely, a transient `COVERED (RP-x)` narrative annotation anywhere in the matched corpus flips classification to COVERED for as long as the annotation exists, even if no actual certification evidence is present.

**Repair sketch (TRAIN-1 RP-5 Closure or other):**
- Tighten STATUS_PATTERNS: `\bFAIL_PROVEN\b` only (no fallback `\bFAIL\b`); require explicit `FAIL_PROVEN` marker.
- For COVERED: require either a `certified_at_sha` annotation in the matched line, or a dedicated cert-receipt path on disk.
- Add unit tests in `scripts/test_gen_current_uat_status.py` covering: hyphen-FAIL false positives, transient COVERED narrative false positives, and a clean baseline.
- Reclassify UAT-RP-013 explicitly to `FAIL_PROVEN` (or whichever the operator decides). The current C-committed `CURRENT_UAT_STATUS.md` has it as COVERED, which is wrong; the operator should decide whether to amend it via fe-de-erratas.

**Blocking RP-5?** No, but blocking **admission-check R4 determinism**, which is part of the certifier path.

## Integration candidate status

```text
Candidate  C = 38b05a5e3b46fdf2e768614adc05e2c5d5772be6
Receipt    I = NOT SHIPPED
Working tree: clean (HEAD = C), one untracked file (this receipt)
origin/wu/rp-053r-red-fixtures = 38b05a5e (T0.D was the last pushed commit)
origin/main = acc903875d70f939713786d71a6331bb6ccf7dc9 (UNCHANGED)
harness #3  = OPEN
```

The 46 commits ahead of origin/main counted at I are now reduced back to 45 at C (because I was reset). T0.E deliberately did NOT push a candidate that would have failed R4 admission.

## Operator decision required

**Pending operator input on T0.E finalisation.** Three options:

1. **Re-emit T0.E without the state-projection regen.** Commit only `TRAIN_0_T0E_RECEIPT.md` (this file) on top of C. `C..I = docs-only, +1 file`. Cheap re-verify: admission at I = R3 FAIL (working tree dirty by the uncommitted receipt), but after commit it should pass. Select this option if the operator accepts that `CURRENT_UAT_STATUS.md` is best left at C's snapshot until D-007 is fixed in TRAIN-1.

2. **Defer T0.E close to TRAIN-1.** Apply D-007 fix + state-projection regen as a single TRAIN-1 RP-5 closure slice, then re-emit a TRAIN-0 closure-T0.E receipt there. Slower, but eliminates the false-COVERED problem entirely.

3. **Tighten the generator inline now + re-emit T0.E.** Apply minimal D-007-pattern tightening commit (just regex changes + one unit test) in TRAIN-0, then re-emit T0.E with regenerated projections that should match C's expectations. Faster but requires a code-touching change in TRAIN-0, which the operator may consider out of scope.

## Negative knowledge

- The generator's `\bFAIL\b` hyphen-word pattern is a known false-positive that has not been fixed. Documented as D-007.
- The transient `COVERED (RP-1)` narrative was probably carried in `RP3_EXIT_REVIEW.md` once and has since been archived, leaving C's committed CURRENT_UAT_STATUS.md in a stale-as-claimed state. The drift was *always* there; only now, without the narrative, does it surface.
- False-green precedents (UATLocal008 credential events, UATLocal009 archiveArtifacts) cited in the prior receipt's session summary remain out-of-scope; D-007 is a separate finding.

## Material identity (after corrective action)

```text
Cycle        : p-733fb505b5a6bd2d/train-0-baseline-consolidation (OPEN, T0.E blocked)
WorkItem     : c338edee-9ec0-4433-8577-ac59e9f18d3d (Active; corrective)
Slices A..D  : DONE in repo (commits 40f91842 + 38b05a5e)
Slice E      : BLOCKED, requires operator decision (1, 2, or 3 above)
Debt items   : D-001..D-006 (legacy); D-007 (new in this receipt)
origin/main  : acc90387 (UNCHANGED)
harness #3   : OPEN (external; not addressed by TRAIN-0)
```

The receipt-of-receipts discipline that protected T0.A + T0.B/C/D from operator confusion now applies to T0.E: rather than ship a receipt that failed its own gate, we revert and document. The operator gets the actual evidence, not a sanitised success narrative.
