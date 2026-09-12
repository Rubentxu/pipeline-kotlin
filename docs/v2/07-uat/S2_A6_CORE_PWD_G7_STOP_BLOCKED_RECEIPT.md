# S2-A6 / G7 — Installed Acceptance: STOP/BLOCKED Receipt

> Gate: **G7 — installed-distribution acceptance (real CLI)**
> Branch HEAD: `cac9b587` (main, post WAVE-1 readiness merge)
> Date: 2026-09-12T19:47–19:52Z
> Verdict: **INSTALLED_ACCEPTANCE = false — G7 BLOCKED. G8 NOT attempted.**

## Law applied

Zero-fabrication: results below come from real runs of the installed binary
against `main @ cac9b587`. No assertion was weakened and no criterion was
reinterpreted to produce a green gate. `CERTIFIED` stays false (ADR-0074).

## Evidence setup

```text
binary   : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
           (installDist UP-TO-DATE at run time, main @ cac9b587)
host dir : /tmp/pwd-g7-ws (workspace root resolved under control dir)
scenarios: PWD-G7-01..04 as separate .pipeline.kts fixtures (pipeline { ... } form)
raw archived (sha256):
  fresh.json       388bd2cff65c6d74bca91340b96a7bba4ce17f6080ca8ee4d2db75888bac40d7  (G7-02 fresh)
  resume.json      5ad286d7453bed3a00176388b0579e7972ede9e2a014e9294bc9686db4c37372  (G7-02 resume)
  pwd-g7-01.json   b81bac472b69ea515971f0075f5984475275877ec7c8bc07eb7f1c8454b476d9
  pwd-g7-03.json   bdf4de6154094e025e5d55fe3f0ea9f33354ef53edb84b7cffdbb3a478dbf899
```

## Results

```text
PWD-G7-01  pwd() runtime return            FAIL
           PwdResolved path  = .../workspace/pwd-g7-01-0        (correct, Registry family)
           echo captured     = <repo root> (host CWD placeholder)
           raw archived: pwd-g7-01.json

PWD-G7-02  pwd(tmp=true) durable effect    PASS
           deterministic tmp-pwd-<sha256(opId)> path, no timestamp/UUID
           directory really created on disk
           synchronous return              FAIL (host CWD placeholder)

PWD-G7-03  downstream Kotlin consume       FAIL
           (both values consumed the eager placeholder; conditional branch
            took the FAIL path inside the script)
           raw archived: pwd-g7-03.json (EchoOutputCaptured PWD_G7_03_FAIL...)

PWD-G7-04  replay / resume (same --db)     PASS
           same runId, same deterministic path, same sha256
           tmp directory NOT recreated (birth time unchanged across resume)
           note: resume re-emits PwdResolved as a projection of durable
           state; the effect itself is not re-executed
```

## Diagnosis (not a regression)

- Durable execution: **correct** (Registry family, deterministic identity,
  memoized resume, correct typed events).
- Runtime value projection: **correct in events/journal**; **not reconnected**
  to the structured `pipeline { ... }` DSL continuation.
- Root cause: the CLI routes `pipeline { ... }` sources through the eager
  `PipelineSpec` frontend (Main.kt R4B form selection). `PipelineDsl.pwd()`
  documents its synchronous placeholder (`runtimeConfig.userDir()`).
  The runtime-return seam shipped in LFC-2R (R1–R4B) covers only
  **generator-level scripted sources** and only `isUnix` today; the spike
  itself lists `pwd()` as a "sibling later".

## Blocker frozen

```text
STRUCTURED_DSL_RUNTIME_RETURN_GAP

A Step executed by the runtime cannot return a typed value into the Kotlin
frame that built a PipelineSpec. This is NOT pwd-specific: it is the
frontend/continuation boundary for the whole runtime-returning family
(pwd, pwd(tmp), readFile, fileExists, ...).
```

Supersedes in precision the earlier `PWD_RUNTIME_RETURN_RECONNECTION` label.

## State

```text
core.pwd:
  G5 LEGACY_REMOVED       ✅
  G6 CONTRACT_SUITE       ✅  (23/0/0, fresh XML)
  G7 INSTALLED_ACCEPTANCE ❌ BLOCKED (2/4 criteria pass: G7-02 effect, G7-04 replay)
  G8 CERTIFIED            ❌ not attempted

legacy counters = 6 / 6 / 6 (unchanged; no authority mutation in this slice)
```

## Disposition

Follow-up evolutive proposed (separate scope, horizontal — not a pwd patch):
**LFC-2R2 — Structured Runtime-Returning Steps**
(initial consumers: `pwd()`, `pwd(tmp=true)`, `readFile()`, `fileExists()`;
 architectural references: `isUnix`/`sh(returnStdout)` generator-level seams,
 without assuming their solution transfers to the structured form).

Next authorized gate in the burn-down (independent of this blocker):
**core.deleteDir G4 — REGISTRY_PRIMARY (6/6/6 → 5/6/6)**.
