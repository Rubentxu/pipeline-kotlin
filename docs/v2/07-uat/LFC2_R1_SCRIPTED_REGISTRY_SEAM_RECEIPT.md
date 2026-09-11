# LFC-2R / R1 — GENERIC SCRIPTED→REGISTRY SEAM

**Gate:** R1 of LFC-2R (Runtime-Returning Step Seam). **STOP after this gate.**
**Base:** `eaab2307` (G2R). Production-surface change: ONE new main file, `ScriptedRegistryInvoker` — additive only; no existing class, routing, DSL, or Main behavior touched.

## What was built

`ScriptedRegistryInvoker` (`application/scripted/`) — the step-agnostic seam:

```text
ScriptedRegistryCall(runId, entryPointId, callSiteId, dynamicScopePath, invocationOrdinal,
                     stepKey, encodedInput)
→ operationId = same length-prefixed tuple discipline as ScriptedOperation (+ stepKey)
→ OperationJournal.get(operationId)          [DURABLE DECISION FIRST]
    SUCCEEDED + matching fingerprint → decode persisted output, return
    divergent / RUNNING / non-SUCCEEDED terminal → Failed(REPLAY_COMPATIBILITY)
→ FRESH: RegistryExecutionPreparation.prepare (fail-closed admission BEFORE any effect)
         → RegistryExecutionBoundary.coexecute → CommonExecutionResult.encodedOutput
         → journal SUCCEEDED(output) → return encoded output
```

Namespace rule: journal `stepId = "scripted." + stepKey.value` — distinct from every
declarative operation id and from `scripted.core.sh`'s dedicated shell path.

## The frozen law (all machine-asserted, `ScriptedRegistryInvokerTest` 10/0, fresh XML)

```text
FRESH:  handler exactly once; encodedOutput persisted as OperationOutput; typed value returned
REUSE:  same durable identity → handler invocations == 1 (unchanged), reuse_value == persisted_value
        REUSE REQUIRES NO CAPABILITIES: proven by re-invoking with an EMPTY registry —
        the journal SUCCEEDED hit short-circuits before registry resolution or capability
        admission (decisive ordering: durable decision BEFORE the effect)
LOOP SAFETY: distinct ordinal/input → distinct durable operation (repeat-safety)
```

Error model — fail closed, never a fabricated value (the decoded Boolean will control
user Kotlin flow):

| case | result |
| --- | --- |
| unknown stepKey | Failed(SCHEMA), handler 0 |
| undecodable input | decode failure before any effect |
| missing capability | admission failure, handler 0 |
| SUCCEEDED row without output | Failed(REPLAY_COMPATIBILITY) — no `42` fabricated |
| diverged input on existing op | Failed(REPLAY_COMPATIBILITY) |
| FAILED history | replays as failure WITHOUT re-execution |
| SUCCEEDED without encodedOutput (fresh) | Failed(ENGINE) — protocol violation |

Architecture fitness: the invoker source (comments stripped — known gotcha) contains NO
`core.isUnix / core.pwd / core.readFile / core.fileExists / core.sh`. The fixture Step is
neutral (`scripted.fixture.inc`, "41"→"42", counter-verified); the seam is written around
no concrete Step. `pipeline-architecture-tests` 221/0 green after the addition.

## Deliberately NOT touched

`PipelineDsl.isUnix()`, `Main.kt` execution selection, `CoreIsUnixStep`,
`UnixPlatformClassifier`, `CanonicalIsUnixNodeDispatcher`, `LEGACY_PLUGIN_IDS`,
`StructuralFamily`, the `sh` scripted path. No runBlocking / ThreadLocal / global map /
lastOutput / script-twice / per-Step special case anywhere in the seam.

## Slice state

```text
core.isUnix: unchanged — REGISTERED=true, all other gates false; family LegacyCore; counters 8/8/8
DSL_RUNTIME_RETURN_GAP = OPEN (R1 creates the seam; it does not yet serve the DSL)
```

## Next

R2 — connect `core.isUnix` as the FIRST REAL consumer: `ScriptedStepFacade.isUnix(callSite):
Boolean` through this invoker (fresh observes PlatformIdentity; resume decodes the persisted
Boolean). Still without `Main` reconnection (R4).

**R1 COMPLETE. STOP.**
