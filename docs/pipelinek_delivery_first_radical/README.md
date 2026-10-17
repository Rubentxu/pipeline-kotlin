# PipelineK — CORTE RADICAL de tests/harness + entregas Product First

**Versión refinada 2026-10-17 · base consultada:** `main@1c21f30357e5a9f56b257277cce92b40276d25cd`. **Este ZIP no modifica tu repo**, ni declara gates ejecutados. Es un overlay + instrucciones para agente, sin vendorear skills, artefactos ni fuentes de terceros.

## Decisión práctica

**Eliminamos el harness de pruebas como producto.** En lugar de arreglar indefinidamente `CycleRunner`, `EvidenceManifest`, `ValidationPlan`, `GateCli`, `DeletionGuard`, `LanePlanner`, etc., se plantea **retirar el módulo completo `:test-harness`** de la aplicación y del build, tras verificar que no queda consumidor contractual. NO significa borrar `:pipeline-event-harness`: éste alimenta `pipeline events verify`, funcionalidad pública real. Tampoco significa eliminar regresiones durables de secretos, recuperación, seguridad o compatibilidad: **conservamos una prueba pequeña en el seam correcto** o una comprobación externa equivalente.

**Prioridad:** retirar peso y automatizaciones duplicadas primero; entregar nueva funcionalidad observable en el mismo bloque. La poda nunca justifica una release por sí sola si no desbloquea un comportamiento útil o mejora cuantificada del ciclo de entrega.

## Números comprobados en GitHub

- Delta de RC6 `0253d3d` → HEAD `1c21f303` (97 commits): código de producto 437 líneas tocadas; tests 5.788; `test-harness/src/main` 3.742 (incluye adiciones y borrados). Relación ~21,8:1 tests+harness/producto. Es tamaño de diff, **no horas ni esfuerzo**.
- HEAD: 38 archivos Kotlin productivos en `v2/test-harness/src/main/`, 51 Kotlin en `v2/test-harness/src/test/` (89 archivos del módulo); 885 archivos Kotlin de tests bajo `v2/**/src/test/`. No se propone borrar 885.
- `:pipeline-application` tiene `implementation(project(":test-harness"))` y `InProcessProductProbeExecutor` en `src/main`: **dependencia de QA dentro del runtime distribuible**. Ver `INVENTARIO_Y_CORTES.md`.
- Una revisión histórica clasificaba 62 Kotlin con `migrated-pending`; **recontar contra el checkout vivo** antes de modificar (el script suministrado lo hace).

## Ficheros de este paquete

| Fichero | Función |
|---|---|
| `overlay/docs/v2/05-roadmap/PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md` | **Roadmap H0–H5**: H0 corta harness; H1–H5 entregan producto |
| `overlay/docs/v2/03-specifications/PRODUCT_FIRST_MINIMUM_VERIFICATION_SPEC.md` | **Contrato mínimo de verificación**: gates por riesgo/etapa, pruebas efímeras, criterios de corte |
| `overlay/docs/v2/04-adrs/ADR-PK-DF-002-bounded-validation-and-product-first.md` | **ADR PROPUESTA** de cambio de política, con precedencia y conflictos explicitados |
| `INVENTARIO_Y_CORTES.md` | **Rutas reales, oleadas, protecciones, evidencias de retirada** |
| `PLAN_DE_PODA_EJECUTABLE.md` | Plan de cortes con commands y reversión en cada oleada |
| `tools/test_portfolio.py` | Inspector **solo lectura**, stdlib Python, clasifica candidatos y cuantifica Git/HEAD |
| `PROMPT_AGENTE.md` | Instrucción entregable a agent teams con gates, topes y prioridades |
| `INTEGRACION.md` | Instalar overlay sin duplicar roadmap ni pisar normas aceptadas |
| `BASELINE_CODIGO_VS_TESTS.md` | Comparación cuantitativa y método reproducible |

## Autoridad y compatibilidad: imprescindible

`AGENTS.md`, `docs/v2/05-roadmap/ROADMAP.md`, ADR-0072/0074, ADR-0099, ADR-0105, ADR-PK-QV-003/004, `RESPONSIBILITY_MIGRATION_ROADMAP.md` y SDDK siguen vigentes. **Esta poda cambia política:** el ADR de este ZIP está **propuesto, no aceptado**. Antes de relajar `check` o de borrar UAT heredadas protegidas, el agente debe **proponer la enmienda explícita** a las cláusulas en conflicto para aprobación; **no declarar autorización** por incluir este archivo. Sin esa aprobación, la poda segura se limita a archivos no normativos/obsoletos y a mover dependencias sin perder garantías.

Skill existente: `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`, router `references/navigation.md`, `references/lens-registry.json`, lentes `harness-economics`, `change-impact`, `regression-promotion`, `ephemeral-uat`, `merge-assurance`, `integration-assurance`, `release-assurance`, `failure-triage`. Solo lentes `ready`. No copiar skill al repo ni construir un router nuevo.
