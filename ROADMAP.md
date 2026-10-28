# Roadmap de refactorización y correcciones — PipelineKotlin

Base: `main` · commit `bd5edb8de` (medido) · publicación `1ac1afe7` · v0.91.0

La propuesta es reorganizar `pipeline-application` y simplificar `pipeline-step-sdk` mediante vertical slices, separación de responsabilidades y reducción de connascencia, aprovechando las abstracciones existentes.

No se añadirán funcionalidades. Se conservarán los contratos públicos y los comportamientos correctos; únicamente cambiarán los comportamientos en los que se haya demostrado un defecto.

## 1. Objetivo arquitectónico

El problema no se resuelve simplemente dividiendo archivos grandes. Hay que conseguir que cada caso de uso tenga un propietario claro de sus decisiones, dependencias y efectos.

Organización objetivo de las responsabilidades

```
pipeline-application
├── run/
│   ├── admission/       # Validar ejecución
│   ├── compilation/     # Preparar pipeline
│   ├── execution/       # Coordinar ejecución
│   ├── recovery/        # Recuperación existente
│   └── composition/     # Construcción de dependencias
├── observe/
│   ├── console/         # Lectura de salida
│   ├── events/          # Consulta de eventos
│   ├── inspect/         # Inspección
│   └── projections/     # Explain, Cost, Telemetry
├── plugins/
│   ├── admission/       # Integridad y confianza
│   └── composition/     # Registros y capacidades
└── cli/                 # Parsing, dispatch y formato

pipeline-step-sdk/runtime
└── durable/
    ├── shell/           # Orquestación shell
    ├── process/         # Lanzamiento y terminación
    ├── transcript/      # Captura y drenaje
    ├── watchdog/        # Timeout y cancelación
    ├── agent/           # Adaptación al agente existente
    └── protocol/        # Archivos y compatibilidad
```

Es una organización lógica orientativa dentro de los módulos actuales, no una propuesta para crear nuevos módulos Gradle ni nuevas API públicas.

El recorrido de ejecución debe quedar claramente delimitado:


El SDK no debe conocer decisiones de negocio del CLI, y la capa de aplicación no debe implementar detalles de procesos Unix o FIFOs.

## 2. Connascencia: qué corregiremos

La connascencia mide cuánto necesitan coordinarse dos piezas de código para funcionar. El objetivo es mantener los acoplamientos fuertes dentro de límites pequeños y evitar que atraviesen módulos.


connascence.io

|Tipo|Problema identificado|Refactor|
|---|---|---|
|Significado|`fencingToken` empleado como instante temporal|Tipos de identidad y tiempo distintos|
|Ejecución|Recover depende del orden de apertura, lease, lectura y escritura|Separar inspección, decisión y aplicación|
|Timing|Watchdog, resultado y terminación del shell dependen de carreras|Decisor de terminal y propiedad del ciclo de vida|
|Identidad|IDs de operaciones interpretados mediante convenciones de cadenas|Resolver la identidad una sola vez|
|Posición|Algunas funciones agrupan numerosos parámetros relacionados|Requests y contextos tipados, sin bolsas genéricas|
|Algoritmo|Varias proyecciones reconstruyen asociaciones entre eventos|Compartir la regla de correlación|

Principio de diseño: connascencia fuerte cerca, connascencia débil lejos. No se creará una abstracción nueva si únicamente desplaza la complejidad.

## 3. Roadmap propuesto

## H1 — Corregir invariantes de recuperación

P0

Alcance: `pipeline-runtime`, `pipeline-runtime-api` y `pipeline-application`.

Este hito corrige los tres defectos funcionales principales de la auditoría anterior, antes de mover sus implementaciones.

- `--dry-run`: ninguna adquisición de lease ni recuperación implícita con escritura.
    
- `deadlineMs`: reemplazar el cálculo desde `fencingToken` por una decisión temporal coherente con `Clock`.
    
- `cursors.load`: diferenciar ausencia legítima de error de lectura.
    
- Reutilizar los puertos y ADTs actuales siempre que expresen correctamente estos casos.
    
- Conservar los formatos de almacenamiento existentes.
    

Entrega verificable: recuperación real e inspección simulada con resultados coherentes; ninguna repetición de efectos externos y fallos de almacenamiento expresados mediante negativas tipadas.

### Estado de H1 — cerrado

Los tres invariantes están corregidos y la puerta completa está en verde.

|Defecto|Corrección|Commits|
|---|---|---|
|`--dry-run` tomaba la lease|La acquisition se declara en vez de hacerse; el dry run no escribe|`eb9a22723`|
|El plazo se calculaba desde `fencingToken`|El decider no calcula tiempo; el plazo lo lee el adaptador de su propio reloj|`eb9a22723`|
|La lectura fallida del marcador se leía como ausencia|Una lectura que falla es `StorageError`; solo la ausencia es ausencia|`eb9a22723`|

**Corrección de una fila de esta tabla (2026-10-26).** La fila de `--dry-run` afirmaba que «el dry run no escribe». Era falsa en dos puntos: el dry run ABRÍA el carril de escritura —que fija `PRAGMA journal_mode = WAL`, una escritura de cabecera— y contestaba una decisión distinta de la de la recuperación real. La afirmación se retira aquí; el corte que la corrige y la evidencia que lo sostiene están en «El corte: el dry run y la recuperación dicen lo mismo», más abajo en este mismo hito.

La ruptura de ABI que implica el plazo —`RecoveryChoice.Reattach` pierde `deadlineMs`— está autorizada por el maturity `EXPERIMENTAL` de `pipeline-runtime-api` y recibida en `published-contract-exceptions.json` (entrada 21, con el SHA que la introduce). El tren 0.90.0 quedó cerrado en `v0.90.0`; como el árbol ya no producía los mismos bytes, la 0.91.0 se abre aquí (`ed22ff91f`) y su candidata es `v0.91.0-rc1`.

Medido sobre `bd5edb8de` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=bd5edb8de33a89e6b9f56d80c5ea09bc3c1acfe4  elapsed=170s  budget=900s  tests=rewritten:1339 read:0  scenarios=observed:62 executed:62
```

- 183 informes JUnit reescritos por esta ejecución, 1339 tests, 0 fallos, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde, 588 comprobaciones sobre 112 ejecuciones.
- 170 s de reloj de pared frente al presupuesto de 900 s.

### Publicación de `v0.91.0-rc1` — verificada sobre el remoto

La candidata se publicó desde `1ac1afe7` y la publicación se comprobó sobre el remoto, no sobre el árbol local:

```
release-pipelinek: PUBLISHED v0.91.0-rc1 with candidate_id sha256:b847f81d80c85d431fb552290dc2dc3926106d31c64d317b3333dfae2f7732b7
```

- El tag remoto es **anotado**: `git ls-remote --tags origin v0.91.0-rc1` devuelve el objeto de tag `c00cbbc6` y, con él, el peel `refs/tags/v0.91.0-rc1^{}` al commit `1ac1afe7`. Un tag ligero no tendría esa segunda línea.
- La firma **verifica** contra la clave declarada `9600F7D49B9C966E3BB04E6A17E44CA7C5288281` (uid `test@example.com`), comprobada sobre el objeto descargado del remoto y no sólo sobre la copia local.
- La prerelease de GitHub existe (`draft=false`, `prerelease=true`) con 7 assets. Los seis digests de `SHA256SUMS` coinciden con los assets descargados, y el digest de `pipelinek-0.91.0.zip` es exactamente el `candidate_id` anunciado.
- `git-head.txt` y `manifest.json` nombran el commit `1ac1afe7`, que es el mismo commit al que apunta el tag.

No queda ninguna publicación pendiente de H1.

#### El corte: el dry run y la recuperación dicen lo mismo

**Qué hace.** Un `runs recover --dry-run` contesta «qué haría la recuperación» sin escribir nada. La tabla de H1 afirma, desde `eb9a22723`, que «el dry run no escribe». La afirmación era falsa en dos puntos distintos, y este corte los corrige a los dos y retira la afirmación vieja.

**La primera falsedad: el dry run escribía.** Cumplió a medias. Dejó de TOMAR la lease —eso sí era cierto desde `eb9a22723`— pero ABRÍA el carril de escritura del event store (`MainRunsCli.kt:220`). Ese carril no es una puerta sin cerradura: `SqliteConnectionFactory.open` fija `PRAGMA journal_mode = WAL`, que es una escritura de cabecera, y el cierre de una conexión escribible hace checkpoint del WAL. El dry run abre ahora el carril de LECTURA (`MainRunsCli.kt:220`), la puerta por la que el motor se niega a escribir.

**La segunda falsedad, más honda: el dry run contestaba una cosa distinta.** Y aquí la culpa no era del dry run sino del JOURNAL. La fila RUNNING se escribía SIN la atribución del run, y `listForRun` filtraba por esa atribución: la fila que dice «esta operación sigue en curso» era una fila INVISIBLE hasta que la operación terminaba. Un run que moría a mitad de una operación dejaba una fila que no nombraba ningún run, así que todo lector que no pudiera reparar la columna lo veía TERMINADO.

Y la respuesta DEPENDÍA DE QUÉ CARRIL PREGUNTABA: el carril de escritura repara la columna al abrir (`SqliteEventStore.kt:263`, `backfillRunId`) y el de lectura no puede escribir, porque abre la base por una puerta que el motor no deja escribir. Medido, sobre el mismo run y el mismo journal:

|Carril|Filas que ve|Fila terminal del run|Decisión|Lo que dice|
|---|---|---|---|---|
|lectura|`[SUCCEEDED]`|`succeeded`|`reuse-terminal`|«would recover … as succeeded»|
|escritura|`[SUCCEEDED, RUNNING]`|`null`|`reattach`|«still in flight; nothing was recovered»|

El dry run leía por el carril de lectura y la recuperación real por el de escritura. Dos respuestas para el mismo run, y el operador no tenía forma de saber cuál era la verdadera.

**La corrección son dos mitades, y ninguna basta sola.**

- `LeaseLiveness` (nuevo) separa DOS preguntas que estaban confundidas: «¿quién tiene la lease?» —el registro durable lo responde, y sobrevive a la muerte de su dueño, así que dice «vivo» de un proceso asesinado hace una hora— y «¿hay ALGUIEN VIVO publicando este run?» —que sólo responde el cerrojo del sistema operativo. `FileBackedRunExecutionLeaseStore.liveness` (`FileBackedRunExecutionLeaseStore.kt:231`) pregunta al núcleo, y sus tres respuestas son `Held`, `Free` y `Unverifiable`. `Unverifiable` NUNCA se colapsa en `Free`: «no lo sé» y «nadie la tiene» son opuestos, y el segundo autoriza a escribir.
- El dry run pregunta al kernel y se niega con la misma negativa que la recuperación (`RecoverAdapters.kt:229`): `RecoverRefusal.LeaseHeldByAnother` para una lease viva de otro, `RecoverRefusal.StorageError` para una pregunta que el kernel no pudo contestar. Un dry run que dijera «reattach» para un run que otro proceso está trabajando mandaría al operador a un plan que la recuperación real se negaría a ejecutar.
- `LeaseAsAcquired` (nuevo) presenta el registro que la adquisición HABRÍA dejado, con los tres casos que el decidor puro produce. Con eso, dry run y recuperación deciden sobre los MISMOS hechos, sin que ninguno tenga que adivinar. La reescritura `asIfTheLeaseWereOurs` —que vivía dentro del adaptador— desaparece: no podía arreglar el defecto, porque la introspección RECHAZA antes de que el adaptador tenga una observación que reescribir.
- La fila RUNNING nace atribuida a su run: `beginOperation` lee el run id del propio input de la operación (`OperationJournal.kt:285`, `InMemoryOperationJournal.kt:123`) y lo escribe con la fila. Un input mal formado falla ahí, en voz alta, en vez de producir una fila que no pertenece a nadie.
- Y la lectura no depende de una reparación: `listForRun` lee también la atribución que falta en las filas que escribió una build anterior (`OperationJournal.kt:407`). Un lector no puede repararlas, luego tiene que leerlas a través del input; si creyera sólo en la columna, un run con la fila huérfana seguiría pareciendo finished después de la corrección.

`backfillRunId` sigue existiendo, pero ya no es lo que hace correcta a la lectura: es una NORMALIZACIÓN de lo que escribió una build anterior, y su KDoc lo dice. La lectura es correcta con o sin ella.

**La garantía, la capacidad y el instrumento.** La garantía es que el dry run y la recuperación dicen lo mismo, y que el dry run no escribe. La capacidad que sostiene es que un operador puede preguntar «¿qué harías?» antes de ordenar «hazlo» y obtener una respuesta que el producto va a cumplir: sin esta garantía, el dry run es un adorno que puede anunciar un éxito que la recuperación real se negará a producir. El instrumento es DOBLE, y cada uno cubre una mitad distinta de la garantía:

- El escenario E2E `durable/40-recover-refuses-a-half-finished-run` (`examples/run.sh`): compara los BYTES durables —base, WAL y todo el árbol del directorio de control— antes y después del dry run, y compara la DECISIÓN de los dos como una palabra. La decisión se compara como palabra y no como frase porque la respuesta lleva consigo un instante («retry by 2026-10-26T21:57:36.164Z») que difiere entre dos invocaciones de la MISMA decisión: comparar las frases reportaría una divergencia donde no la hay. El fichero `-shm` queda fuera de la comparación a propósito: es el índice WAL de SQLite, un fichero DERIVADO que el motor reconstruye desde el WAL, cuya lectura mueve una conexión de solo lectura; incluirlo mediría la contabilidad del motor y la reportaría como una escritura del producto.
- La fila unitaria `OperationJournalRunAttributionTest` (nueva, `pipeline-events-store`): el journal en memoria y el SQLite, uno por implementación, porque el producto los declara espejos y el defecto estaba en los dos. La tercera fila reproduce la fila que una build anterior dejó sin atribución —la borra con un `UPDATE`— y comprueba que un lector la sigue viendo.

**Visto en rojo antes del corte.** Los dos instrumentos se vieron rojos contra el árbol sin corregir, y las mutaciones se leyeron de vuelta antes de afirmar nada.

El E2E, contra el binario sin corregir:

```
FAIL durable/40-recover-refuses-a-half-finished-run.pipeline.kts the dry run decided 'reuse-terminal' and the recovery decided 'reattach'
```

El unitario, con el código de producción revertido a su forma previa —el `INSERT` sin la columna `run_id`, el `listForRun` sin la lectura a través del input, y el journal en memoria con `runId = null`— y esa mutación leída de vuelta antes de ejecutar:

```
INSERT INTO operation_journal
    (op_id, fingerprint, status, kind, attempt, input, output, started_at, created_at, updated_at, deadline_ms)
WHERE j.run_id = ?
```

y el resultado, leído del XML de JUnit y no del texto del build:

```
tests=3 failures=3 errors=0 skipped=0
RED  el journal en memoria lista la operacion que sigue en curso
RED  el journal sqlite lista la operacion que sigue en curso
RED  una fila sin atribucion sigue siendo del run que la escribio
```

Las TRES filas del instrumento se pusieron rojas —son exactamente las tres que guardan el defecto— y ninguna otra. La fila que se ve en el detalle es la tercera: `expected: <1> but was: <0>` en `OperationJournalRunAttributionTest.kt:152`. La mutación se revirtió, se leyó de vuelta y las tres volvieron a verde.

**Medido sobre `ec21fa17a` con `scripts/verify-all.sh` sin filtros:**

```
PASS  sha=ec21fa17a184df713d0f51cbdab434c5b3411899  elapsed=139s  budget=900s  tests=rewritten:1440 read:0  scenarios=observed:63 executed:63
```

- 1440 tests reescritos por esta ejecución, 0 fallos, 0 errores, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 139 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 26902`, `PRODUCT_LOC = 70973`, ratio `0.379` (límite 2). El censo cuenta 174 ficheros de tests unitarios y 63 programas E2E, sobre el árbol VERSIONADO: una medición sobre árbol sucio leería un test nuevo como cero, y el ratio mentiría a favor.
- El corte, en líneas brutas sobre 16 ficheros: +843 / −79.

**Lo que este corte NO arregla, y se dice.** No arregla el informe falso del avance del cursor: `RecoverAdapters.kt:500` sigue diciendo `cursorAdvanced = cursor != null`, que afirma un avance que una lectura fallida no puede sostener. No arregla la expiración falsa de «sin timeout»: `ShellWatchdog.pollDeadlineFor` (`ShellWatchdog.kt:429`) devuelve `NO_TIMEOUT_POLL_MS` —una hora— cuando `timeoutMs` es 0, luego un paso sin plazo expira igual a la hora; la fila de `ShellWatchdogTest` que fija esa regla queda pendiente de sustituir. No arregla la ayuda de `runs recover` ni la inconsistencia de `CLI_VERBS`, que pertenecen a H5.

Declara además tres consecuencias de este corte que no son defectos:

- `ParallelStageEngine` y `ParallelReconciler` ven ahora la hija en curso de una rama: una rama con una hermana SUCCEEDED y otra RUNNING pasa a ser INCOMPLETA (se reanuda) en vez de reutilizarse como SUCCEEDED. Es la CORRECCIÓN de un defecto latente de la misma causa, y falla cerrada.
- `listForRun` llama a `json_extract` sobre el input de las filas sin atribución, y SQLite lanza «malformed JSON» si ese input no lo es. Ninguna build de este producto produce un input que no sea JSON serializado, luego la condición no es producible; se deja FALLA ALTA en vez de excluir la fila en silencio, porque una fila que no se puede atribuir no debe desaparecer de un recuento.
- El snapshot de detekt no se toca: los dos hallazgos que este corte introducía —`UnsafeCallOnNullableType` por un `!!` y `UnusedImport`— se corrigieron EN EL CÓDIGO, no se congelaron.

|Garantía|Original|Sustituto o justificación|Evidencia|Decisión|Coste|Riesgo|
|---|---|---|---|---|---|---|
|El dry run y la recuperación dicen lo mismo|Ninguno: la fila de H1 afirmaba que el dry run no escribía, y nadie lo contrastó contra la recuperación real|Los dos se ejecutan sobre el MISMO run y se comparan sus DECISIONES; el escenario E2E `durable/40` los invoca en ese orden|Visto en rojo antes del corte: `the dry run decided 'reuse-terminal' and the recovery decided 'reattach'`; verde después sobre `ec21fa17a`, 63 de 63 escenarios|Replace (la afirmación vieja)|16 ficheros, +843 / −79 líneas brutas|Bajo: el dry run ahora se niega con la misma negativa que la recuperación, y no ofrece un plan que la recuperación real rechazaría|
|El dry run no escribe|Ninguno: la puerta medía que el carril de escritura se abría, no que el dry run no escribiera|Comparación de los BYTES durables —base, WAL y árbol del control— antes y después del dry run, dentro del mismo escenario|Mismo escenario; el digest es idéntico antes y después sobre `ec21fa17a`|Add (una comprobación que faltaba)|92 líneas en `examples/run.sh`|Bajo: si el dry run volviera a escribir, la fila lo diría por digest y no por opinión|
|La fila en curso del journal pertenece a su run|Ninguno: `listForRun` filtraba por una columna que la propia fila tardaba en rellenar|La fila se atribuye al nacer, y la lectura atraviesa la atribución que falta en las filas de una build anterior|`OperationJournalRunAttributionTest`: 3 filas, vistas en rojo con la mutación aplicada y leída de vuelta, y en verde tras revertirla|Add (3 filas unitarias)|177 líneas de test, 0 de infraestructura|Bajo: el formato durable no cambia; la columna `run_id` ya existía y ya estaba indexada|
|La pregunta de «quién publica este run» tiene una sola autoridad|Ninguno: el registro durable respondía «vivo» de un proceso muerto|Una pregunta nueva al kernel, con tres respuestas que no se confunden entre sí|`LeaseLiveness` y su `Unverifiable`, que no colapsa en `Free`; fila del E2E y del unitario del adaptador de recuperación|Add (el puerto nuevo) sin quitar el registro, que sigue siendo la atribución|45 líneas de producto|Bajo: el registro durable no se borra; sólo deja de ser la autoridad sobre la LIVENESS|
|La semántica pública de la recuperación no cambia|Los 63 escenarios E2E y las 1440 pruebas unitarias de la puerta completa|Los mismos, sin cambio: lo que cambia es qué hechos ve el dry run y qué dice|La puerta completa sin filtros sobre `ec21fa17a`: 1440 tests, 0 fallos, 0 omitidos, 63 de 63 escenarios en verde|Retain|0|Bajo: los tipos publicados de `pipeline-runtime-api` no se tocan; `RecoverReport`, `RecoverOutcome`, `RecoverOptions` y `RecoverRefusal` conservan su forma|
|Una rama con una hija en curso se reanuda en vez de reutilizarse|Los consumidores de `listForRun`, que no veían la hija en curso|Corrección de la misma causa: ahora la ven, y la rama pasa a ser INCOMPLETA|Medido por lectura del camino: `ParallelStageEngine` y `ParallelReconciler` dejan de reutilizar como SUCCEEDED una rama con una hermana RUNNING|Replace (un defecto latente corregido)|0|Bajo, y falla cerrada: reanudar es más caro que reutilizar, nunca al revés|

## H2 — Refactor del núcleo durable del SDK

Foco principal: `DurableShellExecutor.kt` — aproximadamente 1.803 líneas.

Actualmente concentra materialización de scripts, construcción del wrapper, creación de FIFOs, lanzamiento del agente, procesos, watchdog, cancelación y limpieza.

La separación propuesta conserva `DurableShellExecutor` como fachada compatible y extrae responsabilidades internas:

|Componente interno|Responsabilidad única|
|---|---|
|`ShellProtocolLayout`|Nombres y rutas del protocolo persistente|
|`ShellLaunchPreparation`|Preparar scripts, wrapper y entorno|
|`ShellProcessLauncher`|Lanzar procesos y devolver su identidad|
|`AgentRendezvous`|Coordinar los FIFOs y el agente ya existente|
|`ShellWatchdog`|Vigilar tiempos y solicitar terminación|
|`ShellTerminalDecision`|Clasificar salida, timeout o fallo|
|`ShellResourceCleanup`|Liberar los recursos pertenecientes al lanzamiento|

Los nombres son orientativos. Antes de crear cada componente se debe comprobar si `DurableShellFiles`, `LaunchedShell`, `TranscriptDrain` u otra abstracción ya resuelve esa responsabilidad.

No habrá dos motores shell. `executeCore()` debe convertirse en un coordinador legible, no duplicarse en una nueva implementación.

Entrega verificable: ejecutar, terminar, cancelar y recuperar `sh` con las mismas salidas, efectos y protocolos persistentes.

#### El corte: el informe de la recuperación no afirma lo que nadie observó

**Qué hace.** `RecoverAdapters.dispatchReuseTerminal` publicaba un informe con dos afirmaciones que no eran observaciones:

- `cursorAdvanced` decía si un marcador de reanudación EXISTÍA antes de la recuperación, no si la recuperación lo movió. La línea era `cursorAdvanced = cursor != null`, y `cursor` era el marcador leído ANTES de avanzar. Un marcador que existía y no se pudo mover se reportaba como avanzado, y uno que la recuperación creó se reportaba como no avanzado. El campo era verdadero en un caso y falso en el otro, y ninguno de los dos tenía que ver con el avance.
- la excepción de `cursors.advance()` caía en un `catch (_: Exception)` vacío, con un comentario que decía que el fallo «surfaces in the report rather than as a refusal». No surfacaba en ningún sitio: el informe salía como si el avance hubiera pasado.

El lado de la LECTURA ya tenía el precedente, escrito en este mismo hito: un marcador que no se puede LEER es `StorageError`, y sólo la ausencia es ausencia. El lado de la ESCRITURA es el mismo hecho —un sustrato que se niega a decir dónde está el run no puede reportarse como si lo hubiera dicho—.

**El corte.** El avance falla cerrado con `RecoverRefusal.StorageError`, que nombra la escritura que falló (`the replay cursor could not be advanced: …`). El rechazo es seguro de reintentar: el apéndice del diario es idempotente, luego un segundo intento no compromete nada dos veces. El recuento de filas del diario deja de mentir en el mismo sentido: `journalRowsCommitted` se leía del diario antes y después, y si la relectura fallaba devolvía el número anterior —«cero filas comprometidas» para un apéndice que sí había comprometido una—; ahora una relectura que falla es también `StorageError`.

`cursorAdvanced` se calcula desde el avance: `true` cuando el avance no falló. El idempotente del avance no lo vuelve falso: un marcador que ya estaba allí sigue estando allí, y el informe dice que el cursor está donde el avance lo dejó.

**La garantía, la capacidad y el instrumento.** La garantía es que el informe de una recuperación dice lo que la recuperación hizo. La capacidad que sostiene es que un lector de ese informe —o del rechazo que lo sustituye— puede creerlo, porque ninguna de sus afirmaciones procede de una existencia anterior en lugar de de una escritura observada. El instrumento es un test unitario (`RuntimeRecoverPortStoreAdapterTest`), porque la decisión es el efecto de un puerto: un `ReplayCursorStore` en memoria cuya escritura falla, y la lectura del veredicto.

**Visto en rojo antes del corte.** Dos mutaciones discriminantes, cada una aplicada, leída de vuelta con `git diff` y revertida:

|Mutación|Fila que se puso roja|Filas del lote|
|---|---|---|
|El `catch` del avance vuelve a tragarse la excepción|`RuntimeRecoverPortStoreAdapterTest > un cursor que no se puede avanzar no se reporta como avanzado()`|15 tests, 1 rojo, sin colateral|
|`cursorAdvanced = ready.cursor != null`|`RuntimeRecoverPortStoreAdapterTest > un cursor creado por la recuperacion se reporta como avanzado()`|15 tests, 1 rojo, sin colateral|

**Lo que este corte NO arregla, y se dice.** No arregla la SEGUNDA propiedad del marcador: `RecoverAdapters` sigue siendo un segundo dueño del marcador de reanudación, porque `RecoveryInterpretationEngine` lo dejó a propósito y nadie lo reclamó después. Eso es trabajo de un corte posterior de este mismo hito, no de este corte. No arregla que el informe, cuando todo va bien, siga siendo un informe que el operador no ve: la capacidad que este corte sostiene es la del RECHAZO —un rechazo se imprime y se nombra—, no la del informe.

**Medido sobre `2e3a1efd5` con `scripts/verify-all.sh` sin filtros:**

```
PASS  sha=2e3a1efd5faf41c198feec425a62f8c66718c2f1  elapsed=108s  budget=900s  tests=rewritten:1460 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1460 tests, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 108 s de reloj de pared frente al presupuesto de 900 s — cifra que la build cache contaminó: la medición
  real de este árbol es de 188 s, corregida con su reejecución más abajo (corte del gate).
- `VERIFY_LOC = 27278`, `PRODUCT_LOC = 71109`, ratio `0.384` (límite 2).

**Corrección de este bloque, medida con el instrumento arreglado.** Las 108 s de esta línea tampoco eran una medición del trabajo —la build cache de Gradle estaba activa—, y el «1460 tests reescritos» incluía informes servidos en vez de ejecutados. Reejecutando ESTE MISMO árbol con el gate arreglado —build cache de construcción desactivada y censo leyendo el sello del informe— la línea es:

```
PASS  sha=2e3a1efd5faf41c198feec425a62f8c66718c2f1  elapsed=188s  budget=900s  tests=rewritten:1460 read:0  scenarios=observed:63 executed:63
```

El coste real de esta tanda de cortes fue de 188 s, no de 108 s. Los tres bloques que citan esta ejecución comparten la corrección. Ver el corte del gate, más abajo.

### Estado de H2 — cerrado

Los siete componentes están extraídos, la puerta completa está en verde sobre el árbol publicado y la candidata de fin de hito `v0.91.0-rc3` está publicada y verificada sobre el remoto. La evidencia de esa publicación está al final de este hito.

Antes de crear cada componente se comprobó qué abstracción existente ya es la dueña de cada responsabilidad. El resultado, medido sobre `DurableShellExecutor.kt` (1.802 líneas):

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Transcripto de consola: nombre canónico y compatibilidad de lectura|`DurableShellFiles` (41 líneas)|Ya es la dueña del nombre `console.log`; se conserva y se le añade el resto del protocolo|
|Identidad de un lanzamiento: hijo, bombas, sumideros, descriptores de calentamiento|`LaunchedShell`|Ya es la forma de retorno del lanzador; no se crea otra|
|Bombas de transcripción, drenaje y veredicto de persistencia|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Materialización de scripts y wrapper, FIFOs, agente, watchdog, cookie-scan, limpieza|Dentro del ejecutor|Es lo que se extrae|

La investigación previa contó 26 apariciones de literales del protocolo (`.cookie`, `result.txt`, `result.txt.tmp`, `script.sh`, `script.sh.copy`, `wrapper.sh`, `output.txt`, `fifos/stdout.fifo`, `fifos/stderr.fifo`, `fifos/agent.ready`, `agent.log`, `timeout.flag`) repartidas entre el ejecutor y el contenido del wrapper. Faltaba, por tanto, una autoridad única de los nombres del protocolo persistente. `ShellProtocolLayout` era el primero de los siete componentes, y el que menos mueve comportamiento.

#### El corte: `ShellProtocolLayout`

Los nombres pasaron de literales a propiedades de una clase que se construye con el directorio de control. `ShellProtocolLayout` (en `pipeline-step-sdk:runtime`) publica los trece nombres del protocolo y las rutas que se derivan de ellos; el nombre del transcripto de consola no se replica, delega en `DurableShellFiles`, que sigue siendo su única dueña.

Cuatro consumidores se unieron: el ejecutor (escribe), `StepReconcilerL1` (reconcilia), `RunningSubprocessRecovery` (observa el valor capturado) y `ObsPc2IngestAgent` (abre los FIFOs). El cuarto no estaba en el inventario inicial y apareció al barrer el árbol con los literales del protocolo: una copia de los nombres que nadie había contado también es una copia que puede derivar.

Medido sobre el árbol tras el corte, los únicos ficheros que aún escriben un literal del protocolo son la propia autoridad, el test que fija el formato, y `durable.task.ProcessDurableTaskRuntime` — que es OTRO protocolo, con otro directorio de control y otro contenido en su `result.txt` (un REGISTRO terminal, no un código de salida). Mezclarlos fundiría dos formatos que sólo comparten dos palabras.

Dos documentos falsos se corrigieron: la tabla "Control Directory Layout" de `DurableShConfig` nombraba `jenkins-log.txt` como si fuera el transcripto, y el KDoc del ejecutor afirmaba que el script del usuario se detectaba y se rechazaba en el argv. La primera frase era falsa; la segunda describía un mecanismo que el código no tiene. Un comentario que afirma una propiedad que el cableado no tiene es peor que ningún comentario: convierte una ausencia en una garantía.

**La garantía, la capacidad y el instrumento.** La garantía es que el escritor y los lectores del protocolo se encuentran: el wrapper publica el código de salida donde el ejecutor lo sondea y donde un proceso posterior decide entre "terminado", "expirado" y "perdido". La capacidad que esa garantía sostiene es que un paso `sh` durable se puede ejecutar, expirar y reconciliar más tarde desde su directorio de control. El instrumento es un test puro: ni proceso, ni hilo, ni reloj, ni sistema de ficheros. Fijar los nombres como literales no congela un detalle de implementación — son el formato durable que otro proceso, y un operador con un directorio delante, leen; el test es donde queda registrada esa decisión.

**Visto en rojo dos veces.** Antes de dar el corte por bueno, el instrumento se rompió a propósito dos veces, leyendo el diff antes de leer el resultado:

- El escritor ata una ruta distinta de la del layout: se enciende exactamente una fila, la que comprueba que el wrapper ata las rutas del protocolo.
- La constante `RESULT_FILE` pasa a `result.bin`: se enciende exactamente una fila, la que fija los nombres, mientras la anterior sigue en verde. Esa ortogonalidad es lo que prueba que las dos filas miran cosas distintas.

Medido sobre `f5968af75` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=f5968af756cf2faa943ea9af85828fd48f52694a  elapsed=141s  budget=900s  tests=rewritten:1344 read:0  scenarios=observed:62 executed:62
```

- 184 informes JUnit reescritos por esta ejecución, 1344 tests, 0 fallos, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde, 588 comprobaciones sobre 112 ejecuciones.
- 141 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25449`, `PRODUCT_LOC = 71128`, ratio `0.358` (límite 2). El censo excluye 2 ficheros como generados (los dos Java de `buildSrc`, cuya ruta de paquete contiene `/build/`) y deja 53 sin contar en ninguna parte (README, `AGENTS.md`, `ROADMAP.md`, ficheros de contrato `.api`, bases de detekt y el `gradle-wrapper.jar`).

La release candidata de este corte se publicó desde `c868ec136`, y la publicación se comprobó sobre el remoto y no sobre el árbol local:

```
release-pipelinek: PUBLISHED v0.91.0-rc2 with candidate_id sha256:5ae24f3f8392d19ab5cdacea24cb2c8c011e641aaebe7bf6add5d9d5b25cbff5
```

- `git ls-remote --tags origin 'v0.91.0-rc2*'` devuelve DOS líneas: el objeto de tag ANOTADO `cff7fe44bebc07bc0e611dbcb844cbff9b61b3f2` y el peel `refs/tags/v0.91.0-rc2^{}` al commit `c868ec136baa96e4ce7d9d21bd258adff0546ae2`. Un tag ligero no tendría esa segunda línea.
- El objeto de tag descargado FRESCO del remoto es de tipo `tag` y lleva un bloque PGP. La firma verifica contra la clave declarada `9600F7D49B9C966E3BB04E6A17E44CA7C5288281` (uid `test@example.com`) mediante `scripts/release/verify-tag-signature.sh`, con las tres filas en PASS: A, el objeto está anotado y la firma verifica; B, la huella y el uid son los de la clave declarada; C, el tag apunta al commit esperado.
- La prerelease de GitHub existe con `draft=false`, `prerelease=true` y el conjunto EXACTO de 7 assets. Los seis digests de `SHA256SUMS` coinciden con los assets descargados, y el digest de `pipelinek-0.91.0.zip` es exactamente el `candidate_id` anunciado.
- `git-head.txt` y `manifest.json` nombran `c868ec136…`, el mismo commit al que apunta el tag.

#### El corte: `ShellLaunchPreparation`

El ejecutor escribía los ficheros del protocolo y componía el entorno del hijo dentro de `launch`, entre las decisiones de descriptores. La composición era una política de cuatro reglas que sólo se podía leer en línea y que no se podía comprobar sin lanzar un proceso. `ShellLaunchPreparation` (222 líneas) la recoge: materializa el script, su copia y el wrapper, y compone el entorno. `DurableShellExecutor` queda como coordinador —lanza, vigila y decide el desenlace— y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Preparar scripts, wrapper y entorno|Dentro del ejecutor, en `launch`|Es lo que se extrae|
|Orden de precedencia del entorno|`GateEnvironmentPrecedence` (dominio, `pipeline-domain`, creado en `e0e32abae`)|Ya es la dueña de la regla "declarado gana"; no se crea otra|
|Componer el entorno del hijo|`EnvironmentComposer` (155 líneas)|Parecía la dueña y no lo era: cero llamadas y no gobernaba nada — se elimina|
|Transcripto de consola: nombre canónico|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacto|
|Identidad de un lanzamiento: hijo, bombas, sumideros|`LaunchedShell`|Es la forma de retorno del lanzador, no una segunda política; se conserva intacta|

`EnvironmentComposer` merecía una comprobación aparte, porque su nombre prometía exactamente la responsabilidad del corte. Medido: `grep` sobre el árbol devuelve **cero** referencias en código Kotlin y **cero** en los ficheros de contrato `.api`; no estaba publicada ni formaba parte de ningún contrato de compatibilidad. Sus 155 líneas eran un segundo sitio donde la política del entorno podía vivir, y nadie lo llamaba. Se borró, y el comentario de `CompositionRoot` que la nombraba pasó a nombrar la autoridad real, `GateEnvironmentPrecedence.DEFAULT` (`DeclarationWins`).

**El entorno como función.** Antes, `composeChildEnvironment(pb, …)` mutaba el `ProcessBuilder` del lanzador. Ahora la composición es una función de entorno a entorno: `composeEnvironment(ambient, sandbox, userEnv): Map<String, String>` devuelve el entorno del hijo, y el lanzador toma una foto del ambiente (`pb.environment().toMap()`), lo vacía y adopta el mapa compuesto entero (`DurableShellExecutor.kt:479-482`). La política —sentinelas de identidad, lista denegada y normalización del PATH bajo el perfil local, variables del llamante por último, y normalización de nuevo si el llamante nombra un PATH— se lee ahora como una política y se comprueba como una política: sin proceso, sin hilo, sin reloj.

**Un `outerRedirect` que se calculaba y no se usaba.** El wrapper construía una redirección (`"> fichero"`, `2>&1`, …) dentro de un `when` y **nunca la interpolaba** en su contenido: el wrapper no abre el transcripto, el lanzador redirige los descriptores. Su comentario afirmaba una propiedad que el cableado no tiene. Se eliminó. El cambio es inocuo porque la cadena nunca llegó al contenido del wrapper: las cuatro variantes de redirección por los dos modos de bombeo producen exactamente el mismo resultado.

**La garantía, la capacidad y el instrumento.** La garantía es que el hijo recibe un entorno cuyo alcance no depende de quién pregunta: la identidad de la operación viaja, la lista denegada no alcanza al hijo bajo el perfil local, el PATH sólo conserva prefijos autorizados y un llamante no puede ampliar ese alcance por la puerta de atrás. La capacidad que esa garantía sostiene es que un paso `sh` durable se ejecuta con el entorno que su autor declaró, y no con el que hereda la máquina. El instrumento es un test puro de la política —13 tests de JUnit, sin proceso, hilo, reloj ni sistema de ficheros— porque una garantía de entorno se puede observar sin lanzar nada; lo que sólo aparece al ejecutar sigue cubierto por los escenarios E2E, y un segundo instrumento para lo mismo sería duplicación, no evidencia.

**Visto en rojo tres veces.** Cada mutación se leyó en el diff antes que en el resultado, y después de cada una el árbol se restauró y se comprobó limpio:

|Mutación|Filas que se encendieron|
|---|---|
|Quitar la aplicación de la lista denegada|1 — `the deny-listed variables do not reach the child under the local profile`|
|Quitar la segunda normalización del PATH del llamante|3 — `a prefix named in the keep set survives the filter`, `a caller's path cannot smuggle an entry past the filter`, `the path keeps only the sanctioned prefixes under the local profile`|
|Vaciar la identidad de la operación|1 — `the child's environment carries the operation's identity`|

Ninguna encendió filas ajenas a la regla quitada: cada mutación encendió exactamente las que la describen, y ninguna más.

**Una fuga que el propio test podía producir.** El primer borrador comparaba el entorno entero, y su mensaje de fallo volcaba el mapa completo —el mapa completo del entorno de quien ejecuta los tests puede llevar dentro un token real— dentro del XML de JUnit, que se queda en disco. Un test de entorno no puede imprimir el entorno: se cambió por aserciones sobre nombres concretos. Comprobado después sobre los XML que escribió la ejecución del gate: ninguna coincidencia de `ANTHROPIC_AUTH_TOKEN`, `sk-cp-` ni `sk-ant-`, ni en los informes, ni en el árbol versionado, ni en el repositorio entero.

**Un hallazgo preexistente que este corte no arregla.** `EnvModel.apply` se aplica dos veces sobre las variables del llamante: una en la capa de aplicación (`ShExecution.kt:234`, `:256`, `:400`) y otra en el runtime (antes en `DurableShellExecutor.kt:493`, ahora en `ShellLaunchPreparation.kt:185`). El corte movió el sitio; no lo creó. Se reporta y no se arregla aquí: decidir cuál de las dos capas es la dueña de la coerción del PATH es una pregunta de la costura entre `ShExecution` y el runtime, y esa costura es de H3.

**El diagnóstico de detekt, que no era del código.** El gate de este corte se puso rojo en `detektMain` —la tarea que sí analiza el *source set*, a diferencia del `detekt` agregado, que no analiza ningún fichero y reporta verde— con dos `UseOrEmpty` en `ShellLaunchPreparation.kt:192-193`. La causa no era el código: el baseline suprime POR RUTA, y el código se había mudado de `DurableShellExecutor.kt` a su propio fichero, de modo que la supresión dejó de alcanzar a su sujeto. Se corrigió con `.orEmpty()` y retirando del baseline las entradas que ya no tenían sujeto (`7e3cf6d94`).

Lo mismo se repitió con cinco entradas más. Cuatro (`val cookieFile`, `val resultFile`, `val resultTmp` y `val scriptFile`, todas `= shellFiles.*`) quedaron huérfanas en el primer corte, que ya usaba `protocol.*`; la quinta, `val outerRedirect`, quedó huérfana en este. Que el texto de una entrada del baseline no aparezca en el código NO prueba que la entrada esté inerte: dentro de la ruta la entrada casa de forma laxa —hay una entrada viva cuyo texto ya no coincide con el código actual—, y sólo la ruta tiene que coincidir. La prueba es la inversa, quitar la supresión y ver qué hace el detector, y se hizo: `detektMain` siguió en verde tras retirar las cinco, así que ninguna tapaba un hallazgo real. Se retiraron (`0a7a7e7b4`). Una supresión sin sujeto no es una garantía: es deuda que finge ser política.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=0a7a7e7b40e103828306747239cec78860c741bc  elapsed=143s  budget=900s  tests=rewritten:1357 read:0  scenarios=observed:62 executed:62
```

- 185 informes JUnit reescritos por esta ejecución —todos con su marca de tiempo dentro de una ventana de 4 segundos—, 1357 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones; ninguno sin comprobar.
- 143 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25602`, `PRODUCT_LOC = 71061`, ratio `0.360` (límite 2). El censo cuenta 165 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 720 ficheros de código de producción como producto.

#### El corte: `ShellProcessLauncher`

El ejecutor lanzaba el hijo dentro de `launch`, entre la preparación del entorno y la decisión de qué hacer con su desenlace. El lanzamiento era un bloque de decisiones —el argv, el directorio de trabajo, el entorno, la redirección de la entrada, y una matriz de cuatro ramas que decidía dónde iban el stdout y el stderr— que sólo se podía leer en línea y que sólo se podía comprobar arrancando un hijo y mirando los ficheros que dejaba. `ShellProcessLauncher` (382 líneas) las recoge; `DurableShellExecutor` queda como coordinador y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Identidad de un lanzamiento: hijo, bombas, sumideros, descriptores de calentamiento|`LaunchedShell`|Es la FORMA de retorno del lanzador, no el lanzador; se conserva intacta y se sigue devolviendo sin cambios|
|Preparar scripts, wrapper y entorno|`ShellLaunchPreparation` (corte anterior)|No toca esta responsabilidad; se conserva intacta|
|Nombres y rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|No toca esta responsabilidad; se conserva intacta|
|Transcripto de consola: nombre canónico|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacta|
|Bombas de transcripción, drenaje y veredicto de persistencia|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Lanzar el hijo y enrutizar sus dos descriptores|Dentro del ejecutor, en `launch`|Es lo que se extrae|

El resultado de la comprobación es que no existía ningún componente que lanzara: `LaunchedShell` es la identidad que el lanzamiento devuelve, no el acto de lanzar. Se crea, y se sigue devolviendo.

**El enrutado pasa a ser un valor.** Dónde van el stdout y el stderr del hijo depende de dos hechos —si el stdout se captura y si los bytes se bombean por la JVM— y esa decisión era una matriz de ramas dentro del cuerpo del lanzamiento. Ahora es una función pura sobre el protocolo (`planChildDescriptors`), así que se puede leer como una regla y se puede comprobar como una regla, sin proceso, sin hilo, sin reloj y sin sistema de ficheros. Cada caso nombra el FICHERO al que apunta, no un enum: "dónde va el stderr" sólo tiene respuesta con una ruta, y una transcripción con dos escritores es un defecto distinto de un valor tipado contaminado por el stderr.

**La propiedad del agente se decide antes, y por qué.** El rendezvous del agente llega al lanzador como un callback (`attachRendezvous`) y no como un componente, porque el lanzador sabe CUÁNDO ocurre —entre configurar el proceso y arrancarlo: abrir una FIFO para escribir bloquea hasta que haya un lector— y no qué es el agente. El corte del agente (`AgentRendezvous`) es otro corte, y este no lo adelanta. El callback se invoca FUERA del `try`, para que un fallo al acoplarse salga como el error del agente y no como un fallo de lanzamiento envuelto: son dos hechos distintos y el llamante los lee distinto. Los descriptores de calentamiento se cierran en el `catch` si el hijo nunca arrancó, porque son los únicos escritores de las FIFOs y nadie más los cerrará.

**Qué se quita por medida, y no por forma.** `setsidPath`, `bashPath` y `resolveCommandPath` se midieron sobre el árbol anterior al corte (`0a7a7e7b4`): `setsidPath` y `bashPath` tienen **una** aparición cada una —su propia declaración— y cero lectores; `resolveCommandPath` tiene tres —su declaración y las dos que sólo se alcanzan desde esos `by lazy`—. Los dos eran `by lazy` y nunca se leyeron, así que su inicializador nunca corrió y ningún `command -v` llegó a ejecutarse: quitarlos no cambia el comportamiento observable, y era lo único que su lectura habría cambiado. `checkLinuxOrThrow` tenía dos lectores —el lanzamiento y el desprendimiento— y ahora es `requireLinuxHost()`, junto a la excepción que lanza, para que las dos puertas no puedan discrepar sobre la plataforma.

**La petición de ejecución sale del fichero del ejecutor.** `DurableShellExecutionRequest` y sus dos proyecciones (dónde acaba la transcripción y qué significa un timeout) salen de `DurableShellExecutor.kt`, donde eran `private` de fichero, a un fichero propio: el lanzador y el ejecutor son DOS colaboradores de la misma petición, y ninguno la posee. Declararla junto a la fachada obligaría al lanzador a depender del fichero de la fachada para la forma de su propio trabajo.

**La garantía, la capacidad y el instrumento.** La garantía es que los bytes del hijo llegan donde el modo de la ejecución exige: un stdout capturado es el valor tipado y NADA más llega a él —ni el stderr, ni los bytes de la transcripción—, el stderr es un canal distinto hasta el descriptor del núcleo, la transcripción tiene UN solo escritor, y cuando el agente de ingesta es el que bombea, los dos canales son sus FIFOs. La capacidad que esa garantía sostiene es que un paso `sh` durable devuelve un valor tipado que no está contaminado por los diagnósticos del propio paso, y que un lector puede seguir la transcripción y saber si mira el stdout o el stderr. El instrumento es un test puro de 12 filas de JUnit 5 —sin proceso, sin hilo, sin reloj, sin sistema de ficheros— porque el enrutado es una función pura de cuatro hechos: el instrumento más barato que puede fallar es una llamada a ella, y los escenarios E2E ya lanzan hijos reales y dicen si los bytes llegaron; esto dice QUÉ regla los envió.

**Visto en rojo seis veces.** Cada mutación se leyó en el diff antes que en el resultado, el árbol se restauró después, y cada ejecución se comprobó contra el XML de JUnit autoritativo —no contra la salida del build, que también dice "BUILD FAILED"—, con la condición de que corrieron las 12 filas:

|Mutación|Filas que se encendieron|
|---|---|
|El stdout y el stderr intercambian su destino|1 — `the captured stdout goes to the value file and the stderr to a pipe`|
|La rama de captura envía el stderr al fichero de valor|1 — `the captured stdout goes to the value file and the stderr to the transcript`|
|La rama del agente desaparece|1 — `the agent owns both channels when it is the one pumping`|
|`||` por `&&` en la regla de bombeo|2 — `the composition pumps the child's bytes when it has a redactor`, `the composition pumps the child's bytes when it has a channel destination`|
|El argv gana un `-c`|1 — `the argv is the wrapper and nothing else`|
|Los dos canales van a la transcripción|1 — `both channels go to the transcript when nothing is pumped`|

Cada mutación encendió exactamente las filas que la describen y ninguna más. La cuarta es la que importa más: al cambiar `||` por `&&` se encendieron las dos filas que afirman el bombeo, y la que afirma que NO se bombea siguió en verde —porque afirma lo contrario—, y ésa es la prueba de que las tres filas miran hechos distintos y no una sola regla repetida.

**El diagnóstico de detekt, que no era del código.** `detektMain` —la tarea que sí analiza el *source set*— se puso en rojo con un `SpreadOperator` en `ShellProcessLauncher.kt:123`: `ProcessBuilder(*shellLaunchArgv(…).toTypedArray())`. La causa era el constructor variádico, no la política: `ProcessBuilder` tiene un constructor que recibe la lista entera, y usarlo quita el `*` sin cambiar el argv. Además, el corte retira siete entradas del baseline cuyo sujeto había desaparecido; la prueba de que eran inertes es quitarlas y ver que `detektMain` y `detektTest` siguen en verde, no que su texto no aparezca en el código.

**Que `detektTest` no sea un verde vacío.** La tarea `detekt` agregada no analiza ningún fichero y reporta verde: un gate que nunca miró no puede estar en rojo. Eso se comprobó con una sonda —un import sin usar en el fichero de test— que hizo aparecer el `UnusedImport` nombrando el fichero de test y su línea, y se revirtió. `detektTest` analiza de verdad `src/test/kotlin`.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=d06e2202697ed4076867d79da3c38c915494a641  elapsed=189s  budget=900s  tests=rewritten:1369 read:0  scenarios=observed:62 executed:62
```

- 186 informes JUnit reescritos por esta ejecución —todos con su marca de tiempo dentro de una ventana de 9 segundos—, 1369 tests, 0 fallos, 0 errores, 0 omitidos. Las cifras se leyeron de los XML de la ejecución, no del resumen del gate: 1369 son los 1357 del corte anterior más las 12 filas nuevas.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 189 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 25750`, `PRODUCT_LOC = 71111`, ratio `0.362` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `913 / 2187 = 0.417`. El censo cuenta 166 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 722 ficheros de código de producción como producto.

#### El corte: `AgentRendezvous`

El ejecutor coordinaba FIFOs y agente dentro de `launch`, y el agente leía su argv **por posición**, en su propio `main`. El acuerdo entre los dos —qué valores viajan, en qué orden, con qué nombre de variable viaja el secreto— era una lista en un módulo y una lectura posicional en otro: dos cosas que se pueden separar sin que nada se rompa a la vista. `AgentRendezvous` (374 líneas) recoge el rendezvous completo: materializar los FIFOs, abrir el calentamiento, lanzar el agente y esperar su marca de listo. `DurableShellExecutor` queda como coordinador y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Los nombres y las rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|No toca esta responsabilidad; se conserva intacto|
|Preparar scripts, wrapper y entorno|`ShellLaunchPreparation` (segundo corte)|No toca esta responsabilidad; se conserva intacto|
|Lanzar el hijo y enrutar sus dos descriptores|`ShellProcessLauncher` (tercer corte)|No toca esta responsabilidad; se conserva intacto|
|La identidad de un lanzamiento: hijo, bombas, sumideros|`LaunchedShell`|Es la FORMA de retorno del lanzador; se conserva intacta|
|El nombre del transcripto de consola|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacta|
|Las bombas de transcripción y el drenaje|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Cuando ocurre el rendezvous (después de configurar el proceso, antes de arrancarlo)|El lanzador, que decide el CUÁNDO|El rendezvous sigue llegando como callback: el lanzador no sabe QUÉ es el agente|
|Coordinar los FIFOs y el agente ya existente|Dentro del ejecutor, en `launch`|Es lo que se extrae|

El resultado de la comprobación es que **no existía ningún componente que coordinara**: `LaunchedShell` es la identidad que el lanzamiento devuelve, `ShellProtocolLayout` es la autoridad de los nombres, y ninguno de los dos coordina a nadie. Se crea, y el lanzador lo invoca exactamente en el mismo punto en que lo invocaba antes.

**La duplicación tenía una causa, y la causa tiene dirección.** Medido sobre el árbol anterior al corte (`38ec2204c`), el acuerdo entre el runtime y el agente estaba escrito DOS veces, y el motivo estaba escrito en el propio código: el KDoc de `buildAgentArgv` decía *"Duplicated locally so the runtime does not import the agent's `argv` helper (the import would pull `pipeline-application` into `pipeline-step-sdk:runtime`, which is a cycle)"*. La duplicación no era descuido: era el precio de poner el contrato en el módulo equivocado. La dependencia real va al revés —`pipeline-application` ya depende de `pipeline-step-sdk:runtime`—, así que el contrato puede y debe vivir en el runtime, y el agente leerlo allí. La otra dirección habría sido el ciclo. Por eso `AgentRendezvous` es público en el runtime y su constructor es `internal`: el contrato es de dos procesos y necesita ser visible; el mecanismo no.

**Cuatro autoridades que colgaban, medidas antes de tocar nada.** Todas sobre `38ec2204c`:

|Autoridad colgante|Medición|
|---|---|
|`ObsPc2IngestAgent.argv` — el helper que el runtime "no podía" importar| cero lectores en todo el árbol: las apariciones de `ObsPc2IngestAgent` son un comentario de `StreamingRedactor` y cinco en el runtime (cuatro en KDoc y la constante del nombre de clase), y NINGUNA es una llamada al helper. Su KDoc afirmaba que el lanzador lo usaba; no lo usaba|
|Dos copias del predicado de tipo del FIFO|`isFifo` declarado dos veces, una por módulo, con el mismo cuerpo. La invariante de seguridad del FIFO tenía dos dueñas|
|`READY_CONTENT` — la constante del texto de la marca de listo| cero lectores; el agente escribía el literal `"ready\n"` en línea, en la otra copia del mismo hecho|
|`utf8` — el helper de codificación del agente| cero lectores: sólo su propia declaración|

Y en el runtime, `AgentHandles` declaraba tres campos sin ningún lector: `stdoutFifo` y `stderrFifo` (las apariciones homónimas del fichero eran `protocol.stdoutFifo`, otra cosa) y `warmup` (el valor devuelto era la variable local, no el campo). Se borraron con la clase.

**El secreto deja de viajar en el argv.** Antes, `buildAgentArgv` ponía el secreto en la sexta posición del argv. Medido en el host de referencia: `/proc/self/cmdline` tiene modo **444** y `/proc` está montado **sin `hidepid`**, así que un argumento de línea de órdenes lo lee *cualquier usuario de la máquina*; `/proc/self/environ` tiene modo **400**, y lo lee su propietario solo. El secreto viaja ahora en el entorno del agente, bajo `PIPELINEK_AGENT_SECRET`, y el argv lleva identificadores y rutas y nada que deba permanecer secreto. El argv pasa de seis valores a cinco, y el número se comprueba ANTES de leer las posiciones. Antes no se comprobaba: el agente leía `args[0]`..`args[4]` por posición y aceptaba en silencio un argv de más valores —la forma antigua, con el secreto en sexta posición— mientras que uno más corto lo hacía fallar con un `IndexOutOfBounds` en vez de con un motivo dicho. Hoy la forma antigua se rechaza por su cuenta.

**La garantía, la capacidad y el instrumento.** La garantía es que el escritor y el lector del argv se encuentran —los valores que el runtime escribe son los que el agente lee, posición por posición— y que el secreto que el agente existe para redactar no viaja donde todo el host puede leerlo. La capacidad que esa garantía sostiene es que un paso `sh` durable bombeado por el agente entrega al Output Plane bytes saneados, y que un paso así no publica por el camino el mismo secreto que debería ocultar: los dos fallos son silenciosos, porque el agente drenaría en vacío o no redactaría nada y la transcripción saldría limpia. El instrumento es un test puro de 9 filas de JUnit 5 —sin proceso, sin hilo, sin reloj, sin sistema de ficheros— porque el contrato es un valor con un escritor y un lector: el instrumento más barato que puede fallar es escribirlo y leerlo de vuelta. Un FIFO real, un agente real y un hijo real ejercitarían el conjunto, y el camino del agente no tiene ningún escenario ejecutable; un test unitario es el único instrumento disponible aquí, y es el que nombra QUÉ mitad se rompió. El predicado de tipo del FIFO **no** se afirma aquí: su comportamiento no cambia con este corte —se mudó al contrato compartido con el mismo cuerpo— y afirmar que una ruta es o no una tubería con nombre necesita un fichero real, que un test unitario no debe crear.

**Visto en rojo seis veces.** Cada mutación se leyó en el diff antes que en el resultado, el árbol se restauró antes de la siguiente (una mutación apilada sobre la anterior hace el conjunto rojo acumulativo, y un conjunto acumulativo no prueba nada de una fila sola), y cada ejecución se comprobó contra el XML de JUnit autoritativo —no contra la salida del build, que también dice "BUILD FAILED"— con la condición de que corrieron las 9 filas:

|Mutación|Filas que se encendieron|
|---|---|
|El argv escribe el run id donde va el operation id|2 — `the argv carries the five values the agent reads`, `the argv the runtime writes is the argv the agent reads`|
|El argv lleva el secreto|3 — `the secret travels in the environment and not in the argv`, `the argv the runtime writes is the argv the agent reads`, `the argv carries the five values the agent reads`|
|El argv no comprueba su número de valores|1 — `an argv with the wrong number of values is refused`|
|La raíz del directorio de control sube un nivel de más|1 — `the control dir's root is the level above the control dir`|
|El secreto viaja bajo otro nombre de variable|2 — `the secret travels in the environment and not in the argv`, `the agent reads the secret back from the environment`|
|Un secreto ausente se responde con uno vacío|2 — `an environment without the secret is refused`, `an environment with an empty secret is refused`|

La segunda mutación enciende tres filas y no una, y la razón es parte del resultado: llevar el secreto al argv cambia también el número de valores, así que el argv deja de ser el argv y las filas que comparan el argv con lo que el agente lee se encienden con ella. La tercera y la sexta son ortogonales a propósito —una quita la COMPROBACIÓN y la otra la RESPUESTA— y sus conjuntos rojos no se tocan: la aridad y la respuesta ante un secreto ausente son hechos distintos, y el instrumento los distingue.

**Lo que el corte no arregla, y a quién pertenece.** El camino del agente no tiene ningún escenario ejecutable en `examples/`: nada en este árbol conduce al agente de extremo a extremo, así que las garantías de arriba quedan declaradas por construcción —el tipo del FIFO se comprueba, la redacción ocurre en memoria antes del commit— y no presenciadas por un escenario. Siguen siendo las que son.

**El diagnóstico de detekt, que no era del código.** `detektMain` se puso en rojo con un `SpreadOperator` en `DurableShellExecutor.kt`: `ProcessBuilder("setsid", javaBin, "-cp", classpath, AGENT_MAIN_CLASS, *argv)`. La causa era el constructor variádico, no la política: `ProcessBuilder` tiene un constructor que recibe la lista entera. El corte retira además del baseline la entrada `SpreadOperator` de esa línea, cuya supresión ya no tenía sujeto.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros y con la build cache de Gradle **desactivada** (`GRADLE_OPTS=-Dorg.gradle.caching=false`), para que la línea de veredicto describa una ejecución y no una restauración:

```
PASS  sha=4dfdd812f4e4102c3aa65493fdf4ddaead5abd97  elapsed=164s  budget=900s  tests=rewritten:1378 read:0  scenarios=observed:62 executed:62
```

- 187 informes JUnit reescritos por esta ejecución —**todos** con su marca de tiempo dentro del MISMO minuto (13:31 UTC), lo que prueba que los 1378 tests se EJECUTARON y no fueron repuestos de la cache—, 1378 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 164 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 25854`, `PRODUCT_LOC = 71100`, ratio `0.364` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `1017 / 2197 = 0.463`. El censo cuenta 167 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 723 ficheros de código de producción como producto.

**El instrumento del gate, que no era del corte: `rewritten` no significa "ejecutado".** `pipelinek/gradle.properties` fija `org.gradle.caching=true` y el gate no lo desactiva. Una tarea `test` cuyas entradas no cambian es servida `FROM-CACHE`: Gradle repone sus ficheros de salida —los XML de JUnit— con el contenido viejo y un `mtime` fresco. El criterio de frescura del gate es exactamente el `mtime` del fichero (`stat -c %Y`), así que cuenta como "reescrito por esta ejecución" un informe que nadie ejecutó.

Medido: una segunda corrida completa del gate sobre el árbol sin cambios ejecutó **CERO** tests —los 1378 los sirvió la cache— y su línea siguió diciendo `rewritten:1378 read:0`; el reloj bajó de 137 s a 105 s, y la guarda que el gate tiene para el caso "árbol totalmente cacheado ⇒ esto no es una medición ⇒ fallar" **no puede dispararse**, porque la cache deja todos los informes con `mtime` fresco. Experimento controlado, con la única variable cambiada (antes de cada invocación se borran los outputs; las entradas son las mismas):

|Invocación|Estado de `:pipeline-domain:test`|
|---|---|
|sin `-Dorg.gradle.caching=false`|`FROM-CACHE`|
|con `-Dorg.gradle.caching=false`|`executed`|

El arreglo es de **H6**, que es donde vive el gate: el censo debe leer la marca de tiempo PROPIA del XML (`timestamp`), no el `mtime` del fichero, y distinguir tres estados —ejecutado ahora, servido de la cache, leído de disco— en vez de dos. El arreglo está hecho, y es el corte «el veredicto del gate cuenta lo ejecutado, no lo repuesto», en H6, más abajo de este documento. No se toca el gate aquí: cambiar el instrumento de medición en mitad de un corte cambiaría la contabilidad del presupuesto que este corte está midiendo. Este corte se midió, por tanto, con la cache desactivada, y por eso su línea de veredicto dice lo que dice. En los tres cortes anteriores, cuyo código cambió, la cache sólo pudo servir los módulos cuyas entradas no cambiaron —sus hallazgos (detekt, LOC, escenarios) no dependen de ello—, pero su cifra de `rewritten` es la del gate, y el gate no distingue lo ejecutado de lo repuesto.

#### El corte: un acoplamiento fallido no deja nada abierto

**Qué hace.** `AgentRendezvous.rendezvous()` abría los dos descriptores de calentamiento, lanzaba el agente y esperaba su marca de listo, SIN try/catch. Medido sobre el árbol anterior al corte (`2ac2cf6bb`), las salidas posibles eran cuatro:

- `materialiseFifos` falla → no hay nada abierto todavía; inofensivo.
- `openWarmup` falla A MITAD → el primer descriptor queda abierto en la JVM del runtime y el segundo no existe: una fuga de un descriptor, sin proceso.
- `launchAgent` falla → los DOS descriptores quedan abiertos en la JVM del runtime.
- `awaitReady` falla (la marca de listo no aparece en 10 s) → los DOS descriptores quedan abiertos Y el proceso del agente queda vivo sin dueño: el handle `Process` se descarta al salir de `rendezvous`, y `LaunchedShell.agentWarmup` sólo transporta los descriptores.

Los descriptores de calentamiento son, con el hijo, los ÚNICOS escritores de las FIFOs; el agente lee hasta EOF, y EOF llega cuando todo escritor se ha ido. Un descriptor que sigue abierto ⇒ el agente nunca ve EOF ⇒ su JVM se queda esperando una transcripción que no va a crecer. Cada acoplamiento fallido dejaba, por tanto, una JVM huérfana.

Y el KDoc de `launchAgent` afirmaba: *«The runtime keeps the [Process] handle so the run terminal can send SIGTERM and, after a 5-second grace, SIGKILL»*. Eso era FALSO: el handle se perdía al salir de `rendezvous`. Una KDoc que afirma una propiedad que el cableado no tiene convierte una ausencia en una garantía.

**El corte.** La POLÍTICA del rendezvous y sus EFECTOS se separan. La política —adquirir, y si no completar, liberar— vive en `AgentRendezvous`; los efectos —FIFOs, descriptores, agente, reloj— viven en un `RendezvousHost`, con una implementación real (`ProcessRendezvousHost`) y una costura interna. La política queda con esta forma:

- se capturan los descriptores de calentamiento y el handle del agente;
- si cualquier paso falla, los descriptores se cierran PRIMERO y el agente se mata DESPUÉS, y el fallo que provocó el desmontaje es el que se reporta;
- si el agente no llegó a arrancar, no hay handle que matar, y los descriptores se cierran igual.

El orden no es una preferencia: cerrar los descriptores es lo que hace que el agente vea EOF, y un agente al que se mata antes de cerrarlos queda con los extremos de lectura abiertos. Un descriptor que se niega a cerrar no impide que se cierren los demás ni que se mate al agente; un agente que se niega a morir no impide que se cierren los descriptores. Ninguno de los dos fallos cambia el veredicto: el veredicto es el fallo que provocó el desmontaje, y la KDoc lo dice en el sitio donde antes afirmaba lo contrario.

**Lo que mide, y por qué se puede matar al agente.** Se midió en el host de referencia, desde un hijo de `ProcessBuilder`: `setsid` NO hace fork cuando quien llama no es ya líder de grupo, así que el pid que lleva el handle ES el pid que ejecuta el agente —medido: `cmdline=sleep 30`, `pgrp=session=pid`— y `destroyForcibly()` lo alcanza (`alive=false`, y la entrada de `/proc` desaparece). Sin esa medición, «matar al agente» habría sido una hipótesis.

**La garantía, la capacidad y el instrumento.** La garantía es que un rendezvous que no completa no deja nada de su propio abierto: ni descriptores, ni proceso del agente. La capacidad que sostiene es que un acoplamiento fallido se puede reintentar sin arrastrar un proceso huérfano ni un escritor que impida el siguiente intento: un FIFO reusado por un segundo intento necesita que los escritores anteriores se hayan ido. El instrumento es un test unitario —14 filas en el fichero, 5 nuevas— que conduce la política con dobles: sin proceso, sin FIFO, sin reloj, sin espera. La ruta del agente NO tiene ningún escenario ejecutable en `examples/` —nada en este árbol conduce al agente de extremo a extremo—, así que un test unitario que conduzca la política con dobles es el único instrumento disponible, y es el que nombra QUÉ mitad se rompió. La costura es lo que lo hace posible: sin ella, la política sólo se podría ejercitar contra un FIFO real y un agente real.

**Visto en rojo tres veces.** Cada mutación se aplicó, se leyó de vuelta con `diff` contra la copia limpia, y el árbol se restauró desde esa copia antes de la siguiente —una mutación apilada sobre la anterior hace el conjunto rojo acumulativo, y un conjunto acumulativo no prueba nada de una fila sola—. Cada ejecución se comprobó contra el XML de JUnit autoritativo —no contra la salida del build, que también dice "BUILD FAILED"— con la condición de que corrieron las 14 filas.

|Mutación|Filas que se encendieron|Lote|
|---|---|---|
|La llamada a `releaseFailedRendezvous` desaparece|4 — `a rendezvous that never attaches releases the warm-up descriptors and kills the agent`, `the warm-up descriptors are released before the agent is killed`, `a rendezvous whose agent never starts releases the warm-up descriptors`, `a descriptor that refuses to close does not keep the agent alive`|14 tests, 4 rojos, sin colateral|
|El agente deja de matarse (`agent?.let { … }` desaparece)|3 — las tres que afirman que el agente fue matado|14 tests, 3 rojos, sin colateral|
|El agente se mata ANTES de cerrarse los descriptores|1 — `the warm-up descriptors are released before the agent is killed`|14 tests, 1 rojo, sin colateral|

La segunda mutación enciende tres filas y no cuatro: `a rendezvous whose agent never starts releases the warm-up descriptors` sigue en verde porque en ese camino no hay handle que matar —el agente no llegó a existir—, y esa fila afirma sólo la liberación de los descriptores. La tercera enciende una sola fila: el orden no es lo que las otras tres miran.

**Lo que este corte NO arregla, y se dice.** No arregla la RUTA DEL AGENTE de extremo a extremo: `examples/` no tiene ningún escenario que conduzca al agente, así que lo que este corte observa es la POLÍTICA, con dobles, y no una ejecución del agente real. No arregla la fuga de un descriptor cuando `openWarmup` falla A MITAD —el segundo `FileChannel.open` lanza y el primero queda abierto—: la política no puede liberar lo que el host nunca devolvió, así que ese agujero vive en el host y este corte no lo tapa. No arregla el handle de un rendezvous EXITOSO: tras el éxito el handle se suelta a propósito —el agente debe sobrevivir al runtime— y su vida queda acotada por el EOF de las FIFOs, que es lo que dice ahora la KDoc en vez de prometer un handle que nadie guarda. Y no arregla `DurableShellExecutor.isAlive(controlDir, process)`, residuo ya declarado: sigue sin llamantes de producción.

Medido sobre `f39838e9b` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=f39838e9b44f1d307539961c8a48c883134fde62  elapsed=144s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1465 tests, 0 fallos, 0 omitidos: 1460 antes del corte más las 5 filas nuevas.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 144 s de reloj de pared frente al presupuesto de 900 s — cifra que la build cache contaminó: la medición
  real de este árbol es de 186 s, corregida con su reejecución más abajo (corte del gate).
- `VERIFY_LOC = 27460`, `PRODUCT_LOC = 71158`, ratio `0.386` (límite 2). El módulo del corte, `pipeline-step-sdk:runtime`, queda en `2042 / 2364 = 0.864`.

**Corrección de este bloque, medida con el instrumento arreglado.** Las 144 s de esta línea NO son una medición del trabajo: la build cache de Gradle estaba activa, así que el grafo de tests fue servido en vez de ejecutado en la parte que no había cambiado, y el «196 informes JUnit reescritos por esta ejecución» era un `mtime` renovado por la restauración. Reejecutando ESTE MISMO árbol con el gate arreglado —build cache de construcción desactivada y censo leyendo el sello del informe— la línea es:

```
PASS  sha=f39838e9b44f1d307539961c8a48c883134fde62  elapsed=186s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

El coste real del corte de B1 fue de 186 s, no de 144 s. Ver el corte del gate, más abajo.

#### El corte: `ShellWatchdog`

El ejecutor llevaba dentro el reloj: el hilo del temporizador, la escritura de la marca de expiración, el barrido de cookies sobre `/proc`, la escalada a `SIGKILL` y las cuatro consecuencias de una expiración (el código de salida de la limpieza, la decisión del transcripto, el terminal y la rama de excepción). `ShellWatchdog` (494 líneas, en `pipeline-step-sdk:runtime`) los recoge. `DurableShellExecutor` queda como fachada y `executeCore()` sigue siendo el único motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Los nombres y las rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|Es la dueña del nombre `timeout.flag`; se conserva intacto y sólo se usa|
|Preparar scripts, wrapper y entorno|`ShellLaunchPreparation` (segundo corte)|No toca esta responsabilidad; se conserva intacto|
|Lanzar el hijo y enrutar sus dos descriptores|`ShellProcessLauncher` (tercer corte)|No toca esta responsabilidad; se conserva intacto|
|Coordinar los FIFOs y el agente|`AgentRendezvous` (cuarto corte)|No toca esta responsabilidad; se conserva intacto|
|La identidad de un lanzamiento: hijo, bombas, sumideros|`LaunchedShell`|Es la forma de retorno del lanzador; se conserva intacta|
|El nombre del transcripto de consola|`DurableShellFiles`|No toca esta responsabilidad; se conserva intacta|
|Las bombas de transcripción y el drenaje|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|La marca de una expiración|`timeout.flag`, escrita por el ejecutor y leída por `StepReconcilerL1`, `RunningSubprocessRecovery` y `RecoveredExecutionMaterializer`|Los lectores son consumidores de la marca, no su dueña; quien la ESCRIBE es el reloj, y por eso el reloj la escribe|
|Vigilar el tiempo y pedir la terminación|Dentro del ejecutor|Es lo que se extrae|

El resultado de la comprobación es que **no existía ninguna clase con esa responsabilidad**: sobre el árbol anterior al corte (`6332da306`), `git grep -E "(class|object|interface) [A-Za-z]*Watchdog"` sobre `*.kt` devuelve **cero** filas, y las 43 menciones de la palabra `watchdog` fuera del ejecutor son todas prosa —KDoc y comentarios que nombran el mecanismo—, ninguna una declaración. `hasTimeoutFlag` —público— y `killWithCookieScan` —`internal`— eran los dos únicos lectores de la marca y del barrido, y ambos estaban dentro del propio ejecutor: se movieron con él, sin dejar lector.

**Los dos hechos de una expiración, y por qué no son uno.** El reloj produce dos marcas, y no son la misma:

- el **LATCH** (`fired`): el reloj publicó que disparó. Depende de que el hilo se planifique Y de que la muerte haya terminado, así que puede perder la carrera contra un envoltorio que publica su código de salida después de que su hijo muera.
- la **BANDERA** (`timeout.flag`): se escribe **ANTES** de la muerte. Es la autoridad, porque sobrevive a esa carrera: una bandera que existe significa que la ejecución expiró, diga lo que diga el código de salida.

El executor pregunta al latch **mientras sondea** —una marca que todavía no es durable no debe cortar el sondeo— y a la bandera **cuando el sondeo acaba**. Y leer la bandera la *latchea*: leerla resuelve el hecho para el resto de la ejecución.

**Lo que cambia de verdad, y por qué no es un detalle.** Antes del corte los dos hechos vivían en una sola variable, `timeoutTriggered`, y se fundían en **un** punto del camino normal: allí se consultaba la bandera y se volcaba en la variable. Los otros tres lectores —el terminal de la rama de excepción, el código de salida de la limpieza y la decisión del transcripto— leían la variable sin volver a mirar la bandera. Medido sobre `6332da306` (el árbol anterior al corte), en la ventana en que el reloj había escrito la bandera pero todavía no había publicado y el hilo principal lanzaba una excepción **antes** de ese punto de fusión, las dos consecuencias de leer el latch donde hacía falta la autoridad eran: el `catch` declaraba `LOST` —mientras el reconcilizador leía la bandera y declaraba `TIMED_OUT`—, y el `finally` limpiaba con el código de salida que hubiera publicado el envoltorio: si ese código era 0, la limpieza de éxito borraba el directorio de control y, con él, la bandera autoritativa. Ahora el terminal dice `TIMEOUT` y la limpieza conserva el directorio: el terminal coincide con el reconcilizador en vez de contradecirlo, y la evidencia de la expiración sobrevive. Es un cambio de comportamiento, y por eso se dice aquí y no sólo en el KDoc.

**La garantía, la capacidad y el instrumento.** La garantía es que una ejecución a la que se le acaba el tiempo nunca se reporta como un éxito, y que la evidencia de la expiración nunca se borra. La capacidad que sostiene es que un paso `sh` durable con plazo devuelve un terminal `TIMEOUT` y no `Exited(0)`, y que un paso así se puede reconciliar después en vez de parecer perdido. El instrumento es un test puro de 15 filas de JUnit 5 —ni proceso, ni hilo, ni reloj, ni sistema de ficheros— porque la mitad imperativa del reloj (el hilo, el hijo real, `/proc`) no puede usarla un test unitario, pero las DECISIONES que el reloj aplica son funciones puras de hechos: un plazo, un latch, una bandera, un código de salida, una proyección. Un test que falseara el temporizador costaría más, probaría menos y añadiría un segundo reloj que mantener en sincronía con el de verdad. Lo que el test NO congela, y se dice: que el hilo llegue a dispararse, que la muerte alcance todo el árbol de procesos y que la bandera se escriba antes de la muerte — eso se observa donde ocurre, no aquí.

**Visto en rojo seis veces.** Cada mutación se leyó en el diff antes que en el resultado —el fichero es nuevo en este corte, así que el diff se leyó contra una copia previa y no contra `git diff`—, el árbol se restauró antes de la siguiente (una mutación apilada sobre la anterior hace el conjunto rojo acumulativo, y un conjunto acumulativo no prueba nada de una fila sola), y cada ejecución se comprobó contra el XML de JUnit autoritativo: si la mutación no compilaba no hay informe, y el informe de la corrida anterior se borró antes de cada una para que no pudiera leerse como verde.

|Mutación|Filas que se encendieron|
|---|---|
|El sondeo ya no sobrevive a la muerte|1 — `the poll outlasts the kill`|
|La bandera ya no gana al latch que no ha publicado|1 — `the flag wins over the latch that has not been published`|
|Un kill que no encontró nada cuenta como timeout|1 — `a kill that found nothing is not a timeout`|
|La rama de expiración de la limpieza desaparece|1 — `an expired execution is a failure whatever the wrapper published`|
|La expiración ya no retiene el transcripto|1 — `an expired execution keeps nothing`|
|El terminal de una ejecución expirada es otra interrupción|1 — `the terminal of an expired execution is a timeout`|

Las seis son **discriminantes** y cada una enciende exactamente la fila esperada, sin colateral. La tercera y la quinta son ortogonales a propósito —una cambia QUÉ se publica y la otra QUÉ se conserva— y sus conjuntos rojos no se tocan.

**El diagnóstico de detekt, que era del código, y el del instrumento, que era del gate.** `detektMain` se puso en rojo con dos hallazgos nuevos en el fichero movido: `killWithCookieScan` (complejidad 30) y `findCookieProcesses` (26). La causa no era la política sino la forma: el código movido no hereda la supresión del baseline, porque el baseline suprime por RUTA y la ruta nueva no es la vieja. La respuesta fue reescribir sin la forma suprimida —la sesión, la terminación, la comprobación, la escalada, el uid y la cookie son pasos con una forma cada uno— y no volver a registrar la supresión. El baseline pierde diez entradas: seis de este corte y cuatro cuya materia ya no existe, y se comprobó que esos cuatro sujetos no producen ya hallazgo. Y el gate del análisis estático se comprobó contra su propio sujeto: un import muerto temporal en `ShellWatchdogTest.kt` hizo fallar `detektTest` nombrando ese fichero, esa línea y esa regla (`The import 'java.util.concurrent.TimeUnit' is unused. [UnusedImport]`), y el fallo desapareció al quitarlo. Un gate que no puede ver a su sujeto no es un gate.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros y con la build cache de Gradle **desactivada** (`GRADLE_OPTS=-Dorg.gradle.caching=false`), para que la línea de veredicto describa una ejecución y no una restauración:

```
PASS  sha=d42e2d749108ef8314e09513bd30d8da881ca22c  elapsed=193s  budget=900s  tests=rewritten:1393 read:0  scenarios=observed:62 executed:62
```

- 188 informes JUnit reescritos por esta ejecución —**todos** con su marca de tiempo dentro del MISMO minuto (14:38 UTC), lo que prueba que los 1393 tests se EJECUTARON y no fueron repuestos de la cache—, 1393 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 193 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 26010`, `PRODUCT_LOC = 71148`, ratio `0.366` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `1173 / 2245 = 0.522`. El censo cuenta 168 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 724 ficheros de código de producción como producto; 53 ficheros quedan sin contar en ninguna parte (README, `AGENTS.md`, `ROADMAP.md`, ficheros de contrato `.api`, bases de detekt y el `gradle-wrapper.jar`) y 2 se excluyen como generados.

**Lo que este corte no arregla.** La muerte con barrido de cookies sigue siendo la mitad imperativa del reloj, y esa mitad —el hilo, el `SIGKILL` sobre la sesión, el barrido de `/proc`— no la cubre ningún test unitario: un test unitario no puede crear un árbol de procesos ni leer `/proc`. Y ningún escenario E2E expira una ejecución: el único `timeout(...)` de `examples/` (en `control-flow/11-workflow-control`) envuelve un `sh` que termina de inmediato, así que el plazo nunca vence. Las garantías de arriba quedan declaradas por construcción y por el veredicto de sus decisiones, no presenciadas por un escenario. Siguen siendo las que son.

**Lo que queda de H2.** El otro componente se extrae conservando `DurableShellExecutor` como fachada compatible y sin crear un segundo motor de shell: `executeCore()` debe quedar como un coordinador legible, no como una copia de la implementación actual. Queda `ShellResourceCleanup` — y es el último: con él queda cerrado el hito.

#### El corte: `ShellTerminalDecision`

El ejecutor decidía en línea, en tres sitios distintos, tres preguntas distintas, y un cuarto sitio —el reloj— construía el terminal de la expiración:

- al final del camino normal: «¿salió o expiró?», con el código de salida de un hijo al que mataron mientras lo publicaba;
- en el rehuso de la salida: «los bytes se fueron, ¿qué dice el paso?»;
- en el `catch`: «¿falló antes del lanzamiento, después de una expiración, o de una forma que nadie puede reconciliar?».

Cada sitio tenía su propia regla, y ninguno tenía un instrumento que la observara: la única forma de verlas era lanzar un hijo y hacerlo fallar del modo correcto, y ese instrumento dice QUE se rompió la regla, nunca cuál.

`ShellTerminalDecision` (173 líneas, en `pipeline-step-sdk:runtime`) es ahora la única dueña del veredicto: dados los hechos de cómo terminó una ejecución, publica el terminal —`Exited`, `LaunchFailed`, `Lost` o `Cancelled`— y el ejecutor sólo pregunta. Las reglas son funciones puras de hechos, así que un hecho que no se conoce no se inventa. `DurableShellExecutor` baja de 721 a 693 líneas y sigue siendo la fachada: `executeCore()` es un coordinador legible —lanzar, desvincular, armar el reloj, sondear, asentar, decidir, limpiar— y no se creó un segundo motor de shell.

**La comprobación previa, por responsabilidad.** Antes de crear el componente se comprobó si otra abstracción ya era la dueña de esa responsabilidad:

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|La clasificación de un terminal durable|No: tres decisiones en línea en el ejecutor y una cuarta en el reloj|Es lo que se extrae|
|`TaskTerminalDecision` (dominio)|Proyecta el resultado de una TAREA de proceso a tres formas planas, para otro protocolo, con otro directorio de control y otro `result.txt`|No es la misma materia: se conserva intacto|
|El terminal del reconcilizador (`terminalFromReconciliation`)|Reconstruye un terminal desde lo que el directorio de control contiene, para una ejecución que nadie vio|Sigue siendo suyo: su interrupción dice «durable task timed out»|
|Los nombres y las rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|Se conserva intacto y sólo se usa|
|Las bombas de transcripción y el drenaje|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|La identidad de un lanzamiento y el nombre del transcripto|`LaunchedShell`, `DurableShellFiles`|Ya están fuera del ejecutor; no se tocan|

**La precedencia ES la regla.** En `forFailure` el orden de las ramas no es decorativo: un fallo **antes** del lanzamiento es un fallo de lanzamiento, porque nada corrió y no hay nada que reconciliar ni que reintentar; un fallo **después** de una expiración es la expiración, porque el reconcilizador lee la misma marca y un terminal que dijera «perdida» lo contradiría; cualquier otro fallo es **perdida**, y `Lost` nunca implica éxito. En `forExitCode` la expiración manda sobre el código de salida por la misma razón: un envoltorio que publica un 0 después de que su hijo murió no decide si la ejecución tuvo éxito.

**El `timeoutTerminal()` se mudó, no se copió.** El reloj conserva «expiró o no»; la decisión, «qué terminal es eso». Con la mudanza, `ShellWatchdog` ya no necesita el directorio de control como propiedad —lo usa para construir el layout del protocolo y nada más— y `ShellWatchdogTest` ya no necesita construir un reloj para comprobar el terminal. El terminal de una ejecución expirada era una fila de `ShellWatchdogTest` y ahora es una de `ShellTerminalDecisionTest`: la garantía se MUDÓ, no se duplicó.

**Lo que NO se unificó, y por qué.** El reconcilizador sigue construyendo su terminal, con el mensaje «durable task timed out», y el shell sigue publicando el suyo, con «durable shell timed out». Esa diferencia es del **formato durable**, no un descuido: unificarla cambiaría lo que un lector de una ejecución reconciliada encuentra en disco, y un cambio de formato sin autorización no es un refactor. Hay además un tercer constructor de terminales, el de `core.sh` en `pipeline-application` (`ShExecution.kt`), que clasifica desde el `TaskTerminalDecision` del dominio: es **otra ejecución**, con otro directorio de control y otro protocolo, y es la materia de H3, no de este corte.

**La garantía, la capacidad y el instrumento.** La garantía es que el terminal que publica una ejecución de shell durable nombra lo que pasó: una ejecución a la que se le acabó el tiempo es un TIMEOUT diga lo que diga el código de salida que publicó el envoltorio; un fallo anterior al lanzamiento es un fallo de lanzamiento porque nada corrió; un fallo que no se puede reconciliar es **perdida** y nunca un éxito; y una ejecución cuya salida no se pudo persistir lo dice —con el token que distingue un hijo atascado de un drenaje sin tiempo— en vez de publicar un transcripto plausible y corto. La capacidad que sostiene es que un lector de una ejecución durable puede distinguir una que expiró de una que falló de una que se perdió, y que el terminal coincide con el reconcilizador en vez de contradecirlo. El instrumento es un test puro de 13 filas de JUnit 5 —ni proceso, ni hilo, ni reloj, ni sistema de ficheros— porque la clasificación es una función pura de hechos: un código de salida, una marca de expiración, si el hijo llegó a lanzarse, un rehuso. Un test que lanzara un hijo real para que expirara o para que fallara antes del lanzamiento costaría más, probaría menos y añadiría un proceso y un reloj que un test unitario no puede usar. Lo que el test NO congela, y se dice: que el ejecutor reúna los hechos correctos, que el reconcilizador lea la misma marca y que un hijo que expira deje la marca de verdad —eso se observa donde ocurre, no aquí.

**Visto en rojo nueve veces.** Cada mutación se aplicó y se leyó en el diff antes que en el resultado, el árbol se restauró antes de la siguiente (una mutación apilada sobre la anterior hace el conjunto rojo acumulativo, y un conjunto acumulativo no prueba nada de una fila sola) y cada ejecución se comprobó contra el XML de JUnit autoritativo.

|Mutación|Filas que se encendieron|
|---|---|
|La expiración ya no gana al código de salida|1 — `an expired execution is a timeout whatever the exit code says`|
|La precedencia entre expiración y lanzamiento se invierte|1 — `a failure before the launch stays a launch failure even if it expired`|
|El predicado de lanzamiento se invierte|5 — las cinco filas de `forFailure`|
|Los dos tokens del desague se intercambian|2 — `a stuck child is not a drain that ran out of time` y `a drain that ran out of time is not a stuck child`|
|El valor capturado se pierde|1 — `the captured value reaches the exit terminal`|
|El rehuso de la salida se vuelve reintentable|1 — `a refusal of the output says the output could not be persisted`|
|El mensaje de la tarea perdida cambia|1 — `a failure that says nothing says it could not be reconciled`|
|El shell dice las palabras del reconcilizador|1 — `an expired execution is a timeout whatever the exit code says`|
|La interrupción pierde el directorio de control|1 — `an expired execution is a timeout whatever the exit code says`|

Las nueve son **discriminantes** y cada una enciende exactamente la fila esperada, sin colateral. Tres de ellas caen sobre la misma fila del terminal expirado porque esa fila es la que afirma las tres cosas que ese terminal dice: el tipo, el mensaje y el directorio de control. Las dos del desague son ortogonales a propósito —una cambia QUÉ se publica y la otra QUÉ se conserva— y sus conjuntos rojos no se tocan.

**El diagnóstico de detekt, que era del código movido y no del código nuevo.** `detektMain` se puso en rojo con `UnusedPrivateProperty` en `ShellWatchdog.kt`: `controlDir` era propiedad de la clase sólo porque `timeoutTerminal()` la usaba, y al mudarse esa función la propiedad quedó sin lector. `detektTest` se puso en rojo con `UnusedPrivateFunction` (`watch()`) y `UnusedImport` (`java.nio.file.Path`) en `ShellWatchdogTest.kt`, por la misma razón: el helper construía un reloj para una fila que ya no vive ahí. Los tres se QUITARON —la propiedad, el helper y el import— en vez de registrarse como supresión: un hallazgo que nace de una mudanza se resuelve retirando lo que la mudanza dejó sin uso, no silenciándolo. Y el gate del análisis estático se comprobó contra su propio sujeto: los tres fallos nombraron su fichero, su línea y su regla, y desaparecieron al quitar lo que señalaban. El baseline del módulo no cambia: ninguna de sus entradas apuntaba a lo que este corte movió ni a lo que este corte añadió.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros y con la build cache de Gradle **desactivada** (`GRADLE_OPTS=-Dorg.gradle.caching=false`), para que la línea de veredicto describa una ejecución y no una restauración:

```
PASS  sha=78d8305414fe5112b168a10c6bdfc5a1fe7596e9  elapsed=180s  budget=900s  tests=rewritten:1405 read:0  scenarios=observed:62 executed:62
```

- 189 informes JUnit reescritos por esta ejecución —comprobado aparte, los 189 llevan su marca de tiempo en el MISMO minuto (15:10 UTC), lo que prueba que los 1405 tests se EJECUTARON y no fueron repuestos de la cache—, 1405 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 180 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 26282`, `PRODUCT_LOC = 71184`, ratio `0.369` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `1445 / 2281 = 0.633`. El censo cuenta 169 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 725 ficheros de código de producción como producto; 53 ficheros quedan sin contar en ninguna parte (README, `AGENTS.md`, `ROADMAP.md`, ficheros de contrato `.api`, bases de detekt y el `gradle-wrapper.jar`) y 2 se excluyen como generados.

**Lo que este corte no arregla.** La clasificación ya no está repartida, pero sus hechos siguen viniendo de donde vienen: que el ejecutor reúna el código de salida correcto, que la marca de la expiración se lea en el momento correcto y que un hijo que expira deje la marca de verdad, sigue sin estar presenciado por un test unitario —un test unitario no puede crear un árbol de procesos ni escribir una bandera en un directorio de control—. Y ningún escenario E2E hace expirar una ejecución: el único `timeout(...)` de `examples/` (en `control-flow/11-workflow-control`) envuelve un `sh` que termina de inmediato, así que el plazo nunca vence. Las garantías de este corte quedan declaradas por construcción y por el veredicto de sus decisiones, no presenciadas por un escenario. Siguen siendo las que son.

#### El corte: `ShellResourceCleanup`

El ejecutor cerraba el lanzamiento con dos recorridos casi idénticos y una configuración que no decidía nada. `cleanup(controlDir, exitCode, keepTranscriptLog)` leía `DurableShConfig.fromSystemProperties()` y consultaba `cleanupRetainOnFailure`:

- con la bandera activa (el valor por defecto), el método caía en una rama que sólo borra cuando el código de salida es cero, así que una ejecución fallida conservaba su directorio;
- con la bandera apagada, el método salía antes de borrar: la misma conservación.

Las dos posiciones dan lo mismo. La bandera era **inerte**: no podía cambiar el resultado en ninguna de las dos. Y la regla que de verdad se aplicaba —«una ejecución fallida conserva su directorio»— estaba escrita como una configuración que no se podía honrar. Honrarla habría destruido la evidencia de la que depende el protocolo: el código de salida, la marca de expiración y el latido son lo que `StepReconcilerL1` lee para distinguir una ejecución expirada de una perdida. Así que la bandera se **retira** y la regla queda escrita donde se lee.

`ShellResourceCleanup` (161 líneas, en `pipeline-step-sdk:runtime`) es ahora la única dueña: decide el destino del directorio con un ADT cerrado (`Retained` | `Disposed(consoleLogSurvives)`), decide qué paths sobreviven comparando **rutas** y no nombres, y suelta los descriptores del lanzamiento de uno en uno. `DurableShellExecutor` baja de 693 a 658 líneas y sigue siendo la fachada: `cleanup(...)` delega y el `finally` de `executeCore()` pregunta.

**La comprobación previa, por responsabilidad.**

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|La liberación de los descriptores y el destino del directorio de control|Dentro del ejecutor: un `deleteRecursively` propio y un `Files.walk` casi gemelo en el `finally`|Es lo que se extrae|
|La identidad de un lanzamiento (`handle`, bombas, sumideros, `agentWarmup`)|`LaunchedShell`|Ya es la forma de retorno del lanzador; no se crea otra: el componente la consume, no la duplica|
|Los nombres y las rutas del protocolo persistente|`ShellProtocolLayout` (primer corte)|Se conserva intacto y sólo se usa; el nombre del transcripto sigue siendo de `DurableShellFiles`|
|Las bombas de transcripción y el drenaje|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca: las bombas se unen y se drenan antes de que este componente suelte nada|
|El veredicto de la ejecución|`ShellTerminalDecision` (sexto corte)|Se conserva intacto: aquí se decide qué se libera, no qué se publica|
|La expiración y el código de salida de la limpieza|`ShellWatchdog`|Se conserva intacto: el ejecutor le entrega los hechos que ya tiene|

**Lo que la bandera retirada no rompe.** `cleanupRetainOnFailure` no tenía ni un consumidor fuera de su propio fichero —`git grep` sobre el repositorio entero devuelve cero referencias tras el corte— y el módulo `pipeline-step-sdk:runtime` no está en `bcvModules` ni en `publishedContractModules` ni aplica `maven-publish`: sus miembros públicos son internos de la construcción y se pueden mover. El comportamiento es **idéntico** antes y después en las cuatro combinaciones de (bandera, código de salida), porque las dos posiciones de la bandera convergen en la misma conservación.

**Dos diferencias deliberadas, y no son accidentales.** La comparación del transcripto pasó de **nombre** a **ruta**: antes sobrevivía cualquier fichero llamado `console.log` a cualquier profundidad del árbol, y ahora sobrevive sólo el transcripto de ESE directorio de control. En la disposición real el transcripto está en la raíz del directorio de control, así que el cambio no altera nada en producción; lo que altera es una regla que era falsa. Y el recorrido que borra ya no puede lanzar: antes, un directorio de control ausente hacía que `Files.walk` escapara del `finally` del ejecutor, y una excepción que escapa de ahí reemplaza el terminal que la ejecución se ganó por un fallo de limpieza. Ahora la limpieza no puede cambiar el veredicto de la ejecución que está limpiando.

**La garantía, la capacidad y el instrumento.** La garantía es que una ejecución fallida conserva su directorio de control **entero**, porque ese directorio es la evidencia que el reconcilizador lee, y que una ejecución exitosa lo suelta sin llevarse por delante el transcripto que el consumidor de producción lee después. La capacidad que sostiene es que un paso `sh` durable pueda reconciliarse más tarde —distinguiendo una ejecución expirada de una perdida— sin que la limpieza haya borrado la prueba. El instrumento es un test puro de 11 filas de JUnit 5 —ni proceso, ni hilo, ni reloj, ni sistema de ficheros— porque las reglas son funciones puras de hechos: un código de salida, la petición del llamante, una ruta. La liberación de los descriptores se observa con un doble en memoria. Un test que creara un directorio de control real y mirara qué sobrevivió tocaría el sistema de ficheros, que un test unitario no puede hacer, y diría lo mismo a más coste. Lo que el test NO congela, y se dice: que el recorrido que borra borre lo que la regla dice, que el sistema de ficheros coopere y que el ejecutor reúna los hechos correctos —eso se observa donde ocurre, no aquí.

**Visto en rojo seis veces.** Cada mutación se aplicó, se leyó en el diff antes que en el resultado, se restauró antes de la siguiente —RESTORED OK: True en las seis— y cada ejecución se comprobó contra el XML de JUnit autoritativo: el leedor es un parser de XML real, no una expresión regular, porque una expresión regular empareja un `<testcase/>` autocerrado con un `</testcase>` posterior y atribuye la fila roja a la fila equivocada.

|Mutación|Filas que se encendieron|
|---|---|
|La comparación del código de salida pasa a ser «mayor que cero»|1 — `an expired execution keeps its control directory`|
|La petición de conservar el transcripto se invierte|2 — `a successful execution keeps the transcript when the caller asks for it` y `a successful execution disposes its control directory`|
|La comparación del transcripto pasa de ruta a nombre|1 — `a file with the same name deeper in the tree does not survive`|
|Una ejecución fallida se declara liberada|3 — `a failed execution keeps the transcript with the directory`, `a failed execution keeps its control directory` y `an expired execution keeps its control directory`|
|El fallo de un descriptor ya no se contiene|1 — `a descriptor that refuses to close does not keep the others`|
|Sólo se libera el primer descriptor|2 — `a descriptor that refuses to close does not keep the others` y `every descriptor of the launch is released`|

Las seis son **discriminantes** y cada una enciende exactamente las filas esperadas, sin colateral. Las tres de la cuarta mutación caen sobre las tres filas que afirman la conservación, y son tres porque son tres hechos distintos: el fallo, la expiración y la irrelevancia de la petición del transcripto cuando el directorio entero se conserva. La última mutación enciende la fila del descriptor que se niega y la de todos los descriptores, porque su fila afirma las dos cosas: que se sueltan todos y que uno que falla no retiene a los demás.

Medido sobre el árbol del corte con `scripts/verify-all.sh` sin filtros y con la build cache de Gradle **desactivada** (`GRADLE_OPTS=-Dorg.gradle.caching=false`), para que la línea de veredicto describa una ejecución y no una restauración:

```
PASS  sha=55d19917ac3813b8d5e8a564edf9910321a2b7da  elapsed=159s  budget=900s  tests=rewritten:1416 read:0  scenarios=observed:62 executed:62
```

- 190 informes JUnit reescritos por esta ejecución —comprobado aparte con un lector de XML real: los 190 llevan su marca de tiempo dentro de la misma ventana (16:12 UTC del 26 de octubre), lo que prueba que los 1416 tests se EJECUTARON y no fueron repuestos de la cache—, 1416 tests, 0 fallos, 0 errores, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde; 588 comprobaciones sobre 112 ejecuciones, 219 pasos terminados, 2 terminados inestables; ninguno sin comprobar; 7 credenciales sembradas y 2 reruns durables.
- 159 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- El gate completo se ejecutó **dos veces** sobre el mismo árbol y las dos veces en verde (181 s y 159 s). La segunda ejecución no es un redundante: la primera había dejado en disco el informe JUnit de la última mutación —en rojo, y con la mutación aplicada—, así que los informes que quedan en el árbol tienen que pertenecer a una corrida coherente del código commiteado. El registro cita la segunda.
- `VERIFY_LOC = 26410`, `PRODUCT_LOC = 71196`, ratio `0.371` (límite 2); el módulo del corte, `pipeline-step-sdk:runtime`, queda en `1573 / 2293 = 0.686`. El censo cuenta 170 ficheros de tests unitarios y 62 pipelines E2E como verificación, y 726 ficheros de código de producción como producto; 53 ficheros quedan sin contar en ninguna parte (README, `AGENTS.md`, `ROADMAP.md`, ficheros de contrato `.api`, bases de detekt y el `gradle-wrapper.jar`) y 2 se excluyen como generados.

**Lo que este corte no arregla.** La mitad imperativa de la limpieza —el recorrido que borra y el sistema de ficheros que coopera o no— no la cubre ningún test unitario: un test unitario no puede crear un árbol de ficheros ni borrarlo. Y ningún escenario E2E observa el destino del directorio de control: el único `timeout(...)` de `examples/` (en `control-flow/11-workflow-control`) envuelve un `sh` que termina de inmediato, así que el plazo nunca vence y ninguna ejecución llega a fallar por expiración. Las garantías de este corte quedan declaradas por construcción y por el veredicto de sus decisiones, no presenciadas por un escenario. El remedio —una directiva nueva de `examples/run.sh` que observe el directorio de control de una ejecución fallida— pertenece al hito que es dueño del vocabulario del lanzador (H6) y no a este corte: este no introduce comportamiento nuevo, y ampliar el vocabulario de una herramienta compartida para un caso que el corte no produce sería cambiar más de lo que el corte toca.

### Publicación de `v0.91.0-rc3` — verificada sobre el remoto

Con los siete componentes cerrados, H2 entrega su candidata. La publicación se hizo desde `967563d0e` y la comprobación es sobre el remoto, no sobre el árbol local:

```
release-pipelinek: PUBLISHED v0.91.0-rc3 with candidate_id sha256:febc866cfcdada0b16ec33f5c4225cf093e088b22e8d9ac9b32b5a82ae49affa
```

- El tag remoto es **anotado**: `git ls-remote --tags origin 'v0.91.0-rc3*'` devuelve **dos** líneas — el objeto de tag `b714c939` y el peel `refs/tags/v0.91.0-rc3^{}` al commit `967563d0eea23ee91ddf0334a2a93cf04d0b4e41`. Un tag ligero no tendría esa segunda línea.
- La firma **verifica** contra la clave declarada `9600F7D49B9C966E3BB04E6A17E44CA7C5288281` (uid `test@example.com`), comprobada sobre el objeto **descargado del remoto** —`git fetch origin refs/tags/v0.91.0-rc3` y `git verify-tag` sobre ese objeto—, no sobre la copia local: `GOODSIG` y `VALIDSIG` con la clave declarada.
- La prerelease de GitHub existe (`isDraft=false`, `isPrerelease=true`, publicada el `2026-10-26T16:29:15Z`) con **7 assets**. Los seis digests de `SHA256SUMS` —descargado del remoto, no del staging local— coinciden uno a uno con los que el propio GitHub calcula sobre los assets subidos, y el digest de `pipelinek-0.91.0.zip` es exactamente el `candidate_id` anunciado.
- Los bytes publicados se descargaron y se hashearon aquí: `sha256(pipelinek-0.91.0.zip) = febc866c…` = `candidate_id`. El ZIP son 52 entradas con el lanzador `pipelinek-0.91.0/bin/pipelinek`, y ejecutado desde una extracción limpia responde `pipeline 0.91.0`. El SDK publicado son 85 entradas en coordenadas Maven (`dev/rubentxu/pipeline/v2/…/0.91.0/`) con sus sumas, y su digest coincide con el de `SHA256SUMS`.
- `git-head.txt` y `manifest.json` —descargados del remoto— nombran el commit `967563d0eea23ee91ddf0334a2a93cf04d0b4e41` y el `release_tag` `v0.91.0-rc3`, con esquema `pipelinek-release-manifest-1`.
- La puerta de la propia publicación: `releaseGate` en verde y `external-compat` en `PASS rows=A:PASS B:PASS C:PASS` —la fila C rechaza el artefacto de la API antigua con `IncompatibleApiRange([0.90.0, 0.91.0))`, que es el refusal que debe ocurrir y no un fallo— y `verify-tag-signature` en `PASS rows=A:PASS B:PASS C:PASS` **antes** de que nada se publicara.

**Lo que la puerta de la publicación NO corre.** `releaseGate` es `distZip + cyclonedxBom + candidateAdmission + LaunchReceipt + alineación del BOM del SDK`; por diseño propio **no** depende de `check`, así que la suite de tests unitarios no corre dentro de la publicación (en la ejecución que publicó, las 20 tareas ejecutadas fueron generación de POM, publicación del SDK, `publishSdkForExternalPlugin`, `candidateAdmission` y `releaseGate`, y ninguna tarea `:test`). La suite completa se midió aparte, con `scripts/verify-all.sh` sin filtros, sobre el árbol publicado:

```
PASS  sha=967563d0eea23ee91ddf0334a2a93cf04d0b4e41  elapsed=178s  budget=900s  tests=rewritten:1416 read:0  scenarios=observed:62 executed:62
```

La diferencia entre `55d19917a` —el árbol del séptimo corte, sobre el que se midió el gate de 159 s— y `967563d0e` —el árbol publicado— son 57 líneas de `ROADMAP.md`: un fichero que ninguna tarea de construcción lee y que el censo de LOC cuenta en ninguna de las dos partes.

**Los dos intentos produjeron el mismo `candidate_id`, y eso NO prueba reproducibilidad.** Los dos builds vinieron de la misma condición de árbol, y la segunda ejecución de Gradle declaró `20 executed, 78 up-to-date`: reutilizó las salidas de la primera. Es la misma observación que en rc2 —el `distZip` reproduce byte a byte cuando el build sale de la misma condición— y no una prueba de que un árbol independiente reconstruya los mismos bytes. Esa medición sigue sin hacerse, y por eso no se afirma.

**Un intento que se perdió, y por qué.** El primer intento (`/tmp/h2-rc3/release.attempt1.log`) llegó hasta el push del tag y ahí se quedó. `scripts/release/publish-tag.sh:169` invoca `git push` a secas, el `PATH` lo resuelve al wrapper `~/.local/bin/git`, y ese wrapper se auto-invoca durante su propio escaneo de identidad. La cadena quedó con tres niveles anidados, y el más interno tenía el `stdin` tomado por la salida de `git log --branches --not --remotes`: cuando esa tubería se llenó, el nivel que debía vaciarla estaba ocupado, y el push nunca llegó a la red. `git ls-remote` lo confirmó —el tag no estaba en el remoto—, así que **nada se publicó** y no había nada que deshacer: el tag local se borró y la publicación se relanzó **una** vez, con el escape que el propio wrapper documenta (`RANDOM_GIT_COMMITTER_DISABLED=1`), que delega en el git real sin tocar la identidad del tagger —`publish-tag.sh` la lee de `git config` y la pasa explícita con `-c user.name=… -c user.email=…`—. El guardián de identidad (`~/.git-hooks/pre-push`) siguió ejecutándose, porque es un hook de git y no depende del wrapper. La evidencia del intento perdido queda en `~/.local/state/pipelinek/release-evidence/v0.91.0-rc3.attempt1/`.

**Lo que este cierre no arregla.** El bloqueo estaba en el *tooling* de publicación, no en el producto: `scripts/release/publish-tag.sh` sigue llamando a `git` sin ruta absoluta, así que vuelve a depender del `PATH` de la máquina y a heredar el fallo del wrapper. Arreglarlo —que los scripts de release usen el git real por ruta absoluta, o que el wrapper deje de ser un factor para publicar— pertenece a H6, que es el hito dueño de los scripts de release. Sigue pendiente también la directiva de `examples/run.sh` que observe el destino del directorio de control, ya anotada en el séptimo corte.

Con esta candidata, H2 queda cerrado: los siete componentes extraídos, la puerta completa en verde sobre el árbol publicado, y un artefacto que un consumidor puede descargar, hashear y ejecutar.

## H3 — Vertical slice completo `core.sh`

Foco conjunto: `pipeline-application` + `pipeline-step-sdk`.

Es la extracción transversal de mayor valor, porque conecta los componentes que actualmente comparten conocimientos sobre el proceso shell.

Archivos principales:

- `CoreShellStep.kt`    
- `durable/ShExecution.kt`    
- `durable/StepDispatchEngine.kt`    
- `DurableShellExecutor.kt`    
- `ProcessDurableTaskRuntime.kt`    

Las responsabilidades finales quedarían así:

|Propietario|Debe conocer|No debe conocer|
|---|---|---|
|`CoreShellStep`|Contrato y codecs de `core.sh`|`ProcessBuilder`, rutas físicas|
|`ShExecution`|Coordinación del caso de uso, resultados y puertos|FIFOs, señales Unix|
|`DurableShellExecutor`|Ejecución concreta del proceso|Eventos de dominio, CLI|
|`Output Plane`|Persistencia y lectura de bytes|Decisiones sobre el pipeline|
|`StepDispatchEngine`|Admisión, invocación y resultado del step|Implementación interna del shell|

Se revisarán especialmente los dos recorridos de ejecución de shell existentes, para distinguir compatibilidad legítima de lógica duplicada. No se fusionarán implementaciones diferentes sin demostrar equivalencia.

Entrega verificable: un `sh` completo con stdout, stderr, redacción, captura tipada y reanudación conserva la misma semántica pública.

#### El corte: el modelo de entorno se aplica una vez por recorrido

`EnvModel.apply` no es idempotente: antepone el directorio del toolchain a `PATH` en cada llamada. El recorrido durable de `sh` la aplicaba DOS veces —una en `ShExecution` y otra en `ShellLaunchPreparation.composeEnvironment`, que es la autoridad que compone el entorno del hijo—, de modo que el hijo de un `sh` durable veía `/opt/jdk/bin` dos veces en su `PATH` mientras el hijo del recorrido no durable lo veía una vez, para el mismo entorno declarado. Los dos recorridos discrepaban.

La divergencia era invisible bajo el perfil `LOCAL`, que reconstruye el `PATH` desde el keep-set, y visible bajo el perfil por defecto (`NONE`), que no lo hace. Por eso sobrevivió a las pruebas que sí observan el `PATH`.

El corte quita la aplicación de `ShExecution` en el recorrido durable y NO la quita del runtime: la autoridad del runtime (`ShellLaunchPreparation.composeEnvironment`) es la que compone el entorno del hijo y la que aplica el perfil de sandbox. El recorrido no durable conserva la suya, porque su sustrato (`ProcessDurableTaskRuntime`) materializa las variables declaradas pero no las compone. Una aplicación por recorrido, la misma función sobre el mismo entorno declarado.

El delta es exactamente una duplicación. Lo demás no cambia: el consumo de las claves `PATH+=`, la propagación de un `PATH` enmascarado, el entorno vacío, los valores de `JAVA_HOME` y `M2_HOME` y la ausencia de persistencia del entorno son los mismos antes y después. El entorno declarado se entrega intacto y la composición ocurre una sola vez, en la frontera donde el hijo arranca de verdad.

**La garantía, la capacidad y el instrumento.** La garantía es que el entorno que ve el hijo es el entorno declarado, compuesto una sola vez. La capacidad que sostiene es que un paso `sh` durable y un paso `sh` no durable lanzan el mismo entorno para la misma declaración, que es lo que permite razonar sobre el `PATH` del hijo sin tener que saber por qué recorrido pasó. El instrumento es un escenario E2E (`examples/steps/35-env-model-applied-once.pipeline.kts`), porque ninguna fila unitaria puede observar esta costura: un test de `composeEnvironment` no ve la aplicación previa de la aplicación, y un test de `ShExecution` no ve el `PATH` del hijo sin lanzar un proceso, que un test unitario no puede hacer. La alternativa más barata —una fila unitaria sobre `composeEnvironment`— no habría detectado el defecto, porque la duplicación no está en `composeEnvironment` sino en quién lo llama.

**Visto en rojo antes del corte.** El escenario se escribió primero y se ejecutó contra la distribución sin el corte. Falló con el valor duplicado a la vista:

```
FAIL steps/35-env-model-applied-once.pipeline.kts  exit 1, expected 0
OBSERVED_PATH=/opt/jdk/bin:/opt/jdk/bin:/usr/bin:/bin
```

La aserción compara el `PATH` EXACTO y no "contiene el directorio": un elemento perdido y uno duplicado se detectan los dos, y una fila que sólo comprobara la presencia del directorio habría pasado en verde con el defecto delante.

Medido sobre `eaafbb2a6` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=eaafbb2a63b4a9b4112886c1b2f22455f99d76a6  elapsed=163s  budget=900s  tests=rewritten:1416 read:0  scenarios=observed:63 executed:63
```

- 190 informes JUnit reescritos por esta ejecución, 1416 tests, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados. Los dos pasos "inestables" son los de los dos escenarios que declaran `expect-outcome unstable` (`control-flow/11-workflow-control` y `negative/12-error-handling`): declarado, no una inestabilidad nueva.
- 163 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 26421`, `PRODUCT_LOC = 71195`, ratio `0.371` (límite 2). El censo cuenta 63 programas E2E —los 62 de antes más este— y 170 ficheros de tests unitarios.

Lo que este corte NO arregla, y se dice: el recorrido no durable ignora el perfil de sandbox entero —no aplica la lista de rechazo ni normaliza el `PATH`—, que es una divergencia mayor y distinta de la que aquí se corrige. Y la mitad "los dos recorridos coinciden" de la garantía se argumenta por construcción, no se observa de extremo a extremo: el recorrido no durable es inalcanzable desde el CLI, porque `CanonicalRunRequest.controlDirRoot` no es nulo y el lanzador de escenarios pasa siempre `--control-root`.

#### El corte: el stdout capturado tiene una sola regla

El `stdout` capturado —el canal de VALOR tipado de un paso `sh` con `returnStdout`— lo leían dos componentes, en dos procesos y en dos módulos: el ejecutor, cuando el proceso termina y publica el valor, y el observador de recuperación, cuando un proceso posterior lee el directorio de control de una operación cuyo worker murió. Los dos leían el mismo fichero —`output.txt`, cuyo nombre pertenece a `ShellProtocolLayout`— pero cada uno bajo una regla propia: el ejecutor recortaba el texto y el observador no.

La consecuencia era un valor público distinto para el mismo programa: un `sh` que imprimía `hello\n` publicaba `"hello"` en una ejecución fresca y `"hello\n"` en una reanudada. No era un detalle interno. El valor recuperado llega al consumidor por el camino tipado —`CoreShellStep.recoveredProjection` → `classifyShellTerminal(terminal, STDOUT)` → `Stdout(capturedStdout.orEmpty())`—, así que la divergencia se publicaba como el valor del paso, no se quedaba en un canal lateral.

El corte converge el camino RECUPERADO sobre la regla del camino FRESCO, y no al revés. El valor fresco es la semántica pública de la que hoy dependen todos los consumidores de un `sh` que se ejecuta; cambiarla sería un cambio de semántica pública no autorizado, mientras que el camino reanudado es el que tiene que alcanzarla. La autoridad nueva —`ShellCapturedStdout`, en `pipeline-step-sdk:runtime`, junto a `ShellProtocolLayout`, que es la autoridad de los NOMBRES del protocolo— posee la REGLA DEL VALOR. Lo que queda en cada llamante es la RETENCIÓN: el ejecutor sigue descartando el fichero leído bajo `CaptureRetainPolicy.READ_THEN_DELETE`, y el observador no lo descarta, porque ese fichero es la evidencia que lee el reconciliador. Descartar es un efecto secundario que posee cada llamante, no parte del valor.

El delta es exactamente una duplicación de la regla de lectura. Lo demás no cambia: el nombre del canal sigue siendo el del protocolo, la ausencia (`null`) sigue siendo un hecho distinto de la vacuidad (`""`), el ejecutor sigue sin lanzar nunca, el reconciliador sigue construyendo el mismo `DurableTaskTerminal.Exited` y la proyección del Step sigue siendo la misma. Medido sobre el árbol del corte, `Files.readString(outputFile)` aparece en un solo fichero de código de producción —la autoridad— y ninguna otra fuente lee el canal.

**La garantía, la capacidad y el instrumento.** La garantía es que el `stdout` capturado publica el mismo VALOR lo publique la ejecución que lo leyó ahora o la que lo leyó después de la muerte del worker. La capacidad que esa garantía sostiene es que un `sh` durable con `returnStdout` sobrevive a la muerte de quien lo ejecutaba y sigue reportando el valor que el programa imprimió. El instrumento es un test unitario (`CapturedStdoutSingleRuleTest`), no un escenario E2E, por una razón concreta: la ventana entre «el proceso publicó `result.txt`» y «el ejecutor borró `output.txt`» es de microsegundos, así que un escenario que intentara matar a un worker dentro de ella sería flaky por construcción. La costura, en cambio, es enteramente observable en proceso: el observador toma un directorio de control y devuelve un terminal, y el lector del ejecutor toma un directorio de control y devuelve un texto.

Las filas comparan el valor recuperado con el valor fresco y, además, con el texto absoluto: comparar sólo los dos lectores también pasaría si los dos estuvieran mal del mismo modo, así que el texto se fija aparte y la igualdad de los caminos se afirma por separado. Una cuarta fila afirma que el observador no se deshace de la evidencia que lee.

**Visto en rojo antes del corte.** El instrumento nuevo se ejecutó contra el observador de antes del corte —el fichero `RunningSubprocessRecovery.kt` restaurado desde `6f32b33bd` sobre el árbol commiteado— y falló con el valor duplicado a la vista:

```
CapturedStdoutSingleRuleTest > a resumed execution publishes the value a fresh execution publishes() FAILED
    org.opentest4j.AssertionFailedError: expected: <hello> but was: <hello\n>
4 tests completed, 1 failed
```

Las otras tres filas —vacuidad, ausencia y no-disposición— quedaron en verde: la fila que se enrojece es exactamente la que guarda la garantía, y ninguna otra se movió. El poder discriminante se comprobó además con dos mutaciones focales sobre el código nuevo: quitar el `trim` de la autoridad, y devolverle al observador una lectura propia. Cada una puso en rojo esa misma fila y sólo esa.

Medido sobre `b0a78904b` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=b0a78904b21fb81353ff632d3b1aa1b4e270b342  elapsed=139s  budget=900s  tests=rewritten:1420 read:0  scenarios=observed:63 executed:63
```

- 191 informes JUnit reescritos por esta ejecución, 1420 tests, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados. Los dos pasos «inestables» son los de los dos escenarios que declaran `expect-outcome unstable` (`control-flow/11-workflow-control` y `negative/12-error-handling`): declarado, no una inestabilidad nueva.
- 139 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 26492`, `PRODUCT_LOC = 71201`, ratio `0.372` (límite 2). El censo cuenta 63 programas E2E —los mismos 63 que antes, porque este corte no añade escenario— y 171 ficheros de tests unitarios: uno más, el de la garantía nueva.

Lo que este corte NO arregla, y se dice: la garantía que se protege aquí es la del VALOR, y no la de la RETENCIÓN ni la de la EXCLUSIVIDAD. Nada impide que un segundo lector del mismo fichero aparezca mañana con su propia regla, porque un test unitario no puede afirmar que no existe un lector que no está en la clase que prueba: esa propiedad se sostiene por la medición de arriba —una sola ocurrencia de `Files.readString(outputFile)` en el código de producción— y por la revisión, no por una aserción. Y el paso RECUPERADO conserva un comportamiento que este corte no toca: cuando el fichero `output.txt` no existe, la proyección devuelve `InsufficientEvidence` en vez de un valor, y eso no cambia aquí.

|Garantía|Original|Sustituto o justificación|Evidencia|Decisión|Coste|Riesgo|
|---|---|---|---|---|---|---|
|El `stdout` capturado publica el mismo valor lo publiquen la ejecución fresca y la reanudada|Ninguno: no había instrumento, y el defecto era invisible porque los dos valores sólo se comparan cuando un consumidor los ve|Test unitario `CapturedStdoutSingleRuleTest`: valor, vacuidad, ausencia y no-disposición|Rojo reproducido contra el observador de `6f32b33bd` (`expected: <hello> but was: <hello\n>`), dos mutaciones focales con el mismo discriminante, verde en `b0a78904b`, puerta completa en verde|Promote (regresión permanente)|131 líneas en el fichero de test, 71 efectivas para el censo; ratio `VERIFY_LOC`/`PRODUCT_LOC` 0,372|Bajo: converge sobre la semántica que el camino fresco ya publica, así que ningún valor público cambia de forma|
|La ausencia del canal y su vacuidad son hechos distintos y no se colapsan|Ninguno: la diferencia estaba codificada en dos sitios, cada uno con su propia forma de expresarla|Las dos filas de ausencia y vacuidad del mismo instrumento, no un segundo test|Verde en `b0a78904b`|Replace (no hay segundo instrumento)|0 líneas adicionales|Bajo: la ausencia la publica `capturedStdout = null` y la vacuidad `""`|
|La evidencia que el reconciliador lee no se descarta en la observación|Ninguno: el observador podía leer y borrar el mismo fichero|Fila `the observer reads the captured stdout without disposing of it`|Verde en `b0a78904b`|Promote|Incluido en el mismo fichero, sin coste adicional|Bajo: la retención sigue siendo del ejecutor, bajo `CaptureRetainPolicy`|
|El canal no tiene un segundo lector con regla propia|Ninguno|No hay instrumento admisible: un test unitario no puede afirmar la ausencia de un lector sin examinar el texto de producción, y AGENTS.md prohíbe la forma de aptitud por texto de fuente|Censo sobre `b0a78904b`: una sola ocurrencia de `Files.readString(outputFile)` en el código de producción|Retain (declarada, no aserción)|0|Un lector futuro con regla propia no lo detectaría el test; lo vería la revisión|

#### Revisión: los dos recorridos de `sh`, y qué es de cada uno

H3 se abrió con una pregunta que no es de estilo sino de sustantivo: *«se revisarán especialmente los dos recorridos de ejecución de shell existentes, para distinguir compatibilidad legítima de lógica duplicada»*. La respuesta no se deduce leyendo los nombres: se deduce midiendo.

**Los dos recorridos.** `ShExecution.invokeShell` —un `object`, no una clase— tiene dos caminos, y se eligen por el valor de `controlDirRoot`:

- el **durable** (`controlDirRoot != null`): compone el entorno del hijo bajo el perfil de sandbox (`ShellLaunchPreparation.composeEnvironment`), lo lanza por `DurableShellExecutor` (`ShExecution.kt:344`), publica la transcripción en el Output Plane por canales y sella el final de la operación, y deja un directorio de control que otro proceso puede reconciliar. El terminal lo decide `ShellTerminalDecision` (módulo runtime) y el valor capturado se lee de `output.txt` con la regla de `ShellCapturedStdout`;
- el **no durable** (`controlDirRoot == null`): compone el entorno en el propio `ShExecution` (`ShExecution.kt:241`), lo lanza por `ProcessDurableTaskRuntime` —el sustrato genérico de procesos, el mismo que usan `tar`, `git` y las sondas de release—, acumulando stdout y stderr en dos `StringBuilder` para pegarlos en un único `EchoOutputCaptured` (`ShExecution.kt:713`), y construye el terminal a mano desde el ADT del dominio (`ShExecution.kt:726-745`). No deja nada que reconciliar: el directorio de control que usa es un temporal que él mismo crea y borra.

**El no durable es inalcanzable, y está medido.** La cadena del directorio de control no admite un valor nulo en ningún punto de producción:

- `resolveDurableControlRoot(config, dbPath)` (`MainDurableRun.kt:107`) y `resolveVolatileControlRoot(config)` (`MainVolatileRun.kt:80`) devuelven siempre un `Path`; el mundo volátil, sin `--control-root`, crea un directorio temporal (`Files.createTempDirectory("pipelinek-inmem-run")`, `MainVolatileRun.kt:354`);
- `CanonicalRunPolicy.controlDirRoot: Path` (`CanonicalRunRequest.kt:71`) no es nulable, y `runCanonical` lo pasa al coordinador (`CompositionRoot.kt:69-97`), el coordinador al motor de despacho (`CanonicalDurableRunCoordinator.kt:342`), el motor al contexto (`StepDispatchEngine.kt:228-235`), el contexto al adaptador (`CanonicalRuntimeCapabilityAccess.kt:201-205`) y el adaptador a `invokeShell` (`ShOperationsAdapter.kt:64`);
- el ÚNICO sitio de construcción de producción del coordinador es `CompositionRoot.kt:69`, y pasa el valor explícitamente: el valor por defecto `= null` de `CanonicalDurableRunCoordinator.kt:70` no lo usa nadie;
- el censo de los cuatro tipos de la cadena que sí se construyen —`CanonicalDurableRunCoordinator`, `StepDispatchEngine`, `CanonicalRuntimeContext`, `ShOperationsAdapter`— da **0 sitios de construcción en test** y uno en producción cada uno, salvo el contexto, que tiene dos: `CompositionRoot.kt:69` construye el coordinador, `CanonicalDurableRunCoordinator.kt:324` el motor de despacho, `StepDispatchEngine.kt:228` y `ScriptedFrontendRunner.kt:120` el contexto, y `CanonicalRuntimeCapabilityAccess.kt:201` el adaptador;
- el lanzador E2E pasa `--control-root` en todas sus invocaciones de `run` (`examples/run.sh:2143`, `2178`, `2199`, `2239`, `2285`, `2307`), así que la suite tampoco lo alcanza.

No es un camino raro: es un camino que nadie recorre.

**Qué es de cada uno.**

|Divergencia|Recorrido durable|Recorrido no durable|Veredicto|
|---|---|---|---|
|La clasificación del terminal|`ShellTerminalDecision` (módulo runtime): cuatro formas ricas —`Exited`, `LaunchFailed`, `Lost`, `Cancelled`—|`TaskTerminalDecision` (dominio, publicado): tres formas planas, traducidas a mano en `ShExecution.kt:726-745`|**Compatibilidad legítima.** Son dos protocolos y dos sustratos distintos: el durable lee su propio `result.txt` —un entero plano, `DurableShellExecutor.kt:241`— y el no durable usa el sustrato genérico, cuyo protocolo escribe `EXIT <n>` o `TIMED_OUT` (`ProcessDurableTaskRuntime.kt:162-170`). La KDoc de `ShellTerminalDecision` lo dice: *«This is NOT the domain's `TaskTerminalDecision`»*. Lo único que sí es duplicación es la TRACCIÓN: el tercer constructor de terminales que H2 señaló|
|El valor del `stdout` capturado|stdout solo, recortado, leído de `output.txt` por `ShellCapturedStdout`|stdout **y** stderr pegados, sin recortar, y publicados como el valor del stdout en `DurableTaskOutput` (`ShExecution.kt:713` y `:743`)|**Lógica duplicada, y divergente.** El mismo contrato público —el valor de `Stdout` para `returnStdout`— con dos reglas, y una de ellas publica bytes de stderr como si fueran el valor del stdout|
|El perfil de sandbox|`ShellLaunchPreparation.composeEnvironment` aplica la lista de rechazo y normaliza el `PATH`|`shOptions.sandbox` no llega al sustrato: no hay lista de rechazo ni normalización|**Lógica duplicada por omisión.** Es la divergencia que el primer corte declaró por escrito como «lo que este corte NO arregla»|
|La transcripción|dos flujos canalizados en el Output Plane, sellados al terminal|un `EchoOutputCaptured` con stdout y stderr pegados|**Compatibilidad legítima, y el producto lo dice por sí mismo.** La KDoc de `ShExecution.kt:705-711`: *«The law is therefore per-execution, not per-type»*. El Output Plane prohíbe una SEGUNDA autoridad sobre los mismos bytes, no exige que exista donde no puede existir: el recorrido no durable no tiene directorio de control, luego no tiene Output Plane|
|El punto de aplicación del modelo de entorno|en el sustrato (`composeEnvironment`)|en `ShExecution` (`ShExecution.kt:241`)|**Compatibilidad legítima**, ya cerrada en el primer corte: una aplicación por recorrido, sobre el mismo entorno declarado|
|El `result.txt` que deja cada uno|El suyo: `ShellProtocolLayout(controlDir).resultFile`, un entero plano|El del sustrato genérico, escrito con un nombre literal —`controlDir.resolve("result.txt")`, `ProcessDurableTaskRuntime.kt:227`— en un temporal que la ruta borra al terminar y que nunca relee|**Compatibilidad legítima.** No hay un fichero compartido con dos gramáticas: son dos protocolos en dos directorios distintos. Lo que sí es verdad es que la ruta no durable USA un sustrato cuyo protocolo no consume|

**El veredicto de la revisión.** Ninguna de las dos fusiones que la tabla de propiedad de H3 podría sugerir tiene sentido: los dos recorridos no implementan el mismo protocolo, así que no se pueden fusionar sin demostrar una equivalencia que no existe —y el ROADMAP la prohíbe sin demostración—. Lo que sí es cierto es lo contrario de lo que suele suponerse: la mitad del sistema que parece «la alternativa compatible» es la que se aparta. Dos de las seis divergencias —el valor del stdout y el perfil de sandbox— son duplicación con divergencia, y las dos están en un camino que nadie recorre. La pregunta que queda no es «¿cómo se unifican?» sino «¿por qué existe un segundo camino?».

**Lo que la respuesta cuesta, y por qué no es «arreglarlo».** La ruta no durable no tiene un instrumento admisible. Repararla —aplicarle el sandbox, partir el stdout, unificar el terminal— no se puede verificar: un test unitario necesitaría lanzar un proceso, que AGENTS.md §2 prohíbe; un escenario E2E no puede alcanzarla, porque el CLI siempre pasa un directorio de control. Un cambio que nadie puede observar no se puede cerrar con evidencia, y la evidencia es lo que cierra un corte. La única salida honesta es la eliminación, que AGENTS.md §1 ya pide —*«remove dead legacy fallbacks rather than building adapters upon adapters»*— y que además devuelve a `ShExecution` la propiedad que la tabla de H3 le exige: no conocer la implementación interna del shell. Hoy `ShExecution` construye el sustrato de procesos en la línea 659, es decir, la coordinación conoce la implementación, que es exactamente lo que la tabla prohíbe.

**Lo que NO se elimina.** `ProcessDurableTaskRuntime` y `TaskTerminalDecision` se quedan: no son de la ruta no durable de `sh` sino el sustrato de procesos de `tar`, `git` y las sondas de release (`TarWriter.kt:71`, `GitPollExecutor.kt:30`, `GitChangelogWriter.kt:30`, `GitCheckoutExecutor.kt:67`, `SourceProvenanceProbe.kt:94`). `TaskTerminalDecision` tiene además un consumidor en el propio SDK (`CapturedRun.kt:61-65`), otro en el propio sustrato que escribe la gramática de `result.txt` (`ProcessDurableTaskRuntime.kt:162-170`) y uno en `tar` a través de sus funciones (`TarWriter.kt:103`). Y `pipeline-domain` está en `publishedContractModules`, así que el ADT es ABI publicado: no se puede tocar, ni siquiera para quedarse sin consumidores.

#### El corte seleccionado: el directorio de control existe siempre

Este corte se abrió y se cerró: la sección siguiente registra su implementación, su rojo y su puerta. Esta sección se conserva como el registro de lo que se decidió ANTES de mirar el código, incluido el punto en el que la implementación se desvió de la enumeración.

**Qué hace.** La propiedad que se decide escribir en el TIPO es una sola —*el directorio de control existe siempre*— y su efecto sobre `sh` es que un `sh` tenga un solo recorrido. El corte:

1. hace `controlDirRoot` no nulable en la cadena —`CanonicalDurableRunCoordinator` → `StepDispatchEngine` → `CanonicalRuntimeContext` → `ShOperationsAdapter` → `ShExecution.invokeShell`— y elimina el valor por defecto `= null` del coordinador (`CanonicalDurableRunCoordinator.kt:70`), que nadie usa;
2. elimina la rama no durable de `ShExecution` (`ShExecution.kt:228` y la función `executeNonDurableInvocation`) —esta enumeración se desvió al implementar: la función no se eliminó, se renombró, y el porqué está en la sección siguiente;
3. elimina las envoltorias muertas de `ShExecution` que existían sólo para proyectar la variante de raíz nulable —`runShellCommand` (`:185`), `runShellCommandTyped` (`:447`) y `executeBranchStep` (`:537`)—, las tres sin ningún llamante en producción ni en test según el censo, y ajusta la KDoc que las nombra (`ShellStepOutcomeClassifier.kt:11` y `:24`) —el censo de la implementación mide CUATRO envoltorias muertas, no tres: `runShStep` (`:151`) se había quedado fuera de la lista—.

**El alcance real es más ancho que el shell, y se declara.** La misma nulabilidad que separa los dos recorridos de `sh` gobierna otras siete decisiones de producción, y todas ellas son hoy incondicionales porque la cadena nunca es nula: el registro condicional de siete capacidades en `CanonicalRuntimeCapabilityAccess` —`LOCK_COORDINATION` (`:280`), `INPUT_DECISIONS` (`:297`), `DELETE_DIR_OPERATIONS` (`:421`), `CLEAN_WS_OPERATIONS` (`:447`), `ARTIFACT_ARCHIVE_OPERATIONS` (`:488`), `STASH_OPERATIONS` (`:509`) y `PUBLISH_HTML_OPERATIONS` (`:530`)—, la resolución del workspace de etapa (`CanonicalDurableRunCoordinator.kt:429`) y la del workspace de rama paralela (`ParallelStageEngine.kt:445`). Al volverse el tipo no nulable, esos siete `?.let` dejan de ser condicionales: su comportamiento de producción NO cambia —hoy el bloque siempre se ejecuta—, pero la prosa que los presenta como «expuestos sólo cuando hay directorio de control, ausentes si no, para que la admisión falle cerrada» pasa a describir un estado que no puede ocurrir. Esa prosa es la que hay que corregir, y corregirla es parte del corte.

**Lo que el corte no hace.** No cambia el valor público de `core.sh` —la ruta eliminada no se recorre—, no fusiona protocolos, no toca ningún módulo publicado, no añade funcionalidad y no deja el sustrato de procesos de `tar`, `git` y release sin su sitio. Tampoco toca el puerto `ExternalSubprocessRecovery`, que conserva su parámetro nulable: es un puerto, y su caso `Unavailable`/`NoControlRootConfigured` (`RunningSubprocessRecovery.kt:126` y `:156`) queda inalcanzable desde la ruta canónica pero no muerto como capacidad del puerto. Retirarlo tocaría la KDoc de `pipeline-runtime-api` (`RecoverRefusal.kt:30`), que es un módulo publicado: es una decisión posterior, no este corte.

**Decisiones de impacto (lente `change-impact`).**

- **`run-affected`** — `:pipeline-application`, y sólo ese módulo: es donde viven los cinco tipos de la cadena, la puerta de capacidades y las tres envoltorias muertas. Ningún otro módulo compila contra esas clases. El mapa de consumidores afectados está completo y medido —`CompositionRoot` → coordinador → motor de despacho → contexto → acceso a capacidades → adaptador → `invokeShell`, más `ParallelStageEngine` y `ExternalSubprocessRecovery`—, y no tiene huecos. Riesgo: bajo; lo que se elimina no lo recorre nadie. Comando real: `scripts/verify-all.sh --stage 1 --module ':pipeline-application'`.
- **`widen`** — a la suite E2E completa y al gate entero, no a una selección parcial. Motivo: el único observador de `core.sh` de extremo a extremo es la suite de escenarios, y `sh` aparece en la mayoría de los 63; una selección parcial no distinguiría «el corte no rompió nada» de «el corte no llegó a tocarse». Y el corte toca la admisión de siete capacidades, que se observa por escenarios de `core.lock`, `core.input`, `core.deleteDir`, `core.cleanWs`, `core.archiveArtifacts` y `core.stash`, no sólo por los de `sh`. Riesgo: bajo; el coste es el tiempo del gate, que ya está dentro del presupuesto de 900 s. Comandos reales: `scripts/verify-all.sh --stage 3 --scenario 'steps/*'` para el bucle interior, y `scripts/verify-all.sh` sin filtros para la evidencia del hito.
- **`defer-to-integration`** — nada que diferir. `apiCheck` no aplica: `pipeline-application` no está en `publishedContractModules` ni en `bcvModules`, y el corte no toca ningún módulo publicado.
- **Contraprueba** — un consumidor nuevo no mapeado jamás debe producir un PASS vacío: el corte no crea capacidades ni caminos nuevos, y el mapa de consumidores de arriba se midió sobre el árbol, no sobre una lista de suposiciones.

**El instrumento.** La garantía de este corte es estructural —«el directorio de control existe siempre»— y su instrumento es el COMPILADOR: un `controlDirRoot` no nulable hace que la rama no durable no pueda existir, y ninguna aserción afirmaría la ausencia de una rama mejor que el tipo que la prohíbe. El valor público de `core.sh` y la admisión de capacidades lo observan los 63 escenarios E2E, que no cambian. Por eso el corte no añade pruebas: no hay una garantía nueva que proteger, hay una garantía que el tipo hace verdadera.

**Lo que este corte NO arregla, y se dice.** No arregla la clasificación de terminales del sustrato genérico de procesos —`TaskTerminalDecision` para `tar`, `git` y release—, que es una compatibilidad legítima y no un duplicado. No arregla que el perfil de sandbox se aplique hoy sólo en el recorrido durable: al quedar un solo recorrido la pregunta se cierra sola, pero no se añade un escenario que lo observe, porque el sandbox ya tiene su instrumento permanente en el módulo runtime (`ShellLaunchPreparationTest`, que cubre la lista de rechazo y la normalización del `PATH`). Y no arregla la prosa de `CoreShellStep` (`:451`) ni de `CanonicalRuntimeCapabilityAccess` (`:146`), que hoy describen un recorrido y un modo de admisión que dejarán de existir: esa prosa se ajusta en el corte, no antes.

|Garantía|Original|Sustituto o justificación|Evidencia|Decisión|Coste|Riesgo|
|---|---|---|---|---|---|---|
|El directorio de control existe siempre, y un `sh` tiene un solo recorrido de ejecución|Ninguno: la propiedad se sostenía por lectura del código, y ningún instrumento podía afirmar la ausencia de una rama|El TIPO: `controlDirRoot` no nulable en los cinco eslabones de la cadena, sin valor por defecto. El compilador es el instrumento; un test unitario afirmaría menos que el tipo que la prohíbe|Censo medido sobre `44ed64a2a`: 0 sitios de test construyen los cuatro tipos construibles de la cadena, un sitio de producción cada uno salvo el contexto, que tiene dos, el valor por defecto `= null` sin uso, y las dos ramas con `null` (`ShExecution.kt:228`, `:556`) internas al propio fichero|Replace (el compilador sustituye a un test imposible)|7 ficheros de producción; ~150 líneas de producto menos; 0 cambios en tests; el ratio `VERIFY_LOC`/`PRODUCT_LOC` mejora|Bajo: lo que se elimina no lo recorre nadie, y el valor público de `core.sh` no cambia|
|La semántica pública de `core.sh` no cambia con el corte|Los 63 escenarios E2E y las 1420 pruebas unitarias de la puerta completa|Los mismos, sin cambio: el corte quita un camino no recorrido y no toca el que sí lo es|Puerta completa sin filtros sobre el árbol del corte (a ejecutar al abrirlo)|Retain|0|Bajo: la ruta eliminada era inalcanzable en los dos mundos y en la suite E2E|
|La admisión de las siete capacidades ancladas al directorio de control sigue igual|Los escenarios E2E que las ejercitan y el comportamiento actual, que es siempre registrarlas|Los mismos, sin cambio: en producción el bloque condicional siempre se ejecutaba; lo que cambia es que la condición desaparece del código y de la prosa|Puerta completa sin filtros sobre el árbol del corte (a ejecutar al abrirlo)|Retain|0|Bajo: ninguna rama de producción cambia de comportamiento; lo que se retira es la condición imposible|
|Los dos clasificadores de terminal son dos conceptos y no un duplicado|Ninguno|Las KDoc de `ShellTerminalDecision` («This is NOT the domain's `TaskTerminalDecision`») y el ADT publicado `TaskTerminalDecision`, con sus consumidores reales de `tar`, `git`, release y del propio SDK|Censo medido: `TarWriter.kt:103`, `CapturedRun.kt:61-65`, `ProcessDurableTaskRuntime.kt:162-170`, `GitPollExecutor.kt:30`, `GitCheckoutExecutor.kt:67`, `GitChangelogWriter.kt:30`, `SourceProvenanceProbe.kt:94`|Retain (declarada, no aserción)|0|Ninguno: son dos protocolos con dos gramáticas de `result.txt`|
|El perfil de sandbox se aplica al entorno del hijo|Test unitario `ShellLaunchPreparationTest` en el módulo runtime: lista de rechazo y normalización del `PATH`|El mismo, sin cambio: el corte no toca la composición del entorno del recorrido durable|Ya en verde en el árbol actual (`b0a78904b`, puerta completa)|Retain|0|Bajo|
|El `stdout` capturado publica el mismo valor lo publiquen la ejecución fresca y la reanudada|Test unitario `CapturedStdoutSingleRuleTest`|El mismo, sin cambio: el corte no toca ni el ejecutor ni el observador de recuperación|Ya en verde en el árbol actual (`b0a78904b`, puerta completa)|Retain|0|Bajo|
|Las divergencias D2 (valor del stdout) y D3 (perfil de sandbox) no pueden volver a aparecer en `sh`|Ninguno: eran propiedades de un camino que nadie recorría, y el mejor instrumento era precisamente no tener camino|La eliminación del camino: sin segundo recorrido, no hay segunda regla que sostener|Censo de las ramas eliminadas al abrir el corte|Replace (la eliminación es el instrumento)|Incluido en el coste del corte|Ninguno: la divergencia no era observable y, al no existir el camino, deja de existir la condición que la producía|
|El caso `NoControlRootConfigured` del puerto de observación es inalcanzable desde la ruta canónica, y se dice en vez de fingir que no lo es|Ninguno|La KDoc del corte lo declara como caso del puerto, no de la ruta; retirarlo tocaría `pipeline-runtime-api`, que es un módulo publicado|Censo: el único productor es `RunningSubprocessRecovery.kt:202`, y el único punto de construcción de producción del coordinador pasa un valor no nulo|Defer (decisión posterior, fuera de este corte)|0|Ninguno para este corte; queda declarado como trabajo futuro|

#### El corte: el directorio de control existe siempre

**Qué hace.** `controlDirRoot` deja de ser nulable en los cinco eslabones de la cadena —`CanonicalDurableRunCoordinator` → `StepDispatchEngine` → `CanonicalRuntimeContext` → `ShOperationsAdapter` → `ShExecution.invokeShell`— y el coordinador pierde el valor por defecto `= null` (`CanonicalDurableRunCoordinator.kt:70`), que el censo de la revisión ya había medido como no usado por nadie. La rama que elegía recorrido (`ShExecution.kt:228`) desaparece, y con ella las envoltorias muertas que existían sólo para proyectar la variante de raíz nulable: `runShStep` (`:151`), `runShellCommand` (`:185`), `runShellCommandTyped` (`:447`), `executeBranchStep` (`:537`) y `toLegacyStatus`.

**El censo de la revisión tenía dos errores, y el corte los corrige.** La revisión enumeró tres envoltorias muertas; el censo de este corte mide CUATRO: `runShStep` (`ShExecution.kt:151`) no estaba en la lista. Y la revisión cuenta cinco consumidores de la cadena; son SEIS: `WorkspaceOperationsAdapter` (`WorkspaceOperations.kt:74`) construye su adaptador con la raíz del coordinador, y tres de sus operaciones —`writeFile`, `readFile` y `fileExists`— lanzaban `IllegalStateException("controlDirRoot is required for workspace file operations")` cuando la raíz era nula. Con la raíz no nulable esos tres rehusos quedan inalcanzables, y se eliminan: la operación usa la raíz.

**La desviación: el camino no durable NO se elimina, se renombra.** La revisión y el corte seleccionado enumeran borrar `executeNonDurableInvocation` entera. Al implementarlo, un hecho medido obliga a desviarse de esa enumeración, y la desviación se declara aquí en vez de esconderse:

- `requireLinuxHost()` lanza `LinuxRequiredException` en el propio lanzamiento durable, en los dos sitios donde el hijo tiene que existir: `ShellProcessLauncher.kt:102` y `DurableShellExecutor.kt:201`;
- `ShExecution` captura esa excepción y enruta a la ruta no durable;
- por tanto, en un host que no es Linux, `core.sh` se ejecuta SÓLO por esa ruta: la ruta durable es la que lanza;
- macOS es una plataforma soportada del producto: el README lo dice en dos sitios —«Linux, macOS and Windows (WSL) are supported» (`README.md:39`) y «**OS**: Linux, macOS, Windows via WSL» (`README.md:256`).

Borrar esa función habría roto `core.sh` en macOS, y la puerta no lo habría observado: la puerta corre sobre un host Linux, donde la ruta durable nunca lanza. Un corte que se cierra en verde sobre el host que lo ejecuta, y rompe el producto en otro host declarado como soportado, es un corte mal cerrado. Así que la función se conserva, renombrada a `executeNonLinuxInvocation`, con su KDoc diciendo lo que es —no un segundo recorrido, sino un SUSTRATO DISTINTO PARA UNA PLATAFORMA DISTINTA— y con la raíz no nulable. Su semántica queda intacta bit a bit: en macOS la raíz ya era no nula, luego `ownsControlDir` ya era falso, luego el directorio temporal ya nunca se creaba y la ruta ya usaba `controlDirRoot.resolve(opId)`. Lo que se retira de esa función es exactamente lo que la raíz no nulable hace imposible: `ownsControlDir`, `Files.createTempDirectory` y el `finally { deleteRecursively }` que borraba un directorio que ya no se creaba.

**La prosa.** El corte incluye corregir la prosa que describe un estado imposible. Nueve ficheros de producción: `ShellStepOutcomeClassifier.kt` (la KDoc que nombraba las envoltorias duplicadas), `CanonicalRuntimeCapabilityAccess.kt` (la KDoc de `SHELL_OPERATIONS_CAPABILITY` y las tres funciones de registro, más los comentarios de bloque que describían la exposición condicional), `CanonicalDurableRunCoordinator.kt` (la resolución del workspace de etapa), `ParallelStageEngine.kt` (la del workspace de rama paralela), `CoreStepRegistryFactory.kt`, `CoreCleanWsStep.kt`, `CoreDeleteDirStep.kt` (las tres menciones de «exposed only when controlDirRoot != null» y «fails closed»), `CoreShellStep.kt` (el pasaje que nombraba el camino no durable) y `WorkspaceOperations.kt` (los tres rehusos que quedan inalcanzables). La prosa de `examples/steps/35-env-model-applied-once.pipeline.kts` también nombraba el recorrido no durable como el que «ya produce» el valor esperado; se corrige, porque ese recorrido ya no existe.

**La garantía, la capacidad y el instrumento.** La garantía es que el directorio de control existe siempre y un `sh` tiene un solo recorrido de ejecución. La capacidad que sostiene es que el código del producto no puede volver a bifurcarse por una raíz que nadie puede nular: un `sh` se comporta igual en cualquier host, sin una variante cuya semántica nadie observa. El instrumento es el COMPILADOR, y no un test: la garantía es estructural —«una rama que no puede existir»— y ninguna aserción afirmaría la ausencia de una rama mejor que el tipo que la prohíbe. Por eso el corte no añade pruebas: no hay una garantía nueva que proteger, hay una garantía que el tipo hace verdadera.

**Visto en rojo antes del corte.** El compilador se usó como instrumento con dos mutaciones discriminantes, cada una aplicada, leída de vuelta y revertida:

```
e: .../CanonicalRuntimeCapabilityAccess.kt:205:30 Null cannot be a value of a non-null type 'Path'.
BUILD FAILED in 3s
```

La primera mutación —`controlDirRoot = null` en la construcción del adaptador de operaciones de shell— puso en rojo exactamente UNA fila. La segunda —`CanonicalRuntimeContext.controlDirRoot` de vuelta a `Path?`— puso en rojo NUEVE filas, todas en `CanonicalRuntimeCapabilityAccess.kt` (`:205`, `:278`, `:289`, `:417`, `:440`, `:477`, `:494`, `:511`, `:629`): las siete registraciones de capacidad más las dos construcciones de adaptador. Ninguna fila colateral en ninguna otra parte del árbol. Ambas mutaciones se revirtieron, se leyeron de vuelta y el árbol quedó en verde de compilación.

**Residuales que este corte deja declarados.** `ingestTranscriptIntoOutputPlane` y `NoOpEventSink` están muertos —censo: 0 llamadores—, pero NO son una proyección de la raíz nulable, luego no pertenecen a este corte y quedan para otro. La KDoc del primero afirmaba que «a characterisation test that exercised it is still in the tree»; medido: ese test no existe. La KDoc queda corregida con la verdad medida. La instantánea de deuda de detekt de `pipeline-application` arrastra filas obsoletas: `./gradlew :pipeline-application:detektBaselineMain` sobre este árbol retiraría 50 filas, 46 de ellas de ficheros que este corte no toca (`Main.kt`, `PluginAdmissionGate.kt`, `CompositionRoot.kt`, `DslCompiledPipelineCompiler.kt`) —deuda ya pagada en cortes anteriores cuya instantánea nunca se refrescó—. Este corte sólo mueve las CUATRO filas que su propio cambio invalida: la de `executeNonDurableInvocation` pasa a `executeNonLinuxInvocation`, y se retiran las de `executeBranchStep`, del `UnnecessarySafeCall` y del `UseOrEmpty` del bloque `controlDirRoot?.let { … } ?: emptyMap()`, que ya no existe. Refrescar la instantánea entera aquí congelaría como conocido todo hallazgo presente, incluido uno introducido por error; queda como residual declarado.

**Medido sobre `126aa3b15` con `scripts/verify-all.sh` sin filtros:**

```
PASS  sha=126aa3b15c97ce93e46a0ca712e545c7aaaa9930  elapsed=142s  budget=900s  tests=rewritten:1420 read:0  scenarios=observed:63 executed:63
```

- 191 informes JUnit reescritos por esta ejecución, 1420 tests, 0 fallos, 0 errores, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 142 s de reloj de pared frente al presupuesto de 900 s.
- `VERIFY_LOC = 26492`, `PRODUCT_LOC = 71011`, ratio `0.373` (límite 2). El censo cuenta 63 programas E2E y 171 ficheros de tests unitarios: este corte no añade ninguno, y el producto baja 190 líneas (`71201` → `71011`).

**Lo que este corte NO arregla, y se dice.** No arregla la clasificación de terminales del sustrato genérico de procesos —`TaskTerminalDecision` para `tar`, `git` y release—, que es una compatibilidad legítima y no un duplicado. No arregla la divergencia D2 —el valor del `stdout` capturado, stdout y stderr pegados— del camino no durable: ese camino ya no puede existir, pero su SUSTRATO —`ProcessDurableTaskRuntime`— sigue siendo el de `tar`, `git` y release, y su valor combinado sigue siendo el valor que publica. El corte cierra la rama de `sh`, no el sustrato. No arregla el puerto `ExternalSubprocessRecovery`, que conserva su parámetro nulable: retirarlo tocaría `pipeline-runtime-api`, que es un módulo publicado. No arregla `ingestTranscriptIntoOutputPlane` ni `NoOpEventSink`. No arregla la instantánea de detekt, salvo en sus cuatro filas. Y no arregla la observación que este corte hace más incómoda: en macOS, `core.sh` corre por un sustrato que NO aplica el perfil de sandbox y que publica stdout y stderr pegados como el valor del stdout. Eso no es un defecto de este corte —es el comportamiento de siempre en macOS—, pero ahora se ve con nombre propio.

|Garantía|Original|Sustituto o justificación|Evidencia|Decisión|Coste|Riesgo|
|---|---|---|---|---|---|---|
|El directorio de control existe siempre, y un `sh` tiene un solo recorrido de ejecución|Ninguno: la propiedad se sostenía por lectura del código, y ningún instrumento podía afirmar la ausencia de una rama|El TIPO: `controlDirRoot` no nulable en los cinco eslabones, sin valor por defecto. El compilador es el instrumento; un test unitario afirmaría menos que el tipo que la prohíbe|Dos mutaciones discriminantes medidas sobre el compilador: `controlDirRoot = null` → una fila roja; `CanonicalRuntimeContext.controlDirRoot` de vuelta a `Path?` → nueve filas rojas, todas en `CanonicalRuntimeCapabilityAccess.kt`, sin colateral. Ambas revertidas y reverificadas en verde|Replace (el compilador sustituye a un test imposible)|14 ficheros, 190 líneas de producto menos, 0 cambios en tests; el ratio `VERIFY_LOC`/`PRODUCT_LOC` pasa de 0,372 a 0,373|Bajo: lo que se elimina no lo recorre nadie, y el valor público de `core.sh` no cambia|
|`core.sh` sigue ejecutándose en un host que no es Linux|Ninguno: el camino no durable servía de reserva, y la revisión lo declaraba eliminable|El camino conservado y renombrado `executeNonLinuxInvocation`, con raíz no nulable y sin directorio temporal|Medido: `requireLinuxHost()` lanza en `ShellProcessLauncher.kt:102` y `DurableShellExecutor.kt:201`, `ShExecution` captura `LinuxRequiredException`, y el README declara macOS soportado (`README.md:39`, `:256`)|Retain (el camino, renombrado y con su KDoc)|0|Alto si se hubiera borrado: `core.sh` roto en macOS y no observado por la puerta, que corre sobre Linux. La desviación respecto de la enumeración de la revisión se declara aquí|
|La semántica pública de `core.sh` no cambia con el corte|Los 63 escenarios E2E y las 1420 pruebas unitarias de la puerta completa|Los mismos, sin cambio: el corte quita un camino no recorrido y no toca el que sí lo es|Puerta completa sin filtros sobre `126aa3b15`: 1420 tests, 0 fallos, 0 omitidos, 63 de 63 escenarios en verde|Retain|0|Bajo|
|La admisión de las siete capacidades ancladas al directorio de control sigue igual|Los escenarios E2E que las ejercitan y el comportamiento actual, que es siempre registrarlas|Los mismos, sin cambio: en producción el bloque condicional siempre se ejecutaba; lo que cambia es que la condición desaparece del código y de la prosa|Puerta completa sin filtros sobre `126aa3b15`; y el compilador, que pasa a exigir la raíz en los nueve puntos de uso|Retain|0|Bajo: ninguna rama de producción cambia de comportamiento; lo que se retira es la condición imposible|
|La prosa del producto describe el estado real, no un recorrido que ya no existe|La prosa, que describía una exposición condicional y un recorrido no durable|La prosa corregida en nueve ficheros de producción y en un escenario E2E|Censo de las menciones: 0 referencias vivas a `runShStep`, `runShellCommand`, `runShellCommandTyped`, `executeBranchStep`, `toLegacyStatus` o `executeNonDurableInvocation` en el código de producción y en los ejemplos. Las menciones de «no durable» que sobreviven en el código son de otro eje —`LaunchReceipt.kt:33` habla de journal frente a memoria, `CoreErrorStep.kt:135` de entrada fresca frente a entrada recuperada—, no de los dos recorridos de `sh`|Replace (la prosa vieja)|Incluido en el coste del corte|Bajo|
|La instantánea de deuda de detekt sigue al código que la analiza|Ninguno: la instantánea nombra hallazgos por identidad de función, y un renombrado la invalida|Las cuatro filas que este corte invalida, movidas a mano; el resto de la instantánea, intacta|Medido: `./gradlew :pipeline-application:detektBaselineMain` retiraría 50 filas, 46 de ellas de ficheros que este corte no toca. La puerta con las cuatro filas movidas pasa en `126aa3b15`|Retain (las otras 46 quedan declaradas como residual)|4 líneas del fichero de instantánea|Bajo: la instantánea congelada en bloque habría ocultado hallazgos presentes|
|El caso `NoControlRootConfigured` del puerto de observación es inalcanzable desde la ruta canónica, y se dice en vez de fingir que no lo es|Ninguno|La KDoc del corte lo declara como caso del puerto, no de la ruta; retirarlo tocaría `pipeline-runtime-api`, que es un módulo publicado|Censo: el único productor es `RunningSubprocessRecovery.kt:202`, y el único punto de construcción de producción del coordinador pasa un valor no nulo|Defer (decisión posterior, fuera de este corte)|0|Ninguno para este corte; queda declarado como trabajo futuro|

#### El corte: sin timeout no significa una hora

**Qué hace.** `ShellWatchdog.pollDeadlineFor(timeoutMs)` devolvía `NO_TIMEOUT_POLL_MS = 3_600_000L` cuando la ejecución no tenía plazo. El ejecutor lo usaba como techo del poll —`while (System.currentTimeMillis() < pollDeadline && !watchdog.fired())`—, luego un `timeoutMs = 0` pedía «sin timeout» y recibía una hora. Al alcanzarla sin resultado, el ejecutor publicaba `exitCode = -1`, un número que ningún envoltorio escribió, y `ShellTerminalDecision.forExitCode` lo traducía a `DurableTaskTerminal.Exited(exitCode = -1)`: un terminal de SALIDA con un código observado, cuando lo único observado era que el reloj había vencido.

La regla estaba fijada por una prueba en verde: `ShellWatchdogTest > an execution without a deadline polls for an hour` afirmaba `assertEquals(ShellWatchdog.NO_TIMEOUT_POLL_MS, …)`. Una verde que protege una regla incorrecta.

**El corte.** Tres cosas en la misma dirección:

1. `pollDeadlineFor` devuelve `null` cuando la ejecución no tiene plazo, y el techo del poll sale del plazo y de nada más. Un operador que pidió no expirar no es abandonado por un reloj que él no puso.
2. La espera pasa a tener dueña: `ShellResultPoll` la duerme y nombra QUÉ la terminó —publicado, expirado, resultado que no es un código de salida, envoltorio que terminó sin publicar, techo alcanzado, o nada todavía—. La regla es una función pura de hechos (`shellPollOutcome`), y el ejecutor ya no calcula un techo con aritmética en línea.
3. `ShellTerminalDecision.forPollOutcome` ya no fabrica una salida: lo único que produce `Exited` es un código PUBLICADO. Todo lo demás —resultado ilegible, envoltorio que terminó sin publicar, techo vencido, espera interrumpida— es `Lost`, que es reintentable y nunca implica éxito.

Además, la ventana de `settle()` —los dos segundos que se le dan al vigilante para publicar su cerrojo tras la muerte del hijo— se abre sólo cuando el plazo YA pasó, porque antes de eso no hay ninguna muerte con la que competir. Antes la pagaba toda ejecución con plazo: dos segundos de sueño por una muerte que no venía. Y las duraciones pasan al reloj MONOTÓNICO, porque un salto del reloj de pared no debe terminar la espera de una ejecución.

**Por qué un envoltorio que terminó sin publicar NO es una salida.** El envoltorio publica el resultado ANTES de quitar la cookie:

```text
echo $EXIT_CODE > result.txt.tmp ; mv result.txt.tmp result.txt ; rm -f .cookie
```

Luego una cookie ausente sin resultado publicado significa que el envoltorio nunca llegó a la publicación: lo mataron, o murió. El código de salida no existe, y el único terminal honesto para un código que no existe es el que dice que el desenlace no se pudo establecer.

**La garantía, la capacidad y el instrumento.** La garantía es que una ejecución que no publica resultado nunca se reporta como un código de salida. La capacidad que sostiene es que un lector de un run durable puede distinguir un paso que terminó de un paso cuyo desenlace no se pudo establecer, y que una ejecución sin plazo espera al protocolo y no a un reloj. El instrumento es un test unitario: la regla (`shellPollOutcome`) y su traducción (`forPollOutcome`) son funciones puras sobre hechos, así que el instrumento más barato que puede fallar es una llamada a ellas. El escenario E2E durable no se usa aquí: el defecto sólo era observable esperando una hora, y una prueba que espera una hora no es un instrumento.

**Visto en rojo antes del corte.** Tres mutaciones discriminantes, cada una aplicada, leída de vuelta con `git diff` y revertida:

|Mutación|Fila que se puso roja|Filas del lote|
|---|---|---|
|`WrapperEnded` vuelve a fabricar `Exited(exitCode = -1)`|`ShellTerminalDecisionTest > a wrapper that ended without publishing a result is lost()`|16 tests, 1 rojo, sin colateral|
|`pollDeadlineFor` vuelve a devolver la hora|`ShellWatchdogTest > an execution without a deadline has no ceiling()`|14 tests, 1 rojo, sin colateral|
|El orden de las ramas pone la ausencia del envoltorio antes del resultado publicado|`ShellResultPollTest > a published result ends the wait with its exit code()`|7 tests, 1 rojo, sin colateral|

**Lo que este corte NO arregla, y se dice.** No arregla `settle()` con un instrumento propio: la ventana ahora se abre sólo tras el plazo y ningún test unitario mide ese tiempo. La garantía que lo sostiene es «no cambia ningún veredicto» —la ventana sólo podía abrirse después del plazo, que es cuando el vigilante puede disparar—, y eso es una derivación del código, no una observación; se declara como tal en vez de vestirse de cobertura. No arregla el orden entre «el envoltorio terminó» y «el techo venció»: los dos terminan en `Lost` y sólo difieren en el mensaje, así que no hay una garantía que los separe. No arregla `DurableShellExecutor.isAlive(controlDir, process)`, una función que pregunta por la cookie y por un `ProcessHandle` y a la que el censo no encuentra ningún llamante de producción: es un residuo declarado y este corte no la toca. Y no arregla el AGENTE: `AgentRendezvous` sigue sin garantizar que un fallo de arranque no deje descriptores ni proceso abiertos. Eso pertenece al corte de `AgentRendezvous`, que sigue declarado en H2 y no se cierra aquí —se cerró después, en el corte de H2 «un acoplamiento fallido no deja nada abierto» (`f39838e9b`), que añadió la liberación de descriptores y la muerte del agente, y que este corte no anticipa ni sustituye—.

**Medido sobre `2e3a1efd5` con `scripts/verify-all.sh` sin filtros —la misma ejecución midió los tres cortes de esta tanda, que salen del mismo árbol—:**

```
PASS  sha=2e3a1efd5faf41c198feec425a62f8c66718c2f1  elapsed=108s  budget=900s  tests=rewritten:1460 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1460 tests, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 108 s de reloj de pared frente al presupuesto de 900 s — cifra que la build cache contaminó: la medición
  real de este árbol es de 188 s, corregida con su reejecución más abajo (corte del gate).
- `VERIFY_LOC = 27278`, `PRODUCT_LOC = 71109`, ratio `0.384` (límite 2).

## H4 — Refactorizar los casos de uso de `pipeline-application`

Foco principal: ejecución durable, composition y despacho.

`MainDurableRun.kt` concentra compilación, identidad, stores, leases y ejecución. `StepDispatchEngine.kt` incorpora preparación, reconciliación, invocación, bloques y limpieza de credenciales.

Propongo extraer verticalmente:

|Slice|Trabajo|
|---|---|
|Run admission|Reunir decisiones de admisión e identidad|
|Run preparation|Separar compilación y preparación del contexto|
|Run execution|Mantener `runCanonical()` como único punto de coordinación|
|Step invocation|Separar preparación, reconciliación y despacho|
|Plugin admission|Conservar un único propietario de confianza y composición|

`CanonicalRunRequest`, `CanonicalRunState` y `CanonicalRunPolicy` ya son buenos límites. No deben sustituirse por un gran `ApplicationContext` con referencias a todos los servicios.

Reducir la dependencia de `CanonicalRuntimeCapabilityAccess` sobre capacidades no relacionadas; agrupar únicamente las que pertenezcan al mismo ciclo de vida.

Entrega verificable: ambas modalidades de ejecución existentes utilizan los mismos contratos canónicos, sin duplicar el camino de ejecución durable ni alterar el comportamiento del registro de plugins.

#### El corte: el rechazo de los dos mundos es el mismo

**Qué hace.** El mundo durable reducía el análisis de elegibilidad canónica a un booleano y tiraba las razones. La puerta era `supportsCanonicalDurableExecution`, y el mundo durable la usaba como puerta: `if (soporta(...)) { ejecutar } else { cabecera; salir }`. El mundo volátil hacía el MISMO análisis —`analyzeCanonicalDurableExecution`— y conservaba la lista, así que imprimía una línea por paso infractor. El resultado era que el mismo script, en las dos modalidades, recibía el mismo rechazo con distinta información: con `--db` el operador leía «script uses non-canonical plugins; canonical bridge requires…» y se quedaba sin saber QUÉ paso tenía que cambiar.

Medido sobre dos distribuciones construidas de este repositorio —`4d6aa4155` (antes) y `61e105f03` (después)— con el MISMO script y las dos modalidades:

|celda|antes|después|
|---|---|---|
|volátil (sin `--db`)|cabecera **+** «  - Step 'never-runs/registrystep-0' (custom.notCanonical): opaque step plugin not in canonical core step IDs»|igual|
|durable (`--db`)|cabecera **Y NADA MÁS**|cabecera **+** la misma línea del paso|

Las cuatro mediciones salen con código 2 antes y después: el corte no cambia el VEREDICTO, cambia lo que el operador puede leer con él.

**El corte.** El rechazo se dice UNA vez, en `MainRunCommon.refuseNonCanonicalExecution`: la cabecera y la línea por paso salen del mismo sitio, y las dos modalidades llaman a la misma función. El mundo durable conserva el análisis entero en vez de su proyección booleana, y la proyección se borra: `supportsCanonicalDurableExecution` no tiene más llamantes. No se borra por gusto —es justo la que hacía PERDER las razones: una decisión que hay que explicar se conserva entera o no se conserva.

**La garantía, la capacidad y el instrumento.** La garantía es que una ejecución rechazada dice en las dos modalidades lo mismo, y dice QUÉ paso hay que cambiar. La capacidad que sostiene es que el operador, con `--db` o sin él, puede localizar el paso infractor sin adivinarlo y sin re-ejecutar el pipeline con otros scripts. El instrumento es un escenario de `examples/negative/`, NO un test unitario: la garantía es sobre lo que el operador LEE en dos invocaciones del CLI, y un test unitario no puede observar dos invocaciones —AGENTS.md §2 prohíbe CLI, subproceso y persistencia en el sistema de ficheros dentro de un test unitario—. El launcher ejecuta el escenario en las DOS modalidades y sujeta ambas a las mismas expectativas, así que un rechazo que nombra el paso en una y no en la otra falla ahí. Es el instrumento más barato que puede fallar: un solo fichero de DSL, sin harness ni driver.

**Visto en rojo antes del corte.** El escenario se ejecutó contra la distribución sin el corte y falló por la razón correcta:

```
FAIL negative/non-canonical-step-refusal.pipeline.kts     exit 2 as expected, but stderr lacked: custom.notCanonical
```

Una mutación focal, aplicada, leída de vuelta con `git diff` y revertida desde una COPIA —nunca desde git—: el mundo durable vuelve a imprimir sólo la cabecera.

|Mutación|Qué se encendió|
|---|---|
|El mundo durable tira las razones (`refuseNonCanonicalExecution(nonCanonicalSteps)` → cabecera y salida)|exactamente la ejecución DURABLE: su stderr deja de contener «custom.notCanonical»; la VOLÁTIL siguió en verde, porque la mutación no la tocaba|

Eso es lo que distingue un instrumento que discrimina de uno que se rompe: la fila que se enciende es la del mundo que la mutación alcanzó, y la otra siguió en verde.

**Lo que este corte NO arregla, y se dice.** No arregla la SEGUNDA divergencia entre las dos modalidades, que es de otro género y sigue viva: el mundo volátil tiene una rama explícita para «compiló pero no produjo `PipelineSpec`» (`FailureKind.SCHEMA`) y el mundo durable cae en `else -> compilation.failure`, que no distingue ese caso. No se han medido las dos respuestas de ese caso, así que se declara como residuo y no como defecto medido. No arregla la redacción de la cabecera, que sigue siendo la misma de antes. No arregla el hecho de que el análisis de elegibilidad sólo mire `StageBody.Steps` y no baje a los cuerpos anidados: se comprobó que es INACCESIBLE desde el DSL —`BranchScope` no expone `registryStep`—, así que no es un defecto sino un límite del modelo. Y no arregla la FORMA del hito, que es más grande que este corte: H4 sigue pidiendo extraer verticalmente la admisión, la preparación y el despacho, y este corte no extrae nada —quita una proyección que perdía información y pone un rechazo en un solo sitio—.

**Medido sobre `61e105f03544590a1067ac2634a6b265634644d7` con `scripts/verify-all.sh` sin filtros:**

```
PASS  sha=61e105f03544590a1067ac2634a6b265634644d7  elapsed=180s  budget=900s  tests=rewritten:1549 read:0  scenarios=observed:64 executed:64
```

- 1549 tests EJECUTADOS de verdad —205 informes JUnit reescritos por esta ejecución—, 0 fallos, 0 errores, 0 omitidos.
- 64 de 64 escenarios E2E ejecutados y en verde: los 63 de antes y el nuevo.
- 180 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28891`, `PRODUCT_LOC = 71291`, ratio `0.405` (límite 2).

## H5 — Separar CLI, consultas y proyecciones

Foco: `CliParser.kt`, `MainConsoleCli.kt`, Observe, Explain, Cost y Telemetry.

El parser actual, de unas 1.931 líneas, debería delegar en parsers por familia de comando. La interpretación de opciones debe terminar antes de comenzar cualquier efecto.

`MainConsoleCli.kt` debe separar selección, lectura, seguimiento y presentación, reutilizando los lectores existentes.

Las proyecciones Explain, Cost y Telemetry deben compartir las reglas de identidad y asociación de pasos, no una única proyección gigantesca.

Se corregirá además la inconsistencia de `CLI_VERBS` para que la ayuda refleje los comandos ya implementados, incluido `runs recover`.

Entrega verificable: mismas invocaciones válidas, mismas respuestas públicas y mismo comportamiento de lectura, sin lógica de consulta duplicada en los controladores CLI.

#### El corte: cada verbo de `runs` es dueño de sus opciones

**Qué hace.** `runs cancel` aceptaba `--dry-run` y lo ignoraba: la cancelación era REAL y contestaba 0. `runs recover` aceptaba `--reason` y lo ignoraba: el texto se perdía y contestaba 0 también. Los dos fallos eran silenciosos, y cada uno era lo contrario de lo pedido —un operador que pedía una simulación recibía un cierre, y un operador que daba una razón recibía un run sin rastro de ella—.

**El corte.** El parser de `runs` pasa a tener DOS verbos con opciones PROPIAS, y cada verbo RECHAZA la opción del otro con un mensaje que la nombra (`--dry-run belongs to 'runs recover'…`, `--reason belongs to 'runs cancel'…`). El rechazo usa el código 2, que el CLI ya reserva para un rechazo, y `--reason` sin valor o una opción desconocida también se rechazan. La ayuda de `runs` dice ahora QUÉ significa cada código, verbo por verbo: `recover` con 0 significa «la recuperación se decidió» —recuperada, simulada, ya recuperada, o en vuelo—, no «el run se cerró»; y dice qué opciones son de cada verbo.

**La garantía, la capacidad y el instrumento.** La garantía es que un verbo de `runs` nunca hace lo que no se le pidió. La capacidad que sostiene es que un operador que escribe una opción que no pertenece recibe un rechazo que la nombra, en vez de un 0 sobre un run en el estado que acababa de intentar evitar. El instrumento es un test unitario (`MainRunsCliTest`) sobre el parser: la decisión es una función pura del verbo y de los argumentos —sin disco, sin reloj, sin proceso—, así que el instrumento más barato que puede fallar es una llamada al parser y la lectura de su veredicto. Un test de CLI que ejecutara una cancelación real necesitaría un almacén durable, un run que cancelar y una forma de observar lo escrito, y deciría QUE el verbo cerró un run, nunca cuál de las dos reglas se rompió.

**Visto en rojo antes del corte.** Cuatro mutaciones discriminantes, cada una aplicada, leída de vuelta y revertida —dos a nivel de unidad y dos sobre el binario construido:

|Mutación|Fila que se puso roja|Filas del lote|
|---|---|---|
|`runs cancel` vuelve a aceptar `--dry-run`|`MainRunsCliTest > cancel refuses a dry run instead of closing the run for real()`|8 tests, 1 rojo, sin colateral|
|`runs recover` vuelve a aceptar `--reason`|`MainRunsCliTest > recover refuses a reason instead of dropping the text()`|8 tests, 1 rojo, sin colateral|
|`runs recover` vuelve a aceptar `--reason` (binario construido)|`durable/40-recover-refuses-a-half-finished-run.pipeline.kts: a recover given a reason exited 0, expected 2 (a refusal)`|escenario rojo|
|`runs cancel` vuelve a aceptar `--dry-run` (binario construido)|`durable/39-cancel-dead-run.pipeline.kts: a cancel asked to simulate exited 0, expected 2 (a refusal)`|escenario rojo|

Las dos filas E2E además comprueban que el rechazo NO escribe: el escenario durable compara la huella de los bytes durables del run antes y después de la invocación rechazada, y un rechazo que cambiara un solo byte lo diría.

**Lo que este corte NO arregla, y se dice.** No arregla el resto del CLI: `CliParser.kt` sigue siendo un parser de unas 1.931 líneas y `MainConsoleCli.kt` sigue mezclando selección, lectura y presentación; este corte toca sólo `runs`. No arregla la inconsistencia de `CLI_VERBS` para otros verbos, que sigue pendiente en este mismo hito. Y no arregla que `runs cancel` cierre el run y selle las streams sin que exista un modo de simulación para EL: la respuesta es el rechazo, no un `--dry-run` para el verbo que no lo tiene.

**Medido sobre `2e3a1efd5` con `scripts/verify-all.sh` sin filtros —la misma ejecución midió los tres cortes de esta tanda, que salen del mismo árbol—:**

```
PASS  sha=2e3a1efd5faf41c198feec425a62f8c66718c2f1  elapsed=108s  budget=900s  tests=rewritten:1460 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1460 tests, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 594 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 108 s de reloj de pared frente al presupuesto de 900 s — cifra que la build cache contaminó: la medición
  real de este árbol es de 188 s, corregida con su reejecución más abajo (corte del gate).
- `VERIFY_LOC = 27278`, `PRODUCT_LOC = 71109`, ratio `0.384` (límite 2).

#### El corte: la ayuda nombra las formas que el CLI responde

**Qué hace.** La ayuda y el despacho discrepaban CUATRO veces, y siempre hacia el mismo lado: la ayuda faltaba algo que el CLI hacía, o nombraba algo que el CLI rechaza. Medido sobre la distribución construida en `37b10f7f7`, cuya sección de verbos tenía 14 líneas:

- `runs recover` estaba implementado y se alcanzaba —`pipelinek runs recover` responde «runs recover: a run id is required.» y sale con 2— y la ayuda NO lo nombraba.
- `events verify` estaba implementado y se alcanzaba —`pipelinek events verify` imprime su uso y sale con 2— y la ayuda NO lo nombraba.
- La ayuda nombraba `saga` como si fuera un comando, y `pipelinek saga` RECHAZA esa palabra: «pipeline: unknown saga subcommand.» y salida 2. La forma que sí corre, `saga inspect`, aparecía solo dentro del texto del resumen.
- El rechazo de un comando desconocido enumeraba `saga` y `runs cancel` como si fueran palabras de comando, y `saga` es exactamente la palabra que el CLI rechaza.

Las CUATRO son el mismo defecto: una lista escrita a mano de lo que el CLI hace, libre de divergir de lo que el CLI hace. Y nada la vigilaba: en todo el árbol rastreado de `37b10f7f7` no hay ni un test ni un ejemplo que mire la ayuda —solo los tres ficheros de producción que la definían—, así que la lista podía estar mal sin que ninguna fila se pusiera roja. `README.md` la señala como la autoridad de «every CLI flag and exit code»: la deriva no era solo interna.

**El corte.** Una sola tabla, `CLI_VERBS`, y una fila por FORMA —las palabras que el operador escribe—, su resumen y el código que la responde. La clave de una fila es la forma ENTERA: `events` y `events verify` son dos formas distintas, y `saga inspect` es una forma cuya primera palabra es además el nombre de una familia. Cuando dos formas pueden contestar las mismas palabras, gana la MÁS LARGA, porque la corta es la familia de la larga.

Cada fila se DERIVA de la autoridad que despacha su forma, y no hay una segunda lista con la que discrepar:

- las formas de solo lectura son las filas de la tabla, y cada una lleva su handler: la tabla ES el despacho, así que una forma que está en ella se contesta y una que no, no es comando de este CLI.
- las del parser (`validate`, `run`) salen de `CLI_PARSER_COMMANDS`, que el parser ya lee.
- las de la familia `runs` salen de `MainRunsCli.RunsVerb`, que ya posee esas grafías.

Una palabra de familia sola no es un comando: `saga` se rechaza con el uso de la familia, y ese rechazo lo construye la fila que posee la familia, para que la palabra del mensaje sea su propia palabra. Y el rechazo de un comando desconocido enumera las FORMAS de la tabla, no palabras sueltas.

**Medido antes y después, con la única variable cambiada** (las dos distribuciones construidas con el mismo `installDist`: la de `37b10f7f7` en un worktree desechado, y la de este corte):

|celda|antes|después|
|---|---|---|
|`--help`, líneas de la sección de verbos|14|16|
|`--help`, `runs recover`|no aparece|`runs recover  reconcile a run whose work finished but whose terminal was never recorded (--db, --control-root, --dry-run)`|
|`--help`, `events verify`|no aparece|`events verify check a run's events against a contract (--db, --run, --contract)`|
|`--help`, `saga`|`saga          read what a run's saga still owes (saga inspect <runId> --db <path>)`|`saga inspect  read what a run's saga still owes (--db, --format)`|
|el rechazo de `pipelinek bogus`|«expected one of: validate, run, observe, console, operations, events, inspect, saga, runs cancel, credentials, doctor, version, capabilities, sdk-info»|«expected one of: validate, run, observe, console, operations, events, events verify, inspect, saga inspect, credentials, doctor, version, capabilities, sdk-info, runs cancel, runs recover»|
|`pipelinek saga`|«pipeline: unknown saga subcommand.» + el uso de `saga inspect`, salida 2|igual, byte a byte|
|`pipelinek runs bogus`|«runs: the verbs are 'cancel' and 'recover.'», salida 2|igual, byte a byte|
|`pipelinek events bogus`|el uso de `events`, salida 2|igual, byte a byte|
|`pipelinek runs recover`|«runs recover: a run id is required.», salida 2|igual, byte a byte|
|`pipelinek events verify`|el uso de `events verify`, salida 2|igual, byte a byte|
|`pipelinek version`|`pipeline 0.91.0`, salida 0|igual|
|`pipelinek bogus`, salida|1|1|

Las SEIS filas que repiten un comportamiento —de `saga` a `version`— son el contenido de la garantía: este corte no cambia lo que el CLI hace con una forma, cambia lo que la ayuda DICE de ella, y las seis lo confirman byte a byte.

**Visto en rojo, con la mutación leída de vuelta.** Cuatro mutaciones focales, una cada vez, sobre `CliVerb.kt` y `CliExitMapping.kt`. Cada una se aplicó, se leyó de vuelta con `diff -u` contra una copia byte a byte del fichero tal como estaba antes de mutar —con su sha256 anotado— y se restauró desde ESA copia, nunca desde git. En cada una se leyó el XML de JUnit de ESTA ejecución para ver qué filas se encendieron, no el «BUILD FAILED» del build:

|Mutación|Qué se encendió|
|---|---|
|La familia `runs` vuelve a derivarse sin `Recover` —la ayuda sin `runs recover`|exactamente UNA fila en rojo: «the runs family is named the way the family spells it» («the help does not name the form 'runs recover'»); las otras SEIS siguieron en verde|
|Vuelve la fila fantasma `saga`, sin handler y sin despacho —la ayuda nombrando lo que el CLI rechaza|exactamente UNA fila en rojo: «every form the help names is a form the CLI dispatches» («the help names 'saga' and nothing dispatches it»); las otras SEIS siguieron en verde|
|La enumeración del rechazo vuelve a la primera palabra de cada forma|exactamente UNA fila en rojo: «the rejection hint enumerates the forms of the inventory»; las otras SEIS siguieron en verde|
|La resolución de la forma pasa de la más larga a la primera que casa|exactamente UNA fila en rojo: «the longest form answers the words it owns» («the words of 'events verify' are answered by another form»); las otras SEIS siguieron en verde|

Ninguna mutación encendió de más: en las cuatro, SEIS filas siguieron en verde. Eso es lo que distingue un instrumento que discrimina de uno que se rompe.

La primera mutación es la que importa por lo que NO encendió: si la garantía se hubiera escrito como «la ayuda nombra lo mismo que nombra la tabla», esa mutación habría dejado la fila verde —la tabla y la ayuda se habrían apagado juntas— y el defecto habría vuelto sin que nada se encendiera. Por eso el instrumento compara la ayuda contra las DOS autoridades que están FUERA de la tabla, una por una, y no contra la tabla.

**La garantía, la capacidad y el instrumento.** La garantía es que la ayuda nombra las formas que el CLI responde y solo esas, y que el rechazo de un comando desconocido enumera las mismas. La capacidad que la sostiene es que un operador que lee `--help` —o que escribe mal un comando y lee el rechazo— aprende las palabras que el CLI responde. El instrumento es un test unitario que renderiza la ayuda en un búfer y la lee contra la tabla y contra esas dos autoridades, porque no puede ser un escenario de `examples/` —ese corre la distribución, no la ayuda— ni nada que lance un proceso.

**Lo que este corte NO arregla, y se dice.** No arregla la otra mitad de la deriva: una forma de solo lectura BORRADA de la tabla se lleva su capacidad con ella, porque la tabla ES el despacho. Ese caso queda cerrado por construcción —no queda una segunda lista con la que discrepar— y no por una comprobación: el instrumento no puede ver una forma que ya no existe en ninguna parte, y la fila «the help names every form of the inventory» no lo vería, porque compara la ayuda contra la misma tabla. Las dos familias con autoridad FUERA de la tabla —el parser y la familia `runs`— sí se comprueban una por una, y por eso las dos formas que faltaban no podían volver. No arregla que `pipelinek bogus` salga con 1 y no con 2: es el comportamiento previo, ajeno a este corte. No arregla que la ayuda no detail las opciones de cada forma más allá de las que van en el resumen: es una deuda de la ayuda, no de esta lista. Y no arregla el hecho de que las formas de la familia `runs` aparezcan al final de la lista y no agrupadas: el orden no decide nada —una forma se reconoce por sus palabras y gana la más larga—, pero el orden sigue siendo el de quien escribió la tabla.

Medido sobre `37a99f14c` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=37a99f14cbbb7d2c6679a0747556de6a8372ea56  elapsed=191s  budget=900s  tests=rewritten:1472 read:0  scenarios=observed:63 executed:63
```

- 197 informes JUnit reescritos por esta ejecución, 1472 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 191 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27764`, `PRODUCT_LOC = 71322`, ratio `0.389` (límite 2).

#### El corte: un verbo rechaza la opción que no posee

**Qué hace.** Cinco verbos contestaban una petición que llevaba una opción que NO era suya sin rechazarla: la opción se perdía en silencio y el verbo contestaba 0 con su respuesta de siempre. Medido sobre la distribución construida de `f3e079ba7` (siete invocaciones, la misma tabla de antes y después):

|celda|antes|después|
|---|---|---|
|`version --db /tmp/x`|salida 0, 15 B por stdout: la versión|salida 2, stdout vacío, stderr «version: unknown option: --db»|
|`doctor --json`|salida 0, 186 B por stdout: el informe|salida 2, stdout vacío, stderr «doctor: unknown option: --json»|
|`capabilities --view full`|salida 0, 1448 B por stdout: las capacidades|salida 2, stdout vacío, stderr «capabilities: unknown option: --view»|
|`sdk-info --db /tmp/x`|salida 0, 760 B por stdout: el manifiesto del SDK|salida 2, stdout vacío, stderr «sdk-info: unknown option: --db»|
|`operations run-1 --control-root /tmp/x --bogus-flag`|salida 0, stdout vacío: un LISTADO VACÍO publicado|salida 2, stdout vacío, stderr «operations: unknown option: --bogus-flag»|
|`operations --control-root --format json`|salida 0, stdout vacío: `--format` tomado como VALOR de `--control-root`|salida 2, stdout vacío, stderr «operations: value for option --control-root looks like another option: --format»|
|`operations run-1 --bogus-flag`|salida 2, pero por otro motivo: «--control-root is required»; la opción se había perdido|salida 2, stderr «operations: unknown option: --bogus-flag» + el uso|

Las DOS filas que muestran el falso silencio —la quinta y la sexta— son el contenido de la garantía: la quinta publicaba un listado vacío —el operador leía «este run no publicó operaciones», una respuesta FALSA sobre un run que nunca preguntó— y la sexta se comía una opción como si fuera un valor. La séptima dice algo que también es verdad: antes, la salida 2 de esa invocación no venía del rechazo de la opción sino de una comprobación posterior, así que el motivo que llegaba al operador era otro.

**El corte.** Una tabla de admisión, `CliOptionAdmission`, declara qué opciones posee cada verbo y rechaza las demás NOMBRANDO la opción. Los verbos que no poseen ninguna la declaran vacía (`NO_OPTIONS`); los que poseen una declaran su aridad (`VERSION_OPTIONS`, `OPERATIONS_OPTIONS`). Un valor que nombra otra opción de ESTE BUILD se rechaza en vez de consumirse: es la misma regla que el parser compartido ya aplicaba, y ahora se lee de la misma autoridad.

La aridad de los tokens que el parser compartido ya conoce se LEE de él (`knownOptionArity`), no se re-declara: el mismo token no puede valer en un sitio y ser bandera en otro. Los tokens que el parser compartido NO conoce —`--json` es de `version` solamente— los declara esta tabla, y el test unitario compara las DOS tablas token a token por cada token que comparten.

El rechazo sale de `toCliExit()`, la única tabla de errores del CLI, así que el mensaje y el código de salida son los mismos que usa el resto del CLI. Los verbos leen el resultado con un `when` EXHAUSTIVO, y no con `if (admission is CliAdmission.Rejected) return`: la rama negativa de una comprobación de tipo NO estrecha el valor en este compilador, así que después de ese `if` el resultado seguía siendo el tipo sellado y sus datos no estaban al alcance. El `when` nombra las dos formas y, de paso, el compilador comprueba que las dos se atienden.

**La garantía, la capacidad y el instrumento.** La garantía es que un verbo nunca hace lo que no se le pidió: una opción que no posee se rechaza con un motivo que la nombra. La capacidad que la sostiene es que un operador que escribe una opción mal, o que entrega una bandera a un verbo que nunca la aceptó, lee un rechazo en vez de una respuesta que parece verdad. El instrumento es un test unitario (`CliOptionAdmissionTest`, 15 filas): la admisión es una función pura sobre un array de argumentos, así que el instrumento más barato que puede fallar es llamarla y comparar el resultado con una expectativa LITERAL escrita en el test —un valor calculado con la misma tabla que se comprueba concordaría con cualquier mutación de ella—. Y los verbos se ejercitan EN PROCESO: se llaman, se lee su código de salida y se captura su stderr, de modo que la costura entre «al verbo le dieron una opción» y «el verbo la rechazó» se cruza sin lanzar un proceso. Un test unitario no puede lanzar un proceso —AGENTS.md lo prohíbe—, y un escenario de `examples/` pagaría la construcción de la distribución entera para comprobar cinco rechazos que son funciones puras.

Las expectativas son literales y lo único que se lee de producción es el vocabulario bajo prueba. Lo que NO se congela es la REDACCIÓN del rechazo: sólo el token de la opción ofensiva, que el operador tiene que poder leer. Y lo que el instrumento NO puede ver son los verbos con otro vocabulario —`run`, `events`, `console`, `inspect`, `saga inspect`, la familia `runs` y `observe`—, que ya rechazaban una opción desconocida ANTES de que existiera esta admisión.

**Visto en rojo, con la mutación leída de vuelta.** Dos mutaciones focales, una cada vez, sobre `CliOptionAdmission.kt` y `MainVerbs.kt`. Cada una se aplicó, se leyó de vuelta con `diff -u` contra una copia byte a byte del fichero tal como estaba antes de mutar —con su sha256 anotado— y se restauró desde ESA copia, nunca desde git, comprobando después con sha256 que el fichero y la copia eran el mismo byte.

|Mutación|Qué se encendió|
|---|---|
|La regla que rechaza un valor que nombra otra opción —`if (knownOptionArity(value) != null)` → `if (false)`—|exactamente DOS filas en rojo: «a value that names an option of this build is refused» y «operations refuses a value that is another option»; las otras TRECE siguieron en verde|
|El verbo `doctor` vuelve a no admitir —el bloque de admisión sale de `runDoctorVerb`—|exactamente UNA fila en rojo: «doctor refuses an option it does not own» (esperaba 2, obtuvo 0); las otras CATORCE siguieron en verde|

Ninguna mutación encendió de más: en las dos, el resto del lote siguió en verde. Eso es lo que distingue un instrumento que discrimina de uno que se rompe.

**Lo que este corte NO arregla, y se dice.** No arregla la otra mitad de la lista: `observe` sigue renderizando un `CliError` con el `toString()` de su ADT —la única sede de producción que interpola un error—, y por eso `observe` sigue contestando «observe: UnknownOption(value=--bogus)» en vez de una frase. No arregla la PISTA del rechazo que dice «prefix with '=' or quote it»: es un texto PREEXISTENTE en `toCliExit()` —el parser compartido ya lo emitía antes de este corte, y ahora `operations` lo emite por la MISMA tabla—, y es falso en las dos partes —la distribución construida rechaza `--control-root=/tmp/x` con «unknown option» y rechaza `--format=json` con «unknown option», así que la grafía con '=' no existe; y entrecomillar no cambia el token que ve el parser—. Este corte no lo arregla porque es otra familia de defecto —la REDACCIÓN de un rechazo, no su existencia— y el texto vive en la tabla de errores, que este corte no toca. No arregla `events verify`, que es el ÚNICO verbo que todavía se traga una opción que no conoce: su bucle de opciones no tiene rama para lo que no reconoce, así que `events verify --db <db> --run <r> --contract <c> --bogus` da EXACTAMENTE la misma salida que la misma invocación sin `--bogus` —«Error: db not found: …»—, y la opción desconocida no aparece por ninguna parte. Es el mismo defecto que este corte cierra en los otros cinco verbos, y necesita la admisión de esa familia, que es una tabla propia. Y no arregla `CliParser.kt`, que sigue siendo un parser de unas 1.931 líneas que decide la aridad de sus opciones a mano en OCHO sitios —cuatro en el parser compartido, uno por familia (`observation`, `inspect`, `events`, `console`), y cuatro más fuera (`events`, `events verify`, la familia `runs` y `saga inspect`)—: esta tabla es el NOVENO pedazo del mismo género, y unificarlos es deuda que sigue viva.

Medido sobre `d456d29233592d97447f94452acd39ed98635ed7` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=d456d29233592d97447f94452acd39ed98635ed7  elapsed=204s  budget=900s  tests=rewritten:1487 read:0  scenarios=observed:63 executed:63
```

- 198 informes JUnit reescritos por esta ejecución, 1487 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 556 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 204 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27915`, `PRODUCT_LOC = 71400`, ratio `0.391` (límite 2).

#### El corte: un error dice una sola cosa

**Qué hace.** Un `CliError` se renderizaba de DOS maneras, y las dos vivían en el mismo repositorio: `toCliExit()`, la tabla de errores del CLI, y el `toString()` que cada caso de la ADT traía consigo —dieciocho sobrescrituras—. `observe` usaba la segunda, y por eso contestaba con la sintaxis del constructor (`UnknownOption(value=--bogus-flag)`, `InvalidView(bogus): expected one of …`) mientras `run` contestaba con una frase distinta para la misma familia de error. Un error, dos oraciones, y la que parecía un volcado de pila era la que el operador leía.

El corte anterior dejó este mismo defecto escrito como residual: «`observe` sigue renderizando un `CliError` con el `toString()` de su ADT —la única sede de producción que interpola un error—, y por eso `observe` sigue contestando "observe: UnknownOption(value=--bogus)" en vez de una frase». Este corte lo cierra.

Medido sobre la distribución construida de `dc2fd38f3` (diez invocaciones, la misma tabla de antes y después):

|celda|antes|después|
|---|---|---|
|`observe run-1 --view bogus`|«observe: InvalidView(bogus): expected one of normal, events, console, quiet, full»|«observe: invalid view: bogus» + «known views: normal, events, console, quiet, full»|
|`observe run-1 --channel bogus`|«observe: InvalidChannel: --channel 'bogus' is not a channel. Known: stdout, stderr»|«observe: invalid channel: bogus» + «known channels: stdout, stderr»|
|`observe run-1 --limit abc`|«observe: InvalidLimit: --limit needs a positive whole number of records, got 'abc'. Omit it to show everything.»|«observe: invalid --limit value: abc» + «omit it to show everything»|
|`observe run-1 --format bogus`|«observe: InvalidFormat(bogus): expected one of text, jsonl, json»|«observe: invalid format: bogus» + «known formats: text, jsonl, json»|
|`observe run-1 --bogus-flag`|«observe: UnknownOption(value=--bogus-flag)»|«observe: unknown option: --bogus-flag»|
|`run --view bogus`|«Invalid CLI arguments: invalid view: bogus» —SIN la lista de vistas|«Invalid CLI arguments: invalid view: bogus» + «known views: normal, events, console, quiet, full»|
|`run --channel bogus`|«Invalid CLI arguments: invalid channel: bogus» —SIN la lista de canales|«Invalid CLI arguments: invalid channel: bogus» + «known channels: stdout, stderr»|
|`run --limit abc`|«Invalid CLI arguments: invalid --limit value: abc» —SIN el consejo|«Invalid CLI arguments: invalid --limit value: abc» + «omit it to show everything»|
|`operations --control-root --format json`|«operations: value for option --control-root looks like another option: --format» + «if you meant to pass '--format' as a value, prefix with '=' or quote it»|«operations: value for option --control-root looks like another option: --format» + «this build refuses a value spelled like one of its own options; quoting it or writing '--control-root=--format' does not change that, so pass a different value»|
|`operations --control-root=/tmp/x`|«operations: unknown option: --control-root=/tmp/x»|igual|

Las CINCO primeras filas son el contenido de la garantía: el mismo error, dos oraciones, y la que se leía no era una oración. Las TRES siguientes son el efecto del mismo corte por el otro lado —`run` ya usaba `toCliExit()`, y ahora recibe el vocabulario que la tabla no tenía—: el rechazo no cambia de forma, gana la lista de lo que sí se acepta. La NOVENA fila es la PISTA que el corte anterior declaró residual por escrito: «No arregla la PISTA del rechazo que dice "prefix with '=' or quote it" … es un texto PREEXISTENTE en `toCliExit()` … y es falso en las dos partes». La DÉCIMA fila es la que demuestra por qué era falsa: la distribución construida rechaza `--control-root=/tmp/x` con «unknown option» —la grafía con '=' NO existe— y entrecomillar `--format` entrega el mismo token.

**El corte.** `toCliExit()` es la ÚNICA sede donde un `CliError` se convierte en palabras. La parte ACCIONABLE que vivía en los `toString()` —la lista de vistas, de formatos, de canales y de resultados que este build acepta, y el consejo de qué hacer— pasa al `hint` del renderer. Los dieciocho sobrescrituras desaparecen y el tipo sellado queda como un VALOR: dice qué falló y lleva los tokens, no la redacción.

El `hint` nunca promete una forma que el CLI no acepta. La pista de un valor que parece otra opción decía «prefix with '=' or quote it», y ninguna de las dos funciona aquí; ahora dice lo que es verdad: este build rechaza un valor escrito como una de sus propias opciones, y ni entrecomillarlo ni escribir `--op=valor` cambian eso.

`observe` deja de interpolar el error y usa `refusalLines("observe")`, el mismo helper que usan los demás verbos: el mensaje y el consejo salen del MISMO sitio.

**La garantía, la capacidad y el instrumento.** La garantía es que un error se dice de UNA sola manera: quien rechaza dice QUÉ pasó y QUÉ hacer, y hay un solo sitio donde se redacta, así que dos verbos no pueden contestar al mismo error con dos frases distintas. La capacidad que la sostiene es que un operador que escribe una opción mal lee una frase y la lista de lo que sí se acepta, sea cual sea el verbo que usó. El instrumento es un test unitario (`CliErrorRenderingTest`, 12 filas) sobre el renderer, que es una función pura: el instrumento más barato que puede fallar es llamarla y leer lo que dice. La COSTURA —que un verbo renderice su rechazo por el renderer y no por la forma del error— se cruza llamando al verbo EN PROCESO: se llama a `MainObserveCli.main`, se lee su código de salida y se captura su stderr. Un test unitario no puede lanzar un proceso —AGENTS.md lo prohíbe— y un escenario de `examples/` pagaría la construcción de la distribución entera para comprobar un rechazo.

Las expectativas son LITERALES escritas en el test; lo único que se lee de producción es el vocabulario bajo prueba, y se lee como TEXTO: un cambio en ese vocabulario tiene que cambiar una cadena escrita a mano para ser cazado. Dos filas esquivan deliberadamente la vacuidad: la fila de `observe` exige «stdout, stderr» con COMA —la línea de uso ya nombra «stdout|stderr» con barra vertical, y una aserción más floja habría sido satisfecha por el uso—, y la fila de la pista exige que NO aparezcan «prefix with '='» ni «or quote it», que son exactamente las dos frases que la pista vieja prometía.

**Visto en rojo, con la mutación leída de vuelta.** Dos mutaciones focales, una cada vez, sobre `CliExitMapping.kt` y `MainObserveCli.kt`. Cada una se aplicó, se leyó de vuelta con `diff -u` contra una copia byte a byte del fichero tal como estaba antes de mutar —con su sha256 anotado en `SHA256SUMS`— y se restauró desde ESA copia, nunca desde git, comprobando después con `sha256sum -c` que el fichero y la copia eran el mismo byte.

|Mutación|Qué se encendió|
|---|---|
|La pista de la rama `InvalidChannel` desaparece —una línea: `hint = "known channels: …"`—|exactamente DOS filas en rojo: «the refusal of an invalid channel names the channels this build has» y «observe names the channels this build has when a channel is wrong»; las otras DIEZ siguieron en verde|
|`observe` vuelve a interpolar el error —`"observe: ${result.error}"` en vez de `refusalLines("observe")`—|exactamente DOS filas en rojo: «observe refuses an option it does not own with the shared sentence» y «observe names the channels this build has when a channel is wrong»; las otras DIEZ siguieron en verde|

Ninguna mutación encendió de más: en las dos, el resto del lote siguió en verde. Y cada una aprieta UNA mitad de la costura —la una el renderer, la otra el verbo— y deja en verde la mitad que no aprieta: es lo que distingue un instrumento que discrimina de uno que se rompe.

**Lo que este corte NO arregla, y se dice.** No arregla el CÓDIGO DE SALIDA del despachador: un rechazo de primer nivel sale con 1 mientras `CliExit.InvalidArgument.code` es 2 —la taxonomía dice 2 y el despachador dice 1—. Es una inconsistencia real del contrato de salida, y no se toca porque cambiarla rompe un contrato observable que este corte no está autorizado a romper. No arregla los CUATRO verbos que rechazan con una cadena propia y no con un `CliError` —`runs cancel`, `runs recover`, `inspect`, `saga inspect`—: no producen este tipo, así que no tienen un vocabulario común al que unirse; unirlos exige que sus parsers produzcan el tipo, que es otro corte. No arregla `events verify`, que sigue tragándose una opción que no conoce. Y no arrastra la redacción EXPLICATIVA que tenían algunos `toString()` —«which used to be discarded silently», «Refusing rather than storing '$value' as the value of '$option', which would have dropped a flag you asked for and exited 0»—: era historia del defecto, no el camino de salida del operador, y lo que sí era accionable se volvió `hint`.

Medido sobre `143f5aa23cf761cf3fd2691658fcff3f02e31faa` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=143f5aa23cf761cf3fd2691658fcff3f02e31faa  elapsed=207s  budget=900s  tests=rewritten:1499 read:0  scenarios=observed:63 executed:63
```

- 199 informes JUnit reescritos por esta ejecución, 1499 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 207 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28014`, `PRODUCT_LOC = 71353`, ratio `0.393` (límite 2). La producción BAJÓ 47 líneas —los dieciocho `toString()` borrados— y la verificación subió 99 —el test nuevo—.

#### El corte: el verbo del veredicto dice lo que no puede leer

**Qué hace.** `events verify` era el ÚNICO verbo que todavía se tragaba una opción que no conocía, y los dos cortes anteriores lo dejaron escrito como residual. Su bucle de opciones era un `when` escrito a mano SIN rama `else`: lo que no reconocía se perdía en silencio. Y era peor que perderlo — leía dos valores que NO PODÍA leer y los leía como otra cosa: `--from-sequence abc` se leía como la secuencia 0, y `--scope cualquier-cosa` como el alcance WHOLE—. El verbo que emite el veredicto de aceptación estaba emitiendo un veredicto sobre peticiones que nadie había hecho.

Medido sobre la distribución construida de `e884edbd1` —el estado anterior al corte— y la de `32eaff098` —el estado posterior—, con un store real, un contrato real y la misma máquina, seis invocaciones:

|celda|antes|después|
|---|---|---|
|petición limpia: `--db <db> --run <r> --contract <c>`|salida 0, «observed-terminal-outcome: SUCCESS», «acceptance: PASSED»|igual|
|la misma, más `--bogus`|salida 0, «acceptance: PASSED», stderr VACÍO: la opción desaparece sin dejar rastro|salida 2, stdout vacío, stderr «events verify: unknown option: --bogus» + el uso|
|la misma, más `--scope bogus`|salida 0, «acceptance: PASSED»: se verificó el alcance WHOLE|salida 2, stdout vacío, stderr «events verify: --scope must be last-segment or whole.» + el uso|
|la misma, más `--from-sequence abc`|salida 0, «acceptance: PASSED»: se verificó desde la secuencia 0|salida 2, stdout vacío, stderr «events verify: --from-sequence must be a whole number.» + el uso|
|la misma, más un argumento de más (`extra`)|salida 0, «acceptance: PASSED»|salida 2, stdout vacío, stderr «events verify: unexpected argument: extra» + el uso|
|`events verify --db --run <r> --contract <c>`|salida 2 y stderr SÓLO con la línea de uso: `--run` se había comido como VALOR de `--db`, el rechazo no culpaba a nadie y la línea de uso nombraba `[--after-last-run-started]`, una forma que el verbo nunca aceptó|salida 2, stderr «events verify: value for option --db looks like another option: --run» + la pista|

Las CUATRO filas del medio son el contenido de la garantía: el verbo contestaba 0 con «PASSED» sobre peticiones que nadie había hecho, y el veredicto era el de un run que sí había pasado. La SEXTA fila es el otro lado del mismo defecto: el rechazo existía, pero culpaba al argumento sobrante —que no era lo que estaba mal— y su línea de uso prometía una bandera inexistente.

**El corte.** El verbo lee su petición por la admisión compartida —`CliOptionAdmission.admit` con una tabla propia, `EVENTS_VERIFY_OPTIONS`—, que es la MISMA autoridad que ya leen `version`, `doctor`, `capabilities`, `sdk-info` y `operations`. Una opción ajena se rechaza NOMBRÁNDOLA; un argumento que no se le pidió se rechaza; una secuencia que no es un número y un scope que no es uno de los dos se rechazan. Y todo eso ocurre ANTES de tocar el store: una petición que no se puede leer no llega al sistema de archivos.

La regla del valor que nombra una opción se extiende a los tokens del verbo, que son la mitad MÁS FUERTE de esa regla: un token que este verbo posee es todavía menos probable que sea un camino que uno que el parser compartido conoce por casualidad. Antes `--db --run r1` se comía `--run` como valor de `--db` y el rechazo echaba la culpa al argumento sobrante; ahora el rechazo nombra la opción que se comió la otra.

El uso deja de nombrar dos formas que el verbo nunca aceptó: una estaba en el KDoc del verbo —`[--from-cursor TOKEN]`— y la otra en la propia línea de uso —`[--after-last-run-started]`—. Este corte imprime el uso más a menudo —cada rechazo lo imprime—, así que una forma falsa ahí se convierte en el rechazo de una forma documentada.

El verbo queda partido en dos mitades: `main` lee la petición y rechaza lo que no puede leer; `verify` es la única función que abre algo. Sin esa partida el gate de `detekt` encuentra `main` con complejidad 26 sobre un tope de 25, y la respuesta NO es apagar la regla —la política de este repo no apaga ninguna regla para tapar un hallazgo—: es que la lectura y la verificación son dos cosas y estaban en una sola función.

**La garantía, la capacidad y el instrumento.** La garantía es que `events verify` nunca emite un veredicto sobre una petición que nadie le hizo: una petición que el verbo no puede leer se rechaza por su nombre, y el rechazo ocurre antes de que se toque el store. La capacidad que la sostiene es que el veredicto de aceptación de un run SIGNIFIQUE algo: si el verbo acepta cualquier cosa, «PASSED» deja de ser una afirmación sobre el run y pasa a ser una afirmación sobre la invocación, y quien lee el veredicto no puede distinguir las dos.

El instrumento es un test unitario (`MainEventsVerifyCliTest`, 8 filas) más dos filas puras en `CliOptionAdmissionTest`. Los rechazos se deciden antes de cualquier E/S, así que el instrumento más barato que puede fallar es llamar al verbo EN PROCESO, leer su código de salida y capturar su stderr —sin proceso, sin disco, sin reloj—. Un test unitario de este árbol no puede abrir una base de datos —lo dice el propio `EventStoreAdmissionTest`—, y un escenario de `examples/` pagaría la construcción de la distribución entera para comprobar un rechazo.

Las expectativas son LITERALES escritas en el test; lo único que se lee de producción es el vocabulario que el rechazo nombra. Y cada fila lee el RECHAZO —la parte de stderr anterior a la línea de uso—, porque la línea de uso nombra `--scope` y `--from-sequence` también: una fila que leyera todo el stderr quedaría satisfecha por la línea de uso sola, y un verbo que no imprimiera más que su uso la aprobaría. Una fila mira la AUSENCIA de las frases del store —«db not found», «no directory for db»— para probar que una petición rechazada no llegó a tocarlo.

**Visto en rojo, con la mutación leída de vuelta.** Doce mutaciones focales, una cada vez, sobre `CliOptionAdmission.kt` y `MainEventsVerifyCli.kt`. Cada una se aplicó, se leyó de vuelta con `diff -u` contra una copia byte a byte del fichero tal como estaba antes de mutar —con su sha256 anotado— y se restauró desde ESA copia, nunca desde git, comprobando después con `sha256sum -c` que el fichero y la copia eran el mismo byte. Las seis primeras se hicieron sobre el corte tal como se escribió; las otras seis, sobre el código ya partido, porque al partirlo los rechazos cambiaron de sitio y un guard que no se vuelve a ver en rojo no está probado.

|mutación|qué se encendió|
|---|---|
|La regla del valor que nombra una opción pierde la mitad del verbo —`knownOptionArity(value) != null \|\| owned.any { it.token == value }` → `knownOptionArity(value) != null`—|exactamente DOS filas en rojo: «a value that names an option of the verb itself is refused» y «events verify refuses a value that names one of its own options»; las otras 325 del módulo siguieron en verde|
|El scope que no se sabe se lee como WHOLE —`else -> false` en vez de `else -> null`—|exactamente UNA fila: «events verify refuses a scope it cannot read»|
|La secuencia que no se sabe se lee como 0 —`raw.toLongOrNull()` → `0L`—|exactamente UNA fila: «events verify refuses a from-sequence it cannot read»|
|El rechazo del argumento que no se pidió desaparece|exactamente UNA fila: «events verify refuses an argument it was not asked about»|
|Vuelve a la línea de uso una forma que el verbo nunca aceptó —`[--after-last-run-started]`—|exactamente UNA fila: «the usage of events verify names only the forms it accepts»|
|Desaparece el rechazo de «--contract es obligatorio»|exactamente UNA fila: «events verify says which of its options a request is missing»|
|Después de la partida: `readScope` deja de rechazar un scope desconocido —`else -> null` → `else -> false`—|exactamente UNA fila: «events verify refuses a scope it cannot read»; las otras 24 del lote filtrado en verde|
|Después de la partida: `readFromSequence` deja de rechazar una secuencia ilegible —`raw.toLongOrNull()` → `0L`—|exactamente UNA fila: «events verify refuses a from-sequence it cannot read»; las otras 24 en verde|
|Después de la partida: el rechazo del argumento sobrante desaparece —`if (unexpected != null)` → `if (false)`—|exactamente UNA fila: «events verify refuses an argument it was not asked about»; las otras 24 en verde|
|Después de la partida: el rechazo de «--contract es obligatorio» desaparece|exactamente UNA fila: «events verify says which of its options a request is missing»; las otras 24 en verde|
|Después de la partida: la línea de uso deja de imprimirse|exactamente UNA fila: «the usage of events verify names only the forms it accepts»; las otras 24 en verde|
|Después de la partida: el veredicto nunca dice PASSED —`if (report.observedTerminalOutcome?.name == contract.expect.name)` → `if (false)`—|NINGUNA fila en rojo: el camino feliz no tiene fila unitaria —abrir el store es lo que un test unitario de este árbol no puede hacer—. Se ve en la DISTRIBUCIÓN, donde con la mutación el veredicto pasa a «acceptance: FAILED» y el código de salida a 1, y vuelve a «PASSED» al restaurar|

Ninguna mutación encendió de más: en las once primeras, el resto del lote siguió en verde. La duodécima es la que se ve en la distribución, y es la que dice con honestidad qué camino NO tiene instrumento automático.

**Lo que este corte NO arregla, y se dice.** No arregla la RUTA FELIZ de `events verify`: no hay fila unitaria para ella —abrir el store es lo que un test unitario de este árbol no puede hacer— y tampoco hay escenario en `examples/` que la cubra. El camino feliz se comprobó MANUALMENTE sobre la distribución construida, con un store real: «acceptance: PASSED» y salida 0. Es una observación, no una cobertura automatizada, y queda como hueco declarado. No arregla la línea de ayuda de `events verify` —`CliVerb("events verify", "check a run's events against a contract (--db, --run, --contract)")`—, que nombra TRES de las cinco opciones que el verbo acepta: `--from-sequence` y `--scope` no aparecen. No arregla la familia que deja pasar un ARGUMENTO que nadie pidió: `pipeline doctor foo`, `pipeline version foo`, `pipeline capabilities foo` y `pipeline sdk-info foo` siguen contestando 0 con su respuesta de siempre, porque la admisión aparta los POSICIONALES a una lista y cada verbo decide qué hacer con ellos, y esos cuatro no dicen nada. No arregla los vocabularios de opciones que siguen escritos a mano: las banderas de inspección de `events`, la familia `runs` y `saga inspect` tienen su propio bucle, y la tabla `CliOptionAdmission` es UN pedazo más del mismo género.

Medido sobre `32eaff0984e057a710451a3c4597779ee314a071` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=32eaff0984e057a710451a3c4597779ee314a071  elapsed=182s  budget=900s  tests=rewritten:1509 read:0  scenarios=observed:63 executed:63
```

- 200 informes JUnit reescritos por esta ejecución, 1509 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 182 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28161`, `PRODUCT_LOC = 71422`, ratio `0.394` (límite 2).

#### El corte: el verbo que no posee posicional lo dice

**Qué hace.** Cuatro verbos contestaban una petición que llevaba un ARGUMENTO POSICIONAL sin rechazarla: el argumento se perdía en silencio y el verbo contestaba 0 con su respuesta de siempre. Es la misma familia del corte recién cerrado —un verbo debe SAY que una petición no es una suya— y es el residual más pequeño que quedaba abierto.

Medido sobre la distribución construida de `ace673e2f` —el estado anterior al corte— y la de `7f71ccb57` —el estado posterior—, con la misma máquina, ocho invocaciones:

|celda|antes|después|
|---|---|---|
|`version foo`|salida 0; stdout BYTE-IDÉNTICO al de `version` a secas; stderr vacío|salida 2; stdout vacío; stderr «version: unexpected argument: foo» + «this command takes no positional argument»|
|`doctor foo`|salida 0; stdout BYTE-IDÉNTICO al informe de salud de `doctor` a secas; stderr vacío|salida 2; stdout vacío; stderr «doctor: unexpected argument: foo» + la pista|
|`capabilities foo`|salida 0; stdout BYTE-IDÉNTICO al documento JSON de `capabilities` a secas; stderr vacío|salida 2; stdout vacío; stderr «capabilities: unexpected argument: foo» + la pista|
|`sdk-info foo`|salida 0; stdout BYTE-IDÉNTICO al documento JSON de `sdk-info` a secas; stderr vacío|salida 2; stdout vacío; stderr «sdk-info: unexpected argument: foo» + la pista|
|`version`, `capabilities` y `sdk-info` a secas|salida 0 con su respuesta|salida 0 con la MISMA respuesta, byte a byte: el camino feliz no se toca|
|`events verify extra`|salida 2; stderr «events verify: unexpected argument: extra» + el uso, SIN la pista|salida 2; stderr la MISMA primera línea + «this command takes no positional argument» + el uso|
|`operations run-1`|salida 2 por `--control-root`, sin nombrar el run id|salida 2 por `--control-root`, sin nombrar el run id: el verbo POSEE el posicional y lo lee|
|`operations run-1 run-2`|salida 2 por `--control-root`; el posicional DE MÁS se pierde en silencio|igual — RESIDUAL declarado, ver abajo|

Las CUATRO primeras filas son el contenido de la garantía: el verbo contestaba 0 con una respuesta BYTE-IDÉNTICA a la del verbo desnudo, así que el operador no tenía ninguna forma de saber que su argumento no se había leído. La QUINTA fila es la que dice que el camino feliz no se toca —el stdout del verbo desnudo es el mismo antes y después, byte a byte—. La SEXTA es el otro lado del mismo defecto: `events verify` ya rechazaba el argumento sobrante, pero con una comprobación PROPIA; ahora lo rechaza la MISMA autoridad que los otros cinco verbos, con una pista que antes no tenía. Las DOS últimas son el verbo que sí posee un posicional, y no se toca.

**El corte, verbo por verbo.** La admisión compartida declara, para cada verbo, la FORMA de la petición que acepta: las opciones que posee y el argumento posicional que posee.

|verbo|opciones|posicional|qué hace con él|
|---|---|---|---|
|`version`|`--json`|ninguno|lo rechaza|
|`doctor`|ninguna|ninguno|lo rechaza|
|`capabilities`|ninguna|ninguno|lo rechaza|
|`sdk-info`|ninguna|ninguno|lo rechaza|
|`operations`|`--control-root`, `--format`|el run id|lo LEE — y por eso no se toca|
|`events verify`|`--db`, `--run`, `--contract`, `--from-sequence`, `--scope`|ninguno|lo rechaza|

Las dos mitades de la forma viajan JUNTAS en un valor —`CliRequest`—, porque un verbo que declara una mitad en un sitio y la otra en otro declara una de las dos por accidente: con la forma partida, las dos filas de test que leían la tabla por el camino antiguo se rompieron al no repetir la declaración, que es exactamente el defecto que la forma unida evita. Las tablas dejan de llamarse `NO_OPTIONS` y `VERSION_OPTIONS` para llamarse `NO_ARGUMENTS_REQUEST` y `VERSION_REQUEST`: el nombre dice lo que el valor contiene, que es la forma completa de la petición y no sólo sus opciones.

El valor por defecto es la mitad SEGURA: una forma que no dice que posee un posicional no lo posee, y una petición que lleva uno se rechaza NOMBRANDO EL TOKEN. Un verbo que sí lo posee lo dice en su forma: `operations`, que posee el run id, es el único de los seis que lo lee.

El rechazo sale de la ÚNICA tabla de errores del CLI —`toCliExit()`—, así que el mensaje y el código de salida son los mismos que usa el resto del CLI, y la rama nueva es exhaustiva POR CONSTRUCCIÓN: un `CliError` nuevo sin su rama no compila.

La comprobación local de `events verify` desaparece: la admisión decide ya si una petición es una suya, y un segundo sitio que decide lo mismo es un segundo lugar donde puede mentir.

**La garantía, la capacidad y el instrumento.** La garantía es que un verbo que no posee argumento posicional nunca contesta una petición que lo lleva: la rechaza nombrando el token, antes de hacer nada. La capacidad que la sostiene es que el operador que escribe `pipeline doctor foo` —o el que se equivoca de verbo— lee un RECHAZO y no una respuesta: hasta ahora leía un informe de salud, que es un documento que parece una verdad.

El instrumento es un test unitario (`CliOptionAdmissionTest`, 9 filas nuevas). La admisión es una función pura sobre un array de argumentos, así que el instrumento más barato que puede fallar es llamarla y comparar el resultado contra una expectativa LITERAL escrita en el test; y la costura —que el verbo REHÚSE y no conteste— se cruza llamando al verbo EN PROCESO: se llama a `runDoctorVerb`, se lee su código de salida y se capturan su stderr y su stdout, sin proceso, sin disco y sin reloj.

Las expectativas son literales, y lo único que se lee de producción es el vocabulario bajo prueba. Dos filas esquivan la vacuidad a propósito: las de los verbos comprueban que stdout está VACÍO, porque un rechazo que además contesta es la forma PELIGROSA —el operador leería la versión como la respuesta a lo que pidió—; y la de `operations` comprueba que stderr NO menciona el run id, porque el verbo lo posee y lo lee: un rechazo que lo nombrara estaría diciendo lo contrario.

**Qué NO tiene instrumento, y se dice.** `doctor` no tiene fila que ejercite la petición que ES la suya: su sonda de salud escribe un fichero temporal en el directorio de trabajo, y un test unitario de este árbol no toca el sistema de archivos. Los otros tres verbos se ejercitan en las dos direcciones. El camino feliz de `doctor` se comprobó MANUALMENTE sobre la distribución construida: salida 0 y el informe de salud, con stdout byte a byte igual al de antes del corte.

**Visto en rojo, con la mutación leída de vuelta.** Cuatro mutaciones focales, una cada vez, sobre `CliOptionAdmission.kt` y `CliExitMapping.kt`. Cada una se aplicó, se leyó de vuelta —el `grep` que confirma que la mutación ESTABA en el fichero, no un «BUILD FAILED»— y se restauró desde una copia byte a byte del fichero tal como estaba antes de mutar, comprobando después con `sha256sum` que el fichero y la copia eran el mismo byte. El discriminador lee las FILAS del informe JUnit, no la palabra «BUILD FAILED».

|mutación|qué se encendió|
|---|---|
|El rechazo del posicional desaparece de la admisión —las cuatro líneas del `if`|exactamente SEIS filas en rojo: las cuatro de los verbos —«version refuses an argument it was not asked about», «doctor refuses an argument it was not asked about», «capabilities refuses an argument it was not asked about» y «sdk-info refuses an argument it was not asked about»—, la pura «a verb that owns no positional argument refuses one» y la de «events verify refuses an argument it was not asked about»; las otras 28 del lote siguieron en verde|
|Se rechaza TODO posicional, incluso el que el verbo posee —`request.positional == PositionalArgument.NONE` sale de la condición—|exactamente CUATRO filas: «a valued option takes the token after it», «a value that names nothing this build knows is a value», «a verb that owns a positional argument reads it» y «operations still reads the run id it owns»; las otras 30 en verde|
|El token desaparece del mensaje —«unexpected argument: $value» → «unexpected argument»—|exactamente CINCO filas —las cuatro de los verbos y la de `events verify`—; la fila pura NO se enciende, porque aserta el VALOR de la ADT y no su prosa, que es lo que este corte no congela; las otras 29 en verde|
|La declaración `OWNED` desaparece de la forma de `operations`|exactamente CUATRO filas, las mismas que la segunda mutación: las que dependen de que un posicional POSEDIDO se admita; las otras 30 en verde|

Ninguna mutación encendió de más: en las cuatro, el resto del lote siguió en verde. Y las dos últimas se aprietan en lados OPUESTOS de la misma costura —la regla que decide y la declaración que la activa—: una que no se vuelve a ver en rojo no está probada.

**Lo que este corte NO arregla, y se dice.** No arregla el POSICIONAL DE MÁS de `operations`: `operations run-1 run-2` lee `run-1` y se pierde `run-2` en silencio, antes y después del corte. El verbo posee un posicional legítimamente y este corte no lo toca; el caso es DISTINTO —un verbo que posee UNO y traga el segundo— y queda declarado como residual medido. No arregla la línea de ayuda de `events verify` —`CliVerb("events verify", "check a run's events against a contract (--db, --run, --contract)")`—, que nombra TRES de las CINCO opciones que el verbo acepta: `--from-sequence` y `--scope` no aparecen. No arregla los vocabularios de opciones escritos a mano fuera de esta admisión: las banderas de inspección de `events`, la familia `runs` y `saga inspect` tienen su propio bucle, y la tabla `CliOptionAdmission` es UN pedazo más del mismo género.

Medido sobre `7f71ccb57fe1579c67d25f547952bfd2eabfc4dd` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=7f71ccb57fe1579c67d25f547952bfd2eabfc4dd  elapsed=182s  budget=900s  tests=rewritten:1518 read:0  scenarios=observed:63 executed:63
```

- 200 informes JUnit reescritos por esta ejecución, 1518 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 182 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28232`, `PRODUCT_LOC = 71445`, ratio `0.395` (límite 2). La verificación subió 71 líneas —las 9 filas nuevas y su documentación— y la producción subió 23.

#### El corte: el verbo que posee UN posicional lo dice entero

**Qué hace.** `operations <runId> extra-arg` publicaba las operaciones de `<runId>` y no decía NADA de `extra-arg`: el verbo leía el primer posicional y se tragaba el resto en silencio. El operador preguntaba por dos runs y leía una respuesta sobre uno, sin una palabra que dijera que el otro se había ignorado. Es el residual que el corte anterior DECLARÓ y no arregló —«No arregla el POSICIONAL DE MÁS de `operations`»— y es el último hueco que quedaba de esa familia dentro de la admisión.

Medido sobre dos distribuciones construidas de este repositorio —`a10c34c8a` (antes) y `64ec65966` (después)— en la misma máquina, contra el MISMO Output Plane real (un run con un paso `sh` de verdad) y con la misma invocación:

|celda|antes|después|
|---|---|---|
|`operations <rid> --control-root <control>`|salida 0; stdout el id de la operación|salida 0; stdout BYTE-IDÉNTICO: el camino feliz no se toca|
|`operations <rid> extra-arg --control-root <control>`|salida 0; stdout BYTE-IDÉNTICO al de la petición de una sola; stderr vacío; `extra-arg` NO aparece ni en stdout ni en stderr|salida 2; stdout vacío; stderr «operations: unexpected argument: extra-arg» + «this command takes one positional argument» + el uso|
|`operations <rid>` a secas|salida 2 por `--control-root`, sin nombrar el run id|igual, byte a byte: el verbo POSEE el posicional y lo lee|
|`version foo`, `doctor foo`, `capabilities foo` y `sdk-info foo`|salida 2 con su rechazo|salida 2 con el MISMO rechazo, byte a byte en stdout y en stderr: el corte anterior no se toca|

La segunda fila es el contenido de la garantía: el stdout del antes es byte a byte el de la petición de una sola, así que el operador no tenía NINGUNA forma de saber que su segundo argumento no se había leído, y la salida era 0, el mismo código de una respuesta correcta. La tercera fila es el otro lado del mismo defecto y por eso NO se toca: el verbo posee legítimamente ese posicional —el run id— y un rechazo que lo nombrara estaría diciendo lo contrario. La cuarta fila es la que dice que el corte anterior sobrevive: los cuatro verbos que no poseen posicional rechazan igual, con los mismos bytes.

**El corte, en una línea.** La forma de la petición declara CUÁNTOS posicionales posee el verbo, no sólo si posee alguno. `PositionalArgument` pasa de `{NONE, OWNED}` a `{NONE, ONE}`, y cada caso lleva su CAPACIDAD: `NONE(0)` e `ONE(1)`.

El nombre cambia porque la respuesta cambia. `OWNED` decía «posee alguno» y no distinguía «posee uno» de «posee todos»: con un `OWNED` la admisión no tenía forma de decir que el segundo es de más, porque la pregunta que hacía era binaria. `ONE` dice la cantidad, y la admisión hace una pregunta que tiene respuesta: el posicional que ocupa el lugar `capacity` es el de más, y el que lo nombra es el que hay que quitar. El valor por defecto NO cambia: sigue siendo `NONE`, la mitad segura, de modo que un verbo que no dice nada no posee ninguno.

`operations` declara `PositionalArgument.ONE`, que es la verdad medida: es el único de los seis verbos de esta admisión que lee un posicional. El verbo no se toca: sigue leyendo `accepted.positionals.firstOrNull()`, y su camino feliz produce el mismo stdout byte a byte.

El rechazo sale de la ÚNICA tabla de errores del CLI —`toCliExit()`—, con un error NUEVO y distinguible: `CliError.TooManyPositionals`, que no es lo mismo que `UnexpectedPositional`. Allí el verbo no posee ninguno; aquí posee exactamente uno. Un solo error para las dos cosas habría hecho que un rechazo dijera «este comando no toma posicionales» sobre una petición que sí tomaba uno, o que el mismo token fuera «de más» y «no previsto» según el verbo. La `when` sobre `CliError` sigue siendo exhaustiva POR CONSTRUCCIÓN: un error nuevo sin su rama no compila.

**La garantía, la capacidad y el instrumento.** La garantía es que un verbo que posee un posicional rechaza el SEGUNDO nombrándolo, antes de hacer nada: nunca contesta una petición que lleva un argumento de más. La capacidad que la sostiene es que el operador que escribe `pipeline operations <runId> <otro-run>` lee un RECHAZO y no el listado de otro run: hasta ahora leía el listado del primero, que es un documento que parece una verdad.

El instrumento es un test unitario (`CliOptionAdmissionTest`, 2 filas nuevas). La admisión es una función pura sobre un array de argumentos, así que el instrumento más barato que puede fallar es llamarla y comparar el resultado contra una expectativa LITERAL escrita en el test; y la costura —que el verbo RECHÚSE y no conteste— se cruza llamando al verbo EN PROCESO: se llama a `MainOperationsCli.main`, se lee su código de salida y se capturan su stderr y su stdout, sin proceso, sin disco y sin reloj.

La fila del verbo NO lleva `--control-root` a propósito: el rechazo del argumento de más ocurre en la admisión, ANTES de que el verbo mire nada más, y un test unitario de este árbol no toca el sistema de archivos. Esa fila esquiva tres vacuidades a propósito: comprueba que stderr nombra «run-2» —el token de más—, que stderr NO nombra «run-1» —el que el verbo posee, y un rechazo que lo nombrara estaría diciendo lo contrario— y que stdout está VACÍO, porque un rechazo que además contesta es la forma PELIGROSA: el operador leería el listado del primer run como la respuesta a lo que pidió.

**Qué NO tiene instrumento, y se dice.** Este corte no añade fila al camino feliz de `operations` con un plano real: esa lectura toca el sistema de archivos, y un test unitario de este árbol no lo toca. El camino feliz se comprobó MANUALMENTE sobre las dos distribuciones construidas: salida 0 y el mismo stdout byte a byte antes y después.

**Visto en rojo, con la mutación leída de vuelta.** Cuatro mutaciones focales, una cada vez, sobre `CliOptionAdmission.kt` y `CliExitMapping.kt`. Cada una se aplicó, se leyó de vuelta —el `grep` que confirma que la mutación ESTABA en el fichero, no un «BUILD FAILED»— y se restauró desde una copia byte a byte del fichero tal como estaba antes de mutar, comprobando después con `sha256sum` que el fichero y la copia eran el mismo byte. El discriminador lee las FILAS del informe JUnit, no la palabra «BUILD FAILED».

|mutación|qué se encendió|
|---|---|
|La regla del exceso desaparece —`positionals.getOrNull(request.positional.capacity)` → `positionals.getOrNull(positionals.size)`—|exactamente OCHO filas: las cuatro de los verbos que no poseen posicional, las dos puras —«a verb that owns no positional argument refuses one» y «a verb that owns one positional argument refuses a second one»—, la de «operations refuses the second positional argument it does not own» y la de «events verify refuses an argument it was not asked about»; las otras 330 del lote siguieron en verde|
|La capacidad de `ONE` pasa a 2 —el verbo que posee uno pasa a poseer dos—|exactamente DOS filas, las dos nuevas: «a verb that owns one positional argument refuses a second one» y «operations refuses the second positional argument it does not own»; las otras 336 en verde, entre ellas «a verb that owns a positional argument reads it», que sigue admitiendo el que el verbo posee|
|El token desaparece del mensaje —«unexpected argument: $value» → «unexpected argument», en las dos ramas—|exactamente SEIS filas: las cuatro de los verbos, la de `events verify` y la nueva de `operations»; las filas puras NO se encienden, porque asertan el VALOR de la ADT y no su prosa, que es lo que este corte no congela; las otras 332 en verde|
|La declaración `ONE` desaparece de la forma de `operations` —vuelve a no poseer posicional—|exactamente SEIS filas: las que dependen de que `operations` posea UN posicional —«a valued option takes the token after it», «a value that names nothing this build knows is a value», «a verb that owns a positional argument reads it», «operations still reads the run id it owns» y las dos nuevas—; las otras 332 en verde, entre ellas las cuatro de los verbos que no poseen ninguno, que no dependen de esta forma|

Ninguna mutación encendió de más: en las cuatro, el resto del lote siguió en verde. Y las dos últimas se aprietan en lados OPUESTOS de la misma costura —la regla que decide y la declaración que la activa—: una que no se vuelve a ver en rojo no está probada.

**Lo que este corte NO arregla, y se dice.** El MISMO defecto, en OTRA autoridad: el parser compartido. `run <script> extra-arg` y `validate <script> extra-arg` contestan 0, contestan EXACTAMENTE lo mismo que sin el argumento —el `run` termina en «success» y su stdout es byte a byte el de la petición de una sola, módulo el id de run— y no nombran el token ni una vez. El parser compartido rechaza los tokens `--` que van detrás del script (`CliError.TrailingOption`) y no tiene regla para los que NO empiezan por `--`: el bucle de `CliParser.parse` recorre lo que queda y sólo pregunta por los que empiezan por `--`, así que un posicional de más se pierde en silencio. Es la MISMA familia de defecto —un verbo debe SAY que una petición no es la suya— pero OTRA autoridad, y este corte no la toca: queda declarado como residual MEDIDO, con su número. `credentials` NO se ha medido para esta afirmación: el verbo exige una passphrase antes de mirar los argumentos, así que su caso es inconcluyente y se dice.

Tampoco arregla la línea de ayuda de `events verify` —`CliVerb("events verify", "check a run's events against a contract (--db, --run, --contract)")`—, que nombra TRES de las CINCO opciones que el verbo acepta: `--from-sequence` y `--scope` no aparecen. Ni la de `operations`, que no nombra NINGUNA de sus dos opciones (`--control-root` y `--format`). Ni los vocabularios de opciones escritos a mano fuera de esta admisión: las banderas de inspección de `events`, la familia `runs` y `saga inspect` tienen su propio bucle, y la tabla `CliOptionAdmission` es UN pedazo más del mismo género. Ni la FAMILIA de verbos que tienen una frase propia por autoridad: `observe` dice «trailing option without value», `inspect` dice «unexpected positional argument», `runs cancel` dice «unknown option» y `events` dice «unexpected extra argument» — cuatro frases para un mismo hecho, cada una en su familia.

Y una corrección que este ciclo se lleva por delante: el KDoc de `PositionalArgument` afirmaba que «ningún verbo del CLI posee dos posicionales», y la medición lo desmentía —`console` posee dos, el run id y el id de la operación, y los lee por el parser compartido—. La afirmación se acotó a esta admisión, el verbo que la desmiente quedó nombrado en el KDoc, y el mensaje del commit se enmendó para no dejar la frase falsa en la historia del corte.

Medido sobre `64ec65966ea6ea1ef37762e7a9fdc56acaa5bcff` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=64ec65966ea6ea1ef37762e7a9fdc56acaa5bcff  elapsed=198s  budget=900s  tests=rewritten:1520 read:0  scenarios=observed:63 executed:63
```

- 200 informes JUnit reescritos por esta ejecución, 1520 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 198 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28247`, `PRODUCT_LOC = 71455`, ratio `0.395` (límite 2). La verificación subió 15 líneas —las 2 filas nuevas y su documentación— y la producción subió 10.

#### El corte: el parser compartido no se traga el posicional que no posee

**Qué hace.** El parser compartido —la autoridad por la que leen `run` y `validate`— cerraba su gramática SÓLO contra los tokens que empiezan por `--`. Un posicional de más no empieza por `--`, así que no lo miraba: se lo tragaba. Medido sobre la distribución construida de este repositorio, `run s.pipeline.kts extra-arg` EJECUTÓ el pipeline —salida 0, el paso `sh` de verdad imprimiendo `hello`, «Finished: success»— y no dijo NI UNA palabra de `extra-arg`; `validate s.pipeline.kts extra-arg` contestó «VALIDATION SUCCESSFUL» con el stdout y el stderr BYTE-IDÉNTICOS a los de la petición de una sola. Es el residual que el corte anterior DECLARÓ —«El MISMO defecto, en OTRA autoridad: el parser compartido… queda declarado como residual MEDIDO»— y este corte lo cierra.

Medido sobre dos distribuciones construidas de este repositorio —`d77b82d77` (antes) y `f6eee6b7f` (después)— en la misma máquina, con el MISMO script —una etapa `build` con un paso `sh` de verdad— y con la misma invocación:

|celda|antes|después|
|---|---|---|
|`validate s.pipeline.kts`|salida 0; stdout 76 B; stderr 22 B («VALIDATION SUCCESSFUL»)|salida 0; stdout y stderr BYTE-IDÉNTICOS: el camino feliz no se toca|
|`validate s.pipeline.kts extra-arg`|salida 0; stdout 76 B y stderr 22 B, BYTE-IDÉNTICOS a los de la fila anterior; `extra-arg` NO aparece NI en stdout NI en stderr|salida 1; stdout 0 B; stderr «Invalid CLI arguments: unexpected argument: extra-arg» + «this command takes one positional argument» + el uso|
|`run s.pipeline.kts`|salida 0; el pipeline EJECUTÓ —stdout 376 B con la salida del paso, stderr 608 B con el plano de lanzamiento|salida 0; el pipeline EJECUTÓ, con el mismo stdout salvo los ids de correlación|
|`run s.pipeline.kts extra-arg`|salida 0; el pipeline EJECUTÓ IGUAL: stdout 376 B IDÉNTICO módulo los ids, el reloj y el directorio de control; `extra-arg` NO aparece NI en stdout NI en stderr|salida 1; stdout 0 B; stderr el MISMO rechazo que `validate`; NINGUNA señal de ejecución y NINGÚN fichero creado|
|`run s.pipeline.kts --format text`|salida 1; stdout 0 B; stderr «trailing option without value: --format» + «options must appear before the script path, not after it» + el uso|IGUAL, byte a byte en stdout y en stderr: el rechazo anterior sobrevive|

La cuarta fila es el contenido de la garantía y la más grave de las dos: antes, `run s.pipeline.kts extra-arg` PRODUCÍA UN EFECTO —el pipeline corría, el paso `sh` de verdad imprimía `hello`, el run se publicaba— y ese efecto era INDISTINGUIBLE del de la petición de una sola: las dos salidas eran byte a byte iguales módulo los ids, y el token de más no aparecía en ninguna. El operador pedía una cosa y recibía el efecto de otra, sin una palabra que lo dijera. La segunda fila es la misma forma pero SIN efecto: `validate` contestaba «VALIDATION SUCCESSFUL» sobre una petición que nadie había hecho. La quinta fila es la que dice que el corte anterior sobrevive: el rechazo de la opción que va detrás del script es el MISMO, byte a byte.

**El corte, en una línea.** El parser compartido cierra su gramática contra las DOS formas de token que puede recibir: la que empieza por `--` —una opción— y la que no —un posicional—. Un verbo que posee UN posicional —el camino del script— rechaza el SEGUNDO nombrándolo, ANTES de leer el script y antes de cualquier efecto.

El arreglo NO introduce una autoridad nueva: `CliError.TooManyPositionals` ya existía —lo creó el corte anterior, para `operations`— y el renderer que lo pronuncia es el MISMO `toCliExit()`. Lo que cambia es que el parser compartido pasa a usarlo. La aritmética que se declara es la de la admisión: aquí el verbo posee UN posicional, y el token que ocupa el lugar de más es el que hay que quitar.

El añadido es ESTRICTAMENTE ADITIVO: toda petición que ya se rechazaba se rechaza igual —la opción se nombra PRIMERO cuando la petición trae las dos formas, porque el operador que escribió `--format` detrás del script CREÍA en una bandera, y esa creencia es la que hay que corregir antes que ninguna otra—. Lo único que cambia de respuesta es el posicional de más, que antes se perdía y ahora se nombra.

El PUNTO del rechazo es lo que sostiene la garantía: el rechazo ocurre en `CliParser.parse`, que se llama ANTES de que el verbo abra el script, y por tanto antes de leer el fichero, antes de compilar y antes de publicar nada. Medido: `run s.pipeline.kts extra-arg` deja el directorio de trabajo con EXACTAMENTE los ficheros que la invocación había creado —ninguno más—.

**La garantía, la capacidad y el instrumento.** La garantía es que `run` y `validate` rechazan el segundo posicional nombrándolo, ANTES de cualquier efecto: el operador nunca lee una respuesta —ni una validación, ni un run— sobre una petición que no hizo. La capacidad que la sostiene es el vocabulario de lo que una petición de estos dos verbos PUEDE llevar: el camino del script y las opciones que lo preceden, y nada más.

El instrumento es un test unitario nuevo (`CliParserPositionalTest`, 6 filas) sobre el parser puro, más una fila en el test del renderer (`CliErrorRenderingTest`). `CliParser.parse` es una función pura sobre un array: abre nada, lee nada, escribe nada y contesta con un resultado TIPADO. El instrumento más barato que puede fallar es, por tanto, llamarla y comparar el resultado contra una expectativa LITERAL escrita en el test —sin proceso, sin disco, sin reloj—. La costura —que el verbo RECHÚSE y no ejecute— se cruza en la FRONTERA SUPERIOR, que es donde se midió el defecto: sobre la distribución construida.

Las seis filas del test puro se reparten en las particiones que importan: los DOS verbos que leen por aquí (`run` y `validate`), una opción ANTES del script —que no cambia la aridad—, el camino feliz —que sigue admitiéndose—, el rechazo anterior —que sigue siendo el que era— y la petición que trae las dos formas —que nombra la opción primero—. Esta última fila es la que congela el ORDEN de las dos negativas, que es una decisión y no un accidente: sin ella, un cambio que invirtiese el orden pasaría inadvertido.

**Qué NO tiene instrumento, y se dice.** Este corte NO añade un ejemplo `.pipeline.kts` en `examples/`: el rechazo ocurre en el parser, ANTES de que exista un pipeline, así que un escenario end-to-end sólo podría comprobar lo mismo con más machinery —un directorio, una base de datos, un proceso— y no añadiría una garantía que el test unitario no dé ya. El EFECTO de que no hay efecto —que el rechazo no crea ficheros, ni abre el script, ni publica un run— no lo puede ver un test unitario de este árbol, porque eso toca el sistema de archivos: se midió MANUALMENTE sobre las dos distribuciones construidas. Y las PALABRAS exactas que el operador lee no están congeladas por las filas puras —asertan el VALOR de la ADT, no su prosa—: la prosa la fija el renderer, y una fila lo comprueba.

**Visto en rojo, con la mutación leída de vuelta.** Tres mutaciones focales, una cada vez, sobre `CliParser.kt` y `CliExitMapping.kt`. Cada una se aplicó, se leyó de vuelta —el `grep` que confirma que la mutación ESTABA en el fichero, no un «BUILD FAILED»— y se restauró desde una copia byte a byte del fichero tal como estaba antes de mutar, comprobando después con `sha256sum -c` que el fichero y la copia eran el mismo byte. El discriminador lee las FILAS del informe JUnit, no la palabra «BUILD FAILED», y cada mutación se midió sobre el LOTE COMPLETO del módulo —50 clases, 345 tests— para poder afirmar que no encendió de más.

|mutación|qué se encendió|
|---|---|
|La negativa del posicional sobrante DESAPARECE —se borra el bloque `extraPositional` de `CliParser.parse`—|exactamente TRES filas, las tres que dependen de que el parser compartido rehúse el segundo posicional: «run refuses the positional argument it does not own», «validate refuses the positional argument it does not own» y «an option before the script path does not change the arity»; las otras 342 del lote siguieron en verde —`clases=50 tests=345 failures=3 errors=0 skipped=0`—|
|El error equivocado —`CliError.TooManyPositionals(extraPositional)` → `CliError.UnexpectedPositional(extraPositional)`—|exactamente las mismas TRES filas, y por un motivo que la mutación enseña: las dos negaciones dicen cosas DISTINTAS —«de más» frente a «no previsto»—, y una que se confundiera con la otra le diría al operador que el verbo no toma posicionales, que es falso: toma uno, el del script. Las otras 342 en verde —`clases=50 tests=345 failures=3 errors=0 skipped=0`—|
|El consejo equivocado —en `toCliExit()`, «this command takes one positional argument» → «this command takes no positional argument»—|exactamente UNA fila, la del renderer: «the refusal of a second positional says the command takes one»; las otras 344 en verde, entre ellas las 28 de `CliOptionAdmissionTest` —`clases=50 tests=345 failures=1 errors=0 skipped=0`—|

La primera mutación y la segunda aprietan el MISMO lado de la costura —la regla que decide— y por eso encienden las MISMAS tres filas: la fila que dice «no lo rechuses» y la fila que dice «rechúzalo con el error equivocado» son dos maneras de romper lo mismo. La tercera aprieta el lado que las otras dos no tocan —la PROSA del consejo— y se ve en su propia lectura de vuelta: tras la mutación el consejo «no positional argument» aparece DOS veces en el renderer, porque la rama de `TooManyPositionals` ha pasado a decir exactamente lo mismo que la de `UnexpectedPositional`. Un consejo que dice «ningún posicional» sobre un verbo que SÍ posee uno es la forma de mentir por la vía educada.

**Lo que este corte NO arregla, y se dice.** El CÓDIGO DE SALIDA del rechazo del parser compartido es 1, mientras que el de los verbos de la admisión es 2: la misma familia de fallo sale con dos números distintos según la autoridad que lo pronuncia. No se toca aquí —cambiarlo es tocar la tabla de salida del CLI, y ese es otro corte—.

Tampoco se unifica la FRASE: el CLI sigue hablando con seis frases distintas para el mismo hecho. Medidas hoy: el parser compartido dice «unexpected argument: extra-arg» + «this command takes one positional argument»; `observe` dice «trailing option without value: extra» + un consejo que habla de «script path», que es de otro verbo; `inspect` dice «unexpected positional argument 'extra'»; `events` y `console` dicen «Error: unexpected extra argument: extra»; `runs cancel` dice «unknown option 'extra'» y `saga inspect` dice «unknown option: extra». Seis frases para un mismo hecho, una por autoridad, y la tabla `CliOptionAdmission` es un PEDAZO más del mismo género.

Ni la línea de ayuda de `events verify` —que nombra TRES de las CINCO opciones que el verbo acepta: `--from-sequence` y `--scope` no aparecen—, ni la de `operations`, que no nombra NINGUNA de sus dos. Ni los vocabularios de opciones escritos a mano fuera de la admisión. Ni `credentials`, que NO se ha medido para esta afirmación: el verbo exige una passphrase antes de mirar los argumentos, así que su caso es inconcluyente y se dice.

Y una corrección que este ciclo se lleva por delante: el KDoc de `CliError.TooManyPositionals` afirmaba que «los verbos que leen por el parser compartido ya rechazaban un segundo posicional», y la medición lo desmentía —los DOS que leen por ahí, `run` y `validate`, se lo tragaban igual—. La afirmación se corrigió en el mismo corte que la desmentía, y el KDoc nombra ahora a los verbos que SÍ rechazan uno, cada uno con su frase.

Y un hallazgo ajeno al producto, que se declara porque se encontró de paso: pedir `:pipeline-application:test` y `:pipeline-application:installDist` en la MISMA invocación de Gradle falla con un problema de validación de dependencias —«Task ':pipeline-application:test' uses this output of task ':buildExamplePlugin' without declaring an explicit or implicit dependency»—, porque los jars de los plugins de ejemplo están en el classpath del test y su tarea productora no está declarada como entrada. Se midió que NO depende de este corte: la invocación falla IGUAL con la fuente pre-fix puesta, y cada tarea POR SEPARADO tiene «BUILD SUCCESSFUL». La entrada documentada —`scripts/verify-all.sh`— no lo sufre, porque pregunta en una sola invocación por el `:test` de todos los módulos; pero un `./gradlew` a mano choca con ello, y queda dicho.

Medido sobre `f6eee6b7fe93123280ba5c59d0d0416056e80c46` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=f6eee6b7fe93123280ba5c59d0d0416056e80c46  elapsed=195s  budget=900s  tests=rewritten:1527 read:0  scenarios=observed:63 executed:63
```

- 201 informes JUnit reescritos por esta ejecución, 1527 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 195 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28290`, `PRODUCT_LOC = 71459`, ratio `0.396` (límite 2). La verificación subió 43 líneas —las 7 filas nuevas y su documentación— y la producción subió 4.


#### El corte: el rechazo del parser responde con el código que la taxonomía declara

**Qué hace.** El despachador del CLI calculaba el código de salida del rechazo y lo tiraba. `CliParser.parse` devuelve un `CliError`; el renderer `toCliExit()` —la ÚNICA autoridad que traduce un `CliError` a un `CliExit`, con su mensaje y su pista— ya contestaba «qué KINDO de error es este»: `InvalidArgument` (2) para una petición que no se entendió, `RuntimeNotAvailable` (4) para una que esta build no puede servir. `Main.main` imprimía ese mensaje y su pista, y después salía con `System.exit(1)`: el literal, no el código del renderer. Ese 1 es, en la misma taxonomía que el renderer acababa de usar, `PipelineFailed` — «el pipeline se ejecutó y produjo un fallo»—.

No se ejecutó nada. Medido sobre la distribución construida de este repositorio, las peticiones rechazadas por el parser compartido —un posicional de más, un comando inexistente, una opción inexistente, un formato inválido, un perfil de sandbox no soportado— respondían todas 1, y todas con `script_ran: no`: ningún script llegó a ejecutarse y ningún fichero se creó. La familia de la admisión (`observe`, `doctor`, …) ya contestaba 2 por su propia puerta: la misma familia de fallo salía con dos números distintos según la autoridad que lo pronunciara.

Medido sobre dos distribuciones construidas de este repositorio —`b03377bd0` (antes) y `e2d5bc3b5` (después)— en la misma máquina, con el MISMO script de sonda y la misma invocación:

|celda|antes|después|
|---|---|---|
|`validate s.pipeline.kts extra-arg`|salida 1; stderr 1969 B; `script_ran: no`|salida **2**; stderr los MISMOS 1969 B|
|`run s.pipeline.kts extra-arg`|salida 1; stderr 1969 B; `script_ran: no`|salida **2**; stderr los MISMOS 1969 B|
|`nosuchverb`|salida 1; stderr 2135 B; `script_ran: no`|salida **2**; stderr los MISMOS 2135 B|
|`validate --no-such-option s.pipeline.kts`|salida 1; stderr 1928 B; `script_ran: no`|salida **2**; stderr los MISMOS 1928 B|
|`run --sandbox-profile os s.pipeline.kts`|salida 1; stderr 2032 B; `script_ran: no`|salida **4**; stderr los MISMOS 2032 B|
|`validate --format bogus s.pipeline.kts`|salida 1; stderr 1950 B; `script_ran: no`|salida **2**; stderr los MISMOS 1950 B|
|`observe run-1 --bogus-flag` (admisión)|salida 2; stderr 286 B|salida 2; stderr los MISMOS 286 B, sin cambio|
|`doctor foo` (admisión)|salida 2; stderr 75 B|salida 2; stderr los MISMOS 75 B, sin cambio|
|`--help`|salida 0; stdout 1872 B|salida 0; stdout los MISMOS 1872 B, sin cambio|
|`run --help`|salida 1; stderr 1918 B|salida **2**; stderr los MISMOS 1918 B|
|`validate --help`|salida 1; stderr 1918 B|salida **2**; stderr los MISMOS 1918 B|

La tabla entera tiene una forma: **el mensaje no cambia, ni un byte**. Se comprobó con `cmp` sobre el stderr COMPLETO de dos rechazos —1928 B el de la opción y 2032 B el del perfil—: idénticos byte a byte antes y después. Lo único que cambia es el número, y el número es lo único que lee un consumidor que ramifica sobre el exit code. Un script que lee 1 como «el run falló» y reintenta está reintentando una petición que nunca fue admitida; un supervisor que cuenta 1 como «fallo del pipeline» contabiliza como fallo del producto una petición mal escrita.

**El corte, en una línea.** El despachador sale con el código que el renderer ya declaró, en lugar de con un literal: `System.exit(exit.code)`.

No hay autoridad nueva. `toCliExit()` ya existía y ya era total —`CliError` es un ADT cerrado y el renderer lo cubre entero—. El corte no añade un caso, no añade un renderer y no cambia el mensaje de ni una sola de las once peticiones: lo que hace es dejar de descartar la respuesta. La aritmética es la de la taxonomía ya escrita: 0 éxito, 1 fallo del pipeline, 2 invocación y compilación, 4 runtime o capacidad no disponible.

**La garantía, la capacidad y el instrumento.**
- **Garantía**: una petición rechazada NUNCA responde con el código que significa «el pipeline se ejecutó y falló», y la distinción de la taxonomía —petición no admitida (2) frente a capacidad que esta build no carga (4)— llega viva al proceso.
- **Capacidad observable**: el código de salida del proceso.
- **Instrumento más barato que puede fallar**: el escenario de humo `examples/smoke/11-cli-self-report.pipeline.kts`, que ya pregunta al binario por dentro y ya vive en el conjunto. Una prueba unitaria NO puede servir aquí: `main` llama a `System.exit`, y la política prohíbe lanzar un CLI o un subproceso desde una prueba unitaria.

Dos filas, dos códigos, porque la taxonomía distingue y la distinción tiene que sobrevivir: una opción que nadie entiende es 2, y un perfil de sandbox que esta build no lleva es 4. Un despachador que contestara siempre 2 pasaría la primera y fallaría la segunda; uno que contestara siempre 1 fallaría las dos. La TERCERA fila candidata —«un pipeline que falla de verdad sigue contestando 1»— se RECHAZA: ya hay cobertura equivalente en `examples/negative/*` y `examples/durable/*`, y una garantía que ya tiene cobertura no recibe un segundo instrumento.

**Visto en rojo, con la mutación leída de vuelta.** La mutación es el DEFECTO MISMO: `System.exit(exit.code)` → `System.exit(1)`. Se aplicó con un cambio de una sola línea, comprobado con un `assert` de que el bloque objetivo aparecía exactamente una vez; se leyó de vuelta con `grep` —la 196 pasó a `System.exit(1)` y la 101, la del sobre JSON, quedó intacta— y con `diff`. Con el binario reconstruido sobre esa mutación, el escenario se puso en rojo con «stdout lacked: REFUSAL_ANSWERS_INVALID_ARGUMENT», y el censo de los marcadores sobre la salida del escenario cuenta lo que sigue: los ocho marcadores previos, UNO cada uno; `REFUSAL_ANSWERS_INVALID_ARGUMENT`, CERO; `MISSING_CAPABILITY_ANSWERS_RUNTIME_NOT_AVAILABLE`, CERO; `OPERATIONS_ADDRESS_THEIR_OWN_BYTES`, UNO; `FOLDED_IDENTITY_GETS_NO_NEIGHBOUR_BYTES`, UNO. Y los dos tokens de fallo presentes, con el texto «the product answered 1 for an option it does not understand» y «the product answered 1 for a capability this build does not carry». Es decir: exactamente las DOS filas esperadas en rojo, las otras diez en verde, sin colateral. El fichero se restauró después desde una copia verificada por `sha256sum` —nunca con `git checkout --`, que destruye una reescritura sin commitear—.

**Lo que este corte NO arregla, y se dice.**
- El sobre JSON `pipelinek.cli/1` se emite en UN solo camino de rechazo —`negotiateOutputProtocolOrExit`—, y NUNCA en el del parser compartido, aunque el KDoc de la taxonomía promete que se escribe en stderr antes de que el proceso termine. Una máquina que lee el sobre recibe la forma estructurada en un rechazo y no en el otro.
- `pipelinek run --help` y `pipelinek validate --help` se rechazan («unknown option: --help»), y el README, en su tabla de «Every CLI flag and exit code», promete `pipelinek run --help` y «the flag table in `Main.kt`». Las dos mitades de esa promesa son falsas hoy: `run --help` no imprime nada, y en `Main.kt` no hay tabla de banderas.
- El KDoc de `CliExit.RuntimeNotAvailable` nombra «control root, db, or journal» y nada más, así que el perfil de sandbox que esta build no carga viaja en un nombre que no lo menciona.
- Cuatro verbos que rechazan con una frase propia —`runs cancel`, `runs recover`, `inspect`, `saga inspect`— siguen sin producir un `CliError`: su 2 viene de su propia puerta y no de la taxonomía, así que la unificación de la que este corte habla todavía no alcanza a todos.
- `credentials` NO se ha medido para esta afirmación: el verbo exige una passphrase antes de mirar los argumentos, así que su caso es inconcluyente y se dice.
- Sigue en pie el residual ya declarado y no tocado aquí: el CLI habla con seis frases distintas para el mismo hecho, la línea de ayuda de `events verify` nombra tres de las cinco opciones que el verbo acepta, y la de `operations` no nombra ninguna de las dos.

Medido sobre `e2d5bc3b53cff58844101531509009aa2ad816e5` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=e2d5bc3b53cff58844101531509009aa2ad816e5  elapsed=189s  budget=900s  tests=rewritten:1527 read:0  scenarios=observed:63 executed:63
```

- 1527 tests EJECUTADOS de verdad por esta ejecución, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 189 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28318`, `PRODUCT_LOC = 71459`, ratio `0.396` (límite 2). La verificación subió 28 líneas —las 2 filas nuevas y su documentación— y la producción NO subió: el cambio es una línea de código por otra, y el resto es comentario, que el censo no cuenta.

#### El corte: los cuatro verbos que leen un run rechazan con el error de la taxonomia

**Qué hace.** `runs cancel`, `runs recover`, `inspect` y `saga inspect` rechazaban una petición con una frase escrita a mano y con un `2` literal. Cada uno tenía su propio parser; cada parser tenía su propia manera de decir «no te he entendido»; y ninguno pasaba por la tabla de errores del CLI. La misma falta recibía tres formas distintas según el verbo que la recibía.

No es una frase: son cuatro autoridades sobre la misma taxonomía. `CliError` es un ADT cerrado y `toCliExit()` es su ÚNICA traducción a `CliExit` —mensaje, pista y código—, y la tabla distingue `1` («el pipeline se ejecutó y falló») de `2` («petición no admitida»). Cuatro verbos que contestaban `2` por su cuenta contestaban bien por casualidad: el día que la taxonomía mueva el `2`, esos cuatro se quedan atrás, y un consumidor que ramifica sobre el exit code lee un rechazo como otra cosa.

El corte les da a los cuatro un `CliError` de verdad y deja que su código salga de la tabla. Los tipos que hacían falta para NOMBRAR las faltas que ya rechazaban —argumento que falta, valor que el verbo no puede usar, combinación sin respuesta honesta, opción que pertenece a otro verbo, subcomando que no existe— entran en el ADT como cinco casos más, SIN palabras propias: el ADT sigue sin `toString()` y el renderer sigue siendo el único que habla. Los 23 sitios de rechazo de `parseInspect` dejan de pintar una frase y pasan a nombrar una falta.

Medido sobre dos distribuciones construidas de este repositorio —`e2dbf14cc` (antes) y `47bbb2645` (después)— en la misma máquina, con el MISMO script de sonda y la misma invocación. Lo que se mide es la primera línea de stderr: el mensaje. Detrás vienen la pista y el uso de cada verbo, que son suyos.

|celda|antes|después|
|---|---|---|
|`runs cancel run-1 --bogus-flag`|`runs cancel: unknown option '--bogus-flag'`|`runs cancel: unknown option: --bogus-flag`|
|`runs recover run-1 --bogus-flag`|`runs recover: unknown option '--bogus-flag'`|`runs recover: unknown option: --bogus-flag`|
|`inspect run-1 --bogus-flag`|`inspect: unknown option '--bogus-flag'`|`inspect: unknown option: --bogus-flag`|
|`saga inspect run-1 --bogus-flag`|`saga inspect: unknown option: --bogus-flag`|`saga inspect: unknown option: --bogus-flag`, sin cambio|
|`runs cancel`|`runs cancel: a run id is required.`|`runs cancel: missing required argument: run id`|
|`runs recover`|`runs recover: a run id is required.`|`runs recover: missing required argument: run id`|
|`inspect`|`inspect: a run id is required.`|`inspect: missing required argument: run id`|
|`saga inspect`|`saga inspect: a run id is required.`|`saga inspect: missing required argument: run id`|
|`runs cancel run-1`|`runs cancel: --db <path> is required to read the run's durable state`|`runs cancel: missing required argument: --db <path>`|
|`runs recover run-1`|`runs recover: --db <path> is required to read the run's durable state`|`runs recover: missing required argument: --db <path>`|
|`inspect run-1`|`inspect: --db <path> is required to read the durable event store`|`inspect: missing required argument: --db <path>`|
|`saga inspect run-1`|`saga inspect: --db <path> is required to read the durable promises`|`saga inspect: missing required argument: --db <path>`|
|`runs cancel run-1 --db`|`runs cancel: --db requires a value`|`runs cancel: option --db requires a value`|
|`inspect run-1 --db`|`inspect: --db requires a value`|`inspect: option --db requires a value`|
|`runs`|`runs: the verbs are 'cancel' and 'recover'.`|`runs: missing required argument: subcommand`|
|`runs bogus`|`runs: the verbs are 'cancel' and 'recover'.`|`runs: unknown subcommand: bogus`|
|`saga`|`pipeline: unknown saga subcommand.`|`pipeline: missing required argument: subcommand`|
|`saga bogus`|`pipeline: unknown saga subcommand.`|`pipeline: unknown subcommand: bogus`|

El código de salida fue `2` en las DIECIOCHO celdas de las dos columnas, y stdout fue vacío en las dieciocho. Eso es exactamente lo que no cambia, y es por eso que el defecto era invisible: nadie lo veía porque el número era el correcto. Lo que cambia es la frase, y en la tabla hay tres formas que no son sólo distintas sino equivocadas:

- `runs bogus` contestaba lo MISMO que `runs` a secas —«the verbs are 'cancel' and 'recover'»—, así que quien escribía una palabra que no existe recibía una lista de verbos y NINGUNA noticia de que su palabra estaba mal.
- `saga` a secas contestaba «unknown saga subcommand», una queja sobre un token que NADIE había escrito.
- La misma falta se decía de tres formas según el verbo, y la que parecía un volcado de depuración —`unknown option '--bogus-flag'`— era la que un operador leía.

Ahora los cuatro verbos dicen la MISMA frase para la misma falta, y la frase la escribe el renderer. La puerta de cada familia distingue además las dos faltas que antes se confundían: no escribiste el subcomando, o escribiste uno que no existe.

**La garantía, la capacidad y el instrumento.**
- **Garantía**: la misma falta produce la MISMA frase en los cuatro verbos que leen un run, y una puerta de familia distingue «no escribiste el subcomando» de «escribiste uno que no existe».
- **Capacidad observable**: la primera línea de stderr que cada verbo escribe al rechazar.
- **Instrumento más barato que puede fallar**: una prueba unitaria que llama a los cuatro verbos EN PROCESO y compara sus mensajes ENTRE SÍ. Aquí una prueba unitaria SÍ puede servir, al contrario que en el corte del código de salida: estos verbos devuelven el código en vez de llamar a `System.exit`, así que no hay que lanzar un subproceso ni un CLI, que la política prohíbe. La puerta de la familia `saga` es la excepción: contesta por `System.exit`, y por eso se observa en la puerta (`cliFamilyDoor`), no en el terminal. La de `runs` contesta por su valor de retorno y se observa entera.

El instrumento tiene una fila por falta, y cada fila compara los mensajes de los verbos entre ellos. Una garantía que ya tiene cobertura no recibe un segundo instrumento: no se añadió ninguna fila para `observe` —que ya cruzaba el renderer y ya tenía su fila— ni para el `2` de la puerta, que es la MISMA aritmética que el corte anterior ya midió en `scripts/verify-all.sh`.

**El instrumento tuvo que rehacerse, y se dice.** La primera forma de estas filas comprobaba que el stderr de cada verbo CONTIENERA un fragmento escrito a mano —«missing required argument: run id»—. Una mutación que hacía que `inspect` dijera `missing required argument: run identifier` la SOBREVIVIÓ, porque el fragmento es un PREFIJO del mensaje y `contains` lo acepta. Un fragmento no puede ver una divergencia que está dentro de su propia cola. Las filas se reescribieron para comparar los mensajes de los verbos ENTRE SÍ —que es la garantía misma— y para comprobar el fragmento sobre el mensaje COMPARTIDO. Con esa forma, la misma mutación se pone en rojo y nombra la divergencia.

**Visto en rojo, con la mutación leída de vuelta.** Cuatro mutaciones, de una en una, cada una aplicada con un `assert` de que el bloque objetivo aparecía exactamente una vez, leída de vuelta con `diff` contra una copia verificada por `sha256sum` —nunca con `git checkout --`, que destruye una reescritura sin commitear— y restaurada desde esa copia. El discriminador son las FILAS del XML de JUnit de los informes que escribió esa ejecución, no la palabra «BUILD FAILED»:

|mutación|fila que se puso en rojo|
|---|---|
|`MainSagaInspectCli`: `CliError.UnknownOption(option)` → `CliError.InvalidOptionValue(option, "unknown option")`|`an option nobody wrote is refused the same way by every verb that reads a run`|
|`MainInspectCli`: `MissingRequiredArgument("run id", …)` → `MissingRequiredArgument("run identifier", …)`|`a run nobody named is refused the same way by every verb that reads a run`|
|`CliVerb`: `MissingRequiredArgument("subcommand", …)` → `MissingRequiredArgument("sub-command", …)`|`the saga family door refuses with the shared sentence`|
|`MainRunsCli`: `MissingRequiredArgument("subcommand", …)` → `MissingRequiredArgument("verb", …)`|`the runs family refuses a word that names no verb with the shared sentence`|

Cada una de las cuatro ejecuciones: 354 tests, 1 en rojo, 0 omitidos, y ninguna otra fila en rojo. La segunda mutación es la que sobrevivió a la primera forma del instrumento; contra la forma nueva se pone en rojo.

**Lo que este corte NO arregla, y se dice.**
- `runVersionVerb` y `runDoctorVerb` imprimen la frase compartida y luego responden `return 2`: la FRASE está unificada, el CÓDIGO sigue siendo un literal. Queda declarado, no se toca aquí.
- Los rechazos sobre el MUNDO de estos cuatro verbos —un almacén que no se puede abrir, una cancelación que el propio run rehúsa, una inspección de saga que no puede leer las promesas— siguen trayendo su propio ADT y su propio renderer. Son hechos sobre el mundo, no peticiones que el CLI no entendió, y por eso quedan fuera de la taxonomía a propósito.
- `pipeline-application` no está entre los módulos con `apiCheck`, así que los cinco casos nuevos de `CliError` no cambian ninguna superficie ABI vigilada. Si el módulo entra en esa lista algún día, el volcado hay que regenerarlo.
- Sigue en pie, sin volver a medir en este corte, el residual ya declarado: el CLI habla con seis frases distintas para el mismo hecho —`events` y `console` dicen «Error: unexpected extra argument: extra», `observe` dice «trailing option without value: …» con una pista que habla de «script path», que es de otro verbo—; la línea de ayuda de `events verify` nombra tres de las cinco opciones que el verbo acepta, y la de `operations` no nombra ninguna de las dos. Este corte toca cuatro verbos del residual, no el residual entero.
- `credentials` NO se ha medido para esta afirmación: el verbo exige una passphrase antes de mirar los argumentos, así que su caso es inconcluyente y se dice.

Medido sobre `47bbb2645fe6bb6ffedb24fb756fab43729d8a3b` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=47bbb2645fe6bb6ffedb24fb756fab43729d8a3b  elapsed=185s  budget=900s  tests=rewritten:1536 read:0  scenarios=observed:63 executed:63
```

- 201 informes JUnit reescritos por esta ejecución, 1536 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 185 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28424`, `PRODUCT_LOC = 71566`, ratio `0.397` (límite 2). La verificación subió 106 líneas y la producción 107: el corte mete cinco casos en el ADT y reescribe los 23 sitios de rechazo de `parseInspect` para que nombren una falta en vez de pintar una frase.

#### El corte: un follow filtrado avanza la lectura por encima de los frames descartados

**Qué hace.** `console --frames --follow` entrega los frames que el filtro admite, pero la ventana de lectura se anclaba al ÚLTIMO frame ENTREGADO. Un frame que el filtro descarta se leía y se rechazaba, y como la entrega no lo movía, el siguiente sondeo volvía a pedir la MISMA ventana. Cuando el filtro descartaba todos los frames de la ventana, el presupuesto de sondeos se consumía entero sin entregar nada, y los frames que el filtro SÍ admitía quedaban sin leer más allá.

No es un estilo: es un fallo de progreso. La misma pregunta tenía dos respuestas opuestas según por dónde se mirara. La lectura simple entregaba el frame; el follow con el MISMO filtro entregaba NADA y declaraba agotado el presupuesto.

Medido sobre dos distribuciones construidas de este repositorio —la anterior al corte y `d082ab47e` (después)— en la misma máquina, con el MISMO script de sonda y la misma invocación. La sonda corre un pipeline de dos stages, lee el run entero en jsonl para ver los ordinales de los que están hechas las ventanas, y después hace tres lecturas.

|celda|antes|después|
|---|---|---|
|A: la lectura simple con filtro, sin follow|salida 0; stdout `BETA_MARKER` (12 bytes)|igual|
|B: el follow con filtro desde el principio, `--frame-limit 1 --follow-max-polls 6`|**salida 3**; stdout VACÍO; stderr `console-follow-exhausted: 6 polls, no terminal fact observed; last frame ordinal -1. The run was NOT observed to finish — resume with --after-ordinal -1`|salida 0; stdout `BETA_MARKER` (12 bytes); stderr vacío|
|C: el run entero en jsonl|4 frames: ordinal 0 `ALPHA_MARKER`, 1 `\n`, 2 `BETA_MARKER`, 3 `\n`|igual|
|D: el mismo follow reanudado con `--after-ordinal 1`|salida 0; stdout `BETA_MARKER`|igual|

La fila B es el defecto y la fila D es su CONTROL: reanudado por encima de los frames descartados, el follow sí entregaba. El filtro funcionaba; lo que no avanzaba era la lectura.

El corte separa las DOS posiciones que un follow responde de verdad. La posición de LECTURA avanza al último frame EXAMINADO, admita o no la selección. La posición de ENTREGA sigue siendo el último frame MOSTRADO, y es la que reporta el presupuesto agotado y la que reanuda `--after-ordinal`: una reanudación no debe saltar un frame que el consumidor nunca vio. Las dos posiciones coinciden cuando la selección no descarta nada, y se separan exactamente cuando descarta —que es el único caso en que la diferencia importa—.

El loop se extrajo a `followFrames`, sobre el plano que el llamante entrega, con la espera entre sondeos inyectada. Eso es lo que permite ejercitar el loop real en memoria, sin reloj y sin dormir, y por eso la costura existe para esa fila y no por comodidad. La costura muerta `emitFollowPageForTest` se eliminó: no la referenciaba nadie en el árbol. Su contrato —el stream del consumidor sigue vivo entre dos páginas— queda cubierto por la segunda fila nueva, que escribe dos páginas por el mismo stream y comprueba que el stream sigue aceptando escrituras.

**La garantía, la capacidad y el instrumento.**
- **Garantía**: un follow con filtro entrega los frames que la selección admite, y un frame que la selección descarta nunca fija la ventana de lectura.
- **Capacidad observable**: lo que el verbo escribe en stdout cuando se le pide un follow filtrado, y el ordinal que reporta cuando se agota el presupuesto.
- **Instrumento más barato que puede fallar**: una prueba unitaria que ejercita el LOOP REAL —el que se despacha— sobre un plano en memoria, con la espera entre sondeos inyectada. Aquí una prueba unitaria SÍ puede servir, porque el loop no lanza subprocesos ni escribe en disco: son frames en memoria y un `PrintStream` a un buffer. Un escenario de `examples/` no haría falta: el defecto no está en el contrato de la línea de órdenes, sino en la aritmética de la ventana, y un escenario de subproceso no podría observar la posición del cursor sin escribir un acento en el resultado. La garantía del contrato de vida del stream no recibe un segundo instrumento: la fila que lo cubría ya no está en el árbol, y la nueva la cubre sobre el loop real.

**Visto en rojo, con la mutación leída de vuelta.** Dos mutaciones, de una en una, cada una aplicada con un `assert` de que el bloque objetivo aparecía exactamente una vez, leída de vuelta con `diff` contra una copia verificada por `sha256sum` —nunca con `git checkout --`, que destruye una reescritura sin commitear— y restaurada desde esa copia. El discriminador son las FILAS del XML de JUnit de los informes que escribió esa ejecución, no la palabra «BUILD FAILED»:

|mutación|fila que se puso en rojo|
|---|---|
|La posición de LECTURA vuelve al frame ENTREGADO —`scanOrdinal = page.lastOrdinal` pasa a moverse sólo cuando la selección admite frames—|las DOS filas que guardan el defecto: «a follow whose window opens with dropped frames still delivers the frame the filter admits» y «every admitted frame is delivered once, in order, through the same stream». La tercera sigue verde|
|El presupuesto agotado reporta el ordinal EXAMINADO —`lastOrdinal = deliveredOrdinal` pasa a ser `lastOrdinal = scanOrdinal`—|exactamente la TERCERA fila: «an exhausted poll budget reports the last delivered ordinal and not the last examined one» (`expected: <1> but was: <3>`). Las otras dos siguen verdes|

La primera mutación, sobre el módulo entero: 357 tests, 2 en rojo, ninguno más. La segunda enciende la fila que existe para RECHAZAR un arreglo equivocado —esa fila está verde antes del corte y verde después—: un arreglo que moviera el ordinal reportado al examinado reanudaría por encima de frames que el consumidor nunca vio, y la fila lo dice.

**Lo que este corte NO arregla, y se dice.**
- El verbo `console --follow` no tiene autoridad sobre el plano de ejecución: declara `RunPlane.Unavailable` porque el verbo nunca abre el journal de eventos, así que el fin de la ejecución no se puede observar desde aquí y el follow se apoya sólo en el plano de salida. Sigue declarado, no se toca aquí.
- El presupuesto de sondeos agotado sigue siendo una salida `0` con un ordinal reportado. Este corte no cambia esa semántica: cambia QUÉ ordinal se reporta y qué se entrega antes.
- La espera entre sondeos es un parámetro con valor por defecto, y un llamante que no lo inyecte duerme de verdad. El corte no introduce un reloj inyectado en el resto del verbo.
- No se toca la aritmética de las ventanas del resto de verbos: `MainObserveCli` ya usaba `page.lastOrdinal` —el último EXAMINADO— y por eso este defecto era del verbo de consola y no del lector. El lector no se ha modificado.
- `pipeline-application` no está entre los módulos con `apiCheck`, así que este corte no cambia ninguna superficie ABI vigilada.

Medido sobre `d082ab47e6be9e79d851e2f3a6435d13af11c1ec` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=d082ab47e6be9e79d851e2f3a6435d13af11c1ec  elapsed=189s  budget=900s  tests=rewritten:1539 read:0  scenarios=observed:63 executed:63
```

- 202 informes JUnit reescritos por esta ejecución, 1539 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde; 560 comprobaciones de garantía sobre 114 ejecuciones, 0 que no comprobaran nada.
- 189 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28626`, `PRODUCT_LOC = 71579`, ratio `0.400` (límite 2). La verificación subió 202 líneas y la producción 13: el corte mete tres filas y extrae el loop para poder ejercitarlo.

#### El corte: el detector de divergencia se compone como puerto

**Qué hace.** `CanonicalDurableRunCoordinator` declara su detector de divergencia como PUERTO: el constructor recibe un `DivergenceDetector` —la interfaz— y su colaborador, `DurableInvocationResolver`, también lo declara. Entre los dos había un estrechamiento en el cuerpo del coordinador: antes de entregar el detector al resolutor, lo convertía a `StrictFingerprintDivergenceDetector`.

```kotlin
// antes
divergenceDetector = divergenceDetector as StrictFingerprintDivergenceDetector,
// después
divergenceDetector = divergenceDetector,
```

Un estrechamiento no puede fallar con la interfaz: falla con la clase. Cualquier detector que no fuera el estricto moría con `ClassCastException` en la CONSTRUCCIÓN, antes de que el run hubiera dicho una palabra. El llamante no podía elegir otra política de divergencia y ni siquiera podía enterarse de que la elección se le había rechazado: el run no empezaba, y no había ningún sitio donde mirar para saber por qué.

No es una lectura: es un hecho medido en el árbol. `DivergenceDetector` se estrecha en UN solo sitio de todo el repositorio, y `DurableInvocationResolver` sólo usa `check(...)`. El cast no cumplía ninguna función: no convertía nada que alguien leyera.

|celda|antes|después|
|---|---|---|
|La composición del coordinador con un detector que NO es el estricto|ROJA: `java.lang.ClassCastException` en `DivergenceDetectorPortTest.kt:114` — la construcción revienta antes de que el run empezara|VERDE: la composición termina|
|La fila que dice que el resolutor consulta el detector que tiene|VERDE|VERDE, y el detector que puede llegar a ser ése pasa a ser cualquiera|

La segunda fila estaba verde antes y después: el resolutor siempre consultó el detector que tenía. Lo que el corte cambia no es esa consulta, sino QUÉ detector puede llegar a tener.

**La garantía, la capacidad y el instrumento.**
- **Garantía**: el detector de divergencia es un puerto —una propiedad que no puede romperse— y la composición lo conserva: quien compone el coordinador entrega una política de divergencia, y ésa es la que el run aplica.
- **Capacidad observable**: la política de divergencia que un run durable consulta cuando vuelve a invocar una operación cuyo rastro no coincide con el journal. Es la capacidad que decide si un reuso se reconcilia, se repite o se niega.
- **Instrumento más barato que puede fallar**: una prueba unitaria que COMPONE el coordinador con un detector que no es el estricto y comprueba que la construcción termina. El defecto estaba en una composición, así que el instrumento es una composición: los colaboradores son los de producción en memoria —el journal y el almacén de cursores ya son implementaciones en memoria, el sumidero de eventos descarta, el reloj está fijo— y la raíz de control es una ruta que NO existe, de modo que cualquier escritura ahí rompería la fila en lugar de pasar inadvertida. Un escenario de `examples/` no añadiría nada: el defecto no está en el contrato de la línea de órdenes sino en la firma de una clase, y desde fuera ningún proceso observable distingue «el run no arrancó» de «el run se negó». La segunda fila —el resolutor consulta el detector que tiene— es verde antes y después del corte: no es un segundo instrumento de la misma garantía, es el OTRO EXTREMO del mismo puerto. Fija qué significa el puerto y rechaza un arreglo que estreche el colaborador en vez del coordinador.

**Visto en rojo, con la mutación leída de vuelta.** Dos mutaciones, de una en una, cada una aplicada con un `assert` de que el bloque objetivo aparecía exactamente una vez, leída de vuelta con `diff` contra una copia verificada por `sha256sum` —nunca con `git checkout --`, que destruye una reescritura sin commitear— y restaurada desde esa copia. El discriminador son las FILAS del XML de JUnit de los informes que escribió esa ejecución, no la palabra «BUILD FAILED»:

|mutación|fila que se puso en rojo|
|---|---|
|Restaura el cast —la interfaz vuelve a estrecharse a `StrictFingerprintDivergenceDetector` al componer el resolutor|exactamente la PRIMERA fila: «the coordinator composes a detector that is not the strict one», con `java.lang.ClassCastException` en `DivergenceDetectorPortTest.kt:114`. La segunda sigue verde, sin colateral|
|Sustituye el cast por un estrechamiento CON RESERVA —`divergenceDetector as? StrictFingerprintDivergenceDetector ?: StrictFingerprintDivergenceDetector()`—|NINGUNA: la fila siguió VERDE|

La segunda mutación es el dato importante de este corte, y se declara en vez de esconderse: **el instrumento ve que la composición ACEPTA cualquier implementación, no que la CONSULTE.** Un estrechamiento con reserva dentro del cuerpo del coordinador —el arreglo plausible que escribiría quien lee deprisa— sobrevive a este instrumento, porque el fallo que ese arreglo introduce no es «no acepta el detector», sino «acepta el detector y luego consulta otro». Sólo un run durable completo distinguiría las dos cosas, y un run durable no es una prueba unitaria: es un escenario de `examples/`, y no se añade ninguno porque el defecto que este corte arregla no se manifiesta por la línea de órdenes.

Y conviene precisar qué cierra la segunda fila y qué no: fija que el RESOLUTOR consulta el detector que tiene, de modo que rechaza un arreglo que estreche el colaborador. Lo que NO puede rechazar es un estrechamiento con reserva en el COORDINADOR. Se dice con esas palabras, sin ensanchar la cobertura.

**Lo que este corte NO arregla, y se dice.**
- El instrumento no observa, A TRAVÉS del coordinador, qué detector consulta un run real. Sólo un run durable completo lo diría, y eso no es una prueba unitaria.
- `CanonicalStructuralDecisions.kt` arrastra imports MUERTOS: la sección anterior de este ROADMAP nombraba dos —`DivergenceDetector` y `StrictFingerprintDivergenceDetector` (líneas 69 y 76)— y afirmaba que `detekt` 2.0.0-alpha.6 no puede verlos. **Era falso, y el corte siguiente lo mide**: la regla `UnusedImport` SÍ los veía; lo que los escondía era la entrada de la instantánea que los nombraba. Y no eran dos: ese archivo solo llevaba 67. Ver «El corte: los imports que no se usan dejan de estar escondidos en la instantánea», más abajo.
- El comportamiento de producción NO cambia. La única composición del coordinador que existe (`CompositionRoot.kt:70`) no pasa detector, así que usa el valor por defecto, que es el estricto. Lo que cambia es que la puerta queda ABIERTA para otra política, como decía la firma desde el principio.
- `ScriptedFrontendRunner.kt:96` pasa `StrictFingerprintDivergenceDetector()` a un parámetro ya tipado con la interfaz: eso no es un estrechamiento y no se toca.
- `pipeline-application` no está entre los módulos con `apiCheck`, así que este corte no cambia ninguna superficie ABI vigilada.

Medido sobre `b46f33ec90adc3e1c315ee4891c7341e5ed1bfa6` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=b46f33ec90adc3e1c315ee4891c7341e5ed1bfa6  elapsed=160s  budget=900s  tests=rewritten:1541 read:0  scenarios=observed:63 executed:63
```

- 203 informes JUnit reescritos por esta ejecución, 1541 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde; 560 comprobaciones de garantía sobre 114 ejecuciones, 0 que no comprobaran nada.
- 160 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28731`, `PRODUCT_LOC = 71579`, ratio `0.401` (límite 2). La verificación subió 105 líneas y la producción 0: el corte mete dos filas y quita un token de una línea.

#### El corte: los imports que no se usan dejan de estar escondidos en la instantánea

**Qué hace.** El análisis estático del árbol reportaba 346 imports que NINGÚN archivo usa, y los 346 estaban en la instantánea de `detekt`. La regla `UnusedImport` no fallaba nunca: el archivo que la contiene la silenciaba. Una instantánea de hallazgos es una lista de excepciones con el nombre de una regla —una entrada por hallazgo aceptado—, así que 380 entradas `UnusedImport` no eran 380 excepciones: eran 380 Findings guardados. De esas, 346 nombraban un hallazgo vivo y 34 nombraban un import que ya no estaba en el archivo.

El corte borra los 346 imports del código y retira las 380 entradas de las 20 instantáneas que las contenían —de las 24 que hay en el árbol; las otras 4 nunca tuvieron una. Con eso la regla queda vigente sobre todo el árbol, incluidos los módulos anidados del SDK, y el archivo que la apagaba deja de apagarla.

```kotlin
// antes — pipeline-application/.../durable/CanonicalStructuralDecisions.kt:20
import java.util.concurrent.atomic.AtomicLong
```

```xml
<!-- antes — pipeline-application/detekt-baseline-main.xml -->
<ID>UnusedImport:CanonicalCoreStepDecoder.kt:import kotlinx.serialization.json.jsonArray</ID>
```

```kotlin
// después — el import no está
```

```xml
<!-- después — ninguna entrada: la regla vuelve a tener dientes -->
```

|celda|antes|después|
|---|---|---|
|Imports declarados y no usados en el árbol|346, repartidos en 113 archivos|0|
|Líneas de `import` borradas del código fuente|—|346, en 113 archivos, 0 añadidas, 0 líneas no-import tocadas|
|Entradas `UnusedImport` en las instantáneas|380, en 20 de 24 archivos|0|
|Entradas que ya no nombraban nada|34: el import se había borrado antes y la entrada se quedó|0, porque la entrada no está|
|La regla `UnusedImport` sobre `pipeline-application`|no falla nunca: cada hallazgo está guardado|falla y nombra el archivo, la línea y la regla|
|`CanonicalStructuralDecisions.kt`|99 líneas de `import`, 67 muertas|32 líneas de `import`|

El archivo con más imports muertos era el que la sección anterior del ROADMAP señalaba por dos: `CanonicalStructuralDecisions.kt` llevaba 67 imports que no usaba, no dos. La sección anterior afirmaba que `detekt` «no puede verlos». La verdad medida es al revés: `detekt` los veía, y la entrada de la instantánea los tapaba.

**La garantía, la capacidad y el instrumento.**
- **Garantía**: un import declara una dependencia; el código la cumple o el import sobra. Un import muerto es una afirmación falsa sobre lo que el archivo toca.
- **Capacidad observable**: el conjunto de dependencias declaradas que el árbol realmente usa, dicho por el análisis estático sobre el árbol entero, módulo por módulo.
- **Instrumento más barato que puede fallar**: el propio `detekt`, que YA está dentro de `check`. No se añade ninguna prueba, ningún script, ninguna línea de LOC de verificación: el instrumento existía y estaba callado. El corte no AÑADE una capacidad, retira una supresión — y por eso esta garantía no recibe un segundo instrumento. Un escenario de `examples/` tampoco podría observarlo: el defecto no se manifiesta por la línea de órdenes, sino en una afirmación del código sobre sí mismo.

**Visto en rojo, con la mutación leída de vuelta.** Dos mutaciones, de una en una, cada una aplicada con un `assert` de que el import objetivo aparecía exactamente una vez, leída de vuelta con `diff` contra una copia verificada por `sha256sum` —nunca con `git checkout --`, que destruye una reescritura sin commitear— y restaurada desde esa copia. El discriminador son los HALLAZGOS que nombra el análisis estático, con su archivo, su línea y su regla:

|mutación|hallazgo que se puso en rojo|
|---|---|
|Reimporta `java.util.concurrent.atomic.AtomicLong` en `CanonicalStructuralDecisions.kt` —módulo de primer nivel|exactamente UNO: `CanonicalStructuralDecisions.kt:20:1 The import 'java.util.concurrent.atomic.AtomicLong' is unused. [UnusedImport]`. Ningún otro hallazgo, ni de otra regla|
|Reimporta el mismo import en `pipeline-step-sdk/runtime/.../ShellWatchdog.kt` —módulo ANIDADO|exactamente UNO: `ShellWatchdog.kt:2:1 The import 'java.util.concurrent.atomic.AtomicLong' is unused. [UnusedImport]`. Ningún otro|

La segunda mutación es la que importa y por eso se cuenta. El SDK vive dos niveles más abajo que `<module>/src/main/kotlin/`, así que un barrido de un solo nivel no lo ve: un corte que sólo hubiera mirado `pipelinek/*/…` habría dejado la regla apagada justo en los seis módulos anidados, y el árbol habría parecido limpio. La mutación en `ShellWatchdog.kt` es la que demuestra que la regla llega hasta allí.

Antes del corte, el mismo análisis con las entradas retiradas de la instantánea reportaba 266 hallazgos en los módulos de primer nivel y 80 en los anidados: 346, que es exactamente el número de líneas borradas. La correspondencia es una-a-una, y por eso el corte no borra «imports que parecían muertos»: borra lo que el instrumento nombraba.

**Lo que este corte NO arregla, y se dice.**
- `detekt` no es un compilador. `UnusedImport` afirma que el archivo no USA el símbolo; no afirma que el símbolo no exista, ni que quitar el import no rompa una resolución que se hacía por otra vía. Se dice porque el corte borra 346 líneas, y eso es una afirmación sobre lo que se USA, no sobre lo que COMPILA.
- Lo que demuestra que la eliminación es segura NO es el hallazgo: es la COMPILACIÓN del árbol entero en verde con los 346 imports fuera, más los 1541 tests ejecutados de verdad. Un hallazgo ausente dice que el archivo no usaba el símbolo; no dice que el símbolo no hiciera falta.
- El corte abre UNA regla en 20 archivos. Las demás reglas de las instantáneas siguen apagadas, y aquí no se dice cuántas quedan ni cuántas deberían quedar.
- Las 4 instantáneas sin ninguna entrada `UnusedImport` —`pipeline-credentials-executor`, `pipeline-runtime-api`, `pipeline-step-sdk/files`, `pipeline-step-sdk/workflow-control`— no se tocaron. No se afirma que tuvieran imports muertos: no los tenían.
- La configuración de `detekt` no se toca: ni `detekt.yml` (138 líneas de sobreescritura) ni la forma de calcular `moduleBaseline` en `build.gradle.kts`. Lo que cambia es qué se le deja decir.
- `pipeline-application` no está entre los módulos con `apiCheck`, y este corte no cambia ninguna superficie ABI vigilada: no toca firmas, ni tipos públicos, ni formatos durables. Los imports no son parte de la superficie publicada; lo que se borra es la declaración de una dependencia que el archivo no cumple.
- La regla se lee en modo «construido sobre la configuración por defecto» (`buildUponDefaultConfig.set(true)`), así que este corte puede sacar a la luz hallazgos que la configuración por defecto de `detekt` tampoco consideraría. Se dice para que nadie lea este corte como un censo completo de imports muertos.

Medido sobre `9b859077af2afece30c9ddf3e9cdb75086857570` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=9b859077af2afece30c9ddf3e9cdb75086857570  elapsed=181s  budget=900s  tests=rewritten:1541 read:0  scenarios=observed:63 executed:63
```

- 203 informes JUnit reescritos por esta ejecución, 1541 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde; 560 comprobaciones de garantía sobre 114 ejecuciones, 0 que no comprobaran nada.
- 181 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28731`, `PRODUCT_LOC = 71233`, ratio `0.403` (límite 2). La verificación no cambió NI UNA línea y la producción bajó 346: el ratio SUBE de `0.401` a `0.403` porque el denominador encogió, no porque el numerador creciera. El corte no añade ni una línea de verificación.

#### El corte: los verbos que leen un run lo leen donde el operador lo escribió

**Qué hace.** Cinco verbos leían el run id sólo desde el primer argumento: `observe`, `inspect`, `runs cancel`, `runs recover` y `saga inspect`. Cuando el operador lo escribía DESPUÉS de una opción, la respuesta era «a run id is required.» o «missing required argument: run id»: una afirmación falsa sobre un argumento que el operador acababa de escribir, y un rechazo que nombraba una falta que no existía.

El caso de `observe` era el más franco, porque su frase ni siquiera pasaba por la taxonomía: la escribía el verbo. Los otros cuatro ya usaban `CliError`, pero la tabla no tenía un caso que dijera «te falta el run id» y por eso el verbo escribía su propia frase.

No es una regla nueva: `events`, `console` y `operations` ya leen el run id en cualquier posición. `CliParser.parseEvents` lo recoge como primer posicional donde aparezca, `CliOptionAdmission` admite la petición de `operations` con `PositionalArgument.ONE`, y `parseConsole` acepta el run id antes o después de las opciones. Este corte alinea los cinco verbos que quedaban con la convención del propio CLI: el parser es el único sitio que sabe qué opciones llevan valor, y por eso es el único que puede distinguir el run id del valor de una opción.

Medido sobre dos distribuciones construidas de este repositorio —`a0e4f027f` (antes) y `9639e7213` (después)— en la misma máquina, con el MISMO script de sonda. Se mide el código de salida y la primera línea de stderr.

|celda|antes|después|
|---|---|---|
|`inspect --db X run-1`|`inspect: missing required argument: run id`|`inspect: missing required argument: --control-root <path>`|
|`inspect run-1 --db X`|`inspect: missing required argument: --control-root <path>`|sin cambio|
|`observe --view console run-1`|`observe: a run id is required.`|`observe: there is no Output Plane to read, because --control-root was not given.`|
|`observe run-1 --view console`|sin cambio|sin cambio|
|`runs cancel --reason w run-1`|`runs cancel: missing required argument: run id`|`runs cancel: missing required argument: --db <path>`|
|`runs cancel run-1 --reason w`|sin cambio|sin cambio|
|`runs recover --dry-run run-1`|`runs recover: missing required argument: run id`|`runs recover: missing required argument: --db <path>`|
|`runs recover run-1 --dry-run`|sin cambio|sin cambio|
|`saga inspect --format json run-1`|`saga inspect: missing required argument: run id`|`saga inspect: missing required argument: --db <path>`|
|`saga inspect run-1 --format json`|sin cambio|sin cambio|
|`inspect --db X run-1 run-2`|`inspect: missing required argument: run id`|`inspect: unexpected argument: run-2`|
|`observe --view console run-1 run-2`|`observe: a run id is required.`|`observe: unexpected argument: run-2`|
|`runs cancel --reason w run-1 run-2`|`runs cancel: missing required argument: run id`|`runs cancel: unexpected argument: run-2`|
|`runs recover --dry-run run-1 run-2`|`runs recover: missing required argument: run id`|`runs recover: unexpected argument: run-2`|
|`saga inspect --format json run-1 run-2`|`saga inspect: missing required argument: run id`|`saga inspect: unexpected argument: run-2`|

Las quince celdas salen con código 2 antes y después. Lo que cambia es la frase: la de «antes» nombra una falta que no existía, la de «después» nombra lo que el verbo pide de verdad. Y el segundo posicional, que antes se tragaba en silencio, ahora se rechaza nombrando el token sobrante: «unexpected argument: run-2», con la pista «this command takes one positional argument».

**El instrumento.** Una fila unitaria por verbo, en proceso, con el patrón que ya usan `CliOptionAdmissionTest` y `MainSagaInspectCliTest`: se da el array de argumentos, se lee el código de salida y se captura `stderr`. Cada fila pregunta lo mismo a un verbo distinto, porque la extracción es un sitio distinto en cada uno: una fila de `inspect` no puede cazar un verbo que siga leyendo `args[0]`. La fila no congela la frase del rechazo —congela el NOMBRE de lo que el verbo pide— y por eso afirma dos ausencias: que la stderr no nombre el run id («run id») y que no nombre su valor («run-1»). La primera caza un verbo que se niega POR el run id; la segunda caza un parser que niega el run id COMO posicional. Y afirma que stdout va vacío, porque un rechazo que además publica una respuesta se lee como la respuesta.

**El E2E no se toca, se reordena.** Cuatro invocaciones existentes de `examples/run.sh` cambian de orden para que el run id vaya después de las opciones —`runs recover`, `runs cancel`, `inspect --state` sobre un run real y `inspect --state` sobre un run que no existe— y así se ve que el verbo USA el id leído. El cambio es de cero líneas: los mismos comandos, la misma tienda, los mismos códigos esperados.

**Mutación.** Con los cinco archivos de producción restaurados a su versión de HEAD, `:pipeline-application:test` dio 365 tests completados y 6 fallos, y el único XML con `<failure>` fue `RunIdPositionTest`: sin colateral. Con esa misma mutación, la distribución construida fue a rojo en `events/05-*` («reading the run state exited 2, expected 0»), en `durable/39-*` («the cancel of the dead run exited 2») y en `durable/40-*` («the recover of the half-finished run exited 2»), mientras el escenario hermano `durable/39-saga-two-same-kind` siguió en verde. La restauración se hizo desde una copia verificada por digest, no desde git.

**Lo que este corte NO arregla.**
- `events` y `console` siguen escribiendo su propia frase para el segundo posicional («unexpected extra argument: …»), y `operations` sigue con «a run id is required.» de su puño. Ahora es un residuo declarado y medido, no un olvido.
- La pista de `TooManyPositionals` dice «this command takes one positional argument», y estos verbos tienen exactamente un posicional: es correcta, pero el texto no distingue el caso de un verbo con dos.
- `pipeline-application` no está entre los módulos con `apiCheck`, así que este corte no toca ninguna superficie ABI vigilada: no cambia firmas, ni tipos públicos, ni formatos durables.
- Los verbos que admiten el run id por bandera (`events` con `--run`) conservan su camino propio.

**Evidencia.** Gate completo sin filtros, sobre `9639e72131849c2c24a75ddbf1d5939d6102a943`:

```
PASS  sha=9639e72131849c2c24a75ddbf1d5939d6102a943  elapsed=182s  budget=900s  tests=rewritten:1547 read:0  scenarios=observed:63 executed:63
```

- 1547 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 182 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28829`, `PRODUCT_LOC = 71270`, ratio `0.405` (límite 2). La verificación subió 98 líneas —las seis filas nuevas y su documentación— y la producción subió 37. En `pipeline-application` el ratio es `6419 / 25655 = 0.25`.

#### El corte: los verbos de `credentials` rechazan el argumento que no poseen

**Qué hace.** Los cuatro verbos de `credentials` se tragaban el argumento sobrante. `credentials list extra-arg-xyz` contestaba «No credentials stored.» con código 0: un veredicto que dice que la petición se entendió y se llevó a cabo cuando no era una petición de ese verbo. `credentials add|remove|rotate <id> extra-arg-xyz` se tragaba el token: `add` seguía pidiendo la clave, `remove` y `rotate` contestaban sobre la tienda, y ninguno nombraba nunca el token que había.

El verbo es el único que sabe qué argumentos posee, así que el rechazo vive donde el posicional se lee. No es una tabla nueva, y no es la admisión de la aplicación: `pipeline-credentials-local` está por debajo de `pipeline-application` en el grafo de dependencias, así que no puede ver `CliError` ni `CliOptionAdmission`, que viven en la aplicación. Un solo renderer dice la frase, el código de salida es el del módulo (`CredentialsOutcome`), y el rechazo ocurre ANTES de abrir la tienda: una petición que no es de este verbo no pide clave, no lee la tienda y no pide un secreto.

Medido sobre dos distribuciones construidas de este repositorio —`dea5593f5` (antes) y `8a3051755` (después)— en la misma máquina, con el MISMO script de sondeo y una tienda aislada que no existe. Se mide el código de salida y la primera línea de stdout.

|celda|antes|después|
|---|---|---|
|`credentials list`|`No credentials stored.` (0)|sin cambio (0)|
|`credentials list extra-arg-xyz`|`No credentials stored.` (0)|`Error: unexpected argument: extra-arg-xyz. 'credentials list' takes no argument.` (1)|
|`credentials add owned-id-42 extra-arg-xyz`|`Error: Passphrase required: set PIPELINE_STORE_PASSPHRASE or run interactively in a TTY` (2)|`Error: unexpected argument: extra-arg-xyz. 'credentials add' takes one argument: the credential id.` (1)|
|`credentials add --kind secret-text owned-id-42 extra-arg-xyz`|`Error: Passphrase required: …` (2)|`Error: unexpected argument: extra-arg-xyz. 'credentials add' takes one argument: the credential id.` (1)|
|`credentials remove owned-id-42 extra-arg-xyz`|`No credentials stored.` (1)|`Error: unexpected argument: extra-arg-xyz. 'credentials remove' takes one argument: the credential id.` (1)|
|`credentials rotate owned-id-42 extra-arg-xyz`|`No credentials stored.` (1)|`Error: unexpected argument: extra-arg-xyz. 'credentials rotate' takes one argument: the credential id.` (1)|

Las cinco celdas del argumento sobrante cambian. TRES cambian de código de salida, y las dos que había —0 y 2— son veredictos falsos: una petición que no era del verbo contestada como si lo fuera, y una petición respondida con la pregunta de la clave. Las tres salen ahora con 1, el código del módulo para una petición que no lleva a cabo. Las otras DOS cambian sólo de frase: `remove` y `rotate` ya contestaban 1, pero contestaban sobre la tienda, no sobre el token.

**El instrumento.** El verbo, llamado en proceso: se le da el array de argumentos, se lee su código de salida y se captura su salida (`CredentialsUnexpectedArgumentTest`). Sin CLI, sin subproceso, sin disco, sin reloj: el rechazo es anterior a la tienda, así que la fila no toca nada. Dos filas y no una por verbo: los tres verbos que poseen un id hacen la misma pregunta y comparten la misma respuesta. La fila no congela la frase del rechazo —congela el token, el id que el verbo posee y el verbo— y por eso afirma dos ausencias: que la salida no nombre el id poseído y que no publique la respuesta del verbo. El módulo fija además `PIPELINE_CREDENTIALS_STORE` a una ruta que no existe, porque la respuesta tiene que ser la misma en toda máquina: en una con la tienda poblada, `credentials list` contestaría con el listado y no con «No credentials stored.».

**Mutación.** Con los tres guards fuera, `:pipeline-credentials-local:test` dio 107 tests completados y 2 fallos, y los únicos XML con `<failure>` fueron las dos filas de `CredentialsUnexpectedArgumentTest`: sin colateral. Con sólo el guard de `remove` fuera, la fila data-driven murió en `remove` («remove: No credentials stored.») y la fila de `list` quedó en verde: cada guard se prueba por separado. La restauración se hizo desde una copia verificada por digest (`sha256 21bb20e0…`), no desde git.

**Lo que este corte NO arregla.**
- `credentials add --bogus-flag` responde «Error: --bogus-flag needs a value»: la bandera desconocida se confunde con una bandera a la que le falta el valor. Es un rechazo (código 1), pero la frase manda al sitio equivocado.
- `credentials remove --bogus-flag` toma la bandera como id: `remove` no distingue una opción de un posicional, así que un `--bogus-flag` sólo se descubre al final, cuando se busca un credential con ese nombre.
- `credentials remove --bogus-flag myid` rechaza nombrando `myid` (el segundo token), pero acepta `--bogus-flag` como id sin decirlo.
- `credentials <subcomando-desconocido>` y `credentials` a secas imprimen el uso sin nombrar el token que no es del verbo.
- El código de salida del módulo para una petición mal formada es 1 (`CredentialsOutcome.NOT_CARRIED_OUT`), donde la taxonomía del producto declara 2 para un error de invocación. `credentials` se delega en proceso desde la aplicación, así que el operador ve 1 por una petición de `credentials` y 2 por una petición equivalente del resto del CLI. Cambiarlo rompería el contrato publicado del módulo y el E2E que lo espera.
- Los rechazos de este módulo salen por stdout; los de la aplicación salen por stderr.

**Evidencia.** Gate completo sin filtros, sobre `8a30517550f6333f82eb5a583f7282aa7dbaf313`:

```
PASS  sha=8a30517550f6333f82eb5a583f7282aa7dbaf313  elapsed=181s  budget=900s  tests=rewritten:1549 read:0  scenarios=observed:63 executed:63
```

- 1549 tests EJECUTADOS de verdad, 0 leídos de informes anteriores, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E observados y ejecutados, en verde.
- 181 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 28882`, `PRODUCT_LOC = 71291`, ratio `0.405` (límite 2). La verificación subió 53 líneas efectivas y la producción 21. En `pipeline-credentials-local` el ratio es `1305 / 1876 = 0.696`.

## H6 — Reducir deuda del harness y asegurar la publicación

Foco: `examples/run.sh`, `verify-all.sh` y scripts de release.

Refactorizar internamente el lanzador E2E, conservando los escenarios actuales que aporten garantías diferentes.
Corregir el procedimiento existente de promoción estable y su evidencia de firma, sin introducir un sistema de publicación nuevo.
Consolidar verificaciones redundantes cuando protejan exactamente la misma propiedad, y mantener la clasificación entre pruebas parciales y puerta completa.
Entrega verificable: misma cobertura contractual o superior con menor código de infraestructura de pruebas, release reproducible y procedencia verificable.

#### El corte: el veredicto del gate cuenta lo ejecutado, no lo repuesto

**Qué hace.** `scripts/verify-all.sh` decidía que un informe JUnit lo había escrito ESTA ejecución por el `mtime` del fichero. La cache de construcción de Gradle restaura los BYTES de un informe y sella el fichero restaurado con el instante de la RESTAURACIÓN: un informe que ningún test escribía desde el martes llegaba con el `mtime` de hoy. El censo contaba ese fichero como «reescrito por esta ejecución», y la línea de veredicto —`tests=rewritten:1465 read:0`— describía 1465 tests que no se habían ejecutado. Y la guarda del gate para el caso «no se ejecutó nada, esto no es una medición, fallar» NO PODÍA dispararse, porque la restauración renovaba todos los `mtime`.

**La medición, con la única variable cambiada.** Sobre el mismo árbol, con la cache ya caliente:

|celda|censo de frescura|resultado|
|---|---|---|
|cache ON|`mtime`|`PASS elapsed=103s tests=rewritten:1465 read:0` — CERO tests ejecutados|
|cache ON|sello del XML|0 informes reescritos; la guarda FUEGA; salida 1|
|cache OFF|sello del XML|`PASS elapsed=187s tests=rewritten:1465 read:0` — 1465 tests ejecutados|

**El sello es el testigo que la cache no puede falsificar.** El atributo `<testsuite timestamp="...">` lo escribe el framework cuando la suite EMPIEZA y vive DENTRO de los bytes restaurados: renovarlo sería reescribir el contenido que la entrada de cache afirma reproducir. El `mtime` responde a otra pregunta —cuándo LLEGÓ el fichero—, y para el contenido es un testigo malo. Los dos testigos juntos nombran TRES estados, que es lo que el corte distingue:

- **ejecutado ahora**: el sello es de esta ejecución. Los tests corrieron.
- **servido de la cache**: el fichero LLEGÓ en esta ejecución (el `mtime` lo dice) pero su contenido es viejo: la cache restauró los bytes. Los tests NO corrieron.
- **leído de disco**: ni una cosa ni la otra; el fichero ya estaba ahí y no se tocó.

**El corte.** El censo lee el sello del informe y no el `mtime`; un informe cuyo sello falta o no se puede leer se cuenta como LEÍDO, nunca como reescrito, porque la dirección conservadora es la que NO reclama crédito. El gate completo deja de usar la cache de CONSTRUCCIÓN —la de DEPENDENCIAS se conserva, que es lo que «caches de dependencias locales ya poblados» significa—: con `--no-build-cache` (medido que pisa `org.gradle.caching=true` de `pipelinek/gradle.properties`) el `clean` significa lo que dice y el reloj mide el trabajo. El camino de subset conserva la cache, porque el bucle interior existe para ser barato; lo que el subset debe es CONTAR con honestidad, y eso lo da el sello.

**La guarda nueva, y por qué la vieja no servía.** La guarda vieja («ningún informe fue reescrito, esto no es una medición, fallar») no podía dispararse: la restauración renovaba todos los `mtime`. La nueva es «parte del grafo de tests fue servida de la cache, este reloj mide la cache y no el trabajo, fallar», y se dispara también en el caso MIXTO, que es el que se colaba: una ejecución que corre un módulo y deja los demás servidos.

**Visto en rojo, con la mutación leída de vuelta.** Cada mutación se aplicó, se leyó con `diff` contra la copia limpia, y el árbol se restauró desde esa copia antes de la siguiente.

|Mutación|Qué se encendió|
|---|---|
|El gate completo deja de pasar `--no-build-cache` (la cache sirve el grafo entero)|`verify-all: 196 report(s) were served from the build cache by this run: 1465 test(s) not executed` y después `FAILED at stage 2: part of the test graph was served from the build cache`, salida 1|
|La misma, con UN módulo con bytecode distinto (estado mixto: 40 informes reescritos, 156 servidos, 1233 tests que no corrieron)|la misma guarda; con la guarda VIEJA ese run habría imprimido `tests=rewritten:1466 read:0` y PASADO|

**Un hallazgo de paso, que explica cómo se provocaron los dos estados.** La clave de la cache de construcción se calcula sobre la SALIDA compilada, no sobre el fuente: un comentario añadido a un fichero de test NO invalida la clave y la tarea sigue siendo servida de la cache. Cambiar el bytecode sí. Por eso la primera tentativa de provocar el estado mixto —un comentario— no lo provocó, y por eso el estado mixto se provocó con una clase de test nueva.

**Los tres estados, vistos uno a uno en el camino de subset**, donde el sello separa lo que el `mtime` no separaba:

|estado|qué se observa|con el censo por `mtime`|con el sello|
|---|---|---|---|
|ejecutado ahora|el filtro cambia la clave de la tarea y ésta EJECUTA|`rewritten:8 read:0`|`rewritten:8 read:0`|
|leído de disco|la misma invocación, la tarea está al día|`rewritten:0 read:8`|`rewritten:0 read:8`|
|servido de la cache|las salidas se borran y Gradle RESTAURA: `mtime` de ahora, sello viejo|`rewritten:8 read:0`, 8 tests que no se ejecutaron|`rewritten:0 read:8`|

**La garantía, la capacidad y el instrumento.** La garantía es que la línea de veredicto del gate describe lo que el gate EJECUTÓ, y que un reloj del gate no se puede citar como el coste del trabajo si el trabajo no se hizo. La capacidad que la sostiene es que un lector puede distinguir una medición de un acierto de cache sin tener que saber de la cache: el veredicto lleva `rewritten` y `read`, y el subset declara que no es la puerta. El instrumento es el propio gate —no hay test unitario, y no puede haberlo: un test del gate sería un meta-test, que AGENTS.md prohíbe explícitamente—. El instrumento se prueba mutando el gate y mirando lo que se enciende, que es lo que se ha medido arriba.

**Lo que este corte NO arregla, y se dice.** No arregla que la cache de construcción exista, ni que `org.gradle.caching=true` siga en `pipelinek/gradle.properties`: el corte la desactiva SÓLO para el gate completo, y dejar el camino de subset con cache es una decisión del bucle interior, no un olvido. No arregla la granularidad del reloj: sigue siendo el reloj de pared de un proceso que compila y prueba, sin desglosar qué parte fue compilación y qué parte fue prueba. No arregla la dependencia del gate respecto al framework: un módulo que usara otro framework de test y no escribiera `<testsuite timestamp="...">` tendría sus informes contados como LEÍDOS —dirección conservadora—, pero el gate no podría atribuirlos. Y no arregla las cifras de reloj ya citadas en este documento para los cortes anteriores: se corrigen EN EL SITIO donde aparecen, no se reescribe la historia.

Medido sobre `d73c30493` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=d73c3049380862ff02387882782cdbed6e2b43af  elapsed=208s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1465 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 208 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad. Tres trials sobre el mismo árbol: 187 s, 188 s y 208 s; se cita el PEOR, que es lo que §4 pide.
- `VERIFY_LOC = 27605`, `PRODUCT_LOC = 71158`, ratio `0.388` (límite 2). El instrumento cuesta 145 líneas contadas por el censo —las de código, no las de sus comentarios— y entra en el numerador de verificación, que es donde debe estar: es código que decide qué se ejecutó.

#### El corte: la promoción comprueba el digest que el RC nombra

**Qué hace.** `scripts/release/promote-stable.sh` recibía `--candidate-id` como un parámetro libre: lo comprobaba contra el FORMATO (`sha256:` y 64 hex) y nada más. No lo contrastaba con el `candidate_id` que el propio RC nombra en el mensaje de su etiqueta. Medido contra un remoto real, con una etiqueta RC anotada, firmada y SUBIDA de verdad: se invocó la promoción con `--candidate-id sha256:0000…0000`, terminó con **salida 0**, firmó la etiqueta estable, la verificó y la subió al remoto, y el mensaje publicado fue `PipelineK v1.2.3 (candidate_id sha256:0000000000000000000000000000000000000000000000000000000000000000) (promotes v1.2.3-rc1)`. Todas las filas del veredicto de la promoción dieron PASS y su línea final imprimió `candidate_id=sha256:0000…` como si hubiera sido comprobado. El RC que promovía nombra `sha256:6f9566ef46386b8cf372671cb9eddff5488256eff5ea5fb99e1041c3e27082bf`.

**Por qué ese digest es el del RC y no otro.** La publicación estable lleva los ASSETS del RC, y `publish-github-release.sh --expect-sums` los contrasta contra el digest que el RC ya publica. El digest que viaja con la etiqueta estable es, por tanto, el del RC: uno escrito a mano que el RC no nombra es un digest de nada, y queda en la procedencia publicada. La ley ya existía en el producto —`CandidateHandoffValidation` rechaza un `candidate_id` que no sea el SHA-256 del artefacto que nombra— y lo que faltaba era aplicarla en la promoción, que es donde la etiqueta se escribía.

**El corte.** El objeto de la etiqueta del RC ya está PROBADO antes de esta comprobación (`RC_LOCAL_OBJECT` es el mismo objeto que el remoto publica), así que leer el mensaje de ese id de objeto es leer el mensaje del propio remoto. La promoción deriva ahora `RC_CANDIDATE_ID` de la línea de asunto de la etiqueta del RC y se niega en los dos casos: cuando el RC no nombra candidato, y cuando la afirmación del llamante no coincide. El parámetro NO se borra: es una AFIRMACIÓN sobre ese digest y no una segunda autoridad, y el fichero ya usa ese idioma —`--expect-fingerprint`, `--expect-commit`, el `--at` frente al `--expect-commit`—, donde el llamante nombra un hecho y el script lo contrasta con la autoridad y se niega ante la discrepancia.

**Medido antes y después, con la única variable cambiada:**

|celda|antes|después|
|---|---|---|
|afirmación distinta de la del RC|salida 0; etiqueta estable firmada, verificada y SUBIDA; procedencia publicada con `candidate_id sha256:0000…`|salida 1; el mensaje nombra las DOS afirmaciones; `stable_tag_local=absent` y `stable_tag_remote=absent`|
|el RC no nombra candidato|salida 0; se publicaba una etiqueta cuyo digest nadie puede comprobar|salida 1; la promoción se detiene|
|afirmación igual a la del RC|salida 0; la etiqueta publicada lleva `candidate_id sha256:6f9566ef…`, el del RC|salida 0; igual|

**Visto en rojo, con la mutación leída de vuelta.** Se borró la rama de comparación, se leyó el `diff` contra la copia limpia (la mutación ESTABA aplicada) y el árbol se restauró desde esa copia, nunca desde git.

|Mutación|Qué se encendió|
|---|---|
|La comparación de la afirmación con el digest del RC desaparece|exactamente UNA fila en rojo: la de la afirmación discrepante, que volvió a salir con 0 y a publicar y subir la etiqueta. La fila «el RC no nombra candidato» siguió en verde —la otra rama de negación sigue viva— y la de la afirmación coincidente también|

**La garantía, la capacidad y el instrumento.** La garantía es que una etiqueta estable publicada nunca nombra un digest que el RC no nombra. La capacidad que la sostiene es que la procedencia publicada nombra un digest comprobable: el que el RC ya nombra. El instrumento es la promoción ejecutada contra un remoto real —etiqueta RC anotada, firmada y SUBIDA de verdad—, porque un test unitario no puede: AGENTS.md prohíbe que un test unitario haga `git` o `gpg`, y un escenario de `examples/` corre la distribución de desarrollo, que no produce etiquetas. La protección permanente es la negación del propio script, que es el idioma ya establecido en este camino de publicación.

**Lo que este corte NO arregla, y se dice.** No comprueba que el `candidate_id` que nombra el RC sea el digest del ZIP de los assets: eso lo vigila `publish-github-release.sh --expect-sums` contra los bytes publicados, y lo que este corte comprueba es que la ETIQUETA no nombre un digest distinto del que nombra el RC. No arregla las lecturas `ls-remote` que se abren al fallar (`promote-stable.sh:165` y `release-pipelinek.sh:105`): la de aquí está ENMASCARADA por la lectura comprobada de la línea 179, así que es un diagnóstico engañoso y no una acción equivocada, y queda declarada como residual. No arregla que `publish-tag.sh` invoque `git` sin ruta absoluta y herede el envoltorio de identidad de esta máquina: también residual.

#### El corte: la evidencia de la firma nombra lo observado

**Qué hace.** Dos veredictos del camino de publicación contaban la PETICIÓN como si fuera la OBSERVACIÓN, y uno de ellos atribuía el fallo a la comprobación que había pasado.

El primero es la cabecera de `scripts/release/verify-tag-signature.sh`: imprimía `fingerprint=`, `uid=` y `commit=` con lo que el llamante había PEDIDO. Medido con una etiqueta firmada por una segunda clave real, la cabecera decía:

```
tag-signature: FAIL rows=A:PASS B:FAIL C:PASS tag=v9.9.9 fingerprint=9600F7D49B9C966E3BB04E6A17E44CA7C5288281 uid=test@example.com commit=<any> elapsed=0s
```

La etiqueta la había firmado `E2C455E5418E114E01ED0DFA827FC099B6DD656C` con el uid `otra@example.invalid`, y la cabecera nombraba la huella y el uid DECLARADOS y un commit `<any>` que sí se sabía. Las filas de detalle sí eran verdad: la fila B nombraba al firmante real. Quien lee la línea de veredicto —que es lo que se cita— se queda con la afirmación falsa.

El segundo es `scripts/release/verify-published-tags.sh`, que citaba SIEMPRE el motivo de la fila A. La fila que rechazó fue la B (identidad), y su motivo se quedó el de la A (criptografía):

```
published-tags: FAIL ... fingerprint=9600F7D49B9C966E3BB04E6A17E44CA7C5288281
  tag v9.9.9 verdict=FAIL ... signature=DOES-NOT-VERIFY (the signature verifies cryptographically)
```

Una frase que se contradice sola —afirma que la firma no verifica y lo justifica con el detalle de que sí verifica— y en la que el firmante observado NO APARECE. Esta auditoría es la puerta que la promoción ejecuta al final, y su salida es parte de la evidencia que la promoción publica.

**El corte.** La cabecera de la comprobación de firma nombra ahora la identidad OBSERVADA de la etiqueta —`signer=`, `uid=`, `commit=`— y guarda la petición en campos propios (`expected_fingerprint=`, `expected_uid=`, `expected_commit=`), de modo que ningún campo pueda decir una cosa y leerse como otra. La auditoría cita la fila que FALLÓ, con su letra (`B: …`), etiqueta el estado según la comprobación que rechazó (`DOES-NOT-VERIFY` / `FROM-ANOTHER-KEY` / `NOT-AS-DECLARED`), nombra al firmante observado en la fila, y separa en el resumen lo pedido de lo visto (`expected_fingerprint=` frente a `signers=`).

**El código de salida no cambia.** Las negaciones ya eran correctas: una etiqueta firmada por otra clave ya rechazaba la promoción. Lo que cambia es que el veredicto dice lo que miró, y el error que se llevaba la evidencia —«la firma no verifica, porque la firma verifica»— desaparece.

**Visto en rojo, con las mutaciones leídas de vuelta.** Dos mutaciones, cada una aplicada, leída con `diff` contra la copia limpia, y restaurada desde esa copia.

|Mutación|Qué se encendió|
|---|---|
|La cabecera vuelve a imprimir la huella PEDIDA en el campo del firmante|cuatro filas en rojo en el caso de clave ajena —«la cabecera nombra al firmante observado», «la cabecera no afirma al firmante declarado», «la fila de la auditoría nombra al firmante observado» y «el resumen lista los firmantes observados»—; el caso de clave DECLARADA siguió enteramente en verde|
|La auditoría vuelve a citar siempre la fila A|en el caso de clave ajena, la fila volvió a decir `signature=FROM-ANOTHER-KEY (the signature verifies cryptographically)` y se encendieron exactamente dos filas: «la auditoría cita la fila que rechazó» y «la auditoría no se contradice»; el caso de clave declarada siguió enteramente en verde|

**Un defecto del propio producto, encontrado por el instrumento.** El resumen de la auditoría decía `signers=<none>` justo en el caso que más lo necesitaba, porque el firmante sólo se acumulaba en el camino de éxito. El instrumento lo enrojeció y el producto lo corrigió: ahora el firmante se registra lo haya rechazado o no la comprobación, porque el firmante que provocó el rechazo es exactamente el que hay que ver.

**La garantía, la capacidad y el instrumento.** La garantía es que la evidencia de la firma de una publicación nombra QUIÉN firmó y POR QUÉ se rechazó, y que ninguna fila atribuye un fallo a una comprobación que pasó. La capacidad que la sostiene es que un lector del veredicto puede saber quién firmó sin leer las filas de detalle. El instrumento son las dos puertas ejecutadas contra un remoto y una clave reales —la declarada y una segunda clave efímera—, porque no puede ser un test unitario (estos scripts ejecutan `git` y `gpg`) ni un escenario de `examples/` (corre la distribución de desarrollo, que no produce etiquetas).

**Lo que este corte NO arregla, y se dice.** No ata la clave a una persona: una firma prueba la posesión de la clave privada, y la atribución a una cuenta o a una persona es un hecho que se establece fuera del repositorio; estos scripts ya lo dicen. No añade una regresión permanente, y se dice por qué: no hay un sitio permanente para una puerta de shell de publicación —`scripts/verify-all.sh` no la ejecuta, y un test unitario no puede hacer `git` ni `gpg`—, así que la protección es la negación del propio script y la evidencia que publica. No rompe el formato de la línea de veredicto: los campos que había pasan a ser `expected_…` porque ya no describen lo observado, y una búsqueda de todo el árbol no encuentra ningún lector de esas líneas.

Medido sobre `3fba86491` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=3fba864914f94b56ae89d92e52bdb1946300b31e  elapsed=187s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 1465 tests EJECUTADOS de verdad por esta ejecución, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 187 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27615`, `PRODUCT_LOC = 71237`, ratio `0.388` (límite 2).

#### El corte: el lanzador lee las directivas una vez y cuenta una vez

**Qué hace.** `examples/run.sh` leía las líneas `//@` de cada escenario en TRES sitios, y cada sitio tenía su propia regla: el reloj de la ejecución tomaba las veinte primeras, el parser tomaba las veinte primeras, y el censo tomaba las cuarenta primeras con OTRO patrón (`^//@ expect-`). Tres lecturas del mismo fichero, tres reglas distintas, y un número que las tres alimentaban.

La consecuencia viva era una cifra inflada. El censo contaba la promesa de `//@ expect-exit <distinto de cero>` como una promesa más, y el cuerpo del escenario la contaba OTRA VEZ con un `+1` por cada camino de ejecución. Medido sobre los 63 escenarios: **24 publicaban de más** —14 por dos comprobaciones y 10 por una—, y el informe anunciaba **594 comprobaciones** donde el lanzador hacía **556**.

La consecuencia latente era peor y estaba a un paso. Una línea `//@` en la posición VEINTIUNO la contaba el censo y no la leía nadie, porque el parser se había parado en la veinte. Y una directiva que el lanzador no entiende en esa posición dejaba de negar al escenario, porque el parser ya no la veía: era el mismo silencio que el lanzador ya se niega a dejar cuando una directiva no entiende —«el escenario no declara nada que comprobar»—, extendido por un techo de líneas.

**Medido antes y después, con la misma distribución y la misma suite:**

|celda|comprobaciones publicadas|escenarios|ejecuciones|pasos|inestables|
|---|---|---|---|---|---|
|antes (`306059080`)|594|63/63|114|220|2|
|después (`3a9483150`)|556|63/63|114|220|2|

La cobertura contractual NO baja: los mismos 63 escenarios, las mismas 114 ejecuciones, los mismos 220 pasos terminados y los mismos 2 inestables. Lo que baja es la cifra que mentía sobre la cobertura.

**El corte.** Un solo lector, `read_directives`, que lee TODAS las líneas `//@` del escenario, sin techo: los dos lectores que lo tenían —el del reloj y el del parser— pasan a usar ese lector, y el tercero desaparece. El censo deja de ser una SEGUNDA lectura del fichero: el número de promesas lo acumula el parser, en los mismos brazos que leen los valores que luego se comparan, de modo que el conteo no puede discrepar de lo leído —una promesa que el parser nunca vio no se cuenta, y una que vio no se pierde—. La cuenta del `expect-exit` se hace sobre el VALOR leído (el parser conserva el último) y sólo cuando ese valor es distinto de cero, porque el cero es lo que devuelve un proceso que no se ha caído: comprobar que no pasó nada no es una promesa. La comparación del código de salida ocurre una vez por ejecución —la que da el veredicto y la volátil, que son dos ejecuciones del producto comparadas cada una contra su propia salida—, y ahora el número publicado dice exactamente eso.

**Visto en rojo, con las mutaciones leídas de vuelta.** El instrumento fue el lanzador real sobre una suite de un solo escenario, con 21 directivas `//@` cuya última es un `expect-stdout` que el escenario NO satisface. Cada mutación se aplicó, se leyó con `diff` contra la copia limpia y el árbol se restauró desde esa copia, nunca desde git.

|Mutación|Qué se encendió|
|---|---|
|El lector vuelve a su techo de veinte líneas (`grep -m 20`), aplicado y leído con `diff` contra la copia limpia|el fixture queda VERDE (`ok … checks=46`): el escenario deja de comprobar la promesa VEINTIUNO y el lanzador la cuenta igual|
|El lanzador de `HEAD` sin el corte (`git show HEAD:examples/run.sh`)|el fixture queda VERDE (`ok … checks=48`): el instrumento estaba rojo sobre el código publicado|
|Los dos `+1` del `expect-exit` vuelven, aplicados y leídos con `diff`|la suite real vuelve a publicar **594** comprobaciones: la cifra mintiendo era observable|

La diferencia entre los 46 y los 48 del fixture tiene explicación y no es una columna que sobre: con el corte el escenario FALLA en su promesa VEINTIUNO, así que la comparación del resultado final no llega a ejecutarse; sin corte, el techo la esconde y el escenario pasa.

**La garantía, la capacidad y el instrumento.** La garantía es que toda directiva que un escenario declara se respeta, y que el informe dice cuántas comparaciones hizo el lanzador: una por promesa y por ejecución. La capacidad que la sostiene es que el informe de E2E se puede citar como cifra de comprobaciones hechas. El instrumento es el lanzador real —el número publicado sobre la suite real— más un escenario de fixture con 21 directivas, porque un test unitario no puede ejecutar el lanzador (AGENTS.md prohíbe el subproceso y el sistema de ficheros en un test unitario) y un escenario PERMANENTE en `examples/` que existe sólo para ejercitar un techo del lanzador sería infraestructura del harness, que §3 prohíbe: el fixture es efímero y vive en el ciclo de verificación, no en el árbol.

**Lo que este corte NO arregla, y se dice.** No arregla que el conjunto de escenarios se enumere DOS veces con reglas distintas: `scripts/verify-all.sh:plan_scenarios` busca `*.pipeline.kts` sin excluir `*/example-*-plugin/*`, mientras que `run.sh` sí lo excluye, y el gate nunca compara su plan con lo que realmente se ejecutó. Hoy las dos enumeraciones dan los mismos 63 caminos, así que no hay un defecto OBSERVADO, pero la regla es distinta y queda declarada. No arregla las OCHO copias del bloque que lee el identificador de ejecución de `$root/control/last-run/*` (once líneas efectivas cada una, la lectura y su brazo de negación, equivalentes en comportamiento salvo el nombre de la variable y el texto del fallo): es otra consolidación, con su propia medición. No arregla el idioma del fallo, que vive en dos vocabularios dentro del lanzador —la fila que se IMPRIME y la que se REGISTRA en `failures`— y que no siempre dicen lo mismo: en `examples/run.sh:2715` la fila impresa nombra «a database» y la registrada nombra «the file». No arregla la lectura `ls-remote` que se abre al fallar ni el `git` sin ruta absoluta de `publish-tag.sh`, ya declarados como residuales en los cortes anteriores de este mismo hito.

Medido sobre `3a9483150` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=3a9483150302ea162989b09d897982a602a7229c  elapsed=181s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1465 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde, 556 comprobaciones sobre 114 ejecuciones y 220 pasos terminados.
- 181 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27617`, `PRODUCT_LOC = 71237`, ratio `0.388` (límite 2).

#### El corte: un remoto que no se puede leer no es un remoto sin etiquetas

**Qué hace.** `scripts/release/promote-stable.sh` comprobaba que la etiqueta estable estaba libre con un pipeline cuyo estado era el de `grep`:

```bash
if git ls-remote --tags "$REMOTE" "refs/tags/${STABLE_TAG}" | grep -q .; then
```

Un remoto que no se puede leer hace salir a `git ls-remote` con error (128) y sin stdout; la salida vacía hace que `grep` no encuentre nada y salga con 1; el `if` toma la rama falsa y el script CONCLUYE que la etiqueta no existe en el remoto. `set -o pipefail` no lo evita: el estado de un pipeline es el de su última orden, y `grep` es la última. La garantía #1 del propio script —«the STABLE tag name is free — absent locally and absent on the remote»— podía cumplirse sin haber observado el remoto: una comprobación que no se pudo hacer no es una comprobación que pasó.

Medido con `REMOTE=/nonexistent/remote.git`:

```
ls-remote exit: 128
stdout: []
stderr: [fatal: '/nonexistent/remote.git' does not appear to be a git repository]
CHECK: the tag is ABSENT on the remote  <-- what the scripts conclude
```

El remoto se leía además DOS veces —la de la etiqueta estable y la del RC—, de modo que un transporte que falla una vez y luego responde, que es ordinario, dejaba pasar la primera comprobación con una lectura muerta y contestaba la segunda con otra. Dos lecturas del mismo remoto pueden discrepar sin que nadie lo vea.

El corte anterior declaró este residual y acertó en una parte: en ESTE script la lectura del RC (línea 179) sí comprobaba su estado, así que la promoción se negaba igualmente —«un diagnóstico engañoso y no una acción equivocosa»—. Lo que su medición no vio, porque su remoto respondía, es lo que este corte mide: muerta la lectura de la etiqueta estable, la comprobación de la garantía se salta y el script sigue hacia otra.

**El corte.** El remoto se lee UNA vez, con su estado comprobado:

```bash
REMOTE_LISTING="$(git ls-remote --tags "$REMOTE" \
    "refs/tags/${STABLE_TAG}" \
    "refs/tags/${RC_TAG}" "refs/tags/${RC_TAG}^{}")" || { … exit 1; }
```

y ese listado contesta las DOS preguntas —la de la etiqueta estable y la del RC—, con el idioma de exacto que ya usaba el RC (`awk -v r=… '$2 == r'`), que no es un patrón: una etiqueta con punto en el nombre no puede casar con otra por accidente. Un remoto que no se puede leer rechaza la promoción diciendo que no pudo leer el listado y que la etiqueta estable no pudo comprobarse; un remoto que sí se puede leer y tiene la etiqueta sigue siendo rechazado con el mismo mensaje de antes. La cabecera del script declara ahora la garantía como se cumple: «the STABLE tag name is free — absent locally and absent on the remote, and the remote is READ to say so».

**Medido antes y después, con la única variable cambiada** (el script de publicación REAL, un remoto bare local y un shim de `git` en el PATH que hace fallar la PRIMERA lectura y delega el resto):

|celda|antes|después|
|---|---|---|
|la primera lectura del remoto falla|lecturas al remoto: **2**; el script pasa la comprobación de la etiqueta estable; el rechazo final habla del `candidate_id` del RC —un motivo que no nombra la lectura que no se pudo hacer|lecturas al remoto: **1**; el rechazo nombra el listado que no se pudo leer Y la etiqueta `v1.0.0` que no pudo comprobarse|
|el remoto tiene la etiqueta estable|salida 1; el rechazo la nombra como ya existente en el remoto|salida 1; igual|
|el remoto se lee bien|salida 1 tras la lectura del RC; el rechazo habla del `candidate_id`|salida 1 tras la lectura del RC; el rechazo habla del `candidate_id`|

El caso del medio es el que no puede cambiar: es el contenido de la garantía, y el corte no lo toca.

**Visto en rojo, con la mutación leída de vuelta.** Se restauró el fichero de `HEAD` —el defecto entero, no una rama—, se comprobó con `grep` que `REMOTE_LISTING` NO estaba, es decir, que la mutación ESTABA aplicada, y se restauró el arreglo desde una copia del fichero, nunca desde git, comprobando después con `diff` que la copia y el fichero eran idénticos byte a byte.

|Mutación|Qué se encendió|
|---|---|
|El fichero vuelve al de `HEAD` —la comprobación de la etiqueta estable vuelve a ser el pipeline con `grep`—|en el caso «la primera lectura falla»: exactamente TRES filas en rojo —«el remoto se leyó exactamente una vez», «el rechazo nombra la etiqueta estable» y «el rechazo dice que el listado no se pudo leer»—; el caso «el remoto tiene la etiqueta estable» siguió ENTERAMENTE en verde, porque es el contenido de la garantía y no lo toca este corte|
|El mismo defecto, observado en el caso «el remoto se lee bien»|una fila en rojo: «el remoto se leyó exactamente una vez», que leyó DOS veces|

**La garantía, la capacidad y el instrumento.** La garantía es que la promoción nunca afirma que un nombre de etiqueta está libre en el remoto sin haber leído el remoto, y que el motivo del rechazo nombra lo que no se pudo comprobar. La capacidad que la sostiene es que un lector del rechazo sabe si la garantía se comprobó o si el remoto no respondió. El instrumento es la promoción ejecutada contra un remoto bare real con un shim de `git` que inyecta el fallo de transporte, porque no puede ser un test unitario —AGENTS.md prohíbe que un test unitario haga `git` o `gpg`— ni un escenario de `examples/` —corre la distribución de desarrollo, que no produce etiquetas—. El shim ESCRIBE un log de cada lectura, y por eso la cifra «lecturas al remoto» es una OBSERVACIÓN del instrumento y no una cuenta hecha a mano.

**Lo que este corte NO arregla, y se dice.** No arregla la misma forma en `scripts/release-pipelinek.sh:105` ni en `scripts/release/publish-github-release.sh:277`: ahí la lectura que se abre al fallar es la ÚNICA lectura previa, así que el rechazo posterior nombra un motivo que no es el del fallo —en `release-pipelinek.sh` el operador acabaría mirando el keyring de firma cuando el bloqueo real es la red—. No se arreglan aquí porque su instrumento exige otro montaje: `release-pipelinek.sh` hace `cd "$REPO_ROOT"` y su comprobación de árbol sucio precede a la lectura, de modo que medirlo exige el árbol limpio o una copia del script —y `publish-github-release.sh` hace su lectura DETRÁS de la verificación de firma, que exige una etiqueta firmada de verdad—. Quedan declarados como residuales, con su sitio exacto. No reduce a UNA las lecturas del remoto en todo el camino de publicación: la auditoría final (`scripts/release/verify-published-tags.sh`) vuelve a leer el remoto después de publicar, y esa lectura es de otra puerta. No arregla que `publish-tag.sh` invoque `git` sin ruta absoluta y herede el envoltorio de identidad de esta máquina: sigue siendo residual.

Medido sobre `6e342f1f1` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=6e342f1f182b902efacd6bf300b44d09e09a1292  elapsed=185s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1465 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 185 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27617`, `PRODUCT_LOC = 71259`, ratio `0.388` (límite 2).

#### El corte: una comprobación que no se pudo hacer no es una comprobación que pasó

**Qué hace.** El corte anterior cerró `promote-stable.sh` y declaró como residuales las otras dos lecturas del remoto del camino de publicación, en `scripts/release-pipelinek.sh:105` y `scripts/release/publish-github-release.sh:277`. Las dos tenían la misma forma que la ya corregida: el estado de un pipeline es el de su última orden, y `grep` era la última.

```bash
if git ls-remote --tags origin "refs/tags/${PIPELINEK_RELEASE_TAG}" | grep -q .; then
```

Un remoto que no se puede leer deja el listado vacío; `grep` sale con 1; y la comprobación pasa como si la etiqueta no estuviera. Lo que cambia entre los dos sitios es QUÉ se deduce de ese paso:

- En `release-pipelinek.sh` la lectura era la ÚNICA comprobación previa de la garantía #4 —«the tag name is fresh (does not collide with an existing remote tag)»— y el script **seguía adelante**. Medido: con el remoto ilegible, el script llegó al gate de publicación, que en el fixture es un `gradlew` falso que se anuncia. Sin ese tope, lo que venía después era un build de quince minutos.
- En `publish-github-release.sh` la lectura es la única previa, pero el `if !` invertido convertía el paso en un **rechazo que afirmaba un hecho falso**: «the tag %s does not exist in %s». El remoto SÍ tenía la etiqueta —leído directamente en esa misma ejecución, `git ls-remote --tags …/remote-b.git refs/tags/v0.0.0-remote` responde `ac2148cfe56659467a3b92aae90fb5cc2393eaba refs/tags/v0.0.0-remote`— y el script dijo que no existía.

**El montaje que responde al obstáculo declarado.** El corte anterior dejó escrito por qué no los arreglaba: «su instrumento exige otro montaje: `release-pipelinek.sh` hace `cd "$REPO_ROOT"` y su comprobación de árbol sucio precede a la lectura, de modo que medirlo exige el árbol limpio o una copia del script — y `publish-github-release.sh` hace su lectura DETRÁS de la verificación de firma, que exige una etiqueta firmada de verdad». El montaje que responde a las dos condiciones es el mismo:

1. **El script de envío se alcanza por un symlink dentro de un árbol-fixture.** El fichero que se ejecuta es el de envío —el núcleo lee el destino del symlink—, y el árbol en el que el script cree estar es el del fixture, porque `REPO_ROOT` se deriva del propio camino del script. Así no hace falta ni un árbol limpio del repositorio real ni una copia del script: el symlink da las dos cosas.
2. **La precondición de firma es REAL, no simulada.** El fixture firma la etiqueta con la clave que el propio repositorio DECLARA —`scripts/release/signing-key.conf` es el fichero de envío, enlazado— en un keyring real, y la comprobación la hace el `scripts/release/verify-tag-signature.sh` de envío, enlazado también. Su veredicto, observado sobre la etiqueta del fixture, sale entero en verde: `tag-signature: PASS rows=A:PASS B:PASS C:PASS tag=v0.0.0-remote signer=9600F7D49B9C966E3BB04E6A17E44CA7C5288281`. Lo único que se rompe es el TRANSPORTE.
3. **El gate de publicación es un `gradlew` falso que se anuncia** (`FIXTURE-GATE: the release gate was reached`), para que caminar hasta el build sea una OBSERVACIÓN y no un build de quince minutos.

**El corte.** El listado se lee UNA vez, con su estado comprobado, y la pregunta se le hace al listado por nombre EXACTO —`awk -v r=… '$2 == r'`—, que no es un patrón: una etiqueta con punto en el nombre no puede casar con otra por accidente. Un remoto que no se puede leer rechaza nombrando lo que no se pudo comprobar y qué etiqueta quedó sin comprobar.

**Medido antes y después, con la única variable cambiada** (los scripts REALES, un remoto bare local y un shim de `git` en el PATH que hace fallar la PRIMERA lectura y delega el resto; 25 filas, 6 rojas antes y 0 después):

|celda|antes|después|
|---|---|---|
|`release-pipelinek.sh`, el remoto no se puede leer|salida 3; **no hubo rechazo ninguno**: el script siguió hasta el gate de publicación (`FIXTURE-GATE: the release gate was reached`)|salida 1; el rechazo nombra el listado que no se pudo leer Y la etiqueta `v0.0.0-remote` que no pudo comprobarse; el gate no se alcanzó|
|`publish-github-release.sh`, el remoto no se puede leer|el remoto SÍ tenía la etiqueta y el script rechazó diciendo «the tag v0.0.0-remote does not exist in …»|el rechazo nombra el listado que no se pudo leer y no afirma que la etiqueta falte|
|`release-pipelinek.sh`, el remoto se lee bien y tiene la etiqueta|refusa diciendo «already exists on origin»|igual|
|`release-pipelinek.sh`, el remoto se lee bien y no la tiene|llega al gate de publicación|igual|
|`publish-github-release.sh`, el remoto se lee bien y tiene la etiqueta|publica: pregunta a `gh` con `release create … --verify-tag`|igual|
|`publish-github-release.sh`, el remoto se lee bien y no la tiene|refusa diciendo «the tag … does not exist in …»|igual|

Las CUATRO filas del medio son el contenido de la garantía y este corte no las toca: las dos que dependen de que el remoto TENGA la etiqueta, y las dos que dependen de que NO la tenga.

**Visto en rojo, con la mutación leída de vuelta.** Se restauraron los DOS ficheros enteros a su contenido previo al corte —el defecto entero, no una rama—, se comprobó con `grep` que el guard `REMOTE_LISTING` NO aparecía en ninguno de los dos (es decir, que la mutación ESTABA aplicada) y que la forma `ls-remote … | grep -q .` había vuelto a las líneas 105 y 277, y el `git diff --stat` mostró 6 inserciones y 42 supresiones contra el commit. Después se restauró el arreglo desde una copia del fichero, nunca desde git, comprobando con `diff -q` que la copia y el fichero eran idénticos byte a byte.

|Mutación|Qué se encendió|
|---|---|
|Los dos ficheros vuelven al defecto|exactamente SEIS filas en rojo —en `release-pipelinek.sh`: «refuses» (3 en vez de 1), «names the unreadable listing», «names the tag» y «never reached the build»; en `publish-github-release.sh`: «names the unreadable listing» y «does not claim the tag is absent»—; las otras DIECINUEVE filas siguieron en verde, incluidas las cuatro que son el contenido de la garantía|

**La garantía, la capacidad y el instrumento.** La garantía es que el camino de publicación nunca afirma que un nombre de etiqueta está libre —o que falta— en el remoto sin haber leído el remoto, y que el motivo del rechazo nombra lo que no se pudo comprobar. La capacidad que la sostiene es que un lector del rechazo sabe si la garantía se comprobó o si el remoto no respondió. El instrumento son los scripts de envío ejecutados contra un remoto bare real con un shim de `git` que inyecta el fallo de transporte, porque no puede ser un test unitario —AGENTS.md prohíbe que un test unitario haga `git` o `gpg`— ni un escenario de `examples/` —corre la distribución de desarrollo, que no produce etiquetas—. El shim ESCRIBE un log de cada lectura, y por eso la cifra «read the remote once» es una OBSERVACIÓN del instrumento y no una cuenta hecha a mano.

**Lo que este corte NO arregla, y se dice.** No arregla que `scripts/release/verify-published-tags.sh` vuelva a leer el remoto después de publicar: es otra puerta, con su propia lectura —`verify-published-tags.sh:101`, que sí comprueba el estado de su lectura—, y este corte no la toca. No arregla que `publish-tag.sh` —ni ahora `release-pipelinek.sh` ni `publish-github-release.sh`— invoque `git` sin ruta absoluta: heredan el `git` que haya en el PATH, que en esta máquina es un envoltorio de identidad; sigue siendo residual, y ahora alcanza a los tres. No arregla que el fixture no sea un build real: el `gradlew` falso observa el ORDEN en que el script recorre sus puertas, y el gate de verdad sigue siendo el de `scripts/verify-all.sh`. Y no arregla que el camino de publicación no tolere un remoto ilegible: lo RECHAZA, que es lo que se quería; lo que cambia es que el rechazo dice la verdad.

Medido sobre `2e490b418` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=2e490b418a34365afbe4c4363381aa80921228dc  elapsed=186s  budget=900s  tests=rewritten:1465 read:0  scenarios=observed:63 executed:63
```

- 196 informes JUnit reescritos por esta ejecución, 1465 tests EJECUTADOS de verdad, 0 fallos, 0 omitidos.
- 63 de 63 escenarios E2E ejecutados y en verde.
- 186 s de reloj de pared frente al presupuesto de 900 s, con la suite COMPLETA ejecutada de verdad.
- `VERIFY_LOC = 27617`, `PRODUCT_LOC = 71292`, ratio `0.387` (límite 2).

## 4. Orden y dependencias


H1 y H2 pueden desarrollarse en paralelo, en equipos separados. Después, H3 debe integrar los cambios del shell antes de acometer H4. Así se evitan múltiples agentes modificando simultáneamente `ShExecution` o `StepDispatchEngine`.

Para estos refactors grandes emplearía migraciones incrementales en `main`, manteniendo el proyecto compilable tras cada integración. Branch by Abstraction es apropiado cuando existe un límite estable y se necesita sustituir gradualmente una implementación; no debería utilizarse para crear capas temporales innecesarias.


martinfowler.com

## 5. Reglas obligatorias para el agente

Los agentes deberán trabajar con estos criterios:

1. Refactor antes que rediseño. Identificar la responsabilidad, moverla y eliminar la implementación anterior; no dejar dos caminos activos indefinidamente.    
2. Una autoridad por concepto. Especialmente identidad de operación, ciclo de vida de procesos, resultado terminal, redacción y persistencia.    
3. Sin cambios accidentales de ABI ni de formatos durables. Toda modificación externa debe justificarse por un defecto demostrado.    
4. Programación funcional donde aporta valor. Decisiones mediante funciones puras y ADTs; filesystem, relojes, procesos y streams aislados tras puertos.    
5. Propiedad explícita de recursos. Cada proceso, pipe, hilo y descriptor debe pertenecer a un lanzamiento concreto, y su limpieza debe producirse también ante excepciones o cancelaciones. El ciclo de vida estructurado de Kotlin es una referencia útil, sin necesidad de reescribir todo el shell utilizando coroutines.    
6. No multiplicar interfaces. Introducirlas donde separen una política de un mecanismo o exista una necesidad real de sustitución, no por cada clase.    
7. Borrar código reemplazado. El objetivo es reducir complejidad neta, no aumentar la cantidad de envoltorios.    
8. Verificar comportamiento, no estructura interna. Las pruebas deben seguir siendo válidas si una responsabilidad cambia de archivo o paquete.
    
## 6. Política de testing y aceptación

No hace falta construir otra suite. Para cada hito usaría la infraestructura ya existente:

|Fase|Verificación|
|---|---|
|Desarrollo|Unitarios puros de las decisiones modificadas|
|Integración|Tests reales mínimos sobre puertos afectados|
|Cierre funcional|1–2 escenarios discriminantes existentes o efímeros|
|Antes de release|`scripts/verify-all.sh` completo|
|Después del bloque|Eliminar harness auxiliar, UAT/E2E/AAT efímeras y fixtures desechables|

Los objetivos medibles serán:

# 0
Funcionalidades nuevas y cambios de comportamiento no justificados

# 0
Duplicaciones de caminos de ejecución introducidas
# ≤ 15 min
Presupuesto total de la puerta completa
# ≤ 1×

Objetivo de líneas de pruebas permanentes frente a producción

Metas de aceptación, no resultados ya medidos tras los refactors.

Para valorar la mejoría, además de LOC mediría dependencias cíclicas, número de responsables por caso de uso, supresiones de complejidad, tiempo de compilación incremental y tiempo real de pruebas. Una reducción de líneas en `DurableShellExecutor` que simplemente traslade su complejidad a otro archivo no contará como mejora.

## 7. Qué queda expresamente fuera

No se implementará `output release`, ni nuevas opciones CLI, ni proveedores de ejecución, ni protocolos de agente, ni capacidades de observabilidad. Tampoco se modificarán las políticas de retención actualmente elegidas.

En el caso del Output Plane solo se refactorizarán las responsabilidades existentes y se corregirán los problemas demostrados de propiedad de recursos, caché o gestión de ciclos de vida.

Cada hito debe acabar con una release candidata integrada en Git remoto, verificable de forma independiente, sin depender de que el siguiente hito llegue a completarse.

Mi prioridad sería H2 y H3, después de corregir H1. Es donde confluyen la mayor complejidad, la connascencia temporal y el riesgo de errores difíciles de reproducir. A continuación abordaría H4, que es donde la extracción por vertical slices puede producir la mayor reducción de acoplamiento en `pipeline-application`.
