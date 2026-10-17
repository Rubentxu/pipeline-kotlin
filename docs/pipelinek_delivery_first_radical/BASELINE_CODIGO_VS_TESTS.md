# Baseline cuantitativa de GitHub y presupuesto de QA

**Fuente:** compare público de GitHub `0253d3d670602273915387ef5aac0f4b14d74556` (`v0.62.0-rc6`) a `1c21f30357e5a9f56b257277cce92b40276d25cd` (`main`, 2026-10-17), **97 commits**, 197 archivos modificados en diff. Todas las cifras siguientes son suma de líneas **añadidas + eliminadas** del diff y no actividad histórica intermedia ni horas trabajadas. Clasificación por ruta:

| Grupo | Archivos tocados | Líneas modificadas |
|---|---:|---:|
| Código productivo `v2/**/src/main` fuera de `test-harness` | 14 | 437 |
| Tests `**/src/test` | 104 | 5.788 |
| Código `v2/test-harness/src/main` | 31 | 3.742 |
| Build, scripts, Gradle, justfile | 5 | 389 |
| Docs y evidencia | 26 | 2.083 |
| Otros | 17 | 2.543 |

**Ratio:** (5.788 + 3.742) / 437 ≈ **21,8**. La mejora de test-harness puede tener valor real, pero la desproporción muestra que la iteración se ha convertido en reconstrucción de sus propias pruebas. La métrica ayuda a decidir, no se usa como gate de QA ni como prueba de horas.

**Inventario HEAD (ruta, no comportamiento):** 885 Kotlin test `v2/**/src/test`; 415 en `v2/pipeline-application/src/test`; 38 fuentes `v2/test-harness/src/main`, 51 tests en `v2/test-harness/src/test`, 94 archivos con `Fitness` en nombre (no implica duplicidad). GitHub está libre de `check-runs` reportados para HEAD; no permite declarar gates PASS/FAIL. `v0.62.0-rc6` es última RC publicada que se verificó en la auditoría anterior, no certificación de HEAD. Revalidar antes de actuar.

## Medición reproducible en workspace

```bash
python3 /ruta/pack/tools/test_portfolio.py --repo /ruta/pipeline-kotlin --base 0253d3d670602273915387ef5aac0f4b14d74556 --out /tmp/test-portafolio.tsv
```

Medir **antes/después** de cada retirada: archivos QA/bundle, tareas Gradle `test` y `check`, clases/tests ejecutados (sin SKIP), tiempo p50 de 3–5 runs comparables, falsos rojos, cuánto tardó la primera validación de feature, ZIP bytes y tiempo entre commits de producto. **No fingir** que el número de líneas equivale a productividad. Objetivo económico: reducir el trabajo obligatorio de QA por feature y retirar dependencias innecesarias; cero contratos críticos perdidos.
