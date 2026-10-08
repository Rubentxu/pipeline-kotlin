# B1a — AUD-02 / AUD-08: caracterización con medición, no con lectura

**Rama:** `s6-plugin-sdk` · **HEAD al medir:** `3af8d4a9d945b499492170a1b3911fd1c30905be` (árbol limpio al empezar)
**Alcance:** lado test exclusivamente. **Cero cambios de código de producción.**
**Base del triage:** `docs/v2/07-uat/S7_AUDIT_REVIEW_FINDINGS_TRIAGE.md` (§3 AUD-02, §8 AUD-08).
**Tree state:** los dos ficheros de test y este recibo quedan **sin commitear**; el root alinea y commitea.
Aviso para quien alinee: `git status` muestra además `M docs/v2/05-roadmap/ROADMAP.md`, **modificado por
otro actor durante esta sesión** (mtime 10:33:21 local; su diff habla de los bloques B0/B1 y del WorkItem
de SDDK). Esta rebanada no lo tocó ni lo revierte: se reporta para que no se confunda con parte de B1a.

---

## 0. Qué se entregó, y qué no

```text
ENTREGADO
  v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/
      B1aShNonDurableRouteCharacterizationTest.kt              (AUD-02: b, c, d, e)
      B1aOutputPlaneProviderLifecycleCharacterizationTest.kt   (AUD-08: a, b, c)
  este recibo
NO ENTREGADO (fuera de alcance por encargo)
  ningún cambio de producción
  ninguna ejecución de pipeline completo desde el CLI (prohibido por el encargo)
  ninguna certificación: esto es CARACTERIZACIÓN, y su veredicto NO certifica nada
```

Los 7 tests están **verdes** con números reales. Las filas que miden un defecto lo dicen en el
mensaje de aserción (`CHARACTERISATION OF A DEFECT`), de forma que el día que el defecto se cierre el
cambio de aserción sea deliberado y no un verde silencioso (Harness Fidelity Law §5).

---

## 1. Sujetos y rutas reales

| sujeto | ruta real (localizada por búsqueda, no asumida) |
|---|---|
| `ShExecution.executeNonDurableInvocation` | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt:453` (privada; se entra por `invokeShell:213`) |
| ruta durable comparadora | `ShExecution.invokeShell:242-320` → `DurableShellExecutor.executeTerminal` (`v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/DurableShellExecutor.kt:963`) |
| runtime de la ruta no durable | `ProcessDurableTaskRuntime` (`v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/task/ProcessDurableTaskRuntime.kt`) |
| `ShellInvocationResult` | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ShellInvocationResult.kt:28` |
| `OutputPlaneProvider` | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt:36` |
| `SegmentOutputStore` | `v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStore.kt:76` |
| quema de retención en producción | `CompositionRoot.kt:123` (`runCanonicalPipeline(..., controlDirRoot: Path, ...)`, declarado en `:51`), `:148`, `:211` |
| dos puertas del CLI al control root | `Main.kt:271` (sin `--db`) y `Main.kt:492` (con `--db`) |

**Corrección de dos rutas que el encargo daba por supuestas y no lo son** (el encargo decía «localiza
los ficheros reales con grep; no asumas rutas»):

- el módulo del store es **`v2/pipeline-output-store/`** (estrategia D) y el paquete es
  `dev.rubentxu.pipeline.v2.output.store`; `v2/pipeline-output/` es sólo el **contrato** publicado
  (`OutputReadPort`, `RetainUntil`, `OutputPruneIntent`). Hay dos módulos, y el encargo no los
  distinguía.
- `RetainUntil.RunTerminalPlus` **no** es la política del CLI: `CompositionRoot.kt:213` cablea
  `ExplicitReleaseOnly`, deliberadamente (el `console --control-dir` lee transcripciones de runs ya
  terminados). Ver §9: esto cambia cómo se lee la fila de liberación de AUD-08.

---

## 2. Cómo se midió (fidelidad, hermetismo, comandos)

**Fidelidad.** `HF1` in-process cruzando las autoridades productivas, nombradas en el KDoc de cada
clase: `ShExecution.invokeShell` → `ProcessDurableTaskRuntime` (no durable) / →
`DurableShellExecutor.executeTerminal` + `ShExecution.ingestTranscriptIntoOutputPlane` (durable), con
proceso `bash` real, tuberías reales y control-dir real en disco. Para AUD-08 el soak entra por
`ShExecution.ingestTranscriptIntoOutputPlane` (el ingest durable de producción) y la liberación por
`RunOutputRetention.onRunTerminal` con el mismo supplier `{ OutputPlaneProvider.storeFor(root) }` que
cablea `CompositionRoot:211`. **Ningún algoritmo se reimplementa**: los contadores de stores y de locks
leen el estado del propio objeto de producción por sus campos privados declarados ([cachedStoreCount],
[perStreamLockCount]) y **fallan con `AssertionError` si el campo desaparece** en lugar de devolver 0
(un 0 silencioso convertiría todas las filas en verdes vacuos).

**Hermetismo.** `@TempDir` en cada método, cero red, cero aserciones sobre reloj de pared (los tiempos
se **registran**, nunca se afirman), `@Timeout` de clase y de método, y cero procesos vivos al final
(comprobado con `ProcessHandle.current().descendants()`).

**La única escritura fuera de `@TempDir`, y por qué es en sí una medición.** Cuando
`controlDirRoot == null`, la ruta de producción crea su propio directorio bajo `java.io.tmpdir`
(`Files.createTempDirectory("pipeline-sh-non-durable")`, `ShExecution.kt:469`) y **no lo borra**. El
harness no escribe nada ahí: mide el delta, lo registra y borra lo que produjo la ruta. No se puede
redirigir `java.io.tmpdir` por test (el JDK lo cachea en un campo estático de `java.io.TempFileHelper`
en el primer uso). Ver §12 para un defecto **de mi propio harness** que esta inspección encontró.

**Comandos exactos** (todos con `timeout 600`, log en `$JCODE_SCRATCH_DIR`, nada en `/tmp` ni en el repo):

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:compileTestKotlin                       # L0  → exit 0, 0 líneas '^e: '
cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'B1a*'                     # ← las mediciones
       # canario XML: se borran los TEST-...B1a*.xml antes de correr y se comprueba que reaparecen
cd v2 && timeout 600 ./gradlew :pipeline-application:detekt                                  # tarea del gate `check`
cd v2 && timeout 600 ./gradlew :pipeline-application:detektTest                               # A/B diagnóstico (§10)
```

**Toda cifra de este recibo sale de una ejecución real.** Los números se copian literalmente del
`<system-out>` de los XML de JUnit en
`v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.B1a*.xml`
(no del log de consola, no de una promesa).

---

## 3. AUD-02 (a) — alcanzabilidad desde el CLI: **la premisa del triage es correcta a medias, y la mitad que falta importa**

### 3.1 Lo que dice la enumeración mecánica (evidencia estática, OBSERVED)

```bash
grep -rn --include=*.kt -E "invokeShell\(|runShStep\(|runShellCommand\(|runShellCommandTyped\(|executeBranchStep\(" v2/ | grep "/src/main/"
grep -rn --include=*.kt -E "CanonicalRuntimeContext\(|CanonicalDurableRunCoordinator\(|CoordinatorCaps\(" v2/ | grep "/src/main/"
```

Resultado (13 + 4 líneas, `src/main` entero): las cinco entradas de shell viven en `ShExecution`, y la
única llamada externa es `ShOperationsAdapter.kt:58`, que recibe `controlDirRoot: java.nio.file.Path?`
y lo reenvía tal cual. De los cuatro sitios que en `src/main` construyen o transportan el contexto, sólo
dos son composiciones reales:

| construcción | valor que llega a `controlDirRoot` | nulabilidad del transporte |
|---|---|---|
| `CompositionRoot.runCanonicalPipeline` → `CanonicalDurableRunCoordinator(controlDirRoot = …)` | el parámetro del mismo nombre, `controlDirRoot: Path` | **no nulable** (`CompositionRoot.kt:51`) |
| `ScriptedFrontendRunner` (`:120`) | su propio campo `controlDirRoot: java.nio.file.Path` | **no nulable** (`:75`) |
| `StepDispatchEngine` (`:187`) | campo del engine, `Path?` | nulable, pero lo suministra el coordinador |
| `CoordinatorCaps(controlDirRoot = null)` | — | **cero sitios de construcción en `src/main`**; sólo lo usa el constructor secundario del coordinador |

Y los dos control roots del CLI, `Main.kt:271` y `Main.kt:492`, son `val controlDirRoot: Path` **no
nulables**, con ambas ramas produciendo un directorio real (`validateControlRoot` → `createDirectories`
en `MainRuntimeSupport.kt:20-37`; o `dbPath.parent.resolve("durable-shell")`; o
`Files.createTempDirectory("pipelinek-inmem-run")`).

**Conclusión 1 (OBSERVED, estática):** en `src/main`, en un host Linux, la rama
`controlDirRoot == null` de `invokeShell:227` **no es alcanzable**. El triage acierta.

**Conclusión 2 (OBSERVED, estática):** un contexto con `controlRoot == null` **no falla cerrado por
admisión**: `CanonicalRuntimeCapabilityAccess` liga igualmente `SHELL_OPERATIONS_CAPABILITY` a un
`ShOperationsAdapter(controlDirRoot = context.controlDirRoot)`. Es decir, la nulabilidad del
transporte es la que decide la ruta, sin red de seguridad aguas arriba.

### 3.2 La puerta que el CLI **sí** abre (medido, §7 fila `e`)

`ShExecution.kt:321` no es la misma puerta: es un `catch (LinuxRequiredException)` que, en un host cuya
`os.name` no contiene `linux`, reenvía a `executeNonDurableInvocation` **con el control root real del
llamante**. `DurableShellExecutor.checkLinuxOrThrow()` lee la propiedad en cada llamada (no la cachea),
así que la ruta se puede ejercitar de verdad. La fila `e` lo mide: sin stream en el Output Plane, un
`EchoOutputCaptured` (la única representación de esa ruta), y **el presupuesto de timeout igualmente
perdido**.

**Veredicto revisado de AUD-02(a):** la ruta no durable **no** es sólo de composición de test; es la
ruta del CLI en todo host no-Linux, y conserva los tres defectos que se miden abajo (timeout,
materialización, cancelación). Lo que sigue siendo cierto es que **no** es alcanzable desde el CLI
Linux por la rama `null`. «No medida en producción» es la frase correcta, no «no alcanzable».

---

## 4. AUD-02 (b) — heap: O(salida) en la ruta no durable, O(1) en la durable

Payload: 16 MiB (`head -c 16777216 /dev/zero | tr '\0' 'A'`), mismo comando y mismo modo
(`ShellReturnMode.NONE`) en ambas ramas; la única variable es el control root.

Líneas literales del `<system-out>`:

```text
B1a(b) non-durable: peakUsedDelta=45126880 liveAfterGcDelta=17843376 (contentLength=16777224 dirsLeftBehind=[/tmp/pipeline-sh-non-durable13856620671492573885])
B1a(b) durable:     peakUsedDelta=23134224 liveAfterGcDelta=115216 (planeExtent=16777224 consoleEvents=0)
B1a(b) live difference (non-durable - durable) = 17728160 bytes
```

(Pasada final, XML `2026-10-08T08:41:39Z`, sobre los ficheros ya corregidos. **Tres pasadas
independientes** del mismo código dieron `live` no durable = 18.030.968 / 17.842.760 / **17.843.376** B
y `live` durable = 256.312 / 115.120 / **115.216** B: la propiedad es reproducible y las cifras de heap
varían poco entre pasadas, que es exactamente por lo que los umbrales del test son diferencias y nunca
valores absolutos.)

| cantidad | no durable | durable | lectura |
|---|---|---|---|
| `peakUsedDelta` (muestreado cada 2 ms) | 45.126.880 B ≈ 43 MiB | 23.134.224 B ≈ 22 MiB | el pico incluye basura no recolectada; es una **cota de demanda**, no un conjunto vivo |
| `liveAfterGcDelta` (3 `System.gc()`, con resultado y sink aún alcanzables) | 17.843.376 B ≈ **17,0 MiB** | 115.216 B ≈ **0,11 MiB** | **esto es lo decisivo**: la ruta no durable deja residente una copia del transcript; la durable no |
| longitud verificada del contenido | 16.777.224 chars (payload+8) | `planeExtent=16.777.224` en el Output Plane | ambas ramas **prueban que los bytes fluyeron**: no pueden pasar por no transferir nada |
| eventos de consola | 1 `EchoOutputCaptured` (la única representación de esa ruta) | **0** (M1-P2: el plano es la autoridad) | comprobación de ruta, no cosmética |

Un payload de 16 MiB produce ~17,0 MiB de conjunto vivo en la ruta no durable y ~0,11 MiB en la
durable: el transcript entero queda en el heap del JVM en el primer caso. El pico durable (~22 MiB)
**no** es una copia del transcript: es basura transitoria de chunks (8 KiB por chunk) sin recolectar
más la política de crecimiento del heap; el conjunto vivo lo desmiente (`115.120 B` con 16 MiB
transferidos). Ambas cifras quedan en el recibo porque un lector que sólo mire el pico sacaría la
conclusión equivocada.

**Lo que la aserción afirma, y con qué margen.** La fila exige
`live(non-durable) − live(durable) ≥ payload/2` (8 MiB): medido 16,9 MiB, margen ~2,1×. Si el margen
molesta a alguien, la lectura es que el margen protege contra ruido de medición, no contra el defecto.

---

## 5. AUD-02 (c) — el presupuesto de timeout **desaparece** (confirmado, medido)

Mismo comando (`sleep 2`), mismo `shOptions.timeoutMs = 300`.

```text
B1a(c) non-durable with timeoutMs=300 observed=UnitValue elapsedMs=2007
B1a(c) non-durable control dirs left behind: [/tmp/pipeline-sh-non-durable17640982427173650221]
```

- Brazo durable (control, aserción verde): `Interrupted(interruption.kind == TIMEOUT)`. El sustrato
  durable **sí** respeta el presupuesto; sin este brazo, el resultado de abajo no sería atribuible.
- Brazo no durable: **`UnitValue`** a los 2007 ms, es decir el hijo corrió hasta el final. Causa
  exacta: `executeNonDurableInvocation` construye `TaskExecutionRequest(..., timeoutMs = null)`
  (`ShExecution.kt:489`) y nunca lee `shOptions.timeoutMs`.

Aserción: se afirma la **clasificación** (`!is Interrupted`), jamás la duración; los 2007 ms se
registran como observación.

**Alcance de la pérdida (medido + estático):** el mismo `TaskExecutionRequest` es el que ejecuta la
rama de fallback no-Linux (§3.2), así que en un host no-Linux el CLI pierde el presupuesto igual — y
ahí el `shOptions` sí traía el valor del producto.

---

## 6. AUD-02 (d) — cancelación: el `CancellationException` **se convierte en fallo de infraestructura** (defecto medido)

Guion: `: > <marker-started>; sleep 30; : > <marker-finished>`. La cancelación se emite cuando el
marker de arranque existe (posición por condición, no por espera). El veredicto de la llamada se captura
como **valor** fuera del scope cancelado (`CompletableDeferred.complete`, sin suspender), de modo que
«la excepción escapó» y «se la tragó el código» no se pueden confundir.

```text
B1a(d) verdict of the cancelled call = RETURNED:Failed(kind=INFRASTRUCTURE, message='StandaloneCoroutine was cancelled')
B1a(d) result.txt says = CANCELLED
```

| observación | resultado | cómo se observó |
|---|---|---|
| veredicto de `invokeShell` | `Failed(kind=INFRASTRUCTURE, message='StandaloneCoroutine was cancelled')` | valor devuelto por la llamada de producción |
| lo que el runtime grabó sobre su propia run | `result.txt` = `CANCELLED` | fichero durable escrito por `ProcessDurableTaskRuntime` |
| árbol de procesos | muerto | sin descendientes con `pipeline-sh-non-durable` en la línea de comandos |
| marker post-sleep | ausente | el script nunca llegó a escribirlo |

**La ley que se viola**, literal de AGENTS.md (PAR-D): *«`CancellationException` is an execution
mechanism: it MUST NOT be mapped to a generic infrastructure failure or to a terminal durable
outcome.»* Aquí se mapea exactamente a eso, y el mensaje que ve el llamante es el texto del mecanismo
de corrutinas («StandaloneCoroutine was cancelled») presentado como fallo de infraestructura. Un
llamante no puede distinguir «me cancelaron» de «el sustrato de shell se rompió».

Mecanismo (leído después de medir, para explicar y no para predecir): `execute` re-lanza el
`CancellationException` tras grabar `CANCELLED`, y `executeNonDurableInvocation` lo captura en su
`catch (failure: Exception)` → devuelve un `Failed` tipado. **No medido:** qué hace con ese valor el
coordinador en una run real (requeriría un pipeline completo, prohibido por el encargo) — y si la
cancelación sigue propagándose *además* por el job del llamante, lo cual es un asunto distinto del
valor devuelto.

---

## 7. AUD-02 (e) — el fallback no-Linux, medido entrando por la misma función

`os.name` se fuerza a `Mac OS X` durante una sola llamada y se restaura en `finally`; el KDoc del test
lo declara como **simulación de plataforma**, no como una ejecución en macOS. La ruta se comprueba por
tres observaciones independientes: sin stream en el plano (`planeHasStream=false`), exactamente un
evento de consola con el contenido completo del script, y el presupuesto de 300 ms ignorado.

```text
B1a(e) forced os.name='Mac OS X', controlDirRoot=<real>: observed=UnitValue elapsedMs=2047 planeHasStream=false consoleEvents=1 osNameRestored=true
```

| observación | valor | qué descarta |
|---|---|---|
| `planeHasStream` | **false** | si hubiera sido `true`, el sustrato durable habría corrido y la fila no mediría nada |
| `consoleEvents` | **1** (con `BEGIN` y `END` dentro) | es la única representación de esa ruta; confirma que el script corrió entero |
| `observed` / `elapsedMs` | `UnitValue` con presupuesto de 300 ms, a los **2047 ms** | el presupuesto **se pierde igual** en la rama que el CLI sí abre: el mismo `TaskExecutionRequest(timeoutMs = null)` |
| `osNameRestored` | **true** | la simulación no contamina el resto del JVM |

---

## 8. AUD-08 — ciclo de vida del Output Plane (medido sobre 40 runs en un solo proceso)

N = 40 runs, payload 256 KiB por run, un solo JVM. Las tres filas literal del `<system-out>`:

```text
B1a(AUD-08,a) N=40 storesBefore=0 afterSoak=40 afterRelease=40 | filesUnderRoots afterSoak=80 afterRelease=78 | disposition=Released(report=OutputPruneReport(streamsRemoved=1, bytesReleased=262144, streamsRetained=0))
B1a(AUD-08,a) storesAfterForget(one root)=39
B1a(AUD-08,b) N=40 storesBefore=0 afterSoak=1 | perStreamLocks afterSoak=40 afterRelease=80 | filesUnderRoot afterSoak=80 afterRelease=0 | releasedRuns=40
B1a(AUD-08,c) fd probe: idle=0 withHeldStream=1 afterClose=0
B1a(AUD-08,c) heap probe: baselineUsed=17088296 with24MiBBallast=49807584 afterRelease=-524176
B1a(AUD-08,c) soak: N=40 bytesWritten=10485760 stores=1->41 retainedHeapDelta=26600 fdsAfterSoak=0 filesAfterSoak=80
```

### 8.1 Las tres preguntas del encargo, respondidas con número

**¿El store por root se libera al terminar la run?** **No.** Tras liberar la run por la ruta de
producción (`RunOutputRetention.onRunTerminal`, disposición `Released(streamsRemoved=1,
bytesReleased=262144)`), el contador de stores pasa de 40 a **40**: se liberan los bytes, no la entrada
de caché. `forget(root)` sí lo baja a 39 y `forgetAll()` a 0 — y esas dos son las **únicas** remociones
del tipo: `grep -rn --include=*.kt "OutputPlaneProvider.forget\|\.forget(" v2/ | grep "/src/main/"`
**no devuelve ni una línea en todo `src/main`** (los dos únicos resultados del grep de definiciones son
`OutputPlaneProvider.kt:65` y `:70`). **Cero llamantes en producción.**

**¿Sobrevive entre runs del mismo proceso?** **Sí, y acumula.** Con un root fijo (que es el caso del
CLI con `--db`: `dbPath.parent.resolve("durable-shell")`, `Main.kt:505`), 40 runs dejan **1 store**
pero **40 locks** en su `perStream`; tras liberar las 40 runs, **80**. La segunda mitad es un hallazgo
que no estaba en el informe de auditoría: `prune` bloquea el **nombre de directorio** (ya pasado por
`safe()`, que pliega `/` sobre `_`) mientras el escritor bloqueaba el **id crudo** (`run/op/transcript`),
así que liberar una run **añade** una segunda entrada de lock permanente por stream en lugar de
reutilizar la del escritor. La fila lo afirma con el número medido y dice que si los dos keyings se
unifican, la fila pasa a N.

**¿Crecen stores/descriptores/fds con N runs?**
- **stores: sí, lineal en el número de roots distintos** (40 runs con root fresco → 40 stores; delta
  medido 0→40). Con root fijo → 1 store, y el crecimiento se muda al mapa de locks de arriba.
- **descriptores: no.** Tras el soak, `fdsAfterSoak=0`. La sonda `/proc/self/fd` está **probada capaz de
  ver uno**: con un `InputStream` retenido el contador va `0 → 1`, y al cerrarlo vuelve a `0`. Sin ese
  control, «no hay fds retenidos» sería una aserción vacua, y por eso está en la misma fila.
- **heap: no, y ahora con control positivo.** 10.485.760 B escritos y **26.600 B** de heap retenido tras
  GC (0,25 % de lo escrito). La sonda demuestra que ve retención de verdad: 24 MiB de balasto producen
  +49.807.584 B y su liberación devuelve el contador a la línea base (−524.176 B). El pico de
  inflación 24 MiB → 49,8 MiB no es un artefacto: es lo que hace este JVM a `-Xmx512m` con
  asignaciones *humongous* de 1 MiB, medido aparte en un JVM limpio antes de creer el resultado (§12).

### 8.2 Lo que el diseño ya decía, para no confundir medición con descubrimiento

`OutputPlaneProvider` documenta su ciclo de vida en su propio KDoc («one recovered store is held per
control-directory root»), y los recibos `B2_OUTPUT_PLANE_RETENTION_AND_RELEASE_RECEIPT.md` y
`S4_RETENTION_RUN_TERMINAL_AUTHORITY_RECEIPT.md` fijaron la separación «el store borra y no decide».
Esta rebanada **no descubre** que exista una caché por root: **la mide y le pone número** por primera
vez (el triage marcaba AUD-08 como «no verificado / sin medir»). La mitad nueva es la aritmética del
mapa de locks (8.1) y los descriptores/heap con control positivo.

### 8.3 Riesgo real, sin inflarlo

El CLI es un proceso por run, así que la caché muere con el proceso: **el crecimiento medido es por
proceso, no por run**. Lo que queda expuesto es (i) un host de vida larga que ejecute muchos runs en
un JVM (servidor embebido, harness, tests), y (ii) el **disco**, que es el punto de la §9.

---

## 9. Nueve líneas que cambian cómo se lee lo anterior (política real del producto)

```kotlin
// CompositionRoot.kt:200-214
// `ExplicitReleaseOnly`, and the reason is the product's own console: `pipeline console
// --control-dir` reads the transcript of a run that ALREADY FINISHED, so a
// `RunTerminalPlus` here would delete the very output the product exists to serve.
outputRetention = RunOutputRetention(
    retention = { OutputPlaneProvider.storeFor(controlDirRoot) },
    policy = RetainUntil.ExplicitReleaseOnly,
),
```

Consecuencia que el propio código declara y que esta medición respalda: en el CLI **nada se libera
automáticamente** y la superficie de liberación del operador (`OperatorReleased`) **no está
construida**. Con `--db`, ese root es `dbPath.parent/durable-shell` (estable): cada run añade 2
ficheros por stream (medido: 80 ficheros para 40 runs) que nada poda. Es deliberado, está documentado,
y su consecuencia — acumulación en disco — ahora tiene número.

La fila de liberación de AUD-08 mide el **mecanismo** (`prune` funciona, libera bytes y no libera el
store); **no** es la disposición por defecto del CLI. Confundirlas convertiría esta medición en una
afirmación falsa sobre el producto.

---

## 10. `detekt`: el gate verde, y un `detektTest` que ya estaba rojo antes de esta rebanada

### 10.1 La tarea que el gate sí corre

```bash
cd v2 && timeout 600 ./gradlew :pipeline-application:detekt
```

**BUILD SUCCESSFUL, exit 0**, 469 ficheros `.kt` analizados, **0 findings** (`detekt.md`). Es la tarea
que `check` depende (`build.gradle.kts`: `check` → `dependsOn(tasks.named("detekt"))`), y un A/B la
confirma verde **con** los ficheros nuevos y **sin** ellos (dos corridas, exit 0 las dos).

### 10.2 La tarea de resolución de tipos, que NO está en el gate

`:pipeline-application:detektTest` no está en la ruta de `check` (se comprobó con `--dry-run`), y su
estado **ya era rojo antes de esta rebanada**: 460 findings en ficheros de test existentes
(`UnusedImport`, `UnusedVariable`, `UnusedPrivateFunction`, `UseCheckOrError`…), todos ajenos a este
trabajo. Ese rojo queda como estaba: no se tocó ningún fichero ajeno para ponerlo verde.

De los 462 findings de la primera corrida determinista, **2 eran míos**, y se corrigieron en el lado
test (no en producción):

```text
B1aShNonDurableRouteCharacterizationTest.kt:339:56  Dispatcher Default is used without dependency injection. [InjectDispatcher]
B1aShNonDurableRouteCharacterizationTest.kt:217:31  extent!! contains an unnecessary not-null (!!) operators [UnnecessaryNotNullOperator]
```

- el primero: el scope de un test no necesita un dispatcher explícito para medir cancelación (`launch`
  elige `Dispatchers.Default` igual), así que se quita la referencia explícita en vez de silenciar la regla;
- el segundo: `!!` redundante tras el smart cast.

Tras las dos correcciones, la corrida final de `detektTest` da **`crashes=0`, `findings=460`,
`findingsOnB1a=0`**: los ficheros nuevos ya no aportan ningún finding, y los 460 restantes son los
pre-existentes, intactos. El gate (`detekt`) sigue en 0.

### 10.3 Un fallo de la herramienta que **no** se pudo reproducir, y por qué se escribe igual

En una corrida intermedia, `detektTest` abortó con
`KotlinIllegalArgumentExceptionWithAttachments: No fir element was found for KtNamedFunction` (API de
análisis FIR de detekt `2.0.0-alpha.6` con Kotlin 2.4), señalando `awaitCondition` de mi fichero. **No
se reprodujo**: dos corridas limpias con los dos ficheros presentes y dos corridas con un solo fichero
todas terminaron sin crash (`crashes=0`, `firMissing=0`, hashes de los ficheros verificados antes y
después). La causa más probable, y se dice como probable y no como hecho: **esa corrida estaba
analizando el fichero mientras yo lo editaba** (arrancó a las 08:31:55 y mi edición entró a las
08:32:11; el volcado FIR del crash no contenía la fila que esa edición añadía). Lo correcto es
clasificarlo como artefacto de la medición —un fichero que cambia bajo el analizador—, no como una
propiedad de los ficheros entregados. §12 lo deja como lección.

---

## 11. NO_MEDIDO (con motivo, nunca con número inventado)

```text
1  Pico/vivo de heap en un CLI real end-to-end                → el encargo prohíbe ejecutar pipelines completos.
2  Comportamiento del coordinador ante el Failed(INFRASTRUCTURE)  → requeriría una run completa (prohibido).
3  Memoria RSS del proceso hijo / del sistema operativo         → se mide heap del JVM, no RSS. El heap del
                                                                  JVM no es la RSS y el recibo no los mezcla.
4  El estado de la caché tras un `forget` concurrente con un run en curso → no hay API de producción que
                                                                  lo haga hoy; medirlo sería medir una carrera inexistente.
5  Descriptores vivos en un host no-Linux                        → el harness corre en Linux; la sonda /proc
                                                                  devuelve lista vacía por diseño y la fila falla en vez de mentir.
6  Sustituto del detektTest pre-existente en otros módulos       → fuera del alcance; sólo se mide este módulo.
7  Cuántos procesos CLI reales hacen crecer la caché antes de OOM → depende del tamaño de la distribución y
                                                                  del heap del host; no es una propiedad del código.
```

---

## 12. Lecciones de harness (tres defectos propios y una trampa de herramienta, escritos)

Un recibo que sólo cuenta lo que salió bien es publicidad. Estas cosas son errores míos o trampas de
herramienta que costaron una pasada de medición cada una, y quedan escritas:

1. **Mi `nonDurableArm` pasaba el conjunto *actual* de directorios como si fuera el «antes»**, así que el
   delta salía vacío, nada quedaba reclamado para teardown y se filtraba **un directorio de producción
   por ejecución** en `java.io.tmpdir`. Lo encontré inspeccionando `/tmp` después de la primera pasada
   verde (no lo encontró ningún test: los tests estaban verdes). Corregido, con la comprobación
   `LEFTOVER_TMP=0` en la pasada final. Lección para el próximo: *una pasada verde no prueba que el
   harness sea limpio; hay que mirar fuera del test*.
2. **El control positivo del balasto de heap falló la primera vez** (`afterRelease` no bajaba). La causa
   era del harness, no del JVM: la referencia temporal del array quedaba viva en el frame. Se arregló
   asignando/liberando por funciones auxiliares (sin local que escape) y se **verificó aparte**, en un
   JVM limpio con los mismos `-Xmx`, antes de volver a creer la medición. Sin ese control, «el heap
   retenido no crece» habría sido una aserción vacua y el recibo habría mentido por defecto.
3. **Acusé a mis ficheros de tumbar `detektTest` sin repetir la medición.** La primera corrida del
   análisis falló con un crash de la API FIR señalando mi fichero, y lo escribí como si fuera una
   propiedad del fichero. Al repetir —dos veces con los dos ficheros, y una vez por fichero— el crash
   **no apareció**; lo que sí apareció, de forma determinista, fueron dos findings míos (corregidos,
   §10.2) y la causa probable del crash: el fichero cambió bajo el analizador porque yo lo estaba
   editando durante la corrida. Lección gemela de la 1: *una medición hecha mientras se modifica el
   sujeto no es una medición del sujeto*, y una afirmación de causa necesita su segunda pasada.
4. **`java.io.tmpdir` no se puede redirigir por test** (caché estática del JDK), así que la excepción de
   hermetismo de §2 se documenta en lugar de disimularse.

---

## 13. Estado de los tests y cómo reproducir

```text
TESTS NUEVOS (sólo test, sin tocar producción)
  B1aShNonDurableRouteCharacterizationTest            4 filas PASS  (b heap · c timeout · d cancelación · e fallback)
  B1aOutputPlaneProviderLifecycleCharacterizationTest 3 filas PASS  (a stores/locks · b 2N locks · c fds+heap)
XML  v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.B1a*.xml
     tests=4 failures=0 errors=0 skipped=0  (timestamp 2026-10-08T08:41:39Z)
     tests=3 failures=0 errors=0 skipped=0  (timestamp 2026-10-08T08:41:37Z)
     canario: los XML se borran antes de la pasada y se comprueba que reaparecen
```

### 13.1 Pasada final de verificación (sobre los ficheros corregidos)

```text
cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests "B1a*"   → exit 0  (BUILD SUCCESSFUL in 15s, ^e: = 0)
                                                                                 XML: tests=4+3, failures=0, errors=0, skipped=0
LEFTOVER_TMP=0            (cero directorios de producción en java.io.tmpdir; cero hijos vivos)
cd v2 && timeout 600 ./gradlew :pipeline-application:detektTest             → exit 1  crashes=0 findings=460 findingsOnB1a=0
cd v2 && timeout 600 ./gradlew :pipeline-application:detekt                   → exit 0  (la tarea del gate: 0 findings)
```

**Ningún test existente se borró, se deshabilitó ni se debilitó.** No hay ningún test existente que
contradiga lo medido; lo único que se contradice en el árbol es el `detektTest` pre-existente (§10), que
ya estaba rojo por causas ajenas y no está en la ruta del gate.

---

## 14. Veredicto del triage, con la medición delante

| hallazgo | veredicto de B1a |
|---|---|
| **AUD-02** | **Confirmado y matizado.** Los tres defectos son reales y están medidos (timeout perdido, transcript entero residente, cancelación convertida en fallo de infraestructura). El matiz del triage («no alcanzable por el CLI») es correcto **sólo en Linux** y sólo para la rama `null`: el fallback de `ShExecution.kt:321` mete la misma función en el camino del CLI en cualquier host no-Linux, con el control root real del producto. La severidad correcta no es «test-only»: es «plataforma-dependiente». |
| **AUD-08** | **Contestado: existe, y su forma exacta es 1 store por root para toda la vida del proceso, con 2 entradas de lock por run liberado, 0 descriptores retenidos y 0,25 % de heap retenido.** No es el escenario catastrófico que el informe temía (no hay fuga de fds, no hay retención de bytes, y el CLI muere con el proceso), y sí es real para cualquier host de vida larga. Hereda además la consecuencia de disco de `ExplicitReleaseOnly` (§9). |
| **AUD-11** (documental) | no tocado: fuera del alcance de la rebanada. |

---

## 15. Lo que esto NO prueba

```text
- NO prueba nada sobre un host no-Linux real: la fila (e) simula la propiedad de plataforma en Linux.
  Prueba que esa rama del CLI ES ESA FUNCIÓN; no prueba que el resto del producto corra en macOS.
- NO certifica el Output Plane, ni el segment store, ni la retención: es caracterización de un ciclo de
  vida, y una caracterización que mide un defecto sólo se convierte en no-regresión cuando el defecto se
  cierra y la aserción se invierte de forma explícita.
- NO prueba que la ruta no durable sea inalcanzable: prueba lo contrario para no-Linux (§3.2).
- NO mide RSS del sistema, ni el comportamiento de la caché en hosts con muchos JVM, ni el crecimiento de
  disco a lo largo de días: el soak es de 40 runs en un proceso.
- NO prueba que los números sean estables en otra máquina (los umbrales son diferencias, no valores
  absolutos), ni que el heap se comporte igual con otro GC o con `-Xmx` distinto.
- NO dice nada sobre el `detektTest` pre-existente rojo: lo reporta y lo deja como estaba.
- NO arregla ningún defecto: no cambia una línea de producción. El valor de esta rebanada son las
  mediciones, no una reparación.
```
