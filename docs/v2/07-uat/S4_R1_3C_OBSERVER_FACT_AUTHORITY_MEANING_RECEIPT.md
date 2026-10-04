# S4-R1 §3c — el observer dice el hecho, la autoridad le da significado

**Estado:** `IMPLEMENTED` — cierre de una ley del ADR. No cierra ninguna decisión diferida.
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `39e8ff030b775643ab66a75ba2fc515c49647521` (S4-R1 §3b, caracterización)
**ADR:** `ADR-S4-R1` línea 85, §2.3, §2.4, **D-1** (permanece DEFERRED)
**Decisión de ownership:** ninguna nueva. El observer ya era dueño de los hechos y el intérprete de la
persistencia; §3c sólo les quita una decisión que ninguno de los dos tenía.

---

## 1. Las tres afirmaciones

### 1.1 Hecho observado

```text
ReattachWindowExpired  ≠  Lost
```

El sustrato había dicho `Reattach` — el proceso puede seguir vivo — y nuestra ventana se cerró sin
terminal. Eso es un hecho sobre **nuestra ventana**, no un veredicto del sustrato. Antes de §3c el
observer devolvía `Lost`, que afirma «miré y no había nada recuperable».

### 1.2 Política actual de compatibilidad

```text
ReattachWindowExpired
    → Recover(RecoveredTerminal.Lost)
```

Declarada como **compatibility policy** en ADR-S4-R1 §2.3, y escrita en `DurableInvocationResolver`
como el único lugar del codebase que colapsa los dos hechos. El resultado observable no cambia:
una fila que ya se terminalizaba `LOST` se sigue terminalizando `LOST`.

### 1.3 Decisión diferida — D-1, sigue abierta

La futura reconciliación no-terminal, tal como la declara el ADR:

```text
no Execute
no terminalización inventada
journal permanece RUNNING
```

Motivo del diferimiento, textual del ADR: es semántica de reconciliación nueva y **cambia el resultado
observable de un caso certificado**. No se toca aquí.

## 2. El reshape

| fichero | antes | después |
|---|---|---|
| `RunningSubprocessObservation` | `Recovered(StepOutcome, OperationStatus)` · `Unavailable` | `Recovered(RecoveredTerminal)` · `Unavailable` · **`ReattachWindowExpired`** |
| `InvocationReconciliation.RecoverRunning` | `(StepOutcome, OperationStatus)` | `(RecoveredTerminal)` |
| `RecoveryInterpretationEngine` | leía `resolution.outcome` / `resolution.status` | proyecta `RecoveredTerminal` mecánicamente |
| `StepOutcome.toOperationStatus` | decidía TIMEOUT leyendo `failure.kind` | ya no participa en recovery |

**El observer no nombra `StepOutcome` ni `OperationStatus`** — es la línea 85 del ADR, implementada.
`RecoveredTerminal` lleva su propio payload por caso, así que `TimedOut` dejó de deducirse de
`failure.kind`: el flag del watchdog está en el disco, no en un mensaje.

## 3. Mutaciones

Ninguna es ortogonal a otra: las tres caen sobre fronteras distintas y el solapamiento end-to-end es
esperado. Las tres se restauraron con identidad de hash probada.

### M-3c-1 — `KILLED` (obligatoria del gate)

```text
fichero   DurableInvocationResolver.kt
pre       eb43489e097530ebbaa9b06a05d13a84fa4a65d4e31fcf63048f68deffc896c7
cambio    el brazo de compatibilidad deja de producir RecoveredTerminal.Lost
          y pasa a producir RecoveredTerminal.Succeeded
comando   ./gradlew :pipeline-application:test --tests '*S4RecoveryRequiredNeverExecutesTest*' \
                              --tests '*S4RRecIndeterminateEffectSpikeTest*'
post      eb43489e097530ebbaa9b06a05d13a84fa4a65d4e31fcf63048f68deffc896c7   IDÉNTICO
```

Mató: el test directo de la política de autoridad, y la fila 10 extremo a extremo.
Sobrevivió: la matriz de «required recovery nunca es `Execute`» — correcto, es una propiedad
estructural que el terminal correcto y el incorrecto ambos satisfacen. Por eso existe además un test
que fija **cuál** terminal.

### M-3c-2 — `KILLED` (obligatoria del gate)

```text
fichero   RecoveryInterpretationEngine.kt
pre       8965c14d7fdc1b31896a7cb50a0fdbeecd501ba211e1d91ce40c1d40600e91de
cambio    RecoveredTerminal.Lost -> OperationStatus.LOST  pasa a  -> OperationStatus.FAILED
comando   el mismo
post      8965c14d7fdc1b31896a7cb50a0fdbeecd501ba211e1d91ce40c1d40600e91de   IDÉNTICO
```

Mató: la fila 4 (`Lost` genuino de un directorio vacío) y la fila 10.
Sobrevivió: el test de autoridad — correcto, la proyección no le afecta.

### M-3c-3 — `KILLED` — sonda exploratoria de discriminación arquitectónica

**No es una mutación obligatoria del gate.** Se ejecutó porque la afirmación «la fila 8 discrimina la
frontera del observer» precisava evidencia, y producirla costó un minuto.

```text
fichero   RunningSubprocessRecovery.kt
pre       55274d173dfa1a9ac66619697ec3a9f586e3dc3c3925b1cc8f12b5541becbee3
cambio    el observer devuelve Recovered(RecoveredTerminal.Lost(...)) en vez de
          ReattachWindowExpired
post      55274d173dfa1a9ac66619697ec3a9f586e3dc3c3925b1cc8f12b5541becbee3   IDÉNTICO
          verificado con sha256sum -c → "La suma coincide"
```

Mató: las filas 8 y 9. **Sobrevivió: la fila 10.**

### Matriz de discriminación

| mutación | observer 8/9 | autoridad compat | fila 4 `Lost` | fila 10 E2E |
|---|---:|---:|---:|---:|
| M-3c-1 autoridad `Lost→Succeeded` | GREEN | **RED** | GREEN | **RED** |
| M-3c-2 intérprete `LOST→FAILED` | GREEN | GREEN | **RED** | **RED** |
| M-3c-3 observer `Expired→Lost` | **RED** | GREEN | GREEN | GREEN |

Tres fronteras distintas: **observer · autoridad · intérprete.**

### 3.1 Límite observacional end-to-end por diseño

La fila 10 **debe poder seguir verde** ante M-3c-3, y lo hizo. El test extremo a extremo observa el
terminal durable resultante y no puede distinguir dos caminos internos que convergen a propósito en la
misma política de compatibilidad:

```text
camino correcto    ReattachWindowExpired  →  RecoveredTerminal.Lost  →  OperationStatus.LOST
camino M-3c-3      Lost                   →  RecoveredTerminal.Lost  →  OperationStatus.LOST
```

Forzarla a ponerse roja filtraría detalles internos de la observación hacia una prueba cuyo nivel de
abstracción no debería conocerlos. **La discriminación semántica de esa diferencia pertenece a las
filas 8 y 9.** Esta frase existe para que nadie «mejore cobertura» acoplando el E2E a
`ReattachWindowExpired` dentro de unos meses.

## 4. Gate de cierre

```text
cd v2 && ./gradlew :pipeline-application:test :pipeline-architecture-tests:test
09:17:03 → 09:46:00 · EXIT=0 · BUILD SUCCESSFUL in 28m 57s · 0 "^e: "

pipeline-application          302 clases · 2269 tests · F=0 · E=0
pipeline-architecture-tests    87 clases ·  432 tests · F=0 · E=0
TOTAL                                   2701 tests · 0 fallos · 0 errores
```

Verificado por nombre y frescura, no por exit code:

| clase | tests | F/E | edad XML |
|---|---|---|---|
| `S4RRecIndeterminateEffectSpikeTest` (filas 8-10) | 12 | 0/0 | 42 s |
| `S4RecoveryRequiredNeverExecutesTest` (4, con el nuevo) | 4 | 0/0 | 42 s |
| `CoordinatorGrowthGuardrailTest` | 2 | 0/0 | 09:19:55, posterior al arranque |

`pipeline-architecture-tests:test` aparece **sin** `UP-TO-DATE`, así que el ratchet corrió contra este
árbol y no heredó un resultado anterior.

Resto de comprobaciones de cierre:

```text
ratchet            572 = 572 (techo == tamaño real)
git diff --check   EXIT=0
residuos M-3c-1    0
residuos M-3c-2    0
residuos M-3c-3    0
hashes pre/post    identidad demostrada en las tres
```

## 5. Incidente operacional

El primer comando de restauración de M-3c-2 fue abortado por el runtime, después de que la mutación ya
había producido su RED. **No invalida la mutación** — el RED ya estaba medido — pero dejaba sin
demostrar que el árbol volviera al estado pre-mutación. Se cerró antes de continuar: se validó el hash
del backup **antes** de tocar el target, se restauró con `cp --`, y se exigió
`restored_hash == pre_mutation_hash`. No se usó `git restore` ni `git checkout` en ningún momento, y
hacerlo habría sido un error: el snapshot correcto incluye los cambios legítimos no commiteados de 3c,
que `HEAD` no tiene.

## 6. Lo que este documento NO hace

- **No** cierra D-1. El terminal sigue siendo `LOST`; la alternativa no-terminal sigue diferida.
- **No** toca `EffectReplayPolicy`, ni el formato durable, ni `OperationStatus`.
- **No** introduce `RecoveryPolicy.Unavailable` ni ningún otro valor de enum.
- **No** reescribe los recibos de `S4-R1 §3b` ni de `S4-R-REC`: cada uno sigue siendo evidencia de su
  propio SHA. Este documento **extiende** la matriz y declara qué filas cambiaron en este SHA.
- **No** renombra `RecoveryUnobservable` a `FailClosed`. La reconciliación terminológica queda para el
  bloque de ADRs, y es de dos niveles: `fail-closed` es la propiedad arquitectónica,
  `RecoveryUnobservable` la decisión concreta que hoy la satisface. Con una sola razón concreta no hay
  evidencia para una familia algebraica, y nombrarla ahora sería abstracción anticipada.

## 7. Referencias

- `docs/v2/04-adrs/ADR-S4-R1-single-reconciliation-authority.md` — línea 85 (contrato del observer),
  §2.3 (política de compatibilidad), §2.4 (`RecoveredTerminal`), **D-1** (diferida), D-2..D-7
- `docs/v2/07-uat/S4_R1_3B_REATTACH_WINDOW_EXPIRED_RECEIPT.md` — las filas 8-10 **antes** de §3c, y el
  hallazgo medido que hizo posible el reshape
- `docs/v2/07-uat/S4_R_REC_RECOVERY_OBSERVABILITY_RECEIPT.md` — filas 1-7, evidencia de su SHA
- `v2/pipeline-step-sdk/runtime/…/StepReconcilerL1.kt` — `Classification`, la autoridad que ya
  distinguía `Lost` de `Reattach`
