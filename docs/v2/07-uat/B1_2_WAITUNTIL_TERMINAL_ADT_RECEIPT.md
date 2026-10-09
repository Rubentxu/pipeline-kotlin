# B1.2 — `waitUntil`: la terminal deja de ser un `String`

**Slice:** B1.2 del plan por bloques B0..B7 (ROADMAP §13.3)
**Fecha:** 2026-10-08
**Ciclo SDDK:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk` (OPEN/build, lease `orchestrator`)
**Base:** `dd089e55b54dc448ee862ae5a7d6cc3d32761d9f`
**Resultado:** `PASS`

---

## 1. Qué capability obtiene el usuario

Un `waitUntil` que **no puede mentir sobre por qué terminó**.

Antes, `core.waitUntil` llevaba su resultado como texto y lo interpretaba así:

```kotlin
override val outcome: StepOutcome
    get() = if (resultOutcome == "completed") StepOutcome.Success else StepOutcome.Failure(TIMEOUT, …)
```

Ese `else` es el defecto. Cualquier valor que no sea exactamente `"completed"` — un typo, un
terminal que una versión futura añada, un journal corrupto — se convertía en **un timeout que
la ejecución nunca alcanzó**, con un mensaje que hablaba de una espera que nunca se excedió. Una
ejecución terminaba como deadline sin haber tenido deadline, y el mensaje era falso.

Ahora el valor es un ADT cerrado de tres casos y el texto es una **proyección** de ese ADT, no su
origen. Un valor desconocido se **rechaza nombrando el valor**, en vez de convertirse en un
fallo inventado.

## 2. Defecto corregido, con su antes y su después

| | Antes | Después |
|---|---|---|
| Tipo del terminal | `String` | `WaitUntilCompletion` (3 casos cerrados) |
| `StepOutcome` | `if (token == "completed") Success else Failure(TIMEOUT)` | `completion.toStepOutcome()` |
| Token de wire | campo almacenado (**segunda autoridad**) | propiedad derivada del ADT |
| Decoder | aceptaba cualquier `String` | rechaza por nombre lo que no puede nombrar |
| authority direction | token → decisión | **ADT → token**, y el token nunca decide |

La línea que cambió, literalmente:

```diff
 data class WaitUntilOutput(
-    val resultOutcome: String, // "completed" or "deadline-exceeded"
+    val completion: WaitUntilCompletion,
     val totalAttempts: Int,
     val totalDurationMs: Long,
 ) : TypedStepOutput {
-    override val outcome: StepOutcome
-        get() = if (resultOutcome == "completed") StepOutcome.Success else StepOutcome.Failure(…)
+    val resultOutcome: String get() = completion.wireOutcome
+    override val outcome: StepOutcome get() = completion.toStepOutcome()
 }
```

### Por qué `resultOutcome` sigue existiendo

Porque el payload `outcome` es **superficie de scripting publicada** y congelada. El JSON
**no se mueve**: sigue siendo `{"kind":"waitUntil","outcome":"completed","totalAttempts":3,
"totalDurationMs":1500}`, byte a byte, y un journal anterior sigue decodificando. Lo que cambió
es la dirección de la autoridad, no el contrato observable. Por eso esto **no** es una ruptura de
compatibilidad y no requiere ADR (DR-10: la composición de identidad durable no se toca; el
`runtimeCompatibilityVersion` no se mueve porque el valor serializado es idéntico).

## 3. Lo que ya existía, y por qué esto no inventó un tipo

`WaitUntilCompletion` **ya estaba** en `pipeline-domain` con los tres terminales del dominio, y
`WaitUntilEngine` ya proyectaba sus cinco puntos de emisión desde él. El `String` era **la última
autoridad textual que quedaba**, y `FArchE4b4WaitUntilTerminalAuthorityTest` la tenía fichada
por fichero y línea con esta razón:

> "the handler is a non-routed registry candidate (§5.3)"

**Esa razón era falsa.** `core.waitUntil` es REGISTRY_PRIMARY desde WU-LPR-301 / G5 (2026-09-18) y
`LEGACY_PLUGIN_IDS` es el conjunto **vacío**. El handler no era un stub no-ruteado: era la ruta de
producción. La exención se concedió sobre una premisa que el código ya había superado, y por eso
la deuda no se cerraba nunca. Queda escrito en el KDoc de la ley, porque **una entrada de
allowlist es un argumento, y los argumentos se pudren**.

## 4. Evidencia ejecutada

### RED válido primero (no un error de compilación)

El primer intento de RED **no contaba**: el test nuevo usaba `completion = …` y no compilaba contra
el tipo viejo. AGENTS.md (HARNESS FIDELITY §6) dice que un `compileTestKotlin` fallido no es un
RED — Gradle ejecuta la clase compilada previamente. Se descartó ese test, se movió fuera del
árbol de compilación, y se escribió el RED contra el decoder, que el código viejo sí expresa:

```
cd v2 && ./gradlew :pipeline-application:test --tests '*WaitUntilOutputCodecFailClosedTest*'
tests=3 failures=1 errors=0
  FALLA [an unknown terminal is rejected by name instead of becoming a failure]:
    The codec accepted the unknown terminal 'Completed'.
```

Las otras dos filas pasaban — los tres tokens congelados decodificaban y la no-vacuidad se
sostenía. El RED era del defecto, no del harness.

### GREEN directed

```
cd v2 && ./gradlew :pipeline-application:test --tests '*WaitUntil*'
BUILD SUCCESSFUL in 47s
73 tests · 0 failures · 0 errors
```

Incluye los 8 nuevos (3 fail-closed + 5 ADT) y los 65 preexistentes de la familia.

### El fitness reaccionó, y reaccionó bien

```
cd v2 && ./gradlew :pipeline-architecture-tests:test --tests '*FArchE4b4*'
RED [the emissions outside the durable engine are pinned at three]
  Two production sites... [BodyExecutionEngine.kt:324, BodyExecutionEngine.kt:342]
```

El baseline de emisiones **bajó de 3 a 2** porque la migración eliminó una emisión, y la ley lo
detectó sola. Eso es lo que un baseline bien puesto tiene que hacer: premiar el arreglo en lugar
de congelar el número. La allowlist de lecturas pasó a **vacía** — la forma más fuerte de la ley,
donde ya no hay ningún perdón por fichero.

## 5. Mutaciones negativas

Cada una atribuida 1:1 a las filas que la matan. Restauradas y verificadas por SHA-256
(`33a03e6a7833167a56a323ba96437c04b97a0b2bfae14f92b1610315000c724c`, idéntico al pre-mutación).

| # | Mutación | Muerta por | READ(s) |
|---|---|---|---|
| 1 | El `else` del decoder vuelve a coercionar a `DeadlineExceeded` (el defecto original, literal) | `WaitUntilOutputCodecFailClosedTest` + `WaitUntilTerminalAdtTest` | 2 |
| 2 | `WaitUntilOutput` vuelve a derivar `outcome` comparando `resultOutcome == "completed"` (la autoridad `String`) | 2 tests de la familia + `FArchE4b4WaitUntilTerminalAuthorityTest` | 3 |

La mutación 2 importa por una razón concreta: la ley fitness la atrapó **sin ninguna excepción
allowlisted**. Antes de este slice, ese mismo defecto estaba **pardonado**. La ley dejó de ser un
registro de deuda y pasó a tener dientes de verdad.

### Un fallo de harness que casi se convierte en verde falso

La primera corrida conjunta de `:pipeline-application:test` y `:pipeline-architecture-tests:test`
usó dos flags `--tests` en una línea. **Gradle aplica `--tests` solo al último target**: la tarea
de arquitectura quedó `UP-TO-DATE` y no generó XML. Interpretarlo como "la ley no falló" habría
sido un verde falso por construcción — exactamente la clase de error que este bloque persigue.
La ley se re-ejecutó sola y ahí sí produjo su RED. Registrado porque el modo de fallo (un flag
ignorado en silencio) es indistinguible de un verde si no se mira el XML.

## 6. Verificación

| Nivel | Alcance | Resultado |
|---|---|---|
| L0 | compilación | OK, sin `^e:` |
| L1/L2 | familia `WaitUntil` dirigida | 73 tests, 0 F, 0 E |
| L2 | `FArchE4b4` (ley) | 4 tests, verde tras actualizar el baseline |
| ADVERSARIAL | 2 mutaciones | ambas muertas |
| L4 | `:pipeline-application:test` + `:pipeline-architecture-tests:test` + detekt ambos | **BUILD SUCCESSFUL in 27m46s** · 3041 tests (342+104 clases), 0 F, 0 E, 131 skipped |
| L5 | `check` completo | **BUILD SUCCESSFUL in 23m37s** · 793 clases, 5222 tests, 0 F, 0 E, 140 skipped, 325 tareas (62 ejecutadas, 239 up-to-date) |

Detekt murió la primera vez y el arreglo fue el código, no la regla: mis dos ficheros nuevos no
terminaban en newline (`NewLineAtEndOfFile`). No se silenció nada.

## 6 bis. Contaminación ambiental registrada

El primer L4 falló dos veces con `Gradle build daemon disappeared unexpectedly`, sin ningún
error de compilación ni de test. La causa no fue este slice: **otro agente estaba ejecutando Gradle
en `PipelinekFabric` al mismo tiempo** (tres daemons con JVM distintos compitiendo). Se confirmó
con `ps` y se aisló la corrida con `--no-daemon`. Se registra porque el síntoma — `exit=1` con
cero clases ejecutadas — es indistinguible de un fallo de código si no se mira el XML: unBuild
que muere por falta de recursos produce exactamente la misma salida que uno que muere por un
test rojo.

## 7. Deuda que queda, con dueño

- **`BodyExecutionEngine.kt:324` y `:342`** siguen construyendo `WaitUntilCompleted` con un
  literal. Son la ruta no durable y están **fichados por el baseline de 2 emisiones**, que falla si
  crecen y falla si alguien los migra sin decirlo. No es deuda escondida.
- **La ley fitness tenía un límite declarado** que este slice no arregla: la ventana de la cláusula
  3 es "esta línea más las dos de arriba", así que un `when` cuyo sujeto está más lejos se
  escaparía. Está escrito en el propio KDoc, no es un límite nuevo descubierto por mí.

## 8. Cierre de referencia de implementación

```text
Reference implementation consulted:  WaitUntilCompletion + FArchE4b4WaitUntilTerminalAuthorityTest
                                      (propios del repo, ya existentes y ya vigentes)
Behaviour adopted:                   el ADT cerrado ya proyectado por el motor durable, aplicado
                                      a la ruta no durable que quedaba
Intentional deviations:              ninguno — no se creó ningún tipo nuevo
Security implications reviewed:      n/a para este WU (no toca credenciales ni superficie expuesta)
Tests demonstrating the contract:    WaitUntilOutputCodecFailClosedTest (3),
                                      WaitUntilTerminalAdtTest (5),
                                      FArchE4b4WaitUntilTerminalAuthorityTest (4, allowlist vacía)
```

## 9. Lo que este slice NO dice

No dice que `core.waitUntil` esté certificada. El handler de `CoreWaitUntilStep` sigue siendo un
stub que asume la condición satisfecha (`conditionResult = true`), y por §5.3 no es la ruta que
ejecuta el bucle de polling en producción. Este slice arregla **el terminal**, no el bucle. Que el
stub sea alcanzable por el registry y que el bucle real viva en el motor canónico es un
hallazgo **medido y registrado** en `B1_BLOCK_EXIT_RECEIPT.md` §AUD-03, y cerrarlo requiere el
gate propio de un body Step, no una rebanada de tipado.
