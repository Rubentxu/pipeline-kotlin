---
type: adr
id: ADR-PK-QV-002
title: Evidence-aware impact planning and minimum faithful execution
status: accepted
date: 2026-10-15
accepted: 2026-10-16
deciders: Rubentxu
related:
  - ADR-0072
  - docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md
  - docs/v2/07-uat/CERTIFICATION_PROTOCOL.md
---

# ADR-PK-QV-002 — Selección mínima sin falsos verdes

## Problema

`ImpactResolver` solo trata parte de los inputs del producto; `ContractCatalogue` se apoya en rutas concretas; `EvidenceReader` comprueba errores/fallos pero no una ejecución útil; fidelity enum del plan llega a HF4, mientras el contrato superior exige HF0..HF6. Un XML de test vacío/skipped no debe validar nada.

## Decisión propuesta

1. `ChangedInput` clasifica inputs ejecutables (sources, proto, services, resources, build/config/scripts) en función pura.
2. Cambios desconocidos que afectan producción **rechazan**; no obligan a registrar miles de archivos manualmente: mapear propietarios/patrones y fitness que descubre superficies nuevas.
3. Separar `ImpactPlan`, `ObservedExecution`, `EvidenceVerdict` y `CandidateCertification`: no son el mismo objeto.
4. Aceptar evidence solo con suite/invocación identificada, tests ejecutados, errores y fallos cero, skipped contabilizados, frontera efectivamente ejecutada y fuente/artefacto atados a SHA.
5. Estructura de fidelidad incluye HF0..HF6 (ADR-0072); el runner demuestra fronteras, no el nombre del test.
6. Ejecutar mínima suite fiel afectada durante desarrollo; completas de release cuando lo exija el contrato.

## Consecuencias

Tests puros deterministas para catálogo, selección y veredicto. I/O de Git/XML/Gradle en adapters. No desplazar al productor las comprobaciones de certifier externo. Nunca inventar un éxito por no encontrar XML o por XML viejo.

## Aceptación

Mutaciones `tests=0`, `skipped=tests`, servicio no catalogado y wrong commit son rechazadas. Se documenta equivalencia entre selector antiguo/nuevo para inputs soportados. ADR propuesta, requiere revisión.
