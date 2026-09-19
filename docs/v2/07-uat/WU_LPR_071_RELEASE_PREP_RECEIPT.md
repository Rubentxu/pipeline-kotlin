# WU-LPR-071 Release Prep Receipt — Round-Gate Defect Closure

**Commit:** `cce9b3ab fix(release-prep): WU-LPR-071 close round-gate defects (SB-S-008, SB-S-010, CR-BD-027, CR-U9, WULpr402)`
**Base:** `35a2f3fd` (WU-LPR-070 distribution name + reproducible distZip)
**Author:** Rubén
**Date:** 2026-09-19

## What this slice closes

The L4/L5 round gate after WU-LPR-070 surfaced **five real defects** in production code plus several
fitness-reconciliation drifts accumulated from WU-LPR-060..070. All are closed here without
expanding public surface.

### Defects (production code)

| ID | Surface | Fix |
|----|---------|-----|
| **SB-S-008** | parallel branches shared stage cwd | Branch derives ISOLATED `cwd` from `(controlDirRoot, stageIndex, branchIndex)` via `WorkspaceResolver`. Branch's immutable `ShOptions` copy carries the workspace. No coordinator state mutated. `--workspace` (when set) still wins as the shared project dir. |
| **SB-S-010** | sandbox profile changes silently re-attach | Non-NONE `SandboxProfile` enters the operation fingerprint so a resume with a CHANGED confinement profile diverges fail-closed instead of silently re-attaching. NONE stays absent from params so default-run journals keep historical fingerprints. |
| **CR-BD-027** | one `CredentialUsed` per lease, not per USE | Bindings of the ACTIVE credential lease pass through `dispatchBlockChildren`; one `CredentialUsed` event per binding is emitted after each child step execution (per USE, not per lease). `BoundPurpose` resolved from lease kind: `string → API_KEY`, `usernamePassword → USERNAME_PASSWORD`, `sshUserPrivateKey → SSH_KEY`. |
| **CR-U9** | `WorkspaceOperationsAdapter.writeFile` dropped `FileWritten` event | The adapter is now the single `FileWritten` emitter. The executor substrate carries the write evidence but event emission was left to a dispatcher that does not exist on the registry path, silently dropping the `FileWritten` contract. |
| **WULpr402 property 6** | `Main.doctor` read runtime values directly | Routes host-environment reads through the canonical `SystemRuntimeConfig` adapter. Direct `System.getProperty` here would be a second runtime-value authority. |

### Fitness reconciliation (no production change)

| Drift | Resolution |
|-------|------------|
| LPR-301 `physicalResidual` still listed `core.load` + `core.waitUntil` | Both physically removed; `physicalResidual = emptySet()`; counters converge 2/2/2 → 0/0/0. |
| LPR-401 DslMarker hierarchy pin | `Lpr101L4SweepCharacterizationTest` updated: `>= 1 @DslMarker` (was pinned at 0). |
| FArchLfc1 schema version | Baseline declares `schemaVersion: "v1"` (canonical LFC-2 event envelope), not the legacy "1.4". |
| FArchL7 WaitUntilBlock body | Reflects `body: List<StepSpec>` (WU-G5R certification). |
| PipelineDsl `ScriptScope` shims | `echo`, `sh`, `error` shims added so script bodies can no longer resolve step verbs from outer `StageScope` after the `@DslMarker` hierarchy was wired (LPR-401). |
| `BodyAggregateIdentity.ALL` size | Now 3 (added `wait-until-control`, ADR-0075 analog). |
| `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` body-bearing detection | Accepts `body: List<StepSpec>` as well as `steps: List<StepSpec>` for `WaitUntilBlock`. |
| `Lfc0GlobalStateFitnessTest` prose-vs-code | Scans code only (substring before `//`) so a line comment that merely MENTIONS a forbidden token is not a global-state access. |

### WULpr010 harness alignment

`WULpr010CliCharacterizationTest` was hardcoded to the legacy `pipeline-application` install path
(violating WU-LPR-070's distribution rename to `pipelinek`). Aligned with `AppBinSupport.discover()`
which already knows both names. **Without this fix, 11/11 of WULpr010 fails on a fresh checkout.**

## Tests touched (fresh XML canaries, fresh mtime)

| Suite | tests | failures | errors | notes |
|-------|-------|----------|--------|-------|
| `UatLocal007SandboxProfileTest` | 12 | 0 | 0 | SB-S-008 + SB-S-010 |
| `UatLocal008CredentialsTest` | 27 | 0 | 0 | CR-BD-027 (1 unrelated skip) |
| `UatLocal008SshPrivateKeyRoundGateTest` | 2 | 0 | 0 | 2 env-conditional skips |
| `UatCompat001CorpusSmokeRunTest` | 2 | 0 | 0 | CR-U9 FileWritten contract |
| `MainCliParsingTest` | 7 | 0 | 0 | WULpr402 doctor entry path |
| `CanonicalInMemoryCliTest` | 1 | 0 | 0 | parity with durable branch |
| `CliNonCanonicalInMemoryExitsTwoTest` | 1 | 0 | 0 | exit-2 path |
| `WULpr010CliCharacterizationTest` | 11 | 0 | 0 | was 11/11 RED on stale binary path |
| `UatParallelBlockDurableTest` | 3 | 0 | 0 | SB-S-008 cwd isolation |

All XMLs regenerated within this session (canary verified, mtime fresh).

## Architecture invariants preserved

- Canonical execution spine: unchanged path; one `dispatchBlockChildren` loop, no new collection.
- Body policies: `BodyExecutionPolicy` ADT untouched.
- Zero Step-specific routing: no new dispatcher case; all five fixes are typed contracts.
- Zero fake runtime values: `Main.doctor` now reads through the same `SystemRuntimeConfig` adapter
  the rest of the runtime uses (single authority).
- Hexagonal direction: `Capabilities.kt` prose update only; no new adapter dependency from inner modules.
- DSL describes: `ScriptScope` shims construct strings only; no runtime effects.

## Out of scope (intentionally)

- Root `/pipeline.kts` CI/CD authority (planned for WU-LPR-071 release workflow phase).
- `releaseVersion` authority derivation from git tag (planned for WU-LPR-071 release workflow phase).
- `0.1.0-SNAPSHOT` removal from the distribution (planned for the same phase).
- CycloneDX SBOM emission (planned for RC v0.36.0).
- `pipelinek-0.36.0.zip` candidate (planned for RC v0.36.0).

## Files

- `docs/v2/07-uat/WU_LPR_071_RELEASE_PREP_RECEIPT.md` (this file)
- Commit: `cce9b3ab`
- Parent: `35a2f3fd` (WU-LPR-070)
