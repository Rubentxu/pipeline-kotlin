# El canal de salida: 7 filas rotas y 1 fila ciega por la misma causa

**Estado:** hallazgo del trabajo paralelo en `wt-ga-gate`; **el producto es correcto en ambos casos**, los rojos son del harness.
**Base:** `7b058e0e` (main). Medición sobre `wt-ga-gate`, 2026-10-15T12:38:39Z.

Este documento existe para que nadie "arregle" el producto por un fallo que introdujo el propio
arreglo. Cubre dos síntomas que la nota diagnóstica del `releaseGate` no separó: **7 de los 25
fallos** (`UatDsl003ParallelTest`) y una fila que solo se hizo visible al arreglar las 7.

---

## 0. El síntoma mayor: 7 de los 25 fallos son una sola causa

Antes de G2 está lo que la nota agrupó sin demostrar dentro de sus "18 problemas de harness": **siete
filas de `UatDsl003ParallelTest`, el 28 % de los 25 fallos del `releaseGate`**, todas con el mismo
error:

```text
JSON event document failed to decode:
JSON view: event document must be a single bracketed array, got: [{"eventId":"e-correlated-...
```

El producto emite el documento JSON **correcto**. La cadena de hechos, cerrada en cada eslabón:

```text
JsonEventLog.encode (JsonEventLog.kt:93-101)  construye "[" + events + "]"   SIN newline final
Main.kt:1287-1291                               receipt a stdout                SIN newline final
Main.kt:612 -> writeEventDocument              documento a stdout              SIN newline final
Main.kt:623                                     "Pipeline finished with …" a stderr  CON newline

stdout real        = "{receipt}\n[{…},{…}]"
stdout + stderr    = "{receipt}\n[{…},{…}]Pipeline finished with SUCCESS\n"
lines[1]           = "[{…},{…}]Pipeline finished with SUCCESS"
```

`CliContractTestKit.kt:201` toma **solo `lines[1]`**, y `:237` exige que empiece por `[` **y termine
por `]`**. El array está bien formado; lo que se le soldó encima son 34 caracteres de stderr. `startsWith("[")`
pasa —por eso el extracto de la evidencia empieza por `[`— y `endsWith("]")` falla.

El defecto está en `UatDsl003ParallelTest.kt:230`, que pasa `outcome.stdout + outcome.stderr` bajo el
nombre `stdout`. Los otros tres lectores de eventos **nunca fusionan** — `UatEvt001ReplayTest:58`,
`UatEvt002MultiStepReplayTest:230`, `UatDsl005TimeoutGrammarTest:261` pasan `stdout` puro— y por eso
no se rompieron: fallan después, en el conteo y en el orden, que es otro asunto (§2 del documento de
escalado). **Mismo documento, mismo decoder, distinta fusión: esa asimetría es toda la explicación.**

Es una regresión, no algo longstanding. `7dc8af79` (2026-10-07) migró la clase a `OwnedSubprocess` e
introdujo la fusión, que era inofensiva porque el test decodificaba con el tolerante
`JsonEventLog.decode(stdout.trim())`. `9a1b5836` (2026-10-12) cambió al `decodeLaunchContract` estricto
**sin deshacer la fusión**. Un lado cambió y el otro no lo siguió.

`wt-ga-gate` ya lo deshizo y las 7 filas pasaron. Ese es un A/B ejecutado del mismo cambio, no una
coincidencia.

### El arreglo ya aplicado en `wt-ga-gate`

```diff
-    private fun runBinary(vararg args: String): Pair<Int, String> {
+    private fun runBinary(vararg args: String): CliRun.Completed {
-            is CliRun.Completed -> outcome.exitCode to (outcome.stdout + outcome.stderr)
+            is CliRun.Completed -> outcome
-            stdout = stdout,
-            stderr = "",
+            stdout = output.stdout,
+            stderr = output.stderr,
```

Correcto. Y **al hacerlo destapó G2**, que es lo que sigue.

---

## 1. G2: lo que se vio

En `harness-focused-20261015T123500Z`, `UatDsl003ParallelTest` queda en 9 tests / 2 fallos. Uno es el
drift de eventos ya escalado. El otro:

```text
G2 - stage mixing parallel body with sibling step is rejected fail-closed()
Rejection must name the G2 contract: {"schema":"pipelinek.launch/1",...,"status":"Launched"}
 ==> expected: <true> but was: <false>
```

El `$output` capturado es, byte a byte, **solo** el `LaunchReceipt`. Cero bytes de stderr.

## 2. El producto hace lo correcto

Ejecutado directamente contra la distribución instalada en este worktree:

```console
$ pipelinek run --format json /tmp/g2probe/parallel-mixed-body-sibling.pipeline.kts
EXIT=1
--- STDOUT (280 bytes) ---
{"schema":"pipelinek.launch/1","runId":"0c19a5d5-...","status":"Launched"}
--- STDERR (428 bytes) ---
Exception in thread "main" java.lang.IllegalStateException:
  Stage 'ParallelTest' cannot mix a parallel body with sibling steps
	at ...DslCompiledPipelineCompiler.stageNode(DslCompiledPipelineCompiler.kt:107)
	at ...DslCompiledPipelineCompiler.compile-83gHUBw(DslCompiledPipelineCompiler.kt:78)
	at ...MainKt.main(Main.kt:498)
```

El guard está en `DslCompiledPipelineCompiler.kt:106-107` y es alcanzable: la rama anterior (`:92`)
solo captura `steps.size == 1`, y el fixture `parallel-mixed-body-sibling.pipeline.kts` tiene el
`parallel {}` **más** un `echo()` hermano. El `when` rechaza correctamente, con exit 1, y nombra el
stage y el contrato.

En `main` la fila está **verde**: `1 tests, 0 failures, timestamp 2026-10-15T12:50:47Z`.

## 3. La regresión de G2 es la misma, vista por el otro lado

El arreglo de §0 es correcto **y por eso destapa G2**. Antes, la fusión de streams hacia que la fila
G2 funcionara por accidente: el mensaje de rechazo (stderr) quedaba fundido en el mismo blob que el
test leía. Al separar los canales correctamente, esa falsa passageway desapareció y G2 pasó a leer
`component2() = stdout` — donde no está el rechazo.

Es decir: **G2 llevaba tiempo verde por un error, no por una propiedad.** La fila nunca comprobó lo
que dice comprobar; ocurrió antes de que nadie la tocara.

```kotlin
val (exit, output) = runBinary("run", "--format", "json", mixedBodySiblingScript.toString())
```

Como `CliRun.Completed` es `data class Completed(exitCode, stdout, stderr)`:

```text
component1() -> exitCode   ✅ "exit" sigue siendo el código real
component2() -> stdout     ❌ "output" ya no incluye stderr, donde vive el rechazo
```

El destructuring es lo que permitió que el cambio pasara en silencio: `val (a, b)` no dice qué es `a`
ni qué es `b`, y con un `data class` de tres campos el segundo componente es un salto que el lector no
nota. `runAndDecode`, la otra consumidora del mismo helper, sí lee `stdout` a propósito —decodifica el
documento de eventos, que es stdout por contrato—, así que el tipo único daba el canal equivocado a una
de las dos.

## 4. El arreglo para G2

Nombrar los campos y usar el canal que corresponde a cada aserción:

```kotlin
val completed = runBinary("run", "--format", "json", mixedBodySiblingScript.toString())
assertEquals(1, completed.exitCode, "Mixed parallel+sibling stage must fail closed")
assertTrue(
    "cannot mix a parallel body with sibling steps" in (completed.stdout + completed.stderr),
    "Rejection must name the G2 contract: stdout=${completed.stdout} stderr=${completed.stderr}",
)
```

`runWithDb:318` de ese mismo fichero también liga `stdout` desde `component2`, lo cual es correcto
para su uso de `JsonEventLog.decode`. Solo G2 necesita los dos canales.

**No cambiar el producto.** El guard cumple: falla cerrado, exit 1, mensaje accionable.

## 5. Qué leer de esto

Las 7 filas y la 1 fila son **el mismo error de canal en dos momentos distintos**, y el orden importa:
la primera corrección era correcta y produjo la segunda. Un rojo que aparece al arreglar otro no es un
defecto nuevo — es uno que estaba tapado.

Y hay un aviso para el lector: siete de veinticinco fallos del `releaseGate` estaban resumidos como
"18 problemas de tests/harness" sin que nadie verificara que fueran eso. Eran una sola causa, una línea
de código, y se llevaba **el 28 % del total**. El recuento agregado era correcto; la clasificación de
cada hallazgo, no.

## 6. Hallazgo de producto que sí queda (deuda, no P0)

Lo anterior es que el rechazo sale como **stack trace de una excepción no capturada desde `main`**,
no como un rechazo tipado. Funciona, y el mensaje llega al autor verbatim, pero:

- `DslCompiledPipelineCompiler.kt:107` usa `error()`, que es correcto para el compilador —no sabe
  cómo reportarlo— pero deja que la excepción atraviese `main` sin catch.
- AGENTS.md, "Typed errors are values" §11: las excepciones se reservan para fronteras
  adaptador/framework y defectos del programador. Un `stage` que mezcla un body parallel con steps
  hermanos es **entrada de usuario inválida**, no un defecto del programador: merece un rechazo
  tipado fail-closed en stderr, con exit 1, y no un dump de stack.
- ElLaunchReceipt con `"status":"Launched"` se emite **antes** de compilar (`Main.kt:436-444` frente
  a `:498`), así que un consumidor que solo lea el receipt ve un lanzamiento que después falla. Eso es
  correcto por contrato de join, pero combined con la ausencia de un mensaje de error legible en el
  mismo canal hace que el fallo sea más difícil de diagnosticar de lo que debería.

Coste del arreglo: un catch alrededor de `DslCompiledPipelineCompiler.compile` en ambas ramas
(`Main.kt:498` y `:883`) que convierta la excepción en un rechazo tipado, más una fila que lo fije.
Riesgo: bajo, es la misma forma que ya usan los `catch (e: IllegalArgumentException)` de `:653` y
`:686`. **No se aplica aquí**: es un cambio de producción con su propia WU, su propio receipt y sus
propias pruebas, y el lote de remediación del harness no toca `src/main`.