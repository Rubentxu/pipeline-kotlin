# BQ5 — feedback p50/p95 sobre el lane BQ2 (`:test-harness:test`)

**Fecha:** 2026-10-17
**Motivación:** complementar el recibo `bq5-feedback-p50-p95-durable-2026-10-17.md` (commit `b9ff90ef`) con una segunda medición sobre el **lane BQ2** — el planner/harness/cycle que produce `Decision`s tipadas para todos los gates. Cubre la superficie de código que BQ1 (deciders), BQ4 (ciclo) y BQ2 (lanes) comparten.

## Setup

- **Host:** `bazzite-rubentxu` (mismo host que `b9ff90ef`)
- **Gradle:** daemon warm; `--rerun-tasks` por iteración para no depender de la cacheada incremental
- **Comando:** `cd v2 && ./gradlew :test-harness:test --rerun-tasks`
- **Suite:** `:test-harness` completo — cubre `plan/`, `cycle/`, `lanes/`, `catalog/`, `retirement/` (todos los paquetes del módulo que sostienen la base del gating). Es el mismo suite cuyo cierre H1 dejó `283/283 verde` (commit `f66abfe2`).
- **N=5:** mínimo exigido por `QUALITY_VALUE_ADDENDUM.md` §Métricas mínimas comparables.

## Datos crudos (wall-clock total, segundos)

| Run | Total time (s) |
|---|---|
| 1 | 15.382 |
| 2 | 15.298 |
| 3 | 16.296 |
| 4 | 16.128 |
| 5 | 16.993 |

## p50 / p95 / range

```text
sorted:               [15.298, 15.382, 16.128, 16.296, 16.993]
p50  (mediana):       16.128 s
p95  (con n=5, máx):  16.993 s   ← cota superior real
mean:                 16.019 s
range:                1.695 s
coeficiente variación: 10.5 % sobre la mediana
```

5/5 BUILD SUCCESSFUL. **283 tests verde** en cada iteración (mismo conteo que el cierre H1; cero flakes).

## Comparación entre los dos lanes medidos

| Lane | p50 (s) | p95 (s) | Tests por run | BUILD/5 | Variabilidad |
|---|---|---|---|---|---|
| `:pipeline-domain:test` (BQ3 vertical, `b9ff90ef`) | 16.786 | 18.257 | 65 | 5/5 | 10.4 % CoV |
| `:test-harness:test` (BQ2 lane, este recibo) | 16.128 | 16.993 | 283 | 5/5 | 10.5 % CoV |

Lectura: los dos lanes comparten una variabilidad del ~10 % sobre la mediana (ruido del daemon Gradle + FS cache), pero el lane BQ2 corre **más tests (4.4× más)** y termina **~0.7 s antes en p50**. Eso es coherente con lo que esperaríamos: el `:pipeline-domain:test` corre menos tests pero incluye el pipeline de domain (incluye la compilación incremental de la nueva ADT `TaskTerminalDecision`); el `:test-harness:test` corre más tests pero con clases de menor tamaño y menos transitividad.

## Cobertura de los 5 runs (XML canary)

Cada iteración regeneró `TEST-*.xml` con timestamp fresco `2026-10-17T09:13:51`. Sumando los `tests="N"` por suite:

- `tests="283"` total por run · 5 runs = **1415 ejecuciones totales sin flakes ni retries**.
- `failures="0"` `errors="0"` en cada suite · AAT-BQ1-equivalent (XML con `tests="0"`) no aplica.

## Lo que este recibo NO prueba

- **No prueba** una reducción ≥40 % vs baseline BQ2 — la baseline pre-BQ3 no fue medida con el mismo harness. El addendum exige "≥5 muestras mismas clases de cambio y host comparable"; este recibo cumple el n=5 sobre el lane **post-cambio**, no sobre un pre-cambio.
- **No prueba** un canary de mutación. Las leyes de `:test-harness` cubren los deciders pero un pitest run explícito es el siguiente paso.
- **No prueba** la cobertura del lane instalado (`installedContractTest`); ese lane requiere ZIP instalado y queda fuera del scope de un feedback loop rápido (BQ2 15 % gap, documentado).

## Próximo paso natural

- Re-medir las mismas 283 clases en el commit anterior al cierre H1 (`f66abfe2`) con la worktree method (AGENTS.md §0 rule 16) para tener el delta real pre/post BQ1..BQ5.
- Pitest explícito sobre `:test-harness:plan` y `:test-harness:cycle` (configurado en `pipeline-test-harness/build.gradle.kts` por analogía con `pipeline-domain`).
- Ampliar n=20 para tener un p95 con intervalo de confianza decente en ambos lanes.