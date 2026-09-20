# WU-LPR-087 — LFC-2R2 Implementation: phase tracking

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | **PLANNING CLOSED; Phase A starting** |
| Trigger | WU-LPR-086 (spike merge) authorised the production-code slice; this WU executes it. |
| Parent | `cbb7c61d` (post WU-LPR-086) |
| Scope | LFC-2R2 implementation: close `core.pwd` G7+G8 + `core.pwdTmp` G6+G8 + (optional) `core.readFile`/`core.fileExists` registration. |
| Authoritative design | `openspec/changes/lfc2-r2-implementation/{proposal,design,tasks}.md` + `ADR-0093`. |

## Phase A — Foundation (no DSL change)

This phase adds the new kinds to the public façade ADT without breaking
the existing R4B closure. After this phase, the façade is ready to be
implemented but no script can reach the new kinds yet.

- [x] Read blocker + spike + precedent code.
- [x] Confirm corpus impact (3 fixtures: 13-workspace-helpers, 20-pwd-tmp, 23-readfile).
- [x] Confirm `runFixturePass` is robust to echo-content changes.
- [x] Write `openspec/changes/lfc2-r2-implementation/{proposal,design,tasks}.md`.
- [ ] Extend `ScriptedCallKind` (ScriptedExecutionApi.kt) with `ReadFile`, `FileExists`, `ShellReturnStdout(script)`.
- [ ] Extend `ScriptedSourceLocation` with the three new call-site factories.
- [ ] Extend `ScriptedStepFacade` with the three new methods.
- [ ] L0: `:pipeline-scripting-api:compileKotlin`.
- [ ] L1: existing `ScriptedStepFacade` tests still green (additive only).

## Phase B — Concrete façade + Step registrations

- [ ] `RuntimeScriptedStepFacade.readFile/fileExists/shReturnStdout`.
- [ ] Decide `core.readFile`/`core.fileExists` placement (core or Tier-D).
- [ ] L0 + L1.

## Phase C — Mapping + Lowering

- [ ] `KotlinScriptedSourceMapper` recognises the four new forms.
- [ ] `ScriptedSourceLowering` rewrites the four new forms.
- [ ] Bump `facadeSchemaVersion` to `facade-r4-pwd-readFile-fileExists-shReturnStdout-v1`.
- [ ] L0 + L1.

## Phase D — Main.kt + structured body

- [ ] Main.kt form selector extended.
- [ ] `PipelineDsl.steps { block }` becomes `suspend` receiver.
- [ ] Eager `pwd/tmp/readFile/fileExists` builders removed.
- [ ] `suspend fun sh(..., returnStdout = true): String` overload added.
- [ ] `RUNTIME_VALUE_PLACEHOLDER` + `DslRuntimeConfigScope` killed.
- [ ] L0 + L3 (full `:pipeline-application:test`).

## Phase E — G7 canary + G8

- [ ] Fixtures 23..26 (structured pwd, pwd-tmp, readFile, fileExists).
- [ ] installDist + fresh + replay canaries.
- [ ] Per-Step G7 + G8 receipts.
- [ ] WU-LPR-087 receipt.

## Phase F — Validation + close-out

- [ ] L4: `./gradlew -p v2 check` incremental.
- [ ] L5: full L5 gate.
- [ ] Inventory + initiative + handoff updates.
- [ ] Commit + tag `wu-lpr-087` + push.
- [ ] Advance queue to WU-LPR-088 (or 089 if 088 subsumed).

## End-of-slice closure block (template)

```text
Reference implementation consulted: ADR-0093, SPIKE-016, LFC-2R R2
Behaviour adopted:                 suspend structured DSL; one generic scripted→registry seam
Intentional deviations:            none (every decision bound by ADR-0093 §4.2)
Security implications reviewed:    no new capability surface; new handlers reach capabilities only
Tests demonstrating the contract:   (filled at slice close)
```
