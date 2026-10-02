# WU-093 `core.httpRequest` — Especificación (RP6-C)

> cycle SDDK: `p-1f3622e11c093341/rp6c-http-request` (fase `specify`)
> exploración: `docs/v2/07-uat/RP6C_HTTP_EXPLORATION.md`
> upstream: **`jenkinsci/http-request-plugin`** — NO `durable-task-step` (§2.1 de la exploración)

## 1. Superficie

```kotlin
httpRequest(
    url: String,
    method: HttpMethod = HttpMethod.Get,
    customHeaders: List<HttpHeader> = emptyList(),
    body: String? = null,
    contentType: String? = null,
    acceptType: String? = null,
    validResponseCodes: List<StatusRange> = listOf(StatusRange.Span(100, 399)),
    timeoutSeconds: Int = 30,
    authentication: String? = null,
)
```

### 1.1 Qué NO está en WU-093, y por qué no es silencio

Cada exclusión se declara, porque un parámetro ausente que nadie menciona se lee como un
olvido, y un pipeline Jenkins que lo usa falla sin explicación.

| Parámetro Jenkins | Motivo |
| --- | --- |
| `outputFile` | escribe en el workspace: otro efecto, otro bloque, otro `Effect` |
| `uploadFile`, `formData`, `multipartName`, `wrapAsMultipart` | multipart es una superficie propia, no un añadido |
| `httpProxy`, `proxyAuthentication` | segundo camino de credenciales; requiere su propio modelo de política |
| `useNtlm` | negociación con estado, sin justificación de neutralidad |
| `useSystemProperties` | lee estado ambiental de la JVM; contradice «sin estado ambiente» |
| `validResponseContent` | matching sobre el body duplica la validación que ya da el status |
| `responseHandle`, `consoleLogResponseBody`, `quiet` | los tres son superficie de log de Jenkins, y uno de ellos se sobrescribe en silencio (§2.4) |
| `passBuildParameters` | sólo existe en el Builder, y filtrar parámetros del build a una URL es exfiltración |

## 2. Decisiones

### D1 — Las cabeceras son un valor tipado y ordenado, no un mapa

```kotlin
@JvmInline value class HttpHeaderName(val value: String)
@JvmInline value class HttpHeaderValue(val value: String)
data class HttpHeader(val name: HttpHeaderName, val value: HttpHeaderValue)
```

`List<HttpHeader>`, no `Map<String, String>` ni `Map<String, Any?>`. Dos razones que un mapa no
resuelve:

1. **Orden y duplicados son semántica.** HTTP permite repetir cabeceras (`Set-Cookie` es el caso
   canónico) y un `Map` sobrescribe. Jenkins usa `Map<String, List<String>>`; una lista de
   cabecera conserva ambos sin inventar un multimapa.
2. **Los value classes impiden el par mal formado.** `HttpHeader("X", "Y")` no compila; hay que
   decir `HttpHeader(HttpHeaderName("X"), HttpHeaderValue("Y"))`. El error de orden de argumentos
   deja de ser representable.

### D2 — El método es una ADT cerrada, no un `String`

```kotlin
sealed interface HttpMethod {
    data object Get : HttpMethod
    data object Head : HttpMethod
    data object Post : HttpMethod
    data object Put : HttpMethod
    data object Delete : HttpMethod
    data object Options : HttpMethod
    data object Patch : HttpMethod
}
```

`MKCOL` (WebDAV) queda fuera: sin uso conocido en pipelines, y cada caso de una ADT cerrada es
una rama que el motor tiene que considerar para siempre.

### D3 — `validResponseCodes` es un tipo que se valida al decodificar, no un `String` que se parsea tarde

```kotlin
sealed interface StatusRange {
    data class Single(val code: Int) : StatusRange
    data class Span(val from: Int, val to: Int) : StatusRange {
        init { require(from <= to) { "a status span must not run backwards: $from..$to" } }
    }
}
```

**Por qué esto es una desviación deliberada del upstream:** Jenkins acepta el string
`"100:399,404"` y lo parsea *después de enviar la petición*, lanzando `IllegalArgumentException`
con la petición ya en vuelo (`HttpRequest.java:552-589`). Aquí el span se valida en
`httpIntentOf(input)`, o sea una función pura llamada en el decode: una configuración inválida es
un **error de preparación**, no un error que llega al mundo después de haber escrito en el mundo.

Aceptamos la forma del string de Jenkins en el borde (`"100:399,404"` → la lista de arriba) para
que un pipeline existente no tenga que reescribirse, pero el valor interno es un tipo.

### D4 — El timeout tiene default declarado; `0` significa «sin timeout», como en Jenkins

`timeoutSeconds: Int = 30`. Jenkins usa `0` como «sin timeout» y **desactiva explícitamente** el
default de 5 minutos de su cliente; aquí se declara 30s porque una petición sin cota bloquea el
run indefinidamente, y el autor que quiera ese comportamiento lo pide con `0` a propósito.

El presupuesto de ejecución acota el valor efectivo (§4).

### D5 — `ReplayPolicy.NEVER`

Una petición HTTP puede tener efecto externo —un `POST` a una API que cobra, o que envía— y el
runtime no puede saberlo leyendo el payload. La política se declara por descriptor, no por
entrada, así que no puede depender de `method`. `NEVER` es la única declaración honesta: un resume
no repite una petición a ciegas.

El reintento sigue siendo del autor, con el Step `retry` de bloque, que sí re-ejecuta porque
declara esa política él mismo. Es composición, no un flag más.

### D6 — `RecoveryPolicy.None`

Una petición en vuelo no es reenganchable: no hay proceso externo que reconciliar ni directorio de
control desde el que reconstruir la respuesta. Es el mismo valor que `core.input`, que tampoco
reencola su espera.

### D7 — `Effect.NETWORKS` es un valor nuevo, no una variante reusada

`Effect` tiene hoy `READ_ONLY`, `EXECUTES_SUBPROCESS`, `ABORTS_PIPELINE`, `WRITES_WORKSPACE`. Una
petición HTTP no es ninguna: no es lectura (hace I/O), no es subproceso (no hay proceso), y sólo
es escritura de workspace si se pide `outputFile`, que no está en alcance.

Existe el precedente de reusar la variante más cercana (`CoreArchiveArtifactsStep.kt:47-49` usa
`WRITES_WORKSPACE` con el comentario *"the ADT has no ARTIFACTS_WRITE variant"*). No lo repetimos:
`Effect` es un `enum` **sin ningún `when` exhaustivo** sobre él, así que añadir un valor es
source-compatible, y un descriptor que declara un efecto que no exerce es el mismo tipo de mentira
que se corrigió en `core.input` durante RP6-B (§6.1 de su recibo).

### D8 — Credenciales: sólo `UsernamePassword`, y el rechazo es tipado

`authentication: String?` es un `CredentialsId`, nunca material secreto. El adaptador resuelve con
`CredentialProvider.resolveToCredential(id)` y **sólo** acepta la variante `UsernamePassword`
(Basic). Cualquier otro tipo de `Credential` produce un fallo tipado que dice qué tipo llegó y
cuáles se soportan.

Se copia aquí, explícitamente, una decisión de Jenkins que **no** replicamos: upstream da a un
tipo de credencial no soportado el mensaje *«Authentication 'X' doesn't exist anymore»*, que
miente sobre la causa. Un diagnóstico que culpa al operario cuando el problema es de alcance es
peor que no diagnosticar.

### D9 — `contentType` / `acceptType` aceptan el nombre del enum de Jenkins y también un MIME literal

`contentType: String?`. Si el string es uno de los nombres de `MimeType` de Jenkins
(`APPLICATION_JSON`, `TEXT_PLAIN`, …) se traduce a su valor real; si no, se usa tal cual. Un
pipeline Jenkins funciona sin cambios y no se pierde la capacidad de enviar cualquier MIME.

### D10 — El charset nunca es el de la plataforma

Si la respuesta no declara charset, usamos **UTF-8**. Jenkins usa `Charset.defaultCharset()`
(`HttpRequestExecution.java:71-72`), lo que hace que el `content` del mismo pipeline difiera entre
máquinas. Un resultado no reproducible no puede estar en un sobre durable.

### D11 — La red está ausente por defecto y su ausencia se explica

La capacidad `HTTP_OPERATIONS_CAPABILITY` se expone **sólo** con `--allow-network`. Sin el flag, la
admisión falla cerrada, y el mensaje de admisión dice qué flag falta y por qué — no «capability
unavailable», que es cierto y no dice nada.

Es la diferencia entre este Step y `LOCK_COORDINATION_CAPABILITY` o `INPUT_DECISIONS_CAPABILITY`,
que también son condicionales pero a un ancla de ejecución. Aquí el condicional es una **decisión de
seguridad**, y su ausencia necesita una explicación.

### D12 — El cuerpo se acota, y el recorte es observable

Una respuesta puede ser de cientos de megas, y copiarla entera al journal no es una decisión que
pueda tomarla un autor sin saberlo. `maxResponseBytes` con default **1 MiB**: por encima, `body`
viene truncado, `bodyTruncated = true`, y `bodySha256` es el del cuerpo **completo** — el hash
sigue siendo la identidad del contenido real aunque no se materialice entero.

## 3. ADTs

```kotlin
// salida
data class HttpResponseValue(
    val url: String,
    val method: HttpMethod,
    val status: Int,
    val headers: List<HttpHeader>,
    val body: String,
    val bodySha256: String,
    val bodySizeBytes: Long,
    val bodyTruncated: Boolean,
) : TypedStepOutput
```

`TypedStepOutput` no es opcional: `httpRequest` puede terminar sin cuerpo, y sin carrier el
boundary proyecta `Success` por defecto. Es el mismo defecto que WU-092 encontró y corrigió.

```kotlin
// fallo de transporte: cada caso es un hecho distinto
sealed interface HttpFailure {
    data class Unreachable(val diagnostic: String) : HttpFailure   // host, DNS, conexión
    data class Expired(val afterMs: Long) : HttpFailure             // timeout de transporte
    data class CredentialRejected(val reason: CredentialRejection) : HttpFailure
}

sealed interface CredentialRejection {
    data class NotFound(val id: String) : CredentialRejection
    data class KindUnsupported(val found: String, val supported: List<String>) : CredentialRejection
}
```

Un `status` inesperado **no** es un `HttpFailure`: la petición funcionó. Es un resultado con un
status que el autor no aceptó, y su `FailureKind` es `USER` (la política la escribió el autor), no
`NETWORK` (la red hizo lo que se le pidió).

## 4. Outcome y capacidades

| Situación | Outcome | `FailureKind` |
| --- | --- | --- |
| status dentro de los rangos | `Success` con `HttpResponseValue` | — |
| status fuera de los rangos | `Failure` | `USER` |
| host inexistente / conexión rechazada | `Failure` | `NETWORK` |
| timeout de transporte o presupuesto agotado | `Failure` | `TIMEOUT` |
| credencial ausente o de tipo no soportado | `Failure` | `USER` |
| `--allow-network` ausente | admisión fail-closed, sin handler | `PLUGIN` |

Capacidades declaradas, y sólo éstas:

```text
HTTP_OPERATIONS_CAPABILITY   puerto de transporte (único seam de red)
EVENT_SINK_CAPABILITY        eventos de la petición
EXECUTION_BUDGET_CAPABILITY  acota el timeout efectivo
```

Ausencia de cualquiera ⇒ admisión fail-closed antes del handler, exactamente como ADR-0069.

## 5. Eventos

```kotlin
HttpRequestStarted(url, method, headerCount)   // ni cabeceras, ni body, ni credenciales
HttpResponseReceived(url, status, durationMs)  // ni cabeceras, ni body
HttpStatusRejected(url, status, accepted)      // el status y qué se esperaba
HttpRequestFailed(url, reason)                 // transporte, no política
```

**Ningún payload lleva el cuerpo ni los valores de las cabeceras.** La razón no es
discrecional: `INV-L6-EVT-001` (recogida en `DomainEvent.kt:510`) restringe los payloads a
metadatos porque un cuerpo de respuesta puede contener un token y una cabecera puede ser
`Authorization`. `HttpRequestStarted` lleva `headerCount` y no las cabeceras por el mismo motivo.

## 6. Criterios de salida

1. Contrato de dominio + codecs con round-trip, incluido el rechazo de un span invertido y de una
   URL vacía.
2. `HttpOperations` con adaptador `java.net.http`: probado **sin red** (puerto sustituido) y con
   un servidor real en UAT.
3. Registro en `CoreStepRegistryFactory` sin bypass de admisión; `Effect.NETWORKS` declarado.
4. `--allow-network` fail-closed: sin el flag, `core.httpRequest` se rechaza con un mensaje que
   nombra el flag.
5. DSL `httpRequest(...)` con compilación positiva y negativa, `CoreHttpWireCodec` como autoridad
   única de wire y fitness que lo haga cumplir.
6. UAT HF2 contra la distribución real con un servidor HTTP real: status aceptado, status
   rechazado, host inexistente, timeout, credenciales, body truncado, y las nueve filas duras.
7. Mutaciones discriminantes medidas sobre las leyes que de verdad importan: rango mal
   interpretado, timeout ignorado, y **exposición fail-closed**.
8. Ratchet del coordinador intacto en 552.
9. Recibo con SHA exacto.

## 7. Lo que esta spec NO decide

- Si `outputFile` merece su propia WU (afecta a `Effect` y a `WRITES_WORKSPACE`).
- Una política de allowlist de hosts: `--allow-network` es binario y basta para fail-closed, pero
  una allowlist es un evolutivo con su propio parsing, subdominios, puertos y redirecciones.
- NTLM, proxy y multipart: los tres necesitan modelo propio, no un parámetro más.
- Si el transporte debe soportar streaming en vez de bufferizar: `responseHandle=LEAVE_OPEN` es
  incompatible con nuestro modelo durable (el stream no es serializable), y no hay término medio
  barato.
