# WU-RP-023 — UAT-RP-017 Observation Receipt

**Fecha:** 2026-09-22
**Test:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/WURp023ObservationModesUatTest.kt` (HF2, binario real installDist)
**Base:** main @ `f74d9bcf` (RP-022 cerrado)

## Superficie observada caracterizada

1. **`run` stdout = UN array JSON** de envolventes (forma `JsonEventLog`),
   NO jsonl por línea. El resultado terminal va a stderr
   ("Pipeline finished with SUCCESS/FAILURE").
2. **`pipeline events --db <path> <runId>`**: relee historial persistido SIN
   ejecutar nada. Emite jsonl (una envolvente por línea); el kind de la
   envolvente sigue a `eventRefId` (los kinds anteriores en la línea son de
   subject/source refs). Multiconjunto de kinds idéntico al stream del run.
3. **Cursor reconnect**: la primera lectura imprime `evt-cursor-v1:<runId>:<seq>`
   en stderr; `--after-cursor` devuelve solo eventos posteriores (nada tras
   una lectura completa). Reconexión sin re-ejecución.
4. **`pipeline events verify --db --run --contract`**: verifica el historial
   contra contrato tipado SIN re-ejecutar. Exit 0 PASSED (expectativa
   correcta), 1 FAILED (expectativa errónea, con violaciones detalladas),
   2 error de decode tipado (contrato malformado: 'version', 'constraints',
   formas válidas: exactly/never/before/outcome).
5. **runId desconocido**: lectura vacía, exit 0 (observación read-only).

## Verificación

- L1: `WURp023ObservationModesUatTest*` 1/1 verde.
- UAT-RP-016 (PERF): evidencia formal en `WU_RP_022_RECEIPT.md` (baseline
  M1-M6 en SHA 9393e34a + SLOs aprobados en el anexo). Matriz actualizada.
- Matriz: filas UAT-RP-011..018 actualizadas en
  `PRODUCTION_READY_UAT_MATRIX.md` (017 COVERED; 018 PARTIAL diferido a
  RP-4/5 por ADR-0016 M5/M9 — limitación de perfil, no defecto).

## Cierre (checklist del proyecto)

```text
Reference implementation consulted: none applicable (CLI de observación propia; patrón cursor inspirado en Kafka/Follow de fluxo durable)
Behaviour adopted: caracterización de superficies de observación existentes; cero cambios de producción
Intentional deviations: run imprime array JSON (no jsonl) — superficie existente caracterizada, no modificada en este WU
Security implications reviewed: superficies de lectura no exponen secretos (RedactingEventSink envuelve el store; verificado en redacción RP-015)
Tests demonstrating the contract: WURp023ObservationModesUatTest (1 E2E HF2, 5 invariantes)
```
