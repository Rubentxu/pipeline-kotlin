# Estado actual y deuda que V2 debe resolver

> **ADDENDUM 2026-10-01 — snapshot vigente (supersede este archivo como referencia).**
>
> **Release:** `v0.46.0` ESTABLE (tag en origin → `63ef3220`; CandidateId de los bytes:
> `59478baf…`; ver `docs/v2/08-production-readiness/V0460_STABLE_PROMOTION.md` y
> `CANDIDATE_0460_RP5_EVIDENCE_BUNDLE.md`).
>
> **Roadmap de producto:** RP-0..RP-4 cerrados; RP-034 (workspace/local-first) y RP-035
> (open-world body handlers vía `HANDLER_CONTINUATION`) cerrados; **RP-5 productor
> sellado** (PR-010..015 sobre el candidato exacto; PR-016 = veredicto del harness
> externo). Siguiente frontera tras 0.46.0: hardening arquitectónico H1..H4
> (PR-017..020) y después RP-6.
>
> **Ciclos SDDK activos:** exactamente uno — `p-1f3622e11c093341/pr017-run-lifecycle-engine`
> (RunLifecycleEngine extraída con bit-equivalencia probada; coordinador 2473 líneas;
> guardrail anti-crecimiento activo). Cerrados: rp-5-exact-candidate-certification
> (superseded → pr017), rp-034-closure-candidate (superseded → pr017),
> train-s2-directive-plugin (goal-replaced; divergencia documentada en
> SCOPE_DIVERGENCE_RECONCILIATION_S2D_RP034.md), train-s2-directives, recover-041.
>
> **Leyes operativas vigentes:** ver AGENTS.md — CandidateId = SHA256 del ZIP;
> publicar = mismos bytes; guardrail de coordinador; caracterización antes de
> extracción; sin `UP-TO-DATE` como evidencia.
>
> El contenido histórico abaixo queda como referencia de agosto 2026; NO es el
> estado vigente.

# Estado actual y deuda que V2 debe resolver

> Snapshot de referencia: rama `main`, 2026-08-21.
> **Addendum 2026-09-06 (cycle em-0):** ver sección "Estado EM" al final.

## Observaciones

### Kotlin
`settings.gradle.kts` configura actualmente Kotlin/KSP en la línea **2.2.0**. V2 propone elevar la línea certificada inicial a **2.4.10**.

### Core parcialmente excluido
`core/build.gradle.kts` mantiene una lista extensa de `exclude(...)` sobre DSL, engines, execution, compilation, security, plugins, libraries, steps, modelos, context, events y Jenkins. El compiler plugin de `@Step` aparece deshabilitado por errores IR.

### Testing incompleto
Hay tests y módulos de testing deshabilitados o filtrados. M0 debe recuperar una baseline honesta antes de ampliar producto.

### Generaciones arquitectónicas superpuestas
Coexisten distintos enfoques para context, DSL, runners, step discovery, compiler plugin, sandbox y eventos. V2 no debe intentar fusionarlos todos; usará **Strangler Fig interno**.

## Deuda conceptual

1. Dependencias escondidas vía Service Locator.
2. Koin visible en abstracciones del core.
3. Closures ejecutables almacenadas como modelo de pipeline.
4. Runtime y construcción del DSL mezclados.
5. Reflexión + string dispatch como mecanismo primario de Steps.
6. `Map<String, Any>`/`Any?` en contratos susceptibles de cruzar procesos.
7. Eventos in-memory sin journal duradero.
8. Sandbox basada en Security Manager de Java.
9. Classpath completo del host expuesto al script.
10. Compiler internals en el camino crítico.
11. Tests excluidos como estado estable.
12. Documentación que afirma capacidades sin evidencia CI/UAT.

## Clasificación de migración

Cada componente V1 se etiquetará:

- **KEEP**: reutilizable sin contaminar V2.
- **ADAPT**: útil detrás de un port.
- **REWRITE**: semántica aprovechable, implementación inadecuada.
- **RETIRE**: duplicado o incompatible con V2.
- **SPIKE**: exploración sin compromiso de producción.

## Estado EM (addendum 2026-09-06, cycle em-0)

El programa **EM — Durable Kotlin Execution Model** (ADR-0065) es el track
activo, como refinamiento de LFC-4/5/6 (mapeo en
`05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md`). Estado:

La línea actual y autoritativa está en `docs/v2/`; la disposición y la
procedencia histórica del paquete absorbido están registradas en
[`EXECUTION_MODEL_PROPOSAL_DISPOSITION.md`](EXECUTION_MODEL_PROPOSAL_DISPOSITION.md).

- **EM-0/EM-S0**: contract freeze en curso; SPIKE-016 ampliado PASS 24/24
  (2026-09-06); ADR-0065 aceptado; la expansión productiva sigue bloqueada
  por verificación; ADR-0066/0067/0068 en `proposed`.
- **EM-1/EM-2**: implementados (fuera de SDDK por un agente IDE externo;
  incorporados y trazados por el ciclo em-0); pendientes de release gate.
- **EM-3**: parcial — `ShExecution` tipado; matriz UAT-JEP y timeout-grammar
  incompletos (`UatDsl005TimeoutGrammarTest` en el receipt).
- **Deuda activa**: ~85 fallos de baseline sin clasificar
  (`EM0_BASELINE_RECEIPT.md`) requieren reconciliación base-vs-head en
  verify; el gate completo de verify no está green. La línea F-1..F-8 de withCredentials sigue abierta (8 UATs fallidos
  conocidos, UAT008 19 PASS / 8 FAIL); deuda de código muerto/transicional
  inventariada en `EM_DEAD_CODE_AUDIT.md` (borrar en EM-10, deprecar por
  fase).

El snapshot 2026-08-21 anterior se conserva como referencia histórica; sus
12 puntos de deuda conceptual siguen siendo válidos como motivación de V2.
