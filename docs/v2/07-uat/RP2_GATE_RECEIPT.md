# RP-2 GATE — Baseline de arquitectura, determinismo y observación

**Fecha:** 2026-09-22 · **SHA del gate:** `32fa5924` (main, CI run `35723296797` 7/7 SUCCESS)
**Salida exigida (ROADMAP §4):** UAT-OBS/PERF/REC verde sobre el mismo SHA y entorno documentado.

## Resultado: GATE SATISFECHO (con limitaciones documentadas)

### UAT-OBS (observación) — COVERED
- UAT-RP-017: `WURp023ObservationModesUatTest` (HF2, binario real de este SHA):
  run array stdout, events jsonl replay = stream del run, cursor reconnect sin
  re-ejecución, `events verify` PASS/FAIL/2, unknown-run read-only.
- Sobre el mismo SHA: L1 1/1 verde; L2 vecinos (WULpr010 11/11, WULpr011 1/1) verdes.

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

## Limitaciones explícitas (decisión respecto a límites)
1. M5 maxRss ~11 GB: transcript materializado en memoria antes del chunking.
   Sin SLO de RSS en RP-2; candidato a streaming-chunks en RP-4 si se fija SLO.
2. Flake M3 no determinista (SIGPIPE child exit 141) observado 1x en RP-022,
   2 reruns limpios. Abierto, no bloqueante.
3. UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a
   WU-RP-042 (gate de release RP-5).

## Cierre de WUs del gate
- WU-RP-020, 021, 022, 022b, 023: CLOSED.
- WU-RP-024/025 (mapeo matriz): absorbidos en la actualización de
  PRODUCTION_READY_UAT_MATRIX.md filas UAT-RP-011..018 (commit 32fa5924).
