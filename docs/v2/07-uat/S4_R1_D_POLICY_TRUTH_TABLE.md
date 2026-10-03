# S4-R1-D — Tabla normativa de política de replay (ADR-0103 D1)

**Estado:** `CERTIFIED` (STEP-CERT, no PRODUCT-GATE)
**Work item:** S4-R1-D
**Ciclo:** S4 `train-s4-scripted-spine`
**Base:** `4991619bb25528ab4e71bc9b1fd004b6fe0c0e88` + 1 fichero modificado sin stagear
**Árbol:** `v2/pipeline-step-sdk/runtime/.../durable/EffectReplayPolicy.kt` (83 inserciones, 69 eliminaciones)
**SHA del fichero:** `b34d705e5438a6027c3a6c9f131cd267c0cec389a76347e4e314813461eb35fe`

---

## 1. Qué se cambió y por qué

`DefaultEffectReplayPolicy.decide` era una cascada de `if` cuyo **orden** tenía semántica
accidental, y esa semántica no coincidía con la tabla que su propia interfaz publicaba. El
cambio convierte la cascada en una tabla con **precedencia nombrada** y hace que la tabla
publicada sea la normativa.

El orden separa **admisión** de **replay**, y esa separación es el punto:

```text
1  sin fila de journal (primera ejecución)          EXECUTE
2  journalado + ABORTS_PIPELINE en effects          ABORT
3  journalado + NEVER                               ABORT
4  journalado + RERUN + SUCCEEDED                   SKIP
5  journalado + RERUN + no SUCCEEDED                EXECUTE
6  journalado + MEMOIZED + sólo READ_ONLY + SUCCEEDED   SKIP
7  journalado + MEMOIZED + sólo READ_ONLY + no SUCCEEDED EXECUTE
8  journalado + MEMOIZED + EXECUTES_SUBPROCESS
   o WRITES_WORKSPACE (incluidos conjuntos mixtos)   EXECUTE
```

`ABORTS_PIPELINE` **no** se honra sobre la primera ejecución, a propósito: el efecto significa
*"este Step, al ejecutarse, aborta la pipeline"*, así que impedir la ejecución suprimiría el
aborto en silencio. Un Step que nunca corre nunca aborta, y `CoreErrorStep` es exactamente ese
caso. Un conjunto de effects **mixto** nunca es memoizable; uno **vacío** tampoco — un ejecutor
que no declaró efecto no ha dicho nada sobre pureza.

### El defecto tenía dos direcciones

Este es el hallazgo que motivó la primera regla, y ninguna de las dos direcciones era visible
en el borrador del ADR ni en el spike:

| Dirección | Comportamiento anterior | Por qué no se veía |
|---|---|---|
| `journalado + RERUN + ABORTS_PIPELINE` | `SKIP` (servido de caché) | la rama `RERUN` retornaba antes de consultar el effect set |
| `fresh + MEMOIZED + ABORTS_PIPELINE` | `ABORT` (nunca se ejecuta) | ningún Step del core alcanza esta combinación; `CoreErrorStep` es `NEVER` y no está expuesta al facade scripted |

Mover `ABORTS_PIPELINE` al principio —el "fix" obvio— habría arreglado la primera y
perpetuado la segunda. Por eso la regla 1 va primero y se llama *admisión*.

### Sobre el nombre `RERUN`

`ReplayPolicy.RERUN` significa **reutilizar una fila `SUCCEEDED`**, lo contrario de lo que su
nombre sugiere; el código lo reconocía como *"naming debt"*. ADR-0103 **D2a** fija el contrato
documentado ahora; **D2b** difiere el rename a un epoch de compatibilidad durable explícito,
porque el nombre del enum está dentro del hash del fingerprint y renombrarlo migraría toda
operación que lo declare en ambos historiales.

---

## 2. Falsificar antes de arreglar

El commit previo `4991619b` añadió `EffectReplayPolicyTableFitnessTest` **deliberadamente en
rojo**: 22 tests, 4 RED. Los 4 RED eran exactamente los witnesses exigidos, no un efecto
colateral. Ese commit se certificó como falsificación (patrón I1); este lo corrige.

Los cuatro que pasaron de RED a verde en este gate:

```text
D1-1 fresh NEVER ABORTS_PIPELINE executes        ← la dirección invisible
D1-2 journalled RERUN ABORTS_PIPELINE aborts     ← la dirección documentada
D1-2 journalled MEMOIZED ABORTS_PIPELINE aborts
D1-2 journalled NEVER ABORTS_PIPELINE aborts
```

---

## 3. Evidencia del gate — tres piezas, conservadas

El gate de slice se ejecutó **dos veces**. La primera fue **roja**. Se conserva porque el
veredicto verde posterior no la borra: la clasificación del fallo ajeno es lo que sostiene
el `CERTIFIED`.

### 3.1 Gate 1 — RED (preservado, no es PASS)

```text
argv    cd v2 && ./gradlew :pipeline-step-sdk:runtime:test :pipeline-domain:test \
                  :pipeline-application:test :pipeline-architecture-tests:test
log     s4r1d-slice.log
sha256  11b52c281d5aeb5fd87bf6e2742b8ffbfb68f4ffc733eb0997e77b02403b9845
result  BUILD FAILED in 28m 6s
        89 actionable tasks: 15 executed, 2 from cache, 72 up-to-date

:pipeline-step-sdk:runtime    201 tests   0 failures   0 errors    0 skipped
:pipeline-domain              687 tests   0 failures   —           up-to-date (sin source modificado)
:pipeline-architecture-tests  427 tests   0 failures   0 errors   10 skipped
:pipeline-application        2225 tests   1 FAILURE    0 errors  121 skipped
```

Único fallo:

```text
WalkParallelFrameConcurrencyTest.3 branches x 100ms each complete in 150ms total()
  → Expected concurrent execution ≤150ms but took 160ms
```

### 3.2 Re-run aislada del test que falló — GREEN

```text
argv    cd v2 && ./gradlew :pipeline-application:test \
                  --tests '…walk.WalkParallelFrameConcurrencyTest' --rerun-tasks
log     s4r1d-walkparallel-isolated.log
sha256  1f716e381570e0510d0f348f39d283abd4b332de446ad7865d0fb60e5794aca1
result  BUILD SUCCESSFUL in 2m 43s — 66 actionable tasks: 66 executed
        WalkParallelFrameConcurrencyTest  PASSED
```

### 3.3 Gate 2 — GREEN (el que certifica)

```text
argv    idéntico al gate 1, con --rerun-tasks
log     s4r1d-slice-rerun.log
sha256  1659888f93988f1a408fca36c2c3b5f3d02377aa0f325c8e145bc6d16f774eb8
EXIT=0
result  BUILD SUCCESSFUL in 30m 19s
        89 actionable tasks: 89 executed   (0 up-to-date, 0 from cache)

:pipeline-step-sdk:runtime    201 tests   0 failures   0 errors    0 skipped   ts 20:13:47Z
:pipeline-domain              687 tests   0 failures   0 errors    0 skipped   ts 20:12:49Z
:pipeline-architecture-tests  427 tests   0 failures   0 errors   10 skipped   ts 20:17:24Z
:pipeline-application        2225 tests   0 failures   0 errors  121 skipped   ts 20:42:19Z
────────────────────────────────────────────────────────────────────────────────
TOTAL                       3540 tests   0 failures   0 errors  131 skipped
```

Las cuatro clases que fijan R1-D, todas con timestamp **dentro** de la ventana de este gate:

```text
EffectReplayPolicyTableFitnessTest   22/22 PASSED   ts 2026-10-03T20:14:41Z
ScriptedNamespaceFitnessTest          4/4 PASSED   ts 2026-10-03T20:17:24Z
EffectReplayPolicyContractTest        9/9 PASSED   ts 2026-10-03T20:13:45Z
WalkParallelFrameConcurrencyTest      1/1 PASSED   ts 2026-10-03T20:42:19Z
```

### 3.4 Por qué el fallo del gate 1 no invalida el de gate 2

No se clasifica por inspección. Cuatro hechos:

1. **Acoplamiento cero.** `WalkParallelFrameConcurrencyTest` importa `ParallelFrame`,
   `BranchSpec`, `JoinPolicy`, coroutines y JUnit. No importa `EffectReplayPolicy`, ni el
   journal, ni el executor. Su camino de código no toca lo modificado.
2. **Reproduce verde aislado** con el slice presente (`--rerun-tasks`, 66/66 tareas).
3. **Segundo gate completo verde** con 89/89 tareas realmente ejecutadas.
4. **Deuda preexistente ya registrada.** `S2C_DIRECTIVE_COMPOSITION_RECEIPT.md` §5 documenta
   **el mismo test fallando a 156 ms**, lo clasifica como *"flakiness de timing bajo carga,
   preexistente y ajeno a este slice"* y se niega explícitamente a tocar el umbral. Es la
   segunda ocurrencia en el historial.

### HAR-PAR-001 — deuda registrada, no reparada en este commit

`WalkParallelFrameConcurrencyTest` es **vacuo respecto a producción**: el propio test crea sus
`async(Dispatchers.IO)` y hace `Thread.sleep(100)`; **no invoca `ParallelStageEngine`**. La
autoridad productiva es `ParallelStageEngine.launchBranches()` (línea 187) con
`supervisorScope` (195) y `async(Dispatchers.Default)` (198). Verificado.

El test afirma algo sobre `kotlinx.coroutines` y lo deja pasar como si afirmara algo sobre el
motor. El arreglo correcto **no es subir el umbral a 180/200 ms**: es reemplazarlo por una
**barrera determinista que cruce la autoridad productiva** —tres handlers de fixture donde
`release` sólo se completa cuando los tres han entrado, y el timeout queda como watchdog de
deadlock en segundos, no como aserción de rendimiento.

**No se modifica en este commit.** Tocar ese umbral sin análisis propio sería evidencia débil,
y el arreglo pertenece a un work item del harness con su propio gate.

---

## 4. Mutaciones

Dos mutaciones con atribución RED 1:1, restauradas y verificadas por hash.

| ID | Mutación | RED esperados | Razón del RED |
|---|---|---|---|
| `M-R5` | contención eclipsada por política (regla 2 por debajo de una rama que la precede) | 3 | las filas `D1-2` con `ABORTS_PIPELINE` journalado vuelven a la decisión de la política |
| `M-R5b` | regla de admisión `fresh` suprimida por `ABORTS_PIPELINE` | 4 | `D1-1 fresh NEVER ABORTS_PIPELINE executes` y las demás filas `fresh` con contención |

Restauración verificada:

```text
sha256sum -c /tmp/s4r1d-restore.sha
  v2/…/durable/EffectReplayPolicy.kt: La suma coincide
  b34d705e5438a6027c3a6c9f131cd267c0cec389a76347e4e314813461eb35fe
```

---

## 5. Lo que este STEP-CERT NO dice

- **No pone verde el PRODUCT-GATE.** S4 sigue `BLOCKED_EXTERNAL` por ausencia de CI remota
  (política de verificación del 2026-10-03).
- **No toca la precedencia de scripted.** `ScriptedRegistryInvoker` sigue hasheando con el
  literal `ReplayPolicy.MEMOIZED` (fila 9 de `SPIKE-017B`), y eso es un artefacto durable
  distinto del canónico para la misma operación. Se corrige en R1-E con su corte de
  compatibilidad.
- **No resuelve la asimetría `Unstable`.** El avance del cursor hoy difiere entre ejecución
  (`outcome !is Failure`, incluye `Unstable`) y recovery (`outcome is Success`, lo excluye).
  Es un hecho a **caracterizar** cuando D7 mueva esa responsabilidad, no a corregir de paso.

---

## 6. Nota para R1-B: una autoridad que NO se debe "unificar"

```text
CommonExecutionResult  aplica a la ejecución atómica de un Step.
Las filas terminales del aggregate parallel  son protocolo
de control compuesto, no salida de Step.
```

`ParallelStageEngine.decodeBranchTerminal` lee la fila de control del parallel aggregate
(`parallelControlOpId`, escrita por `aggregateTerminalRow` con su propio `{"outcome","message"}`).
**No desciende de `CommonExecutionResult`**, y R1-B no la elimina. Unificarla "aprovechando B"
sería cambiar la autoridad equivocada.
