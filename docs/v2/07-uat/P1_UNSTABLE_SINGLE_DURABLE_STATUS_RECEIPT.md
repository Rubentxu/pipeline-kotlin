# P1 — Unstable: un único significado terminal en la capa durable

**Item:** `0553d7ef-adec-417c-9f8f-effc754425d0` (Runtime Observation Contract Closure) · Slice P1
**Certificado sobre:** `e1ca51df02e4d219051c4ac72acf96c5154872f8` (rama `p1-orphan-core-sh`, árbol limpio)
**Gate:** `BUILD SUCCESSFUL in 30m 10s` · `329/329` tareas ejecutadas · `check --rerun-tasks`
**Log:** `/var/home/rubentxu/.local/state/pipelinek-gates/gate-p1-e1ca51df.log`
**Fecha:** 2026-10-05

---

## 1. Qué establece P1

`OperationStatus` gana el caso `UNSTABLE` (terminal, no poll-failure) y los tres sitios que
colapsaban el mismo hecho de tres maneras distintas persisten ahora una sola verdad:

| Sitio | Antes | Ahora |
|---|---|---|
| `CanonicalStructuralDecisions.toOperationStatus` | `StepOutcome.Unstable → FAILED` | `→ UNSTABLE` |
| `ParallelStageEngine.aggregateStatusOf` | `BranchTerminal.Unstable → ABORTED` | `→ UNSTABLE` |
| `RetryEngine.persistTerminalTransition` | persistía `FAILED` | persiste `UNSTABLE` |

Dos correcciones auxiliares del mismo defecto: el brazo `else -> error(...)` de `RetryEngine`
habría lanzado en runtime sobre el estado que este slice introduce (la tabla no conocía
`UNSTABLE`), y `WaitUntilReconciler` recibe un brazo `UNSTABLE` explícito fail-closed en vez de
caer en el `else` de "unknown status".

`UNSTABLE` es `terminal = true` e `isPollFailure = false`: no-poll-failure preserva la semántica
de retry existente (unstable no reintenta), haciéndola explícita sin cambio de comportamiento.

## 2. El defecto que el compilador no ve

`isTerminal` es `this in terminalStates`, y `terminalStates` es un `setOf` en el companion
object — **no un `when`**. Añadir `UNSTABLE` a la enum compiló, y 21 módulos reconstruyeron con
cero errores, mientras `UNSTABLE` no era terminal:

- `transition(RUNNING, UNSTABLE)` devolvía `Result.failure`: la escritura de journal que el
  cambio introducía era una transición ilegal.
- `isTerminal` falso: `OperationJournal` no habría sellado `endedAt` a una operación completada.

Un `when` habría rechazado la compilación. Un `setOf` no puede. Esa clase de defecto quedó
convertida en ley estructural por la fila 3 de `UnstableSingleDurableStatusFitnessTest`
(`every durable status production finishes with is terminal in the algebra`), que pregunta a
`OperationStatus.transition` — la autoridad de producción, no una copia local de la regla.

## 3. Evidencia sobre `e1ca51df`

### 3.1 Tests

4 clases / 31 tests / 0F 0E 0S (XML fresco 12:06), luego absorbidos por el gate completo:

- `OperationStatusTest` (18): `UNSTABLE` terminal, `RUNNING→UNSTABLE` legal, no poll-failure,
  y la suite de transiciones existente con `UNSTABLE` añadido a la lista de terminales.
- `P1UnstableSingleDurableStatusTest` (7, nuevo): comportamiento a través de
  `StepOutcome.toOperationStatus()` (HF0, autoridad alcanzable).
- `UnstableSingleDurableStatusFitnessTest` (3, nuevo): los dos sitios privados
  (`ParallelStageEngine.aggregateStatusOf` es `private`; el brazo de retry solo es alcanzable por
  el loop durable completo) leídos de fuente con comentarios eliminados, más la ley del `setOf`.
- `S4A0ScriptedUnstableOutcomeCharacterizationTest` (3): primera fila transicionada
  CHARACTERIZED → NON-REGRESSION con registro de transición en el mensaje (HF Fidelity Law §5).

### 3.2 No-vacuidad por mutación (3/3, una fila por mutación)

| Mutación | Fila que voltea | Restauración |
|---|---|---|
| M-P1-1: `ParallelStageEngine` UNSTABLE→ABORTED | `every Unstable arm writes only UNSTABLE` | hash verificado `0779efde…` |
| M-P1-2: `CanonicalStructuralDecisions` brazo→throw | `the known Unstable arms are all still mapped` | hash verificado `217b7d44…` |
| M-P1-3: UNSTABLE fuera de `terminalStates` | `every durable status production finishes with is terminal` | hash verificado `eece5798…` |

### 3.3 ABI

`v2/pipeline-domain/api/pipeline-domain.api`: **+1 campo** (`OperationStatus.UNSTABLE`),
**0 cambios de firma**.

### 3.4 Gate completo

`./gradlew check --rerun-tasks` sobre `e1ca51df`, árbol limpio:
**734 XML / 4886 tests / 0F 0E 140S**, 19 módulos con XML, 329/329 tareas,
0 errores `^e:`, 0 implicit-dependency. `GATE_EXIT=0`.

El delta contra el gate anterior (732 XML / 4873 tests, `c32fa1e1`-contenido) son exactamente
los tests nuevos de P1: +2 ficheros XML, +13 tests, mismos skips (140).

## 4. Historia del gate: los dos RED honestos

El primer gate completo (sobre `1b642884`) **falló correctamente**: 2 rojos en
`:pipeline-application:test`, ambos tests que fijaban el colapso antiguo, no defectos del cambio
de producción. Los tests quirúrgicos de desarrollo cubrían el camino nuevo; estos dos fijaban el
camino viejo y solo el gate pudo verlos.

1. `WULpr302RetryEngineTest.OutcomeFolding` — "persists FAILED on that attempt": el colapso
   estaba en el propio nombre del test. Transicionado a `UNSTABLE` con registro de transición
   explícito (nombre + mensaje).
2. `S4D2ScriptedUnstablePreservationTest` DURABLE FRONTIER — esperaba `FAILED` en la proyección
   durable. La deuda grabada se partió en dos mitades verificadas contra fuente
   (`EffectReplayPolicy.kt:95,128,137`): la de **representación** (el colapso) la cierra P1; la
   de **replay** (ninguna regla reutiliza una fila no-SUCCEEDED, luego el trabajo
   completado-inestable se re-ejecuta al reanudar) queda grabada como deuda con su propio punto
   de decisión. Ninguna regla fue ensanchada en P1.
3. Un tercer test pasaba pero su mensaje afirmaba que el formato durable no distingue `Failure`
   de `Unstable` — P1 volvió eso falso. Prosas corregida, aserto intacto.

Ambas transiciones + la prosa: commit `e1ca51df` (`test(durable)`), con el escaneo de los módulos
que el gate abortado no alcanzó (architecture-tests, testkit, event-harness, cli,
binding-factory): cero ficheros que fijen el colapso.

## 5. Equivalencia de la reescritura de historia (11:58:51)

Durante esta sesión la historia bajo HEAD fue reescrita sin mensaje de reflog. Verificado por
tree-hash (prueba criptográfica de contenido idéntico byte a byte):

```
c32fa1e1  tree 510d50ab6cf0370f66202cb4b4c20d2d02644407   ← colgante
6e101daf  tree 510d50ab6cf0370f66202cb4b4c20d2d02644407   ← vivo (padre de 4dde04a2)
3b491cfe  tree 86cff7c9620fa96511020aa35a405e546c9dc61b   ← colgante
4dde04a2  tree 86cff7c9620fa96511020aa35a405e546c9dc61b   ← vivo (padre de 1b642884)
```

**Decisión registrada** (gates humanos aprobados por el operador): mantener la historia nueva.
No se recrean ramas para commits nunca publicados ni se reescribe historia de nuevo para
restaurar SHAs muertos. Consecuencia para la trazabilidad: el recibo R1-E declara
`Certificado sobre: c32fa1e1`; ese SHA está colgando pero su tree-hash prueba que el contenido
que certificó es el contenido alcanzable hoy como `6e101daf`. La equivalencia es de contenido,
no de SHA — y por Certification Law el recibo de R1-E sigue siendo evidencia de SU SHA; este
párrafo es el puente documental, no una re-certificación.

## 6. Qué este recibo NO dice

- **No dice que la deuda de replay esté cerrada.** Una fila `UNSTABLE` se re-ejecuta en una
  segunda invocación porque `EffectReplayPolicy` solo reutiliza `SUCCEEDED`. Eso es la frontera
  que el slice **P2 (Durable Outcome + Replay Convergence)** cierra; este recibo la deja
  grabada, no resuelta.
- **No dice compatibilidad de downgrade.** `OperationStatus` se persiste por nombre y se lee con
  `valueOf`: un binario anterior a P1 lanzará `IllegalArgumentException` al leer una fila
  `UNSTABLE`. La misma coyura se aceptó al añadir `FAILED_TIMEOUT`; P2-G debe decidirlo
  conscientemente (posición A: fail-closed / durable epoch).
- **No certifica el PRODUCT-GATE** (sigue `BLOCKED_EXTERNAL` desde `754ddda0`: sin CI remota).
  Este recibo es STEP-CERT sobre `e1ca51df`.
