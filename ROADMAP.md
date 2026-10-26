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

El arreglo es de **H6**, que es donde vive el gate: el censo debe leer la marca de tiempo PROPIA del XML (`timestamp`), no el `mtime` del fichero, y distinguir tres estados —ejecutado ahora, servido de la cache, leído de disco— en vez de dos. No se toca el gate aquí: cambiar el instrumento de medición en mitad de un corte cambiaría la contabilidad del presupuesto que este corte está midiendo. Este corte se midió, por tanto, con la cache desactivada, y por eso su línea de veredicto dice lo que dice. En los tres cortes anteriores, cuyo código cambió, la cache sólo pudo servir los módulos cuyas entradas no cambiaron —sus hallazgos (detekt, LOC, escenarios) no dependen de ello—, pero su cifra de `rewritten` es la del gate, y el gate no distingue lo ejecutado de lo repuesto.

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

## H5 — Separar CLI, consultas y proyecciones

Foco: `CliParser.kt`, `MainConsoleCli.kt`, Observe, Explain, Cost y Telemetry.

El parser actual, de unas 1.931 líneas, debería delegar en parsers por familia de comando. La interpretación de opciones debe terminar antes de comenzar cualquier efecto.

`MainConsoleCli.kt` debe separar selección, lectura, seguimiento y presentación, reutilizando los lectores existentes.

Las proyecciones Explain, Cost y Telemetry deben compartir las reglas de identidad y asociación de pasos, no una única proyección gigantesca.

Se corregirá además la inconsistencia de `CLI_VERBS` para que la ayuda refleje los comandos ya implementados, incluido `runs recover`.

Entrega verificable: mismas invocaciones válidas, mismas respuestas públicas y mismo comportamiento de lectura, sin lógica de consulta duplicada en los controladores CLI.

## H6 — Reducir deuda del harness y asegurar la publicación

Foco: `examples/run.sh`, `verify-all.sh` y scripts de release.

Refactorizar internamente el lanzador E2E, conservando los escenarios actuales que aporten garantías diferentes.
Corregir el procedimiento existente de promoción estable y su evidencia de firma, sin introducir un sistema de publicación nuevo.
Consolidar verificaciones redundantes cuando protejan exactamente la misma propiedad, y mantener la clasificación entre pruebas parciales y puerta completa.
Entrega verificable: misma cobertura contractual o superior con menor código de infraestructura de pruebas, release reproducible y procedencia verificable.

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
