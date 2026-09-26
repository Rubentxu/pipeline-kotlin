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

---

## Re-executed evidence — UAT-RP-017 (2026-09-26)

Esta sub-sección aporta prueba fresca y verificable por el certifier
para el UAT-RP-017. El receipt original (WU-RP-023) ya documentaba
1/1 PASS en L1, pero sin marcador `UAT-EVIDENCE`. El certifier freeform
parser lo clasificaba como REFERENCED (multi-UAT en la línea del
matrix narrative). Este turno autónomo re-ejecuta el test contra el
SHA actual y emite el marker.

### Procedimiento reproducible

```bash
# Sin opt-in: el test corre por defecto.
timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests "dev.rubentxu.pipeline.v2.application.cli.WURp023ObservationModesUatTest"
```

### Resultado observado en HEAD 92f7c4a7

- **WURp023ObservationModesUatTest:** 1/1 PASS (5 invariantes E2E HF2
  sobre binario real installDist: run stdout JSON, `events --db`
  relectura, cursor reconnect, `events verify` contract, runId
  desconocido), 8.054s.
  Digest XML: `sha256:659dc40a232c5b7c44e3f752f0d63b195632862b0260f430b44211c196c8fb38`

Total: **1/1 PASS**, 8.054s, 0 failures, 0 errors, 0 skipped.

### Markers para el certifier

```
UAT-EVIDENCE | UAT-RP-017 | COVERED | candidate=92f7c4a7 | tests=WURp023ObservationModesUatTest:1 (5 E2E HF2 invariantes: run-JSON, events-replay, cursor-reconnect, events-verify-contract, unknown-runId) | exit=0 | xml-sha256=659dc40a232c5b7c44e3f752f0d63b195632862b0260f430b44211c196c8fb38
```

### Notas

- **Sin opt-in**: el test corre por defecto (no usa `@EnabledIfEnvironmentVariable`).
- **Sin código de producción tocado**: este bloque es solo evidencia.
  El test ya existía desde WU-RP-023.
- **Determinismo**: las assertions son bit-exact JSON shape + stderr cursor.
  El XML digest cambia por timestamp; las assertions internas son
  deterministas.
- **Certifier impact esperado**: UAT-RP-017 debe moverse de REFERENCED
  a COVERED.
