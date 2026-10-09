# OBS-F — Recibo: SDK read-side publicado, consumidor real de Fabric, y coste del troceado

**Ámbito:** las tres cláusulas de OBS-F que se pueden certificar desde este repositorio.

- **Árbol de evidencia:** `par/cli-observation` @ `6254d6c8`, árbol limpio salvo el fichero de
  medición que este recibo acompaña.
- **Qué NO certifica este recibo:** ninguna afirmación sobre el PRODUCT-GATE, ninguna UAT contra la
  distribución instalada, y ninguna afirmación de que Fabric consuma hoy el camino que este
  documento demuestra. Ver §4 y §6, donde lo no ejecutado está escrito como `NOT_RUN`.

---

## 1. Publicación del SDK read-side — VERIFICADO

`publishSdkForExternalPlugin` publica cuatro contratos desde **este** árbol:
`pipeline-domain`, `pipeline-scripting-api`, `pipeline-events`, `pipeline-output`.

```text
cd v2 && ./gradlew publishSdkForExternalPlugin --console=plain --rerun-tasks
→ BUILD SUCCESSFUL in 19s · 20 actionable tasks: 20 executed
```

Artefacto resultante:

```text
v2/build/sdk-repo/dev/rubentxu/pipeline/v2/pipeline-output/0.47.0/pipeline-output-0.47.0.jar
```

### 1.1 Una corrección que cambia la conclusión

Una auditoría intermedia concluyó que `OutputChannel`, `OutputFrame`, `OutputFrameIndex` y
`OutputTailState` **no existían** en el artefacto publicado, y que por tanto la separación de
canales y la transición running→sealed no tenían soporte upstream.

**Esa conclusión era falsa y no se sostiene.** El jar inspeccionado era
`../pipeline-kotlin/v2/build/sdk-repo/…`, es decir el artefacto del **repositorio oficial** en
`b6e1ab49`, no el de esta rama. La inspección correcta es la de este árbol:

```text
dev/rubentxu/pipeline/v2/output/OutputChannel.class
dev/rubentxu/pipeline/v2/output/OutputFrame.class
dev/rubentxu/pipeline/v2/output/OutputFrameIndex.class
dev/rubentxu/pipeline/v2/output/OutputTailPort.class
dev/rubentxu/pipeline/v2/output/OutputTailState.class
dev/rubentxu/pipeline/v2/output/OutputTailState$Open.class
dev/rubentxu/pipeline/v2/output/OutputTailState$Sealed.class
dev/rubentxu/pipeline/v2/output/OutputStreamAddress.class
dev/rubentxu/pipeline/v2/output/OperationOutputStreams.class
```

Firmas que sostienen las tres leyes que Fabric necesita:

```text
OutputTailPort.tailState(stream): OutputTailState          → running→sealed
OperationOutputStreams(stdout, stderr).select(Set<OutputChannel>) → filtrado por canal
OutputFrameIndex.framesOfRun(run, afterOrdinal, limit)       → metadata de frames, en orden observado
OutputReadPort.read(stream, cursor, maxBytes)                → cursor de bytes
```

**Por qué importa el error.** `exclusiveContent` en Fabric significa que una resolución correcta es
evidencia de que las coordenadas resolvieron. Anclarse a un `build/` de otro checkout rompe esa
cadena en silencio: el jar resuelve, compila y pasa, y lleva un contrato de hace varias revisiones.
Un consumidor que certifique contra `../pipeline-kotlin/v2/build/sdk-repo` sin fijar el SHA está
certificando el checkout equivocado. Es la misma clase de fallo que un recibo de una versión
anterior heredado hacia `main`.

---

## 2. Consumidor real de `pipelinek-fabric` — VERDE CONTRA ESTE JAR

Ejecutado desde el checkout de Fabric, apuntando explícitamente al artefacto de esta rama:

```text
cd PipelinekFabric
./gradlew :worker:pipelinek-runtime-adapter:test :testkit:spikes:test \
  -PpipelineKSdkRepo=/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2/build/sdk-repo \
  --console=plain --rerun-tasks
→ BUILD SUCCESSFUL in 1m 45s · 17 actionable tasks: 17 executed
```

| módulo | clases | tests | fallos | errores | skips |
|---|---|---|---|---|---|
| `:worker:pipelinek-runtime-adapter` | 11 | 62 | 0 | 0 | **9** |
| `:testkit:spikes` | 7 | 27 | 0 | 0 | 0 |
| **total** | **18** | **89** | **0** | **0** | **9** |

Sin tocar un solo fichero de Fabric: `pipelineKSdkRepo` es una propiedad de Gradle, y el mecanismo
de consumption no requiere coordinar con el trabajo ajeno en vuelo en ese repositorio.

### 2.1 Los 9 skips son `NOT_RUN`, no verde

| clase | skips | motivo |
|---|---|---|
| `ConsoleContinuationGate` | **6** | `assumeTrue` sobre `PIPELINEK_SPIKE_HOME` (`ConsoleContinuationGate.kt:76-87`): exige una distribución instalada y usable. Sin la variable, la clase entera se omite. |
| `SemanticParityTest` | 3 | exige un contenedor. |

Los seis de `ConsoleContinuationGate` son precisamente las leyes de **corte UTF-8 y continuidad de
consola** — la parte del contrato donde más importa la evidencia. No se counted como verde: son
`NOT_RUN` y necesitan la distribución instalada, que es un paso del PRODUCT-GATE.

---

## 3. Coste del troceado — MEDIDO

Dos KDoc de producción prometían esta medición y no la tenían:

- `ProcessOutputSink.kt:155` — *"OBS-F measures the throughput/RSS trade-off against data rather
  than by taste"*.
- `SegmentFrameIndex.kt:318` — *"One force per frame is the price of this index being an authority
  at all; its cost per unit of output is measured in OBS-F"*.

Un KDoc que cita una medición inexistente es exactamente el defecto que el propio repositorio
prohíbe en `build.gradle.kts:427-433` ("a written claim that no evidence backs"). Este recibo es la
evidencia que esas dos frases debían citar.

### 3.1 Qué mide

`ObsFChunkCostMeasurementTest` conduce el **ingress real** (`RedactingOutputIngress`) sobre el
**store real** en disco, exactamente como el pump lo conduce (`target.write(window, 0, n)` con una
ventana completa), y cuenta operaciones de durabilidad. La ley afirmada es discreta y exacta; los
milisegundos sólo se imprimen.

```text
cd v2 && ./gradlew :pipeline-application:test --rerun-tasks --console=plain \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsFChunkCostMeasurementTest'
→ 1 test, 0 fallos, 0 errores, 31.8 s · 8 MiB por candidato
```

| window | transactions | frames = fsyncs | transactions/MiB | MiB/s |
|---:|---:|---:|---:|---:|
| **1 KiB (lo que se envía hoy)** | 8192 | 8192 | **1024.0** | **0.3** |
| 16 KiB | 512 | 512 | 64.0 | 25.5 |
| 32 KiB | 256 | 256 | 32.0 | 64.4 |
| 64 KiB | 128 | 128 | 16.0 | 112.3 |
| 128 KiB | 64 | 64 | 8.0 | 160.8 |

Reproducido en dos corridas independientes (0.3 / 26.3 / 64.0 / 113.3 / 147.3 y
0.3 / 25.5 / 64.4 / 112.3 / 160.8). Las proporciones son exactas y no dependen de la máquina; los
MiB/s son de esta máquina.

### 3.2 Las leyes, y de quién son

Una primera versión de este fichero contaba las escrituras en su propio bucle y afirmaba que su
propio contador igualaba `ceil(bytes / window)`. Eso es una tautología: habría pasado con el
ingress borrado. **Se descartó antes de commitear.** Todo lo que se afirma ahora lo produce la
autoridad durable y se lee de ella:

1. **Una escritura es exactamente un frame.** El índice de frames contiene `ceil(bytes / window)`
   entradas — el conteo es de producción, no del bucle.
2. **Esos frames son densos en ordinal**, del 0 al último sin saltos.
3. **Sus rangos teselan el stream exactamente**: contiguo desde 0, sin solape y sin hueco.
4. **Ningún byte se pierde ni se duplica a ninguna ventana** — digest de ida y de vuelta idéntico.
5. **El tail responde `Sealed(finalEnd)`** una vez cerrado el stream.

La tercera es la que vale. Dos frames solapados serían bytes atribuidos dos veces; un hueco serían
bytes comprometidos pero no atribuibles tras un crash, que es justo lo que
`SegmentFrameIndex.recoverUnframedBytes` existe para reconciliar. **Ambos son invisibles a un digest
y a un conteo.**

### 3.3 Mutaciones, y por qué la atribución es 1:1

Dos mutaciones sobre `RedactingOutputIngress.write`, con el arnés que verifica el hash antes y
después, restaura desde copia y aborta si el build de test no estaba verde:

| mutación | cambio | fila que tumba | línea |
|---|---|---|---|
| **M-C1** | se elimina la llamada a `frameIndex.append` | "una escritura es exactamente un frame" | `ObsFChunkCostMeasurementTest.kt:119` |
| **M-C2** | el frame pasa a `(committedEnd, committedEnd + length)` | "los rangos teselan el stream exactamente" | `ObsFChunkCostMeasurementTest.kt:137` |

M-C2 es la que da valor al par. Un rango desplazado **conserva el número de frames**, así que la
ley 1 sigue verde y sólo la ley 3 lo detecta: el conteo no habría visto nada. Sin la ley de
teselado, M-C2 habría sido un mutante equivalente.

Un tercer RED durante este bloque **no** fue del producto y está escrito como tal: la primera
corrida de la fila 1 falló con `expected: Long(8192) but was: Integer(8192)` — mismos 8192, cajas
distintas, y el overload boxed de JUnit las compara como distintas. Un RED que dice
`ObsFChunkCostMeasurementTest.kt:113` y no dice nada del ingress. Corregido con `toLong()`.

### 3.4 El hallazgo que la tabla no muestra

`SegmentOutputStore.Reserve` inicializa:

```kotlin
limitInternal = baseInternal + maxOf(minBytes.toLong(), DEFAULT_RESERVATION_BYTES)  // 64 KiB
```

Con una ventana de 1 KiB, **cada transacción reserva 64 KiB, escribe 1 KiB y descarta el 98 % en
el commit**. No es sólo "mucho fsync": es una reserva massivemente desperdiciada por diseño, una por
cada KiB de producción.

El rendimiento de 0,3 MiB/s tiene una consecuencia que no es una cifra de laboratorio: el sink es
síncrono en el hilo del pump, así que un fsync por KiB **ralentiza al proceso hijo** al llenarse el
pipe. Eso es *storage backpressure*, que la ley permite como flow-control intrínseco — pero a
0,3 MiB/s el límite deja de ser el del subsistema de observabilidad y pasa a ser el del build.

### 3.5 La decisión, tomada y aplicada

`TRANSCRIPT_LIVE_WINDOW_BYTES` pasa de **1024 a 65536**. Los datos lo decidían y la razón que
mantenía el 1024 ya no existía:

- **Ya no compraba latencia.** El pump lee `min(window, available())`; un productor lento no se ve
  afectado por la ventana, y un productor rápido la ve como granularidad de commit, no como suelo
  de latencia. El suelo pasó a ser el lookahead del redactor. El 1024 era el valor que había elegido
  una justificación que OBS-B eliminó, y el número se quedó.
- **64 KiB es el codo de la curva** y coincide con el `DEFAULT_RESERVATION_BYTES` del store, de modo
  que **una ventana es exactamente una reserva** y el 98% de reserva desperdiciada desaparece.

El coste se acepta y queda escrito en el KDoc de la constante: un producto que satura el pipe hace
su primer byte visible tras ~64 ms en vez de ~1 ms.

**Dos certificadores dependían del valor viejo y se reescribieron en el mismo commit**, que es la
regla que OBS-C2.3 incumplió:

| certificador | antes | ahora | por qué |
|---|---|---|---|
| `ObsBLiveOutputIngressTest` | payload de 32 KiB | **128 KiB** | su premisa es `LARGE_PAYLOAD_BYTES > TRANSCRIPT_LIVE_WINDOW_BYTES`; con 64 KiB un payload de 32 KiB ya no cruzaba la ventana y la fila habría pasado por accidente |
| `ObsC23ChannelSeparationUatTest` | payload de 4 KiB | **128 KiB** | dos ventanas completas; una sola habría dejado de observar bytes comprometidos a mitad del paso |

La segunda tenía además su premisa **sólo citada en el KDoc y nunca afirmada**, lo que la convertía
en una afirmación que el compilador no comprobaba. Se ancló dentro de `windowPayload()`, que es la
única función por la que pasa cualquier fila que necesite un payload comprometido a mitad del paso,
de modo que una fila nueva no puede saltársela.

**No hay mutación para la constante, y eso es una decisión y no un olvido.** Es un valor de ajuste
medido, no una ley: ninguna ley se rompe por volver a 1024, y guardarlo con un umbral sería
exactamente el «invented threshold» que la ley de conformismo prohíbe
(`OutputPlaneConformanceTest`, «No invented thresholds»). Lo que sí queda guardado son las leyes que
el window atraviesa —M-C1 y M-C2— y el valor está escrito junto a la medición que lo justifica, de
modo que un cambio futuro se vuelve a decidir con datos y no por inercia.

---

## 4. Lo que este recibo deja abierto

| # | Cláusula OBS-F | Estado | Por qué |
|---|---|---|---|
| 1 | events desde `EventCursor` | **PROBADO** (HF1/HF2) | `EventHistoryContractTest.kt:123`, `S54ExternalVerticalRestartUatTest.kt:141` |
| 2 | output desde `OutputCursor` | **PROBADO** (HF2) | `ConsoleReadServiceTest.kt:103` |
| 3 | suscriptor lento | **PROBADO** (HF2) | `ObsFConsumerContinuityUatTest` SLOW-1: el consumidor lee una página, queda aparcado en un latch que no controla, y el productor compromete MÁS y la corrida termina igual. La ley se afirma comparando dos extents comprometidos tomados con el consumidor parados entre medias. |
| 4 | desconexión con la corrida en curso | **PROBADO** (HF2) | DISCONNECT-1: un consumidor lee una página y se abandona; el paso se libera y termina, el plano retiene cada byte, el tail queda `Sealed` y un lector NUEVO continúa exactamente donde paró el anterior. |
| 5 | reconexión de la línea de output entre procesos | **PROBADO** (HF3) | RECONNECT-1: dos procesos forkeados de la distribución **instalada**. El B recibe un único string —el token que A imprimió— y nada más; la concatenación de ambas páginas debe igualar el transcript, sin hueco y sin repetición. |
| 9 | filtrado stdout/stderr | **PROBADO** (HF2) | `ObsC23ChannelSeparationUatTest.kt:241,302,344`; `ObservationOutputFollowerTest.kt:165` |
| 10 | running → sealed | **PROBADO** (HF2) | `ObsCChannelAndTailCharacterisationTest.kt:458` |
| 11 | cero grpc/protobuf/Jenkins/controller/transporte | **PARCIAL** | `FArchObservationContractLawFitnessTest.kt:181` cubre `io.grpc`, `io.jenkins.pipelinek.fabric` y protobuf confinado a `:pipeline-protocol`. **No** cubre "jenkins", "controller" ni transporte de red, y escanea sólo `v2`. Además declara `repoRoot()` en la línea 39 y **nunca lo usa**. Una ley "sin transporte de red" sería además falsa sobre este repositorio: `pipeline-step-sdk/http/…/JdkHttpTransport.kt` usa `java.net.http.HttpClient`. |

---

## 5. Una observación sobre el estado de Fabric (no es un defecto de esta rama)

La ruta que las corridas reales de Fabric usan **no** es la que consume el contrato publicado:
`LocalConsoleSource` → `PipelineKConsoleSource` lanza `pipelinek console` una vez por página
(`PipelineKConsoleSource.kt:23`, cableado en `LocalFabricPortBinding.kt:153` y
`FabricServerApplication.kt:140`), mientras que `PipelineKConsoleSourceAdapter`, que sí implementa
`OutputReadPort`, **no tiene punto de construcción en ningún `src/main`** y sólo aparece en tests.

Ambas rutas discrepan sobre el mismo `OutputRefusal.OffsetBeyondCommitted`: una lo traduce a
`OffsetsDiscarded` y la otra a `CursorPastEndOfStream`. Elegir cuál es canónica es una decisión de
Fabric, no de este repositorio, y ese repositorio tiene trabajo ajeno en vuelo. **No se ha tocado.**

Lo que sí queda establecido aquí es lo que hace posible esa decisión: el contrato publicado desde
`6254d6c8` lleva `OutputTailPort`, `OutputStreamAddress` y `OperationOutputStreams.select`, que son
justo las tres piezas que la auditoría daba por inexistentes.

---

## 6. Reproducir

```text
# 1. publicar el SDK read-side desde ESTE árbol
cd v2 && ./gradlew publishSdkForExternalPlugin --console=plain --rerun-tasks

# 2. el consumidor real de Fabric, apuntando explícitamente a ese artefacto
cd PipelinekFabric
./gradlew :worker:pipelinek-runtime-adapter:test :testkit:spikes:test \
  -PpipelineKSdkRepo=<abs>/pipeline-kotlin-cli-obs/v2/build/sdk-repo \
  --console=plain --rerun-tasks

# 3. el coste del troceado
cd <abs>/pipeline-kotlin-cli-obs/v2
./gradlew :pipeline-application:test --rerun-tasks --console=plain \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsFChunkCostMeasurementTest'
```

El paso 2 **debe** pasar la propiedad. El valor por defecto de Fabric
(`settings.gradle.kts:23-25`) es `../pipeline-kotlin/v2/build/sdk-repo`, y sin la propiedad el paso 2
verde no dice nada sobre esta rama.
---

## 7. Un certificador obsoleto que estaba rojo en la rama

```text
RunOutputRetentionTest > a finished run releases every stream its stages wrote in one pass
  AssertionFailedError: expected: <2> but was: <4>
    at RunOutputRetentionTest.kt:336
```

**Reproducido idéntico sobre `6254d6c8` con el árbol limpio**, es decir preexistente a este bloque y
no causado por él.

La causa es OBS-C2.3. Ese cambio dio a cada paso `sh` **un stream por canal**, así que una corrida
de dos etapas escribe `2 etapas x 2 canales = 4` streams. La fila afirmaba `streamsRemoved == 2`,
escrita cuando una etapa era un stream.

**La ley no cambió y el producto no estaba mal.** La fila existen para probar *una pasada al
terminal del run, no un prune por etapa*, y eso lo demuestra `recording.intents.size == 1`: un
 prune por etapa habría producido dos intents. El `streamsRemoved == 2` era un segundo testigo
redundante, y el testigo es el que caducó.

La corrección sube el número a 4 y explica en el mensaje por qué 4 es el valor que **porta** la
afirmación (un prune por etapa reportaría 2). Es la regla que ya está escrita en `AGENTS.md`: al
cambiar un default o un tipo propio, su certificador se reescribe en el mismo commit. OBS-C2.3
cambió el default y dejó la fila detrás; esta es la deuda que dejó.

Que llevara desde OBS-C sin detectarse dice algo sobre el gate, y conviene decirlo: las corridas
verdes de este bloque se hicieron sobre la zona `observation` (22 clases / 185 tests). Esta fila
vive en el paquete `durable`, que nadie había ejecutado entero. **La zona verde no era la zona
afectada.** La corrida de este bloque sí lo fue:

```text
--tests 'dev.rubentxu.pipeline.v2.application.durable.*' \
--tests 'dev.rubentxu.pipeline.v2.application.observation.*'
→ 108 clases · 573 tests · 0 fallos · 0 errores · 0 skips
```
