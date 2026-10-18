# `core.waitUntil` — cierre de su rama dentro de `DEBT-WIRE-AUTHORITY`

> status: RAMA CERRADA sobre el árbol `3fa12a77` (ver §4). La deuda madre sigue abierta.
> deuda madre: `bl-bl-01M3YGH3FE000387X13PG607G0` (`DEBT-WIRE-AUTHORITY`), P2, Triaged,
> registrada en `WU091_LOCK_RELEASE_RECEIPT.md:57` y `WU092_INPUT_RELEASE_RECEIPT.md:57`.
> alcance: **una** rama de `blockPayload`, no la deuda completa.
> ley: `docs/v2/05-roadmap/ROADMAP.md:239-281` (la corrección registrada del 2026-10-18).

## 1. Qué se cierra

`StepSpec.WaitUntilBlock` deja de hand-escribir su cable:

```text
StageScope.waitUntil(...) / StepSpec.WaitUntilBlock
  → DslCompiledPipelineCompiler.toCoreWaitUntilInput()
  → CoreWaitUntilWireCodec.encode          ← autoridad única del formato kind="waitUntilBlock"
  → BlockStepNode(core.waitUntil)
  → projectWaitUntilScope (proyección tipada)  ← lector único
  → BodyExecutionEngine / WaitUntilEngine
```

Consecuencias que no son cosmética:

| Antes | Ahora |
| --- | --- |
| El compilador escribía el JSON del bloque a mano | Delega en el códec nombrado, igual que `Lock` e `Input` |
| `maxBackoffMs` se leía del payload pero **ningún emisor de producción lo escribía** | `StageScope.waitUntil(maxBackoffMs)` es el emisor; el parámetro deja de ser decorativo |
| `initialRecurrencePeriod` se codificaba y **nunca se leía** (parámetro semántico muerto, `SEMANTIC CONSTITUTION` §2) | La proyección lo lee del payload |
| Seis sitios declaraban un valor por defecto, tres `1000L` y tres `1L`, y **ninguno se veía** | Una constante, `DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS`, nombrada por los seis |
| — | `DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS`, segunda mitad de la misma autoridad |

La divergencia de valores no era inocua: hacer vivo el parámetro significa que
`waitUntil(1L)` del DSL habría pollado a 1 ms. Se midió antes de unificar cuál es el valor
que **hoy se ejecuta** (`1000L`) y se unificó hacia ese, no hacia el que declaraba el DSL.

## 2. Lo que el censo encontró al escribirlo

`Lfc2WaitUntilWireAuthorityFitnessTest` es la ley; escribir su segunda fila pagó el coste
inmediatamente. Encontró:

```text
CoreWaitUntilStep.kt:140   private const val MAX_BACKOFF_MS = 60_000L
```

su **única** ocurrencia en todo el repositorio era su propia declaración. Una constante
privada sin lector no es una cuestión de estilo: es un valor que aparenta autoridad y
discrepa de la autoridad ante cualquier cambio de una de las dos. Borrada.

La fila del techo no puede exigir la constante en todas partes, y decirlo es parte del
guard: la proyección es **el lector**, y una clave ausente significa `WaitUntilShape`, así
que pregunta al descriptor en vez de repetir su valor. Exigirle la constante ahí habría
repetido el error en el guard. Un sitio sin token de delegación sigue contado por el
recuento global de literales, así que ningún sitio gana exención.

## 3. Censo medido de las ramas de `blockPayload`

Instrumento: enumerar los brazos `is StepSpec.X ->` de `blockPayload`
(`DslCompiledPipelineCompiler.kt:332-388`) y leer el lado derecho de cada uno. No se usa un
regex de nombres, ni una búsqueda de `*Codec` cuyo nombre contenga el del Step: el ROADMAP
registró que ese segundo instrumento dio un **falso negativo** sobre un códec anónimo, y una
cifra producida por él no es citable.

| Rama | Lado derecho | ¿Autoridad nombrada? |
| --- | --- | --- |
| `Input` | `CoreInputWireCodec.encode(...)` | sí |
| `Lock` | `CoreLockWireCodec.encode(...)` | sí |
| `WaitUntilBlock` | `CoreWaitUntilWireCodec.encode(...)` | sí |
| `WithCredentialsBlock` | `CredentialBindingsPayload.encode(...)` | **leitura disputada** |
| `Dir` | `buildJsonObject { ... }` | no |
| `Timestamps` | `buildJsonObject { ... }` | no |
| `WithEnv` | `buildJsonObject { ... }` | no |
| `TimeoutBlock` | `buildJsonObject { ... }` tras un `when` de unidades | no |
| `RetryBlock` | `buildJsonObject { ... }` | no |

**6 de 9** ramas sin autoridad nombrada, bajo la lectura estrecha (*un códec `*WireCodec`*).
Bajo la lectura amplia (*cualquier codificador nombrado*) son **5**, porque
`WithCredentialsBlock` delega en un codificador con nombre propio que no es un códec de
Step. Se citan las dos lecturas con su instrumento en vez de elegir una en silencio.

Las nueve ramas y sus tres delegados se miden sobre el árbol `3fa12a77`. Las seis ramas
restantes son trabajo futuro y **no** se declaran cerradas aquí.

### 3.1 Hallazgo adicional, mismo brazo

`TimeoutBlock` no sólo escribe su cable: **decide política dentro del encoder**. La conversión
de unidad (`SECONDS`/`MINUTES`/`HOURS` → segundos) ocurre en el mismo `when` que compone el
JSON. Eso viola la separación decidir/interpretar de `AGENTS.md` §7: la lógica que puede
decidir sin hacer E/S debe ser pura, y una función que selecciona una política y la aplica
es un defecto aunque sea corta. No se corrige aquí — es una rama distinta de la que este
recibo cierra.

## 4. Evidencia sobre este SHA

```text
3fa12a77  fix(waituntil): the backoff ceiling is settable from the DSL
75f31bae  fix(waituntil): the poll cadence reaches both engines from one authority
```

Ejecutado sobre el árbol de `3fa12a77`, con canario (XML borrado antes de correr):

```text
:pipeline-application:test        --tests '*WaitUntil*' --tests '*BodyExecution*' --tests '*B1d*'
:pipeline-architecture-tests:test --tests '*FArchS0SurfaceManifestTest'

TOTAL  112 tests, 0 fallos, 0 errores, 0 skips, 19 clases, 35s
```

Clases: `WaitUntilStepContractSuiteTest` (18), `FileBasedWaitUntilControlJournalTest` (14),
`FArchS0SurfaceManifestTest` (13), `CoreWaitUntilStepUnitTest` (9),
`CoreWaitUntilDifferentialContractTest` (8), `WaitUntilMaxBackoffProjectionTest` (7),
`CoreWaitUntilWireCodecContractTest` (6), `WULpr302WaitUntilEngineTest` (10 en 4 clases anidadas),
`BodyExecutionCharacterizationTest` (5), `WaitUntilTerminalAdtTest` (5),
`B1dBodyExecutionEngineClockSeparationTest` (5), `Lfc2WaitUntilDslCanonicalProjectionTest` (3),
`WaitUntilOutputCodecFailClosedTest` (3), `B1aBodyExecutionEngineCredentialPurposeTest` (3),
`CoreWaitUntilBodyVsHandlerTest` (2), `UatLocal011WorkflowControlTest` (1).

`FArchS0SurfaceManifestTest` entra a propósito: el punto 2 del ROADMAP exige que la firma de
la fila 98 cambie **y que su censo se recalcule**, y ese test recomputa el censo desde
`DSL_SURFACE_MANIFEST.md` y falla si la tabladerivada se desvía.

### 4.1 La fila del techo lleva su mutación

`HARNESS FIDELITY LAW` §5: una afirmación de comportamiento lleva su mutación, 1:1.

```text
mutación   StageScope.waitUntil(maxBackoffMs = 60_000L) — un literal en un sitio que DELEGA
aplicada   verificada por grep antes de leer el resultado (1 ocurrencia)
efecto     la fila del techo cae ROJA nombrando los DOS sitios
           la fila de cadencia sigue VERDE
restaurada verificada por hash: d4cd1090… == d4cd1090…, 0 ocurrencias del literal
```

El primer intento de esa mutación **no se aplicó** (ancla incorrecta) y el run devolvió un
timestamp idéntico al verde anterior: era caché, no ejecución. Se registra porque es
exactamente el fallo que la leypreviene, y sólo se lee el resultado después de comprobar que
la mutación estaba presente.

## 5. Lo que NO se cierra

- Las 6 ramas de §3 (5 si se cuenta a `WithCredentialsBlock` como delegado).
- La conversión de unidad de `TimeoutBlock` dentro del encoder (§3.1).
- **`quiet` es un parámetro semántico muerto**, de la misma clase que era `initialRecurrencePeriod`.
  Medido: `CanonicalStructuralDecisions.kt:550` construye el scope con `quiet = shape.quiet`,
  o sea **ignora el valor del payload**, y `scope.quiet` no tiene lector en
  `pipeline-application/src/main`. Un `waitUntil(quiet = true)` se codifica y se descarta.
  No se mezcló aquí porque exige una decisión de contrato (en Jenkins `quiet` silencia el
  log, no el stream de eventos, y `SEMANTIC CONSTITUTION` §8 exige la observabilidad por
  paso: el evento durable no puede suprimirse, la decisión pertenece a la capa de consola).
- `ProductVersion` / PRODUCT-GATE: `BLOCKED_EXTERNAL` sin CI remota (§ política de
  verificación del `AGENTS.md`).

## 6. Cierre de unidad de trabajo

```text
Implementación de referencia consultada: Jenkins pipeline-basic-steps `waitUntil`
  (intervalo inicial 1000ms; sin parámetro de techo de backoff)
Comportamiento adoptado:                   intervalo inicial de 1000ms, verbatim
Desviaciones deliberadas:                 el techo se expone como input del DSL, que Jenkins no hace
Implicaciones de seguridad revisadas:     n/a — no se tocó superficie de credenciales,
                                          procesos ni sistema de ficheros
Pruebas que demuestran el contrato:       Lfc2WaitUntilDslCanonicalProjectionTest,
                                          WaitUntilMaxBackoffProjectionTest,
                                          CoreWaitUntilWireCodecContractTest,
                                          Lfc2WaitUntilWireAuthorityFitnessTest,
                                          FArchS0SurfaceManifestTest
```