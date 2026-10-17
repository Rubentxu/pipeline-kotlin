# BQ5 — feedback p50/p95 real, no narrativa

**Fecha:** 2026-10-17
**Commit:** `d2bf51b0` (TaskTerminalDecision ADT + ShExecution refactor)
**Motivación:** cerrar el gap de BQ5 (22 % según audit del 2026-10-17): "Comparar p50/p95, mutantes, defectos antes/después vs base BQ2. Métrica comparable antes/después no publicada como KPI reproducible". Este recibo publica la primera métrica reproducible sobre la vertical durable.

## Setup

- **Host:** `bazzite-rubentxu` (mismo host que el resto de la sesión 2026-10-17)
- **Gradle:** daemon warm (warmup ejecutado antes de las 5 iteraciones; el `UP-TO-DATE` cache es el mismo en cada run porque las fuentes no cambian entre iteraciones)
- **Comando:** `cd v2 && ./gradlew :pipeline-domain:test --tests TaskTerminalDecisionTest --tests RetryReconcilerTest --tests WaitUntilCompletionProjectionTest --tests OperationStatusTest --tests FingerprintTest --rerun-tasks`
- **Selección:** cinco clases del paquete `pipeline-domain/.../durable/` que cubren el núcleo de tipos y leyes del runtime durable (incluye la nueva `TaskTerminalDecisionTest` post-refactor). El refactor tocó el seam `TaskExecutionResult → DurableTaskTerminal`, así que la métrica es sobre el código que cambió.
- **N=5:** mínimo exigido por `QUALITY_VALUE_ADDENDUM.md` §Métricas mínimas comparables para `feedback_p50` / `feedback_p95`.

## Datos crudos (wall-clock total, segundos)

| Run | Total time (s) |
|---|---|
| 1 | 16.623 |
| 2 | 16.527 |
| 3 | 18.257 |
| 4 | 17.653 |
| 5 | 16.786 |

## p50 / p95 / range

```text
sorted:               [16.527, 16.623, 16.786, 17.653, 18.257]
p50  (mediana):       16.786 s
p95  (con n=5, máx):  18.257 s   ← cota superior real, no estimación
mean:                 17.169 s
range:                1.730 s
coeficiente variación: 10.4 % sobre la mediana
```

> Nota: con n=5 el percentil p95 coincide con el máximo observado por construcción. Una afirmación "p95 = 18.0 s" requeriría ≥20 muestras para tener un intervalo de confianza razonable; este recibo publica la cota superior con honestidad y la nombra como tal. Una segunda tanda con n=20 es el siguiente paso real.

## Cobertura de los 5 runs (XML canary, AGENTS.md §0 rule 25)

Cada iteración corrió los mismos cinco archivos. Total tests por iteración = **65**. Comprobado leyendo los XML `TEST-*.xml` regenerados en cada run (`stat -c %y` confirma timestamp fresco `2026-10-17T07:03:14`):

| Clase | tests | failures | errors | skipped |
|---|---|---|---|---|
| `TaskTerminalDecisionTest` (post-refactor) | 9 | 0 | 0 | 0 |
| `OperationStatusTest` | 18 | 0 | 0 | 0 |
| `FingerprintTest` | 10 | 0 | 0 | 0 |
| `WaitUntilCompletionProjectionTest` | 7 | 0 | 0 | 0 |
| `RetryReconcilerTest` (nested: W0..W5 + Law 8/9 + Composite ADT) | 21 | 0 | 0 | 0 |
| **Total por run** | **65** | **0** | **0** | **0** |

5/5 BUILD SUCCESSFUL. **Cero verdes falsos:** el `--rerun-tasks` fuerza reejecución, los XML muestran timestamps frescos en cada run, y `failures="0" errors="0" skipped="0"` no admite el escenario "0 tests passed = PASS" del AAT-BQ1-01.

## Lectura para BQ5

| Métrica | Valor | Comparado con baseline BQ2 (estimación cualitativa previa) |
|---|---|---|
| feedback_p50 (este slice) | 16.786 s | incluido en el rango habitual de `:pipeline-domain:test`; comparable con un test enfocado |
| feedback_p95 (cota superior) | 18.257 s | la variabilidad del 10 % sobre mediana cabe en ruido de Gradle daemon / FS cache, no en regresión |
| flakes | 0 / 325 ejecuciones de test (65 × 5) | cero reintentos necesarios |
| mutant_survival_post_BQ3 | (no medida en este recibo) | el decider es puro, exhaustivo y mutado por las 9 leyes; un mutante que rompa la precedencia `timedOut > cancelled > exited` falla en `decider is a pure function — same input` o en `timedOut wins over exitCode` |

## Lo que este recibo NO prueba (honestidad residual)

- **No prueba** una reducción ≥40 % vs base BQ2 — esa baseline pre-BQ3 no fue medida con el mismo harness en este host. El addendum exige "≥5 muestras mismas clases de cambio y host comparable"; este recibo cumple el n=5 sobre las clases **post-cambio**, no sobre una baseline pre-cambio.
- **No prueba** un canary de mutación. Las 9 leyes son mutaciones a mano que muestran que la decisión colapsa al ADT correcto; un pitest run (`:pipeline-domain:pitest`) es el siguiente paso para cubrir mutantes generados automáticamente.
- **No prueba** un defecto escapado a RC; este recibo mide tiempo, no calidad del producto.

## Próximo paso natural (BQ5 self-check)

- Re-medir las mismas 5 clases en el commit anterior a `d2bf51b0` (worktree method, AGENTS.md §0 rule 16: "nunca clasificar como pre-existente sin evidencia base-vs-head fresca") para tener el delta real pre/post refactor.
- Ampliar n=20 para tener un p95 con intervalo de confianza decente.
- Añadir `:pipeline-domain:pitest` al gate de RC y comparar `mutation_survival` antes/después.