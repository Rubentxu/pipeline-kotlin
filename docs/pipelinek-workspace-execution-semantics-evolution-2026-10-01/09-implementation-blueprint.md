# 09 — Implementation blueprint

No es código obligatorio; es una propuesta de corte y ubicación para reducir improvisación durante apply.

## 1. Nuevos tipos — `pipeline-domain`

Paquete sugerido:

```text
v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/workspace/
```

Ficheros:

```text
WorkspaceRequest.kt
WorkspaceLease.kt
ExecutionLocation.kt
WorkspacePath.kt
WorkspacePathError.kt
```

Si `WorkspaceRequest` se considera estrictamente CLI/application y no dominio durable, puede vivir en application. `WorkspaceLease`/`ExecutionLocation` sí son conceptos runtime reutilizables.

## 2. Pure decisions — application/domain policy

```kotlin
fun decideWorkspaceRequest(input: WorkspaceCliInput): WorkspaceRequestDecision
fun deriveWorkingDirectory(parent: ExecutionLocation, raw: String): WorkspacePathDecision
fun resolveWorkspacePath(location: ExecutionLocation, request: PathRequest): WorkspacePathDecision
fun decideDestructiveOperation(lease: WorkspaceLease, target: Path, kind: DeleteKind): DeleteDecision
```

Todas testables sin filesystem salvo canonical symlink checks.

## 3. Port de adquisición

```kotlin
interface WorkspaceAllocator {
    fun acquire(plan: WorkspacePlan): WorkspaceAcquireResult
    fun release(lease: WorkspaceLease, outcome: RunOutcome): WorkspaceReleaseResult
}
```

El adaptador local encapsula el actual layout temporal.

## 4. `CanonicalRuntimeContext`

Añadir una authority explícita:

```kotlin
val executionLocation: ExecutionLocation
```

Durante transición se pueden mantener:

```kotlin
workspaceBase
shOptions
```

pero deben derivarse de la nueva authority o estar marcados como compatibility carrier; no fuentes independientes.

## 5. Capability bridge

Objetivo:

```kotlin
builder[EXECUTION_LOCATION_CAPABILITY] = context.executionLocation
```

Compatibility:

```kotlin
builder[WORKSPACE_IDENTITY_CAPABILITY] =
    WorkspaceIdentity(context.executionLocation.workspace.root.value)
```

Nunca:

```kotlin
WorkspaceIdentity(context.executionLocation.cwd.value)
```

## 6. Shell

Transición:

```kotlin
val effective = shOptions.copy(
    workspaceRoot = executionLocation.cwd.value,
    workingDirectory = null,
)
```

sólo dentro de un seam temporal si el executor SDK aún exige `workspaceRoot` como cwd.

Objetivo final:

```kotlin
ProcessExecutionOptions(cwd = executionLocation.cwd.value, ...)
```

El nombre `workspaceRoot` deja de viajar hasta `ProcessBuilder.directory` cuando realmente significa cwd.

## 7. WorkspaceOperationsAdapter

Hoy reconstruye `WorkspaceResolver` por método. Target:

```kotlin
class WorkspaceOperationsAdapter(
    private val location: ExecutionLocation,
    private val pathService: WorkspacePathService,
    ...
)
```

`write/read/exists` usan `CURRENT_DIRECTORY`.

Los SDK executors pueden evolucionar de:

```kotlin
workspaceResolver: (stageName, stageIndex) -> Path
```

a:

```kotlin
baseDirectory: Path
```

o a un port de path ya resuelto. Evitar que un executor de bajo nivel vuelva a decidir stage semantics.

## 8. `WorkspaceResolver`

No eliminarlo al principio.

Reencuadre:

- allocator de `Managed` stage directories;
- compatibility adapter para layouts históricos;
- NO autoridad general de current directory.

Al final su nombre podría cambiar a `ManagedWorkspaceAllocator/Layout` si ya no "resuelve" paths runtime de Steps.

## 9. `ProjectCheckoutDetector`

Fase temporal:

- puede permanecer para preservar el guard antiguo mientras no se haya propagado lease ownership.

Fase final:

- seguridad root destructiva usa `WorkspaceLease.Attached`;
- detector se elimina o queda como diagnóstico no autoritativo si existe otro uso real.

## 10. CLI

`PipelineCliConfig` objetivo:

```kotlin
data class PipelineCliConfig(
    ...,
    val workspace: String? = null,
    val isolated: Boolean = false,
)
```

Inmediatamente después de parse:

```kotlin
val workspaceDecision = decideWorkspaceRequest(
    WorkspaceCliInput(
        invocationDirectory = capturedInvocationDir,
        workspaceFlag = config.workspace,
        isolated = config.isolated,
    )
)
```

`Main`/composition root reciben ya una request/plan, no vuelven a interpretar flags.

## 11. Tests a crear primero

Nombres orientativos:

```text
WorkspaceRequestDecisionTest
ExecutionLocationInvariantTest
WorkspacePathResolverPropertyTest
DirExecutionLocationCharacterizationTest
WorkspaceCrossStepCoherenceTest
AttachedWorkspaceDestructiveSafetyTest
InstalledCliWorkspaceModeUatTest
WorkspaceReplaySemanticsTest
WorkspaceContextParallelIsolationTest
```

## 12. Property tests útiles

Para paths relativos generados:

```text
if resolve succeeds => result startsWith(workspace.root)
parent context is unchanged
nested derive composition is associative w.r.t normalized relative segments
rejecting escape performs zero filesystem effect
```

## 13. Instrumentación/eventos

No crear eventos por cada resolución de path salvo necesidad real. Sí puede ser útil enriquecer `RunStarted`/diagnóstico con datos no sensibles:

```text
workspaceMode = ATTACHED | MANAGED
workspaceRoot = path (según política de privacidad existente)
allocation = RUN_SHARED | STAGE_ISOLATED
```

No duplicar eventos Step si ya existe una autoridad por Step.

## 14. Documentación de usuario final

Actualizar:

- `docs/user/quickstart.md`;
- `docs/user/cli-reference.md`;
- `docs/user/configuration-and-workspace.md`;
- `docs/user/upgrading.md`;
- README examples;
- self-hosted `pipeline.kts` comments si mencionan `--workspace` como requisito.

Mensaje principal:

```text
Run here by default. Use --isolated when PipelineK should own a scratch workspace.
```
