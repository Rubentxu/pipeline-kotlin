# S7 BLOCK 3.2 — HttpInstalledUatTest deja de lanzar la distribución por su cuenta

Rama `s6-plugin-sdk`. Base: `164b9a42` (S7 BLOCK 3.1, H8-10).

El bloque anterior cambió **qué** afirmaba la fila H8-10 y dejó escrito que su harness seguía
lanzando el binario instalado con un `ProcessBuilder` propio. Este bloque cambia **cómo** lo lanza.

---

## 1. La forma que había

```kotlin
val builder = ProcessBuilder(binary.absolutePath, *args).redirectErrorStream(true)
builder.environment().putAll(env)
val proc = builder.start()
val poller = ProcessPeakRss.poll(proc.pid())
onStart(proc.pid())
if (!proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) { … }
return CliResult(exitCode = proc.exitValue(), output = proc.inputStream.bufferedReader().readText(), …)
```

Esperar y **después** leer. Es la forma que S6-PRE investigó y retiró, y aquí no era hipotética: esta
suite ejecuta cuerpos HTTP de hasta 512 MiB y `Main.kt:431` imprime el log entero de la corrida en un
solo `println`, con lo que el pipe de 64 KiB se rebasa con facilidad en las filas que_assertan sobre
la salida.

Migrado a `OwnedSubprocess.run(...)`. `output` pasa a ser `stdout` seguido de `stderr` en vez de un
único flujo entrelazado: las 33 aserciones del fichero son o comprobaciones de subcadena o
`takeLast(...)` para construir un mensaje de fallo, y ninguna depende del entrelazado. El cambio se
declara porque es un cambio, no porque sea gratis.

`KNOWN_DEBT` baja de **27 a 26**.

---

## 2. HALLAZGO: extender la primitiva abrió un agujero en ella

`HttpInstalledUatTest` necesita el pid del hijo para dos cosas reales: arrancar el polledor de pico de
RSS y leer `/proc/<pid>/cmdline` para verificar que el presupuesto de heap está aplicado. Se añadió
por tanto `onStart: (Long) -> Unit` a `OwnedSubprocess.run`, y se colocó **fuera** del `try` cuyo
`finally` es la garantía de propiedad.

Eso significa que si el observador del llamante lanza — un `ProcessPeakRss.poll` que falla, una
lectura de `/proc` que no cuadra — la excepción escapa de `run()` **con el hijo corriendo**. Justo el
resultado que las otras cuatro filas de `OwnedSubprocessRunTest` existen para impedir.

Movido dentro del `try`, con la fila que lo ata:

```kotlin
@Test
@DisplayName("un observador que lanza no deja el hijo vivo")
fun anObserverThatThrowsStillLeavesNoChild() { … }
```

La fila exige dos cosas discretas: que la excepción del observador **propaga** (tragársela
reportaría un resultado de una corrida que nadie observó) y que el proceso esté muerto.

---

## 3. No-vacuidad, con un huérfano real

**Mutante:** devolver `onStart` a su posición original, fuera del `try`.

| | control | mutante |
|---|---|---|
| `BUILD` | SUCCESSFUL | **FAILED** |
| tests | 5 | 5 |
| failures | 0 | **1** |
| fila muerta | — | `un observador que lanza no deja el hijo vivo` |

Las otras cuatro sobreviven. Y el dato que importa no es el fallo sino lo que había debajo:

```text
org.opentest4j.AssertionFailedError: the child must be dead even though the caller's
observer blew up on start; a leaked JVM degrades every measurement that follows it
==> expected: <false> but was: <true>
```

```text
$ pgrep SubprocessFixtureProgram
2667584 … dev.rubentxu.pipeline.v2.application.support.SubprocessFixtureProgram grandchild
2667839 … dev.rubentxu.pipeline.v2.application.support.SubprocessFixtureProgram hang
```

**Dos procesos Java vivos** después del mutante. El defecto no era hipotético: la extensión lo había
introducido de verdad. Los huérfanos se limpiaron a mano y se verificó que quedaban a cero.

Restauración verificada byte a byte:

```text
antes=86656d8e765d66dbc85bddff3324ee6b3e5681ac1b0c57395e947b4830ff0106
ahora=86656d8e765d66dbc85bddff3324ee6b3e5681ac1b0c57395e947b4830ff0106
```

La primera restauración **no** dio hash idéntico porque reescribí el comentario en vez de
reproducirlo; se corrigió hasta que la comparación byte a byte pasó. Un hash que no coincide es
información, no un trámite que se repite hasta que sale bien.

---

## 4. Corrección: `UatDsl003ParallelTest` NO es un caso de deadlock

Al inventariar los 26 harnesses restantes se dejó escrito, como hipótesis, que las filas P1/P2/P3 sin
clasificar de `UatDsl003ParallelTest` eran el deadlock manifestándose: el fichero tiene tres
lanzamientos, tres `waitFor()` y era el único que además fusionaba stderr con `redirectErrorStream`.
**La hipótesis era falsa y se descartó leyendo el código.**

Los tres sitios redirigen stdout a un **fichero temporal**:

```kotlin
val stdoutFile = Files.createTempFile("uat", ".stdout")
val process = ProcessBuilder(appBin.toString(), "run", script.toString())
    .redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()))
    .redirectErrorStream(true)
    .start()
val exitCode = process.waitFor()
val stdout = Files.readString(stdoutFile).trim()
```

Un fichero no tiene capacidad de buffer, así que no hay backpressure y no hay deadlock posible por
esa vía. Lo que sí tiene son otros dos defectos reales:

1. `process.waitFor()` **sin timeout**: si el hijo se cuelga, la clase entera espera hasta que salta
   el `@Timeout(180)`, que corta el test y deja un `pipelinek` vivo detrás.
2. `Files.createTempFile` **sin padre**: cae en `java.io.tmpdir` y fuga un fichero por fila y por
   corrida, que es exactamente el modo de fallo que Harness Fidelity §4 prohíbe en los arneses.

Ninguno de los dos se arregla suponiendo: ambos se corrigen migrando a `OwnedSubprocess`, que además
lleva diagnóstico de cuelgue para poder clasificar por fin las filas P1/P2/P3 sin adivinar.

Queda anotado porque la hipótesis se escribió antes de mirar, y una hipótesis escrita antes de mirar
es exactamente lo que este bloque iba a cerrar.

---

## 5. Lo que este bloque NO demuestra

- **No clasifica las filas P1/P2/P3 de `UatDsl003ParallelTest`.** El diagnóstico del cuelgue queda
  disponible al migrar ese fichero; la clasificación es el bloque siguiente y no se anticipa aquí.
- **`HttpInstalledUatTest` no se ha hecho hermético.** Migrarlo corrige la propiedad del proceso y
  el drenaje de pipes; no cambia los `Files.createTempFile` que esa suite pueda seguir usando.
- **No se afirma nada sobre las otras 25 entradas de `KNOWN_DEBT`.** De las 26, sólo una se migra
  aquí.

---

## 6. Ficheros

| Fichero | Cambio |
|---|---|
| `.../support/CliRun.kt` | `onStart` añadido a `OwnedSubprocess.run`, invocado **dentro** del `try` de propiedad y **después** de arrancar los drenadores |
| `.../support/OwnedSubprocessRunTest.kt` | fila de falsificación `anObserverThatThrowsStillLeavesNoChild` |
| `.../cli/HttpInstalledUatTest.kt` | `run()` delegado a `OwnedSubprocess`; `TimeUnit` libre, `Duration` añadido |
| `.../support/InstalledDistributionHarnessFitnessTest.kt` | `KNOWN_DEBT` 27 → 26 y sus contadores en prosa |

Ningún fichero de producción modificado.

---

## 7. Evidencia ejecutada

Gate prescrito, sobre el árbol de este bloque:

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

BUILD SUCCESSFUL in 31m 51s
320 actionable tasks: 320 executed
```

`320 executed` y ninguna `up-to-date`: corrida completa.

Recuento desde los XML de `test-results`, acotados por la marca de arranque:

```text
779 clases | 5157 tests | 0 fallos | 0 errores | 140 skips
```

Delta **+1** frente al gate de `164b9a42`, y es exactamente la fila de falsificación que este bloque
añade (`anObserverThatThrowsStillLeavesNoChild`). La migración no crea ni elimina ninguna otra fila:
`HttpInstalledUatTest` sigue teniendo 16, y el resto de la matriz no se toca.

Estado de las tres suites afectadas dentro de ese mismo gate:

```text
HttpInstalledUatTest                 tests=16 failures=0 errors=0
InstalledDistributionHarnessFitness  tests=1  failures=0 errors=0
OwnedSubprocessRunTest               tests=5  failures=0 errors=0
```

Cero procesos `pipelinek` ni fixtures `SubprocessFixtureProgram` supervivientes después del gate,
comprobado por `exe` real y no por coincidencia de texto en la línea de órdenes — que es como este
bloque se mató a sí mismo una vez al limpiar los huérfanos del mutante.