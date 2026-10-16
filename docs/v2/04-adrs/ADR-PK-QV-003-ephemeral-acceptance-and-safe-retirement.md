---
type: adr
id: ADR-PK-QV-003
title: Ephemeral cycle acceptance and evidence-gated retirement of legacy UAT
status: accepted
date: 2026-10-15
accepted: 2026-10-16
deciders: Rubentxu
related:
  - ADR-0071
  - ADR-0074
  - ADR-0099
  - docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md
amends:
  - docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md §4
  - docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md §10
---

# ADR-PK-QV-003 — UAT/E2E/AAT temporales y borrado seguro

## Conflicto a resolver antes de aceptarlo

El roadmap de migración vigente **prohíbe borrar UAT heredadas antes de migrarlas** y prescribe mantener temporalmente `@Disabled("migrated...")` tras equivalencia. El usuario desea retirar pruebas UAT/E2E/AAT al final de cada ciclo. No se puede aplicar borrado masivo sin alterar el protocolo vigente.

## Decisión propuesta

1. Distinguir `CycleProbe` efímero de `ProtectedContract` permanente y de `LegacyAcceptanceTest` histórico. Los `CycleProbe` se eliminan al acabar el ciclo, con evidencia y garantía promovida. **No cambian los tests normativos por arte de magia**.
2. Un `LegacyAcceptanceTest` es candidato a eliminación únicamente después de clasificar su función, demostrar sustituto del **mismo defecto** al nivel de fidelidad requerido y registrar veredicto de la autoridad correspondiente (incluido certifier externo cuando sea binario instalado).
3. Para los test heredados, se mantiene la regla histórica de `@Disabled("migrated...")` y no borrado **hasta decisión explícita de supersesión** por el responsable. Si se aprueba la transición posterior a `DELETE_READY`, borrar archivo y fixtures exclusivas con commit atómico y prueba de no regresión. No reescribir recibos históricos.
4. El agente registra `DeletionDecision` por test: `Keep`, `MigratePending`, `DeleteReady(proof)`, `Blocked(reason)`. Nunca `DeleteReady` por etiqueta de nombre, edad o duración.
5. Ejemplos y especificaciones `.pipeline.kts` de producto (ADR-0071) sobreviven al borrado de implementaciones UAT de sesión.
6. Las pruebas testkit/fitness/unitarias permanentes se conservan. Una release candidate no puede heredar `CERTIFIED` por desaparición de tests.

## Alternativas rechazadas

- `rm -rf **/Uat* **/*E2E*`: pierde garantías.
- Marcar como skipped y contar verde: falsa certificación.
- Copiar pruebas sin igualar el bug fingerprint: falsa equivalencia.
- Retener para siempre todo por miedo: atasca el producto.

## Criterio de equivalencia

`fingerprint = (contractId, scenarioId, inputDigest, failureKind, symptom, causalBoundary)`; proof declara `sourceSHA`, `CandidateId`, `artifactDigest`, `replacementTest`, `HF_observed`, `verdict`, `mutation` o justificación. Los campos no disponibles son `UNKNOWN`, nunca PASS.

## Compatibilidad

Este ADR debe ser aceptado junto a una enmienda **explícita** a `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4/§10 para permitir borrado posterior a equivalencia. Antes de eso, solo permite borrar pruebas temporales creadas por el ciclo.
