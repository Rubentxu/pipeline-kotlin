# WU-LPR-082 — Step Ecosystem Depuration

> **Cycle:** `wu-lpr-082`
> **Date:** 2026-09-20T18:20Z
> **Author:** Jcode (SDDK orchestration)
> **Trigger:** User directive 2026-09-20 — "registralo todo en los documentos necesarios para que en las proximas iteraciones y ciclos implementemos solo los steps que has propuesto" + "todos los steps deben estar certificados y pasar todas las validaciones y controles que pusimos de manera extricta"
> **Status:** **CLOSED** (docs/specs only; no production code change)

## 1. Purpose

The user asked for two things:

1. A **depurated list** of Steps that the team will implement in
   upcoming cycles, with explicit rejection of tool-specific / vendor /
   niche / Jenkins-only Steps.
2. A **strict certification rule**: every Step on the list must pass
   all validation gates before being recorded as complete
   (no `DONE/PASS`, no `IMPLEMENTED_UNCERTIFIED`, no
   `LEGACY_IMPLEMENTED_UNCERTIFIED`).

This WU delivers both by:

- Updating `STEP_ECOSYSTEM_MATRIX.md` with the depurated list and
  explicit REJECTED section.
- Regenerating `STEP_INVENTORY_LFC2E0.md` (machine-derived) to reflect
  the real 2026-09-20 state.
- Creating `STEP_REGISTRY_PLAN.md` (operational roadmap with per-Step
  G0..G8 plan + validation set).
- Updating `LFC2_STEP_ECOSYSTEM_EXPANSION.md` to point at the new plan.
- Creating an OpenSpec change
  `lfc2-step-ecosystem-depuration-2026-09-20/` with `proposal.md`,
  `design.md`, `tasks.md`.

## 2. Diagnosis

Before writing the plan, an honest audit of the current state:

### Inventory machine-derived (2026-09-20)

```text
Production Step keys total:                  18
  Registry (open-world Step seam):           17   (CoreStepRegistryFactory)
  Legacy (Canonical*NodeDispatcher):         0    (LEGACY_PLUGIN_IDS = {})
  External plugin (ServiceLoader):           1    (example.uppercase)

CERTIFIED (G8 + real installDist + receipt):  11
  core.echo, core.sh, core.error, core.sleep, core.writeFile, core.isUnix,
  core.deleteDir, core.milestone, core.cleanWs, core.archiveArtifacts,
  core.emit.event
  example.uppercase (external)

G6/G7 BLOCKED by horizontal blocker:          1
  core.pwd (STRUCTURED_DSL_RUNTIME_RETURN_GAP — affects pwd/pwdTmp/readFile/fileExists)

REGISTRY_PRIMARY, contract pending, installDist pending:  2
  core.waitUntil, core.pwdTmp

CERTIFIED but missing formal contract suite:    1
  core.writeFile (G8 done; StepContractSuiteTest not yet authored)

Step SDK plugins (open-world, registry seam):
  scm-git.checkout, junit.results

Core utilities plugin (registry):
  zip, unzip, findFiles, readJson, writeJson, readYaml, writeYaml, sha256
```

### Honesty corrections vs the prior matrix

The prior `STEP_ECOSYSTEM_MATRIX.md` (2026-09-11) and
`STEP_INVENTORY_LFC2E0.md` (2026-09-11) were stale:

- The matrix claimed "12 LEGACY_IMPLEMENTED_UNCERTIFIED". Reality on
  2026-09-20: `LEGACY_PLUGIN_IDS = {}` since WU-LPR-301 (2026-09-18);
  counter converges to 0/0/0.
- The matrix claimed "3 CERTIFIED". Reality: 11 CoreSteps + 1 external
  plugin are CERTIFIED today (11 receipts `S2_*_G8_*_FINAL_*` exist).
- The matrix proposed ~25 NOT_STARTED families. The user explicitly
  said "no npm, maven, etc." → many of those are now REJECTED.

The matrix was honest at its writing; this WU brings it back to honest.

## 3. Change

### 3.1 OpenSpec change

Created
`openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/`:

- `proposal.md` — Why, outcomes, strict certification law, validation
  set, depurated list (Tiers A..E).
- `design.md` — Why docs-only; anchoring; tier ordering; risks.
- `tasks.md` — Concrete checklist for this cycle + the binding
  Tier A/B/C tasks for upcoming cycles.

### 3.2 Updated docs

- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` —
  - DEPURATION banner at the top.
  - Corrected certification snapshot.
  - Tier A/B/C/D/E depurated list (binding).
  - REJECTED section with reason per Step.
  - Strict validation set (5 layers per Step).
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` —
  - Regenerated with 2026-09-20 machine-derived counts.
  - 11 G8-CERTIFIED listed explicitly.
  - 1 BLOCKED (`core.pwd` — horizontal blocker).
  - Explicit "Known blockers" section so the gap cannot be hidden.
- `docs/v2/01-product/STEP_REGISTRY_PLAN.md` — **new**:
  - Per-Step G0..G8 plan and binding ordering.
  - Strict Validation Set (5 layers).
  - Tier D REJECTED list with reasons.
  - Tier E EXTERNAL list with vendor responsible.
- `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md` —
  - New "DEPURATION 2026-09-20" section replacing the E2..E10 sequence
    with the depurated list.

### 3.3 Strict certification law (binding, re-asserted)

Per `STEP_PLUGIN_CERTIFICATION.md` §R1 and AGENTS.md "Step is done only
when CERTIFIED":

- **NEVER** `DONE`, `PASS`, `IMPLEMENTED_UNCERTIFIED`,
  `LEGACY_IMPLEMENTED_UNCERTIFIED`, `WIP`, `TBD`, `partial` as a final
  state in the depurated plan.
- Every Step on Tier A/B/C must reach **`CERTIFIED`** (full G0..G8 +
  fitness + StepContractSuite + installDist + receipt) OR be
  **REJECTED** with explicit reason in Tier D.

## 4. Validation Set (binding per Step)

Every Step must pass all 5 layers before `CERTIFIED`:

1. **ADR / constitutional gates** (fitness: no `when(stepKey)`, no
   privileged core path, registry seam, capability == used, no global
   cwd/env mutation).
2. **Step contract gates** (C01..C19 of
   `STEP_PLUGIN_CERTIFICATION.md`).
3. **Fitness test suite** (`Lfc2RegistryFamilyFitnessTest`,
   `Lfc2DurableCoordinatorScopeFitnessTest`,
   `Lfc2ConcreteBodyRoutingDebtFitnessTest`,
   `Lfc2BodyExecutionPolicyFitnessTest`,
   `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`,
   `Lfc2B11ExternalScopedRoutingDefenseFitnessTest`,
   `S3<Name>LegacyRemovedFitnessTest`,
   `LegacyResidualConvergenceFitnessTest`).
4. **StepContractSuite** (16/17 standard rows: identity, contract
   completeness, codec input/output, canonical envelope, registry
   resolution, capability admission, success, typed failure, fresh
   durable, replay, divergence, observability, missing capability,
   architecture fitness, real DSL scenario).
5. **installDist + real installed binary** (real `.pipeline.kts`
   fixture, same `--db`/`--control-root` for fresh and replay,
   `SHA-256` of binary + artifact fingerprint).
6. **Receipt** with argv, exit code, XML counters, `Status: CERTIFIED`.

## 5. Verification (this WU)

```text
L0  compile : ./gradlew -p v2 :pipeline-application:compileKotlin
                   :pipeline-step-sdk:utilities:compileKotlin
              -> BUILD SUCCESSFUL (UP-TO-DATE)
              -> 45 actionable tasks: 45 up-to-date

L1  fitness : ./gradlew -p v2 :pipeline-architecture-tests:test
                   --tests '*Lfc2RegistryFamily*'
                   --tests '*LegacyResidualConvergence*'
              -> BUILD SUCCESSFUL in 3s
              -> 62 actionable tasks: 1 executed, 61 up-to-date

Production code change : 0
```

No production code change. This is a docs-only WU.

## 6. End-of-work-unit closure

```text
Reference implementation consulted: none applicable (spec-only WU)
Behaviour adopted:                  docs/specs synchronization; depurated list with
                                    explicit REJECTED section; binding certification law
Intentional deviations:             none
Security implications reviewed:     n/a for this WU (no code)
Tests demonstrating the contract:    n/a for this WU (no code)
```

### Acceptance against AGENTS.md

- ✅ V2 PRIME DIRECTIVE §1: authority `docs/v2/` updated (matrix,
  inventory, plan, roadmap).
- ✅ V2 PRIME DIRECTIVE §2: scope firewall — backlog (Tier A/B/C) is
  explicit; closure requires Exit criterion (CERTIFIED or REJECTED).
- ✅ V2 PRIME DIRECTIVE §3: no V1 repair; legacy path is empty and
  stays empty.
- ✅ V2 PRIME DIRECTIVE §4: no V2 dependency on
  `:pipeline-steps-system:compiler-plugin` (touched nothing).
- ✅ Hexagonal architecture: docs-only; no module dependencies changed.
- ✅ Strict typed functional design: Tier D reasons are explicit
  (tool-specific, vendor, niche, Jenkins-only, cosmetic).
- ✅ Step Constitution: per-Step validation set is the gate; no
  `IMPLEMENTED_UNCERTIFIED` final state.
- ✅ Burn-down sequence G0..G8 enforced via the strict validation set.
- ✅ Jenkins familiarity: per-Step `C17 Jenkins compatibility` is part
  of the validation set.
- ✅ RETRY-D: not affected (no retry changes).
- ✅ REPLAY POLICY: not affected (no Step semantics changes).
- ✅ COROUTINES: not affected (no execution model changes).
- ✅ EXECUTION CONTEXT: not affected (no execution context changes).
- ✅ V2 TESTING RULES:
  - L0 compile UP-TO-DATE (no regression).
  - L1 fitness 1/1 executed, 61/61 up-to-date.
  - Result truth: not applicable (no new tests added).
- ✅ Intelligent change-scoped testing: docs-only change; L0+L1
  sufficient (no production code touched).

## 7. Closure checklist

- [x] OpenSpec change created (proposal, design, tasks).
- [x] `STEP_ECOSYSTEM_MATRIX.md` updated.
- [x] `STEP_INVENTORY_LFC2E0.md` regenerated.
- [x] `STEP_REGISTRY_PLAN.md` created.
- [x] `LFC2_STEP_ECOSYSTEM_EXPANSION.md` updated.
- [x] L0 compile UP-TO-DATE.
- [x] L1 fitness green.
- [ ] Commit + tag `wu-lpr-082`.
- [ ] Push to `origin/main`.
