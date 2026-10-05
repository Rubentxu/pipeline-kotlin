# P2 — Replay Convergence y Candidata v0.47.0-rc2

**Item:** `0553d7ef-adec-417c-9f8f-effc754425d0` (Runtime Observation Contract Closure) · Slice P2
**SHA certificado:** `5ea4137d878b8fe325f588e292a72136cae0156c` (rama `p1-orphan-core-sh`, árbol limpio)
**Gate:** `BUILD SUCCESSFUL in 29m 49s` · `329/329` tareas · `check --rerun-tasks`
**Log:** `/var/home/rubentxu/.local/state/pipelinek-gates/gate-p2-5ea4137d.log`
**Candidata:** `v0.47.0-rc2` · CandidateId `sha256:02b1632edcd351bce3ee49ab735b72df21941595036ef5edcb7dbb3d4e9dd635`
**Handoff:** `Rubentxu/pipelinek-release-harness#4`
**Fecha:** 2026-10-05

---

## 1. Qué establece P2

P1 dio a `Unstable` un único significado terminal. P2 cierra la propiedad transversal: una
operación que termina `StepOutcome.Unstable` conserva esa semántica en fresh, restart, recovery
y replay, y NO vuelve a ejecutar efectos completados sólo porque su status durable no sea
`SUCCEEDED`.

| Frente | Cambio |
|---|---|
| `EffectReplayPolicy` | `isReusableCompletion = {SUCCEEDED, UNSTABLE}` — la ÚNICA clasificación de reutilizable; reglas 4/5 la leen; precedencia ADR-0103 intacta; `ABORTED` sigue no-reutilizable por decisión, no por analogía |
| `RetryReconciliationDecision` | +`ReuseUnstable(attempt)`: el restart devuelve el mismo hecho inestable con cero nuevos intentos |
| `RetryReconciler` | Tres brazos deliberados (latest, control UNSTABLE, hijo UNSTABLE bajo control RUNNING) + exclusión del agotamiento: un intento inestable NO consume presupuesto |
| `DefaultDurableControlEnginePolicy` | +`EngineDirective.CloseUnstableTerminal(attempt)` — se rechaza el colapso booleano `CloseTerminal(success: Boolean)` para unstable |
| `RetryEngine.execute` | Brazo `ReuseUnstable → StepOutcome.Unstable` sin escritura (un reuse reprodunce la verdad durable y no escribe) |
| `ParallelReconciler` | W6 acepta aggregate UNSTABLE; `reconstructBranch` lee filas UNSTABLE como carrier lossless; W5 cierra el fold unstable desde hijos; la ambigüedad legacy de FAILED se conserva y se fija con test |
| `WaitUntilEngine` | Control negativo: un body Unstable es predicado-no-satisfecho; NINGUNA fila waitUntil será jamás UNSTABLE |
| Formato durable | Posición A decidida y documentada en `OperationStatus`: downgrade fuera de contrato; `valueOf` fail-closed es la frontera de epoch (igual que `FAILED_TIMEOUT` antes) |
| `TypedStepOutput` | KDoc corregido: dejaba de ser cierto que Unstable se journaliza `SUCCEEDED`; la regla de autoridad (derivar del carrier) se mantiene por diseño, no por imposibilidad |

Commits: `762164bf` (P2) + `5ea4137d` (fixup: detekt MaxLineLength 162>160 y `else` redundante
que el compilador pidió eliminar cuando el brazo UNSTABLE hizo exhaustivo un `when`).

## 2. Evidencia sobre `5ea4137d`

### 2.1 Suite quirúrgica durante desarrollo

173 tests / 0F / 0E / 0 XML rancios en 32 ficheros de resultados. Filas de dinero:

- `UNSTABLE` en `maxAttempts=1` → `ReuseUnstable(1)`, **no** `ReuseFailure(1)`.
- Hijo UNSTABLE sobre control RUNNING stale → `ReuseUnstable` (ventana de crash real).
- W6 paralelo: aggregate UNSTABLE → `ReuseUnstable`; W5: hijos UNSTABLE → `CloseFromChildren(Unstable)`.
- FAILED legacy → `RejectAmbiguousOutcome` (la ambigüedad pre-P1 se conserva, fijada con test).
- S4D2: `fresh.value == reused.value`, outcome `Unstable` en reuse, `handlerInvocations == 1`,
  bajo MEMOIZED+READ_ONLY **y** bajo RERUN+EXECUTES_SUBPROCESS.
- Control negativo waitUntil: 0 filas UNSTABLE; el poll inestable se reintenta por política.

### 2.2 Gate completo

734 XML / **4906 tests / 0F / 0E / 140S** / 19 módulos / 329-329 tareas / 29m49s / GATE_EXIT=0,
árbol limpio en `5ea4137d`. Delta vs gate de P1 (4886): +20 tests, exactamente las filas P2.

### 2.3 ABI

`pipeline-domain.api`: +2 casos ADT (`EngineDirective$CloseUnstableTerminal`,
`RetryReconciliationDecision$ReuseUnstable`), 0 cambios de firma.

## 3. Hallazgos

1. **`EngineDirective` no tenía consumidor de producción.** El intérprete real es el `when` de
   `RetryEngine.execute`; la costura pura era golden-test-only. Ambos lados quedaron
   actualizados; la directiva nueva evita el colapso booleano.
2. **P1 había abierto una regresión de comportamiento que P2-E cerró.** El whitelist W6 no
   reconocía las filas aggregate UNSTABLE que P1 empezó a persistir: caían al camino stale y
   podrían relanzar ramas. Cazada leyendo código, no por un rojo.
3. **El defecto "existe fichero de configuración sin versión" es análogo al que motivó P2:**
   una observación `Undeclared` no puede impedir resolver otra autoridad legítima. Registrado
   para el evolutivo de resolución de versiones del orquestador (limitación upstream: su
   adaptador `jvm_gradle` consulta `gradle.properties` antes que la autoridad real
   `rootProject.version`; NO se deforma PipelineK para satisfacerla).

## 4. Candidata materializada

Secuencia ejecutada SIN commits intermedios (el SHA certificado es exactamente el SHA del gate):

| Paso | Resultado |
|---|---|
| sourceCommit congelado | `5ea4137d` · árbol limpio |
| `candidateAdmission -Pcandidate.sequence=2` | **PASSED** · provenance VERIFIED |
| CandidateId | `sha256:02b1632e…dd635` (= SHA-256 del ZIP, 91.854.545 bytes) |
| Superficies de identidad | PRODUCT / ASSET / ARCHIVE_ROOT / IMPLEMENTATION_VERSION / RUNTIME / MANIFEST = `0.47.0` (las dos invisibles al probe ZIP verificadas sobre el extraído: `pipelinek version` → `pipeline 0.47.0`; JAR `Implementation-Version: 0.47.0`) |
| Tag | `v0.47.0-rc2` anotado → `5ea4137d`, pusheado |
| Release | prerelease `v0.47.0-rc2` con exactamente los assets declarados por el handoff + `SHA256SUMS` |
| Digest post-upload | re-descargado y byte-idéntico al CandidateId |
| Handoff | `pipelinek-release-harness#4`; el productor continúa sin esperar veredicto |

## 5. Qué este recibo NO dice

- **No certifica la candidata.** La certificación/promoción pertenece al release-harness; este
  recibo es el STEP-CERT del productor sobre `5ea4137d`.
- **No certifica el PRODUCT-GATE** (sin CI remota desde `754ddda0`).
- **Restart canonical de plain-step UNSTABLE** está cubierto vía autoridad compartida (misma
  `DefaultEffectReplayPolicy` en `DurableInvocationResolver`, misma proyección `outcomeOf`,
  restart de retry probado a nivel engine), no con fixture canonical dedicada. Declarado.
- **Deuda cosmética:** `BodyExecutionEngine:607-608` tiene un `return` duplicado (código muerto
  inocuo); registrada, no abierta.
