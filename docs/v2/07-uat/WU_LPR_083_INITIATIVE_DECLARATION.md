# WU-LPR-083 — Declare INITIATIVE LPR-001 + auto-run handoff

> **Cycle:** `wu-lpr-083`
> **Date:** 2026-09-20T18:23Z
> **Author:** Jcode (SDDK orchestration)
> **Trigger:** user directive 2026-09-20T18:22Z — "completar todo el roadmap con sddk en modo auto (continuar en ciclos sin parar), resolviendo cualquier bloqueo con investigación profunda... podemos considerar los futuros human_gate para lo que surjan como pre aprobados..."
> **Status:** **CLOSED** (declaration + handoff only; no production code change)

## 1. Purpose

The user formally declared an **initiative** for the LPR/LFC-2E
program: complete the entire roadmap in auto-run mode, treating every
blocker as a diagnose-and-fix WU (never quarantine, never skip), with
future human_gates pre-approved unless they touch the six exceptions.

This WU captures that directive as **binding artifacts in the repo** so
that any future session/agent picks it up automatically when it reads
the canonical anchors.

## 2. Change

### 2.1 Files committed

| File | Action | What |
|------|--------|------|
| `docs/v2/05-roadmap/INITIATIVE_LPR_001.md` | NEW | The umbrella. Operating rules (auto-run, blocker policy, strict certification law, six human_gate exceptions), scope (Tier A/A.1/B/C ordering), anchor docs. |
| `docs/v2/07-uat/HANDOFF-2026-09-20-LPR-AUTO-RUN.md` | NEW | State snapshot for the next session. Queue, anchors, state invariants, next-session start-up protocol. |
| `.agent/TESTING-STATE.md` | LOCAL-ONLY (in .gitignore per AGENTS.md) | Prepended a header with the initiative binding + queue. NOT committed; lives in the agent's local state. |

### 2.2 Why `.agent/TESTING-STATE.md` is local-only

Per AGENTS.md §Persistent Testing State, `.agent/` is the agent's local
state — it is intentionally `.gitignore`-d. The committed equivalents
are the docs in `docs/v2/` (canonical, machine-derivable). This WU
respects that convention: the directive is committed in `docs/v2/05-
roadmap/INITIATIVE_LPR_001.md`; the live local state is in `.agent/`.

### 2.3 What this WU does NOT do

- Does NOT touch production code (`v2/**/*.kt`).
- Does NOT modify `AGENTS.md`, ADRs, or `STEP_PLUGIN_CERTIFICATION.md`.
- Does NOT start any Tier A WU yet — this is the declaration. The next
  WU (WU-LPR-084: `core.writeFile` formal contract test) starts the
  queue.

## 3. Acceptance against AGENTS.md

- ✅ V2 PRIME DIRECTIVE §1: authority docs updated.
- ✅ V2 PRIME DIRECTIVE §2: scope firewall — Tier A/B/C backlog is
  explicit; closure requires Exit criterion (CERTIFIED or REJECTED).
- ✅ V2 PRIME DIRECTIVE §3: no V1 repair; legacy path is empty.
- ✅ V2 PRIME DIRECTIVE §4: no V2 dependency on
  `:pipeline-steps-system:compiler-plugin`.
- ✅ Hexagonal architecture: docs-only.
- ✅ Strict typed functional design: Tier D reasons are explicit.
- ✅ Step Constitution: per-Step validation set is the gate.
- ✅ Burn-down sequence G0..G8 enforced via the strict validation set.
- ✅ Jenkins familiarity: per-Step `C17 Jenkins compatibility`.
- ✅ RETRY-D: not affected.
- ✅ REPLAY POLICY: not affected.
- ✅ COROUTINES: not affected.
- ✅ EXECUTION CONTEXT: not affected.
- ✅ V2 TESTING RULES:
  - L0 compile UP-TO-DATE.
  - L1 fitness green (Lfc2RegistryFamily + LegacyResidualConvergence).
  - Result truth: not applicable (no new tests added).
- ✅ Intelligent change-scoped testing: docs-only change.

## 4. Verification

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

## 5. End-of-work-unit closure

```text
Reference implementation consulted: none applicable (declaration WU)
Behaviour adopted:                  bind auto-run + deep-investigation + future
                                    human_gates pre-approved for the LPR/LFC-2E
                                    program; Tier A/B/C queue binding
Intentional deviations:             none
Security implications reviewed:     n/a
Tests demonstrating the contract:    n/a (docs-only)
```

## 6. Closure checklist

- [x] INITIATIVE doc created and committed.
- [x] Handoff doc created and committed.
- [x] `.agent/TESTING-STATE.md` refreshed locally (per `.gitignore`).
- [x] L0 compile UP-TO-DATE.
- [x] L1 fitness green.
- [ ] Commit + tag `wu-lpr-083`.
- [ ] Push to `origin/main`.
- [ ] Next WU queue starts at WU-LPR-084 (`core.writeFile` formal
  contract test).
