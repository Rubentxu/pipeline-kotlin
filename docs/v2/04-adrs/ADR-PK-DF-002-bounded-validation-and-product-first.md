---
type: adr
id: ADR-PK-DF-002
title: "Product First — retirar el framework de QA embebido y limitar pruebas por riesgo"
status: proposed
date: 2026-10-17
deciders: "Rubentxu (product owner): aprobación pendiente"
related:
  - ADR-0071
  - ADR-0072
  - ADR-0074
  - ADR-0099
  - ADR-0105
  - ADR-PK-QV-003
  - ADR-PK-QV-004
  - ADR-PK-DF-001
---

# ADR-PK-DF-002 — Product First, retirada radical del test-harness interno

**PROPUESTA — NO APROBADA.** Integrar el archivo como propuesta; no invocar este texto para eludir el `check` ni contradecir normas aceptadas. Presentar el diff de política al operador para decisión explícita antes de cambios incompatibles con `AGENTS.md` y ADR-PK-QV-003. Si no se ratifica, respetar el contrato vigente y registrar `BLOCKED_POLICY`.

## Contexto comprobable

RC6→HEAD: 437 líneas tocadas de producto frente a 5.788 de tests y 3.742 de fuentes de test-harness. V2 tiene `:test-harness` como módulo Kotlin de ~38 fuentes + 51 tests, con catálogos, planes, sidecars, ciclo y motor de retirada. Más importante: `pipeline-application` arrastra `:test-harness` en `implementation` y aloja `InProcessProductProbeExecutor` en `src/main`. La infraestructura QA ha entrado en la distribución ejecutable y crea otra superficie de fallo. Es una deriva económica y de responsabilidad. Las cifras son *diff*, no tiempo invertido.

## Decisiones propuestas (sustituyen **solo tras aprobación** políticas incompatibles)

1. **La garantía, no el test, es la unidad de conservación.** Por cada comportamiento crítico hay una prueba mínima discriminante y en su propietario; pruebas redundantes, `@Disabled` obsoletas, métricas de forma y categorías migradas se eliminan por lotes, con un sustituto único si hace falta.
2. **Desmontar `:test-harness` completo** del runtime y del build tras cutover demostrable. `cycle`, `plan`, `retirement`, `lanes`, `catalog` y `util` no se transfieren 1:1. Se borran si su garantía está ya cubierta por Gradle/JUnit, pruebas de dominio y `pipelinek-release-harness` externo. Prohibido crear un segundo sistema de gating para reemplazar al primero. Nunca dejar `implementation(project(":test-harness"))` en el binario.
3. **Excepción por producto:** `pipeline-event-harness` se conserva porque el consumidor real `pipeline events verify` lo utiliza. El nombre "harness" no es criterio de baja.
4. **Ciclo habitual reducido:** desarrollo ejecuta test afectado, merge ejecuta área + contratos críticos, integración centralizada/RC asume matriz completa correspondiente, instalable y certifier externo. El `check` global deja de ser condición de **cada** merge **solo tras aprobar la enmienda** a `AGENTS.md` y `ROADMAP.md`; no se elimina como mecanismo de RC/integración.
5. **UAT/AAT/E2E efímeras**: una ejecución externa por comportamiento nuevo cuando un test unitario no puede observarlo; 0 E2E en cambios ordinarios. No guardar cada UAT como regresión. Reusar smoke instalado por release. El test temporal se borra tras verificar aceptación y promover garantía esencial.
6. **La poda histórica se hace con criterio de equivalencia:** ADR-PK-QV-003 ya permite eliminar pruebas creadas en sesión; **no** autoriza eliminar legacy marcadas `migrated-pending` sin equivalencia. Para una retirada masiva se requiere consentimiento explícito de enmienda a `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4 / AGENTS y evidencia por **garantía**, no 5 recibos por fichero. No ocultar pérdidas de secretos/replay/aislamiento bajo "duplicada".
7. **Eliminar mecanismos de auto-certificación ad hoc** (sidecar por suite, `ImpactResolver`, `CycleRunner` y `DeletionGuard`) solo al probar dos hechos: las tareas seleccionadas realmente ejecutaron tests >0 y el artefacto de RC exacto está verificado por la autoridad externa. Ni XML suelto ni SHA inventado son veredicto.
8. **Presupuesto del QA**: un bloque de producto no puede acabar solo con más carriers, fitness/infraestructura QA o documentación de cómo probarlos. Las excepciones son P0 real y medido (secretos, pérdida de datos, falso verde grave, release corrupta). Medir latencia y falsos rojos, no introducir métricas de porcentajes como gates nuevos.

## Enmiendas necesarias tras aceptar la ADR

- `AGENTS.md`: reemplazar regla de `check` global por merge selectivo + integración/RC centralizada; mantener reglas de identidad, evidencia y escalado crítico. Eliminar comandos/normas de test-harness local que se retire, sin afectar SDDK ni publicación.
- `docs/v2/05-roadmap/ROADMAP.md`: conservar como **única autoridad** RP/B0–B7, integrar la poda como WorkUnit de desbloqueo de producto (no roadmap paralelo), registrar DONE solo tras código eliminado e integración real.
- `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`: proponer agrupar pruebas por *garantía/huella de fallo*, no por fichero, y permitir retirar `migrated-pending` con equivalencia externa agrupada y aprobación; no eliminar el requisito de demostrar nivel HF suficiente.
- `docs/v2/03-specifications/TEST_IMPACT_AND_EVIDENCE_SPEC.md` / `TEST_EXECUTION_LANES_SPEC.md` / `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`: registrar que el ejecutor custom se retira; **norma semántica** (evidencia veraz, aislamiento, Fidelidad HF) permanece en Gradle / certifier / skill. Si la SPEC exige `CycleManifest/DeletionGuard`, enmendarla o dejar bloqueo, no simular que existe.
- `ADR-0105` y `ADR-PK-QV-004`: no tocar propiedad de certificación externa ni RC tras limpieza; solo producto publica candidata, certifier publica check.

## Sustitutos y prohibiciones

- Gradle `Test` con resultados reales y task identity, `:pipeline-release:releaseGate`, APIs/product contracts existentes, consumidor externo en RC y `delivery-first-testing` ya instalada. No nuevo catalog/router/SaaS de QA/servicio/daemon ni serialización adicional.
- No borrar contratos reales por ser lentos: **moverlos** a la etapa correcta o sustituirlos por equivalentes más baratos y discriminantes. `SKIP/NOT_RUN/BLOCKED` no son `PASS`.
- No usar porcentajes de líneas para decidir seguridad; únicamente economía e impacto.

## Alternativas descartadas

A. **Seguir corrigiendo la FSM, sidecar y fidelity matrix del `:test-harness`:** conserva coste y fragilidad sin comportamiento nuevo de PipelineK. Solo aceptable como parche P0 temporal si el módulo no puede retirarse en el ciclo.

B. **Borrar todas las UAT sin equivalencia:** rápido pero rompe garantías y contradice ADR aceptada. No autorizado.

C. **Cambiar todo a GitHub Actions**: no forma parte de la infraestructura CI local vigente, genera nueva dependencia.

## Observabilidad y criterios de éxito

- Ninguna dependencia transitiva de `:test-harness` llega a ZIP/SDK; `pipeline events verify` continúa funcionando.
- Menos clases Kotlin de QA, menor coste medido de feedback y de cambio test-only.
- Gate selectivo sabe fallar al romper su contrato, RC instala ZIP real y certifier decide; no falso verde y no doble certificación.
- Al menos una feature/defecto real solucionado y entregado por ciclo. La poda es una fase de la entrega, nunca producto autónomo.
