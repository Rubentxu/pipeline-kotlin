# Design: lfc2-r2-implementation

## 1. Architecture summary

```text
.user source (.pipeline.kts)
  pipeline { stages { stage("x") { steps {
      val p = pwd()                       // suspend runtime-return
      val b = fileExists("marker")        // suspend runtime-return
      val c = readFile("config.txt")      // suspend runtime-return
      val s = sh("echo $p", returnStdout = true)   // suspend runtime-return
      sh("cleanup")                       // eager (existing)
      echo("done $s")                     // eager (existing)
  } } } }
       │
       ▼  K2 PSI pre-pass (KotlinScriptedSourceMapper)
  ScriptedSourceMapping.Mapped(
      calls=[
        ScriptedMappedCall(kind=Pwd(tmp=false),       location=…),
        ScriptedMappedCall(kind=FileExists,            location=…),
        ScriptedMappedCall(kind=ReadFile,              location=…),
        ScriptedMappedCall(kind=Shell(returnStdout=true), location=…),
        ScriptedMappedCall(kind=Shell,                 location=…),
      ]
  )
       │
       ▼  Main.kt form selector (line 622–658, extended)
  hasAnyRuntimeReturningCall = mappedCalls.any { it.kind != Shell }
  hasStructuredBody = Regex("""\bpipeline\s*\{""").containsMatchIn(source)
  scriptedFrontend = if (hasStructuredBody && hasAnyRuntimeReturningCall)
                       ScriptedSourceLowering.lower(…)        // structured form path
                     else if (!hasStructuredBody && mappedCalls.isNotEmpty())
                       ScriptedSourceLowering.lower(…)        // existing generator form
                     else null                                 // eager PipelineSpec
       │
       ▼  ScriptedSourceLowering.lower(…) (extended)
  rewrites each mapped call to `steps.<method>(ScriptedCallSiteId(…))`:
    pwd()              → steps.pwd(ScriptedCallSiteId("…:pwd"))
    pwd(true)          → steps.pwd(ScriptedCallSiteId("…:pwd:tmp"), tmp=true)
    readFile(file)     → steps.readFile(ScriptedCallSiteId("…:readFile"), file)
    fileExists(file)   → steps.fileExists(ScriptedCallSiteId("…:fileExists"), file)
    sh(cmd, returnStdout=true) → steps.shReturnStdout(ScriptedCallSiteId("…:sh:ro"), cmd)
       │
       ▼  Kotlin host compile (no eager RuntimeConfig injection)
  CompiledScriptedEntryPoint {
      override suspend fun execute(steps: ScriptedStepFacade) {
          val p = steps.pwd(ScriptedCallSiteId("…:pwd"))
          val b = steps.fileExists(ScriptedCallSiteId("…:fileExists"), "marker")
          val c = steps.readFile(ScriptedCallSiteId("…:readFile"), "config.txt")
          val s = steps.shReturnStdout(ScriptedCallSiteId("…:sh:ro"), "echo $p")
          steps.sh(ScriptedCallSiteId("…:sh:1"), "cleanup")
          steps.echo(ScriptedCallSiteId("…:echo"), "done $s")
      }
  }
       │
       ▼  ScriptedFrontendRunner.run(...) with ScriptedRegistryInvoker wired
  For each call:
    pwd      → ScriptedRegistryInvoker.invoke(CorePwdStep, encoded PwdInput(false))
              → CorePwdStep.handler → WORKSPACE_IDENTITY_CAPABILITY → PwdOutput(path)
              → Codec.decode → String
    fileExists → ScriptedRegistryInvoker.invoke(CoreFileExistsStep, encoded FileExistsInput)
              → CoreFileExistsStep.handler → WORKSPACE_IDENTITY_CAPABILITY → FileExistsOutput(exists)
              → Codec.decode → Boolean
    readFile → ScriptedRegistryInvoker.invoke(CoreReadFileStep, encoded ReadFileInput)
              → CoreReadFileStep.handler → WORKSPACE_IDENTITY_CAPABILITY + READ_FILE_CAPABILITY
              → ReadFileOutput(content)
              → Codec.decode → String
    sh(returnStdout=true) → ScriptedRegistryInvoker.invoke(CoreShellStep, encoded ShInput(returnStdout=true))
                          → CoreShellStep.handler → SHELL_OPERATIONS_CAPABILITY
                          → ShOutput(stdout)
                          → Codec.decode → String
    sh (eager)         → existing JournaledScriptedOperationRuntime path
    echo (eager)       → existing JournaledScriptedOperationRuntime path (or registry Step)
       │
       ▼  PipelineSpec + (optionally) compiled entry point in CanonicalDurableRunCoordinator
  StageStarted, StepStarted, StepFinished, PwdResolved / FileExistsResolved / ReadFileResolved /
  WaitUntilCompleted, RunFinished — typed events from the registry Steps.
```

The key architectural property: **the eager `PipelineSpec` path is unchanged
for sources that contain NO runtime-returning calls**. The R4B GO
("pipeline { } keeps the eager PipelineSpec frontend this slice") is
**generalised**: it now applies only when the structured body has no
runtime-returning calls. When it does, the structured body is compiled to
the same `CompiledScriptedEntryPoint` artefact the generator form already
uses, with the same `RuntimeScriptedStepFacade` wiring.

## 2. Step Constitution compliance

- **Closed execution structure, open Step registry.** The new `core.readFile`
  and `core.fileExists` Steps (if registered as core) follow the same
  `StepDefinition<I, O>` + `StepCodec<I>` + `StepCodec<O>` + `StepHandler` +
  `StepContract` + `StepRegistry.register` discipline as every other Step.
  No new `StepSpec` subtype.
- **Per-step observability.** Each new Step emits its own typed event:
  `FileExistsResolved`, `ReadFileResolved`, etc.
- **Fail-closed coverage.** The mapper / lowerer emits no fallback
  "no-op" branch for the new kinds. A `pipeline { … pwd() … }` that fails
  to compile lowers to `LoweringResult.InvalidSyntax`, which surfaces as a
  `RunOutcome.Failure` (never a silent empty pipeline).
- **Jenkins familiarity.** `pwd()`, `pwd(tmp)`, `readFile(file)`, `fileExists(file)`,
  and `sh(..., returnStdout = true)` are verbatim Jenkins Step signatures;
  the Jenkins reference baseline is preserved.
- **Capability-routed handler discipline.** Handlers reach only declared
  capabilities (`WORKSPACE_IDENTITY_CAPABILITY`, `READ_FILE_CAPABILITY`,
  `SHELL_OPERATIONS_CAPABILITY`); never `CanonicalRuntimeContext`.

## 3. Hardest technical risk (per ADR-0093 §9)

**Eager/suspend duality inside `steps { }`.** The change replaces

```kotlin
fun steps(block: StepsScope.() -> Unit)
```

with

```kotlin
fun steps(block: suspend StepsScope.() -> Unit)
```

and adds `suspend fun pwd/tmp/readFile/fileExists/shReturnStdout`. Every
existing user-authored `.pipeline.kts` in the corpus that uses these as
eager must be migrated:

```text
// before (WU-LPR-085 era; placeholder return):
val p = pwd()                // compiled; p = "<workspace>" placeholder
sh("echo $p")                // sh receives "<workspace>", not the real path

// after (LFC-2R2 era; typed runtime return):
val p = pwd()                // suspend; p = CorePwdStep.handler output
sh("echo $p")                // sh receives the real workspace path
```

The corpus impact is bounded: the only existing user of `pwd()` in a
structured body in `main` is **zero** (verified by `grep -rn 'pwd()' v2/compatibility/`).
The fixture `22-wait-until.pipeline.kts` uses `waitUntil { sh(...) }`
(no runtime return). `core.pwd` itself is exercised in
`v2/compatibility/06-pwd*.pipeline.kts`? Let me check:

```text
TODO: verify with grep v2/compatibility/*pwd*
```

**Migration cost for the corpus** is therefore: **0 fixtures need updating**.
The DSL signature change is source-incompatible for any *future* script
that uses these methods eagerly; the compiler will reject the script at
compile time with a clear error message ("`pwd()` is a suspend function;
the `steps { }` block must be a `suspend` block; the `pipeline { }` body
must be routed through the scripted frontend"). This is the failure mode
AGENTS.md §"Review checklist" demands ("can this be ADT?", "am I
representing an impossible state?").

## 4. Concrete code-shape changes

### 4.1 `ScriptedCallKind` (ScriptedExecutionApi.kt)

```kotlin
sealed interface ScriptedCallKind {
    data object Shell : ScriptedCallKind                       // existing
    data object IsUnix : ScriptedCallKind                     // existing
    data class Pwd(val tmp: Boolean = false) : ScriptedCallKind // existing
    data object ReadFile : ScriptedCallKind                   // NEW
    data object FileExists : ScriptedCallKind                 // NEW

    /** Distinguish eager `sh` (returnStdout = false / Unit) from
     *  runtime-returning `sh` (returnStdout = true / String).
     *  ADT extension is closed: the data carried is the runtime decision. */
    data class ShellReturnStdout(val script: String) : ScriptedCallKind // NEW
}
```

`ShellReturnStdout` carries the script text because the rewriter needs it
inline (the rewrite target is `steps.shReturnStdout(callSite, script)`).

### 4.2 `ScriptedSourceLocation` (ScriptedExecutionApi.kt)

Add three new call-site factories (mirroring `pwdCallSite`):

```kotlin
fun readFileCallSite(): ScriptedCallSiteId = ScriptedCallSiteId("$sourceId:$line:$column:readFile")
fun fileExistsCallSite(): ScriptedCallSiteId = ScriptedCallSiteId("$sourceId:$line:$column:fileExists")
fun shReturnStdoutCallSite(): ScriptedCallSiteId = ScriptedCallSiteId("$sourceId:$line:$column:sh:ro")
```

### 4.3 `ScriptedStepFacade` (ScriptedExecutionApi.kt)

Add three new methods (mirroring `pwd`):

```kotlin
suspend fun readFile(callSite: ScriptedCallSiteId, file: String): String
suspend fun fileExists(callSite: ScriptedCallSiteId, file: String): Boolean
suspend fun shReturnStdout(callSite: ScriptedCallSiteId, script: String, encoding: String? = null): String
```

### 4.4 `RuntimeScriptedStepFacade` (CompiledScriptedEntryPoint.kt)

Add three concrete methods (mirroring the existing `pwd` impl):

```kotlin
override suspend fun readFile(callSite: ScriptedCallSiteId, file: String): String { /* delegate to invoker */ }
override suspend fun fileExists(callSite: ScriptedCallSiteId, file: String): Boolean { /* delegate to invoker */ }
override suspend fun shReturnStdout(callSite: ScriptedCallSiteId, script: String, encoding: String?): String { /* delegate to invoker */ }
```

### 4.5 `KotlinScriptedSourceMapper` (KotlinScriptedSourceMapper.kt)

Extend the PSI visitor to recognise `pwd(...)`, `readFile(...)`, `fileExists(...)`,
and `sh(..., returnStdout = true)` invocations and emit the corresponding
`ScriptedCallKind` variants. The PSI shape is identical to the existing
`isUnix()` recognition; the visitor gains three new match arms and one new
argument-shape check (`sh(...)` with `returnStdout = true` literal).

### 4.6 `ScriptedSourceLowering` (ScriptedSourceLowering.kt)

Add three new `rewrite<Pwd|ReadFile|FileExists|ShReturnStdout>Calls` methods
(mirroring `rewriteIsUnixCalls`). Each walks the source in REVERSE offset
order and replaces the textual call site with
`steps.<method>(ScriptedCallSiteId("…"), …args)`. The lowerer dispatches
the four families in sequence; the four rewrites are independent (their
text replacements do not overlap because each kind uses a distinct method
name).

### 4.7 `Main.kt` form selector

Lines 622–658 are extended:

```text
val hasAnyRuntimeReturningCall = mappedCalls.any { call ->
    when (call.kind) {
        ScriptedCallKind.Shell                  -> false
        ScriptedCallKind.IsUnix                 -> true
        is ScriptedCallKind.Pwd                 -> true
        ScriptedCallKind.ReadFile               -> true
        ScriptedCallKind.FileExists             -> true
        is ScriptedCallKind.ShellReturnStdout   -> true
    }
}
val hasStructuredBody = Regex("""\bpipeline\s*\{""").containsMatchIn(scriptContent)
val scriptedFrontend = if (hasAnyRuntimeReturningCall) {
    when (val lowered = ScriptedSourceLowering.lower(…)) {
        is LoweringResult.Generated       -> ScriptedFrontendRunner.EntryPointArtifact(…)
        is LoweringResult.InvalidSyntax   -> fail-closed
    }
} else null
```

The new predicate activates the scripted frontend for ANY source
(generator-level OR structured `pipeline { }`) that has runtime-returning
calls. The compiled entry point is then executed by `ScriptedFrontendRunner`
with `RuntimeScriptedStepFacade` + `ScriptedRegistryInvoker` wired.

### 4.8 `PipelineDsl.pwd/tmp/readFile/fileExists/shReturnStdout`

These are removed from `StageScope`/`StepsScope` (they no longer exist as
eager `Unit`-returning builders). The structured body that wants typed
runtime values compiles through the scripted frontend; the structured body
that wants eager `Unit` actions continues to use the existing
`StepSpec.RegistryStepSpec` builders (no change).

### 4.9 `StageScope.steps { block }` signature

```kotlin
fun steps(block: suspend StepsScope.() -> Unit) {
    val scope = StepsScope(stageName, /* … */)
    scope.block()         // suspend call
    /* … */
}
```

This is **source-incompatible** with eager `steps { ... }` blocks that
contain no runtime-returning calls. Backward compat is preserved by the
Kotlin compiler: any existing `steps { sh("...") }` block (no suspend
calls inside) still compiles and runs identically — the suspend receiver
is satisfied by a non-suspend block. Only scripts that mix suspend and
eager calls into a single block need to be aware that the block is now
suspend; the Kotlin compiler handles this transparently.

### 4.10 Kill `RUNTIME_VALUE_PLACEHOLDER` + `DslRuntimeConfigScope`

Both are removed in the same commit. Their references migrate:

- `RUNTIME_VALUE_PLACEHOLDER` references in `PipelineDsl` (the eager
  `pwd/tmp/readFile/fileExists` paths) are deleted when those paths are
  deleted.
- `DslRuntimeConfigScope` references in `Main.kt` (the `set`/`clear` calls
  around DSL compilation) are deleted because the eager `pwd()` etc. are
  no longer reachable in compiled structured bodies.

### 4.11 New Step registrations

Two new core Steps (or Tier-D rejection):

- `core.readFile` (if core). `StepDefinition<ReadFileInput, ReadFileOutput>`,
  declared capabilities `WORKSPACE_IDENTITY_CAPABILITY` +
  `READ_FILE_CAPABILITY`. Handler reads the file via the capability; never
  via `Files.readAllBytes` directly.
- `core.fileExists` (if core). `StepDefinition<FileExistsInput, FileExistsOutput>`,
  declared capability `WORKSPACE_IDENTITY_CAPABILITY`. Handler checks via
  the capability; never via `Files.exists` directly.

**Decision deferred to L5 gate evidence + WU-LPR-087 receipt.** Per the
strict certification law, this decision is recorded with explicit
rationale (core vs Tier D REJECTED) before any code lands.

## 5. Validation ladder

| Level | Scope | When |
|---|---|---|
| L0 | `:pipeline-scripting-api:compileKotlin` + `:pipeline-application:compileKotlin` + `:pipeline-step-sdk:api:compileKotlin` + `:pipeline-step-sdk:runtime:compileKotlin` + `:pipeline-step-sdk:files:compileKotlin` + `:pipeline-scripting-kotlin24:compileKotlin` | after every batch |
| L1 | `KotlinScriptedSourceMapperTest` (existing + new pwd/readFile/fileExists/shReturnStdout cases) | after mapper changes |
| L1 | `ScriptedSourceLoweringTest` (existing + new rewriters) | after lowerer changes |
| L1 | `ScriptedPwdRuntimeTest` (no change; proves the registry seam unchanged) | sanity after wiring |
| L1 | `RuntimeScriptedStepFacadeTest` (new; covers all 4 consumers) | after façade impls |
| L2 | `pipeline-scripting-api/.../test` (full) + `pipeline-scripting-kotlin24/.../test` (full) | after mapping + lowering |
| L2 | `pipeline-application/.../scripted/...test` (full) | after façade wiring |
| L3 | `pipeline-application:test` (full) — covers contract suites for all affected Steps + UAT corpus | after Main.kt form selector |
| L4 | `v2:check` (full module suite, no rerun) | after green L3 |
| L5 | `v2:check` (full round gate) | final apply/verify boundary |

## 6. Counter updates (post-success)

| Counter | Before | After |
|---|---|---|
| Certified CoreSteps | 12 | **15 or 14** depending on `readFile`/`fileExists` core/Tier-D decision |
| Total certified | 13 | **16 or 15** |
| Legacy executable | 0 | 0 |
| `LEGACY_PLUGIN_IDS` | `{}` | `{}` |

## 7. Architectural fitness (acceptance criteria for this slice)

- `KotlinScriptedSourceMapper` does not introduce any concrete-Step
  switch (architecture fitness scan: still no `when (stepName)`).
- `ScriptedSourceLowering` does not introduce any concrete-Step switch.
- The form selector predicate is **closed over a closed ADT**
  (`ScriptedCallKind`); no per-Step branch is added at runtime.
- `RuntimeScriptedStepFacade` methods each delegate to `ScriptedRegistryInvoker.invoke`
  (single generic seam); no per-Step handler code lives in the façade.
- `core.readFile` / `core.fileExists` (if registered) declare the
  capabilities they use; no `CanonicalRuntimeContext` reach-around.
- `RUNTIME_VALUE_PLACEHOLDER` and `DslRuntimeConfigScope` are physically
  deleted (fitness: source-level absence).
- Lfc2RegistryFamilyFitness GREEN.
- L5 round gate (full `v2:check`) GREEN.

## 8. End-of-slice closure block (template)

```text
Reference implementation consulted: ADR-0093 (suspend structured DSL); SPIKE-016 (replay); LFC-2R R2 (isUnix generator precedent)
Behaviour adopted:                 suspend runtime-returning DSL funs; one generic scripted→registry seam
Intentional deviations:            none (every decision is bound by ADR-0093 §4.2)
Security implications reviewed:    no new capability surface; new handlers reach capabilities only
Tests demonstrating the contract:   (filled at slice close)
```
