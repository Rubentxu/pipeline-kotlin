# B1c — AUD-08 lock keying, and the observability CLI argument/exit-code contract

**Rama:** `s6-plugin-sdk` · **HEAD al trabajar:** `1dbc01b2960b858ca120beef746dbbef0a198f74`
(la sesión arrancó sobre `03b98b62`; el root commiteó B1b y un doc de roadmap durante el trabajo, y
estos cambios quedan encima. Base efectiva de la medición: `1dbc01b2`, árbol limpio + este trabajo
sin commitear).
**Alcance:** dos bloques acotados. Producción tocada en 3 ficheros; tests nuevos y una fila de B1a
invertida. **Sin commitear**: el root alinea y commitea.
**Base del encargo:** `docs/v2/07-uat/B1A_CHARACTERIZATION_RECEIPT.md` §8 (AUD-08) y §10 (detekt).

---

## 0. Qué se entregó

```text
BLOQUE 1 — AUD-08, keying de locks (defecto medido en B1a; NO la retención)
  M  v2/pipeline-output-store/src/main/kotlin/.../SegmentOutputStore.kt
  A  v2/pipeline-output-store/src/test/kotlin/.../OutputPruneLockKeyingTest.kt
  M  v2/pipeline-application/src/test/kotlin/.../durable/B1aOutputPlaneProviderLifecycleCharacterizationTest.kt

BLOQUE 2 — AUD-04 / AUD-05, CLI de observación
  M  v2/pipeline-application/src/main/kotlin/.../MainEventsCli.kt        (AUD-04 + AUD-05)
  M  v2/pipeline-application/src/main/kotlin/.../MainConsoleCli.kt       (AUD-04)
  A  v2/pipeline-application/src/test/kotlin/.../MainEventsCliArgumentContractTest.kt
  A  v2/pipeline-application/src/test/kotlin/.../MainConsoleCliArgumentContractTest.kt
  M  docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md                 (§12, contrato)

NO TOCADO (decisiones deliberadas, fuera de alcance)
  RetainUntil.ExplicitReleaseOnly en CompositionRoot: NO se cambia.
  OutputPlaneProvider.forget / forgetAll: siguen SIN llamante de producción (documentado aquí).
```

---

## 1. AUD-08 — el keying de locks: causa medida y arreglo

### 1.1 Causa exacta

`SegmentOutputStore` guarda un `ReentrantLock` por stream en `perStream`. El **escritor** lo keyaba
por el [OutputStreamId] **crudo** (`run/op/transcript`) desde `withStreamLockFor`; `prune` lo keyaba
por el **nombre de directorio** (`safe()` pliega `/` sobre `_`: `run_op_transcript`). Las dos claves
nunca coincidían, así que liberar una run **añadía** una segunda entrada de lock permanente por
stream en lugar de reutilizar la del escritor. B1a lo midió: 40 runs → 40 locks tras el soak, **80**
tras liberarlas.

### 1.2 Arreglo (una sola clave canónica)

La clave canónica es `safe(stream.value)`, que **es exactamente** el nombre de directorio que
`layout(stream)` resuelve. No es una convención cómoda: dos ids que pliegan al mismo nombre ya
comparten el mismo directorio en disco, luego **son el mismo stream** y deben compartir su lock.

```kotlin
private val perStream = HashMap<String, ReentrantLock>()          // era HashMap<OutputStreamId, …>

private fun <T> withStreamLockFor(stream: OutputStreamId, block: () -> T): T {
    val streamLock = synchronized(perStream) { perStream.getOrPut(streamKey(stream)) { ReentrantLock() } }
    return streamLock.withLock { block() }
}

private fun streamKey(stream: OutputStreamId): String = safe(stream.value)

// prune:
val streamLock = synchronized(perStream) {
    perStream.getOrPut(dir.fileName.toString()) { ReentrantLock() }   // == streamKey(stream) del escritor
}
```

Sin cambio de firma pública: `streamKey` es privada y el puerto (`OutputAppendPort`/`OutputReadPort`/
`OutputRetentionPort`) no se toca. La semántica de retención **no** se modifica.

### 1.3 Fila de B1a invertida (transición explícita, HFL §5)

La fila (b) de `B1aOutputPlaneProviderLifecycleCharacterizationTest` afirmaba el defecto
(`soakRuns * 2`). B1a §14 ya declaró que «una caracterización que mide un defecto sólo se convierte
en no-regresión cuando el defecto se cierra y la aserción se invierte de forma explícita». Se invirtió
a `soakRuns`, con mensaje `NON-REGRESSION (AUD-08, was a characterisation of a defect until B1c)`.
**Ningún test se borró, se debilitó ni se deshabilitó**; sólo se invirtió la fila que medía el defecto.

---

## 2. AUD-04 / AUD-05 — CLI de observación

### 2.1 AUD-04 — `MainEventsCli`: opción desconocida y posicional de más

```text
ANTES:  else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]
        `--bogus` se ignoraba; un segundo posicional se descartaba en silencio; la CLI seguía y
        devolvía 0 por un read distinto del que se escribió.
AHORA:  else -> when {
            arg.startsWith("--") -> "Error: unknown option: $arg" ; return 2
            runId != null        -> "Error: unexpected extra argument: $arg" ; return 2
            else                 -> runId = arg
        }
```

### 2.2 AUD-04 — `MainConsoleCli`: `--max-bytes` no convertible

```text
ANTES:  "--max-bytes" -> maxBytes = args.getOrNull(++i)?.toIntOrNull() ?: DEFAULT
        `--max-bytes abc` caía al default en silencio, mientras el propio fichero ya rechazaba
        `maxBytes <= 0` unas líneas más abajo: incoherente consigo mismo.
AHORA:  se captura el texto crudo y se valida una sola vez:
            parsed == null || parsed <= 0 -> "Error: --max-bytes must be a positive integer, got: …" ; return 2
```

### 2.3 AUD-05 — el contrato del exit code, declarado y fijado

El triage fija el remedio: **declarar el contrato del exit code, que hoy no existe**. No se inventa
ningún código nuevo ni se cambia ninguno existente: `Stalled` ya devolvía `0`, y el contrato lo
declara como decisión. Se añade `MainEventsCli.exitCodeFor(Outcome): Int`, total y exhaustivo sobre
`EventPageDrain.Outcome`, y `main` lo usa (`return exitCodeFor(outcome)`). Un caso nuevo en `Outcome`
obliga a elegir un estado.

El contrato vive en `docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md` §12:

```text
0  la observación se completó. Refusals, run desconocido, filtro sin coincidencias y Stalled son 0:
   el rechazo o el atasco viajan como token estructurado por stderr, no son fallo del comando.
1  (console) el read se hizo y el plano lo rechazó (p.ej. unknown stream).
2  el comando NO se ejecutó: usage/argumento (falta requerido, opción desconocida, posicional de
   más, valor de opción no convertible o fuera de rango).
otro  excepción no capturada escapó de main: defecto, no salida diseñada.
```

**No se cambió ningún exit code existente.** El caso `Stalled -> 0` se declara, no se modifica. Si
se quisiera un código no-cero para `Stalled`, es propuesta (no hecha).

---

## 3. Evidencia de ejecución (real, sobre el árbol de este recibo)

Todos los comandos con `timeout 900`, log en `$JCODE_SCRATCH_DIR`. Canario XML: se borra el
`TEST-<Clase>.xml` antes y se comprueba que reaparece. Ninguna ejecución tocó `/tmp` como destino ni
la red.

```text
cd v2 && timeout 900 ./gradlew :pipeline-output-store:test --tests 'OutputPruneLockKeyingTest'
        RED  → exit 1, ^e: = 0, "expected: <1> but was: <2>"  (antes del arreglo)
        GREEN→ exit 0, ^e: = 0
cd v2 && timeout 900 ./gradlew :pipeline-application:test --tests 'B1aOutputPlaneProviderLifecycleCharacterizationTest'
        GREEN→ exit 0, ^e: = 0,  perStreamLocks afterSoak=40 afterRelease=40
cd v2 && timeout 900 ./gradlew :pipeline-output-store:test :pipeline-application:test --tests 'MainEventsCli…' \
        … :pipeline-output-store:detekt :pipeline-application:detekt
        GREEN→ exit 0, ^e: = 0, BUILD SUCCESSFUL in 41s
```

XML (todos `failures="0" errors="0" skipped="0"`):

```text
pipeline-output-store
  OutputPlaneConformanceTest                            tests=9
  OutputPruneLockKeyingTest   (NUEVO)                   tests=1
  OutputPruneTest                                       tests=6
  SegmentOutputStoreTest                                tests=30
pipeline-application
  durable.B1aOutputPlaneProviderLifecycleCharacterizationTest  tests=3   (fila b invertida)
  durable.OutputPlaneSurvivalFitnessTest                tests=3
  durable.OutputSingleAuthorityFitnessTest              tests=4
  durable.RetentionAuthorityFitnessTest                 tests=9
  durable.RunOutputRetentionTest                        tests=10
  durable.ScriptedStageExecutionTest                    tests=7
  MainEventsCliArgumentContractTest   (NUEVO)           tests=3
  MainConsoleCliArgumentContractTest  (NUEVO)           tests=2
  MainEventsCliRefusalVisibilityTest    (pre-existente) tests=5
  ConsoleReadServiceTest                (pre-existente) tests=11
```

Nota de ejecución: `:pipeline-output-store:test` (módulo entero, 46 tests) y los consumidores
directos del Output Plane en `:pipeline-application` se corrieron porque el arreglo vive en
`pipeline-output-store` y se consume desde `pipeline-application`. `detekt` (la tarea del gate,
`check` → `dependsOn(detekt)`) verde en ambos módulos; **no** se corrió `detektTest` (no está en la
ruta de `check` y ya era rojo antes por causas ajenas, B1a §10).

---

## 4. Mutaciones (una afirmación nueva → un mutante que la mate)

Cada mutación se aplicó, se observó el RED **sin `^e:`** (descartando error de compilación), y se
restauró con hash verificado.

| # | mutación | filas que mata | RED observado | restauración |
|---|---|---|---|---|
| M1 | `MainEventsCli` vuelve a `else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]` | opción desconocida (línea 71), posicional de más (línea 82) | `exit 1`, `^e: = 0`, dos `AssertionFailedError` | `sha256` coincide |
| M2 | `MainConsoleCli --max-bytes` vuelve a `?: ConsoleReadService.DEFAULT_PAGE_BYTES` | `--max-bytes` no convertible (línea 71) | `exit 1`, `^e: = 0`, `AssertionFailedError` | `sha256` coincide |
| M3 | `exitCodeFor` `Stalled -> 1` | contrato `Stalled == 0` (línea 116) | `exit 1`, `^e: = 0`, `AssertionFailedError` | `sha256` coincide |
| M4 | `SegmentOutputStore.streamKey` vuelve a `stream.value` (escritor crudo, prune plegado) | `OutputPruneLockKeyingTest` (afterPrune=2) y B1a fila b (40≠80) | `exit 1`, `^e: = 0`, `expected: <1> but was: <2>` | `sha256` coincide (`d6785d73…`) |

Hashes de restauración:

```text
SegmentOutputStore.kt   pre = d6785d7335f3dfa17811e56fc654d0ab328063bd79e326182a3cad88ab00aa8c   post = igual (sha256sum -c OK)
MainEventsCli.kt        pre = 90188b3aa7a1ccb1525607150f1ead2d0ec4841a2d3f41f698ef8d5e5ee5f4b6   post = igual (sha256sum -c OK)
MainConsoleCli.kt       pre = 8b699d2389cac28987d16f9cdea589f313d835269e8a4e21683b70e2a0ff6608   post = igual (sha256sum -c OK)
```

**Aviso de fidelidad de la mutación (HFL §6):** la reversión literal de `prune` a
`perStream.getOrPut(OutputStreamId(dir.fileName.toString()))` **no compila** ahora que el mapa está
keyado por `String`; se usó la mutación equivalente que sí compila (escritor a id crudo, prune
plegado), que reproduce exactamente el defecto original y pone el contador en 2.

---

## 5. NO_MEDIDO (con motivo, nunca con número inventado)

```text
1  Un `Stalled` real de punta a punta                       → EventPageDrain lo declara inalcanzable con ambos stores
                                                              hoy («the guard that keeps the loop from spinning»),
                                                              así que no se puede producir por el CLI. El contrato se
                                                              fija sobre `exitCodeFor`, que es la autoridad del mapeo;
                                                              se prueba tanto como el tipo lo permite.
2  RETENCIÓN: liberación automática en el CLI                → NO es defecto y NO se toca (ExplicitReleaseOnly es
                                                              decisión deliberada; el console lee runs ya terminadas).
3  `forget` / `forgetAll` sin llamante de producción         → NO se cablean aquí. Documentado: `src/main` sigue sin
                                                              llamarlos; siguen siendo seam de test / restart.
4  Opciones desconocidas y posicionales de más en `console`  → fuera del encargo (sólo se pidió `--max-bytes`).
                                                              Queda como defecto paralelo no arreglado, reportado.
5  `--limit`/`--db` con valor ausente al final (trailing)     → el valor que falta cae a `getOrNull → null`: `--db`
                                                              ausente es usage error (2), pero `--limit` ausente cae
                                                              al default en silencio. No pedido; reportado como gap.
6  `:pipeline-application:detektTest`                         → no está en la ruta de `check` y ya era rojo (B1a §10).
```

---

## 6. Lo que esto NO prueba

```text
- NO cambia ni certifica la política de retención. El arreglo es de keying de locks; los bytes y la
  decisión de cuándo liberarlos quedan exactamente como estaban.
- El contrato del exit code se prueba como mapeo total (`exitCodeFor`) más las rutas alcanzables por
  el CLI real. `Stalled` se fija por tipo, no por un stall real (inalcanzable hoy).
- No se corrió la suite completa (`check`): verificación acotada por la matriz de impacto de los
  ficheros tocados y sus consumidores directos del Output Plane.
```
