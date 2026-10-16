# Presupuestos y criterios de aceptación de calidad/velocidad (propuestos)

## Evidencia veraz

Toda medición se ata a: `{repo, fullCommitSha, treeSha, env (OS/JDK/CPU), command, startedAt, durationMs, testCount, failCount, skippedCount, artifactSha256, status}`. Si falta un atributo obligatorio del claim, declarar `UNKNOWN` o `NOT_RUN`.

**Budget de feedback BQ2:** p50/p95 medidos sobre 5 ejecuciones del mismo cambio y host; objetivo inicial `p50_fast_after <= 0.60 * p50_fast_before` sin perder mutantes críticos. Una comparación de máquinas distintas no es un resultado de optimización.

**Budget de release:** no fijar tiempos absolutos hasta medir candidate BQ1; registrar distribuciones/JVM lanzadas, wall time de `check`, ABI, detekt, pruebas de integración y certificación externa. Prioridad: menos validaciones repetidas por commit, no ocultar tests a costa de regresiones.

**Criterios negativos:** XML sin pruebas, skipped total, falla Gradle, SHA ajeno, asset reconstruido, `@Disabled` desconocido, infraestructura ausente, veredicto del harness no disponible ⇒ nunca PASS.

**Bugs detectados por mutación:** por cada ley crítica mantener un mutante de referencia; no convertirlo en una ejecución pesada diaria si la misma propiedad tiene un contrato puro más temprano. Seguridad/replay/ABI merecen prueba de frontera en integración/release.

## Métricas de entrega por hito

| Indicador | Fuente | Éxito |
|---|---|---|
| `functional_value` | CLI/API/SDK demo real | caso de uso que antes fallaba/costaba más y ahora funciona |
| `fast_wall_p50` | task Gradle y script medido | mejora significativa vs baseline comparado |
| `selected_not_run` | planner vs evidence | **0** con casos negativos probados |
| `missing_owners` | catálogo fitness | **0 sin clasificación** en superficies soportadas |
| `ephemeral_remaining` | repo SHA final + cycle dir | **0 pruebas de ciclo** versionadas; evidencia conservada |
| `legacy_deleted_without_proof` | retiro ledger | **0** |
| `candidate_id_consistency` | manifest + archive + tag | mismo SHA/digest y tags alcanzables desde main |
| `external_cert` | pipelinek-release-harness | estado veraz, incluyendo `BLOCKED_EXTERNAL` |
