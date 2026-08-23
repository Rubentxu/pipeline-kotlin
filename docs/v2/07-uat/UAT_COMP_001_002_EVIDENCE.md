# UAT_COMP_001_002 Evidence

> **Supersession note (M2-R1 closure):** This evidence file was created during
> M1-R3 (v0.6.0). M2-R1 (v0.7.0, `[[ADR-0022-dsl-seed-bridge]]` §M2-R1 Extension)
> extends the DSL grammar but does NOT change the `UatComp001` / `UatComp002` /
> `UatEvt001` / `UatEvt002` test assertions or fixture shapes. The evidence below
> remains valid for M2-R1 verification purposes. New evidence for M2-R1 DSL
> grammar capabilities (agent, parallel, retry, timeout) is captured in
> `UatDsl001JenkinsFamiliarityTest`, `UatDsl003ParallelTest`, and
> `UatDsl005TimeoutGrammarTest`.

## Test Commands

### Run UAT Comp Tests
```bash
./gradlew -p v2 :pipeline-scripting-kotlin24:test --tests '*UatComp*'
```

### Run UAT Evt Tests (M1-R3 DSL seed)
```bash
./gradlew -p v2 :pipeline-application:test --tests '*UatEvt*'
```

### Run Full V2 Check
```bash
./gradlew -p v2 clean check
```

### Run Architecture Tests
```bash
./gradlew -p v2 :pipeline-architecture-tests:test
```

## Last-Run Verification (M1-R3 DSL seed bridge)

- `./gradlew -p v2 :pipeline-scripting-kotlin24:test` → 5 tests, 0 failures, 0 ignored.
- `./gradlew -p v2 clean check` → BUILD SUCCESSFUL across `pipeline-domain`,
  `pipeline-application`, `pipeline-scripting-api`, `pipeline-testkit`,
  `pipeline-architecture-tests`, `pipeline-scripting-kotlin24`.
- FArch003 containment pass: `kotlin.script.experimental` only in
  `pipeline-scripting-kotlin24` (adapter) + `pipeline-architecture-tests`
  (test fixtures).
- `grep -rn "wholeClasspath" v2/` → 3 comment-only matches in
  `Kotlin24ScriptingHost.kt` design rationale. **No runtime `wholeClasspath = true`**
  anywhere in the production path.

## Fixture Changes (M1-R3)

The `hello.pipeline.kts` fixture was updated from:
```kotlin
val definition = "hello"
```
to the DSL form:
```kotlin
pipeline {
    stages {
        stage("hello") {
            echo("hello")
        }
    }
}
```

This means `UatEvt001ReplayTest` now expects 8 events (was 4) because the DSL
evaluation walks the `PipelineSpec` and emits `StageStarted`, `StepStarted`,
`StepFinished`, `StageFinished` in addition to the run/compilation events.

## Expected Outputs

### UatComp001 — Script Compiles
- **Expected**: `result.isSuccess == true`, `result.diagnostics` is empty (DEBUG reports are filtered out at INFO threshold by the adapter to avoid noise — see `Kotlin24ScriptingHost.compile`).
- **Key assertions**:
  - `assertTrue(result.isSuccess, ...)`
  - `assertTrue(result.diagnostics.isEmpty(), ...)`
  - `assertNotNull(result.value)`
  - `assertEquals(result1.cacheKey, result2.cacheKey)` — cache key stable across evaluations.

### UatComp002 — Error Source-Mapped
- **Expected**: `result.isSuccess == false`, ERROR diagnostics with `line > 0` and `path` referencing `broken.pipeline.kts`.
- **Key assertions**:
  - `assertFalse(result.isSuccess)`
  - `assertTrue(errors.any { it.severity == ScriptDiagnosticSeverity.ERROR })`
  - `assertTrue(diagnostics.any { it.line > 0 })`
  - `assertTrue(diagnostics.any { it.path.contains("broken.pipeline.kts") })`

### UatEvt001 — DSL pipeline replay (updated for M1-R3)
- **Expected**: 8 events from `hello.pipeline.kts`:
  `[RunStarted, CompilationStarted, CompilationFinished, StageStarted, StepStarted, StepFinished, StageFinished, RunFinished]`
- **Key assertions**:
  - `assertEquals(8, events.size)`
  - `assertTrue(events[2] is CompilationFinished)` — `cacheKey.version == "v1"`
  - `assertTrue(events[3] is StageStarted)` — `stageName == "hello"`
  - `assertTrue(events[4] is StepStarted)` — `stepType == "echo"`
  - `assertTrue(events[7] is RunFinished)` — `outcome == "success"`

### UatEvt002 — Multi-step DSL replay
- **Expected**: 16 events from `multi-step.pipeline.kts` (2 stages × 2 steps):
  `[RunStarted, CompilationStarted, CompilationFinished,
   StageStarted(build), StepStarted(echo), StepFinished(echo), StepStarted(sh), StepFinished(sh), StageFinished(build),
   StageStarted(test), StepStarted(echo), StepFinished(echo), StepStarted(sh), StepFinished(sh), StageFinished(test),
   RunFinished]`
- **Key assertions**:
  - Stage 0 (build): `stageIndex == 0`, `stageName == "build"`, 2 steps (echo, sh)
  - Stage 1 (test): `stageIndex == 1`, `stageName == "test"`, 2 steps (echo, sh)
  - All `StageFinished.outcome == "success"`
  - `RunFinished.outcome == "success"`, diagnostics empty

## PASS Criteria

1. UatComp001 passes (script compiles successfully; diagnostics filtered of DEBUG).
2. UatComp002 passes (broken script produces ERROR diagnostics with line > 0 and path referencing `broken.pipeline.kts`).
3. UatEvt001ReplayTest passes (8 events from DSL pipeline, `cacheKey.version == "v1"`).
4. UatEvt002MultiStepReplayTest passes (stage/step events in correct sequence).
5. Architecture test FArch003 passes (allowlist includes `/pipeline-scripting-kotlin24/`).
6. `grep -rn "wholeClasspath" v2/` returns only comment references in `Kotlin24ScriptingHost.kt`.
7. `grep -rn "kotlin.script.experimental" v2/` returns matches only in `pipeline-scripting-kotlin24` (adapter module) and `pipeline-architecture-tests` (test fixtures).
8. `grep -rn 'ProcessBuilder' v2/pipeline-application/src/main/` → 0 matches (no real sh execution in production path).

## Cache Key Formula

```kotlin
val cacheKey = sha256Hex(
    scriptText + "|" +
    classpathFiles.map { it.canonicalPath }.sorted().joinToString(",") + "|" +
    kotlinVersion + "|" +
    hostVersion
)
```

Where:
- `scriptText`: raw content of the `.pipeline.kts` file (read via `SourceCodeFactory`).
- `classpathFiles`: list of `File` objects from `ScriptDefinition.classpath` (canonicalised so relative/absolute paths produce the same key).
- `kotlinVersion`: `"2.4.10"` (fixed).
- `hostVersion`: `"1.0.0"` (fixed).

## Adapter Pattern

The adapter uses the canonical Kotlin scripting pattern (same as V1's
`GenericKotlinDslEngine`):

```kotlin
val cfg = createJvmCompilationConfigurationFromTemplate<Any> {
    jvm {
        dependenciesFromCurrentContext()          // default wholeClasspath = false
        if (classpathFiles.isNotEmpty()) {
            updateClasspath(classpathFiles)        // explicit per-call jars
        }
    }
}
val rwd = BasicJvmScriptingHost().eval(source, cfg, ScriptEvaluationConfiguration {})
```

- `createJvmCompilationConfigurationFromTemplate<Any>` resolves to the built-in
  Kotlin script definition. The custom `.pipeline.kts` file extension is conveyed
  via `FileScriptSource(file).name` (which the host maps to a script definition
  through the standard resolver).
- `dependenciesFromCurrentContext()` default `wholeClasspath = false` pulls the
  current module's compilation context (kotlin-stdlib, kotlin-script-runtime,
  kotlin-reflect, kotlin-scripting-jvm, kotlin-scripting-jvm-host) — exactly
  what the spec allows.
- Per-call jars supplied by `ScriptDefinition.classpath` are appended via
  `updateClasspath(files)`, keeping the **explicit classpath** contract.

## Verification Status

- [x] UatComp001 passes (script compiles, diagnostics filtered of DEBUG).
- [x] UatComp002 passes (ERROR diagnostic, `line > 0`, `path` references `broken.pipeline.kts`).
- [x] UatEvt001ReplayTest passes (8 events, `cacheKey.version == "v1"`).
- [x] UatEvt002MultiStepReplayTest passes (stage/step event sequence).
- [x] FArch003 allowlist includes `/pipeline-scripting-kotlin24/`.
- [x] `wholeClasspath` grep clean in production path.
- [x] `kotlin.script.experimental` containment as specified.
- [x] No `ProcessBuilder` in `pipeline-application/src/main/` production code.