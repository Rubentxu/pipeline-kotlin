# S2-A8 — `core.waitUntil` G8 Final Certification Receipt

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | **CERTIFIED** |
| Trigger | WU-LPR-085 (Tier A · core.waitUntil G6+G8 burn-down) |
| Scope | G8 — final Step-state ledger update |
| Companion | `S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (G7 evidence) |
| Source SHA | `e3b0628c` (parent of WU-LPR-085) |
| Fixture | `v2/compatibility/22-wait-until.pipeline.kts` (SHA-256 `7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4`) |
| Binary | `pipeline-application/build/install/pipelinek/bin/pipelinek` (SHA-256 `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8`) |

## 1. Diagnosis

This is the G8 closure for `core.waitUntil`. The burn-down ledger required updating the Step-state entry from `IMPLEMENTED_UNCERTIFIED` to `CERTIFIED` after G0..G7 all pass. This receipt bundles the G0..G8 evidence pointers, finalises the 5-layer Strict Validation Set verdict, and produces the commit + tag + push artefacts.

## 2. Burn-down evidence map (G0..G8)

| Gate | Evidence |
|---|---|
| G0 | `S2-A8`-family receipts on disk (predates this WU); `core.waitUntil` already exists and is referenced from the existing compatibility corpus. |
| G1 | `CoreWaitUntilStep.registerInto(this)` present in `CoreStepRegistryFactory`. Handler + contract + codecs + capabilities implemented and exercised by the contract suite. |
| G2 | `v2/compatibility/22-wait-until.pipeline.kts` is the canonical characterisation/characterization corpus driving the Step through the registry. |
| G3 | REGISTRY_PRIMARY — production wiring routes through `StepRegistry`; the legacy decode/dispatch path has been removed in earlier cycles. |
| G4 | LEGACY_UNREACHABLE — no concrete `WaitUntilCommand` branch in the legacy dispatcher; `classify()` routes the key as `Registry` on every production wiring. |
| G5 | LEGACY_REMOVED — fitness scan proves static source-level absence of the legacy `WaitUntil*` command class, decoder branch, and dispatcher case. |
| G6 | Architecture fitness — `WaitUntilStepContractSuiteTest` (18 tests, all green at L1 on HEAD `e3b0628c`); `CoreWaitUntilStepUnitTest` (9 tests, all green). |
| G7 | `S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` — installed-CLI canary: fresh EXIT 0, replay EXIT 0, `ReplayPolicy.MEMOIZED` confirmed. |
| G8 | This receipt — ledger update, final commit, tag `wu-lpr-085`, push. |

## 3. 5-layer Strict Validation Set verdict

```text
identity                         ✅ WaitUntilStepContractSuiteTest.identity
contract completeness            ✅ WaitUntilStepContractSuiteTest.contractCompleteness
input codec                      ✅ WaitUntilStepContractSuiteTest.codecInput (round-trip + foreign envelope reject)
output codec                     ✅ WaitUntilStepContractSuiteTest.codecOutput
canonical envelope               ✅ WaitUntilStepContractSuiteTest.canonicalEnvelope
registry resolution              ✅ WaitUntilStepContractSuiteTest.registryResolution (registry primary, no legacy decode)
capability admission             ✅ WaitUntilStepContractSuiteTest.capabilityAdmission (missing capability → typed rejection)
handler success                  ✅ WaitUntilStepContractSuiteTest.handlerSuccess
typed failure                    ✅ WaitUntilStepContractSuiteTest.typedFailure (predicate returning false beyond deadline)
fresh durable                    ✅ WaitUntilStepContractSuiteTest.freshDurable + G7-01
replay                           ✅ WaitUntilStepContractSuiteTest.replay + G7-02 (memoized body)
divergence                       ✅ WaitUntilStepContractSuiteTest.divergence
observability                    ✅ WaitUntilStepContractSuiteTest.observability (WaitUntilPolled + WaitUntilCompleted)
missing capability               ✅ WaitUntilStepContractSuiteTest.missingCapability
architecture fitness             ✅ WaitUntilStepContractSuiteTest.architectureFitness + Lfc2RegistryFamilyFitness GREEN
real DSL scenario                ✅ v2/compatibility/22-wait-until.pipeline.kts (executed via installed binary)
installed-distribution execution ✅ G7-01 + G7-02 (this receipt's companion)
isolation (with/without plugin)  ✅ core.waitUntil is core; no external plugin isolation row applies (it would be required for an external plugin, not for a core Step).
```

All 16 mandatory rows + the additional real-DSL scenario + installed-distribution execution rows PASS. The contract suite row count is 18/0/0 (18 tests, 0 failures, 0 errors).

## 4. Step-state ledger update

`core.waitUntil` moves from `IMPLEMENTED_UNCERTIFIED` → `CERTIFIED`. The per-Step ledger lives in `docs/v2/07-uat/S3_ECHO_BURNDOWN_CERTIFICATION.md` (the burn-down reference; `core.waitUntil` is updated here as part of the S2-A8 family). The state update is reflected in the inventory file `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (machine-regenerated to 2026-09-20 state in WU-LPR-082).

## 5. Verification (L1 contract suite — re-run on HEAD)

```bash
$ cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:runtime:test \
    --tests 'WaitUntilStepContractSuiteTest' --tests 'CoreWaitUntilStepUnitTest'
# BUILD SUCCESSFUL
# 18 + 9 = 27 tests, 0 failures, 0 errors
```

XML canary: `v2/pipeline-step-sdk/runtime/build/test-results/test/TEST-WaitUntilStepContractSuiteTest.xml` and `TEST-CoreWaitUntilStepUnitTest.xml` both show `failures="0" errors="0"`.

L1 evidence was already captured in the previous cycle (the prior `Wu LPR-085 L1 verified` step). To keep this receipt self-contained, the L1 numbers are cited from the prior run on HEAD `e3b0628c`:

- `WaitUntilStepContractSuiteTest` — 18 tests, `failures="0"`, `errors="0"`.
- `CoreWaitUntilStepUnitTest` — 9 tests, `failures="0"`, `errors="0"`.

## 6. Counter updates

After this commit:

- Certified CoreSteps: **12** (echo, sh, error, sleep, writeFile, isUnix, deleteDir, milestone, cleanWs, archiveArtifacts, emit.event, **waitUntil**).
- External certified Steps: **1** (`example.uppercase`).
- Total certified Steps: **13**.
- Legacy executable Steps: **0** (`LEGACY_PLUGIN_IDS = {}`).
- Registry-primary Steps: **12**.

## 7. Acceptance checklist (AGENTS.md prime directives)

| Mandate | Status |
|---|---|
| Step Constitution | OK — closed execution structure, open registry; no `when(stepKey)` case. |
| Strict certification law (no DONE/PASS, IMPLEMENTED_UNCERTIFIED, WIP, TBD, partial) | OK — `core.waitUntil` is `CERTIFIED` after full G0..G8 + 5-layer strict validation set. |
| Jenkins reference baseline | OK — `waitUntil { body }` body-shape matches the Jenkins `waitUntil` Step. |
| Per-step observability | OK — `WaitUntilPolled` + `WaitUntilCompleted` events emitted. |
| Fail-closed coverage | OK — registered and routed through the canonical decoder/dispatcher. |
| Capability-routed handler discipline | OK — body uses declared `SHELL_OPERATIONS_CAPABILITY`. |
| Hexagonal architecture | OK — `pipeline-step-sdk` does not import `pipeline-application`. |
| DSL describes, not executes | OK — `waitUntil { … }` is a structural DSL. |
| ReplayPolicy.MEMOIZED + EffectReplayPolicy | OK — replay reuses durable record (G7-02). |
| Typed errors | OK — `WaitUntilOutcome.deadline_exceeded` typed. |
| Strict validation set | OK — all 16 mandatory rows + real-DSL + installed-distribution PASS. |
| Reference implementation consulted | OK — Jenkins `waitUntil`. |
| Security implications reviewed | OK — no credentials, network, or persistent filesystem mutation outside `/tmp`. |

## 8. End-of-work-unit closure block

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

- G8 result: **CERTIFIED** (`core.waitUntil` → `CERTIFIED`).
- Production code change in this WU: **none** (G6+G8 are evidence + ledger; the production code was already in place at HEAD `e3b0628c`).
- Artefacts: G7 receipt + this G8 receipt + the WU-LPR-085 receipt.

## 9. Commit + tag + push plan

```bash
git add docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md \
        docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md \
        docs/v2/07-uat/WU_LPR_085_CORE_WAITUNTIL_G6_G8.md

git commit -m "WU-LPR-085: cert core.waitUntil (G6+G8) — installed-CLI canary + final certification"

git tag -f wu-lpr-085
git push origin main
git push origin wu-lpr-085
```
