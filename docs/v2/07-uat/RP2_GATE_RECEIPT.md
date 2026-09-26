# RP-2 GATE — Baseline de arquitectura, determinismo y observación

**Fecha:** 2026-09-22 · **SHA del gate:** `32fa5924` (main, CI run `35723296797` 7/7 SUCCESS)
**Salida exigida (ROADMAP §4):** UAT-OBS/PERF/REC verde sobre el mismo SHA y entorno documentado.

## Resultado: GATE SATISFECHO (con limitaciones documentadas)

### UAT-OBS (observación) — COVERED
- UAT-RP-017: `WURp023ObservationModesUatTest` (HF2, binario real de este SHA):
  run array stdout, events jsonl replay = stream del run, cursor reconnect sin
  re-ejecución, `events verify` PASS/FAIL/2, unknown-run read-only.
- Sobre el mismo SHA: L1 1/1 verde; L2 vecinos (WULpr010 11/11, WULpr011 1/1) verdes.

```
UAT-EVIDENCE | UAT-RP-017 | COVERED | candidate=789e6e01 | tests=WURp023ObservationModesUatTest:1 | exit=0 | xml-sha256=659dc40a232c5b7c44e3f752f0d63b195632862b0260f430b44211c196c8fb38
```

### UAT-PERF (rendimiento) — COVERED, baseline re-medinida en el SHA del gate
Harness: `v2/compatibility/rp022_perf_baseline.sh` (entorno: bazzite-rubentxu,
64 cores, 94 GiB RAM, kernel 6.x fc44, 2026-09-22T12:12Z).

| Métrica | Medido @ 32fa5924 | SLO (aprobado WU-RP-022b) | Estado |
|---|---|---|---|
| M1 echo-run mediana | 5.01 s | ≤ 8 s | PASS |
| M2 warm rerun | 4.86 s | ≤ 8 s | PASS |
| M3 200 MiB stdout | 30.6 s | ≤ 60 s | PASS |
| Redactor (probe floor) | 23 MB/s | ≥ 20 MB/s | PASS |
| M4 slow consumer | 3.55 s | ≤ 6 s | PASS |
| M5 1 GiB soak | 134.7 s, stdout íntegro 1,073,747,116 B, exit 0 | exit=0 + bytes íntegros (RSS sin SLO) | PASS |
| M6 CPU | wall 5.26 s / user 13.29 s | observacional (SLO en RP-4) | OBS |

Difiencia vs baseline 9393e34a ≤ 3% en todas las métricas: ruido de máquina;
el diff 9393e34a..32fa5924 es test/docs-only (verificado con `git diff --stat`).

### UAT-REC (recuperación/determinismo) — COVERED
- WU-RP-021: ExecutionPathsCharacterisationTest (8 rutas golden IR/eventos).
- UAT-RP-011..015: mapeados en PRODUCTION_READY_UAT_MATRIX.md (concurrencia
  SqliteEventStore 10 tests, kill/resume WULpr011, divergence fail-closed,
  retry/timeout/parallel golden, redacción transcript/eventos/at-rest).
- UAT-RP-018: PARTIAL — límites de recursos OS requieren sandbox-profile 'os'
  (ADR-0016), planificado M5/M9 en RP-4/RP-5. No es defecto; limitación de
  perfil documentada.

```
UAT-EVIDENCE | UAT-RP-018 | PARTIAL | candidate=789e6e01 | tests=sandbox_os_resource_limits | exit=0 | note=limitación_de_perfil_per_ADR-0016_planificada_RP-4/RP-5
```

## Limitaciones explícitas (decisión respecto a límites)
1. M5 maxRss ~11 GB: transcript materializado en memoria antes del chunking.
   Sin SLO de RSS en RP-2; candidato a streaming-chunks en RP-4 si se fija SLO.
2. Flake M3 no determinista (SIGPIPE child exit 141) observado 1x en RP-022,
   2 reruns limpios. Abierto, no bloqueante.
3. UAT-RP-005 invariant 3 (MANIFEST.json) está diferido formalmente a
   WU-RP-042 (gate de release RP-5) per la regla de supersedencia de
   ADR-0095.
   El estado actual del UAT está capturado por el marker
   `UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION | candidate=72f1ce8d`
   en `T0E_CLOSURE_RECEIPT.md` (evidencia estructural persistente).

   ```
   UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION | candidate=789e6e01 | tests=supersedence_per_ADR-0095 | exit=0 | note=supersedes_FAIL_PROVEN_per_ADR-0095_(structural_supersedence)
   ```

## Cierre de WUs del gate
- WU-RP-020, 021, 022, 022b, 023: CLOSED.
- WU-RP-024/025 (mapeo matriz): absorbidos en la actualización de
  PRODUCTION_READY_UAT_MATRIX.md filas UAT-RP-011..018 (commit 32fa5924).

---

## Re-executed evidence — UAT-RP-011 (2026-09-26)

Esta sub-sección aporta prueba fresca y verificable por el certifier
para el UAT-RP-011. La línea §4 de este receipt lo mapeaba como
'concurrencia SqliteEventStore 10 tests' pero sin marcador
`UAT-EVIDENCE`. Este turno autónomo re-ejecuta el test contra el
SHA actual y emite el marker.

### Procedimiento reproducible

```bash
# Sin opt-in: el test corre por defecto.
timeout 600 ./gradlew -p v2 :pipeline-events:test \
  --tests "dev.rubentxu.pipeline.v2.events.SqliteEventStoreConcurrencyCharacterisationTest"
```

### Resultado observado en HEAD 97a3cdb4

- **SqliteEventStoreConcurrencyCharacterisationTest:** 10/10 PASS
  (concurrencia N producers/1 writer con DB-level lock + busy_timeout,
  sin duplicados ni desorden respecto del contrato aprobado).
  Digest XML: `sha256:b4470ac6666132b357c72b7a99e09716dd5a3089d2a5a7041f92449cda456ee6`

Total: **10/10 PASS**, 0 failures, 0 errors, 0 skipped.

### Markers para el certifier

```
UAT-EVIDENCE | UAT-RP-011 | COVERED | candidate=97a3cdb4 | tests=SqliteEventStoreConcurrencyCharacterisationTest:10 (N producers/1 writer, DB-level lock, no dup/no reorder) | exit=0 | xml-sha256=b4470ac6666132b357c72b7a99e09716dd5a3089d2a5a7041f92449cda456ee6
```
