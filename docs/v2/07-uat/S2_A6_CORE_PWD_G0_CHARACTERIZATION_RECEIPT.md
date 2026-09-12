# S2-A6 / G0 — `core.pwd` Three-Path Characterization

**Cycle:** `lfc2-e1-s2-a6-g3-core-pwd-readiness`
**Branch:** `cycle/lfc2-e1-s2-a6-g3-core-pwd-readiness`
**Base:** `dc0da54f` (S2-A5/G8 — CERTIFIED trunk HEAD)
**Date:** 2026-09-12T09:51Z
**Status:** CHARACTERIZATION COMPLETE — STOP after G0 receipt; G1..G3 follow.

## 1. Scope (user GO, 2026-09-12T09:49Z)

First pass: `pwd(false)` only. `pwd(tmp=true)` is **explicitly out of scope** of
this slice and constitutes the **PWD_TMP_TRUE_DISPOSITION** blocker for the
StepKey authority flip. The user-mandated split is:

```text
pwd(false) → supported in registry candidate
pwd(true)  → UNSUPPORTED_REGISTRY_VARIANT / fail closed
```

## 2. PATH_A — eager DSL (`PipelineDsl.pwd(tmp)`)

**Source**: `v2/pipeline-scripting-api/.../dsl/PipelineDsl.kt:1570`

```kotlin
fun pwd(tmp: Boolean = false): String {
    val step = StepSpec.Pwd(tmp = tmp)
    steps.add(step)
    // Return real workspace path synchronously for in-memory scripting host.
    // Reads through the RuntimeConfig port so :pipeline-scripting-api does
    // not couple to global JVM state; ...
    return runtimeConfig.userDir().ifEmpty { "<workspace>" }
}
```

**Observed semantics**:
- `pwd(false)` → returns `runtimeConfig.userDir()` (host JVM `user.dir`).
- `pwd(true)`  → returns `runtimeConfig.userDir()` (same as `false`).
- The `tmp` flag is recorded into `StepSpec.Pwd(tmp)` but **ignored by the eager
  return value**.
- The DSL contract is a **lie**: it promises a per-call tmp subdirectory but
  never creates one.

**Source of `userDir`**: `SystemRuntimeConfig.userDir()` reads `System.getProperty("user.dir")`.
This is the **host process working directory**, not the canonical stage workspace.

## 3. PATH_B — durable legacy (`CanonicalPwdNodeDispatcher`)

**Source**: `v2/pipeline-application/.../durable/CanonicalPwdNodeDispatcher.kt`
(50 lines, two paths: `tmp=false` and `tmp=true`)

**`tmp=false`** (line 28-31):
```kotlin
val path = context.workspaceRoot.toAbsolutePath().toString()
```
Where `context.workspaceRoot` is populated from `shOptions.workspaceRoot`
(`CanonicalNodeDispatcher.pwdContext()` line 89). In production, Main.kt sets
`shOptions.workspaceRoot = controlDirRoot.resolve("workspace")` (line 838/895).
Net effect: `pwd(false)` returns **`<controlDirRoot>/workspace`**.

**`tmp=true`** (line 22-27):
```kotlin
val tmpDir = context.workspaceRoot.resolve("tmp-pwd-${System.currentTimeMillis()}")
tmpDir.toFile().mkdirs()
tmpDir.toAbsolutePath().toString()
```
Creates a physical directory `tmp-pwd-<timestamp>` under the workspace root.
The path includes a millisecond timestamp — **NOT deterministic**; replay
re-runs would mint different paths.

**Event emitted**: `PwdResolved(path, workspaceRoot, sha256)` — typed, durable.

## 4. PATH_C — registry candidate (this slice's design)

**StepKey**: `core.pwd` (matches the legacy `pluginId`)
**Effects**: `{ READ_ONLY }` (for `tmp=false` only)
**ReplayPolicy**: `MEMOIZED`

**Required capabilities**:
- `WORKSPACE_IDENTITY_CAPABILITY` (new narrow capability, mirroring
  `PLATFORM_IDENTITY_CAPABILITY` for `core.isUnix`).
- `EVENT_SINK_CAPABILITY` (existing, for `PwdResolved` emission).

**Typed contract**:
```kotlin
data class PwdInput(val tmp: Boolean = false)        // tmp=true rejected
data class PwdOutput(val path: String)               // absolute path string
```

**Handler logic** (proposed):
1. `require(!input.tmp) { "core.pwd tmp=true is unsupported in this registry candidate; see PWD_TMP_TRUE_DISPOSITION" }`
2. Read `WorkspaceIdentity` capability → `workspaceRoot: Path`.
3. Read `EventSink` capability → emit `PwdResolved(path = workspaceRoot.toAbsolutePath().toString(), workspaceRoot = workspaceRoot.toAbsolutePath().toString(), sha256 = sha256(path))`.
4. Return `PwdOutput(path = workspaceRoot.toAbsolutePath().toString())`.

**WorkspaceIdentity** (new):
```kotlin
data class WorkspaceIdentity(val workspaceRoot: Path)
val WORKSPACE_IDENTITY_CAPABILITY: StepCapability = StepCapability("runtime.workspace-identity")
```
Bridge (in `CanonicalRuntimeCapabilityAccess.buildProvided`) populates from
`context.shOptions.workspaceRoot` — **the same source the legacy
`pwdContext()` consumed**, preserving byte-equivalent truth.

## 5. Three-path truth comparison (single-table)

| Variant          | PATH_A (DSL eager)                | PATH_B (legacy durable)                          | PATH_C (registry candidate, this slice)          |
|------------------|-----------------------------------|--------------------------------------------------|---------------------------------------------------|
| `pwd(false)`     | `runtimeConfig.userDir()` (host)  | `shOptions.workspaceRoot` (controlRoot/workspace)| `WorkspaceIdentity.workspaceRoot` (= shOptions)   |
| `pwd(true)`      | `runtimeConfig.userDir()` (lie)   | `workspaceRoot/tmp-pwd-<timestamp>` (side-effect)| **rejected** at decode (fail-closed)              |
| Event emitted?   | `StepStarted/Finished` only       | `PwdResolved`                                    | `PwdResolved` (same fields)                       |
| Determinism      | host-dependent (user.dir)         | stable per stage; `tmp=true` non-deterministic   | stable per stage; `tmp=true` rejected             |
| Effect class     | implicit                           | READ_ONLY (legacy metadata, but `tmp=true` writes)| `{ READ_ONLY }` declared on descriptor            |
| Replay           | step-replay (input fingerprint)   | MEMOIZED (legacy metadata)                       | MEMOIZED (descriptor)                             |

**Truth claim**: `pwd(false)` is **equivalent** between PATH_B and PATH_C in the
durable path. PATH_A is **observably different** (host `user.dir` vs canonical
workspace). This is a separate, pre-existing PATH_A ↔ PATH_B discrepancy that
this slice does NOT change; the DSL eager path is unrelated to the registry
candidate's truth.

## 6. Contract freeze — proposed D1..D4 (subject to G2 confirmation)

| ID  | Decision | Status |
|-----|----------|--------|
| D1  | canonical `pwd(false)` truth = `shOptions.workspaceRoot` (= `<controlDirRoot>/workspace` in production; per-call injected in tests) — **NOT host `user.dir`** | PROPOSED |
| D2  | typed output `PwdOutput(path: String)` is APPROVED — single field, absolute path | PROPOSED |
| D3  | `tmp=true` = OUT_OF_SCOPE for first-pass slice; **BLOCKER_FOR_AUTHORITY_FLIP** because the StepKey routing is by `core.pwd`, not by `tmp` flag; the candidate MUST reject `tmp=true` at decode time | PROPOSED |
| D4  | `ReplayPolicy.MEMOIZED` for `pwd(false)`: fresh/rerun observes, resume/reuse reproduces persisted observation; `core.isUnix` precedent already CERTIFIED with the same `{READ_ONLY} + MEMOIZED` pairing | PROPOSED |

These four are the G2 freeze targets.

## 7. Out-of-scope observations (documented, NOT acted on)

1. **PATH_A eager DSL discrepancy** (`runtimeConfig.userDir()` returns host
   cwd, not canonical workspace) is a **pre-existing** semantic gap between
   the in-memory eager path and the durable canonical path. This slice does
   not touch the eager DSL; it documents the discrepancy so a future
   LFC-2-HONEST-DSL closure cycle can address it independently. Authority:
   `docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md` `pwd() real return` row
   (🔲 OPEN) and `EM_DEAD_CODE_AUDIT.md` A3 entry on `PipelineDsl.pwd()`.
2. **`tmp=true` side-effect with non-deterministic timestamp** is
   **architecturally broken** for a `MEMOIZED` step. Three dispositions are
   possible (a) implement as a separate `core.pwdTmp` StepKey with
   `Effect.WRITES_WORKSPACE` + deterministic naming; (b) make `tmp=true`
   reject with `UNSUPPORTED_REGISTRY_VARIANT`; (c) keep legacy-only path
   with `EFFECTFUL` semantics. None of these is decided in this slice —
   **PWD_TMP_TRUE_DISPOSITION** is the blocker.
3. **No new scripted facade for `pwd`** is required in G0..G3 of this
   slice. The G1 candidate is wired through `CoreStepRegistryFactory` and
   reachable through the generic registry path; extending `ScriptedStepFacade`
   with `pwd(callSite, tmp)` is a G8-extension (LFC-2R/R2 second consumer),
   not part of this readiness slice. (S2-A5 added `steps.isUnix(callSite)` as
   part of G1 — `pwd` parallel can land in a follow-on slice once the
   registry candidate is green.)

## 8. Stop

G0 ends here. The next slice (`S2-A6 / G1` — registry candidate) requires
explicit user GO after review of this receipt. Per the AGENTS.md scope
firewall and the LB-02 burn-down law, the framework will not auto-progress.

Re-entry path for `S2-A6 / G1`:
```text
G1 → CorePwdStep.kt (input/output codec + handler + capability)
   + WorkspaceIdentity capability declared in Capabilities.kt
   + CanonicalRuntimeCapabilityAccess wiring
   + CoreStepRegistryFactory.registerInto(this)
G2 → freeze D1..D4 from §6 above
G3 → readiness assessment: pwd(false) MIGRATION_READY, core.pwd AUTHORITY_FLIP_READY=false
STOP (no G4 in this slice)
```

Until then, no `core.pwd` production change has been made.
