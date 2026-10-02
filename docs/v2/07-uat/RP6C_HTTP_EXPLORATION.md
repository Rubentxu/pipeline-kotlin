# WU-093 `core.httpRequest` — Exploración (RP6-C)

> cycle SDDK: `p-1f3622e11c093341/rp6c-http-request` (fase `explore`)
> base: `504467d8` · work item: WU-093 (por abrir)
> decisiones de producto tomadas antes de escribir la spec:
> **destino = core**, **alcance = mínimo defendible**, **exposición = fail-closed con `--allow-network`**

## 1. Hechos del repositorio

### 1.1 No existe infraestructura de red. Ninguna.

Búsqueda de `HttpClient|java.net|okhttp|URLConnection|Socket|okio` sobre todo `v2/**/*.kt`: ninguna
clase de red. Las únicas apariciones de `java.net` son `URLClassLoader` para cargar JARs de plugin
(`MainRuntimeSupport.kt:45`) y `toURI()` de recursos. `core.httpRequest` será **el primer
consumidor de red del producto**.

### 1.2 El cliente HTTP no es una elección: lo impone un fitness

`FArch002ApplicationDependsInwardTest.kt:13-17` fija las únicas dependencias de terceros
permitidas en `pipeline-application`:

```kotlin
org.jetbrains.kotlin:kotlin-stdlib
org.jetbrains.kotlin:kotlin-stdlib-jdk8
org.jetbrains.kotlinx:kotlinx-coroutines-core
```

OkHttp, Ktor y Apache HttpClient **rompen el fitness** — y el propio test usa Ktor como fixture de
violación (línea 44-57). El toolchain es `jvmToolchain(21)`, luego **`java.net.http.HttpClient` del
JDK es el único cliente posible**. No es preferência de estilo: es la única puerta que queda.

### 1.3 No hay tipo de cabecera

No existe ningún tipo de cabecera HTTP ni ningún par clave-valor ordenado genérico. Precedentes
reales:

| Precedente | Forma | Dónde |
| --- | --- | --- |
| Pares ordenados tipados | `List<Pair<String, SecretHandle>>` | `EnvironmentComposer.kt:165` |
| Contrapropuesta del repo | `Map<String, SecretHandle>` en `ShOptions.env` | `ShOptions.kt:38` |
| Lo que la DSL hace hoy | `overrides: List<String>` (`"K=V"`, sin tipar) | `StepSpec.kt:204` |

`Map<String, Any?>` está prohibido por AGENTS.md §STEP CONSTITUTION, y un mapa no conserva el orden
ni los duplicados de una cabecera. `httpRequest` necesita un valor nuevo, no un molde heredado — y la
estructura de la spec tiene que decidirlo, no el adaptador.

### 1.4 No hay temporizador ejecutable para Steps sin proceso hijo

`ExecutionBudget.bound()` (`ExecutionBudgetCapabilityKey.kt:44-48`) es una intersección **pura y
combinatoria**. `core.lock` la consume (`CoreLockStep.kt:277-283`) sobre un bucle de sondeo propio.
La única temporización ejecutable del repo es el watchdog del subproceso (`DurableTaskRuntime`).

Además **no hay `Clock` inyectado**: los adaptadores llaman `Instant.now()` directamente
(`WorkspaceOperationsAdapter.kt:138,162,182`). Consecuencia: sin un reloj detrás de un puerto, un
timeout de red no es testeable sin red real.

### 1.5 El molde para una capacidad condicional ya existe

`CanonicalRuntimeCapabilityAccess` (`CanonicalRuntimeCapabilityAccess.kt:72-81`) declara
capacidades condicionales como **parámetros de constructor con default**:

```kotlin
open class CanonicalRuntimeCapabilityAccess(
    context: CanonicalRuntimeContext,
    private val milestoneStateStore: MilestoneStateStore? = null,
    private val artifactIndex: ArtifactIndexCapability? = null,
) : StepCapabilityAccess
```

y las registra condicionalmente en `buildProvided`. La exposición de red debe seguir esa forma, no
inventar una nueva.

### 1.6 Restricción dura: el coordinador tiene ratchet de 552 líneas

`CoordinatorGrowthGuardrailTest.kt:47` fija `maxCoordinatorLines = 552L`, y `CanonicalDurableRunCoordinator.kt`
tiene hoy exactamente 552 líneas. Un flag que llegue al bridge **por parámetros nombrados del
coordinador** lo rompería.

Dato relevante: el coordinador **no nombra ningún campo de `CanonicalRuntimeContext`** (0
coincidencias de `context.<campo>`), y `StepDispatchEngine:150` construye el contexto con campos
nombrados. Por eso el camino del flag está acotado y hay que resolverlo en diseño, no aquí.

### 1.7 Lo que NO hay que tocar

| Fichero | Por qué |
| --- | --- |
| `CanonicalDurableRunCoordinator.kt` (552) | ratchet; el patrón registry lo evita |
| `CanonicalCoreStepDecoder.kt:59` | `LEGACY_PLUGIN_IDS` vacío y **convergado** (`LegacyResidualSnapshot.kt:79,124`: residual físico vacío, `registryPrimaryPendingRemoval = null`) |
| `CanonicalCoreStepMetadata.kt` | tabla legacy; un Step nacido en registry no añade fila |
| `CanonicalNodeDispatcher.kt` | AGENTS.md prohíbe un caso de Step concreto |
| `pipeline-domain/.../domain/directive/` | zona con imports prohibidos y fitness propio |

`Effect` es un `enum` de 4 valores (`READ_ONLY`, `EXECUTES_SUBPROCESS`, `ABORTS_PIPELINE`,
`WRITES_WORKSPACE`) y **no hay ningún `when` exhaustivo sobre él**: añadir un valor es
source-compatible. Precedente de reusar la variante más cercana: `CoreArchiveArtifactsStep.kt:47-49`
usa `WRITES_WORKSPACE` con el comentario *"the ADT has no ARTIFACTS_WRITE variant"*.

## 2. Superficie real de Jenkins `httpRequest`

### 2.1 Corrección de premisa

`httpRequest` **ya no pertenece a `durable-task-step`**. El árbol de
`jenkinsci/workflow-durable-task-step-plugin` no contiene ningún fichero `HttpRequest*`. El
propietario es **`jenkinsci/http-request-plugin`** (`jenkins.plugins.http_request`, short name
`http_request`).

Consecuencia: una lista de parámetros-diffundida sobre `durable-task-step` describe una superficie
que **ya no existe**. En particular **no existen** `failOnStatusCode`, `customBands`,
`responseCode`, `sslVerify`, `wrapResponse`, `retry` ni `retryableStatusCodes`. Esos pertenecen al
plugin legacy previo a la extracción de 2019.

### 2.2 Parámetros vigentes

Obligatorio: `url`. Opcionales: `httpMode` (método; default `GET`), `requestBody`,
`customHeaders`, `contentType`, `acceptType`, `validResponseCodes` (default `"100:399"`),
`validResponseContent`, `responseHandle`, `outputFile`, `authentication`, `proxyAuthentication`,
`httpProxy`, `timeout` (segundos; **0 = sin timeout**), `ignoreSslErrors`, `useNtlm`,
`useSystemProperties`, `consoleLogResponseBody`, `quiet`, `formData`, `uploadFile`,
`multipartName`, `wrapAsMultipart`.

Enums: `HttpMode = GET|HEAD|POST|PUT|DELETE|OPTIONS|PATCH|MKCOL`;
`ResponseHandle = NONE|LEAVE_OPEN|STRING`;
`MimeType = NOT_SET|TEXT_HTML|TEXT_PLAIN|APPLICATION_FORM|APPLICATION_FORM_DATA|APPLICATION_JSON|…`.

### 2.3 Outcome

| Situación | Resultado |
| --- | --- |
| Status dentro de `validResponseCodes` | SUCCESS |
| Status fuera de rango | **FAILURE** (sin excepción; `AbortException` en Jenkins) |
| `validResponseContent` no contienen el body | FAILURE |
| `UnknownHostException` | FAILURE |
| Timeout / conexión fallida | FAILURE |
| `validResponseCodes` malformado | `IllegalArgumentException` **después de enviar la petición** |

**No existe UNSTABLE.** No hay bandas ni modo permisivo: un status inesperado siempre falla. Para
aceptar cualquier status, el mecanismo es el rango `100:599`.

Valor de retorno: `status`, `content` (String), `headers` (`Map<String,List<String>>` case-insensitive),
`charset`.

### 2.4 Hechos que cambian el diseño

1. **Sin reintentos.** El plugin los **desactiva** explícitamente
   (`disableAutomaticRetries()`). En Jenkins se reintenta envolviendo en el Step `retry`, que es un
   bloque, no un parámetro.
2. **`timeout = 0` significa sin timeout**, y no es el default de la librería (5 min): Jenkins lo
   pone a `DISABLED` a propósito. Nuestro default debe ser distinto y **declarado**.
3. **`validResponseCodes` se valida tarde**, con la petición ya emitida. Nuestra implementación
   debe validarlo en decode/prepare. Es una mejora legítima y declarable.
4. **Charset no determinista** si la respuesta no lo declara: Jenkins usa `Charset.defaultCharset()`.
   Heredarlo haría el resultado no reproducible entre máquinas. Fijar UTF-8 por defecto.
5. **`responseHandle` se sobrescribe en silencio** a `STRING` si `consoleLogResponseBody` es true o
   `validResponseContent` no está vacío.
6. **Sólo dos tipos de credencial funcionan** (username/password y certificate). Los demás dan un
   mensaje falso (*"doesn't exist anymore"*). No replicar ese diagnóstico.
7. **Logs de Jenkins** que no tenemos: `Launcher`/`VirtualChannel`, `TaskListener`, `Item`/Job ACL,
   `HttpRequestGlobalConfig` (XML en `$JENKINS_HOME`), `passBuildParameters`, `AbortException`.

## 3. Huecos reales que el bloque debe cerrar

| # | Hueco | Consecuencia |
| --- | --- | --- |
| 1 | No hay puerto ni adaptador de red | hay que crearlos; `HttpOperations` detrás de `HTTP_OPERATIONS_CAPABILITY` |
| 2 | `Effect` no tiene variante de red | añadir `Effect.NETWORKS` (source-compatible) o reusar la más cercana |
| 3 | No hay tipo de cabecera | hay que decidir la forma tipada y ordenada, sin `Map<String,Any?>` |
| 4 | No hay reloj inyectado | sin él, los timeouts no son testeables; hay que abrir el seam |
| 5 | No hay traducción presupuesto → timeout de cliente | `ExecutionBudget.bound()` es puro; falta el aplicador |
| 6 | No hay política de exposición de red | hay que añadir `--allow-network` sin romper el ratchet de 552 |
| 7 | La credencial llega por `CredentialProvider`, fuera del bridge | el Step nombra un id; el adaptador resuelve |

## 4. Ficheros que una variante nueva de evento obliga a tocar

Descubierto por `Rp030EventCodecsConnascenceFitnessTest.kt` (F1-F4):

1. `SequenceAssigner.withSequence`
2. `SqliteEventStore.appendAssigned` (`SqliteEventStore.kt:288`)
3. `InMemoryEventStore.appendAssigned` (`InMemoryEventStore.kt:19`)
4. `EnvelopeProjector.subjectOf` (`EnvelopeProjector.kt:158`)
5. `JsonEventLog.decodeEvent` (`JsonEventLog.kt:95`)

más los pines de conteo `FArchL7DomainEventExhaustivityTest:118` (65) y
`DomainEventRoundTripTest:341` (65), y la fila de round-trip.

## 5. Lo que esta exploración NO decide

- La forma del ADT de entrada y salida (spec).
- Si `Effect.NETWORKS` se añade o se reusa otra variante (spec).
- La forma tipada de las cabeceras (spec).
- El camino exacto del flag por el spine sin romper el ratchet (design).
- Qué credenciales se soportan en el alcance mínimo (spec).
