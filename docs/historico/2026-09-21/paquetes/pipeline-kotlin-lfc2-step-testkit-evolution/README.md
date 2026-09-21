# pipeline-kotlin — LFC-2 Step/Plugin/TestKit Evolution Pack

**Objetivo:** paquete documental mergeable para `Rubentxu/pipeline-kotlin`, preparado para reforzar LFC-2 y adelantar únicamente las piezas mínimas de LFC-3/LFC-4/LFC-5 que son prerequisitos arquitectónicos reales.

**Baseline observado:** `5af901c75782cfdbd35bb24e0fb52f6d088addc1` — 2026-09-08.

## Idea central

Un Step no se considera terminado porque exista una función del DSL, un `StepSpec`, un decoder o un handler.

Un Step se considera terminado cuando:

1. tiene un único contrato tipado;
2. compila a una invocación canónica;
3. se resuelve por registro;
4. declara y recibe capabilities explícitas;
5. ejecuta por el mismo spine que cualquier plugin externo;
6. produce eventos/resultados tipados;
7. respeta replay, cancelación y body semantics;
8. pasa una suite de certificación común;
9. tiene al menos un escenario `.pipeline.kts` ejecutable;
10. puede probarse en el nivel de aislamiento adecuado.

## Qué añade este paquete

- ADR-LFC-018..023.
- SPEC-LFC-016..021.
- Delta de ROADMAP.
- Delta de IMPLEMENTATION_BACKLOG.
- Delta de UAT_CATALOG, TEST_MATRIX y UAT_RUNBOOK.
- Delta de TRACEABILITY_MATRIX.
- Constitución normativa para `AGENTS.md`.
- Estrategia de evolución de `examples/`, `compatibility/`, fixtures y plugins de referencia.
- Diseño del nuevo Pipeline Test Harness inspirado en las ideas de Jenkins Test Harness.
- Estrategia de sandbox por capas: JVM → fork real → restart → Podman rootless → servicios → online smoke.
- Definition of Done y certificación para Step y Plugin.

## Relación con decisiones ya existentes

Este paquete **no reemplaza** las decisiones LFC ya presentes. Las refina.

En particular preserva y extiende:

- ADR-LFC-003 — Jenkins-like type-safe builder DSL.
- ADR-LFC-004 — declarative vs durable scripted boundary.
- ADR-LFC-005 — generated plugin SDK.
- ADR-LFC-006 — typed capabilities.
- ADR-LFC-007 — single suspend execution spine.
- ADR-LFC-015 — layered sandbox strength.
- ADR-LFC-016 — Plugin TestKit.
- SPEC-LFC-003 — Plugin SDK.
- SPEC-LFC-014 — sandbox profiles.
- SPEC-LFC-015 — Plugin TestKit.
- ROADMAP / IMPLEMENTATION_BACKLOG / UAT_CATALOG actuales.

## Integración

Los archivos `*_DELTA.md` son transitorios: se fusionan en las autoridades actuales y después se eliminan.

Los ADR y SPEC nuevos sí son permanentes.

Orden recomendado:

1. ADR-LFC-018..023.
2. SPEC-LFC-016..021.
3. `ROADMAP_DELTA.md`.
4. `IMPLEMENTATION_BACKLOG_DELTA.md`.
5. UAT / Test Matrix / Runbook deltas.
6. `AGENTS_STEP_PLUGIN_CONSTITUTION.md`.
7. migración progresiva de examples/corpus.
8. traceability.
9. regenerar índices/manifiestos del repositorio.

Ver `MERGE_ORDER.md`.
