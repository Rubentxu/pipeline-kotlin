# WU-093 `core.httpRequest` — Plan de gates G1..G4 (RP6-C)

> cycle SDDK: `p-1f3622e11c093341/rp6c-http-request`
> spec: `docs/v2/07-uat/SPEC_WU093_HTTP.md` · design: `docs/v2/07-uat/RP6C_HTTP_DESIGN.md`

## G1 — Contrato, ADTs y codecs

```text
CoreHttpInput          CoreHttpInput(url, method, customHeaders, body, contentType,
                     acceptType, validResponseCodes, timeoutSeconds, authentication)
HttpMethod             ADT cerrada: Get|Head|Post|Put|Delete|Options|Patch
HttpHeader             value classes HttpHeaderName/HttpHeaderValue + data class
StatusRange            ADT: Single(code) | Span(from,to), Span con require(from <= to)
CoreHttpResponse       data class : TypedStepOutput   <-- carrier, no data class pelado
HttpFailure            ADT: Unreachable | Expired
CredentialRejection    ADT: NotFound | KindUnsupported(found, supported)
CoreHttpWireCodec      autoridad única de wire (request + response)
httpIntentOf(input)    resolución PURA: url vacía, span invertido, body sin método que lo acepte
```

Mutaciones G1 (deben cortar):

- **M-http-1** — toda denegación de entrada como `Unanswerable` (ADT de entrada) → fila de
  round-trip que exige que cada rechazo produzca su propio discriminante.
- **M-http-2** — `Span(from, to)` acepta `from > to` → fila «un span invertido se rechaza en el
  decode, no en la interpretación».

## G2 — Puerto y adaptador de red

```text
HttpOperationsCapabilityKey   HTTP_OPERATIONS_CAPABILITY, interface HttpOperations,
                              data class NetworkPolicy { Denied | Allowed }
JdkHttpOperations             java.net.http.HttpClient, único punto de red
```

El adaptador se prueba **sin red** contra un `HttpClient` sustituible (fábrica inyectada) y con un
servidor real en la UAT. La duración se mide dentro del adaptador y vuelve en la respuesta, porque
quien hace el I/O es quien puede medirlo.

Mutaciones G2:

- **M-http-3** — el adaptador ignora `timeoutMs` y usa un timeout fijo → fila «un timeout declarado
  acota la espera».
- **M-http-4** — el truncado de body ignora `maxBodyBytes` → fila «un body mayor que el tope llega
  marcado como truncado y con el sha256 del cuerpo completo».

## G3 — Superficie DSL y autoridad única de wire

```text
StepSpec.HttpRequest         (sello nuevo en la jerarquía)
StageScope.httpRequest(...)  construcción declarativa
DslCompiledPipelineCompiler  is StepSpec.HttpRequest -> CoreHttpInput (dominio)
                              CoreHttpWireCodec.encode(...)  (encoding)
CoreHttpOutputCodec          autoridad de wire de la salida
BlockStepFlattener           rama (atómico: sin cuerpo, no recursión)
Lfc2HttpWireAuthorityFitnessTest
```

G3.5 anti-inline-wire, y G3.6 clasificación de la jerarquía sellada
(`SOURCE_ADDITIVE_WITH_EXHAUSTIVE_WHEN_RISK`, como RP6-A y RP6-B).

Mutación G3:

- **M-http-5** — el compilador escribe una clave del wire de `httpRequest` a mano → rojo en el
  fitness, por escaneo estructural como el de `core.input`.

## G4 — Exposición, routing y UAT

```text
ShOptions.networkPolicy       +1 campo, default DENIED (0 líneas en el coordinador)
CliParser / Main / CompositionRoot   --allow-network
CanonicalRuntimeCapabilityAccess    capacidad condicional
CoreStepRegistryFactory             registro (22 -> 23 keys)
CoreSleepRegistryPrimaryFitnessTest pin 22 -> 23
Effect.NETWORKS                     +1 valor en el enum del dominio
DomainEvent + 4 consumidores        4 eventos; pines 65 -> 69 (x2)
CanonicalRuntimeCapabilityAccessTest conjunto exacto + fila de fail-closed
```

Mutaciones G4 (las tres leyes que de verdad importan):

- **M-http-6** — la capacidad se expone aunque la política sea `Denied` → rojo en la fila de
  fail-closed: sin `--allow-network`, `core.httpRequest` se rechaza.
- **M-http-7** — el rango de status se interpreta como «no igual a» en vez de «dentro de» → rojo en
  la UAT de status aceptado/rechazado.
- **M-http-8** — la credencial se resuelve por tipo sin comprobar que sea `UsernamePassword` → rojo
  en la fila de `KindUnsupported`.

## UAT HF2 — `UatHttpRequestDurableTest`

Servidor HTTP real en localhost, CLI real forkado con `--allow-network`.

| Fila | Ley |
| --- | --- |
| HR-L1 | un GET con status dentro del rango termina verde y devuelve `status`, `headers` y `body` tipados |
| HR-L2 | un status fuera del rango falla, y el evento `HttpStatusRejected` dice cuál se esperaba |
| HR-L3 | host inexistente falla con `FailureKind.NETWORK`, no con `UNKNOWN` |
| HR-L4 | `timeoutSeconds` corta la espera con `FailureKind.TIMEOUT` |
| HR-L5 | **sin `--allow-network`, el Step se rechaza en admisión con un mensaje que nombra el flag** |
| HR-L6 | cabeceras propias llegan al servidor; el body llega con `contentType` traducido |
| HR-L7 | credencial `UsernamePassword` produce `Authorization` correcto; otro tipo produce el rechazo tipado |
| HR-L8 | un body mayor que `maxResponseBytes` llega truncado, con `bodyTruncated` y el sha256 del cuerpo completo |
| HR-L9 | un body con un token **no** aparece en el log de eventos |

## Criterios de cierre

- 9 filas UAT verdes contra la distribución real.
- 8 mutaciones medidas, cada una en ROJO sobre la fila que dice proteger, revertidas.
- Ratchet del coordinador en 552.
- `git sddk-align` / `--ack` / `sddk-close` por commit; commits Conventional Commits atómicos.
- Recibo + informe de verificación + release receipt con SHA exacto.
