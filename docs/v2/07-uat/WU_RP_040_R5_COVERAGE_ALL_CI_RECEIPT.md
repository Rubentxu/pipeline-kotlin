# WU-RP-040 R5 — coverage-all: koverXmlReport invocado en CI

**Fecha:** 2026-09-24. **HEAD:** `21b89514` (main). **Estado:** COVERED (instrumentación), baselines pendientes de primeras ejecuciones CI.

## Gap cerrado

`WU_RP_053_SLICE_RECEIPT.md` §KNOWN_GAP declaraba: "la tarea `koverXmlReport` está
definida en `v2/build.gradle.kts:161` … pero NO se invoca en ningún workflow
(`grep -rn koverXmlReport .github/workflows/` → vacío)". Este cambio invoca el
agregado root (merge de todos los módulos Kotlin con tests) en un job dedicado.

## Cambio

- `.github/workflows/lpr0-ci.yml`: nuevo job `coverage-all` (name `coverage (kover-all)`).
  - Ejecuta `cd v2 && ./gradlew -q koverXmlReport`.
  - Sube `v2/build/reports/kover/report.xml` como artefacto `kover-all-xml` (`if: always()`).
  - Cache de Gradle idéntica al resto de jobs (clave `lpr0-kover`).
  - `timeout-minutes: 40`, `runs-on: self-hosted`.
- NO es required-status-check: la primera ejecución mide el runtime real; el
  presupuesto de 40 min se valida con esa medición antes de blindarlo.

## Evidencia local (SHA `21b89514`, 2026-09-24T16:38 CEST)

- Comando: `cd v2 && timeout 1500 ./gradlew koverXmlReport` → exit 0, 899s wall.
- Artefacto: `v2/build/reports/kover/report.xml`, 2 248 363 bytes, 1440 clases,
  timestamp de fichero sep 24 16:38 (fresco, regenerado en esta ejecución).
- Validación sintáctica del workflow: `yaml.safe_load` OK.

## Ejecución CI

- Run disparado por el push: `36012246997` (LPR-0 CI, 12 jobs incl. el nuevo).
- Resultado: PENDIENTE en el momento de emitir este recibo (runner self-hosted
  en cola, condición conocida de capacidad). El veredicto del job se registrará
  en el diario al completarse; si falla por presupuesto, el timeout se re-deriva
  según la regla 4 de AGENTS.md (basal medida × 1.3) y se re-lanza.

## Clasificación honesta

- KNOWN_GAP de instrumentación: **CERRADO en source** (el workflow ya invoca el reporte).
- **NOT_RUN en CI** hasta que el run `36012246997` (o re-dispatch) ejecute el job.
  No se declara PASS por existencia del job (zero-fabrication).
