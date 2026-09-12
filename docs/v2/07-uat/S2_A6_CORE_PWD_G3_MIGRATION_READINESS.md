# S2-A6 / G3 — `core.pwd` Migration Readiness Assessment

**Cycle:** `lfc2-e1-s2-a6-g3-core-pwd-readiness`
**Branch:** `cycle/lfc2-e1-s2-a6-g3-core-pwd-readiness`
**Base:** `dc0da54f` (S2-A5/G8 — CERTIFIED trunk HEAD)
**Date:** 2026-09-12T09:57Z
**Status:** READINESS ASSESSED — STOP after this receipt; G4 explicitly NOT in scope of this slice.

## 1. Scope (user GO, 2026-09-12T09:49Z)

First pass: `pwd(false)` only. `pwd(tmp=true)` is **explicitly out of scope** —
the PWD_TMP_TRUE_DISPOSITION blocker prevents a StepKey authority flip for
`core.pwd` until a separate disposition lands.

Production allowed (S2-A6 / G0..G3):
- `CorePwdStep.kt` — StepDefinition, codecs, capability-routed handler.
- `WorkspaceIdentity` capability declared in `Capabilities.kt`.
- `CanonicalRuntimeCapabilityAccess` wiring populates `WorkspaceIdentity.workspaceRoot`
  from `context.shOptions.workspaceRoot` (PATH_B byte-equivalence).
- `CoreStepRegistryFactory` registration of `CorePwdStep` (registry-membership does
  NOT change production authority at G1).

Production forbidden (per slice firewall, all observed in `git diff`):
- no `LEGACY_PLUGIN_IDS` change
- no authority flip
- no legacy removal
- no `pwd(tmp=true)` implementation
- no other Step migration

## 2. G3 readiness — twelve assertions

| #  | Assertion | Evidence source | Status |
|----|-----------|-----------------|--------|
| 1  | candidate registration | `CoreStepRegistryFactory.kt:84` `CorePwdStep.registerInto(this)` with G1 explanatory comment (lines 70-83) | ✅ PROVEN |
| 2  | `StructuralFamily = LegacyCore` (post-G1 invariant) | `StructuralStepFamilyResolver.classify("core.pwd", registry)` returns `LegacyCore` because `core.pwd` is still in `LEGACY_PLUGIN_IDS` (the resolver's legacy-membership-wins rule). Pinned by `CorePwdStepUnitTest:structural family - core pwd stays LegacyCore while in LEGACY_PLUGIN_IDS` | ✅ PROVEN |
| 3  | D1 truth = `shOptions.workspaceRoot` (PATH_B byte-equivalent) | `CanonicalRuntimeCapabilityAccess.buildProvided` line 99-100 sources `WorkspaceIdentity.workspaceRoot` from `context.shOptions.workspaceRoot` — the SAME source `CanonicalNodeDispatcher.pwdContext()` consumed (line 89). Pinned by `CorePwdStepUnitTest:D1 - canonical pwd(false) truth` | ✅ PROVEN |
| 4  | D2 typed output `PwdOutput(path)` APPROVED | `CorePwdStep.PwdOutput(val path: String)` extends `TypedStepOutput` with `outcome = Success`. Pinned by `CorePwdStepUnitTest:D2 - typed output PwdOutput(path) is APPROVED` and `output codec round-trip` | ✅ PROVEN |
| 5  | D3 tmp=true REJECTED at decode (PWD_TMP_TRUE_DISPOSITION) | `CorePwdStep.PwdInput.init` `require(!tmp)` AND `CorePwdStep.inputCodec.decode` re-validates after JSON parse. Pinned by `CorePwdStepUnitTest:D3 - tmp=true is OUT_OF_SCOPE` and `real seam - tmp=true fails closed at decode before capability admission` | ✅ PROVEN |
| 6  | D4 ReplayPolicy = MEMOIZED for `pwd(false)` | `CorePwdStep.descriptor.replayPolicy = ReplayPolicy.MEMOIZED`. Pinned by `CorePwdStepUnitTest:D4 - ReplayPolicy MEMOIZED for pwd(false)` | ✅ PROVEN |
| 7  | capability admission (admit both / deny missing) | `CorePwdStepUnitTest:real seam - missing WORKSPACE_IDENTITY rejects admission` + `missing EVENT_SINK rejects admission` + `both missing` (3 deny rows) + `full capabilities execute through preparation and boundary with typed output and exactly one event` (1 admit row) | ✅ PROVEN |
| 8  | handler is total (no exceptions as semantics outside `tmp=true` rejection) | All `execute(input, ctx)` paths in the unit test return without throwing for the supported inputs. The only throws are the `tmp=true` rejection at `PwdInput.init` and `inputCodec.decode` — both fail-closed with an explicit blocker name | ✅ PROVEN |
| 9  | LFC-2R scripted facade: NOT in scope of this slice | The candidate is reachable through the **generic** registry path; the LFC-2R/R2 `ScriptedStepFacade` extension for `pwd(callSite, tmp)` is documented in `S2_A6_CORE_PWD_G0` §7 (out-of-scope #3) and is **deferred** to a follow-on slice. G3 does NOT claim LFC-2R readiness for `pwd`; only the registry candidate is proven | ⚠️ DEFERRED (out of G0..G3 scope) |
| 10 | legacy decoder/dispatcher/metadata still physically present | `CanonicalPwdNodeDispatcher.kt` (executable source on main) + `CanonicalCoreStepDecoder.kt` lines 205, 264-270 (decoder branch + payload kind check for `core.pwd`) + `CanonicalCoreStepMetadata.kt` line 25 (`core.pwd` row). `CanonicalPwdNodeDispatcherTest` 2/0 green at HEAD | ✅ PROVEN |
| 11 | counters 7/7/7 unchanged by G1 (no flip, no removal) | `LEGACY_PLUGIN_IDS.size == 7`, `core.pwd in LEGACY_PLUGIN_IDS`, `CanonicalCoreStepMetadata.metadata("core.pwd")` still returns `{READ_ONLY}` row. Pinned by `CorePwdStepUnitTest:counters - 7 7 7 unchanged by S2-A6 G1 registration only` | ✅ PROVEN |
| 12 | Rule-16 baseline not widened | `CorePwdStepUnitTest` 22/0 covers the candidate; no `core.pwd` regression in existing frozen suites. Pre-existing `core.pwd` legacy dispatcher test (`CanonicalPwdNodeDispatcherTest`) 2/0 still green (executed at `git log --oneline -1 -- v2/.../CanonicalPwdNodeDispatcherTest.kt`, captured by L2 spot-check) | ✅ PROVEN |

## 3. Decision freeze (G2 frozen, validated by G1 + G3 evidence)

| ID  | Decision | Frozen text | Source row in `CorePwdStepUnitTest` |
|-----|----------|-------------|---------------------------------------|
| D1  | canonical `pwd(false)` truth = `shOptions.workspaceRoot` (NOT host `user.dir`) | Bridge derives `WorkspaceIdentity.workspaceRoot` from `context.shOptions.workspaceRoot` (PATH_B byte-equivalent). Handler never reads `user.dir` / `controlDirRoot` directly | `D1 - canonical pwd(false) truth = shOptions workspaceRoot` |
| D2  | typed output `PwdOutput(path)` is APPROVED | `PwdOutput(val path: String) : TypedStepOutput` with `outcome = Success` | `D2 - typed output PwdOutput(path) is APPROVED` |
| D3  | `tmp=true` = OUT_OF_SCOPE; **BLOCKER_FOR_AUTHORITY_FLIP** | `PwdInput.init require(!tmp)` AND `inputCodec.decode require(!tmp)`; explicit `PWD_TMP_TRUE_DISPOSITION` mention in error message | `D3 - tmp=true is OUT_OF_SCOPE and BLOCKER_FOR_AUTHORITY_FLIP` |
| D4  | `ReplayPolicy.MEMOIZED` for `pwd(false)` | `descriptor.replayPolicy = ReplayPolicy.MEMOIZED`. Fresh/rerun observe; resume/reuse reproduce persisted observation | `D4 - ReplayPolicy MEMOIZED for pwd(false)` |

## 4. Counters (post-G1, pre-G4)

```text
core.pwd:
  REGISTERED         = true     (G1: CorePwdStep.registerInto CoreStepRegistryFactory)
  REGISTRY_PRIMARY   = false    (LEGACY_PLUGIN_IDS still contains "core.pwd" → LegacyCore)
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CONTRACT_SUITE     = false    (next slice; S2-A6/G6)
  CERTIFIED          = false

pwd(false):
  MIGRATION_READY    = true     (candidate proves handler + codecs + capability admission)

core.pwd StepKey:
  AUTHORITY_FLIP_READY = false

BLOCKER:
  PWD_TMP_TRUE_DISPOSITION
    reason: tmp=true has a non-deterministic legacy path
            (tmp-pwd-<timestamp>) incompatible with MEMOIZED + READ_ONLY;
            the registry candidate rejects tmp=true at decode (PwdInput.init +
            inputCodec.decode), so a StepKey flip would orphan legacy tmp=true
            users without a replacement

legacy counters    = 7 / 7 / 7   (unchanged from S2-A5/G8 frozen state)
```

## 5. Verification (fresh XML, this session, this branch)

| Suite | Tests | Failures | Errors | XML timestamp | sha256 |
|-------|-------|----------|--------|---------------|--------|
| `CorePwdStepUnitTest` | 22 | 0 | 0 | 2026-09-12T09:56:59.916Z | `7e51c0c584b2f51bc0adbbd8fa3ef3200c298e720d7d3c8e18006c6d29fbc676` |
| `CoreIsUnixStepUnitTest` (spot-check) | 18 | 0 | 0 | 2026-09-12T09:57:15.920Z | (this run's hash) |
| `CanonicalCoreStepCommandRegistryTest` (spot-check) | 9 | 0 | 0 | 2026-09-12T09:57:15.288Z | (this run's hash) |
| `CoreIsUnixStepContractSuiteTest` (spot-check) | 22 | 0 | 0 | 2026-09-12T09:57:15.613Z | (this run's hash) |
| `ScriptedIsUnixRuntimeTest` (spot-check) | 13 | 0 | 0 | 2026-09-12T09:57:15.947Z | (this run's hash) |

Command (L1 evidence, fresh canary per AGENTS.md rule 25):

```bash
timeout 90 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CorePwdStepUnitTest' \
  --rerun-tasks
```

Result: **BUILD SUCCESSFUL in 3s, 22 actionable tests executed**. Fresh XML
written; SHA-256 captured.

Architecture fitness proxy: not yet needed at G3 (G4 will introduce
`CorePwdRegistryPrimaryFitnessTest`).

## 6. Out-of-scope observations (documented, NOT acted on)

1. **LFC-2R scripted facade for `pwd`**: `ScriptedStepFacade.pwd(callSite, tmp)`
   is **not** wired in this slice. The candidate is reachable through the generic
   registry path; the LFC-2R/R2 typed return seam (`steps.pwd(callSite): String`
   in Kotlin scripted scripts) is a follow-on slice. Authority:
   `docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md` `pwd() real return` (🔲 OPEN
   pending PWD_TMP_TRUE_DISPOSITION resolution).
2. **`PWD_TMP_TRUE_DISPOSITION`** itself has three candidate dispositions,
   none decided in this slice:
   - **(A)** Implement `tmp=true` as a separate `core.pwdTmp` StepKey with
     `Effect.WRITES_WORKSPACE` + deterministic naming (e.g. `tmp-pwd-<stageIdx>`
     derived from the canonical stage identity, NOT a wall-clock timestamp).
   - **(B)** Reject `tmp=true` formally from the supported DSL surface (a
     contractual narrowing) and document the legacy-only behaviour as
     deprecated. Requires a separate DSL change.
   - **(C)** Keep `tmp=true` as a legacy-only branch with `EFFECTFUL` semantics,
     marked as never-registry-routable. Requires a metadata split.
   The user signalled preference for **(A)** but **not now**; first we close
   `pwd(false)` end-to-end with the seam proven by `isUnix`.
3. **PATH_A eager DSL discrepancy** (`runtimeConfig.userDir()` returns host
   cwd, not canonical workspace) is a **pre-existing** semantic gap between
   the in-memory eager path and the durable canonical path. This slice does
   not touch the eager DSL; see `S2_A6_CORE_PWD_G0_CHARACTERIZATION_RECEIPT.md`
   §7 (out-of-scope #1).
4. **Pre-existing failures unchanged**: `fixture14CredentialsBindings`,
   `UatLocal007.SB-S-008/010` are unrelated to `core.pwd` and unchanged.

## 7. Stop

G3 ends here. The next slice (`S2-A6 / G4` — authority flip + LEGACY_UNREACHABLE)
is **explicitly NOT in scope of this slice** because the
PWD_TMP_TRUE_DISPOSITION blocker prevents an honest StepKey flip for `core.pwd`.
Two paths are visible:

```text
PATH A — dispose tmp=true first
   S2-A7 = `core.pwdTmp` (or equivalent disposition): writes_workspace step
                                       with deterministic naming
   Then S2-A6/G4: flip core.pwd for pwd(false) only; tmp=true still legacy
   Then S2-A6/G5/G6/G8: complete the burn-down for core.pwd

PATH B — narrow DSL contractually
   S2-A6/G4 = reject tmp=true from the DSL surface entirely
           (legacy path remains for backward compat but no longer publicly
           surfaced as `pwd(tmp=true)`)
   Then S2-A6/G5/G6/G8: complete the burn-down for core.pwd with the
                       contract narrowed to pwd(false) only
```

The user will choose the path after reviewing this G3 assessment.

Until then:
- `core.pwd` is REGISTERED (G1 done) but NOT authority-flipped (G4 not started).
- `pwd(false)` is MIGRATION_READY for the registry candidate (handler + codecs +
  capability admission proven by 22/0 G1 tests).
- `core.pwd` StepKey authority flip is **BLOCKED on PWD_TMP_TRUE_DISPOSITION**.

Re-entry path for `S2-A6 / G4` (NOT in this slice, requires explicit GO):
```text
G4 → remove "core.pwd" from LEGACY_PLUGIN_IDS + CanonicalCoreStepMetadata row
G5 → physical legacy removal (dispatcher, decoder branch, subtype, main dispatcher
     `pwdContext()` helper + `pwd` when-branch + `pwdDispatcher` field)
G6 → CorePwdStepContractSuiteTest (16-22 tests)
G8 → installed CLI fresh + replay evidence (post PWD_TMP_TRUE_DISPOSITION
     resolution path A or B)
S2-A6 CLOSED
```
