# INITIATIVE LPR-001 — Complete LPR/LFC-2E Roadmap in Auto-Run Mode

> **Initiative ID:** `LPR-001`
> **Authoritative scope:** `pipeline-kotlin` repository
> **Owner:** Jcode (SDDK orchestration) + user (RWAL) on the human side
> **Declared:** 2026-09-20T18:22Z
> **Binding until:** roadmap closure (LFC-2E10 / Cedar policy binding /
>   local-first feature freeze) OR explicit user override
> **Source of authority:** user directive 2026-09-20T18:22Z + `AGENTS.md`
>   user law #4 (auto-run mode; future human_gates pre-approved; respect
>   directives unless refined by an evidence-backed blocker investigation).

## 1. Goal

**Complete the entire LPR / LFC-2E roadmap in auto-run mode**, working
through cycles without stopping between WUs, **closing each blocker with
deep investigation** rather than quarantining or skipping it.

This initiative binds the agent to a continuous operating mode for the
duration of the LPR / LFC-2E program. It is the operational umbrella
under which all current and upcoming WUs run.

## 2. Operating rules (binding)

These rules apply to every cycle and every WU under this initiative.
They are not project-local; they are *operational* and override any
prior local disposition that contradicts them.

### 2.1 Auto-run (no pausing)

- Continue in cycles without pausing.
- Do not stop to ask the user for human_gate decisions.
- Future human_gates are **pre-approved** unless they touch the
  exceptions in §2.4.
- A cycle ends only when its acceptance criteria are met OR when one of
  the §2.4 exceptions fires.

### 2.2 Blocker policy: deep investigation → solution → continue

- A blocker is **not** a reason to quarantine, skip, or mark
  `WONTFIX`. Every blocker becomes a **diagnose-and-fix** WU.
- "Deep investigation" per AGENTS.md / diagnose skill discipline:
  reproduce → minimise → hypothesise → instrument → fix → verify.
- A fix that touches the strict certification law (ADR-0074 + §R1
  STEP_PLUGIN_CERTIFICATION) must be backed by an ADR-level
  justification, recorded in the receipt.
- A fix that refines a roadmap spec must be backed by the evidence
  found during investigation; the refinement is recorded in the
  receipt as **"refinement over proposal"** with the evidence
  attached.

### 2.3 Respect roadmap directives

- The depurated list in
  [`docs/v2/01-product/STEP_REGISTRY_PLAN.md`](docs/v2/01-product/STEP_REGISTRY_PLAN.md)
  is binding. The Tier A/B/C ordering is the queue.
- The strict certification law (no `DONE/PASS`,
  `IMPLEMENTED_UNCERTIFIED`, `LEGACY_IMPLEMENTED_UNCERTIFIED`,
  `WIP/TBD/partial` as final states) is binding.
- The 5-layer Strict Validation Set per Step is binding.
- Per-WU constraints from AGENTS.md are binding (hexagonal architecture,
  typed functional design, no `when(stepKey)`, registry seam, etc.).

### 2.4 Exceptions that DO require a human_gate

The following require explicit user GO before the agent acts:

1. **Change a public certified semantics.** This breaks external
   consumers. STOP and present.
2. **Alter a published release** (delete a tag, replace a published
   binary, rewrite a published receipt). STOP and present.
3. **Add a plugin-specific exception to the engine or coordinator**
   (concrete `StepSpec` subtype, privileged core path). STOP and
   present.
4. **Introduce remote storage, new protocols, or incompatible public
   API.** STOP and present.
5. **Alter historical receipts.** STOP and present.
6. **The integral goal is technically infeasible inside the LPR/LFC-2E
   scope.** STOP and present the boundary.

For everything else, the agent acts and reports. The user reviews
through the human_feed (per SDDK overlay) and the per-WU receipts.

### 2.5 Per-WU discipline (binding)

- **One WU = one block.** Don't pile multiple unrelated fixes into a
  single commit.
- **Evidence-driven refinement.** Refining a spec is allowed only when
  backed by evidence of a real blocker; the refinement is recorded in
  the receipt.
- **Receipts.** Every WU writes a receipt at
  `docs/v2/07-uat/WU_<ID>_*.md` with: argv, exit code, XML counters,
  SHA-256 fingerprint, link to base SHA, link to evidence files, and a
  final `Status: CLOSED` line.
- **Commit hygiene.** Commit as you go; don't accumulate a long
  uncommitted state.
- **Hygiene.** No leftover Gradle daemons or watchdog monitors.
  `bg`/`wait` for long-running L5 gates; cancel aggressively.
- **L5 gate.** Run `./gradlew -p v2 check` (incremental) once per
  apply/verify round, not per iteration. Use the derived budget
  (last green × 1.3, floor 600, ceiling 1800). Wrap in `timeout`.

### 2.6 Auto-advance (no per-phase confirmation)

- Once a cycle starts, run the path sequence end-to-end (A-lite
  default; A-min / A-full as per routing decision) without pausing.
- The cycle ends only when §2.4 fires, the cycle ledger is closed, OR
  the user gives an explicit STOP.

### 2.7 Reporting (human feed)

- At the close of every stage, emit a `human_feed` block (per SDDK
  overlay).
- Honest: real evidence only; never fabricated; never re-promise.
- Short: 6 lines per block max.

## 3. Scope (binding)

The initiative covers the following roadmap slices in priority order:

```text
Tier A — burn down already-registry Steps (LFC-2E1-S2)
  1. core.writeFile (formal contract test)
  2. core.waitUntil (G6+G8)
  3. core.pwdTmp (G6+G8; depends on LFC-2R2)
  4. core.pwd (BLOCKED — STRUCTURED_DSL_RUNTIME_RETURN_GAP; needs LFC-2R2)

Tier A.1 — LFC-2R2 (horizontal blocker)
  - Structured Runtime-Returning Steps (pwd/pwdTmp/readFile/fileExists)

Tier B — CORE next gate (genéricos universales, 7 Steps)
  5. junit.results (full burn-down)
  6. stash + 7. unstash
  8. publishHTML
  9. lock
  10. input
  11. httpRequest

Tier C — CORE on demand
  12. readTOML/writeTOML
  13. tar/untar
```

Out of scope (Tier D/E) per the depurated list:
toolchains (Maven/npm/...), containers (Docker/K8s), vendors
(Slack/Artifactory/SonarQube/Vault/AWS/Azure/GCP), Jenkins-only idioms,
cosmetic decorators, weak hashes, niche utilities. **Never in core.**

## 4. Definition of Done

The initiative closes when **all Tier A + A.1 + B + C Steps reach
CERTIFIED** per the strict certification law, **or are REJECTED with
explicit reason in Tier D**, AND the local-first feature freeze is
declared.

Until then, the agent continues. The user reviews through the receipts
and the human_feed.

## 5. Anchor documents (binding)

- [`docs/v2/01-product/STEP_REGISTRY_PLAN.md`](../01-product/STEP_REGISTRY_PLAN.md) — operational roadmap.
- [`docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md`](../01-product/STEP_ECOSYSTEM_MATRIX.md) — planning matrix (corrected 2026-09-20).
- [`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`](../07-uat/STEP_INVENTORY_LFC2E0.md) — machine-derived source of truth.
- [`docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md`](../03-specifications/STEP_PLUGIN_CERTIFICATION.md) — certification law (R1..R4).
- [`docs/v2/03-specifications/STEP_CONSTITUTION.md`](../03-specifications/STEP_CONSTITUTION.md) — Step Constitution.
- [`docs/v2/03-specifications/STEP_PLUGIN_SDK.md`](../03-specifications/STEP_PLUGIN_SDK.md) — public SDK surface.
- ADR-0070..0074 (closed structure, open registry, executable scenarios,
  layered test harness, certified-only state machine).
- AGENTS.md — operating law for this project.
- [`openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/`](../../../openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/) — WU-LPR-082 change.

## 6. Change log

```text
2026-09-20T18:22Z  LPR-001 declared.
                   Captures user directive (auto-run, deep-investigation,
                   future human_gates pre-approved).
                   Anchors to LFC-2E1-S2 Tier A as the next WU queue.
```

## 7. Override

This initiative may be **suspended or rescinded** by explicit user
instruction. The agent does not auto-suspend it; the user does.

A cycle that reaches an exception (§2.4) is a STOP, not a suspension.
The user reviews and either:
- GO → continue under this initiative.
- REPLAN → the agent proposes a new path; the user decides.
- STOP → the initiative is paused pending user input.
