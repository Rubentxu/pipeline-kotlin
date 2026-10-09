# B1d — separación de relojes en `BodyExecutionEngine` (AUD-06)

**Base:** `5aadf3f1` (rama `s6-plugin-sdk`) · **Ámbito:** `BodyExecutionEngine.kt` (AUD-06)
**SHA de producción medido:** `435b72a6…` (original) → `c155d18…` (B1d)
**Referencias leídas antes de tocar nada:** `S7_AUDIT_REVIEW_FINDINGS_TRIAGE.md` §7, `B1B_FIXES_RECEIPT.md`, AGENTS.md.

---

## 0. Qué dice AUD-06, y qué contesta este recibo

AUD-06 midió que `BodyExecutionEngine` lee el instante de dos fuentes: el puerto durable
`Clock` (2 sitios) y `java.time.Instant.now()` (una decena). El propio triage §7 lo
resuelve: *"La separación que la auditoría pide —reloj de negocio reproducible frente a
reloj de medición— es la correcta; el trabajo es ejecutarla, no decidirla."*

Este recibo **ejecuta la parte ejecutable** de esa separación, mide cada sitio y aplica
el mínimo diff. No decide de nuevo la política.

---

## 1. Caracterización de cada sitio (paso 1 del método)

### 1.1 Los diez sitios `occurredAt = Instant.now()`

| # | ruta:línea (original) | evento | campo | clase del timestamp |
|---|---|---|---|---|
| S1 | `BodyExecutionEngine.kt:187` | `DirEntered` | `occurredAt` | observación de transición |
| S2 | `BodyExecutionEngine.kt:200` | `TimestampsEntered` | `occurredAt` | observación de transición |
| S3 | `BodyExecutionEngine.kt:264` | `WaitUntilPolled` (pre-poll) | `occurredAt` (+ `durationMs`) | observación + medición |
| S4 | `BodyExecutionEngine.kt:291` | `WaitUntilPolled` (post-poll) | `occurredAt` (+ `durationMs`) | observación + medición |
| S5 | `BodyExecutionEngine.kt:307` | `WaitUntilCompleted` (completed) | `occurredAt` (+ `totalDurationMs`) | observación + medición |
| S6 | `BodyExecutionEngine.kt:325` | `WaitUntilCompleted` (deadline) | `occurredAt` (+ `totalDurationMs`) | observación + medición |
| S7 | `BodyExecutionEngine.kt:506` | `RetryAttemptStarted` | `occurredAt` | observación de intento |
| S8 | `BodyExecutionEngine.kt:559` | `DirExited` | `occurredAt` | observación de transición |
| S9 | `BodyExecutionEngine.kt:571` | `TimestampsExited` | `occurredAt` | observación de transición |
| S10 | `BodyExecutionEngine.kt:595` | `TimeoutTriggered` | `occurredAt` (+ `durationMs = budgetMs`) | observación de brecha |

Los otros dos sitios del fichero ya usaban el puerto: `CredentialUsed.occurredAt`
(`:111`) y `TimeoutScheduled.occurredAt` (`:166`).

### 1.2 Los cinco sitios de medición (`System.currentTimeMillis()`)

`:243` (`overallStartMs`), `:256` (`pollStartMs`), `:283` (`pollDurationMs`),
`:301` y `:319` (`totalDurationMs`). Alimentan **duraciones**, no instantes.

### 1.3 ¿`occurredAt` es semántica durable? — evidencia, no opinión

`occurredAt` es un **campo de identidad no determinista**, excluido de toda comparación
durable. Evidencia en el propio repositorio:

1. **Excluido de la paridad semántica**: `S2_A4_CORE_EMITEVENT_G2_DIFFERENTIAL_CONTRACT_FREEZE.md:57`
   — *"Semantic parity only: eventId/occurredAt/sequence excluded."*
2. **Excluido de la paridad legacy/registry**: `LB02_G3_A4_8_LEGACY_REGISTRY_PARITY.md:38`
   — las diferencias *"MUST be limited to non-deterministic identity fields (`eventId`,
   `sequence`, `occurredAt`)."*
3. **Excluido del corpus de compatibilidad**: `CorpusNormalizer.kt:9` lo separa como
   `eventId, runId, occurredAt (non-deterministic)` antes de hashear.
4. **Ausente del fingerprint**: `Fingerprint.FingerprintPayload` (`stepId, params, runId,
   attempt, replayPolicy`) no contiene ningún timestamp.
5. **Marcado "event timestamp only"**: `S2_A6_...:109` — `occurredAt = Instant.now(), // event timestamp only`.
6. **Ningún consumidor decide con él**: `grep '\.occurredAt'` en `src/main` sólo devuelve
   `EnvelopeProjector`, `EventJsonWriter`, `SqliteEventStore` (proyección/serialización).
   Ningún journal, fingerprint ni reconciliación lo lee.

**Conclusión de la caracterización:** ninguno de los diez sitios es *de negocio* en el
sentido del límite duro (journal / fingerprint / comparación de replay). Son instantes de
**observación** de eventos durables. Bajo la producción `SystemClock`,
`java.time.Clock.systemUTC().instant()` y `Instant.now()` devuelven el **mismo valor**, así
que mover la fuente es un no-op de producción que elimina una lectura ambiental de reloj.

---

## 2. Decisión por sitio (paso 2 del método)

| # | evento | decisión | motivo |
|---|---|---|---|
| S1 | `DirEntered` | **(a)** usa `clock` | Único productor en todo `src/main`; su hermano `TimeoutScheduled` ya usa el reloj durable. El quirk citado por el KDoc es exactamente este sitio. |
| S2 | `TimestampsEntered` | **(a)** usa `clock` | Único productor; cierra el par con S9. |
| S8 | `DirExited` | **(a)** usa `clock` | Único productor; mantiene el par DirEntered/DirExited en una sola fuente reproducible. |
| S9 | `TimestampsExited` | **(a)** usa `clock` | Único productor; cierra el par con S2. |
| S10 | `TimeoutTriggered` | **(a)** usa `clock` | Único productor; empareja con la admisión `TimeoutScheduled` (ya `clock`), de modo que un observador puede restar ambos instantes de forma reproducible. |
| S3–S6 | `WaitUntilPolled` / `WaitUntilCompleted` | **(c)** ambiguo, no se toca | El **mismo tipo de evento** lo emiten `WaitUntilEngine:168/190/392` y `CoreWaitUntilStep:185/198` con `Instant.now()`. Cambiar sólo este motor pondría las dos rutas de ejecución del mismo Step en fuentes de tiempo distintas. Unificar la familia exige un gate propio, más ancho que este fichero. |
| S7 | `RetryAttemptStarted` | **(c)** ambiguo, no se toca | `RetryEngine:249` emite el **mismo tipo** con `Instant.now()`. Mismo razonamiento que S3–S6. |
| — | `System.currentTimeMillis()` ×5 | **(b)** sigue reloj de pared | Son **medición** (duraciones `durationMs`/`totalDurationMs`), no identidad. El puerto `Clock` devuelve `Instant`, no una duración; el puerto es el reloj durable, no el de medida. |

**Decisión aplicada:** grupo (a) = S1, S2, S8, S9, S10. Grupo (c) = S3–S7, reportados y
no tocados. Grupo (b) = las cinco duraciones, no tocadas.

### 2.1 Por qué S1/S2/S8/S9/S10 no violan el LÍMITE DURO

El límite prohíbe tocar un timestamp que forme parte de la **semántica durable**
(journal, fingerprint, comparación de replay). `occurredAt` está excluido de los tres
(§1.3). El cambio no altera ningún valor durable: en producción el valor es idéntico
(`SystemClock == systemUTC().instant()`), y en test se vuelve **reproducible** bajo un
`Clock` inyectado, que es precisamente la propiedad que el auditor pide. No se toca
ningún evento con otro productor (S3–S7), evitando introducir una divergencia nueva
entre rutas del mismo Step.

---

## 3. Diff aplicado (paso 3: grupo (a), mínimo diff)

Un solo fichero: `BodyExecutionEngine.kt`, `435b72a6…` → `c155d18…`, **+29 −8**.

```text
:187  DirEntered.occurredAt          java.time.Instant.now() -> clock.now()
:200  TimestampsEntered.occurredAt   java.time.Instant.now() -> clock.now()
:559  DirExited.occurredAt           Instant.now()            -> clock.now()
:571  TimestampsExited.occurredAt    Instant.now()            -> clock.now()
:595  TimeoutTriggered.occurredAt    Instant.now()            -> clock.now()
:133  KDoc de projectScope           quirk -> contrato explícito (grupos a/b/c)
```

Restan a propósito cinco `Instant.now()` (`:264`, `:291`, `:307`, `:325`, `:506`) y las
cinco duraciones `System.currentTimeMillis()`.

---

## 4. Pruebas (un test por sitio/grupo, propiedad nunca duración)

Nuevo: `v2/pipeline-application/src/test/.../durable/B1dBodyExecutionEngineClockSeparationTest.kt`
(sha256 `7c6cbc94…`). Conduce `BodyExecutionEngine.projectScope` / `executeScope3b` (la
autoridad de producción; HF1) con un `Clock` fijo (`2024-01-01T00:00:00Z`) y un
`RecordingSink`.

```text
1 dir scope bookends carry the injected durable clock instant          -> DirEntered/DirExited == instante fijo   (S1,S8)
2 timestamps scope bookends carry the injected durable clock instant   -> Timestamps* == instante fijo           (S2,S9)
3 timeout admission and breach pair on the injected durable clock      -> TimeoutScheduled/Triggered == fijo     (S10)
4 a body re-executed with the same clock reproduces the same timeline  -> (kind, occurredAt) idéntico en 2 corridas
5 CHARACTERISATION - waitUntil/retry still read the wall clock         -> guarda del grupo (c) (NO es corrección)
```

RESULTADO (XML fresco, canario borrado antes de cada corrida):

```text
B1dBodyExecutionEngineClockSeparationTest   5 tests  0 fail  0 err  0 skip
BodyExecutionCharacterizationTest           5 tests  0 fail
B11ContextBlocksRuntimeTest                 7 tests  0 fail
WULpr302Phase1bTest (3 clases anidadas)    11 tests  0 fail
B1aBodyExecutionEngineCredentialPurposeTest 3 tests  0 fail
TOTAL                                      31 tests  0 fail  0 err  0 skip
```

`^e ` = 0 en cada corrida: ningún resultado proviene de un árbol que no compiló.

sha256 de los XML:

```text
B1d…ClockSeparationTest          126cf09d808c0645c1982554ad36576b9263313dcafab577c0c9c59ec5a8451e
BodyExecutionCharacterizationTest bdef67b266b00ace6d3e4922bb9f02f80d8de462f5eded85aefd427522055e03
B11ContextBlocksRuntimeTest       c0c386459b6fcfc5a3beeec995581b6d8a2fabe6f07d7c971aa84d8922d69038
WULpr302Phase1b$/AttemptDrives…   114bd75f7d28cf0f686ef230d4cdae1567ec057a89964e1dcb91eda40884278e
WULpr302Phase1b$/PatchProjection  92f6042aca9cc24224c14ee1b916936416190c32a95eaf5cc086171b3ba4f1f9
WULpr302Phase1b$/RetryEndToEnd…   7bbb65681d3e7085e90c0e1b09d2eed75890b7eadc478598675088518f8cd3e2
B1a…CredentialPurposeTest         4599b32f5f66c11fc3f0c86c73b872bb66fc25b749c51f37f679ebd49a8b5c8c
```

Comando (idéntico para GREEN y mutantes; log en `$JCODE_SCRATCH_DIR`):

```bash
cd v2 && timeout 900 ./gradlew :pipeline-application:test \
  --tests 'dev.rubentxu.pipeline.v2.application.durable.B1dBodyExecutionEngineClockSeparationTest' \
  --rerun-tasks
```

---

## 5. Mutantes (XML fresco, restauración verificada por hash)

Cada mutante revierte (o fuerza) una afirmación; se borra el `TEST-*.xml` antes de medir;
la restauración se verifica contra `sha256 = c155d18c…` (baseline B1d).

```text
M1  revierte DirEntered + DirExited a Instant.now()
    RED: 2 fallos -> "dir scope bookends…" + "a body re-executed…"
    log sha256 e31a3a35fb047f4d58924ab03ca2e9afcb656be8767603b165e44c8d6f3bb931
M2  revierte TimestampsEntered + TimestampsExited a Instant.now()
    RED: 1 fallo  -> "timestamps scope bookends…"
    log sha256 043762c12489a0ee199cbe8ad125a0d849c8fdf44037452cc0bc21799e4551c1
M3  revierte TimeoutTriggered a Instant.now()
    RED: 2 fallos -> "timeout admission and breach…" + "a body re-executed…"
    log sha256 ce826953a61754239e32e87ac1b071776cbdbf5c089de75c6404a4ffa3e05ff7
M4  unifica silenciosamente RetryAttemptStarted a clock.now() (grupo c)
    RED: 1 fallo  -> "CHARACTERISATION - waitUntil/retry still read the wall clock"
    log sha256 17e1d12ea31aaf1e0910aa0b96b8892ff3ffbf562f58b7a574cc071d47d5fd26

restauración: sha256 de BodyExecutionEngine.kt == c155d18c… tras cada mutante (4/4 verificados)
```

M4 demuestra que la fila 5 no es decorativa: cualquier unificación silenciosa del grupo
(c) rompe el XML y obliga a que la transición sea explícita (familiar-wide gate).

---

## 6. Lo que este recibo NO hace

```text
- No toca los grupos (b) ni (c): las cinco duraciones siguen en reloj de pared y los cinco
  eventos compartidos con RetryEngine/WaitUntilEngine siguen con Instant.now().
- No toca WaitUntilOutput.resultOutcome (AUD-03, dueño y ley propios).
- No toca la ruta no durable de ShExecution (B1b), ni la CLI (B1c), ni la retención del
  Output Plane.
- No cambia ningún contrato durable: occurredAt sigue excluido de fingerprint, journal y
  comparación de replay, y ningún XML de paridad/corpus cambia.
- No unifica el resto del codebase: RetryEngine, WaitUntilEngine, ParallelStageEngine,
  StepDispatchEngine y los emisores de eventos de Steps siguen mezclando ambos relojes.
  Eso es un WorkItem más ancho (familia completa de eventos), con su propio gate; M4 es la
  evidencia de por qué no se hace aquí a medias.
```

---

## 7. Cierre del WU — checklist de referencia

```text
Reference implementation consulted: none applicable (contrato derivado de typed requirement;
                                    la regla es interna: puerto Clock vs reloj de pared)
Behaviour adopted:                  los bookends de scope con productor único usan el Clock inyectado;
                                    las duraciones y los eventos compartidos mantienen el reloj de pared
Intentional deviations:             S3–S7 NO se unifican (divergencia con RetryEngine/WaitUntilEngine)
Security implications reviewed:     n/a (sólo fuente de un timestamp no determinista; sin credenciales)
Tests demonstrating the contract:   B1dBodyExecutionEngineClockSeparationTest (5 filas)
```
