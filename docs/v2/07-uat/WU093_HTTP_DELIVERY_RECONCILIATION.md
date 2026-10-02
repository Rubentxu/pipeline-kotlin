# WU-093 — Reconciliación de clasificación de entrega (HP0)

**Estado:** ACTIVE
**Ciclo SDDK:** `p-1f3622e11c093341/rp6c-http-request`
**WorkItem:** `1e2fa475-177e-42e0-baf6-1640b22d09a5`
**Sustituye la forma de entrega de:** `SPEC_WU093_HTTP.md` (D1: `core.httpRequest`)
**No borra historia:** la spec superada permanece como registro de lo decidido y de por qué se corrigió.

---

## 1. Qué ocurrió

RP6-C se descubrió y especificó como `core.httpRequest`, un Step **core cerrado**, sobre la
respuesta del operador a un questionnaire de la sesión anterior. Los commits
`7a9b4f0f`, `43420fea`, `1c41da0a` y `3901ef6e` construyeron, con el gate en verde, esa forma:

```text
StepSpec.HttpRequest
      ↓
CoreHttpInput
      ↓
CoreHttpWireCodec
      ↓
core.httpRequest
      ↓
DslCompiledPipelineCompiler  (rama nueva)
```

Antes de continuar a G4 se contrastó esa forma con la política de ecosistema del propio
repositorio. El contrasteيمنó lo contrario de lo asumido.

## 2. Evidencia normativa

| Fuente | Contenido |
|---|---|
| `STEP_ECOSYSTEM_MATRIX.md:288` | `httpRequest \| OFFICIAL_PLUGIN candidate \| Tier B \| universal HTTP` |
| `STEP_ECOSYSTEM_POLICY.md:59` | `pipeline-plugin-http`: typed `httpRequest` |
| `RP6A_LOCK_CHARACTERIZATION.md:255-257` | *"La dirección HTTP del operador fue a plugin con `http.request` en vez de `core.httpRequest`, pero el motivo fue distinto: HTTP es concern de protocolo/vendor."* |
| `ROADMAP.md:164` | la cola es explícitamente una **"Cola provisional heredada"** |

La cuarta fuente es la que importa: `lock` se mantuvo en core **por un motivo distinto** y el
mismo párrafo que lo defiende dice que HTTP ya se dirigió a plugin. La distinción ya estaba
escrita y resuelta antes de que RP6-C empezara.

**El fallo fue de proceso, no de diseño.** El questionnaire preguntó "destino: ¿core o plugin?"
sin contrastar la respuesta contra `STEP_ECOSYSTEM_POLICY`. La respuesta se aceptó sin detectar
la contradicción con una política publicada.

## 3. Clasificación del trabajo ya hecho

```text
G3b gate GREEN
      ≠
GO G4
```

El trabajo de G1–G3b es **TECHNICALLY_COHERENT BUT DELIVERY_REJECTED**: era internamente
correcto y estaba en el sitio equivocado. Todo lo aprovechable se conserva cambiando
**ownership**, no reescribiendo el diseño.

## 4. Forma aprobada tras el pivot

```text
pipeline-scripting-api
        │  registryStep(...)          ← primitiva genérica que core SÍ proporciona
        ▼
pipeline-step-sdk:http
        ├── HttpMethod / HttpHeader / StatusRange / HttpDefaults
        ├── HttpRequestInput / HttpResponseOutput / HttpRequestCodec / HttpResponseCodec
        ├── HttpTransport (puerto) / JdkHttpTransport (único socket)
        ├── HttpRequestStep (descriptor + handler)
        ├── HttpStepDefinitionContributor  (ServiceLoader)
        └── StageScope.httpRequest(...)  → registryStep(...)
                    │
                    ▼
              RegistryStepSpec
                    │
                    ▼
              http.request
```

El compilador **no sabe que `http.request` existe**. Esa es la prueba de que el seam es real.

### La frontera que decide el reparto

| Pertenece al **plugin** | Pertenece al **runtime** |
|---|---|
| El protocolo HTTP: método, cabeceras, rangos de estado | El permiso de salir a la red: `NETWORK_EGRESS_CAPABILITY` |
| El transporte: `JdkHttpTransport` es el único que abre un socket | La política por ejecución: `ShOptions.networkEgress` |
| El contrato: codecs, descriptor, `ReplayPolicy.NEVER` | La credencial, cuando llegue (G6) |
| La façade DSL que baja a `registryStep` | El sink de eventos y el presupuesto de ejecución |

`NETWORK_EGRESS_CAPABILITY` y `NetworkEgressPolicy` viven en `pipeline-domain`, no en
`pipeline-application`: son un concepto universal — *un proceso de pipeline no debe salir a la
red* — que responde igual a `http.request`, a un `git` por https, a un feed de artefactos o a
un pull de registro. Ponerlos en el plugin los ataría a HTTP; ponerlos junto a HTTP en
application obligaría al runtime a saber qué es una petición HTTP.

## 5. El defecto de `outcome` que el pivot destapó

`HttpResponseOutput` implementaba `TypedStepOutput` con `outcome` **fijado a `Success`**, y su
propio KDoc afirmaba que esa implementación "no es decoración". Era exactamente el defecto que
WU-092 midió en `core.input`: un carrier con `outcome` fijo reporta verde una ejecución
rechazada.

El tipo ahora son tres, porque la forma honesta necesita el espacio:

```text
HttpResponse         hechos del cable (NO es el carrier)
HttpAttempt          ADT cerrado: Answered | Refused | Failed | Unauthorized
HttpResponseOutput   carrier TypedStepOutput, outcome DERIVADO del attempt
```

El `outcome` se **deriva** del `attempt`, nunca se almacena aparte, así que el carrier no puede
construirse en un estado donde ambos discrepen. Cubierto por dos tests de regresión.

El codec de salida tenía además un segundo defecto: serializaba `validResponseCodes` como
`{"spec":"201,400:404"}` y lo releía con `StatusRange.parse` — un spec de texto reparseado tarde,
que es el antipatrón de Jenkins que este diseño existía para evitar. Ahora es un array tipado.

## 6. Guard reformulado: siete afirmaciones, no una

El guard anterior (`Lfc2HttpWireAuthorityFitnessTest`) afirmaba **una** cosa: que el compilador no
escribe el JSON de wire de HTTP. Eso es el guard correcto para un `core.httpRequest`, y es una
afirmación estrictamente **más débil** que la clasificación de entrega.

Una implementación con forma core puede satisfacer un guard de wire-JSON y seguir siendo core
cerrada: añade `StepSpec.HttpRequest`, añade la rama, delega el payload a un codec, y todas las
aserciones de wire pasan mientras la política se viola entera. La clasificación es una
afirmación sobre **ownership de módulo e ignorancia del compilador**, así que el guard afirma eso:

| FIT | Afirmación |
|---|---|
| FIT-1 | `DslCompiledPipelineCompiler` no contiene `httpRequest` |
| FIT-2 | `StepSpec.sealedSubclasses` no contiene `HttpRequest` |
| FIT-3 | `CoreStepRegistryFactory` no registra ninguna clave http |
| FIT-4 | `pipeline-application` no importa el vocabulario del plugin |
| FIT-5 | la façade DSL baja exclusivamente por `registryStep` |
| FIT-6 | los bytes de wire vienen exclusivamente de `HttpRequestCodec` |
| FIT-7 | `ServiceLoader` descubre el contribuidor |

FIT-4 merece nota: `pipeline-application` **sí** depende del módulo del plugin —es la raíz de
composición y debe poder componerlo—, lo que no puede es importar su **vocabulario**. El guard
prohíbe esos imports concretos y permite la dependencia, para que la aserción siga siendo
cierta en lugar de aspiracional.

## 7. Mutaciones medidas

| Mutación | Resultado | Lectura |
|---|---|---|
| **M-http-5A** — la façade salta `HttpRequestCodec` y escribe el wire a mano | ROJO en FIT-6 | El guard de **autoría** atrapa lo que el round-trip no ve |
| **M-http-5B** — el codec renombra `timeoutSeconds` → `timeout` en encoder **y** decoder | **SOBREVIVIÓ** | Round-trip y byte-identity **no** protegen el vocabulario del cable |
| **M-http-5B′** — la misma mutación, con el vector dorado ya presente | ROJO | Sólo una aserción sobre los bytes literales atrapa un renombrado coordinado |

La supervivencia de M-http-5B es el hallazgo más útil de este slice. Un renombrado de clave en
ambos lados hace round-trip perfecto y rompe en silencio toda carga útil ya registrada en un
journal. Por eso existe ahora `the canonical request encodes to the pinned wire vector`: fijar
los bytes exactos como **datos**. Renombrar una clave es un cambio BREAKING y ese test es donde
se dice.

## 8. Lo que cambia en el alcance

| Antes | Ahora |
|---|---|
| `WU-093 core.httpRequest` | **SUPERSEDED** como forma de entrega |
| — | **ACTIVE**: `WU-093` como OFFICIAL_PLUGIN `http.request` |
| `StepSpec.HttpRequest` (jerarquía sellada core) | Eliminado; el riesgo de `when` exhaustivo en consumidores externos desaparece con él |
| Rama del compilador | Ninguna |
| `CoreHttpInput` / `CoreHttpWireCodec` / `CoreHttpStep` | `HttpRequestInput` / `HttpRequestCodec` / `HttpRequestStep`, en el plugin |
| `domain/step/http/*` | `sdk/http/*` |
| Fitness de wire-JSON (1 afirmación) | Frontera OFFICIAL_PLUGIN (7 afirmaciones) |

## 9. Estado real tras el pivot

`http.request` está **fail-closed en admisión**: el permiso de egress existe y se concede sólo
con `--allow-network`, pero el capability del transporte todavía no lo aporta nadie, así que el
Step se rechaza antes de que su handler corra. **Eso es el default correcto, no un agujero**:
es exactamente lo que el precedente de `scm-git` hace con `SCM_GIT_OPERATIONS_CAPABILITY`.

Aprovisionar el transporte es **H2** en la secuencia acordada. Cuando se cablee, FIT-4 tendrá
que enmendarse para permitir exactamente ese cableado — y la enmienda deberá ser explícita, no
un silencio.

Los 4 eventos del ciclo de vida HTTP **se quedan** en `pipeline-events` (core). El precedente es
`scm-git`, que ya emite sus 11 eventos de checkout desde ahí. Es la única desviación deliberada
de "domain sólo contiene conceptos universales", y se registra como pregunta abierta: el
crecimiento del vocabulario de eventos con cada plugin merece un ADR propio.

## 10. Secuencia a partir de aquí

```text
HP0  reconciliación de entrega          ← este documento
  ↓
H1   contrato del plugin + ServiceLoader
  ↓
H2   spike de transporte (JDK vs Methanol vs OkHttp) + cableado del capability
  ↓
H3   async / cancelación / structured concurrency
  ↓
H4   streaming + backpressure
  ↓
H5   credenciales + redacción
  ↓
H6   política de egress instalada
  ↓
H7   semántica de replay / recovery
  ↓
H8   certificación de la distribución instalada
  ↓
RP-6 CLOSE
```
