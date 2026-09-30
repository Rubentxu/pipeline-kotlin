# WU-RP-020 — durable sequence authority (UNIQUE(run_id, sequence))

**Fecha:** 2026-09-30T08:47Z
**Ciclo SDDK:** `p-733fb505b5a6bd2d/rp-020-durable-sequence-authority` (base `34c08ad9`, == origin/main)
**Backlog items ejecutados:**
- `bl-bl-01M3QD197Q000387ET2D4MFKR0` (P1, el defecto confirmado)
- `bl-bl-01M3QHCPHG000387F2V19JH440` (P1, WU-RP-020 desbloqueado + caveats del harness)
**Origen del defecto:** `docs/v2/07-uat/S1_R0_RUN_CONCURRENCY_1_RECEIPT.md` (RUN-CONCURRENCY-1)
**Clase de evidencia:** OBSERVED (SQLite real, procesos CLI reales, XML de JUnit fresco)

---

## 1. El defecto

`SqliteEventStore` siembra su contador por run **una vez**, en el constructor
(`seedSequenceCounters()`), desde `MAX(sequence)`. Dos instancias sobre la **misma** base
de datos arrancan por tanto desde el mismo número y luego incrementan cada una por su
cuenta. Sin `UNIQUE(run_id, sequence)` ambos `INSERT` tienen éxito y la tabla acumula
secuencias duplicadas.

Caracterizado en el ciclo anterior con procesos reales:

```
run_id=79e8f42b-3d0e-429e-b6bf-c219765e9564
event_rows_after_resume=21
distinct_sequences=13
max_sequence=13
```

`21` filas, `13` distintas: **8 filas colisionan**. La secuencia por run no era un
invariante durable mientras dos dueños se solapaban.

Lo que **no** se reproduce es la hipótesis de efectos duplicados: la memoización del
operation journal ejecuta el `sh` incompleto exactamente una vez. El defecto es de
**integridad del log de eventos**, no de duplicación de efectos.

## 2. El arreglo (mínimo y deliberadamente acotado)

`SqliteEventStore.createSequenceUniquenessIndex()` establece
`CREATE UNIQUE INDEX ux_events_run_sequence ON events(run_id, sequence)`.

**Por qué la base de datos y no el proceso:** el contador es memoria por JVM; dos procesos
no lo comparten. La base de datos sí. Con la restricción, SQLite **rechaza** al escritor
que duplicaría una secuencia ya confirmada: fail-closed, sin corrupción silenciosa, en vez
de anexar un segundo evento con un número que ya existe en el log.

**Lo que este WU NO hace, a propósito:**

- **No renumera** un evento que colisiona. La cola de un solo escritor ya da un `1..N`
  denso para una instancia; renumerar bajo contención abriría huecos.
- **No introduce `RunExecutionLease` ni fencing token.** Decide qué escritor es dueño de un
  run es una pregunta de contrato distinta, con su propio ciclo.
- **No convierte el rechazo en un error tipado todavía.** Hoy aflora como fallo del writer
  en `close()`/`flush()`. La forma tipada es trabajo posterior y queda anotado abajo.

**Fallo limpio en bases ya corruptas:** si una base ya contiene duplicados, el índice no
puede crearse. En vez de dejar un log corrupto que revienta más tarde por una razón opaca,
la migración **falla al abrir** nombrando el run, la secuencia y el conteo, con la
remediación (base nueva, o re-ejecutar el run). Renumerar historia ya confirmada
automáticamente no es aceptable.

## 3. RED demostrado (no supuesto)

Con el cambio de producción revertido (`git checkout` del fichero, canary de XML borrado
antes de correr):

```
Rp020CrossInstanceSequenceAuthorityTest
  tests=1 skipped=0 failures=1 errors=0
  message="DUPLICATE DURABLE SEQUENCES: rows=300 distinct=150 (expected equal; 150 duplicated)"
```

`300 = 2 × 150`: dos stores independientes, 150 eventos cada uno, y **cada** secuencia
queda duplicada exactamente una vez. Falla por la razón correcta (no timeout, no error de
compilación). Log sha256 `39fe2193b9d07d24c8eb21551cc00c5349a2ebe9dcb3414ba3280a000c6bf746`.

Este RED es más limpio que el del ciclo anterior: determinista, aislado, sin forks.

## 4. Dos defectos de test encontrados por el propio gate

Ambos los encontró la escalera de validación, no la lectura de código. Se documentan porque
son el tipo de fallo que un L1 no ve.

### 4.1 Baseline sin flush (L2, fallo sólo en suite completa)

`SqliteEventStoreConcurrencyCharacterisationTest` quedaba **verde en aislamiento y rojo en
la suite completa**. Causa: el test anexaba 3 eventos **sin `flush()`** y luego afirmaba que
el store reabierto continuaba desde `MAX(sequence)` durable. Pero cuando el duplicado mata al
writer, esa transacción hace **ROLLBACK**, así que el "baseline durable" era en realidad
`MAX(nada) = 0` y la siguiente asignación era `1`, no `4`.

El test mentía sobre la durabilidad. Peor que no tener test.

Arreglo: `flush()` antes de provocar el duplicado, y un helper `durableMaxSequence()` que lee
`MAX(sequence)` **directo de SQLite**, sin preguntar al store. Una propiedad de durabilidad
se observa en el estado durable; preguntarle al sujeto por su propia nota es no examinarlo.
Se añadieron además dos aserciones explícitas: el baseline son 3 **antes** del duplicado, y
el duplicado rechazado nunca se vuelve durable (denso `1..3` más `4`).

### 4.2 Caracterización que afirmaba el defecto (L3)

`UatRunConcurrencyCharacterisationTest` —el test que **descubrió** el defecto— afirmaba
`distinctSequences < rowsAfter`, es decir, **afirmaba que el defecto existía**. Con el
arreglo ya no hay duplicados, luego la aserción quedaba obsoleta y el test se puso rojo.

Se **invirtió** a `distinctSequences == rowsAfter`, con la historia escrita en el propio
test: qué afirmaba antes, qué se observó, y por qué ahora afirma lo contrario. Un test de
caracterización es un espejo del comportamiento observado; cuando el comportamiento
cambia porque se arregló, el espejo se actualiza y se deja constancia del porqué.

Detalle observado en el reporte: `owner2_exit=1`. El dueño perdedor ahora **sale no-cero** en
vez de fingir éxito. Eso es el fail-closed funcionando en la frontera de proceso, no una
regresión.

## 5. Evidencia de verificación

| Nivel | Alcance | Resultado | Evidencia |
|---|---|---|---|
| RED | `Rp020CrossInstanceSequenceAuthorityTest` sin el índice | `1/0/1/0` — falla por la razón correcta | mensaje `rows=300 distinct=150`; log `39fe2193…` |
| L1 GREEN | test nuevo + caracterización del store | `1/0/0/0` y `10/0/0/0` | XML `eac5b13f…` / `b430f548…`, ts `06:36:36Z` / `06:37:37Z` |
| L2 | módulo `pipeline-events` completo | **189 tests, 0 failures, 0 errors**, 37 XML | log `a0bbb40b54152da0cce244e81286981661d7d4854693daec5794ed1089d23467` |
| L3 | `durable.*` + blocks durables + resume + replay + concurrencia | **349 tests, 0 failures, 0 errors**, 73 XML | log `89e50f8f1b24e340c7ed14fb5b332e6f15f0c444f862fb5e500d0c6505ce7eb5` |
| L5 | `check` completo | **3768 tests, 0 failures, 0 errors**, 562 XML | ver §6.0 |

Todos los XML se leyeron desde `build/test-results/test/`, nunca desde la consola, y todos
los runs que debían ejecutar borraron su XML antes (canario). La clase de caracterización
lleva `@Timeout(120)`; ver §6.2, donde la explicación que se le daba a su 60 s quedó
**retirada por medición** y el fenómeno queda **sin explicar**.

## 6. Round gate L5 — primero RED, luego VERDE tras arreglar los emisores

El primer gate completo (`check`, presupuesto 1651 s = baseline 1270 x 1.3) salió
**BUILD FAILED in 20m 59s**, con 562 XML frescos y dos clases rojas:

| Clase | Resultado | Destino |
|---|---|---|
| `UatLocal008CredentialsTest` | 27 tests, **14 failures** | **arreglado** en este WU (§6.1): **27 / 0 / 0** |
| `SqliteEventStoreConcurrencyCharacterisationTest` | 10 tests, **1 failure** (60,01 s) | causa **retirada**, sigue **sin explicarse** (§6.2) |

El `exit code` del envoltorio fue 0 con el build fallido: por eso el veredicto se leyó del
**log** y de los **XML**, nunca del código de salida. Es exactamente el motivo de la regla 25.

### 6.0 Gate final: VERDE

Tras arreglar los 13 sitios de emisor (§6.1) y el defecto de barrera huérfana (§6.2), el gate
completo se re-ejecutó sobre el árbol final:

```text
v2/gradlew -p v2 check
BUILD SUCCESSFUL in 20m 51s
562 clases XML, 3768 tests, 129 skipped, 0 failures, 0 errors
```

Leído de los XML con canario de borrado previo, no del log ni del código de salida. Ningún
test se quedó cerca de los 60 s: los únicos >30 s son los largos por naturaleza (`corpus
smoke-runs` 172 s, `ADV-007` 43 s, resume concurrente 35 s), ninguno con la firma del
atasco.

| Nivel | Alcance | Resultado |
|---|---|---|
| L1 | `UatLocal008CredentialsTest` | 27 / 0 / 0 (1 skip pre-existente) |
| L1 | regresión de barrera (RED 60,416 s → GREEN 0,415 s) | 1 / 0 / 0 |
| L2 | módulo `pipeline-events` | **190 / 0 / 0**, 37 XML |
| L2 | módulo `pipeline-step-sdk:scm-git` | **47 / 0 / 0** (8 skips pre-existentes) |
| L2 | consumidores Git de `pipeline-application` | **50 / 0 / 0** (2 skips) |
| L5 | `check` completo | **3768 / 0 / 0**, 562 XML |

### 6.1 `UatLocal008CredentialsTest` — NO es una regresión creada por el fix

**Clasificación:** defecto **pre-existente y silencioso**, registrado como
`bl-bl-01M3RK3NZV000387H65JWN9XM0` (P1).

**Prueba base vs head (regla 16, sin suposiciones):**

| Árbol | Resultado |
|---|---|
| base `34c08ad9` (cambios en stash) | `UatLocal008CredentialsTest` **27 / 0 failures / 0 errors** — verde |
| head (con el índice) | **27 / 14 failures** — rojo |

Es decir: **verde en base, rojo con el cambio**. Eso descarta "preexistente" y obliga a
explicarlo. No se dobló ninguna aserción para maquillar el verde.

**Causa raíz, capturada por instrumentación y no inferida.** Con una sonda temporal en
`bindInsert` se obtuvo el par exacto que colisiona:

```text
incoming=CredentialBound   / 07:20:36.377 / run=21bf37db / seq=1
existing=CompilationStarted / 07:20:31.574 / run=21bf37db / seq=1
```

Dos eventos distintos, cinco segundos de diferencia, mismo run, **una sola** instancia del
store y **un solo** writer thread. Un `AtomicLong` no produce eso por sí solo, así que la
búsqueda bajó al emisor:

```kotlin
// WithCredentialsExecutor.kt:131
var sequence = 1L
...
val boundEvent = CredentialBound(..., sequence = sequence++, ...)
```

`WithCredentialsExecutor` lleva **su propio** contador desde 1 y pasa secuencias
**explícitas** (no cero). En `appendAssigned`, una secuencia distinta de `0` **evita el
contador del store** y se escribe tal cual. Por tanto `CredentialBound` **siempre** reclama
la secuencia 1 de un run, y choca con el primer evento real que ese run ya tenga.

Esta corrupción **ya existía en base**, sólo que sin restricción que la delatara: el log
aceptaba las dos filas y nadie lo notaba. `UNIQUE(run_id, sequence)` no la causó; la
convirtió de corrupción silenciosa en fallo fail-closed. Eso es exactamente lo que un
fail-closed debe hacer, y por eso los 14 fallos son el término correcto, no una regresión.

**Radio de impacto (mayor que "credenciales"):** el patrón no es propio de credenciales.
**Cualquier** emisor que pase una secuencia explícita distinta de cero evade la autoridad
de secuencia.

**Medido, no supuesto:** el barrido de emisores encontró exactamente **dos** ficheros
infractores frente a **63** sitios que ya usan `sequence = 0L` correctamente:

| Fichero | Sitios | Qué pasaba |
|---|---|---|
| `WithCredentialsExecutor.kt` | 2 | `var sequence = 1L` por llamada; el contador **reiniciaba en 1** en cada bind y en cada teardown |
| `GitCheckoutExecutor.kt` | 11 | `sequence = req.stepIndex.toLong()` — un **ordinal de paso** usado como secuencia de evento (error de categoría) |

**Corrección de alcance (retirada la justificación previa).** Antes se escribió que arreglarlo
"exige cambiar el contrato de asignación para todos los emisores y volver a certificar
credenciales", y por eso quedaba fuera de este WU. **Eso estaba sobredimensionado.** Medido:
son 13 sitios mecánicos en 2 ficheros, no una reescritura de contrato. Se han arreglado aquí,
que es lo que corresponde: la ley de alcance prohíbe la reescritura, no el arreglo.

Los 13 sitios pasan ahora `sequence = 0L`, que es la señal documentada de
`appendAssigned` ("asígname la secuencia durable"). El ordinal de paso sigue existiendo en su
payload; lo que se elimina es su uso como secuencia de run.

**Efecto:** `UatLocal008CredentialsTest` pasa de **27 / 14 failures** a **27 / 0 failures /
0 errors** (1 skip pre-existente, `@Disabled` por classpath DSL, presente ya en base).

### 6.2 La caracterización del store: 60,01 s — CAUSA ENCONTRADA Y ARREGLADA

`smaller explicit sequence does not rewind the counter` tardó **60,01 s** contra un
`@Timeout(60)` a nivel de clase durante el gate completo. No es un fallo de aserción: la
aserción es correcta.

**Reproducido y explicado por medición.** Una sonda con el *ordenamiento de base* (duplicado
anexado y `flush()` **sin** un `flush()` intermedio) reprodujo el fallo de forma
determinista: **`flush()` tardó 60008 ms**, exactamente el límite de la barrera
(`barrier.await(60, TimeUnit.SECONDS)`).

Mecanismo, observado con instrumentación del bucle escritor (retirada después):

```text
PROBE-WL batch start, first=Event writerError=null
PROBE-WL batch FAILED size=4 barriers=0     <-- el lote que muere NO contiene barrera
PROBE flush() took 60008ms
```

El lote que falla contiene **sólo eventos** (`barriers=0`): el escritor muere en un `INSERT`
duplicado, así que el bucle de liberación del `catch` no encuentra ninguna barrera que
contar. La barrera que encola el `flush()` del llamador llega **después** de ese lote, cuando
el escritor ya no consume: queda **huérfana** y su esperador quema el límite completo. El
`writerError` no la salva porque es una **carrera**: `appendAssigned` devuelve en
*ASIGNACIÓN*, antes de que el escritor registre su error.

**Dos caminos, uno correcto y uno falso:**

1. **Planteamiento original (correcto).** "El escritor muerto deja una `FlushBarrier`
   huérfana." Resultado: **es el mecanismo real**, sólo faltaba probarlo.
2. **Retractación (incorrecta).** Se afirmó que la barrera no podía quedar huérfana porque
   `flush()` empieza por `writerError?.let { throw ... }` sobre un `@Volatile`. Eso es
   cierto **sólo si el error ya está registrado**. El test que se usó para "refutarlo"
   **esperaba a que el error estuviera registrado antes de hacer flush**, con lo que se
   saltaba la ventana de carrera entera y pasaba idéntico con y sin el arreglo. **Una prueba
   que no ejercita la carrera no puede refutar la hipótesis que la carrera sostiene.**

**Arreglo aplicado** (`SqliteEventStore`): cuando el escritor va a morir, libera las barreras
que queden encoladas detrás (`releaseQueuedBarriers()`), y `flush()` acepta un latch ya
contado. Los `Event` encolados se **devuelven a la cola**, nunca se descartan: tirarlos
perdería escrituras en silencio.

| | `flush()` |
|---|---|
| Antes | **60008 ms** |
| Después | **17 ms** |

Test de regresión permanente: `flush fails fast when the writer dies on the preceding append`,
con el ordenamiento exacto que lo disparaba. RED = `60.416 s` fallando por
`took 60008ms`; GREEN = `0.415 s`. La aserción discrimina por un factor ~3500.

Módulo `pipeline-events`: **190 / 0 / 0** (antes 189, +1 la regresión).

## 7. Lo que sigue abierto (no resuelto aquí)

1. **Rechazo tipado.** El `UNIQUE` violation hoy aflora como `IllegalStateException` del
   writer en `close()`/`flush()`, envuelto. El contrato de dominio pide un
   `ReplayDecision`/rechazo tipado. Convertirlo es trabajo propio.
2. **`RunExecutionLease` + fencing token.** Decide **qué escritor** posee un run. Este WU
   hace visible la violación y la impide; no resuelve la propiedad. Es el diseño grande que
   el backlog nombra y que sigue sin dueño.
3. **Caveats del harness** de `UatRunConcurrencyCharacterisationTest`, registrados en
   `bl-bl-01M3QHCPHG000387F2V19JH440` y **no** arreglados aquí: el `CyclicBarrier` sólo
   rendezvousea hilos auxiliares **después** de que los procesos hijos ya arrancó (la
   simultaneidad no está garantizada), y el dueño inicial se saca de la lista de limpieza
   antes de matar sus descendientes. Documentados en el KDoc del test. Deben arreglarse
   **antes** de promover esa suite a certificación. Ninguno debilita la aserción 3: la
   unicidad es una post-condición que se cumple haya colisión o no.
4. **Bases de datos preexistentes ya corruptas.** El fallback es fail-closed al abrir con
   diagnóstico nombrado. No hay migración; se documenta la remediación en el mensaje.
5. **Ciclo SDDK sigue en `BLOCKED` por fricción del tooling, no por el código.** El ciclo
   `rp-020-durable-sequence-authority` se bloqueó cuando el primer gate salió rojo. Con el
   gate ya **verde** se emitió el recibo `gate-unblock-condition-met-6464580629a024da-1` y
   `-2` con `argv`, `exit_code` y `output_digest` reales, pero `sddk cycle transition
   --transition cycle.unblock` sigue respondiendo `ENGINE_MISSING_GATE_RECEIPT`; y
   `sddk cycle rebuild` devuelve `restored: false`. Comprobado contra el manifiesto: la
   transición y el gate **sí** están declarados y el estado `BLOCKED/Explore` es el
   correcto. Ninguna razón de `supersede` aplica (`scope_invalid`, `goal_replaced`,
   `external-obsolete` serían todas falsas), así que **no se forzó una transición verde**.
   Se registra la divergencia: Git + CI + el gate dicen verde; el estado del ciclo dice
   `BLOCKED`. Según la ley de autoridad, Git/CI manda y el ciclo es lo que queda
   desalineado. Requiere una sesión con la herramienta para cerrar el ciclo.

## 8. Cierre de trabajo (checklist AGENTS.md)

```text
Reference implementation consulted: SQLite UNIQUE constraint semantics + JDBC/SQLite
  transaction/rollback behaviour. No Jenkins-equivalent applies: this is internal
  durability, not a Step surface.
Behaviour adopted:        the DATABASE is the sequence authority; a writer that would
                          duplicate a committed sequence is rejected fail-closed
Intentional deviations:   no renumbering of committed history; no RunExecutionLease in
                          this WU; the rejection is not yet a typed domain error
Security implications:    none — this is a data-integrity boundary, not a trust
                          boundary. No credential, sandbox or egress surface changed
Tests demonstrating:      Rp020CrossInstanceSequenceAuthorityTest (RED+GREEN),
                          SqliteEventStoreConcurrencyCharacterisationTest (10),
                          UatRunConcurrencyCharacterisationTest (inverted), plus the
                          189-test events module and the 349-test durable consumer set
```

## 9. Estado

WU-RP-020 cierra su exit criterion local: la secuencia por run es ahora un invariante
durable respaldado por la base de datos, y el fallo es fail-closed en vez de silencioso.

**NO** se declara resuelta la propiedad de ownership entre procesos. Eso sigue siendo
`RunExecutionLease` + fencing token, y es un ciclo aparte.
