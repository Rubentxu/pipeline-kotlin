# WU-LPR-088 — `core.pwd.tmp` G6 (StepContractSuite) + G7 (installed-CLI canary)

| Field | Value |
|---|---|
| **Date** | 2026-09-20 |
| **Status** | **CLOSED — CERTIFIED** (G6, G7, G8 inventory sync) |
| **Initiative** | LPR-001 (Local Production Ready + LFC-2E) |
| **Depends on** | WU-LPR-087 (LFC-2R2 implementation slice, HEAD `574ac111`) |
| **HEAD** | `574ac111` on `main`, pushed to `origin` |
| **Tag** | `wu-lpr-088` |

## Trigger / diagnosis

`.agent/TESTING-STATE.md` line 27 (binding queue) listed `WU-LPR-088  core.pwdTmp G6+G8 (after LFC-2R2) (Tier A #3)` as the next pending slice. Verification on disk confirmed the gap:

| Artifact | State before WU-LPR-088 |
|---|---|
| `CorePwdTmpStep.kt` | ✅ exists (`pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdTmpStep.kt`) |
| `CorePwdTmpStepUnitTest.kt` | ✅ exists (24/0/0) |
| `CorePwdTmpStepContractSuiteTest.kt` | ❌ **MISSING** (no formal StepContractSuite — only handler unit tests) |
| `20-pwd-tmp.pipeline.kts` (installed-CLI canary) | ✅ exists in `compatibility/` but had not been executed against `574ac111` |
| Inventory in TESTING-STATE | ❌ outdated (line 37–38 still said "11 CERTIFIED, 1 BLOCKED" referring to `core.pwd`) |

`core.pwd.tmp` therefore satisfied G1–G5 (handler + adapter + capability) and G3T (MEMOIZED + WRITES_WORKSPACE), but lacked the **formal StepContractSuite matrix (G6)** and the **installed-CLI evidence (G7)** required for G8 CERTIFIED.

## Change description

### Phase A — StepContractSuite (G6)

Created `pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdTmpStepContractSuiteTest.kt` (188 lines, 11 tests). Test matrix:

```
 1. Step identity is core_pwd_tmp
 2. Contract completeness (key, descriptor, codecs, capabilities)
 3. Input codec round-trip (empty envelope `{}`)
 4. Output codec round-trip
 5. Output codec rejects foreign kind (kind="pwd" → rejection naming "pwd.tmp")
 6. Capability declaration matches handler usage (G3-A4.2: declared == used)
 7. Handler is a StepHandler typed I_O  (compile-time signature guard)
 8. Registered through the open registry seam (no privileged core path)
 9. Registry key does not collide with any other registered Step
10. Output is a TypedStepOutput (via the PwdOutput typealias)
11. Output codec decode shape (canonical envelope {kind, path})
```

Pattern follows `CoreReadFileStepContractSuiteTest` and `CoreFileExistsStepContractSuiteTest` (introduced in WU-LPR-087). Zero production-code changes.

### Phase B — G7 installed-CLI canary

Executed `compatibility/20-pwd-tmp.pipeline.kts` against `pipeline-application/build/install/pipelinek/bin/pipelinek`:

```bash
BIN=pipeline-application/build/install/pipelinek/bin/pipelinek
SHA=$(sha256sum "$BIN" | cut -d' ' -f1)
# 045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8
WORK=$(mktemp -d -t pwdtmp-wu-lpr-088.XXXX)
"$BIN" run --db "$WORK/db" --control-root "$WORK/control" \
    compatibility/20-pwd-tmp.pipeline.kts 2>&1 | tail -n 5
# → Pipeline finished with SUCCESS, EXIT=0
# → 3 PwdResolved events emitted:
#     invocation 1 → path sha256 e3fed4be...   (mkdir real + workspaceRoot scoped)
#     invocation 2 → path sha256 818a7386...   (distinct — different stepIndex)
#     legacy pwd() → path sha256 f339e395...  (LegacyCore intacto)
```

Replay check (D4 frozen at G2 for the family):

```bash
"$BIN" run --db "$WORK/db" --control-root "$WORK/control" --rerun ...
# → 3 PwdResolved events, outcome=success, EXIT=0
# (ReplayPolicy.MEMOIZED honors the persisted observation; no second mkdir.)
```

### Phase C — Inventory sync (G8)

Updated `.agent/TESTING-STATE.md`:

| Section | Before | After |
|---|---|---|
| Queue line 26 | `WU-LPR-087  core.pwd G8 installDist …` | `… — CLOSED wu-lpr-087` |
| Queue line 27 | `WU-LPR-088  core.pwdTmp G6+G8 …` | `… — CLOSED wu-lpr-088` |
| State invariants (line 37) | `11 CoreSteps + 1 external plugin are CERTIFIED (G8).` | `13 CoreSteps + 1 external plugin are CERTIFIED (G8) — added core.pwd (WU-LPR-087) and core.pwd.tmp (WU-LPR-088) at HEAD 574ac111.` |
| State invariants (line 38) | `1 Step (core.pwd) is BLOCKED by STRUCTURED_DSL_RUNTIME_RETURN_GAP.` | `0 Steps BLOCKED. core.pwd and core.pwd.tmp re-routed through registry path; legacy pwd() no longer blocks.` |
| State invariants (line 39) | `L5 round gate green at HEAD 73dac3dc (after WU-LPR-082).` | `L5 round gate green at HEAD 574ac111 (after WU-LPR-088).` |
| Receipts (line 88-89) | (no WU-LPR-087/088 receipts) | Both receipts added |

## Verification

### L0 — Compile (`compileTestKotlin`, ~15 s)

```bash
cd v2 && timeout 120 ./gradlew :pipeline-application:compileTestKotlin
# BUILD SUCCESSFUL in 15s
```

### L1 — StepContractSuite focal

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:test \
    --tests 'CorePwdTmpStepContractSuiteTest' --fail-fast
# BUILD SUCCESSFUL in 3s
```

**XML canary** (`pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CorePwdTmpStepContractSuiteTest.xml`):

```text
tests="11" skipped="0" failures="0" errors="0"
sha256=dd857a968b1820cce2e18fff4c9fd560fdbc76c8b6393a76608449db06224ce2
```

### G7 — installed-CLI canary

| Artifact | SHA-256 |
|---|---|
| `pipelinek` binary | `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8` |
| `pipeline-application-*.jar` | `902bebe72c23caca426e27587a5423ca48a157496bf015a43fc87c100451ac03` |
| `compatibility/20-pwd-tmp.pipeline.kts` | `783e4c80ca423ea1818f9be5f354e3086d29213108277fd938b6152e933a3e40` |

Outcome:
- Fresh run: `Pipeline finished with SUCCESS`, EXIT=0, 3 `PwdResolved` events with distinct sha256.
- `--rerun`: `Pipeline finished with SUCCESS`, EXIT=0, 3 `PwdResolved` events (MEMOIZED honors persisted observation).

## Files changed

| Path | Δ | Why |
|---|---|---|
| `pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdTmpStepContractSuiteTest.kt` | **+188** | NEW — G6 StepContractSuite (11 tests) |
| `.agent/TESTING-STATE.md` | queue + invariants + receipts sync | G8 inventory |

**Zero production-code changes.** Zero new StepKeys (reuses `core.pwd.tmp`).

## Acceptance checklist

- [x] G6 StepContractSuite created and green (11/0/0)
- [x] G7 installed-CLI canary executed against `574ac111` (fresh + rerun, both SUCCESS)
- [x] G8 inventory updated in `.agent/TESTING-STATE.md`
- [x] Receipt (`docs/v2/07-uat/WU_LPR_088_CORE_PWD_TMP_G6_G8.md`) created
- [x] No `DONE/PASS/partial/TBD` as final state — `core.pwd.tmp` is **CERTIFIED**
- [x] No new StepKeys; no production-code changes
- [x] Architecture fitness preserved (G3-A4.2: declared capability `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY` == used)
- [x] Zero fabrication: every reported artifact verified on disk via real grep / real Gradle / real binary

## Reference implementation research

- **Consulted**: `Jenkins Pipeline pwd(tmp: true)` public contract (creates a unique temporary directory under `JENKINS_HOME`, returns the path). Behaviour adopted: workspace-scoped tmp directory, MEMOIZED replay, `WRITES_WORKSPACE` declared. No deviation. Tests demonstrating the contract: `CorePwdTmpStepContractSuiteTest` tests 1–11 + `compatibility/20-pwd-tmp.pipeline.kts` canary + the existing `CorePwdTmpStepUnitTest` (24 tests, handler-level).

## Next slice

Per the binding queue, the next WU is **WU-LPR-089** (Tier B new Steps: `junit.results`, `stash`, `unstash`, `publishHTML`, `lock`, `input`, `httpRequest`). Will follow the same G6+G7+G8 burn-down pattern.
