# S4-R0 — Conflict memo: tres contratos acoplados sin autoridad entre ellos

> **Estado: CONGELADO.** I2b no se implementa hasta que este memo esté resuelto por
> ADR. Ningún cambio productivo de scripted replay está autorizado mientras exista.
> Este memo **no decide** nada: registra.

## 0. Por qué este memo existe

La pregunta con la que empezó I2b era *"¿de dónde sale el índice de iteración?"*. La
investigación mostró que esa pregunta no es respondible, porque tres contratos que
hoy nadie ha coupled entre sí se apoyan mutuamente:

```text
determinismo de replay
        +
identidad de operación
        +
semántica de ReplayPolicy
        ↓
compatibilidad durable
```

Responder "qué compone la iteración" sin haber fijado antes qué significa *replay*
produciría una identidad correcta bajo una semántica equivocada.

`ACTIVE_DOCUMENTS.md:35` ordena, para documentos normativos contradictorios:
*"NO inventar un ganador: registrar el conflicto y requerir ADR"*. Eso es
exactamente lo que este memo hace.

## 1. C1 — El requisito de iteración existe; su fuente, no

**Autoridad normativa** — `docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md` §5.1
exige que una operation key sea, textualmente:

```text
- distinct across dynamic loop iterations;
- stable after process restart;
- computable before executing the effect.
```

§5.2 da la forma propuesta con el ejemplo `loop:45[2]`.

**El hueco** — ningún ADR, spec ni spike dice quién produce ese `2`.
`ScriptedSourceLocation.loopScope(iteration: Int)`
(`v2/pipeline-scripting-api/…/ScriptedExecutionApi.kt:93`) modela un entero, y sigue
**sin ningún uso productivo**: sólo lo ejerce `ScriptedSourceLocationTest`.

**Estado medido tras I2a** (`77ee1d49`):

| requisito §5.1 | estado |
|---|---|
| distinct across loop iterations | **NO** — el path identifica el bucle, no la iteración |
| stable after process restart | **NO** — el contador vive en el mapa `ordinals` en memoria |
| computable before the effect | sí |
| fail-closed ante drift | sí — el fingerprint rechaza |

## 2. C2 — ADR-0093 afirma más de lo que SPIKE-016 demuestra

| Afirmación | Fuente | Status |
|---|---|---|
| *"SPIKE-016's hypothesis … **PASSED, including loops (S16-E5)** … This is **proven infrastructure, not a bet**"* | `ADR-0093-structured-dsl-runtime-return.md:172-175` | **accepted** |
| *"its harness is a **test-only `Scope`/`Runtime` reimplementation, not production**"* | `S4_IDENTITY_I1_ORDINAL_FALSIFICATION.md:26-27` | characterization |
| *"Remaining production work: compiler-derived call-site IDs, durable journal storage/codec… are **not properties established by this spike**"* | `SPIKE-016:266-267` | `passed` |

El conflicto es interno a la cadena ADR-0093 → SPIKE-016, y lo desmiente la fuente
que el propio ADR cita. **No se resuelve aquí.** Corrección propuesta: *addendum* a
ADR-0093, sin reescribir historia.

## 3. C3 — `RERUN` no significa lo que su nombre y su documentación dicen

`v2/pipeline-domain/…/durable/ReplayPolicy.kt:14-15` documenta:

```text
RERUN = Always re-executes regardless of cached output.
```

`DefaultEffectReplayPolicy` implementa
(`v2/pipeline-step-sdk/runtime/…/EffectReplayPolicy.kt`):

```kotlin
if (replayPolicy == ReplayPolicy.RERUN) {
    if (journaledOutcome == OperationStatus.SUCCEEDED) return ReplayDecision.SKIP
    return ReplayDecision.RERUN
}
```

Es decir: **`RERUN` + `SUCCEEDED` journaleado → `SKIP`.** Contradice el nombre, la
documentación del enum, y la tabla normativa que la propia interfaz publica
(`RERUN | any | any | any | RERUN`).

El código lo admite explícitamente: *"Naming debt: RERUN is the current decision
meaning 'execute handler now', even for a fresh execution; renaming the ADT is out
of scope."*

**Consecuencia**: hoy **no es cierto** que `core.sh` declare RERUN y deba re-ejecutar
siempre. Antes de propagar `descriptor.replayPolicy` hay que fijar qué significa
cada política en cada estado de journal, o se cambia el hash sin haber establecido
la semántica que lo justificaba.

## 4. C4 — Dos autoridades de replay, y `ScriptedRegistryInvoker` es la segunda

El canónico (`CanonicalDurableRunCoordinator` → `DurableInvocationResolver`)
delega en una autoridad única:

```text
DurableInvocationResolver
  → divergenceDetector            (fingerprint gate)
  → RunningSubprocessRecovery     (RecoveryPolicy.ExternalSubprocess)
  → EffectReplayPolicy.decide(...)  ← la tabla de decisión
  → RegistryExecutionPreparation
  → CommonExecutionBoundary
```

La política llega por `StepMetadata`, resuelta por clave de Step
(`RegistryStepMetadataResolver`), nunca por nombre concreto.

`ScriptedRegistryInvoker.invoke` **implementa su propia semántica durable**:

```text
journal.get(operationId)
  → fingerprint == Fingerprint.compute(..., ReplayPolicy.MEMOIZED, ATTEMPT)   ← literal
  → status == SUCCEEDED  → restoredOutput(...)          (reutiliza)
  → status == RUNNING    → Failed(REPLAY_COMPATIBILITY)
  → status == FAILED|ABORTED|FAILED_TIMEOUT|LOST → Failed(REPLAY_COMPATIBILITY)
  → status == PENDING|DIVERGENT → Failed(REPLAY_COMPATIBILITY)
```

No consulta `EffectReplayPolicy`, no consulta `StepMetadata`, y sustituye la
política declarada por un literal. Es la clase de defecto que la Semantic
Constitution §1 prohíbe: dos sistemas compartiendo la misma responsabilidad.

## 5. Divergencias derivadas del código (hipótesis para S4-R-POL)

Esta matriz es una **derivación estática de las dos implementaciones**, no una
medición. Su propósito es que el spike confirme o refute fila por fila, no que lo
sustituya.

Descriptores reales de los Steps de referencia:

| Step | effects | replayPolicy | recoveryPolicy |
|---|---|---|---|
| `core.sh` | `EXECUTES_SUBPROCESS` | `RERUN` | `ExternalSubprocess` |
| `core.pwd` | `READ_ONLY` | `MEMOIZED` | — |
| `core.fileExists` | `READ_ONLY` | `MEMOIZED` | — |
| `core.error` | `ABORTS_PIPELINE` | `NEVER` | — |

| # | Step | Estado del journal | Canónico | Scripted | Divergencia |
|---|---|---|---|---|---|
| 1 | `core.sh` | fresh | `RERUN` (ejecuta) | ejecuta | no |
| 2 | `core.sh` | `SUCCEEDED` | `SKIP` | `restoredOutput` | no |
| 3 | `core.sh` | `FAILED` | `RERUN` (re-ejecuta) | `Failed(REPLAY_COMPATIBILITY)` | **SÍ — activa** |
| 4 | `core.sh` | `RUNNING` | `RunningSubprocessRecovery` reengancha el proceso | `Failed(REPLAY_COMPATIBILITY)` | **SÍ — activa, grave** |
| 5 | `core.pwd` | fresh | `RERUN` | ejecuta | no |
| 6 | `core.pwd` | `SUCCEEDED` | `SKIP` | `restoredOutput` | no |
| 7 | `core.pwd` | `FAILED` | `RERUN` | `Failed(REPLAY_COMPATIBILITY)` | **SÍ — activa** |
| 8 | `core.error` | `SUCCEEDED` | `ABORT` (falla cerrado) | **`restoredOutput` (reutiliza)** | **SÍ — latente, crítica** |

**Lectura de las tres divergencias que importan:**

- **Fila 3/7 — un run que falla y se reanuda se comporta distinto según la
  superficie.** Canónico re-ejecuta; scripted aborta con `REPLAY_COMPATIBILITY`. El
  mismo Step, el mismo journal, resultado distinto según si fue invocado desde
  `pipeline {}` o desde `script {}`.
- **Fila 4 — la recuperación de subproceso externo no existe en scripted.**
  `core.sh` declara `RecoveryPolicy.ExternalSubprocess` y el canónico reengancha un
  proceso vivo; scripted devuelve fallo. Esto explica por qué existe
  `RegistryScriptedShellRuntime` y por qué A1b tuvo que enrutar `sh` por el
  registry: no era una endianzación estética, era falta de recuperación.
- **Fila 8 — latente, no activa.** Ningún Step alcanzable desde la fachada scripted
  declara `NEVER` hoy (`sh`=RERUN, `pwd`/`isUnix`/`readFile`/`fileExists`=MEMOIZED),
  así que un Step `NEVER` **se reutilizaría silenciosamente** en cuanto uno lo sea.
  Es un fallo de contención, no un fallo en producción: por eso se registra como
  latente y no como incidente.

## 6. Lo que este memo NO decide

- **No** decide la fuente del índice de iteración. La hipótesis más prometedora es
  el índice **posicional de entrada a la iteración**, producido en la frontera del
  bucle y reconstruido por re-ejecución determinista (estado local determinista, no
  persistido, no derivado del journal, no dependiente del valor). **No se adopta
  sin evidencia**: debe pasar `break`, `continue`, iteraciones sin efecto, bucles
  anidados, variable de bucle sin usar y restart real.
- **No** decide si `ScriptedRegistryInvoker` se elimina, se degrada a adaptador de
  identidad y entrada/salida tipada, o se conserva.
- **No** decide la política de compatibilidad para el historial existente
  (`REPLAY_COMPATIBILITY` explícito vs. conservar la versión anterior).
- **No** propaga `descriptor.replayPolicy` al fingerprint. Ese cambio altera el hash
  de toda fila `core.sh` persistida y sería "fingerprint correcto + decisión replay
  equivocada": peor que el estado actual, porque parece arreglado.

## 7. Secuencia autorizada a partir de aquí

```text
S4-R0  este memo (congelamiento + registro)          ← este documento
   ▼
S4-R-POL    Replay Semantics Truth
             · tabla canónica vs tabla scripted, fila por fila
             · distinción DurableRunPolicy / ReplayPolicy / RecoveryPolicy
             · confirmar o refutar las 8 filas de §5
   ▼
S4-R-KERNEL ¿Puede una ScriptedRegistryCall adaptarse a las
             autoridades canónicas sin duplicar replay y
             conservando el output tipado?
             STOP si exige llenar el kernel canónico de
             excepciones scripted
   ▼
S4-R-ID     Candidato D contra el lowering real
   ▼
ADR-S4-R1   semántica de replay + política de compatibilidad
ADR-S4-R2   fuente de la identidad de iteración
   ▼
leyes DR-1..DR-12 en AGENTS.md + fitness que las haga cumplir
   ▼
implementación
```

Cero código productivo hasta que los tres spikes hayan producido evidencia.
