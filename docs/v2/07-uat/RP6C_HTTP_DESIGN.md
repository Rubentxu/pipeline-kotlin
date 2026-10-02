# WU-093 `core.httpRequest` — Diseño (RP6-C)

> cycle SDDK: `p-1f3622e11c093341/rp6c-http-request`
> spec: `docs/v2/07-uat/SPEC_WU093_HTTP.md` · exploración: `docs/v2/07-uat/RP6C_HTTP_EXPLORATION.md`
> base: `504467d8`

## 1. Mapa de seams

```text
pipeline.kts
  → StageScope.httpRequest(...)                    [scripting-api, declarativo]
  → StepSpec.HttpRequest                           [IR sellado]
  → DslCompiledPipelineCompiler                    [StepSpec → CoreHttpInput,Known domain]
  → CoreHttpWireCodec                              [autoridad única de wire]
  → BlockStepNode? NO → StepNode(core.httpRequest) [atómico, sin cuerpo]
  → CoreStepRegistryFactory                        [registro sin bypass]
  → RegistryExecutionPreparation                   [admisión fail-closed]
  → CoreHttpStep.handler
      ├── HTTP_OPERATIONS_CAPABILITY → HttpOperations   [puerto]
      ├── EVENT_SINK_CAPABILITY                        → eventos
      └── EXECUTION_BUDGET_CAPABILITY                  → acota el timeout
  → JdkHttpOperations                              [ÚNICO punto de red]
  → java.net.http.HttpClient                       [forzado por FArch002]
```

**Un solo seam de red.** `JdkHttpOperations` es la única clase del repositorio que abre un
socket, igual que `FileLockCoordinator` es la única que toma un lock POSIX y
`WorkspaceOperationsAdapter` la única que emite `FileWritten`.

## 2. La decisión de diseño que no es obvia: por dónde viaja `--allow-network`

### 2.1 El problema

La política de exposición debe llegar a `CanonicalRuntimeCapabilityAccess`, que decide si registra
`HTTP_OPERATIONS_CAPABILITY`. El camino evidente —un campo nuevo en `CanonicalRuntimeContext`— pasa
por el constructor del coordinador, y `CanonicalDurableRunCoordinator` tiene un ratchet de **552
líneas** que `CoordinatorGrowthGuardrailTest:47` verifica como máximo. Añadir un campo al
constructor primario son 2 líneas más (la del campo, y la del constructor delegante `CoordinatorCaps`
que enumera los campos uno a uno): 554 > 552, rojo.

### 2.2 La solución: `ShOptions`, con precedente exacto

`ShOptions` **ya transporta una política de entorno de la ejecución**: `sandbox: SandboxConfig`
(`ShOptions.kt:39`). Se configura desde el CLI (`--sandbox-profile`), no tiene superficie DSL, y su
KDoc explica por qué existe:

> *"This field exists because ownership cannot be recovered downstream"* (campo `workspaceOwnership`)

Ese es exactamente el argumento de un permiso de red: si el valor se pierde al proyectar al scope,
la ejecución se queda sin red sin que nadie lo decida.

Añadir `networkPolicy: NetworkPolicy = NetworkPolicy.DENIED` a `ShOptions` cuesta **cero líneas en el
coordinador**, porque `ShOptions` viaja entero de `CompositionRoot` al engine
(`shOptions = ShOptions(...)`) y el contexto de runtime ya lo lleva.

**La proyección de scope lo preserva.** `StageNode.projectShellOptions`
(`CanonicalStructuralDecisions.kt:239-255`) construye con `base.copy(env = …, timeoutMs = …)`, que
conserva los campos no nombrados. Un campo nuevo no puede perderse ahí — y eso se fija con un test,
no con una confianza.

### 2.3 Por qué no es una mezclilla

`ShOptions` no es «opciones de shell»: es **el contexto de entorno proyectado al scope**. Ya lleva
`workspaceRoot`, `workingDirectory`, `timeoutMs`, `env`, `sandbox` y `workspaceOwnership` — cinco de
los seis no son de shell. Un permiso de red pertenece a la misma categoría que un perfil de sandbox:
ambos los decide el operador al lanzar, ninguno es tocable desde el script, y ambos se pierden igual
si el transporte no los lleva.

### 2.4 Seguridad del valor por defecto

`NetworkPolicy.DENIED` es el default del data class, así que **omitir el flag en el constructor es
fail-closed por construcción**, no por una condición en el bridge. Un `copy()` accidental no puede
reactivar la red, y ningún `copy()` la desactiva sin querer, porque nadie más nombra el campo.

## 3. Piezas nuevas

| Fichero | Contenido |
| --- | --- |
| `HttpOperationsCapabilityKey.kt` | `HTTP_OPERATIONS_CAPABILITY`, `interface HttpOperations`, `NetworkPolicy` |
| `CoreHttpInput.kt` | `CoreHttpInput`, `HttpMethod`, `HttpHeader`, `StatusRange`, `HttpIntent` y su resolución pura |
| `CoreHttpWireCodec.kt` | autoridad única de wire (request + respuesta) |
| `CoreHttpOutput.kt` | `CoreHttpResponse : TypedStepOutput`, `HttpFailure`, `CredentialRejection` |
| `JdkHttpOperations.kt` | **único** punto de red; implementa `HttpOperations` con `java.net.http` |
| `CoreHttpStep.kt` | descriptor, codecs, handler, eventos, contrato |
| `CoreHttpInputCodec`-fitness | `Lfc2HttpWireAuthorityFitnessTest` |

Modificaciones fuera de los nuevos:

| Fichero | Cambio |
| --- | --- |
| `ShOptions.kt` | `+1 campo` (`networkPolicy`, default `DENIED`) |
| `CanonicalRuntimeCapabilityAccess.kt` | registro condicional de la capacidad |
| `CoreStepRegistryFactory.kt` | `CoreHttpStep.registerInto(this)` |
| `CliParser.kt` / `Main.kt` / `CompositionRoot.kt` | `--allow-network` → `NetworkPolicy` |
| `Effect.kt` (domain) | `+1 valor`: `NETWORKS` |
| `DomainEvent.kt` + 5 consumidores | 4 eventos nuevos |
| `StepSpec.kt` / `StageScopeBuilders.kt` / `BlockStepFlattener.kt` | superficie DSL |
| `ExecutionBoundaryFactory` / `DurableTypedInputPreparation` | sin cambios: leen `context.shOptions` |

## 4. `HttpOperations`: el puerto

```kotlin
interface HttpOperations {
    suspend fun send(request: HttpSendRequest): HttpSendOutcome
}

data class HttpSendRequest(
    val url: String,
    val method: HttpMethod,
    val headers: List<HttpHeader>,
    val body: String?,
    val timeoutMs: Long?,
    val authorization: Authorization?,
    val maxBodyBytes: Long,
)
```

`HttpSendOutcome` es una ADT cerrada — `Answered` o `Failed` — porque una caída de red no es una
excepción: es un resultado que el Step debe clasificar en un `FailureKind` concreto.

**La autorización viaja al puerto como valor ya resuelto**, no como `CredentialsId`: el handler no
debe poder pedir un secreto, sólo puede pasar lo que el adaptador ya decidió enviar. Es la
distinción entre *atribución* y *autorización* aplicada al mismo caso que `core.input` (§3.5 de su
spec).

## 5. Por qué `Effect.NETWORKS` y no reusar

Un `enum` sin ningún `when` exhaustivo, así que el valor nuevo es source-compatible y no obliga a
tocar ninguna rama existente. Reusar `WRITES_WORKSPACE` obligaría a mentir en el descriptor de un
Step que no escribe en el workspace; reusar `EXECUTES_SUBPROCESS` obligaría a mentir de otra forma,
porque no hay subproceso.

## 6. Fixtures de test sin red

`HttpOperations` es sustituible en `CoreHttpStepContractTest` (igual que `FileInputDecisions` con su
`root`), de modo que el contrato, los ADTs y los eventos se prueban **sin socket**. La UAT HF2 sí
levanta un servidor HTTP real en localhost y usa la distribución real.

## 7. Lo que este diseño NO hace

- **No toca el coordinador.** Ratchet verificado en 552 antes y después.
- **No añade dependencias.** `FArch002ApplicationDependsInwardTest` sólo admite stdlib y coroutines.
- **No amplía `StepHandlerContext`.** El handler ve `capabilities`, no un contexto de red.
- **No introduce `Clock` en el puerto.** Quien hace el I/O mide su propia duración y la devuelve;
  es el mismo reparto que `ShExecution` con su `durationMs`.
- **No decide allowlist de hosts.** `--allow-network` es binario; una allowlist es otro bloque.
