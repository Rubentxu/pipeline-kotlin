# Documentos activos y autoridad de V2

**Actualizado:** 2026-09-26 (TRAIN-0 cutover). **Estado:** índice de lectura ACTIVO.

## Autoridad operativa

```text
SDDK + Git + ADRs/contratos publicados + evidencia externa (harness, GitHub)
       → autoridad efectiva
ROADMAP / ACTIVE_DOCUMENTS / ADR / specs / UAT
       → autoridad de producto/documental
.agent/SESSION_POINTER.md, .agent/WORK_JOURNAL.md, .agent/TECH_DEBT_BACKLOG.md
       → proyección humana opcional / histórico; NO autoridad
```

> Cualquier discrepancia entre el contenido de `.agent/*` y el estado real (SDDK + Git + CI) se resuelve a favor del estado real.

## Empezar siempre aquí

1. Recuperar estado mediante SDDK: `sddk status --cycle <active-cycle>` y `sddk project resolve --root . --scope .`.
2. docs/v2/05-roadmap/ROADMAP.md — única secuencia de trabajo y exit criteria actuales.
3. docs/v2/07-uat/CERTIFICATION_PROTOCOL.md y PRODUCTION_READY_UAT_MATRIX.md — qué significa certificar.
4. docs/v2/01-product/STEP_REGISTRY_PLAN.md — cola histórica/constitución técnica del ecosistema; reconciliar su inventario con el estado SDDK antes de ejecutar cualquier WU.
5. docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md y ADR-0070..0093 aceptados y AGENTS.md — límites normativos que un roadmap no puede revocar.

## Proyección humana opcional (no autoridad)

- `.agent/SESSION_POINTER.md` — primera WU operativa, bloqueos, último commit de código conocido y primer comando. Consulta histórica, no autoridad.
- `.agent/WORK_JOURNAL.md` — diario append-only de decisiones y handoffs; no sustituye a Git ni a CI. Histórico, no autoridad.
- `.agent/TECH_DEBT_BACKLOG.md` — registro de deuda. Histórico, no autoridad.
- `.agent/TESTING-STATE.md` — topología/comandos conocidos. Histórico, no autoridad.

## Precedencia y resolución de conflictos

Contratos publicados + ADRs aceptados + especificaciones normativas > normas de seguridad/autorización de AGENTS.md e INITIATIVE_LPR_001 > roadmap activo (prioridades y gates) > matriz UAT vigente > puntero de sesión (estado) > receipts inmutables (evidencia del SHA histórico) > propuestas y planes anteriores. Si existen documentos normativos contradictorios, NO inventar un ganador: registrar el conflicto y requerir ADR. Un roadmap puede sustituir la SECUENCIA anterior, no los requisitos de seguridad.

El documento docs/v2/00-governance/DOCUMENT_AUTHORITY.md (2026-09-03) conserva el marco ADR-0064; esta actualización de 2026-09-21 fija la prioridad de producto conforme al cambio LPR de 2026-09-18 y al roadmap activo. No vuelve a abrir M3/ML/EM/EVT ya cerrados.

## Archivado vs. vigente

docs/historico/INDEX.md contiene el inventario de documentos trasladados. Los paquetes originales docs/pipeline-kotlin-*/ son propuestas empaquetadas y procedencia histórica; NO constituyen una cola adicional. El viejo roadmap cronológico y el primer roadmap LPR siguen accesibles allí. NO modificar recibos de certificación publicados para alterar su significado: emitir un nuevo recibo o una fe de erratas con referencia al SHA original.

## Norma de navegación y porcentajes

Señalar siempre SHA, fecha y origen al presentar un estado. Distinción obligatoria: REGISTERED, IMPLEMENTED_UNCERTIFIED, CERTIFIED_AT_SHA, RELEASED_ARTIFACT, VERIFIED_ON_CURRENT_HEAD y BLOCKED. Un recuento antiguo de 16, 19 o 20 Steps no reemplaza un inventario nuevo. Sin denominador comprobado no se publica % global.
