# Cumulative Checkpoint — WU-LPR-401 / 402 / 010 / 032 train

**Status:** `PAUSED AT USER CHECKPOINT`.

**Scope:** 4 WUs executed in sequence per the user's auto-train:
`WU-LPR-401 → WU-LPR-402 → WU-LPR-010 → WU-LPR-032 → checkpoint`.

**Commits in this train (8):**

```text
48fc44b7 WU-LPR-401 Phase 1 — DSL isolation via @DslMarker
b4f66166 WU-LPR-401 Phase 3 — waitUntil DSL honesty
05565f2b WU-LPR-401 Phase 4 — closure receipt
91435dfb WU-LPR-402 Phases 1+2+3 — pwd()/isUnix() honest runtime semantics
c01a54a9 WU-LPR-402 Phase 4 — closure receipt
3ed81466 WU-LPR-010 — CLI characterization baseline (11/11)
8602a369 WU-LPR-010 — closure receipt (CLOSED WITH FINDINGS)
4b1ec07a WU-LPR-032 — support admission table (CLOSED, no production change)
```

**Branch state:** `main` is 15 commits ahead of `origin/main`; this train
contributes 8 of those. HEAD = `4b1ec07a`. Working tree clean except for
`docs/pipeline-kotlin-local-production-ready-2026-09-18/` (left as
untracked; belongs to a separate planning pack, not part of this train).

---

## 1. WU-LPR-401 — DSL Honesty (CLOSED WITH FINDINGS @ `05565f2b`)

**Slice objectives → status:**

| Slice | Status |
|-------|--------|
| `@DslMarker` 4 layered markers (Pipeline / Stage / Step / Post) | **GREEN** |
| `waitUntil { ... }` KDoc + inline comment rewritten to match the eager-evaluation reality | **GREEN** |
| `pwd()` / `isUnix()` honest semantics | **DEFERRED → WU-LPR-402** |
| Cross-cutting block-step eager pattern (retry, timeout, dir, withCredentials, withEnv, timestamps, ansiColor, node, script) | **DOCUMENTED — DEFERRED → WU-LPR-403** |

**Evidence:** 36/36 GREEN in touched surface; 14 pre-existing arch
failures unchanged (verified via `git stash` + rerun).

## 2. WU-LPR-402 — `pwd()` / `isUnix()` honest runtime semantics (CLOSED @ `c01a54a9`)

**Slice objectives → status:**

| Slice | Status |
|-------|--------|
| Reuse-first inventory (5 registry authorities, 1 scripted façade, 1 runtime façade) | **GREEN** |
| `ScriptedStepFacade.pwd(callSite, tmp): String` | **GREEN** |
| `PipelineDsl.pwd(tmp=false)` / `pwd(tmp=true)` → registry path | **GREEN** |
| `PipelineDsl.isUnix()` → registry path | **GREEN** |
| Synchronous return is honest placeholder (`RUNTIME_VALUE_PLACEHOLDER`, `ISUNIX_PLACEHOLDER`) | **GREEN** |
| Fitness properties (7 properties, 0 filenames) | **GREEN** |
| Structured DSL runtime-return seam (`pipeline { ... }` form) | **DEFERRED → WU-LPR-402P** |

**Evidence:** 36/36 GREEN in touched surface (7 pwd lowering + 6 pwd
scripted + 13 isUnix runtime + 10 isUnix mapping + 7 fitness).

## 3. WU-LPR-010 — CLI characterization baseline (CLOSED WITH FINDINGS @ `8602a369`)

**Outcome:** 11 measurement-only tests added, all GREEN against the
real `installDist` binary. No production code changed.

**Surface × status matrix:**

| Surface | Status |
|---------|--------|
| `pipeline validate <good>` | **EXISTS** |
| `pipeline validate <bad>` (exit 1, canonical contract says exit 2) | **EXISTS** (drift F3) |
| `pipeline run <passing>` | **EXISTS** |
| `pipeline run <failing>` | **EXISTS** |
| `pipeline run` event set | **EXISTS** |
| `pipeline run --resume` (empty db) | **BROKEN** (uncaught exception, exit 1, drift F4) |
| `pipeline run --resume` (prior db) | **PARTIAL** (replay + re-execute, double RunFinished, F5) |
| `pipeline version` / `pipeline doctor` | **PARTIAL** (not real subcommands, F1) |
| `pipeline events` / `pipeline credentials` | **DEFERRED** (F2) |

**Findings F1..F6** filed for follow-up WUs (WU-LPR-011 / WU-LPR-012).

**Evidence:** `TEST-dev.rubentxu.pipeline.v2.application.cli.WULpr010CliCharacterizationTest.xml` —
11 tests, 0 failures, 0 errors.

## 4. WU-LPR-032 — Support admission table (CLOSED @ `4b1ec07a`)

**Outcome:** Every public DSL surface, every core `StepKey`, and every
external plugin `StepKey` classified into
`SUPPORTED` / `EXPERIMENTAL` / `DEFERRED` / `UNSUPPORTED` with the
canonical authority named explicitly. The counter freeze
(`LEGACY_PLUGIN_IDS = ∅`) from WU-LPR-301 is preserved.

**Highlights:**

- 14 core Steps are registry-routed and CERTIFIED.
- `core.load` is **UNSUPPORTED** — fail-closed at the registry
  admission gate (`EngineInvariantViolation`).
- `readFile` / `fileExists` are **DEFERRED** (no StepKey registered).
- `timestamps` / `ansiColor` are **EXPERIMENTAL** (marker-event
  re-emission pending).
- `whenCondition` is **EXPERIMENTAL** (body admitted but expression
  never evaluated).
- `pwd(tmp=true)` is **EXPERIMENTAL** (deterministic dir creation
  side effect documented).

Findings F7..F13 filed for follow-up WUs (WU-LPR-202..208).

---

## 5. Aggregate findings (filed for follow-up, NOT FIXED)

| ID | Finding | Suggested follow-up |
|----|---------|---------------------|
| F1 | `version` / `doctor` not real subcommands (exit 1) | WU-LPR-011 |
| F2 | `events` / `credentials` not registered as subcommands | WU-LPR-011 |
| F3 | `validate` exits 1 on compile failure (canonical: 2) | WU-LPR-011 |
| F4 | `--resume` empty db throws uncaught exception (exit 1, canonical: 2) | WU-LPR-011 |
| F5 | `--resume` emits TWO `RunFinished` bursts (replay + re-execute); canonical says handler should not re-execute | WU-LPR-011 (decide: bug vs by-design) |
| F6 | No `docs/cli.md` documenting the exit code contract | WU-LPR-012 |
| F7 | `readFile` / `fileExists` DSL funs have no registered StepKey | WU-LPR-202 / S2-A3b |
| F8 | `timestamps` / `ansiColor` marker-event re-emission not implemented | WU-LPR-203 |
| F9 | `timeout.unit` parser only accepts SECONDS / MINUTES | WU-LPR-204 |
| F10 | `whenCondition` body admitted, expression never evaluated | WU-LPR-205 |
| F11 | `pwd(tmp=true)` deterministically creates a directory (side effect) | WU-LPR-206 (doc only) |
| F12 | `archiveArtifacts.excludes` / `onlyIfSuccessful` F2-deferred | WU-LPR-207 |
| F13 | `withEnv` PATH prepend semantics not exhaustively tested | WU-LPR-208 |

DEFERRED slices from earlier WUs:

- **WU-LPR-402P** — Structured DSL runtime-return seam (`pipeline { ... }` form).
- **WU-LPR-403** — Declarative Builder Purity Audit (block-step eager-evaluation pattern).
- **WU-LPR-206** — `pwd(tmp=true)` doc; `tmp` directories persist across resume and are NOT recreated on REUSE.

---

## 6. Build / test evidence (fresh, 2026-09-18)

| WU | Test command | Result | Evidence |
|----|--------------|--------|----------|
| WU-LPR-401 | targeted class sweep | 36/36 GREEN in touched surface | `git stash` + rerun confirmed 14 pre-existing arch failures unchanged |
| WU-LPR-402 | targeted class sweep | 36/36 GREEN in touched surface | `WULpr402RuntimeHonestDslFitnessTest` 7/7 |
| WU-LPR-010 | `./gradlew -p v2 :pipeline-application:test --tests 'WULpr010CliCharacterizationTest'` | **11 tests, 0 failures, 0 errors, 0 skipped (28s)** | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.WULpr010CliCharacterizationTest.xml` |
| WU-LPR-032 | documentation only | n/a | receipt only |

---

## 7. Trunk authority — awaiting user integration decision

Per the AGENTS.md hard law (`trunk authority: cycles closed only if
integrated in trunk`), the train is **locally closed but not yet
integrated**. The 8 commits sit on top of `origin/main` (`d2fe73c6`)
and require an explicit integration step (push / PR) to count as
"closed in trunk".

**Choices for the next step (human decision, not auto-run):**

1. **Push to origin/main** — fastest, makes all 8 commits trunk.
2. **Open PR** — review-friendly, lets one more pair of eyes look at
   the receipts.
3. **Defer integration** — pause here; pick up where we left off in a
   later session.

The receipts in `docs/v2/07-uat/WU_LPR_{010,032,401,402}_RECEIPT.md`
are the canonical evidence and stay valid regardless of which option
is chosen.

---

**PAUSED — 2026-09-18 — awaiting user integration decision.**
