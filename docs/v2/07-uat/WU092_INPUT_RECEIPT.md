# WU-092 `core.input` — Recibo de certificación (RP6-B)

> status: EN CIERRE — los 9 criterios de §7 están CERTIFIED salvo el cierre de ciclo
> cycle SDDK: `p-1f3622e11c093341/rp6b-input`
> spec: `docs/v2/07-uat/SPEC_WU092_INPUT.md` §7 (9 criterios de salida)
> exploración: `docs/v2/07-uat/RP6B_INPUT_EXPLORATION.md`
> diseño: `docs/v2/07-uat/RP6B_INPUT_DESIGN.md`
> plan de gates: `docs/v2/07-uat/WU092_G1_G4_IMPLEMENTATION_PLAN.md`

## 1. Qué se certifica

`input(message) { ... }` como Step **nacido detrás del registry**: sin decoder legacy, sin
fila en `LEGACY_PLUGIN_IDS`, sin `when(stepKey)` en el coordinador, sin bypass de
admisión. Recorrido completo:

```text
pipeline.kts → StageScope.input / BranchScope.input → StepSpec.Input (IR declarativo)
  → DslCompiledPipelineCompiler → CoreInputWireCodec (autoridad única de wire)
  → BlockStepNode(core.input) → CoreStepRegistryFactory → admisión fail-closed
  → StepHandler → INPUT_DECISIONS + BODY_CONTINUATION + EXECUTION_BUDGET + EVENT_SINK
  → FileInputDecisions (<controlDirRoot>/inputs/<runId#stepIndex>/request.json)
  → BodyContinuation (sólo con Proceed)
```

La respuesta llega por el mismo directorio de control que usa el lock, porque las dos
cosas son territorio durable del motor y no del workspace: un hold no sobrevive al
proceso, una decisión sí.

## 2. Estado de los 9 criterios (§7)

| # | Criterio | Estado | Evidencia |
| --- | --- | --- | --- |
| 1 | Contrato de dominio + codecs con round-trip | CERTIFIED | `CoreInputStepContractTest` (21 casos), `CoreInputWireCodec` / `InputAnswerCodec` simétricos |
| 2 | `InputDecisions` con adaptador de fichero | CERTIFIED | `FileInputDecisionsTest` (9 casos): respuesta truncada en cada prefijo, dos preguntas no comparten fichero, `opId` hostil no escapa |
| 3 | Registro en `CoreStepRegistryFactory` sin bypass | CERTIFIED | `CoreInputStep.registerInto`; 22 keys; admisión fail-closed por ancla de control |
| 4 | DSL `input(...)` positiva y negativa | CERTIFIED | `DslCompiledPipelineCompilerTest` (3 casos) + `PipelineDslSealedHierarchyTest` (33) + `Lfc2InputWireAuthorityFitnessTest` |
| 5 | Reanudación verificada por mutación | CERTIFIED | WI-L8 + mutación M-in-reask (§5). **Ley corregida en §5.1 de la spec** |
| 6 | Denegaciones como vías propias | CERTIFIED | `TimedOut` / `Cancelled` / `Unanswerable`; malformada y doble respuesta probadas en el puerto (§3.4 D4) |
| 7 | UAT HF2 con los escenarios duros | CERTIFIED | `UatInputBlockDurableTest` WI-L1..WI-L9 (§4) |
| 8 | Ratchet del coordinador intacto en 552 | CERTIFIED | `CanonicalDurableRunCoordinator` = 552 líneas, sin tocar |
| 9 | Recibo con SHA exacto | PENDIENTE | §3, se fija al cerrar el ciclo |

### 2.1 Suite de certificación (sobre el árbol de G4)

```text
pipeline-events            221 tests, 0 fallos, 0 errores
pipeline-scripting-api      85 tests, 0 fallos, 0 errores
pipeline-application      2064 tests, 0 fallos, 0 errores, 121 skipped
pipeline-architecture-tests 370 tests, 0 fallos, 0 errores,  10 skipped
                          ──
TOTAL                    2740 tests, 0 fallos, 0 errores, 131 skipped   (22m12s, BUILD SUCCESSFUL)
```

## 3. SHA de referencia

```text
base  e68b5143  (cierre de RP6-A)
G1    523ffb0a  feat(input): contrato de dominio y ADTs cerrados
G2    d0a02bd6  fix(input): respuesta por fichero segura + corrección de mi propio diseño
G3    e16b8e28  feat(input): superficie DSL con autoridad única de wire
G4    (este recibo)  fix(input): routing de producción + una pregunta rehusada falla el run
```

## 4. UAT HF2 — `UatInputBlockDurableTest`

Todos contra la distribución real (CLI forkado, `--db` + `--control-root` compartidos).
El test **descubre el canal en disco** como haría un operador, en vez de conocer el
`runId`: si el canal viviera en otro sitio, las filas colgarían en vez de pasar en verde.

| Fila | Ley | Resultado |
| --- | --- | --- |
| WI-L1 | `PROCEED` corre el cuerpo una vez y acaba verde; la pregunta publicada lleva mensaje, submitter e id | PASS |
| WI-L2 | `ABORT` falla el run, emite `InputAborted` y **nunca** `InputProceed`, cuerpo sin ejecutar | PASS |
| WI-L3 | Una respuesta malformada **no** termina la espera; la válida posterior sí | PASS |
| WI-L4 | Gana la **primera** respuesta: un `PROCEED` posterior no pisa un `ABORT` ya registrado | PASS |
| WI-L5 | `timeoutSeconds` acota la espera, deniega con `TIMED_OUT` y la pregunta sigue inspeccionable | PASS |
| WI-L6 | Un `timeout` externo cancela una espera indefinida; nadie responde y nadie procede | PASS |
| WI-L7 | Un aborto para **el cuerpo y para lo que viene después** | PASS |
| WI-L8 | Un resume **no pide una segunda decisión** (§5) | PASS |
| WI-L9 | Dos `input` en un run son dos preguntas en dos directorios, con su propio texto | PASS |

## 5. Mutaciones medidas

| Id | Mutación | Medida | Resultado |
| --- | --- | --- | --- |
| M-in-1 | Toda denegación como `TIMED_OUT` | G1 | RED — fila añadida al contrato; revertida |
| M-in-2 | Fichero malformado leído como `Proceed` | G2 | RED 9/3; revertida |
| M-in-3 | La cota no termina la espera | G2 | RED 9/4; revertida |
| M-in-reask | `FileInputDecisions.publish` borra la respuesta registrada al republicar | G4 | **RED 9 tests / 1 failed = exactamente WI-L8**; revertida, verde |

### 5.1 Mutaciones que SOBREVIVIERON, y por qué importa

Antes de encontrar la mutación discriminante se medieron tres sobre el descriptor, y
**las tres dejaron el UAT entero en verde**:

```text
replayPolicy   MEMOIZED  → RERUN                  sobrevive
recoveryPolicy (default) → ExternalSubprocess     sobrevive
effects        READ_ONLY → WRITES_WORKSPACE       sobrevive
```

La causa está medida en §5.1 de la spec, y es un hecho que cambia lo que el descriptor
promete: **la ley de «no repreguntar» no la impone el descriptor, la impone el canal
durable**. El resume sí re-ejecuta el handler y sí vuelve a publicar `request.json` —
idempotente, mismo directorio, mismo contenido — pero relee `response.json` y no espera.
Un descriptor que prometa lo contrario está describiendo el resultado equivocado, y una
ley que resiste tres mutaciones de descriptor no está probando lo que dice probar.

## 6. Defectos de producción encontrados por este bloque

Ninguno era visible en los tests de componente: los dos aparecieron al ejecutar la
distribución real.

### 6.1 Una pregunta rehusada terminaba el run en VERDE

Medido con el CLI real sobre `input("...", timeoutSeconds = 2)` sin respuesta:

```text
InputRequested  InputDenied   StageFinished   RunFinished{outcome=SUCCESS}   exit 0
```

Causa: `RegistryExecutionBoundary` proyecta el outcome como
`(produced as? TypedStepOutput)?.outcome ?: Success`, y `CoreInputOutput` era un `data
class` plano. La interpretación ya existía y era correcta —`runOutcome()`— pero nadie la
pedía: `core.input` es hoy el único Step cuyo handler puede **terminar sin cuerpo**, así
que el motor de cuerpos no tiene outcome que propagar y el valor devuelto caía a
Success.

Arreglo: `CoreInputOutput : TypedStepOutput`, igual que `CoreShellOutput` y
`CoreErrorOutput`. Sin tocar la boundary, sin tocar el coordinador, sin una rama por
StepKey. Medido tras el arreglo: `exit 1`, `StepFailed`, `RunFinished{outcome=failure}`.

Una fila de contrato nueva afirma el **tipo**, no un caso concreto, para que un refactor
futuro no vuelva a dejar el carrier en un `data class` cualquiera.

### 6.2 El codec serializaba la proyección, no el dato

Al convertir `outcome` en propiedad derivada, el codec pasó a escribir el outcome
*calculado* en el sobre. Para un abort, el campo es `null` y la proyección es `Failure`:
el round-trip devolvía un objeto distinto del que lo produjo, y el test contractual lo
detectó. Arreglo: el sobre lleva `bodyOutcome` (el campo) y la proyección se recalcula
al leer.

## 7. Lo que este bloque deja escrito

- La superficie DSL de `core.input` está en `docs/v2/surface/DSL_SURFACE_MANIFEST.md`, con
  `submitter` declarado explícitamente como **atribución, no autorización**: este runner
  es headless y no tiene contra quién autorizar. La autorización es terreno de `when`
  (S1-C).
- `DEBT-WIRE-AUTHORITY` (`Dir`, `WithEnv`, `TimeoutBlock`, `RetryBlock` siguen autorando
  wire inline en el compilador) sigue fuera de RP6-B, registrada como
  `bl-bl-01M3YGH3FE000387X13PG607G0` (P2, Triaged).
- La ley de autoridad única de wire tiene dos guardas de distinta forma a propósito: el
  de lock es léxico y el de input es **estructural**. El port léxico del de lock al caso
  input es insound —`put("message"` no es vocabulario de input— y habría fallado sobre
  código correcto.
