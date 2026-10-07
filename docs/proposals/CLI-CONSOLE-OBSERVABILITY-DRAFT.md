---
type: draft
status: DRAFT — NO ES UN ADR ACEPTADO · PARCIALMENTE SUPERADO POR LA IMPLEMENTACIÓN
title: "Borrador de decisión — Consola CLI, vistas, filtros y decoraciones"
date: 2026-10-06
actualizado: 2026-10-07
adr_number: null
authoridad_que_no_sustituye:
  - docs/v2/05-roadmap/ROADMAP.md
  - ADRs aceptados (ADR-M1, ADR-0069, ADR-0074, ADR-0085, ADR-0086, ADR-0088 cuando se acepte)
  - docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md
  - SDDK cycle state
implementado_en:
  - "commit a02ca7cbee12311842a8cf194b51bd91e4937297 (rama par/cli-observation)"
  - "SDDK WorkItem 14d764ae-25bc-47a4-8a9b-fa85263653c0"
notas:
  - "view/format/query SÍ están implementados y certificados; §4 y §5 se escribieron antes y se corrigen abajo."
  - "La entrega E1 (ingestión incremental) NO está hecha: sin ella no hay consola viva del proceso, solo del evento."
  - "El default implementado es `normal`, no `full`: `full` aún no es entregable y se rechaza en vez de degradarse."
  - "E7 NO se aisló como este borrador recomendaba. Ver §4.1: la predicción se cumplió y su coste está medido."
---

# Consola CLI: vistas, filtros y decoraciones — borrador de decisión

## 0. Qué es y qué no es

Es un **borrador de reconciliación**, no una decisión tomada. Su objeto no es diseñar la consola: es
determinar **qué vocabulario se conserva** cuando hoy existen tres que describen el mismo eje, y qué
se elimina por carecer de carrier.

Si este borrador se acepta, el siguiente paso es abrir un ADR numerado (el siguiente libre es
ADR-0104) con el contenido de §3 y §4.

**Estado tras la implementación (2026-10-07).** §3 se aplicó tal cual y está en el código. §4 y §5 se
quedaron atrás: el plan de entregas se ejecutó en otro orden y con otro default. Lo que sigue marca
esas diferencias en lugar de dejar el texto fingiendo que todo está por hacer.

## 1. El problema

`pipeline run` no tiene vista humana. En el baseline inspeccionado:

- `v2/pipeline-application/.../Main.kt:436` — camino en memoria: `println(JsonEventLog.encode(events))`,
  es decir un **array JSON completo al terminar**.
- `Main.kt:854` — camino durable: `JsonEventLog.encodeTo(...)`, mismo array, streameado por
 el mismo array, streameado al final para no materializar la lista completa en memoria.
- `pipeline run` no acepta `--view` ni `--format`: `CliParser.kt:144` cierra el bucle de opciones
  en cuanto encuentra un argumento que no empieza por `--`, y `ParseState` (`CliParser.kt:113`) no
  tiene campo alguno para vista, formato ni filtro. Dentro del bucle sí se rechaza lo desconocido
  (`CliParser.kt:242`, `CliError.UnknownOption` en `:81`), pero **después** de la ruta del script nada
  se vuelve a examinar.

Y no es un defecto de render: **no hay nada que renderizar en directo**.
`ShExecution.kt:253` ejecuta el terminal y `ShExecution.kt:298` llama a
`ingestTranscriptIntoOutputPlane(...)` **después**. El transcript no llega al Output Plane hasta que el
paso termina. Un renderer nuevo, por sí solo, no produce consola viva.

## 2. Tres vocabularios para el mismo eje

Este es el hallazgo que motiva el borrador. No falta diseño: **falta reconciliación**.

| Eje | Fuente | Vocabulario |
|---|---|---|
| Vista × Formato × Query | `CLI_OBSERVABILITY_SPEC.md:5-23`, ADR-0088 | `--view normal\|events\|full\|console\|quiet`, `--format text\|jsonl\|json`, filtros + `--fields` |
| Modelo de observación | `OBSERVATION_STREAM_DATA_MODEL.md:74-118` | `ObservationRecord`, `ObservationQuery`, `ObservationView`, `ObservationRenderer` |
| Política de salida | `docs/proposals/pipelinek-agent-secretless/02-specifications/OUTPUT-POLICY-AND-FILTERING.md:57-67` | `OutputPolicy`: `FullEvents`, `Summary`, `Agent`, `EventSelection`, `TypedSelection` |
| Unidades de trabajo | `LPR_WORK_UNITS.md:56,66` | WU-LPR-042 (console hot path), WU-LPR-050 (CLI views/formats), WU-LPR-051 (inspect/filter/cursor) |

Tres consecuencias concretas de no reconciliar:

1. **`OutputPolicy` y `ObservationView` describen el mismo eje** con constructores distintos. Un
   operador que lea ambos no puede saber cuál gobierna.
2. **Colisión de flags.** La especificación ASX propone `--output`, `--events` y `--select`
   (`OUTPUT-POLICY-AND-FILTERING.md:50-54`). Esos nombres **no** cubren las responsabilidades de
   `--view` / `--format` / query, sino que las duplican con otra sintaxis. Publicar las dos familias
   produce una CLI ambigua.
3. **Un ejemplo de la spec ASX ya es incorrecto hoy.** `OUTPUT-POLICY-AND-FILTERING.md:40-46` presenta
   `pipelinek run pipeline.kts | jq -c 'select(.kind == "RunFinished")'` como "compatible con NDJSON
   actual". La distribución emite un **array** (`Main.kt:436`), no NDJSON: el `select` de `jq` no
   encuentra `.kind` en la raíz. Ese ejemplo debe corregirse al reconciliar, y distinguirse de los
   flags futuros.

## 3. Decisión propuesta

### 3.1 Un solo vocabulario: `view` × `format` × `query`

Se conserva el eje de ADR-0088 y `CLI_OBSERVABILITY_SPEC`, que ya es ortogonal y está especificado con
más precisión. `OutputPolicy` (ASX §4) **no se adopta como vocabulario de consola**: sus cinco casos
mezclan vista, formato y selección de tipos en un solo discriminante, que es exactamente lo que ADR-0088
evita. Sus responsabilidades se reparten en los tres ejes.

|-origen ASX §4| → | destino |
|---|---|---|
| `FullEvents`, `Summary` | → | `--view events` / `--view normal` |
| `EventSelection(kinds)` | → | dimensión `--kind` de query |
| `TypedSelection(selector)` | → | superficie de proyección de resultados, contrato propio (no consola) |
| `Agent` | → | perfil de proyección con `--fields`, reconciliado con ASX-030 |

Los flags `--output`, `--events` y `--select` **no se publican**. Si una necesidad concreta los pide más
adelante, se expresa con el vocabulario de esta decisión o con un contrato de proyección de valores
tipados, no con una segunda familia.

### 3.2 `view` × `format` × `query` × `presentation` son cuatro ejes, no tres

Selección y decoración son operaciones distintas y no se colapsan:

- **query** cambia el conjunto visible (elimina registros).
- **presentation** conserva el conjunto y cambia cómo se ve.

`ObservationQuery` cubre solo el primero. No existe hoy ningún concepto de decoración en el diseño
vigente; añadirlo es parte de esta decisión y no una extensión.

```kotlin
// Esquema conceptual. No es API implementada.
data class ConsolePresentation(
    val time: TimeDecoration,        // None | Timestamp | Elapsed
    val color: ColorMode,            // Auto | Always | Never | Ansi(mapName)
    val emphasis: List<EmphasisRule>, // ORDENADA: el orden compone y resuelve conflictos
)
```

`emphasis` es **lista ordenada**, no `Set`: dos reglas que asignan colores distintos sobre el mismo
fragmento necesitan una regla determinista (propuesta: la última declarada prevalece **en esa
propiedad**, mientras que estilos acumulables como bold/underline se acumulan). Un `Set` no puede
expresar prioridad.

### 3.3 `channel` sale de la query soportada

La spec de datos propone `channels: Set<ObservationChannel>` como dimensión de filtro. No es
implementable hoy: `ShExecution.kt:101-102` documenta que en modo normal el transcript contiene
**stdout y stderr fusionados**, y no existe carrier que asigne canal a un rango de bytes. Los tipos
`ConsoleChannel` / `ObservationChannel` no existen en el código.

**Decisión:** `channel` no forma parte de la query soportada y `--channel stdout|stderr` se rechaza
antes de ejecutar, con diagnóstico — nunca devuelve vacío o total como si hubiera filtrado.

ASX §2 sigue siendo un requisito **de futuro** (distinguir stdout/stderr), no un filter entregado. La
recuperación del canal es una evolución de captura y persistencia, versionada y probada aparte, y no
requiere reescribir el codec de segmentos (dos streams identificados, o un índice de rangos con
procedencia, son alternativas válidas). Ninguna permite reconstruir información ya perdida.

El modo `returnStdout` se preserva sin cambios: stdout es el **valor tipado**, el transcript observable
contiene stderr, y el valor capturado no se reimprime para fabricar consola (`ShExecution.kt:281-282`).

### 3.4 Regla de combinación: dos álgebras

- **query**: AND entre dimensiones, OR entre valores repetidos de una dimensión (ADR-0088). Se
  mantiene.
- **presentation**: **composición**, no intersección. Si `--highlight ERROR --highlight WARN`, una línea
  que casa con ambos sale con ambos estilos, no con ninguno.

Aplicar AND a las reglas de énfasis las deja sin efecto mutuo. Son álgebras distintas y el tipo las
distingue.

### 3.5 Selectores: forma normalizada, y el vacío se rechaza

```kotlin
// Esquema conceptual.
sealed interface TextSelector {
    data class Literal(val value: String, val ignoreCase: Boolean) : TextSelector
    data class Pattern(val pattern: String, val ignoreCase: Boolean) : TextSelector
}

sealed interface LineSelector {
    data object All : LineSelector
    data class Only(val selectors: List<TextSelector>) : LineSelector   // no vacío
    data class Except(val selectors: List<TextSelector>) : LineSelector // no vacío
}
```

- `All` explícito; `Only`/`Except` con colección **no vacía** de selectores validados.
- Texto o patrón vacío se rechaza en la CLI. Bajo semántica de substring, `Only(Literal(""))` coincide
  con **todas** las líneas, no con ninguna: la regla "vacío = nada" solo describiría un conjunto vacío
  de predicados en OR, que es otra cosa.
- `--grep a --grep b` → `Only([a, b])`. `--grep-invert` → `Except([...])` del mismo grupo.
  `--grep-invert` sin selector se rechaza.
- El parser admite flags de conveniencia y produce el ADT **normalizado**; no propaga una bolsa de
  flags al runtime.
- `Pattern` guarda el patrón como `String` y lo compila el consumidor. `Regex` ya lleva opciones, así
  que un segundo campo de política de mayúsculas duplicaría autoridad.

`Only` y `Except` son casos separados, no `selector + negate: Boolean`: `Except` vacío es todo y
`Only` vacío es nada, y colapsarlos es el flag bag que la Semantic Constitution §8 rechaza.

### 3.6 Timestamps son decoración con procedencia declarada

Los eventos tienen `occurredAt`. El transcript **no** conserva hora por línea. `--timestamps` puede
mostrar la **hora de observación**, y así debe etiquetarse. No se deriva de `mtime` ni de una secuencia
de eventos cercana. Para duración se declara la base (monotónica en lectura live).

Sin carrier por línea, la reproducción histórica **no puede** mostrar la hora original, y debe
decirlo en vez de inventarla.

## 4. Alcance por entrega

Cada entrega se certifica por separado. No se mezclan el cambio de escritura con el de default.

| # | Entrega | Naturaleza | Precondición |
|---|---|---|---|
| E1 | **Ingestión incremental** sanitizada hacia el Output Plane existente | **ESCRITURA** (WU-LPR-042) | Ninguna. Es la precondición de la consola viva |
| E2 | Parser estricto + contratos `--view`/`--format` | CLI, sin cambio de ejecución | E1 no necesaria |
| E3 | Renderer humano (`full + text`) | read-side | E1 + E2 |
| E4 | Consultas acotadas (`--stage --step --operation --kind --grep --tail --follow`) | read-side | E3; cada dimensión con carrier |
| E5 | Presentación opcional (`--timestamps --elapsed --color`) | read-side | E3 |
| E6 | Énfasis ordenado y `--dim-nonmatching` | read-side | E5 |
| E7 | **Cambio del default de `run`** | contrato observable | E3; recibo propio |

### 4.1 Por qué E7 iba separada — y qué pasó realmente

**Lo que este borrador anticipaba:** cambiar el default rompe **47 ficheros de test** que
decodifican el array implícito (`JsonEventLog.decode` en `*/src/test/`), entre ellos
`S0SemanticWitnessMatrixTest`, `CompatibilityCorpusTest`, `UatLocal005RegressionGateTest` y
`UatEvt001ReplayTest` — es decir, la cadena sobre la que descansan los recibos.

Migrarlos a `--format json` explícito **no** es neutro: un test que solo pasa con un flag explícito
deja de certificar el default, que es la propiedad que E3 promete. Por eso el cambio observable del
default se aísla, con su propio recibo y su UAT de default.

**Lo que ocurrió (2026-10-07).** E7 se ejecutó **dentro** del mismo slice, contra esta recomendación.
El coste que este texto anticipaba se materializó en dos piezas:

- **Medido:** la suite pasó de 193 a 202 fallos por el cambio de default, 201 atribuibles a él. Se
  repararon migrando los 47 ficheros a `--format json`, y la paridad volvió a 193/1/7.
- **No resuelto:** esos 47 ficheros ya **no** certifican el default. Lo único que lo certificaba era
  el array implícito que eles mismos consumían. El hueco se cerró después con
  `RunDefaultIsHumanEndToEndTest` (3 filas end-to-end sobre un `MainKt` forjado), porque
  `DEFAULT-1` solo prueba el default del **parser**, no lo que un run sin flags imprime.

La lección no es "no cambies el default", que era la decisión de producto correcta y explícita. Es
que **cambiar un default exige reescribir su certificador en el mismo commit**, no confiar en que la
migración de Consumers lo cubre.

### 4.2 El default implementado

El borrador proponía **`full` + `text`**. Lo implementado es **`normal` + `text`**, porque `full`
significa "eventos + consola en directo" y la consola en directo del proceso no existe todavía sin E1:
la ingestión del transcript es post-paso (`ShExecution.executeTerminal` →
`ingestTranscriptIntoOutputPlane`).

Un `full` que prometiera directo sin serlo sería exactamente la pérdida silenciosa que §3.2 reprueba,
así que `FULL` y `CONSOLE` se **rechazan con error tipado** en vez de degradarse a `normal`. Cuando
E1 llegue, `full` se habilita; hasta entonces, negarse es la respuesta honesta.

### 4.3 Fuera de alcance

S5.5 (reactors), S6 (SDK), S7 (harness), S8 (migración). No se reabre la separación event/output
aceptada para devolver procesos a los eventos.

## 5. Leyes y aceptación

Actualizado 2026-10-07. La columna de estado refleja lo que la implementación certify hoy.

| Ley | Oráculo observable | Estado |
|---|---|---|
| Cadena completa de un filtro | flag público → query normalizada → dato producido → dato conservado → lector → resultado observable | **RUN** parcial |
| El filtro se usa | Misma fuente, dos queries distintas → resultados distintos | **RUN** (`RunDefaultIsHumanEndToEndTest`, fila `grep`) |
| AND / OR | `kind` y `subject` se intersectan; varios `kind` se unen | **RUN** (`ObservationQueryTest`) |
| AND/OR también en `events` | `--kind` repetido **no** aplica: `MainEventsCli.kt:34-41` sobrescribe | **NOT_RUN** (defecto preexistente, verb `events` intacto) |
| `channel` sin soporte | Error antes de ejecutar; no devuelve vacío ni total | **RUN** (`--channel` se rechaza) |
| Parser fail-closed | Opción desconocida **después** de la ruta también se rechaza | **RUN** (`TRAIL-1`) |
| Default humano observable | Un run **sin flags** imprime texto, no el array JSON | **RUN** (3 filas end-to-end; ver §4.1) |
| Filtro no es filtro de escritura | Un evento filtrado no se imprime pero sí se persiste | **RUN** (`FILTER-1`, con mutación verificada) |
| Consola viva real (eventos) | Primer mensaje visible antes de liberar una barrera | **RUN** (`LIVE-1`, sobre el decorador) |
| Consola viva real (proceso) | Igual, para stdout/stderr del shell | **NOT_RUN** — exige E1 |
| Autoridad única de bytes | Lo emitido por `console` corresponde al rango comprometido del store | **NOT_RUN** — pertenece a M1 |
| Redacción antes de consumidores | Secreto partido entre chunks no llega a store, selector, decoración ni stdout | **NOT_RUN** |
| Presentación conserva hechos | Mismos registros y mismo texto de datos con y sin énfasis | **NOT_RUN** (no hay énfasis aún) |
| Observador independiente | Consumidor lento o desconectado: el proceso progresa | **NOT_RUN** |
| JSON limpio | El parser incremental consume toda la salida sin banners | **RUN** parcial (el banner va a stderr) |
| Lectura acotada | 200 MiB, 1 GiB y una línea gigante sin materializar todo | **NOT_RUN** (streaming de TEXT pendiente) |
| Cursor con filtro vacío | El cursor avanza sobre registros examinados aunque nada coincida | **NOT_RUN** — depende de P3 OutputCursor |
| Identidad y paralelismo | Etapas, nombres repetidos, ramas y cuerpos por contexto correcto | **NOT_RUN** |

### 5.1 Defecto encontrado por el arnés, no por los tests unitarios

`RunDefaultIsHumanEndToEndTest` no nació como decoración: se escribió para cerrar el hueco de
certificación del default (§4.1), y al ejecutarlo encontró que **`--grep` no filtraba en el camino
por defecto**.

La causa: en el camino `run` en memoria, `--format text` instala `ConsolePrintingEventSink` y
**suprime el volcado final** (`Main.kt:464`), así que ese decorador es el único que imprime. No
recibía la query compilada, de modo que `--grep`, `--stage`, `--step` y `--kind` eran **parámetros
muertos justo en el camino que se anuncian**: la CLI aceptaba el flag y no cambiaba nada observable.
Es el «dead semantic parameter» que la Semantic Constitution §2 prohíbe, y ningún test unitario lo
podía ver porque todos probaban `RunObservationOutput` directamente.

El arreglo pasa la query al decorador y la usa **solo para suprimir la impresión**: el store recibe
todos los eventos sin excepción. Un filtro que escribiera sería peor que un filtro muerto, porque
convertiría una preferencia de presentación en una decisión de durabilidad.

**Límite conocido que este defecto deja al descubierto:** `--stage build` elimina las líneas de
`[step: …]`, porque `StepStarted` no porta `stageName` y `stageCarriedBy` devuelve `null` para él, que
la query trata como "no pertenece a la etapa". Filtrar por etapa deja hoy la estructura y borra los
pasos. Corregirlo exige que la query resuelva el nombre de etapa vía el scope del stream, que es
trabajo propio y no se disimula aquí.

## 6. Restricciones ya vigentes que este borrador no relaja

- `PERFORMANCE_BUDGETS.md:52` — sin filtros regex/de texto en el hilo productor. Todo filtro es
  read-side, streaming y con lectura acotada.
- `OUTPUT-POLICY-AND-FILTERING.md:1-21` — el orden raw → redacción → normalización → sanitizado →
  filtros → renderer ya está escrito. Este borrador no lo reordena: lo aplica.
- `CLI_OBSERVABILITY_SPEC.md:106` — redacción antes de persistencia o presentación; un renderer no es
  una frontera de seguridad.
- `CLI_OBSERVABILITY_SPEC.md:112-114` (§11, *No total ordering lie*) — `full` es un flujo de
  presentación. Solo la secuencia de eventos y el offset de cada transcript tienen orden autoritativo
  local. No hay secuencia global durable común, y el renderer no debe fingir un árbol secuencial.
- `OpId` (`OpId.kt:24-29`) lleva `runId`, `stageIndex`, `stepIndex`, `branchIndex` y `bodyPath`. La
  CLI consume ese contrato tipado; no introduce expresiones regulares propias para interpretar IDs.
  Filtrar por **nombre** de etapa sí requiere asociación con metadatos de etapa.
- Una lectura que devuelve cero bytes mientras el proceso sigue vivo **no** demuestra EOF. La
  ausencia de `RunFinished` en una página tampoco prueba que la ejecución haya terminado.

## 7. Preguntas abiertas antes de aceptar

1. **Intercalado en `full`.** Los dos planos tienen órdenes incomparables (secuencia de evento vs offset
   de bytes) y `ObservationRecord` no tiene clave común. Falta la regla de presentación. No se decide
   aquí.
2. **Ventanas `head`/`tail`/`context`.** `tail`/`head` son alternativas; `context` son vecinos de una
   coincidencia, no una tercera alternativa. "Las últimas N coincidencias de todo el histórico" exige
   presupuesto de escaneo declarado y no debe prometerse leyendo el fichero entero.
3. **`--fields` y el contrato `EnvelopeCodec`.** Reducir campos no debe producir una salida que ya no
   cumpla el contrato y siga llamándose `EnvelopeCodec` v1.
4. **Presupuesto de regex.** Un motor con backtracking necesita decisión específica de CPU/memoria y
   comportamiento ante patrones costosos; no se traslada solo del presupuesto de texto.

## 8. Referencias

- `docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md`
- `docs/v2/04-adrs/ADR-0088-cli-observation-contract.md` (status: proposed)
- `docs/v2/06-design/OBSERVATION_STREAM_DATA_MODEL.md` (status: PROPOSED / implementation guidance)
- `docs/v2/06-design/S5_4_OBSERVATION_VERTICAL_{DESIGN,PLAN,SPECIFICATION}.md`
- `docs/v2/03-specifications/PERFORMANCE_BUDGETS.md`
- `docs/proposals/pipelinek-agent-secretless/02-specifications/OUTPUT-POLICY-AND-FILTERING.md`
- `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-042, 050, 051)
- Código: `Main.kt`, `CliParser.kt`, `MainEventsCli.kt`, `MainConsoleCli.kt`, `ShExecution.kt`,
  `OpId.kt`

Externo, consultado 2026-10-06: Jenkins Log File Filter (RegexpPair = regex/reemplazo, **no**
regex/estilo), Timestamper, AnsiColor, Blue Ocean LogResource.