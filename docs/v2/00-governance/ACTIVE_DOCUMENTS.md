# Documentos activos y autoridad de V2

**Actualizado:** 2026-09-21. **Estado:** índice de lectura ACTIVO.

## Empezar siempre aquí

1. docs/v2/05-roadmap/ROADMAP.md — única secuencia de trabajo y exit criteria actuales.
2. .agent/SESSION_POINTER.md — primera WU operativa, bloqueos, último commit de código conocido y primer comando.
3. .agent/WORK_JOURNAL.md — diario append-only de decisiones y handoffs; no sustituye a Git ni a CI.
4. .agent/TESTING-STATE.md — topología/comandos conocidos, reutilizar sólo cuando siguen siendo válidos para el SHA actual.
5. docs/v2/07-uat/CERTIFICATION_PROTOCOL.md y PRODUCTION_READY_UAT_MATRIX.md — qué significa certificar.
6. docs/v2/01-product/STEP_REGISTRY_PLAN.md — cola histórica/constitución técnica del ecosistema; reconciliar su inventario con el puntero antes de ejecutar cualquier WU.
7. docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md, ADR-0070..0093 aceptados y AGENTS.md — límites normativos que un roadmap no puede revocar.

## Precedencia y resolución de conflictos

Contratos publicados + ADRs aceptados + especificaciones normativas > normas de seguridad/autorización de AGENTS.md e INITIATIVE_LPR_001 > roadmap activo (prioridades y gates) > matriz UAT vigente > puntero de sesión (estado) > receipts inmutables (evidencia del SHA histórico) > propuestas y planes anteriores. Si existen documentos normativos contradictorios, NO inventar un ganador: registrar el conflicto y requerir ADR. Un roadmap puede sustituir la SECUENCIA anterior, no los requisitos de seguridad.

El documento docs/v2/00-governance/DOCUMENT_AUTHORITY.md (2026-09-03) conserva el marco ADR-0064; esta actualización de 2026-09-21 fija la prioridad de producto conforme al cambio LPR de 2026-09-18 y al roadmap activo. No vuelve a abrir M3/ML/EM/EVT ya cerrados.

## Distribución local-first en planificación

- `docs/v2/05-roadmap/MULTICHANNEL_RELEASE_DOGFOOD_PLAN.md` — propuesta D0..D5 integrada como WU-RP-060..065 de RP-6, solo ejecutable tras RP-5.
- `docs/v2/07-uat/UAT_RELEASE_CHANNELS.md` — criterios previstos de aceptación por canal; NOT_RUN hasta recibir evidencia real del SHA y del instalador.
- `docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md` — autoridad técnica de artefacto único; su sección multicanal es una ampliación propuesta, no un permiso de publicación.

## Archivado vs. vigente

docs/historico/INDEX.md contiene el inventario de documentos trasladados. Los paquetes originales docs/pipeline-kotlin-*/ son propuestas empaquetadas y procedencia histórica; NO constituyen una cola adicional. El viejo roadmap cronológico y el primer roadmap LPR siguen accesibles allí. NO modificar recibos de certificación publicados para alterar su significado: emitir un nuevo recibo o una fe de erratas con referencia al SHA original.

## Norma de navegación y porcentajes

Señalar siempre SHA, fecha y origen al presentar un estado. Distinción obligatoria: REGISTERED, IMPLEMENTED_UNCERTIFIED, CERTIFIED_AT_SHA, RELEASED_ARTIFACT, VERIFIED_ON_CURRENT_HEAD y BLOCKED. Un recuento antiguo de 16, 19 o 20 Steps no reemplaza un inventario nuevo. Sin denominador comprobado no se publica % global.
