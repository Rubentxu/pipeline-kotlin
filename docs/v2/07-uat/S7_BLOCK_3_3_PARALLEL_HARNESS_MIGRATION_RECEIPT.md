# S7 BLOCK 3.3 — UatDsl003ParallelTest: la fuga medida y la fila que sí se podía clasificar

Rama `s6-plugin-sdk`. Base: `577b0586` (S7 BLOCK 3.2).

El bloque 3.2 dejó escrito, como hipótesis, que las filas P1/P2/P3 sin clasificar de
`UatDsl003ParallelTest` eran el deadlock manifestándose, y que corrigiendo el harness se podrían
clasificar. Este bloque cierra las dos cosas: la hipótesis era falsa, y el fichero sí tenía
defectos reales — uno de ellos medido en 1147 ficheros.

---

## 1. La hipótesis anterior era falsa

`UatDsl003ParallelTest` tiene tres sitios de lanzamiento y los tres tienen `waitFor()`. Se suponía
que eso era el patrón de deadlock de S6-PRE. **No lo es.** Los tres redirigen stdout a un fichero:

```kotlin
val stdoutFile = Files.createTempFile("uat", ".stdout")
val process = ProcessBuilder(appBin.toString(), "run", script.toString())
    .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
    .redirectErrorStream(true)
    .start()
val exitCode = process.waitFor()
val stdout = Files.readString(stdoutFile).trim()
```

Un fichero no tiene capacidad de buffer. No hay backpressure, luego no hay deadlock posible por esa
vía. Se descartó leyendo el código, y se anota porque la hipótesis se escribió antes de mirar.

Consecuencia sobre la clasificación: **las filas P1/P2/P3 no están sin clasificar por un defecto del
producto.** La clase pasa aislada (`BUILD SUCCESSFUL in 1m 15s`) y el fixture a mano da `EXIT=0`
SUCCESS. Lo único demostrado es que fallan bajo carga en una corrida de módulo, y que la evidencia
que faltaba era el motivo. Migrar la da.

---

## 2. Lo que sí tenía: tres defectos reales

**2.1 `waitFor()` sin deadline.** Si el hijo se cuelga, el test se cuelga, y lo que lo corta es el
`@Timeout(180)` de clase — que mata el test y **deja un `pipelinek` vivo detrás**, sin decir por qué.

**2.2 `Files.createTempFile` sin padre.** Cae en `java.io.tmpdir` y fuga un fichero por fila y por
corrida. Harness Fidelity §4 nombra este modo de fallo por su nombre.

**2.3 La base de datos de P6 también.** `Files.createTempFile("par-p6", ".db")`, que además es el
**sujeto** de la fila: tiene que sobrevivir a la llamada, que es justo lo que `createTempFile` no
garantiza. Ahora va a `@TempDir`.

---

## 3. HALLAZGO MEDIDO: la fuga no era hipotética

```text
ficheros par-p6*.db en /tmp:  72
ficheros uat*.stdout en /tmp: 1075
total:                        1147      3,1 MB
```

Mil ciento cuarenta y siete ficheros acumulados en `/tmp` por corridas anteriores, con algunos
datados del 5 de octubre. Y la comprobación de que la versión migrada ya no fuga no se hizo
suponiendo, sino contando en una ventana medida:

```text
marca: 1791403906 (22:11:46)
BUILD SUCCESSFUL in 1m 13s
ficheros creados despues de la marca: 0
```

Los 1147 se limpiaron con `mavis-trash` y quedaron a cero.

Que la fuga fuese real y no teórica cambia lo que este bloque demuestra: no es higiene preventiva,
es deuda ya acumulada y medida.

### 3.1 CORRECCION a "quedaron a cero"

**Esa frase era cierta y estaba mal leída.** Se midió y se limpió con los dos patrones de este
arnés (`par-p6*.db`, `uat*.stdout`), y a cero quedaron esos dos patrones. No se inventarió `/tmp`.

La corrida completa del gate (seccion 9) lo corrigio midiendo el residuo **sin acotar por patron**, y el
inventario real era bastante mayor: 224 entradas de primer nivel mas **2255 ficheros dentro de
`/tmp/uat008-debug/` (9,0 MB)**, de otros tres arneses. Todo atribuido y limpio en 9.2.

La leccion que si se sostiene, y que es la que importa para el gate: **la ventana medida con
`mtime` sobre el patron correcto es la unica forma de afirmar fugas.** Un "quedaron a cero" sobre
un patron es un hecho; sin nombrarlo, se lee como "no hay fugas", y habia tres arneses escribiendo.

---

## 4. La forma nueva

Un solo propietario del proceso, y el fichero temporal desaparece en vez de ganar un padre:

```kotlin
private fun runBinary(vararg args: String): Pair<Int, String> =
    when (val outcome = OwnedSubprocess.run(
        command = listOf(appBin.toString()) + args,
        timeout = CLI_DEADLINE,
    )) { … }
```

Los tres sitios pasan por ahí. `output` es stdout seguido de stderr, que es lo que
`redirectErrorStream(true)` producía: la clase sólo usa `contains` y decodificación del log, así que
nada dependía del entrelazado. stderr y stdout **separados a la entrada**, de modo que un crash de
JVM ya no se esconde detrás de un stdout a medio vaciar.

### El deadline va POR DEBAJO del watchdog

```kotlin
val CLI_DEADLINE: Duration = Duration.ofMinutes(2)   // el subproceso
@Timeout(180)                                        // el watchdog exterior, 3 min
```

El orden es el punto. `@Timeout` es el watchdog de "este test está roto": corta el test, deja el hijo
vivo y no puede decir por qué. El deadline del subproceso salta primero, produce un `TimedOut` con
thread dump y salida parcial, y además respalda el árbol. Un cuelgue pasa de ser un timeout desnudo
a ser un hecho clasificado.

`KNOWN_DEBT` baja de **26 a 25**.

---

## 5. No-vacuidad

**Mutante:** `CLI_DEADLINE` a 1 ms. La forma vieja no podía probar esto bajando un valor, porque
`waitFor()` no tenía ninguno que bajar; por eso la migración necesita existir antes de poder medirse.

| | control | mutante |
|---|---|---|
| `BUILD` | SUCCESSFUL | **FAILED** |
| tests | 9 | 9 |
| failures | 0 | **9** |
| mensaje | — | `the installed binary hung on [run, …] after 0s; pid=429040 descendants=[]` |
| huérfanos | 0 | **0** |

Las nueve filas que lanzan quedan en rojo, todas con un mensaje **clasificado** que nombra el pid, y **cero
procesos supervivientes**: el deadline es del hijo, se dispara, clasifica y respalda. Las dos cosas
que la forma anterior no podía dar.

Restauración verificada byte a byte:

```text
antes=f0a0591c7f8987ad895a3801aefd76147ac9bd4d70cff460f9a058e342a4c5f6
ahora=f0a0591c7f8987ad895a3801aefd76147ac9bd4d70cff460f9a058e342a4c5f6
```

(El mensaje del mutante dice `after 0s` porque 1 ms se trunca a 0 en `.seconds`. Es un artefacto del
valor de la mutación, no del código en su configuración real de 2 min.)

---

## 6. Clasificación de P1/P2/P3, y con qué evidencia

```text
VEREDICTO           NOT_REPRODUCIBLE_IN_ISOLATION
CAUSA               desconocida; no es un defecto de producto demostrado
EVIDENCIA           la clase pasa aislada; el fixture a mano da EXIT=0 SUCCESS
LO QUE FALTA        el motivo real de la corrida de modulo en la que fallo, que
                    este bloque deja de perder: un cuelgue ahora trae pid,
                    descendientes, thread dump y salida parcial
RIESGO RESIDUAL     sigue sin saberse si bajo carga falla por tiempo, por
                    contencion o por otra causa; NO se declara resuelto
```

Lo que este bloque **no** hace es declarar el problema cerrado. Lo que hace es quitarle la
ambigüedad estructural: la evidencia que faltaba ya existe en el harness, y la siguiente vez que
falle dirá por qué.

---

## 7. Lo que este bloque NO demuestra

- **Que P1/P2/P3 estén resueltas.** Siguen sin clasificarse; sólo se ha dejado de perder el
  diagnóstico.
- **Que la clase no falle bajo carga.** El gate de este bloque es la primera ejecución en condición
  completa desde la migración; si aparece un fallo, ahora traerá su motivo.
- **Nada sobre los otros 24 ficheros de `KNOWN_DEBT`.**

---

## 8. Ficheros

| Fichero | Cambio |
|---|---|
| `.../UatDsl003ParallelTest.kt` | tres sitios de lanzamiento unificados en `runBinary`; `CLI_DEADLINE` por debajo del watchdog; base de datos de P6 a `@TempDir` |
| `.../support/InstalledDistributionHarnessFitnessTest.kt` | `KNOWN_DEBT` 26 → 25 y sus contadores en prosa |

Ningún fichero de producción modificado.

---

## 9. Evidencia ejecutada

### 9.1 Gate prescrito

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

inicio (epoch):  1791404129   22:15:29
fin    (mtime):   1791405968   22:46:08
duracion:         1839 s

BUILD SUCCESSFUL in 30m 38s
320 actionable tasks: 320 executed
lineas '^e: ' en el log: 0
```

Ninguna tarea quedo `UP-TO-DATE` (`--rerun-tasks`), y `^e: ` a cero descarta el falso verde de una
compilacion fallida.

El log contiene **cuatro** lineas `BUILD SUCCESSFUL` (364, 484, 673 y 1533). Las tres primeras son de
2s, 3s y 6s: las generan fixtures que invocan Gradle por su cuenta. Contarlas como si fueran la
corrida seria leer mal el log, igual que `pgrep -f` contandose a si mismo.

### 9.2 Recuento acotado por `mtime` del arranque

No se conto la salida de Gradle: se contaron los XML de `test-results` con `mtime >= 1791404129`.

```text
ficheros XML en la ventana: 779
clases:                      779
tests:                      5157
fallos:                        0
errores:                       0
skips:                      140
```

`5157` es el mismo total que en BLOCK 3.2, y tiene que serlo: este bloque **no anadio ni quito
filas**, solo bajo `KNOWN_DEBT` de 26 a 25. Un total distinto habria significado que la migracion
habia roto el recuento de la suite que sostiene H8-10.

### 9.3 Supervivientes

Comprobado por `exe` real en `/proc/<pid>/exe`, nunca por `pgrep -f` (que se auto-coincide):

```text
procesos con exe *pipelinek* o *SubprocessFixtureProgram*: 0
```

Un unico proceso Java seguia vivo al terminar, y **no** es un hijo de este arnes:

```text
PID 3389827  exe = .../temurin-21.0.8.../bin/java
             cmdline = -cp .../.gradle/caches/8.14.5/workerMain/gradle-worker.jar:...
             PPid = 3001 (/usr/lib/systemd/systemd --user)
```

Es un worker de Gradle reparentado a systemd porque el lanzador que lo creo ya salio. Su `exe` es
`java`, no `pipelinek`, y su classpath es `gradle-worker.jar`: es infraestructura de la
construccion. No se cuenta como superviviente **ni** como fuga, porque atribuirlo al arnes seria
nombrar un culpable que no lo es.

### 9.4 HALLAZGO MEDIDO: fugas de la corrida completa, atribuidas

Con `mtime` en `[1791404129, 1791405968]`, por prefijo:

| patron | ficheros | bytes | dueno atribuido |
|---|---|---|---|
| `uat*.stdout` | 5 | 49.900 | `UatDsl005TimeoutGrammarTest` |
| `t21-marker`, `t21*.stdout` | 2 | 3.984 | `UatDsl005TimeoutGrammarTest` fila T21 |
| `t22*.stdout` | 1 | 2.222 | `UatDsl005TimeoutGrammarTest` fila T22 |
| `uat-evt002-control*` | 1 | 120 | `UatEvt002MultiStepReplayTest:169` |
| `uat008-debug/*` | 34 | 53.048 | `UatLocal008CredentialsTest:1353-1359` |
| **`par-p6*`** | **0** | **0** | **`UatDsl003ParallelTest`, el arnes migrado** |
| **total** | **43** | **109.274** | cuatro arneses |

La atribucion no es una conjetura de nombre: `UatDsl005TimeoutGrammarTest` tiene 7 filas, y
exactamente 5 de ellas alcanzan un `createTempFile("uat", ".stdout")` sin padre — una en linea 46 y
cuatro por `runAndDecode()` en linea 225. Salen 5. `T21` escribe su propio `t21-marker` desde el
script que ejecuta el producto, y por eso sobrevive aunque el fixture `.pipeline.kts` se borre con
`deleteOnExit()`. Los `.pipeline.kts` de T21 y T22 si se limpian; solo quedan los `.stdout`.

`par-p6*` a cero en la **corrida completa** es una afirmacion mas fuerte que la de la seccion 3,
que era de una ventana dirigida: aqui la migrada corrio dentro de 5157 tests y no escribio nada.

### 9.5 Limpieza

```text
antes:   243 entradas de primer nivel en /tmp con los 4 prefijos
         + 2255 ficheros dentro de /tmp/uat008-debug/  (9,0 MB solo ese directorio)
despues: 19 entradas, y ninguna es residuo de test
```

Las 19 que quedan son artefactos de trabajo de sesiones previas escritos a mano
(`uat028-*.sh`, `uat-evidence*.json`, `uat-lane.mjs`, `uat.log`, ...), no salidas de test, y **no se
tocan**: son evidencia de trabajo anterior. `mavis-trash` no las ha tocado y no se le pasa.

### 9.6 Lectura sin medir, que NO se afirma

`UatDsl005TimeoutGrammarTest` tiene cuatro sitios con `.redirectError(ProcessBuilder.Redirect.PIPE)`
seguido de `waitFor()` **sin plazo**, y el `errorStream` se lee **despues** de `waitFor()` y solo en
la rama de fallo (`runAndDecode`, lineas 226-234). Leido, eso es el patron clasico de bloqueo por
buffer de tuberia: si el hijo escribe mas que el buffer, `waitFor()` no vuelve nunca.

**No se ha medido que ocurra.** No hay corrida que lo demuestre, y esta clase pasa 7/7 en el gate
de este bloque. Se deja escrito como `READING_NOT_MEASURED` y es exactamente lo que BLOCK 3.4 tiene
que medir o refutar. Afirmarlo aqui seria el mismo error que la hipotesis de la seccion 1.
