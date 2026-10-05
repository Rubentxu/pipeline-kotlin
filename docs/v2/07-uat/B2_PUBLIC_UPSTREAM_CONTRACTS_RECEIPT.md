# BLOCK 2 — Public Upstream Contracts for Fabric

**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Base:** `532e272a2ac3161522a4ae7c27b482c4ce3c9385`
**Gate SHA:** `5a5a56e9210b1a21adad10ecde57b9a6d1e52b1f`

> Recibo de cierre de BLOCK 2. Cubre B2-1..B2-8 y el estado del gate de B2-9 en el momento de
> escribir estas líneas. La autoridad de lo que aquí se afirma es el árbol de código y la
> evidencia ejecutada; los recibos anteriores prueban únicamente sus propios SHA.

## 1. Qué se pidió y qué se entregó

El objetivo era que `pipelinek-fabric` pudiera consumir una candidata PipelineK **sin dependencia
de fuente ni composite**. Se publicaron cuatro contratos y nada más:

| Artefacto | Qué expone | Qué NO expone |
|---|---|---|
| `pipeline-domain` | `RunOutcome`, `ResourceRef`, tipos durables | — |
| `pipeline-events` | `PipelineEventEnvelope`, `EventCursor`, `EventPage`, `EventTail`, `EventQuery` | `events.durable.*` |
| `pipeline-output` | `OutputStreamId`, `OutputCursor`, `OutputPage`, `OutputReadPort`, `OutputReadResult`, `OutputRefusal` | `output.store.*` |
| `pipeline-scripting-api` | vocabulario de scripted | — |

Y dos módulos **no publicados** que alojan la implementación: `:pipeline-output-store`
(`output.store`) y `:pipeline-events-store` (`events.durable`).

## 2. La decisión que gobierna todo lo demás

**La frontera publicado/no publicado se decidió por propiedad, no por lista de tipos.** La
propiedad es: un módulo publicado no toca disco, no abre JDBC y no depende de otro módulo del
repo salvo el contrato al que apunta.

Eso forzó el resultado en vez de precederlo. El lado de escritura, el recovery y los stores
salieron del contrato de output porque un consumidor que puede escribir es una segunda autoridad
potencial sobre los bytes, y porque BLOCK 1 ya había decidido no publicar `OutputAppendPort`.
Publicar el módulo entero habría publicado exactamente lo que BLOCK 2 prohíbe.

El split fue **out, no in**: la implementación se mudó a un módulo nuevo y el módulo publicado
conservó su nombre. El churn es mínimo y el nombre publicado sigue siendo el del plano.

## 3. Lo que el consumidor independiente encontró

`examples/fabric-contract-consumer` es un build Gradle con su propio `settings.gradle.kts`, sin
ningún `project(...)`, que resuelve **sólo** las cuatro coordenadas publicadas. Existe porque
`apiCheck` y el fitness de frontera miran el artefacto desde **dentro**, y los dos pueden quedar
verdes con un artefacto que un consumidor real no compila.

Encontró dos defectos reales del contrato publicado:

1. **`causation` y `correlation` se declaraban en el cable y nunca se escribían.** La clase `Wire`
   las declaraba como cuatro `String?`, el KDoc afirmaba que estaban en el wire, y `serialize` no
   rellenaba ninguna de las dos mientras `deserialize` no leía ninguna. El envelope aparentaba
   llevar contexto causal y no llevaba ninguno. Es un parámetro semántico muerto en el sentido de
   la constitución semántica. Corregido con un `EventRefSerializer` cerrado.

2. **`EventCursor` no podía decodificar un runId con dos puntos.** `encode()` interpolaba el
   runId en crudo mientras `decode()` dividía por `:` y exigía exactamente tres partes. Un runId
   real (`ResourceRef.canonicalText()` es `v1:run:...`) no round-trippeaba: el token que el
   producto producía era rechazado por su propio decoder. Corregido con escapado simétrico al de
   `OutputCursor`.

Que un consumer externo encontrara el segundo es la justificación de que exista. Un test interno
que hubiera asserted `encode().startsWith("evt-cursor-v1:")` habría dado verde.

## 4. El control negativo, y sus dos fallos silenciosos

`verifyPublishedSurface` afirma que el `compileClasspath` resuelto contiene exactamente las cuatro
coordenadas y ningún módulo de implementación. **Falló en silencio dos veces**, y las dos en la
misma dirección:

- **La primera**: reconstruía `group:artifact` desde la ruta Maven suponiendo que el grupo era un
  segmento. `dev.rubentxu.pipeline.v2` ocupa **cuatro**. Cada coordenada salía como
  `pipeline-events:v2`, se filtraba, y el control reportaba `Found: []` sobre una superficie
  correcta.
- **La segunda**: pidió un `ModuleComponentIdentifier` a `artifact.id`, que es la identidad del
  **fichero** (`pipeline-events-0.47.0.jar`), no del módulo. Vacío otra vez, por un motivo más
  sutil. La identidad del módulo vive en `artifact.variant.owner`.

Un check que no puede fallar es peor que no tener check, porque se lee como evidencia. Por eso la
vaciedad se afirma **por separado**: `coordinates.isNotEmpty()` falla con su propio mensaje, en
vez de dejar que una igualdad rota pase por comprobación.

Mutación que demuestra que muerde (M11): publicar `pipeline-events-store` — la fuga exacta que
BLOCK 2 prohíbe — hace fallar el control nombrando el artefacto por coordenada. Es la misma fuga
que M8 probó desde dentro; lo nuevo es que ahora se detecta por **resolución de artefacto**.

## 5. El golden, y por qué vive fuera de `v2`

`examples/fabric-contract-consumer/src/test/resources/golden/contracts.golden`, 21 filas: los
cuatro `RunOutcome`, las seis ramas de `OutputRefusal`, los cinco casos de `EventQuery`, los dos
tokens de cursor y cuatro filas de envelope.

Vive fuera de `v2` por una razón concreta: un golden dentro de `v2` lo regeneraría el mismo
commit que cambia el contrato, y el revisor estaría comparando un fichero consigo mismo. Aquí el
fichero esperado está commiteado en un repositorio sin acceso al código que lo cambió.

Se compara **entero** desde una sola llamada. Row por row, una fila eliminada pasaría desapercibida:
una comparación que sólo busca las filas que ya conoce sigue dando verde después de que el
contrato pierda un caso. Mutación que lo demuestra: borrada `refusal.RecoveryNotCompleted`, la
prueba da rojo y la fila ausente aparece en el `expected:`.

## 6. ROADMAP: RP-8 y RP-9 ya no son de este repo

RP-8 (control plane, workers, transporte remoto) y RP-9 (Jenkins, Kubernetes) dejan de reclamarse
en `pipeline-kotlin`. La razón no es de alcance sino de **autoridad**: un control plane existe para
decidir qué run arranca, qué run se cancela y cuándo un run está terminal, y si este repo vuelve a
decidir eso hay dos autoridades sobre la misma condición. La ley de *una condición, una
representación* se rompería por separación de paquetes en lugar de por diseño.

Lo que este repo conserva es el runtime que el control plane ejecuta, y lo que entrega para que
Fabric pueda cumplir RP-8 y RP-9: un contrato publicado resoluble sin fuente, un spine de eventos
con semántica, y un SDK de plugins abierto.

El texto anterior se conserva literal en §10.2 y §11.2. No se borra: es el registro de por qué se
tomó la decisión.

## 7. Hallazgo lateral que resultó ser estructural: la tercera copia

El gate D-013 de `pipeline-events` pedía 70 y estaba en **21,05%**. La causa no era difusa:
`SequenceAssigner` eran 604 de las 769 líneas sin cubrir — 74 ramas y **cero llamadores en
producción**.

Es decir, había **tres** `when (event) -> event.copy(sequence = …)` y no dos. Dos son legítimos
(InMemory y SQLite son adaptadores distintos con paridad afirmada aparte). El tercero no lo era: una
copia especular de una regla que los stores ya aplican, guards en el módulo que BLOCK 2 acababa de
publicar, y cuyo único referent en todo el repositorio era el propio fitness RP-030 leyéndolo para
comprobar que no tenía rama `else`. Un guard que demuestra la ausencia de código muerto en un
fichero que estaba muerto.

Se eliminó, y la fila F5 lo que hace es que la baja se sostenga: afirma que el estampado de
secuencia vive en exactamente dos ficheros y nombra la **forma** del patrón, no el nombre del
fichero, porque el nombre es una foto de hoy. Mutación M12: reintroducir una tercera copia tumba F5
y sólo F5.

## 8. La lección que cuesta más: dónde vive la prueba de un contrato publicado

`pipeline-events` **no tenía ni una prueba de `PipelineEventEnvelopeSerializer`**. El defecto del
§3.1 llegó verde a un contrato publicado porque su única prueba vivía en
`examples/fabric-contract-consumer`, un build separado que no corre en el gate de ese módulo.

Eso no se arregla tapando el número de cobertura. Se arregla poniendo la prueba donde el gate la
mira. Con 101 tests el módulo pasó de 21,05% a **99,30%** con el umbral D-013 **intacto en 70**, y
`EnvelopeCausationLawsTest` ahora afirma las tres cosas que un round trip no ve: que los campos
llegan, que llegan **en el texto** y no sólo en el objeto, y que la forma de cable queda fijada de
forma literal.

`EventVocabularyTest` deriva su guarda de completitud de `sealedSubclasses` y no de una lista
escrita a mano, así que un evento nuevo sin entrada rompe el build. Y su helper reflectivo de
`copy` está anclado contra un `copy` escrito a mano, precisamente para que la prueba de la clase
no se certifique a sí misma.

## 9. Mutaciones, todas con atribución RED 1:1

| # | Mutación | Qué tumba | Restaurada |
|---|---|---|---|
| M1 | el store nombra `RunLifecycle` | 2 filas de B2-1 | por hash |
| M2 | import de events desde el store | **inválida**: no compilaba, luego no era RED | — |
| M3 | el store declara `project(":pipeline-events")` | 2 filas de grafo | por hash |
| M4 | rama `else` en un dispatch exhaustivo | F3 de Rp030 | por hash |
| M5/M5bis | `Map` en un codec | el guard de grep | por hash |
| M7 | data class público nuevo | `apiCheck` de events | por hash |
| M8 | `maven-publish` en un store | fila de publicación | por hash |
| M9 | clase de `events.durable` en el módulo publicado | fila de clases | por hash |
| M10 | `api` → `implementation` | fila de scopes | por hash |
| M11 | publicar `pipeline-events-store` | `verifyPublishedSurface`, nombrando la fuga | por hash |
| M12 | tercera copia de la regla de secuencia | F5 de Rp030, y sólo F5 | por borrado |
| — | fila eliminada del golden | comparación del golden, con la fila en el `expected:` | por hash |
| — | entrada de fixture eliminada | guarda de completitud del vocabulario | restaurada |

M2 queda registrada como inválida a propósito: una mutación que no compila no es una RED, y
contarla como tal habría sido falsear la atribución.

## 10. Auditoría contra el criterio de salida del owner

Once propiedades, cada una con su evidencia mecánica. Donde la propiedad **no** se cumple
todavía, se dice aquí y no en una nota al pie.

| # | Propiedad | Estado | Evidencia |
|---|---|---|---|
| 1 | El consumidor usa artefactos publicados, no `includeBuild`, sources ni checkout | **CUMPLE** | `grep` de `includeBuild\|sourcesJar\|checkout` en `examples/fabric-contract-consumer/{build,settings}.gradle.kts`: cero. El único `project(` del repo es la línea de comentario que explica que no hay ninguno. |
| 2 | El pin identifica versión exacta, digest si existe; nada de `latest` | **CUMPLE** | `verifyFabricContractConsumer` pasa `-PsdkVersion=rootProject.version` = `0.47.0`. No hay `latest` en ninguna parte. **La aspereza que esta fila declaraba está cerrada** (`f1770e37`): el default `0.1.0-SNAPSHOT` se retiró de los **cuatro** builds externos, no sólo del consumer. No podía resolver nada —el repositorio local lleva la versión del proyecto raíz— así que su efecto era convertir la omisión de la propiedad en un fallo de resolución que nombraba una versión inexistente, y `v2/build.gradle.kts:509-515` documenta que ya ocurrió el 2026-09-19. Que fueran cuatro y no uno es la parte: arreglar el consumer y dejar los tres plugins con la misma fragilidad habría dejado la misma clase de fallo en tres sitios más. `sdkRepo` conserva su default porque un directorio por defecto elige *dónde* se busca y uno de versión elige *qué se cree que existe*; sólo el segundo puede fallar en silencio. |
| 3 | `pipeline-output` expone sólo read-side contractual | **CUMPLE** | Dump BCV: 6 tipos (`OutputStreamId`, `OutputCursor`, `OutputPage`, `OutputReadPort`, `OutputReadResult`, `OutputRefusal`). Cero referencias a `output/store`. `SegmentOutputStore`, append, recovery y retention viven en `:pipeline-output-store`, que no se publica. Mutación M8: añadirle `maven-publish` tumba la fila. |
| 4 | `pipeline-events` expone envelope/cursor/read, no `OperationJournal` ni stores | **CUMPLE** | El paquete publicado es `events` + `events.identity`; `events.durable` está en el módulo no publicado. El consumer **no puede** nombrar `OperationJournal` porque la coordenada no existe, y su control negativo lo comprueba en cada ejecución. Mutación M11 lo demuestra por resolución de artefacto. |
| 5 | `pipeline-domain` aporta los tipos semánticos sin crear un segundo modelo | **CUMPLE** | `RunVerdict` del consumer es un `sealed interface` **propio** de 4 casos, derivado de `RunOutcome` por una función exhaustiva sin `else`. Es proyección, no copia: un quinto outcome rompe la compilación del consumer, que es la señal buscada. |
| 6 | BCV / API dump incluye exactamente las superficies públicas nuevas | **CUMPLE** | `bcvModules` cubre los cuatro; `apiCheck` verde en los seis. `EventRefSerializer` (el serializer que hace que `causation`/`correlation` viajen) entró por `apiDump` explícito, no por accidente. Mutación M7 tumba `apiCheck`. |
| 7 | Consumer externo real que compila desde un repo Maven, sin acceso a internos | **CUMPLE CON SALVEDAD** | `examples/fabric-contract-consumer` compila y ejecuta 12 tests contra los cuatro artefactos, con control negativo que falla si aparece cualquier módulo de implementación. **Salvedad honesta:** el repositorio Maven es `v2/build/sdk-repo`, de alcance de build — tiene layout y POMs reales, pero **no está publicado en un repositorio externo**. Para BLOCK 3, Fabric tendrá que apuntar a ese repo o a uno publicado. |
| 8 | Fixtures de conformance versionados: outcomes, paginación de eventos, output/refusals | **CUMPLE** | `contracts.golden`, 21 filas: 4 `RunOutcome`, 6 `OutputRefusal`, 5 `EventQuery`, 2 cursores, 4 de envelope. Comparación **entera** desde una sola llamada, y fuera de `v2` para que el commit que cambia el contrato no lo regenere. Mutación de fila eliminada → RED. |
| 9 | `pipeline-output` NO depende de `pipeline-events`; Fabric combina ambos | **CUMPLE** | `pipeline-output/build.gradle.kts` no declara dependencia alguna a `:pipeline-events`, con el motivo escrito. Ningún módulo publicado depende de un store. |
| 10 | El ROADMAP deja de reclamar controller/worker/Jenkins/Kubernetes | **CUMPLE** | `083c3a82`, §10.1 y §11.1 del ROADMAP. Texto histórico conservado literal en §10.2 y §11.2. |
| 11 | El pin no introduce adapters todavía | **CUMPLE** | `git diff --diff-filter=A` de todo BLOCK 2 contra `adapter\|jenkins\|control-plane`: **cero** ficheros nuevos. Los cuatro nombres que aparecen llevan "Adapter" en el nombre de un test preexistente y cambiaron **una línea** cada uno (imports del split de módulos). |

### Lo que esta auditoría no demuestra

La propiedad 1 dice "**Fabric** consume artefactos publicados". Lo que BLOCK 2 demuestra es que
el contrato **es consumible así**: un build independiente, con su propio `settings`, sin
`project(...)`, sin fuentes y sin composite, compila y ejecuta contra los cuatro artefactos. Que
Fabric efectivamente lo haga es trabajo de BLOCK 3, y su módulo
`worker:pipelinek-runtime-adapter` sigue vacío a la espera de M1 — que es exactamente el sitio
donde esa estrategia de acoplamiento, hoy indecisa por diseño, queda decidida por existir las
coordenadas.

## 11. Estado del gate de cierre (B2-9)

El gate de cierre no llegó a verde a la primera, y la razón es la parte de este recibo que más
conviene leer. **Verde en el informe y rojo en el gate** —el peor modo de fallo, porque ninguna
prueba se ve roja— resultó ser la forma real que toma esta clase de defecto.

### Los dos intentos que fallaron

**Sobre `6058c825`.** `./gradlew check --rerun-tasks --continue`. 4406 tests, 0 fallos, 0 errores,
130 skipped, 325 tareas, 28m53s — y `BUILD FAILED`. Ningún test estaba rojo:
`:pipeline-architecture-tests:test` **se negó a arrancar**.

```text
Task ':pipeline-architecture-tests:test' uses this output of task
':pipeline-step-sdk:api:koverCachedVerify' without declaring an explicit or implicit dependency.
Gradle detected a problem with the following location: 'v2/pipeline-step-sdk/api'
```

**Sobre `6c346680`.** Mismo fallo, más grande. La primera corrección (`f2e14338`) no lo tapó, y esa
es la parte que hace la historia útil: parecía haberlo arreglado cuando no lo había hecho. El
filtro computaba «toda tarea cuya salida solapa con una **entrada declarada** de este test»
(`build.gradle.kts`, `src/main/kotlin`, `api/`), y eso captura `koverCachedVerify` —cuya salida
declarada es el projectDir entero— mientras dejaba fuera cinco tareas que sí-Gradle señalaba:
`compileTestJava`, `compileTestKotlin`, `koverGenerateArtifact`, `koverGenerateArtifactJvm` y `test`.

El motivo está en la línea siguiente del error y se leyó tarde: la *location* que Gradle reporta es
`v2/pipeline-step-sdk/api`, el **projectDir** del módulo, no una de sus salidas. `compileTestKotlin`
escribe bajo `build/classes/`, que no solapa con ninguna entrada declarada, y aparece igual. El
solapamiento que Gradle valida no es entrada-salida; es «este test **usa** una ubicación que cae
dentro de un directorio que otra tarea **reclama como salida**». La única condición que lo reproduce
es «alguna salida cae dentro del projectDir del otro módulo» (`d4e47f57`).

Un control que cubre parte de lo que dice cubrir es peor que ninguno, porque se lee como cobertura.

### La causa que ninguno de los dos tocaba

`WRITING_TASK_NAMES` era una **lista escrita a mano** de once tareas que escriben dentro de un
directorio de módulo. Se eliminó. Se calcula sobre el modelo de Gradle, y la lista no puede
volver a quedarse corta en silencio: el fallo que se equivocaba era exactamente el de una lista
desactualizada, y se leía como un build roto.

### Cifras del gate que cierra

```text
SHA              5a5a56e9210b1a21adad10ecde57b9a6d1e52b1f
argv             cd v2 && ./gradlew check --rerun-tasks --continue
BUILD            SUCCESSFUL in 28m 50s
tareas           329 actionable, 329 executed
tests            4873   fallos 0   errores 0   skipped 140   clases 732
módulos con XML  27
LOG              /var/home/rubentxu/.local/state/pipelinek-gates/b2-9-gate6.log
META             /var/home/rubentxu/.local/state/pipelinek-gates/b2-9-gate6-meta.txt
```

El recuento de módulos necesita el glob **recursivo**: `v2/pipeline-step-sdk/*` son nueve
módulos anidados, y un `*/build/test-results/test/*.xml` desde `v2/` no baja a ellos. Con el
glob plano salen 18 módulos y 4318 tests — un subrecuento de 555 tests y 8 skips que sigue
pareciendo plausible. Los 27 se confirman por dos vías independientes: 27 directorios con XML
y 27 tareas `:test` distintas en el log. Los 732 XML son **todos posteriores** al arranque del
gate (09:27:29): el más viejo es de las 09:27 y el más nuevo de las 09:56, que es
`:pipeline-architecture-tests:test`, la última tarea. Cero XML rancios, que es lo que
convierte el agregado en evidencia y no en decoración.

Módulos con más peso: `pipeline-application` 2340 (121 skipped),
`pipeline-domain` 687, `pipeline-architecture-tests` 456 (10 skipped),
`pipeline-step-sdk:runtime` 201, `pipeline-events-store` 192,
`pipeline-step-sdk:utilities` 119, `pipeline-step-sdk:http` 115,
`pipeline-events` 105. Todos con cero fallos y cero errores.

Cero ocurrencias de `uses this output of task` en el log. `apiCheck` pasó en los
cinco módulos con dump, incluidos `pipeline-credentials-api`, cuyo ABI recoge el
`readSlice` heredado que D2 añadió a `EventStore`.

El gate corrió con este documento sin commitear, y con una corrección a la frase que
decía que era el único cambio: no lo era. Dos minutos después de arrancar el gate,
`HEAD` se movió de `5a5a56e9` a `4b11e0c7`, que añade `EVENT_SLICE_READ_AUTHORITY_RECEIPT.md`.
Entre el árbol realmente compilado y el de este commit hay, por tanto, **dos** ficheros
markdown sin trackear y ningún otro cambio. La diferencia son documentos, no bytes de build,
pero se escribe en voz alta porque "el único cambio es un documento" es exactamente la
afirmación que se cita después como garantía y que deja de ser cierta en cuanto alguien
commitea a mitad de un gate.

### El consumidor externo, ejecutado de verdad

```text
argv     v2/gradlew -p examples/fabric-contract-consumer --console=plain \
           -PsdkRepo=v2/build/sdk-repo -PsdkVersion=0.47.0 check --rerun-tasks
tests    FabricGoldenContractTest  12 F=0 E=0 S=0  ts=2026-10-05T07:57:41.846Z
         PublishedReadSurfaceTest   2 F=0 E=0 S=0  ts=2026-10-05T07:57:42.012Z
         14 F=0 E=0 S=0
exit     0  ("6 actionable tasks: 6 executed")
```

### La distribución instalada (T3)

```text
argv     ./examples/run.sh
bin      v2/pipeline-application/build/install/pipelinek/bin/pipelinek
         sha256 225a61dbdf61a20a58292803688a1444f85337eae8147f2938db118b7dc97976  (09:28, dentro del gate)
distZip  pipelinek-0.47.0.zip  91850423 bytes  oct 5 10:01
         sha256 d19458894c4f183f0befd0f297b9b7f794dd463eab426662d068f44c3c1754fb
log      /var/home/rubentxu/.local/state/pipelinek-gates/t3-installed-uat.log
```

Diez de diez fixtures contra el binario instalado: `01`–`04` success · `05` exit=1
outcome=failure · `06` success · `07` outcome=unstable con dos `CatchErrorTriggered` en
orden innermost-first · `08` reuse del agregado terminal (0 eventos de rama, 0 de step) ·
`09` `RetryAttemptFinished` failed→succeeded · `10` exit=1 outcome=failure con
`TimeoutScheduled`. `06`, `08` y `09` corrieron dos veces contra la **misma** `--db` y el mismo
`--control-root`. Los cuatro contratos tipados de `examples/contracts/` pasaron la paridad
diferencial. `T3_EXIT=0`.

### Las tres trampas de `UP-TO-DATE`: un verde que no ejecutó nada

Las tres salieron al cerrar este gate, y las tres habrían producido un recibo afirmativo sin
que un solo test corriera. Se registran porque son la misma clase de fallo y porque las tres
eran silenciosas:

1. **`:verifyFabricContractConsumer` salió `UP-TO-DATE`** en su primera invocación. Gradle
   comprobó que sus entradas no habían cambiado, y no compiló ni ejecutó el consumer. La tarea
   es `Exec` y su salida es un jar que ya existía: el chequeo de entradas no dice nada sobre
   si los 14 tests corrieron ahora.
2. **`--rerun-tasks` no propaga al build anidado.** Forzado sobre la tarea externa, republicó
   los cuatro artefactos y ejecutó `verifyPublishedSurface`, pero dentro del build del consumer
   `:test` salió `UP-TO-DATE`, porque el `commandLine` de la tarea no lleva `--rerun-tasks`. Los
   XML del consumer seguían fechados a las 08:42, de la sesión anterior. Hubo que invocar el
   build del consumer directamente para que ejecutara.
3. **`pipelinek-0.47.0.zip` era rancio**: del 4 de octubre a las 22:24, nueve días y siete
   commits por detrás. `installDist` sí era `UP-TO-DATE` legítimo —su jar se había construido
   a las 09:28, dentro del gate—, pero el `distZip` no, porque su up-to-date se afirmaba sobre
   un artefacto viejo. Tuvo que reconstruirse con `--rerun-tasks`.

Un `UP-TO-DATE` en la evidencia significa "no lo he comprobado", nunca "está bien". Es la
misma forma que el gate falló tres veces —verde en el informe, rojo en la ley— y por eso se
anota aquí en lugar de descubrirse otra vez.

## 12. Lo que NO se hizo, y por qué

- **No se tocó ningún umbral.** D-013 sigue en 70 para `pipeline-events` y en 70 para
  `pipeline-event-harness`. Los dos se cerraron con pruebas, no ajustando el número.
- **No se tocó `causation`/`correlation` para que dejaran de estar en el contrato.** El defecto
  era que no se escribían, no que existieran: se arregló escribiéndolos.
- **No se rebajó el golden.** Se regeneró una vez, con el valor calculado a la vista, y a partir de
  ahí la comparación es entera.
- **No se cableó el consumer a `check`.** Lo hace sólo cuando alguien lo ejecuta, igual que los
  tres builds de plugins externos que ya existían. Es coherente con ellos y evita que `check`
  publique, pero significa que es tan fiable como quien lo lanza: por eso este recibo lo nombra
  como paso obligatorio del gate en vez de dejarlo a la costumbre.
- **No se arregló `examples/run.sh` sin decir que estaba roto.** Al ejecutar T3 resultó que su
  `BIN` apuntaba a `install/pipeline-application/bin/pipeline-application`, que no existe desde
  que la distribución se renombró a `pipelinek`. El harness comprobaba la existencia del
  binario, lanzaba `installDist` —que salía `UP-TO-DATE` y no reconstruía nada— y después
  invocaba una ruta inexistente. Es decir: T3 llevaba tiempo sin poder ejecutarse, y un harness
  que no puede ejecutarse se lee igual que un harness que nadie ha ejecutado. La corrección
  lee `applicationName` del build script en vez de repetir el nombre, porque un hardcode de
  ese nombre es exactamente lo que se rompió. El defecto y su corrección son un commit
  aparte, no una línea de este recibo.
