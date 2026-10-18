# Mandato listo para el agente — PipelineK

Ejecuta el siguiente **hito de valor PENDIENTE**, nunca la siguiente fila de un documento histórico, sobre `Rubentxu/pipeline-kotlin` respetando la autoridad `docs/v2/05-roadmap/ROADMAP.md` y `QUALITY_VALUE_ADDENDUM.md`. Lee `AGENTS.md`, SDDK si existe, `docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md`, `docs/v2/03-specifications/DELIVERY_FIRST_IMPLEMENTATION_DELTA.md`, `docs/v2/07-uat/DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md`, `docs/v2/05-roadmap/DELIVERY_FIRST_AGENT_TEAMS.md`, ADR-PK-QV-001..004 y la ADR-PK-DF-001 **solo como propuesta**, sin asumirla aceptada. Preflight Git/HEAD/PR #140/#142/releases; marca `ALREADY_DONE` solo con código + prueba ejecutada + SHA y no repitas trabajo implementado. Trunk con PR integrada sin force/squash sobre historia publicada.

## Delivery-first-testing obligatorio

Descubre la skill real `delivery-first-testing` en `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`; usa su router `references/navigation.md`, `references/lens-registry.json` y **solo las lentes con `status=ready`**: `change-impact` para cambios, `ephemeral-uat` para comprobar funcionalidad, `regression-promotion` para decidir qué quedará, `failure-triage` ante rojo, `merge-assurance`/`integration-assurance`/`release-assurance` en sus momentos, `harness-economics` para tiempos de tests. Usa `scripts/affordances.py` solo para sugerir enlaces si está disponible; no certifica ni sustituye Gradle. Si no está instalada, informa y sigue esas reglas de modo manual, sin fingir ejecución.

## Forma del cambio

Entrega **una vertical productiva visible o medible**, no solo mayor cobertura o una colección de documentos. Preferir ADT cerrado + decider puro + intérprete con capacidades explícitas y tipos existentes (`DomainEvent`, `RunOutcome`, `OutputCursor`, `StepRegistry`, `CycleOutcome`). Una única autoridad por decisión; nunca `when(stepKey)` nuevo en el coordinador, fallback silencioso, duplicación de replay, E/S en funciones puras, strings mágicos de outcomes. Cambios pequeños de código con commits convencionales, tests quirúrgicos durante desarrollo. Medir time-to-value, no número de pruebas añadidas. Reconciliar contratos existentes, NO reescribir ADRs aceptados ni publicar SDK remoto sin autorización.

## Mínimos de aceptación

Por hito, como MÁXIMO dos **familias nuevas** de UAT/E2E/AAT efímeras en root de sesión externo. Reutiliza escenarios y leyes permanentes existentes. Falsación negativa discriminante; promover/replace solo ley menor y fiel; borrar TODOS los scripts y fixtures **nuevos** efímeros al finalizar ciclo, tras verificar oráculo, sustituto y evidencia; nunca borrar UAT heredadas sin equivalencia externa firmada y autorización. Evidencia `NOT_RUN`/`BLOCKED`/`STALE` no equivale a PASS. Reejecuta gates adecuados sobre commit limpio **DESPUÉS** del borrado temporal.

Si el entorno permite Agent Teams, delega como `DELIVERY_FIRST_AGENT_TEAMS.md`: especialistas en decider, adapter, QA, Gradle/SDK, cada uno con propiedad de ficheros y bases SHA; integrador es uno. No compilar con dos procesos Gradle simultáneos en el mismo checkout con lock.

## Cierre del hito (sin excusas ni ficción)

1. Demostrar comportamiento/UX o mejora de feedback realmente medido, y test adversarial que detecta defecto.
2. Registrar contratos, pruebas ejecutadas/skip/fail con comandos y SHA; eliminar archivos efímeros; gate final fresco.
3. PR + integración en `origin/main` tras verificaciones según política; nueva RC inmutable sobre SHA identificado, nunca reusar tag.
4. Producción/distribución instalable desde GitHub con asset-set exacto del contrato vigente, digest/candidato/versión correctos; SDK Maven solo `SDK_PUBLISHED` si resuelve desde remoto autorizado; promoción `CERTIFIED` únicamente con verdict del certifier.
5. Informe breve: valor que el consumidor utiliza, archivos/producto cambiados, tests y tiempo antes/después, temporales borrados, SHA/PR/tag/assets, hallazgos pendientes y siguiente WU del roadmap.

**Si hay bloqueo en release obligatoria:** `BLOCKED` con causa, no tag ficticio, no remover garantías, no repetir full suites en bucle. Puede avanzar en otra WU independiente sin falsear cierre. No ampliar alcance hasta resolver P0 de seguridad/integridad. No crear fuentes ZIP ni morralla en GitHub Release.
