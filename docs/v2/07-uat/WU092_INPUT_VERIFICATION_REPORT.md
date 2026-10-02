# WU-092 `core.input` — Informe de verificación (RP6-B, ciclo `rp6b-input`)

> work item: `81d4cc39-1927-4893-acce-e497480b45f4`
> spec: `docs/v2/07-uat/SPEC_WU092_INPUT.md` · recibo: `docs/v2/07-uat/WU092_INPUT_RECEIPT.md`
> árbol verificado: `9895a2b5` (G4) + `025808a4` (recibo)
> verificación: local. CI remoto `NOT_RUN` — `.github/workflows/` no contiene pipelines
> de producto, sólo Dependabot.

## 1. Qué se verificó

Que `input(message) { ... }` se comporta como un Step Jenkins en la distribución real,
no sólo como un contrato aislado. La diferencia importa: los dos defectos de §5 sólo
aparecieron cuando el pipeline dejó de ser una función de prueba y pasó a ser un proceso
forkado con su propio journal.

## 2. Evidencia de tests

```text
pipeline-events             221 tests,  0 fallos,  0 errores
pipeline-scripting-api       85 tests,  0 fallos,  0 errores
pipeline-application       2064 tests,  0 fallos,  0 errores, 121 skipped
pipeline-architecture-tests 370 tests,  0 fallos,  0 errores,  10 skipped
                          ──
TOTAL                     2740 tests,  0 fallos,  0 errores, 131 skipped
```

`22m12s`, `BUILD SUCCESSFUL`, ejecutado sobre el árbol de G4.

Fijado en este bloque:

- `CoreInputStepContractTest` (21 casos) — contrato, codecs y round-trip, incluida una
  fila que afirma el **tipo** del carrier (§5.1).
- `FileInputDecisionsTest` (9 casos) — el canal como componente, con la respuesta
  truncada en cada prefijo, dos preguntas que no comparten fichero y un `opId` hostil
  (`../../escape`) que no escapa.
- `UatInputBlockDurableTest` WI-L1..WI-L9 — HF2 contra el CLI real.
- `Lfc2InputWireAuthorityFitnessTest` (4 filas) — la ley de autoridad única de wire.
- `CanonicalRuntimeCapabilityAccessTest` — fila nueva: la capacidad se expone sólo con
  ancla, y `get` lanza sin ella.

## 3. Mutaciones: dónde la ley es load-bearing y dónde no lo parecía

| Mutación | Medida | Veredicto |
| --- | --- | --- |
| M-in-1 · toda denegación como `TIMED_OUT` | G1 | RED —.forzó una fila nueva en el contrato |
| M-in-2 · fichero malformado leído como `Proceed` | G2 | RED 9/3 |
| M-in-3 · la cota no termina la espera | G2 | RED 9/4 |
| `replayPolicy` MEMOIZED → RERUN | G4 | **SOBREVIVE** |
| `recoveryPolicy` → `ExternalSubprocess` | G4 | **SOBREVIVE** |
| `effects` READ_ONLY → `WRITES_WORKSPACE` | G4 | **SOBREVIVE** |
| M-in-reask · borrar la respuesta registrada al republicar | G4 | RED 9/1 — exactamente WI-L8 |

Las tres supervivencia son el resultado más útil del bloque. La spec afirmaba que
`MEMOIZED` impide re-preguntar, y el descriptor no lo impide: **ninguno de los tres
atributos del descriptor participa en la ley**. Lo que la sostiene es que la respuesta
vive en el canal durable y se relee.

Que una ley resista tres mutaciones bien elegidas no la hace más fuerte: indica que la
prueba no la estaba midiendo. La mutación que sí la corta es la que toca el mecanismo
real, y corta una fila y sólo una.

## 4. Cumplimiento de política arquitectónica

```text
StepSpec sellado, núcleo cerrado          ✓ StepSpec.Input; ningún subtipo externo
Ejecución por el spine canónico           ✓ sin when(stepKey)/when(stepName)
payload decode canónico antes de efectos  ✓ fail-closed en las 4 capacidades
cuerpo por la maquinaria común            ✓ HANDLER_CONTINUATION vía BodyContinuation
sin bypass de admisión                     ✓ sin registro manual en el coordinador
ratchet del coordinador                   ✓ 552 líneas, sin tocar
```

`CoreInputOutput` es el primer carrier tipado cuyo handler **termina sin cuerpo**. No es
una excepción al patrón de `core.sh`/`core.error`: es la primera vez que el patrón cubre
un Step cuya salida no proviene del motor de cuerpos. Por eso la proyección del outcome
no tenía quién la pidiera (§5.1).

## 5. Defectos encontrados y corregidos durante la verificación

### 5.1 Una pregunta rehusada cerraba el run en VERDE

Medición con el CLI real, `input("...", timeoutSeconds = 2)` sin respuesta:

```text
antes:   InputRequested  InputDenied  StageFinished  RunFinished{outcome=SUCCESS}   exit 0
después: InputRequested  InputDenied  StepFailed      RunFinished{outcome=failure}  exit 1
```

Causa: `RegistryExecutionBoundary` proyecta
`(produced as? TypedStepOutput)?.outcome ?: Success`, y `CoreInputOutput` era un `data
class` plano. `runOutcome()` ya calculaba la interpretación correcta — simplemente nadie
la consumía. Un `input` abortado, expirado o nunca contestado era indistinguible de uno
concedido.

Arreglo: `CoreInputOutput : TypedStepOutput`. Sin tocar la boundary, sin tocar el
coordinador, sin una rama por `StepKey`. Una fila de contrato nueva afirma el tipo.

### 5.2 El codec serializaba la proyección, no el dato

Al convertir `outcome` en propiedad derivada, el codec pasó a escribir el outcome
*calculado*. En un abort el campo es `null` y la proyección es `Failure`, así que el
round-trip devolvía un objeto distinto del que lo produjo. Lo detectó el test contractual;
el sobre ahora lleva `bodyOutcome` y la proyección se recalcula al leer.

### 5.3 Una spec que afirmaba algo falso

`SPEC_WU092_INPUT.md` §3.3 y §7.5 decían que un re-run «no vuelve a preguntar». Medido
en el CLI real, en el resume el handler **sí** se re-ejecuta y **sí** vuelve a publicar
`request.json` — idempotente, mismo directorio `runId#stepIndex`. Lo que no ocurre es una
espera. Corregido en §5.1 de la spec y en el criterio 7.5.

## 6. Clasificación G3.6 (superficie sellada)

`StepSpec.Input` se añadió a la jerarquía sellada pública. Igual que en RP6-A:

```text
BINARY_COMPATIBLE
SOURCE_ADDITIVE_WITH_EXHAUSTIVE_WHEN_RISK
```

Consumidores exhaustivos inventariados y cerrados: `BlockStepFlattener` (rama nueva,
compila), `PipelineDslSealedHierarchyTest` (32 → 33, más el pin de superficie de `Input`),
`FArchS0SurfaceManifestTest` y `DSL_SURFACE_MANIFEST.md`, y
`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` (entrada `Input`).

`core.input` **no** entra en `LEGACY_PLUGIN_IDS`: nace detrás del registry, sin decoder,
sin dispatcher y sin fila de metadatos que retirar.

## 7. Dos formas de test que parecen servir y no sirven

Se documentan porque las dos cometen el mismo error y una de ellas costó una iteración
completa:

1. **Ejecutar el mismo pipeline dos veces con el mismo journal y afirmar que el cuerpo no
   se repite.** El segundo run simplemente no re-ejecuta nada, así que la fila pasa con
   cualquier política de replay. Tautológica.
2. **Afirmar sobre el log de eventos del segundo run.** Ese log reproduce el journal, así
   que contiene legítimamente los eventos del primero. La fila habría pasado justo
   cuando el Step vuelve a preguntar.

El discriminador que sí funciona es doble y observable en disco: el conjunto de directorios
de pregunta no crece, y el resume no emite `InputDenied` — porque relee la respuesta en vez
de esperar a un operador.

## 8. Deuda registrada, no resuelta aquí

- `DEBT-WIRE-AUTHORITY` (`bl-bl-01M3YGH3FE000387X13PG607G0`, P2, Triaged): `Dir`,
  `WithEnv`, `TimeoutBlock` y `RetryBlock` siguen autorando wire inline en el compilador.
- El guard de lock es léxico y el de input es estructural. No es inconsistencia: el port
  léxico al caso input es **insound**, porque `put("message"` no es vocabulario de input
  (`Error`, `WarnError`, `Unstable` y `CatchError` lo escriben legítimamente). Unificar
  ambos guards exige primero decidir qué forma tiene el léxico para un Step cuyo
  vocabulario se solapa, y esa decisión pertenece a un evolutivo horizontal, no a este
  bloque.

## 9. Lo que este informe NO afirma

- **No** hay verificación en CI remoto: no existe pipeline de producto en
  `.github/workflows/`. Todo lo anterior es local y reproducible sobre `9895a2b5`.
- **No** hay modo servidor/agente para responder sin escribir en disco (§8 de la spec).
- **No** hay autorización de `submitter`. Es atribución, y la spec lo dice en voz alta:
  este runner es headless y no tiene contra qué autorizar.
- **No** se ha migrado ningún Step legacy: `core.input` nace detrás del registry y no
  toca `LEGACY_PLUGIN_IDS`.
