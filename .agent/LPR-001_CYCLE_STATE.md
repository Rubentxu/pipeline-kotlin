# LPR-001 — Cycle state ledger (Local Production Ready)

**Cycle:** `cycle/lpr-001-local-production-ready`
**Initiative:** LPR-001 (Local Production Ready + LFC-2E)
**Parent plan:** `.agent/INITIATIVE_LPR_001_COMPLETE_ROADMAP.md`
**Base:** `73dac3dc` (post LFC-2E branch integration, pre Tier A #1)
**Path:** A-lite-style burn-down per WU (each Tier A/B WU = mini-cycle with propose → spec → tasks → apply → verify → release → archive)
**Owner autonomy table:** see `.agent/INITIATIVE_LPR_001_COMPLETE_ROADMAP.md` §"Operator authorization table".

---

## Operator guard rails (verbatim from LPR-001 intake)

**Authorized within the cycle (agent acts):**

- Investigate inventory; pick a Step from the depurated list.
- Write OpenSpec change, ADR if needed, tasks, UAT, contract suite.
- Implement Steps via existing SDK (registry-primary, capability-routed).
- Fix locally-reproduced defects that don't break contracts.
- Run tests, capture evidence (SHA-256 fingerprints, XML canaries, G7 installed-CLI), commit, advance to next WU.
- Close WU, tag `wu-lpr-NNN`, integrate, archive via SDDK flow.

**Requires operator GO (must STOP and present):**

- Change a public certified semantics.
- Modify an F-tier closure contract (F1 `sh` var-scope, F-ARCH-L7 workspace-cleanup, etc).
- Add a Step-specific exception to engine or coordinator.
- Introduce remote storage, new protocols, or incompatible public API.
- Alter historical receipts, replace a published release, delete foreign work.
- Continue if the integral goal is technically infeasible inside the limits above.

**Out-of-cycle (no work in any WU):**

- Tier C (block Steps: `core.script`, `core.parallel`, `core.retry`, `core.timeout`) — gated by ADR-0073 body re-entry + ADR-0075 BodyExecutionPolicy.
- Remote artifact storage (S3/GCS/OCI/Azure).
- Worker distribution / multi-node coordination.
- New public DSL surface incompatible with the existing DSL contract.
- Re-implementing any already REGISTRY_PRIMARY Step.
- F-tier contract modifications (F1, F-ARCH-L7, F2.5).

---

## Cycle map

| Checkpoint | Tier | StepKey(s) | Status | Tag | Commit |
|---|---|---|---|---|---|
| WU-LPR-085 | A | `core.echo` | ✅ CERTIFIED | `wu-lpr-085` | (sealed) |
| WU-LPR-086 | A | `core.error` | ✅ CERTIFIED | `wu-lpr-086` | (sealed) |
| WU-LPR-087 | A | `core.archiveArtifacts` + `core.artifactQuery` | ✅ CERTIFIED | `wu-lpr-087` | (sealed) |
| WU-LPR-088 | A | `core.pwd.tmp` | ✅ CERTIFIED | `wu-lpr-088` | `3c7c1bd3` |
| **WU-LPR-089** | **B** | **`core.stash` + `core.unstash`** | ✅ **CERTIFIED** | `wu-lpr-089` (published in closure commit) | Phase A `d3856fa0` + Phase B `58b806cb` + closure fix (test + receipt) |
| **WU-LPR-090** | **B** | **`core.publishHTML`** | ✅ **CERTIFIED** (Phase D pending) | `wu-lpr-090` (pending) | Phase A `8dd59eba` + Phase B `75232c32` + Phase C `7a974e15` + Phase D `<pending>` |
| WU-LPR-091 | B | `core.lock` | pending (auto-run resume) | TBD | TBD |
| WU-LPR-092 | B | `core.input` | pending | TBD | TBD |
| WU-LPR-093 | B | `core.httpRequest` | pending | TBD | TBD |
| WU-LPR-094 | B | TBD (`core.stage` / `core.node` / `core.catchError`) | pending | TBD | TBD |

**Tier B numbering revised WU-LPR-089 phase-a**: real-inventory scan discovered `core.junit.results` already CERTIFIED as external plugin (ServiceLoader), reducing Tier B from 7 to 6 WUs.

---

## State transitions (binding ledger)

```text
[committed 2026-09-20] WU-LPR-085  core.echo               → CERTIFIED, tag wu-lpr-085
[committed 2026-09-20] WU-LPR-086  core.error              → CERTIFIED, tag wu-lpr-086
[committed 2026-09-20] WU-LPR-087  core.archiveArtifacts + core.artifactQuery → CERTIFIED, tag wu-lpr-087
[committed 2026-09-20] WU-LPR-088  core.pwd.tmp            → CERTIFIED, tag wu-lpr-088 at 3c7c1bd3
[committed 2026-09-20] WU-LPR-089  core.stash + core.unstash
  [phase-a 2026-09-20] d3856fa0 → StashOperations capability + adapter + Steps + 3 domain events + encode
  [phase-b 2026-09-20] 58b806cb → G6 contract suite + G7 canary + JsonEventLog.decode fix + Main.kt flush
  [closure] tag wu-lpr-089
[committed 2026-09-21] WU-LPR-090  core.publishHTML (Tier B #2)
  [phase-a 2026-09-21] 8dd59eba → PublishHtmlOperations capability + adapter + Step + 3 new domain events (48→51) + encode
  [phase-b 2026-09-21] 75232c32 → G6/G7 contract suite (13 rows: identity, contract, codecs 3/3b/4/5/5b, envelope, registry, capability, success, typed-failure, missing-capability, observability, arch-fit)
  [phase-c 2026-09-21] 7a974e15 → compatibility/32-publish-html.pipeline.kts + 4 corpus count bumps (30→31)
  [phase-d 2026-09-21] <pending> → JsonEventLog.extractJsonArray bug fix (bracketDepth=1) + closure receipt
  [closure] tag wu-lpr-090 (pending after Phase D commit)
[next]        WU-LPR-091  core.lock (Tier B #3, auto-run resume)
[pending]     WU-LPR-092  core.input
[pending]     WU-LPR-093  core.httpRequest
[pending]     WU-LPR-094  TBD (core.stage / core.node / core.catchError)
[out-of-cycle, Tier C] core.script, core.parallel (ADR-0073), core.retry / core.timeout (ADR-0075)
```

---

## Counters (project dashboard / roadmap)

| Metric | Pre-cycle | Current (HEAD `<phase-D-pending>`) | Target (LPR-001 close) |
|---|---|---|---|
| CERTIFIED core Steps | 0 | **20** | 19 + 5 (Tier B remaining) = 24 |
| CERTIFIED external plugins | 0 | 1 (`junit.results`) | 1 |
| Total CERTIFIED | 0 | **21** | 25 |
| Registry-primary Steps | 0 | 20 (= 18 + 2 stash/unstash + 1 publishHTML -1 removed via legacy_counted-out, actually: registry-primary = CERTIFIED because no legacy has existed since WU-LPR-087 transition) | 25 |
| Legacy executable Steps | 0 | 0 | 0 |
| Tier B closed | 0 | 2 (LPR-089 + LPR-090) | 6 |
| `DomainEvent` variants | 0 | 51 | +5 (Tier B) |

---

## Testing ladder policy (AGENTS.md rule 17)

- **L0 compile** (`pipeline-{application,domain,events,step-sdk,architecture-tests,scripting-kotlin24}/compileTestKotlin`) after each task batch.
- **L1 individual test or class** after each production edit (e.g. `CoreStashStepContractSuiteTest`).
- **L2 owning class batch** at end of each WU (e.g. `:pipeline-application:test --tests 'UatLocal005*' --tests 'UatCompat001*' --tests 'CompatibilityCorpusTest'`).
- **L4 module suites** at end of each WU if module touched (`:pipeline-events:test`, `:pipeline-architecture-tests:test`).
- **L5 `./gradlew -p v2 check`** ONLY at end of WU batch / Tier close / release / pre-merge gate.

---

## Pre-cycle inventory anchor (sha256)

```text
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
  → sha256 = TBD (rolling on each WU commit; see git blame)
v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt
  → sha256 = TBD (45 → 48 variants after WU-LPR-089 phase-a)
```

---

## OPEN DECISIONS that would require operator GO

None at session-end (2026-09-20T23:38Z). All four architectural forks identified in the operator table are NOT in play for WU-LPR-089 closure or WU-LPR-090 start:

1. Public certified semantics — no change proposed; WU-LPR-089 introduces new StepKey (`core.stash` / `core.unstash`), no public contract modifications.
2. F-tier closures — F1 (`sh` var-scope), F-ARCH-L7 (workspace-cleanup), F2.5 (StepSpec decoupling) all respected. WU-LPR-089 explicitly chose sibling storage (`<controlRoot>/stashes/<runId>/<name>/`) to preserve F-ARCH-L7.
3. Engine/coordinator exception — none added; both `core.stash` and `core.unstash` flow through registry.
4. Remote storage / new protocols — none introduced.

---

## Reference

- Initiative umbrella: `.agent/INITIATIVE_LPR_001_COMPLETE_ROADMAP.md`
- Current TESTING-STATE (with WU queue): `.agent/TESTING-STATE.md`
- This WU handoff: `.agent/HANDOFF-WU-LPR-089.md`
- Previous cycle ledger (E1 junit + artifact-query): `.agent/E1_CYCLE_STATE.md`
- Roadmap: `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md` (Tier B/C mapping)
- Step ecosystem matrix: `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md`
- Step inventory source of truth: `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`
- Step registry plan: `docs/v2/01-product/STEP_REGISTRY_PLAN.md`
- Local foundation consolidation: `docs/v2/00-context/LOCAL_FOUNDATION_CONSOLIDATION.md`

---

## Session-end mark — 2026-09-21T09:06Z

**Cycle state at close:**
- Tier A: 5/5 WUs CERTIFIED (last `wu-lpr-088` at `3c7c1bd3`).
- Tier B: 2/6 WUs CERTIFIED (`wu-lpr-089` closed; `wu-lpr-090` Phase D pending = bug fix + receipt + state, no tag yet).
- Tier C: out-of-cycle, gated by ADR-0073/0075.
- HEAD: `7a974e15` on `main`, in sync with `origin/main`; Phase D commit (bug fix + receipt + state) uncommitted, will be a single Phase D commit before tagging `wu-lpr-090`.

**Last full round-gate run:** Phase D L2 regression batch
(`CoreStashStepContractSuiteTest` + `UatCompat001*` + `UatLocal005CorpusUntouchedTest` +
`CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable`) → 16 tests,
0 failures, 0 errors. Plus installed-CLI G7 canary fresh + --rerun →
exit 0 + `entries` populated correctly after `JsonEventLog.extractJsonArray`
fix (root cause: `bracketDepth` initial value 0 → -1 on `]`; fix: 1).

**Resume command for next session (auto-run, no human gate):**
```bash
# Step 1: WU-LPR-090 Phase D commit + tag + push
git status  # confirm bug fix + receipt + state files are staged
git commit -m "Phase D: JsonEventLog.extractJsonArray bug fix + WU-LPR-090 closure"
git tag -a wu-lpr-090 -m "WU-LPR-090 CERTIFIED: core.publishHTML (Tier B #2)"
git push origin main --tags

# Step 2: start WU-LPR-091 (core.lock, Tier B #3)
# Load handoff: cat .agent/HANDOFF-WU-LPR-091.md (to be created)
```

**Auto-run policy at session-end:** ON. Future human_gates pre-approved (per user directive 2026-09-20T18:22Z). Blockers go to diagnose-and-fix, NEVER quarantine.
