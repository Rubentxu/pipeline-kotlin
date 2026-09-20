# Tasks: lfc2-step-ecosystem-depuration-2026-09-20

## This cycle (LPR-082 — docs/specs only, no production code)

- [x] verify real state on 2026-09-20:
      - `LEGACY_PLUGIN_IDS = {}` (empty since WU-LPR-301, 2026-09-18)
      - `CoreStepRegistryFactory` registers 17 CoreSteps
      - G8 receipts: 8 (echo, sh, error, sleep, writeFile, isUnix,
        deleteDir, milestone)
      - G6-only: 4 (emit.event, pwd, cleanWs, archiveArtifacts)
      - contract-only: 3 (waitUntil, pwdTmp [?], writeFile [already G8])
      - missing: 2 (`core.pwdTmp` formal G6, formal contract for
        `core.writeFile`)
- [x] write `openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/proposal.md`
- [x] write `openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/design.md`
- [x] write `openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/tasks.md`
      (this file)
- [ ] update `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` with:
      - corrected certification snapshot (G8=8, G6-only=4, ...)
      - depurated list (Tier A, B, C)
      - REJECTED section (Tier D) with reasons per Step
      - EXTERNAL section (Tier E)
- [ ] regenerate `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` to
      2026-09-20 state (machine-derived counts + table)
- [ ] create `docs/v2/01-product/STEP_REGISTRY_PLAN.md` with:
      - per-Step G0..G8 plan for Tier A, B, C
      - per-Step validation set (fitness + contract + installDist)
      - binding ordering
      - explicit REJECTED list with reasons
- [ ] update `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`:
      - replace ambiguous matrix references with the depurated list
      - mark Tier D as REJECTED with reason per row
      - mark Tier E as EXTERNAL
- [ ] write WU-LPR-082 receipt at
      `docs/v2/07-uat/WU_LPR_082_STEP_ECOSYSTEM_DEPURATION.md`
- [ ] open PR (this change is non-production; merge after L4/L5 gate
      confirms no test regressions)
- [ ] push to `origin/main` + tag `wu-lpr-082`

## Next cycles (binding per Tier ordering; not in this PR)

### Tier A (CORE pending G8 — close immediately)

For each Step in Tier A, the burn-down is:
G6 → G7 → G8 → receipt → CERTIFIED. Estimated 1 WU each.

- [ ] `core.emit.event` G8 installDist + replay
- [ ] `core.pwd` G8 installDist + replay
- [ ] `core.cleanWs` G8 installDist + replay
- [ ] `core.archiveArtifacts` G8 installDist + replay
- [ ] `core.waitUntil` G6 contract + G8 installDist
- [ ] `core.pwdTmp` G6 contract + G8 installDist
- [ ] `core.writeFile` formal `StepContractSuiteTest`

### Tier B (CORE next gate — generic, high-utility)

For each Step in Tier B, the burn-down is:
G0 baseline → G1 registry seam → G2 corpus → G3 REGISTRY_PRIMARY →
G4 LEGACY_UNREACHABLE → G5 LEGACY_REMOVED → G6 contract → G7 contract
suite → G8 installDist → receipt → CERTIFIED.

- [ ] `junit.results` full burn-down to CERTIFIED
- [ ] `publishHTML` full burn-down
- [ ] `stash` full burn-down
- [ ] `unstash` full burn-down (paired with stash)
- [ ] `lock` full burn-down
- [ ] `input` full burn-down
- [ ] `httpRequest` full burn-down

### Tier C (CORE optional — only on demand)

- [ ] `readTOML` / `writeTOML` if Pyproject/Cargo demand exists
- [ ] `tar` / `untar` if parity-with-zip demand exists

### Tier D (REJECTED — never implement in core)

- [ ] N/A — explicitly not implemented; rejected reasons captured in
      `STEP_REGISTRY_PLAN.md`

### Tier E (EXTERNAL — vendor responsibility)

- [ ] N/A — explicitly not implemented by core team

## Closure

- [ ] L5 round gate green (or L4 if no production change)
- [ ] WU-LPR-082 receipt committed with argv/exit/digest
- [ ] PR merged
- [ ] Push to `origin/main` + tag `wu-lpr-082`

## Constraint reminder

- **No `DONE`/`PASS` for uncertified Step** (ADR-0074).
- **No `IMPLEMENTED_UNCERTIFIED` / `LEGACY_IMPLEMENTED_UNCERTIFIED` as
  final state** in the plan (per user directive 2026-09-20).
- Per-Step validation set is the gate; no Step closes its cycle without
  all of: C01..C19 + fitness + StepContractSuite + installDist + receipt.
- Tier D rejections are binding: future cycles must NOT add any rejected
  Step without an ADR-level justification.
