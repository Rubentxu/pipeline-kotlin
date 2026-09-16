# Debt-Verify: wu-g5-restore

**Cycle:** wu-g5-restore
**Worktree:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5-restore`
**Branch:** `cycle/wu-g5-restore`
**HEAD audited:** `6e7b4b93` (verify report commit; code state audited at verify-verified `4100abb7`)
**Base of cycle diff:** `ca28959f`
**Date:** 2026-09-16
**Phase:** debt-verify (read-only; this file is the only artifact written)
**Inputs:** `verify.md` (PASS with documented deviations), closure receipt, inventory, cycle diff, spot-checks at HEAD.

---

## 1. Verdict

**DEBT_ACCEPTED**

All debt found is either (a) explicitly planned and assigned to the WU-G5B cycle, or
(b) documented, bounded, non-functional documentation drift with a cheap amendment
path. No hidden debt, no functional debt, no scope-firewall violation was found.
**Blocks release: NO.** Blocks archive: NO, conditioned on the two doc amendments
(items D1, D2) being done at archive time or handed to WU-G5B as its first gate task.

---

## 2. Debt inventory

### A. Accepted and planned debt (WU-G5B scope, explicitly out of this cycle)

#### A1. Legacy physical execution path for `core.waitUntil` still present

- **Evidence (fresh grep at HEAD `6e7b4b93`):**
  - `CanonicalCoreStepDecoder.kt:153` — `"core.waitUntil"` in `LEGACY_PLUGIN_IDS`.
  - `CanonicalCoreStepDecoder.kt:288` — legacy decoder branch `WAIT_UNTIL_PLUGIN_ID -> { ... }`.
  - `CanonicalCoreStepMetadata.kt:36` — metadata row `"core.waitUntil" to StepMetadata(...)`.
  - `CanonicalWaitUntilNodeDispatcher.kt` — present on disk.
  - Counter residual: **2/2/2 {core.load, core.waitUntil}** (entries/metadata rows/dispatcher files).
- **Severity:** Medium (in-flight planned debt; the project dashboard counters depend on it).
- **Owner:** WU-G5B (next cycle, per spec cross-cutting constraint: "the slice flips reachability, not LEGACY_PLUGIN_IDS membership").
- **Action:** WU-G5B executes the G0..G8 burn-down: remove dispatcher file, decoder branch, metadata row, LEGACY_PLUGIN_IDS membership → residual 1/1/1 {core.load} → 0 at load burn-down.
- **Blocks release/archive:** NO.

#### A2. `core.waitUntil` is IMPLEMENTED_UNCERTIFIED (G7/G8 pending)

- **Evidence:** inventory row state `IMPLEMENTED_UNCERTIFIED (WU-G5R-GATE: AUTHORITY_FLIPPED, G4/G5 done)`; `WaitUntilStepContractSuiteTest` 18/18 GREEN exists but certification ledger entry (CERTIFIED) is not recorded.
- **Severity:** Medium. Per ADR-0074 this is the CORRECT state representation (never record DONE/PASS for an uncertified Step). It is debt only in the sense that certification work remains.
- **Owner:** WU-G5B (CERTIFIED is reachable only after LEGACY_REMOVED; a Step cannot be CERTIFIED while legacy-executable).
- **Action:** WU-G5B completes G4→G5→G7→G8 and updates the burn-down ledger.
- **Blocks release/archive:** NO (state is honestly recorded).

### B. New unplanned debt (documented verify deviations)

#### D1. Closure receipt drift: claims counter converged 2/2/2 → 1/1/1; actual 2/2/2

- **Evidence:** `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md` lines ~75-86 claim `core.waitUntil` removed from `LEGACY_PLUGIN_IDS` and counters converged to 1/1/1. Fresh grep at HEAD contradicts this: `LEGACY_PLUGIN_IDS` still contains `core.waitUntil` (decoder L153), metadata row present (L36), dispatcher file present. Also the receipt's claim "CoreStepRegistryFactory resolves core.waitUntil → CoreWaitUntilStep" is FALSE (grep count = 0, which is the intended post-WU-G5R.4 state — the receipt's wording makes a true removal sound like an active registration).
- **Severity:** **High as documentation** (counters are the project dashboard per AGENTS.md; a receipt stating convergence that did not happen corrupts the convergence metric `M -> 0`), Low as code (zero code impact; verify.md records the truth).
- **Owner:** Current cycle (this is WU-G5R's own receipt misdescribing its own result). Fix is a doc amendment, ~15 min.
- **Action:** Amend the receipt per verify.md §8.1: counter UNCHANGED 2/2/2 → 2/2/2; LEGACY_PLUGIN_IDS removal is WU-G5B scope; reword the CoreStepRegistryFactory sentence ("registration removed in WU-G5R.4", not "resolves to").
- **Blocks release:** NO. **Blocks archive:** conditionally — do the amendment at archive time or record it as WU-G5B's G0 entry condition. Releasing/archiving with a receipt that overstates counter convergence is the one item I would not let silently persist.

#### D2. Step inventory row drift: `Def=Y`, `Path=registry (WU-G5R)`, `Legacy=N` vs spec `structural` / `N/A` / `Y`

- **Evidence:** `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md:70` vs spec MODIFIED row. Code truth: `CoreStepRegistryFactory` registers nothing for `core.waitUntil` (count=0), so `Def=Y` and `Path=registry` are misleading; `Legacy=N` contradicts the verified legacy residual (A1).
- **Severity:** Medium (inventory is a registry source-of-truth consumed by counters and future burn-down planning; two downstream readers would conclude legacy is already gone).
- **Owner:** Current cycle (amendment) — same fix window as D1.
- **Action:** Amend row per verify.md §8.2: `Path = structural`, `Def = N/A (structural, not a Step)`, `Legacy = Y (until WU-G5B)`.
- **Blocks release:** NO. **Blocks archive:** conditionally (same window as D1).

#### D3. ADR-0085 pending: policy-family decision for waitUntil body carrier

- **Evidence:** verify.md Deviation #1: implementation uses `BlockShellScope.WaitUntilScope` (7th case of the existing closed family) instead of adding `BodyExecutionPolicy.RepeatUntil` (5th case). Design §14.2 documents the ORCHESTRATION decision but no ADR codifies the ADT-shape choice. Verified: `docs/v2/04-adrs/` latest is ADR-0081; no ADR-0085 exists.
- **Severity:** Low-Medium (the decision is sound — closed ADT invariant preserved, sealed family extended with a typed case — but it contradicts spec/design §6.2 literal text and is currently only justified in verify.md prose; per AGENTS.md, spec/ADR divergence should be reconciled by amending the ADR layer, not left implicit).
- **Owner:** Backlog, but should land before or with WU-G5B (WU-G5B's burn-down cites the canonical carrier; it should cite an ADR, not a verify report).
- **Action:** Propose ADR-0085: "BlockShellScope as canonical carrier for body-bearing ORCHESTRATION steps; BodyExecutionPolicy deliberately not extended". Alternatively amend design.md §6.2 to match the implementation. Either way, one authority document.
- **Blocks release/archive:** NO.

#### D4. Rename debt: `executeWaitUntilBody` vs spec `dispatchRepeatUntilBody` + stale KDoc/comments

- **Evidence:** `CanonicalDurableRunCoordinator.kt:1871` (`executeWaitUntilBody`); stale references to the old name remain in `PipelineDsl.kt:647` (KDoc), `Lfc2WaitUntilCanonicalReentryFitnessTest.kt:233,235`, `CoreSleepRegistryPrimaryFitnessTest.kt:176` (comments referencing `BodyExecutionPolicy.RepeatUntil`, which also does not exist — ties to D3).
- **Severity:** Low (cosmetic; semantics intact; comments in tests document an ADT case that was never added — mild reader confusion risk).
- **Owner:** Backlog (can ride along with D3's ADR/spec reconciliation or WU-G5B's doc pass).
- **Action:** Update KDoc/comments to the real names; decide once in D3 whether to rename the function to the spec name or amend the spec to the code name. Do not rename for cosmetics alone during WU-G5B.
- **Blocks release/archive:** NO.

#### D5. Sentinel implementation divergence: `AtomicBoolean` vs spec `ThreadLocal<Boolean>`

- **Evidence:** `CanonicalDurableRunCoordinator.kt:119`; spec §6.5 said ThreadLocal. AtomicBoolean is strictly more defensive (cross-thread visibility) and the fitness reads it through an accessor compatible with both.
- **Classification:** NOT debt — accepted, code-is-better-than-spec divergence. No action; record only so a future ADR (D3) captures it.
- **Blocks release/archive:** NO.

---

## 3. Hidden-debt scan (zero-fabrication; all checks run fresh at HEAD)

| Check | Command/Method | Result |
|---|---|---|
| New TODO/FIXME/HACK/XXX in cycle diff | `git diff ca28959f..HEAD -- '*.kt'` grep for markers on added lines | **0 hits** — no marker debt introduced |
| Deleted/dead files | `git diff --name-status ca28959f..HEAD -- '*.kt'` grep `^D` | **0 deletions**; no orphaned files introduced (all 16 added files are waitUntil-specific and referenced by tests/fitness) |
| Duplicate test files | basename de-dup over changed `*Test.kt` | **0 duplicates** |
| Stale cross-references | grep `dispatchRepeatUntilBody` | 3 comment/KDoc sites (D4); no functional references |
| ADR-0085 existence | `find docs/v2 -iname '*0085*'` + ADR dir listing (latest = ADR-0081) | Does not exist (D3) |
| Legacy residual spot-check | grep decoder/metadata/registry-factory at HEAD | Confirms verify.md Deviation #2: 2/2/2, registry count=0 (A1/D1) |
| Pre-existing red widening | per verify.md §4 fresh XML evidence: `CanonicalDurableRunCoordinatorTest` 26/0 (was 26/11 — **reduced**), `CompatibilityCorpusTest` 21/1 (fixture14 pre-existing only) | NOT widened; positive surprise recorded in verify.md §3.6 — no hidden regression masked by a green |

Scope firewall: no debt item requires changes outside the declared WU-G5B boundary; no item contradicts AGENTS.md hexagonal/typed-functional rules in the shipped code (the ADT-shape question is D3, a documentation-authority gap, not a code defect).

---

## 4. Release/archive gate summary

| Item | Severity | Owner | Blocks release | Blocks archive |
|---|---|---|---|---|
| A1 legacy residual 2/2/2 | Medium | WU-G5B | no | no |
| A2 G7/G8 CERTIFIED pending | Medium | WU-G5B | no | no |
| D1 closure receipt counter drift | High(doc)/Low(code) | current cycle (amend) | no | **conditionally** (amend at archive or WU-G5B G0) |
| D2 inventory row drift | Medium | current cycle (amend) | no | **conditionally** (same window as D1) |
| D3 ADR-0085 pending | Low-Medium | backlog / WU-G5B | no | no |
| D4 rename + stale KDoc/comments | Low | backlog | no | no |
| D5 sentinel divergence | none (accepted) | — | no | no |

**Final verdict: DEBT_ACCEPTED — does not block release.** Recommended order: amend D1+D2 before archive; fold D3+D4 into WU-G5B's opening gate; A1+A2 are WU-G5B's declared body of work.

---

**Generated:** 2026-09-17T01:45+02:00
**Auditor:** sddk-debt-verify (read-only)
**Fabrication check:** every code-state claim above was re-verified by fresh grep at HEAD `6e7b4b93` in this run; test counts are cited from verify.md §4 fresh-run XML evidence (not re-executed in this phase — debt-verify is documentary, per phase contract).
