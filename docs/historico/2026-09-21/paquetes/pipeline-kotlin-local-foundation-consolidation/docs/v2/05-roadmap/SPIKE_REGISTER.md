# Spike register

## SPIKE-LFC-001 — Source-faithful shell dollar syntax

**Question:** Can `ScriptTextEscaper` be removed while retaining ergonomic multiline shell and accurate Kotlin diagnostics?  
**Compare:** normal Kotlin `${'$'}`, dedicated `shell` literal/builder, targeted compiler transformation.  
**Success:** no global source rewrite; source positions preserved; common Jenkins shell migration remains acceptable.

## SPIKE-LFC-002 — KSP + context-parameter capability derivation

**Question:** Can the current Kotlin/KSP toolchain reliably derive context parameter types into static capability metadata?  
**Fallback:** explicit generated annotation metadata bound to typed capability interfaces.  
**Success:** external plugin descriptor contains deterministic required capabilities without hardcoded step IDs.

## SPIKE-LFC-003 — Jlink image with Kotlin scripting/compiler

**Question:** Can a clean platform-specific Jlink image run compile/validate/execute without host JDK?  
**Success:** reference pipeline compiles/runs on clean Linux/macOS hosts; image contents/license data are valid; acceptable startup/size.

## SPIKE-LFC-004 — Hardened Linux execution

**Question:** Does bubblewrap materially improve local execution isolation without breaking common CI workflows?  
**Measure:** filesystem/network/env controls, startup overhead, Bazzite/Fedora compatibility, debugging UX.  
**Decision:** optional backend, container backend, or defer.

## SPIKE-LFC-005 — Graph persistence threshold

**Question:** When does a simple local projection/store become inadequate?  
**Do not run** until LFC-6 has real query/load metrics. Evaluate graph DB only against concrete required queries and scale.
