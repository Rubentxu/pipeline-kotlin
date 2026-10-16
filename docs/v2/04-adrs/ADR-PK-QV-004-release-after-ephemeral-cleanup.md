---
type: adr
id: ADR-PK-QV-004
title: Per-milestone immutable candidate releases after ephemeral cleanup
status: accepted
date: 2026-10-15
accepted: 2026-10-16
deciders: Rubentxu
related:
  - ADR-0099
  - ADR-0105
  - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
---

# ADR-PK-QV-004 — Prerelease en `main` tras retirar pruebas temporales

## Contexto

La entrega continua pierde valor si un test efímero se convierte en dependencia que hay que mantener en cada integración. Sin embargo, una RC construida antes de la poda no coincide con el SHA que se publica después.

## Decisión propuesta

1. Cada BQ se cierra como una mejora vertical con prerelease **del protocolo vigente**. No inventar tags/versions ni re-publicar una RC inmutable.
2. La poda de UAT/E2E/AAT temporales y promoción de leyes permanentes se realiza ANTES de congelar SHA de candidata.
3. El SHA final se integra en `main` preservando historia según ADR-0099; el tag RC es alcanzable desde `main`, la distribución se construye una vez, misma identidad y digest para handoff/publicación.
4. Tests/recibos del ciclo registran el SHA del candidato y trazan, cuando corresponda, pre-merge SHA y su diff, sin afirmar que las pruebas de código temporal estén presentes en el release commit. El gate posterior a limpieza demuestra permanencia de garantías.
5. `CANDIDATE_PUBLISHED` ≠ `CERTIFIED` ≠ `STABLE_PROMOTED`. `BLOCKED_EXTERNAL` se conserva cuando falta veredicto/habilitación; prohibido falsear release completada.
6. No introducir GitHub Actions por defecto; emplear el camino local/harness externo ya acordado.

## Consecuencias

Se publica una RC por hito si el gate correspondiente está verde, con riesgo/contratos transparentes. Eliminar probes no borra su historia: conserva ID de contrato, digest de resultado, mutante detectado y prueba permanente equivalente. ADR propuesta.
