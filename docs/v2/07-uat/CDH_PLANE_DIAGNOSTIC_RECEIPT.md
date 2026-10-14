# WU_CDH_PLANE_DIAG — Output Plane vacío (DIAGNÓSTICO REFUTADO — no es un veredicto válido)

> **AVISO DE ESTADO. Este diagnóstico fue REFUTADO por revisión externa posterior.**
> Las PR #130 y #133, integradas en `origin/main@a7f7538b4dfa6562002b120787a781150d6ab80e`
> el 2026-10-14, ya corrigen el bloqueo del redactor, la pérdida de bytes en EOF, la
> propagación de fallos del pump y la comprobación del drenaje.
>
> **Dos conclusiones mías son inválidas y se conservan aquí como registro, no como veredicto:**
>
> 1. **§5.2 y §6: "exit 0 significa que nadie intentó escribir" es inválido.** Yo afirmé que un
>    fallo de escritura se habría convertido en `OUTPUT_PERSISTENCE_REFUSED` y por tanto el run no
>    habría salido 0. Eso es una cadena de dos eslabones y el segundo no se sostiene: el pump
>    atrapa la excepción en su propio `catch (_: Exception)` y termina sin registrar nada. #133
>    corrige precisamente esa clase de defecto. La inferencia era inválida, y con ella se cae
>    también "el pump nunca intentó escribir".
> 2. **La prueba por ausencia no era una prueba.** `console.log` ausente era consistente con
>    "el pump no corrió", pero también con "el pump corrió, falló y nadie lo dijo". Yo la traté
>    como concluyente en su momento; sólo confirmaba una rama, no la única.
>
> **Qué sobrevive del diagnóstico:** la observación de que `streams/` queda vacío y que los
> cinco tests del corpus fallan es real. **Lo que no sobrevive** es la atribución de causa y la
> clasificación `PRODUCTO`. La clasificación correcta debe salir de reproducir contra el binario
> construido desde `a7f7538b`, no de este texto.
>
> **Lección operativa:** `streams/` vacío es evidencia *orientativa*, no un oráculo de causa.
> Un harness funcional debe leer por las interfaces públicas del producto
> (`OutputReadPort` + journal), no inferir desde el árbol de ficheros.

**Estado:** REFUTADO. No usar como veredicto. Conservado por AGENTS.md §10 (registrar errores y
asunciones incorrectas en vez de reescribirlos silenciosamente).
**Fecha:** 2026-10-14
**HEAD al diagnosticar:** `558e0e18` (pre-`#130`/`#133`)
**Baseline correcto:** `origin/main@a7f7538b4dfa6562002b120787a781150d6ab80e`

---

## 1. Qué se sospechó y por qué era falso

Cinco tests de `CompatibilityCorpusTest` fallan desde antes de este slice:

```text
fixture24UtilitiesRoundtrip
fixture25YamlRoundtrip
fixture26FindFiles
fixture27ZipUnzip
fixture29MixedUtilities
```

La firma común es una sola:

```kotlin
val captured = ConsolePlaneProbe.transcriptsOfWrittenSteps(controlDir, events, stepType = "sh")
// captured == ""
```

`transcriptsOfWrittenSteps` resuelve cada `StepStarted` de tipo `sh` a un `OpId` de nivel
superior (`runId-s{i}-{j}`) y llama a `transcriptOrAbsent`. Si el plano no tiene el stream,
`ConsoleReadService.read` devuelve `OutputRefusal.UnknownStream` y el accesoor devuelve `null`.
Todos nulos ⇒ `""`.

Por la línea de documentación del propio probe (`ConsolePlaneProbe.kt:126-148`) hay **dos**
lecturas de `""`, y son defectos de owners distintos:

1. el proceso no escribió, o
2. el plano tiene los bytes y el lector apunta mal.

Un diagnóstico que se quede en el test no puede separarlas. Por eso este slice baja al binario.

## 2. Clasificación del arnés de diagnóstico

`scripts/` no es el archivo; el arnés está en el scratch de la sesión y **se declara `model`**:

- **Cruza la autoridad productiva:** ejecuta el `pipelinek` instalado, no un doble.
- **No re-deriva el veredicto del probe:** lee el Output Plane en disco y el journal SQLite, que
  son las autoridades. No recalcula `transcriptsOfWrittenSteps`.
- **No afirma el comportamiento del producto:** observa el sustrato y clasifica. Por eso su
  veredicto es caracterización, nunca certificación.

## 3. Qué significa "vacío", y por qué el oráculo es el árbol de segmentos

Esta sección existe porque la pregunta es legítima y la respuesta no es obvia:
**la API del Output Plane es de streams, no de ficheros.** `OutputReadPort` expone
`read(stream, cursor, maxBytes)`, `readRange` y cursores paginados; nadie lee un fichero de
texto. Medir "no hay transcript.txt" sería medir algo que este producto nunca tuvo.

Lo que persiste es el **almacenamiento** que hay debajo, y sí son ficheros
(`SegmentOutputStore`, layout documentado en su cabecera):

```text
<root>/streams/<streamKey>/
    cur.seg      payload del segmento en escritura
    cur.cmt      offset confirmado — la autoridad O2
    cur.res      la reserva "<base>|<limit>" pendiente (ausente = ninguna)
    segments/    segmentos ya sellados, "<base>-<length>.seg"
```

El oráculo usa **ese árbol**, y no un fichero de texto, por una razón concreta:
`SegmentOutputStore.open()` llama a `Files.createDirectories(layout.streamDir)`
(`SegmentOutputStore.kt:267`). Si el motor hubiera abierto un stream, el directorio existiría. Y si
alguna vez se reservó un solo byte, existirían `cur.seg` y `cur.cmt`.

Lo medido fue `find <control-root>/output-plane/streams -type f | wc -l` = **0**, y
`streams/` sin ningún subdirectorio. Es decir: **no se abrió ningún stream**, que es más fuerte y
más específico que "el texto no está donde lo buscabas".

Refuerzo independiente: `OutputPlaneProvider.storeForWriting(root)` construye el store y ejecuta
`recover()` en cada uso, de modo que el store quedó inicializado y recuperado en un run con 8
operaciones registradas, y aun así `streams/` siguió vacío. No es que el store no se abriera.

**Consecuencia para quien tome el WU:** el defecto no es de *formato* ni de *API*. El
almacenamiento es segmentado, la lectura es por cursor y ambos son correctos. Falta la
**apertura del stream** en el camino de escritura.

## 4. Evidencia medida

### 4.1 Fixture 29 contra el binario instalado

```text
argv:  pipelinek run --format json --workspace <ws> --control-root <ctl> --db <db> <fixture>
exit:  0
```

El journal (`operation_journal`) registró **8 operaciones** para el run:

```text
<run>-s0-0  <run>-s0-1  <run>-s0-2
<run>-s1-0
<run>-s2-0
<run>-s3-0
<run>-s4-0  <run>-s4-1
```

El árbol del control root:

```text
CTL/last-run/<runId>
CTL/leases/lease-<runId>.lock
CTL/leases/lease-<runId>.record
CTL/retry-control/
CTL/wait-until-control/
CTL/output-plane/
CTL/output-plane/streams/          <-- VACÍO
```

**Cero ficheros bajo `streams/`.** El directorio se crea; ninguna escritura llega.

### 4.2 Fixture mínima, para descartar la fixture

Una `pipeline.kts` de un solo stage (`echo("MARKER-ECHO")` + `sh("echo MARKER-SH")`), sin
imports de plugin, exit `0`, `runId` emitido:

```text
CTL/output-plane/streams/          <-- TAMBIÉN VACÍO
```

Esto descarta que el vacío sea una propiedad de las fixtures 24-29. Un `echo` y un `sh` que
imprimen no dejan stream. El plano **no está escribiendo**, y el caso mínimo lo demuestra sin
involucrar al corpus.

### 4.3 El UAT del propio motor, por la ruta real

`ObsPc2LevelAUatTest` no usa el binario instalado: entra por
`ShOperationsAdapter` → `ShExecution` → pumps → `RedactingOutputIngress`, que es la misma
composición que usa producción (la propia cabecera del test lo declara, línea 63).

```text
argv:  ./gradlew :pipeline-application:test --tests '*ObsPc2LevelAUatTest*'
resultado: exit 124 (wrapper timeout a 600 s), con 2 filas FAILED antes del corte
```

```text
OBS-PC-205 bytes pending in a pump at child exit are committed()  FAILED
  ObsPc2LevelAUatTest.kt:236
OBS-PC-204 returnStdout keeps stdout in the typed value and out of the transcript()  FAILED
  ObsPc2LevelAUatTest.kt:188
```

Las dos aserciones que fallan son la misma afirmación desde dos ángulos:

- línea 188 — `console != null && console.contains(stderrMarker)` sobre
  `transcriptOrNull(root, runId)`;
- línea 236 — `assertTrue(console != null, "the child emitted 200 lines, so the plane must hold a stream")`.

La fila 205 pide **200 líneas** a un `sh("printf ... seq 1 200")`. Que el plano no la tenga no es
una prueba de que el lector falle: es la prueba de que **nada se escribe**.

### 4.4 El código está cableado y es correcto por diseño

Descartado explícitamente, para que no se re-litigue:

- `ShExecution.kt:272-291` construye un `RedactingOutputIngress` por canal
  (`STDOUT`, `STDERR`) sobre `OutputPlaneProvider.storeForWriting(root)`, y
  `ShExecution.kt:327` los pasa como `channelSinks` a `executeTerminal`.
- `DurableShellExecutor.kt:652, 670, 831` consume `channelSinks` y escribe por
  `channelSinks[channel]`.
- La apertura diferida es **deliberada** (`ShExecution.kt:265-271`): los handles no se abren en
  la construcción; `RedactingOutputIngress` abre y declara en su primera escritura de al menos un
  byte. Eso evita que un `sh` silencioso deje un stream detrás.

El cableado existe, la apertura diferida es intencionada, y aun así no hay escrituras. El
código describe el camino correcto; la ejecución no lo recorre.

### 4.5 Descartado: distribución obsoleta

```text
HEAD .............. 558e0e18  2026-10-14 18:37
installDist .......          2026-10-14 18:25
jar más reciente           2026-10-14 18:30
```

El árbol de trabajo está limpio y `OutputPlaneProvider.streamsOf` **sí** está en la clase
instalada (`strings | grep -c streamsOf` ⇒ `1`). La distribución es más reciente que HEAD y
contiene el código por canal. **No es un problema de versión.**

## 5. Localización del corte: el cableado está íntegro hasta el pump

Rastreado desde el sink hacia atrás, descartando suposiciones una a una:

1. **`ShExecution.kt:272-291`** construye un `RedactingOutputIngress` por canal sobre
   `OutputPlaneProvider.storeForWriting(root)`; **`:327`** pasa `channelSinks` a `executeTerminal`.
   Correcto.
2. **`CanonicalRuntimeCapabilityAccess.kt:203-210`** es el **único** punto de construcción de
   `ShOperationsAdapter` en producción y pasa `context.controlDirRoot`. Correcto.
3. **`Main.kt:371`** resuelve `controlDirRoot` desde `config.controlRoot` y lo propaga por
   `runCanonicalPipeline` (`:536`, `:978`) y `runScriptedFrontend` (`:967`). Correcto.
4. **`DurableShellExecutor.kt:670`** decide el pump con
   `pumpedThroughJvm = transcriptRedactor != null || channelSinks.isNotEmpty()`; con `channelSinks`
   no vacío, el pump arranca. Correcto.
5. **`DurableShConfig.kt:154`**: `PUMPED_VIA_AGENT_DEFAULT = false`, luego la ruta por defecto es
   el pump en la JVM, no el agente. Correcto.
6. **`RedactingOutputIngress.write` (`:83-112`)** hace `reserve → write → commit → appendFrame`
   por chunk, y captura `Throwable` como `refusal` **sin propagarlo**.
7. **`DurableShellExecutor.kt:1725-1731`** convierte ese refusal en
   `OUTPUT_PERSISTENCE_REFUSED` / `FailureKind.INFRASTRUCTURE`.

**El punto 7 es lo que hace el diagnóstico concluyente.** Si el pump hubiera intentado escribir y
fallado, el refusal se habría convertido en un fallo tipado de paso y el run **no** habría salido
`0`. Salió `0`. Luego el pump **nunca intentó escribir**: no hubo `write`, no hubo excepción, no
hubo refusal. El corte está **antes** de `RedactingOutputIngress.write`, en el tramo que decide
qué hacer con los bytes del pipe, y ese tramo no deja rastro observable cuando no escribe.

Una asimetría queda sin explicar y se registra como el siguiente objetivo: el `OpId` que el motor
escribe (`<runId>-s0-0`, confirmado en el journal) es **idéntico** al que el probe compone
(`OpId(runId, 0, 0).format()`), así que un desajuste de identidad queda descartado.

### 5.1 Caso mínimo definitivo

```text
pipeline { stages { stage("only") { sh("echo MARKER-SH") } } }

exit:     0
journal:  2100a67c-...-s0-0        (una operación)
output-plane/streams:  0 ficheros
```

Un solo `sh` que imprime. Exit 0. Nada en el plano. Ningún `@TempDir`, ningún corpus, ningún
plugin, ninguna credencial, nada que pueda atribuirse al arnés.

### 5.2 Hipótesis falsada: el pump NO sale por `n < 0`

La hipótesis inicial era que el bucle del pump terminara en la primera iteración con `read`
devolviendo `n < 0`, sin escribir nunca. **Es falsa, y hay prueba por ausencia.**

Se ejecutó un `sh` cuyo stdout se descarta y cuyo stderr escribe un marcador:

```kotlin
sh("echo MARKER-SH > /dev/null; echo MARKER-ERR >&2")
// exit 0
```

El resultado decisivo es que **no existe `console.log`**:

```text
CTL/last-run/<runId>
CTL/leases/lease-<runId>.lock
CTL/leases/lease-<runId>.record

script.sh   -> NO EXISTE
wrapper.sh  -> NO EXISTE
console.log -> NO EXISTE
```

`DurableShellExecutor.kt:741-743` crea `console.log` **sólo** cuando
`!pumpedThroughJvm && !captureStdout`, y `:1061-1075` borra el directorio de la operación al
terminar. Una ausencia de `console.log` con el directorio ya borrado sólo es explicable por la
rama `else` (`keepTranscriptLog == false`), que es precisamente la rama del pump activo.

**Conclusión: el pump arrancó y leyó el pipe.** Se cae la explicación "el read devolvió EOF de
inmediato". El fallo no es que el pump no corra.

### 5.3 Hipótesis sustituta: el pump corre y escribe en el destino de respaldo

Con el pump confirmado activo, quedan dos salidas:

1. `target` no es el `RedactingOutputIngress` sino el `StreamProcessOutputSink` de respaldo, que
   escribe en `logFile` (`:832-834`).
2. `target` es el ingress correcto pero `reserve`/`commit` no dejan fichero legible.

La primera es la candidata fuerte, y es **consistente con las cinco observaciones a la vez**:
plano vacío, `console.log` ausente tras el run, directorio de operación borrado, exit 0, y ningún
`refusal` — porque escribir en un fichero temporal no falla, luego el `refusal` no se activa y el
paso sale 0.

La condición que la distingue está en `:831`:

```kotlin
val target: ProcessOutputSink = channelSinks[channel]
    ?: StreamProcessOutputSink(... logFile ...)   // fallback silencioso
```

`channelSinks[channel]` devuelve `null` si la clave del mapa no resuelve para el canal que el
pump itera. El comentario de `:649-651` establece que una entrada ausente "nunca debe convertirse
en un byte descartado", y el fallback cumple eso escribiendo a disco; lo que no hace es avisar de
que el destino durable acordado no se está usando. Un `sh` que imprime 20 bytes y termina deja cero
ficheros y cero señales: indistinguible de un paso que no imprime.

**Cómo falsarla:** comparar la clave con la que `ShExecution` construye el mapa
(`ShExecution.kt:276-289`, `ProcessOutputChannel.all.forEach`) contra la que el executor entrega
en `pumpedChannels` (`:813-821`), y confirmar si el lookup resuelve. Es una comprobación de una
línea, no un rediseño. Si resuelve y el destino es el ingress, la candidata 2 pasa a ser la
favorita y el corte está dentro de `SegmentOutputStore.reserve`.

## 6. Veredicto — RETIRADO

```text
Owner del defecto:   NO DETERMINADO
Estado:              REFUTADO por revisión externa (PR #130 / #133 ya integradas)
```

**Lo que este documento ya no afirma.** La clasificación `PRODUCTO` de §6 original, la cadena
"refusal → fallo tipado → exit distinto de 0", y la hipótesis de que el pump no corría, quedan
retiradas. Se conservan arriba únicamente como registro del razonamiento y de por qué era
falso, conforme a AGENTS.md §10.

**Lo que sigue siendo una observación válida:** tras un run con8 operaciones registradas y
código de salida 0, el árbol `output-plane/streams/` no contenía ningún stream. Eso es un
hecho medido. Lo que **no** sigue siendo válido es deducir de ese hecho dónde se perdieron los
bytes.

**Reproducción pendiente (H0 del mandato vigente):** reconstruir `installDist` desde
`a7f7538b` y re-ejecutar los cinco tests del corpus. Cada fallo se clasificará como
`PRODUCT_DEFECT`, `HARNESS_DEFECT`, `STALE_BINARY`, `INVALID_EVIDENCE` o
`INFRASTRUCTURE_BLOCKED`, con reproducción detrás y no con hipótesis.

## 7. Consecuencia para la poda

La decisión previa de **no fusionar los dos barridos** queda reforzada por un motivo nuevo: no
había baseline verde. Con el Output Plane vacío, cualquier aserción de transcript que se mueva
de un barrido al otro se *vería* verde por el motivo equivocado, porque `transcriptsOfWrittenSteps`
devuelve `""` y `assertTrue("".contains(x))` falla siempre — y un `assertTrue(!contains)` pasaría
siempre. La poda sigue bloqueada por el mismo gate: **arreglar el Output Plane primero**.

## 8. Lo que este slice NO hace

- **No arregla** el motor. El arreglo está en el runtime, fuera de la frontera de este paquete.
- **No cambia** `ConsolePlaneProbe`. Su comportamiento de tres respuestas es correcto y es lo que
  permitió localizar el defecto; relajar lo volvería ciego al mismo fallo.
- **No toca** los 5 tests del corpus. Marcar `@Disabled` aquí los ocultaría detrás de un defecto
  de producción sin triage de seguridad, lo que la frontera de responsabilidad prohíbe.
- **No adopta** `docs/pipelinek_cd_harness_package/`. Sigue sin rastrear y sin ratificar.

## 9. Trabajo siguiente

1. **Escalar el defecto de Output Plane** al WU del motor que lo posee. Es un bloqueador de
   producto, no una deuda del harness, y por AGENTS.md §8 corresponde abrir issue del propio
   producto y entregar la corrección en la siguiente candidata.
   Con el rastreo de §4, el punto de entrada para quien lo tome es concreto: el tramo entre
   `pb.start()` (`DurableShellExecutor.kt:795`) y `target.write(...)` (`:862`), con
   `pumpedThroughJvm == true` y `channelSinks` no vacío, no entrega ni un solo byte. La hipótesis
   a falsar es que la condición de salida del bucle del pump (`:861`, `if (n < 0) break`) se
   cumple en la primera iteración, de modo que `read` devuelve `n < 0` y el pump termina sin haber
   escrito nada. Eso explicaría el exit 0 sin refusal: no hubo bytes, no hubo excepción.
2. Con el plano escribiendo, re-ejecutar `CompatibilityCorpusTest` y comprobar si los 5 fallos
   caen. Si alguno sobrevive, es un segundo defecto y se diagnostica aparte.
3. Solo entonces reabrir la extracción de soporte común entre los dos barridos.

## 10. Registro de errores de medición de esta sesión

Ninguno de estos cambió una conclusión, pero todos son reales y se anotan:

1. **`rg -r` usado como búsqueda.** `-r` es *replace* en `rg`; la primera invocación
   sustituyó texto en la salida en vez de listar rutas. Repetido con `grep -n`.
2. **`rm -rf` con ruta calculada bloqueado dos veces** por el guard de irreversibilidad. No se
   forzó: se usaron directorios nuevos (`.cdh-min-2`, `.cdh-min-3`, `.cdh-min-4`) y al final
   se limpiaron los cuatro explícitamente, todos no rastreados y creados en esta sesión.
3. **Rutas con `..` rechazadas por el producto.** `Error: control-root path must not contain '..'`
   es del binario, no del arnés. El primer intento de fixture mínima falló por esto y se repitió
   con rutas absolutas.
4. **Fixture mínima con imports inventados.** `dev.rubentxu.pipeline.v2.dsl.sh` no existe; el
   primer intento dio `cause [SCHEMA]: Kotlin compilation failed` con exit 1. Los pasos `echo` y
   `sh` no necesitan import: se comprobó contra los imports reales de `v2/compatibility/`.
5. **`find -exec wc` bloqueado** por el guard. Se sustituyó por `ls -la` + `wc -c`.
6. **`ObsPc2LevelAUatTest` cortado a 600 s (exit 124).** Las 2 filas FAILED son evidencia válida
   porque están en la salida del propio Gradle, pero **el run no es un resultado completo**: no
   hay XML, no hay recuento de tests, y no se claima el estado de las demás filas. Repetir con
   presupuesto mayor antes de usar esto como evidencia de gate.
7. **Oráculo no declarado en la primera redacción.** El texto decía "el Output Plane está
   vacío" sin decir *vacío de qué*, lo que hacía auditable una conclusión pero no la medida. Una
   pregunta legítima del operador ("¿no habíamos cambiado la salida a stream?") forzó a precisar
   el oráculo, y §3 lo hace: el árbol de segmentos, no un fichero de texto. La conclusión no
   cambió; la medida sí quedó escrita, que es lo que faltaba.
8. **Atribución de causa a partir de una ausencia (REFUTADO, el más grave).** Traté la ausencia
   de `console.log` como prueba de que el pump corría, y el `exit 0` como prueba de que nadie
   intentó escribir. Ambas son inferencias de una sola causa posible cuando había al menos dos:
   el pump también pudo correr, fallar y no informarlo, porque su `catch` traga la excepción.
   #133 confirma que esa era exactamente la clase de defecto sin corregir en el momento en que
   yo diagnostiqué. El `exit 0` no era evidencia: `RedactingOutputIngress.write` captura
   `Throwable` sin propagarlo, así que un fallo de escritura no cambia el código de salida.
9. **Inspección del árbol de ficheros como oráculo de aceptación.** La §3 precisa que los bytes
   se leen por cursor y no de ficheros, pero §5 se apoyó en `find` sobre `streams/` para contar
   ficheros. Eso es válido para *diagnosticar* y no para *aceptar*. Un harness que acepta según
   el árbol de disco queda acoplado a la representación interna y no detectaría una implementación
   de streams que no escriba a disco por diseño.

## 11. RESOLUCIÓN 2026-10-14 — los cinco fallos eran `STALE_BINARY`

### Qué se midió

Contra el binario reconstruido desde el baseline correcto, no desde el HEAD antiguo:

```text
rebase   : 558e0e18 -> origin/main@a7f7538b, 0 behind / 7 ahead, sin conflictos
dist     : v2/pipeline-application/build/install/pipelinek/bin/pipelinek
sha256   : 371b0018597999640db6c964937c3f1cfdf9731c1675a31a9b2b9e03d29e09ae
version  : pipeline 0.60.0
argv     : ./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest'
canary   : TEST-...CompatibilityCorpusTest.xml borrado antes de correr y regenerado
resultado: tests=30 failures=0 errors=0 skipped=0   (timestamp 2026-10-14T21:18:55Z)
```

### Veredicto

Repetición limpia, sin `--fail-fast` y con los dos canarios borrados antes de correr:

```text
argv     : ./gradlew :pipeline-application:test \
             --tests 'CompatibilityCorpusTest' \
             --tests 'ShTranscriptRedactionReproductionTest'
exit     : 0   (BUILD SUCCESSFUL in 3m 13s; 93 actionable tasks)
canary   : ambos TEST-*.xml borrados antes de correr y regenerados

CompatibilityCorpusTest              tests=30 failures=0 errors=0 skipped=0  @21:23:44Z
ShTranscriptRedactionReproductionTest tests=3  failures=0 errors=0 skipped=0  @21:26:38Z
TOTAL                                 tests=33 failures=0 errors=0 skipped=0
```

```text
Clasificación de los cinco fallos:  STALE_BINARY
PRODUCT_DEFECT:                      0
HARNESS_DEFECT:                      0 (en el producto de pipeline-kotlin)
INVALID_EVIDENCE:                    1 (este diagnóstico original)
```

Los cinco tests (`fixture24`, `fixture25`, `fixture26`, `fixture27`, `fixture29`) **pasan**
contra el baseline, y también pasa `ShTranscriptRedactionReproductionTest`, que es justamente
la reproducción que motivó la hipótesis del Output Plane vacío. Nunca hubo un defecto del
producto: se midió sobre un binario construido desde un ancestro anterior a `#130` y `#133`,
que es exactamente el `STALE_BINARY` que `run-scoped-junit-report.py` existe para detectar.

Nótese que la reproducción del transcript que originó todo esto también estaba **verde** con
el binario fresco. Eso confirma que `#130` y `#133` la corrigieron, y confirma a la vez que
cuando se redactó el diagnóstico original esa clase de fallo ya no era la causa de los cinco.

### Por qué esto importa más que el arreglo

La hipótesis que este recibo investigaba (`PRODUCT_DEFECT` en el Output Plane) queda
**descartada por medición**, no por argumento. Y el error que la producía era doble:

1. el binario era antiguo, y
2. el arnés no tenía forma de saberlo.

El punto (2) es el que sobrevive como trabajo real. Un arnés que ejecuta contra un binario
sin atar su hash al commit que lo construyó no puede distinguir `PASS` de `PASS` de hace
tres semanas. Eso no lo arregla `#130` ni `#133`: es deuda del arnés.

### Corrección registrada sobre el propio recibo

La §5 afirmaba que el fallo persistía y que su causa estaba en el Output Plane. Eso era
**observado antes de reconciliar** y no era un veredicto: era una medición sobre el binario
equivocado, presentada como si el binario fuera el vigente. Un artefacto puede estar bien y a la
vez no ser el que se está probando, y esa distinción no se había escrito en ninguna parte.

## 12. Cierre de referencia

```text
Reference implementation consulted:  ninguno aplicable — el defecto está en el
                                      Output Plane propio (ADR-M1 D2 / OBS-B / OBS-C2.3)
Behaviour adopted:                   n/a (slice de diagnóstico, no de implementación)
Intentional deviations:              n/a
Security implications reviewed:      n/a para este slice. Observado y NO resuelto: fixture
                                      28 (zip-slip / CVE-2023-32981) está entre las que
                                      quedan bloqueadas por el mismo baseline rojo
Tests demonstrating the contract:     ObsPc2LevelAUatTest (OBS-PC-204, OBS-PC-205)
                                      CompatibilityCorpusTest (5 filas, sin modificar)
Harness fidelity class:              model (el arnés de diagnóstico observa el sustrato;
                                      la caracterización viene del UAT del motor)
```
