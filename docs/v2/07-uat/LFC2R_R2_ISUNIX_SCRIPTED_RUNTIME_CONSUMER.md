# LFC-2R / R2 — `core.isUnix` Scripted Runtime Consumer Receipt

**Date:** 2026-09-11
**Branch:** `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Slice:** LFC-2R / R2 (Runtime-Returning Step Seam — first productive consumer)

## Scope honored

- `ScriptedStepFacade.isUnix(callSite): Boolean` added (suspend, runtime-returning). NOT `fun isUnix(): Boolean`.
- No `Main.kt` changes. No eager `PipelineDsl.isUnix()` change. No authority flip.
- S2-A5 counters unchanged: REGISTERED=true; REGISTRY_PRIMARY/LEGACY_UNREACHABLE/LEGACY_REMOVED/CONTRACT_SUITE/CERTIFIED=false; LegacyCore; 8/8/8.
- D3 (`DSL_RUNTIME_RETURN_GAP`) refines to: `runtime solution proven = true`, `compiled scripted consumer proven = true`, `production .pipeline.kts wiring = false`. Still OPEN.

## Production changes

1. **`pipeline-scripting-api` / `ScriptedExecutionApi.kt`**
   - `ScriptedStepFacade.isUnix(callSite): Boolean` — public suspend contract; the Boolean is a durable runtime value materialized before control returns to Kotlin. `IsUnixOutput` is NOT exposed to scripts.
   - `ScriptedSourceLocation.unixCallSite(): ScriptedCallSiteId` — `"src:line:col:isUnix"`, deliberately distinct from `shellCallSite()` so two steps transformed at one position never collide.

2. **`pipeline-application` / `scripted/ScriptedRuntime.kt`**
   - `ScriptedScope.identity` (runId, entryPointId, dynamicScopePath) and `nextOrdinal(callSite)` — same loop-safety ordinal discipline as `invokeAt`, shared by registry-step invocations.

3. **`pipeline-application` / `scripted/CompiledScriptedEntryPoint.kt`**
   - `RuntimeScriptedStepFacade.isUnix`: THIN adaptation — identity + encoded `{}` input → `ScriptedRegistryInvoker` → `CoreIsUnixStep` → encoded `IsUnixOutput` → the Step's DECLARED `outputCodec.decode(...)` → Boolean. No `System.getProperty`, no classifier call, no platform access, no hand-written parallel decoder (arch fitness pins all).
   - Typed output projection uses `CoreIsUnixStep.definition.contract.outputCodec` (static Step-owned code, not a runtime capability) so the REUSE path (empty registry, zero capabilities) decodes the persisted payload with the SAME codec that encoded it fresh: one output contract, both directions.
   - Fail loud without an invoker (`EngineInvariantViolation`), never a fabricated value.
   - Corrupt persisted output → `REPLAY_COMPATIBILITY` failure; `error → false` is unreachable.

4. **`pipeline-application` / `scripted/ScriptedRegistryInvoker.kt`**
   - Additive: `capabilityAccessFactory` (default = canonical bridge) and read-only `definitionFor`. The durable decision (journal lookup) still precedes all capability/registry resolution; replay never invokes the factory.

5. **`pipeline-application` / `durable/RegistryExecutionBoundary.kt`**
   - `coexecute` overload accepting an explicit capability-bridge factory; the original signature delegates to it with the canonical bridge. Fail-closed re-check semantics unchanged.

6. **`pipeline-application` / `durable/CanonicalRuntimeCapabilityAccess.kt`**
   - `open` class + `open get` so harnesses can substitute OBSERVATION sources without touching production logic. Production behavior identical.

## Proof — `ScriptedIsUnixRuntimeTest` (13/0, fresh XML)

FRESH:
- `fresh execution target value — SunOS on a Linux host returns true, not the host observation` — PlatformIdentity (execution target) is authoritative, not the accidental host JVM.
- `canonical classifier matrix through the full chain` — Windows 11→false, Mac OS X→true, OpenBSD→true, ""→false; each row asserts facade return == persisted IsUnixOutput == UnixDetected (atomic coherence).
- `typed output roundtrip` is embedded in both (decode through the declared codec).

REUSE (decisive):
- `reuse with EMPTY registry, EMPTY capabilities and CHANGED platform returns persisted true` — SunOS fresh → true; reuse under a Windows observation with an empty registry → still true. PlatformIdentity reads during reuse == 0; new UnixDetected events == 0.
- `reuse after platform change — Windows persisted false stays false when host would say Unix`.
- `handler invocations == 0` asserted via event counts and the R1 suite.

Call-site identity:
- Two call sites + 3 scoped loop iterations = 5 distinct durable operations, each handler-once; a repeat execution reuses all five with zero new events.
- `unixCallSite()` differs from `shellCallSite()` at the same position and is stable.

FAIL CLOSED:
- Missing step (fresh) → `PipelineStepException(SCHEMA)`, no Boolean reaches the Kotlin frame, no events.
- Missing PLATFORM_IDENTITY → admission rejection before any effect.
- SUCCEEDED row without output → `REPLAY_COMPATIBILITY`, never false.
- Corrupt persisted output → `REPLAY_COMPATIBILITY`, never false.
- Facade without invoker → loud engine violation, never a value.

ARCHITECTURE FITNESS (in-suite, comments stripped):
- Facade contains none of: `System.getProperty`, `classifyUnix`, `UnixPlatformClassifier`, `PlatformIdentity`, `RuntimeConfig`, `os.name`.
- Facade contains no parallel hand-decoding (`toBooleanStrict`, `jsonObject[`, `getValue("isUnix")`).

## Regression evidence

- `ScriptedRegistryInvokerTest` 10/0 (R1 seam unchanged).
- `CoreIsUnixStepUnitTest` 18/0 (G1/G2 contracts unchanged).
- `ScriptedScopeTest` 13/0; `CompiledScriptedEntryPointHostTest` (kotlin24) 1/0.
- `pipeline-architecture-tests` 53 classes, failures=0 (canary-verified `--rerun-tasks`).
- Whole-workspace `compileKotlin`/`compileTestKotlin` green.

## Known limits (deliberate, next slices)

- R2 does NOT reconnect `Main`; the eager DSL path still answers at construction time.
- R3 will decide compiler/source mapping (generated `unixCallSite()` from real `.pipeline.kts`); R4 wires the CLI/entrypoint composition so real pipelines consume the runtime value.
- `definitionFor` retained on the invoker for future typed consumers; unused by the facade after the codec-direct simplification.
