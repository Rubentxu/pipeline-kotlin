# HAR-PAR-001 — Un test de concurrencia que no tocaba la autoridad productiva

**Estado:** `RESOLVED`
**Deuda de origen:** `bl-bl-01M41RB9V70003883GP92A2N00` (SDDK backlog, P2)
**Detección:** 2026-10-03, durante el gate de slice de S4-R1-D.
**Alcance:** 1 test sustituido. Sin cambios de producción.

---

## 1. El defecto, en dos capas

### Capa 1 — el test no ejercía la autoridad que decía certificar

`WalkParallelFrameConcurrencyTest` construía un `ParallelFrame` —una **data class**— y
luego ejecutaba su propia concurrencia a mano:

```kotlin
coroutineScope { (0..2).map { async(Dispatchers.IO) { Thread.sleep(100) } }.awaitAll() }
```

No llamaba a `walkParallelFrame`, ni a `ParallelStageEngine.launchBranches()`, ni a
`StepDispatchEngine`, ni a ningún handler de Step. Afirmaba algo sobre
`kotlinx.coroutines` y su mensaje de fallo —*"branches executed sequentially instead of
concurrently"*— describía un defecto de PipelineK que el test no podía detectar.

La autoridad productiva real, verificada:

```text
ParallelStageEngine.launchBranches()      línea 187
  supervisorScope {                        línea 195
    async(Dispatchers.Default) { … }      línea 198
      → executeBranchSteps → StepDispatchEngine → registry → StepHandler
```

### Capa 2 — el presupuesto de reloj lo hacía inservible

150 ms de presupuesto para 100 ms de trabajo ideal: **margen del 50 %**. Un `sleep` bajo
una JVM cargada es exactamente lo que revienta un margen del 50 %.

## 2. Las cuatro ocurrencias registradas

```text
156 ms   S2C_DIRECTIVE_COMPOSITION_RECEIPT.md §5  — deuda observada, umbral deliberadamente intacto
160 ms   gate de slice de S4-R1-D
177 ms   gate de bloque S4-R1-BAC, bajo carga de gate completo
151 ms   en aislamiento, 30 minutos después de un PASS de 100 ms en el MISMO aislamiento
```

La cuarta es la que lo zanja: **verde y rojo en la misma configuración**, con un presupuesto
que sólo falla por 1 ms.

## 3. El reemplazo

`ParallelBranchConcurrencyDeterminismTest` — una **barrera determinista que cruza la
autoridad productiva**:

```text
ParallelStageEngine.launchBranches() → supervisorScope → async(Dispatchers.Default)
    → StepDispatchEngine → registry → BarrierStep.handler
```

Cada handler anuncia su llegada y espera a las otras dos:

```kotlin
if (entered.incrementAndGet() >= branchCount) release.complete(Unit)
release.await()
```

- **CONCURRENTE (producción hoy)** → los tres entran, la barrera abre, la run termina.
- **SECUENCIAL (la regresión que debe cazarse)** → la rama 0 entra y espera a las ramas 1 y 2,
  que nunca arrancan, y el **watchdog de deadlock** dispara.

No hay ninguna aserción de rendimiento en el fichero. El timeout es un watchdog con un
presupuesto deliberadamente absurdo (30 s), porque un watchdog que sólo dispara cuando algo
está **realmente** atascado es el único uso honesto de un timeout en un test de concurrencia.

## 4. Evidencia

### Verde sobre producción restaurada

```text
argv  cd v2 && ./gradlew :pipeline-application:test \
             --tests '*ParallelBranchConcurrencyDeterminismTest' \
             --tests '*ParallelReconcileCoordinatorTest' --rerun-tasks
EXIT=0
ParallelBranchConcurrencyDeterminismTest   1 test   0 failures   1.989s
ParallelReconcileCoordinatorTest           4 tests  0 failures   2.033s
```

### Rojo por mutación — el reemplazo tiene dientes

`HAR-PAR-001-M1`: serializar las ramas en `ParallelStageEngine.launchBranches()`, esperando
que cada ramaYa terminada antes de crear la siguiente.

```text
RED  TimeoutCancellationException: Timed out waiting for 30000 ms
     time=31.24s
```

El tiempo es el presupuesto del watchdog, no una aserción de reloj. Restauración verificada:

```text
sha256sum -c  →  ParallelStageEngine.kt: La suma coincide
git diff      →  vacío (idéntico a HEAD)
```

### Por qué la primera versión de la mutación no valía

Una primera versión de la mutación (`async` sin scope receiver) **no compilaba**:
`async` fuera de un scope es un error de deprecación. Gradle ejecutó entonces la clase
**vieja ya compilada** y el XML mostró un PASS. La lección es la de siempre: si
`compileTestKotlin` falla, el resultado del test no dice nada. Se rehízo la mutación de
forma que compila, y sólo entonces el RED es evidencia.

## 5. Lo que este reemplazo generaliza

`WalkParallelFrameConcurrencyTest` y `SPIKE-016` son la **misma clase de falso test con
dos formas distintas**: ambos reproducen el algoritmo dentro del propio test en lugar de
cruzar la autoridad productiva. De ahí la ley en AGENTS.md:

```text
Un test de arquitectura o runtime NO certifica una propiedad productiva
si reproduce el algoritmo dentro del propio test. Debe cruzar la autoridad
productiva, o declararse explícitamente como model/spike test.
```

## 6. Lo que este reemplazo NO dice

No dice que el aggregate parallel sea correcto más allá de la concurrencia de dispatch.
`decodeBranchTerminal` sigue leyendo la fila de control del aggregate, que es protocolo
compuesto y no salida de Step, y sigue deliberadamente separada de `CommonExecutionResult`.
