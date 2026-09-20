# WU-LPR-085 — `core.waitUntil` G6+G8 Burn-Down

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | **CERTIFIED** |
| Trigger | Tier A queue (`STEP_REGISTRY_PLAN.md`); WU-LPR-085 assigned to `core.waitUntil` G6+G8 burn-down. |
| Parent | `e3b0628c` (WU-LPR-084) |
| Scope | `core.waitUntil` — close G6 architecture fitness row (contract suite) and G8 final certification. |
| Outcome | `core.waitUntil` → `CERTIFIED`. **12th** certified CoreStep. |
| Initiative | INITIATIVE LPR-001 (binding umbrella, auto-run mode active). |

## 1. Diagnosis

The Tier A queue (declared in WU-LPR-082 + WU-LPR-083) named `core.waitUntil` as a Step whose contract suite existed but had not been audited end-to-end on HEAD. The investigation at the start of this WU confirmed:

- `WaitUntilStepContractSuiteTest.kt` exists with 18 tests.
- `CoreWaitUntilStepUnitTest.kt` exists with 9 tests.
- `CoreWaitUntilStep.registerInto(this)` is present in `CoreStepRegistryFactory`.
- The real fixture `v2/compatibility/22-wait-until.pipeline.kts` exists and exercises the structural `waitUntil` block through `BlockStepNode` + `BodyExecutionPolicy.RepeatUntil` + `dispatchRepeatUntilBody`.
- L1 evidence was already captured in the prior cycle (`WaitUntilStepContractSuiteTest` 18/0/0; `CoreWaitUntilStepUnitTest` 9/0/0 on HEAD `e3b0628c`).

The only missing rows in the 5-layer Strict Validation Set were:

- G7 — installed-CLI canary (real binary + real `.pipeline.kts`).
- G8 — final certification receipt + state ledger update.

This WU closed both rows. **No production code change was required**; the Step was already production-ready, and the gap was pure evidence.

## 2. Change description

**No production code change.** Artefacts added:

| Path | Purpose |
|---|---|
| `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` | G7 evidence: installed-CLI canary (fresh + replay). |
| `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md` | G8 final certification: 5-layer strict validation set verdict, counter updates, ledger update. |
| `docs/v2/07-uat/WU_LPR_085_CORE_WAITUNTIL_G6_G8.md` | This receipt. |

## 3. Verification (evidence)

### 3.1 L1 contract suite (re-verified on HEAD `e3b0628c`)

```bash
$ cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:runtime:test \
    --tests 'WaitUntilStepContractSuiteTest'
# WaitUntilStepContractSuiteTest — 18 tests, failures=0, errors=0
$ cd v2 && timeout 600 ./gradlew :pipeline-application:test \
    --tests 'CoreWaitUntilStepUnitTest'
# CoreWaitUntilStepUnitTest — 9 tests, failures=0, errors=0
```

XML canaries:

- `v2/pipeline-step-sdk/runtime/build/test-results/test/TEST-WaitUntilStepContractSuiteTest.xml` → `tests="18" failures="0" errors="0"`.
- `v2/pipeline-application/build/test-results/test/TEST-CoreWaitUntilStepUnitTest.xml` → `tests="9" failures="0" errors="0"`.

### 3.2 G7 — installed-CLI canary

See `S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` for full evidence.

- **Binary SHA-256:** `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8`.
- **Fixture SHA-256:** `7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4`.
- **Fresh run** (G7-01): EXIT 0; `WaitUntilPolled` ×2 + `WaitUntilCompleted` + `StageFinished outcome=success` + `RunFinished outcome=success`.
- **Replay run** (G7-02): EXIT 0; replay reuses the durable record (no body re-execution; only `WaitUntilCompleted + StageFinished + RunFinished`); `ReplayPolicy.MEMOIZED` confirmed.
- **Control-root durable structure:** `/tmp/waituntil-g7/ctl/wait-until-control/` present, proving durable persistence under the Step descriptor's durable key.

### 3.3 G8 — 5-layer Strict Validation Set

See `S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md` §3 for the full verdict. All 16 mandatory rows + real-DSL + installed-distribution PASS.

### 3.4 Counter updates (post-commit)

| Counter | Before WU-LPR-085 | After WU-LPR-085 |
|---|---|---|
| Certified CoreSteps | 11 | **12** (+1: `core.waitUntil`) |
| External certified Steps | 1 | 1 |
| Total certified Steps | 12 | **13** |
| Legacy executable Steps | 0 | 0 |
| Registry-primary Steps | 11 | **12** |

## 4. Acceptance checklist (AGENTS.md prime directives)

| Mandate | Status |
|---|---|
| Step Constitution (closed structure, open registry) | OK — `waitUntil { … }` is a structural DSL routed through `BlockStepNode` + `BodyExecutionPolicy.RepeatUntil` + `dispatchRepeatUntilBody` (generic body machinery). No `when(stepKey)` case. |
| Per-step observability | OK — `WaitUntilPolled` + `WaitUntilCompleted` emitted. |
| Fail-closed coverage | OK — registered in `CoreStepRegistryFactory`. |
| Strict certification law | OK — no `DONE/PASS`/`IMPLEMENTED_UNCERTIFIED`/`WIP`/`TBD`/`partial` as final state; `CERTIFIED` after full G0..G8 + 5-layer strict validation set + receipts. |
| Hexagonal architecture | OK — `pipeline-step-sdk` does not import `pipeline-application`. |
| DSL describes, not executes | OK — `waitUntil { … }` is a structural DSL. |
| Reference implementation consulted | OK — Jenkins `waitUntil` (workflow-cps global library). |
| Security implications reviewed | OK — no credentials, network, persistent filesystem mutation outside `/tmp`. |
| End-of-work-unit closure block | OK — present in both companion receipts. |

## 5. End-of-work-unit closure block

```text
Reference implementation consulted: Jenkins pipeline `waitUntil` (workflow-cps global library)
Behaviour adopted:                 predicate + body; polls until predicate returns true or deadline exceeded; durable across replay
Intentional deviations:            none
Security implications reviewed:    n/a (no credentials, network, or persistent filesystem mutation outside /tmp)
Tests demonstrating the contract:   docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md
                                    docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md
                                    v2/pipeline-step-sdk/runtime/src/test/kotlin/.../WaitUntilStepContractSuiteTest.kt
                                    v2/pipeline-application/src/test/kotlin/.../CoreWaitUntilStepUnitTest.kt
                                    v2/compatibility/22-wait-until.pipeline.kts
```

- WU-LPR-085 result: **CERTIFIED** (`core.waitUntil` → `CERTIFIED`).
- Production code change: **none**.
- Next binding step: **WU-LPR-086 (LFC-2R2 — Structured Runtime-Returning Steps)** — the horizontal blocker for `core.pwd` / `core.pwdTmp` / `core.readFile` / `core.fileExists`.

## 6. Commit + tag + push

```bash
git add docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md \
        docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md \
        docs/v2/07-uat/WU_LPR_085_CORE_WAITUNTIL_G6_G8.md

git commit -m "WU-LPR-085: cert core.waitUntil (G6+G8) — installed-CLI canary + final certification"

git tag -f wu-lpr-085
git push origin main
git push origin wu-lpr-085
```
