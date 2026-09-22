# WU-RP-022 — Performance Baseline Receipt

**Fecha:** 2026-09-22
**SHA de evidencia:** `9393e34a245350c0fbbdef01ce34ec98cb88554b` (main)
**CI:** run `35720331038` — LPR-0 CI, 7/7 SUCCESS (7m5s)
**Host:** bazzite-rubentxu, Linux 7.2.4-ogc3.1.fc44.x86_64, 64 cores, 96 GB RAM

## Objetivo

Establecer la línea base de rendimiento medible del runtime durable (RP-2),
sobre la que el ROADMAP permite definir SLOs POSTERIORES a la medición.

## Hallazgo P1 — Redactor de transcript O(n²) (crítico, corregido)

- **Síntoma:** M3 (200 MiB stdout) colgaba; `durable-sh-transcript-pump`
  busy-spin 100% CPU indefinidamente; child bloqueado en `anon_pipe_write`;
  throughput ~0.1 MB/s (~230x por debajo de lo razonable).
- **Causa raíz:** `StreamingRedactor.RedactingInputStream` usaba
  `ArrayDeque<Byte>` (boxing) + copia completa del buffer pendiente + escaneo
  de costuras POR BYTE.
- **Fix:** reescrito con ring buffer primitivo + filtro literal de primer byte
  + matching incremental en `pipeline-credentials-api/StreamingRedactor.kt`.
  Contrato intacto (23 contract tests verdes; 3 actualizados por
  representación, no por semántica). Un bug real detectado y corregido durante
  la reescritura: `close()` debe vaciar TODOS los buffers.
- **Resultado:** throughput medido 23 MB/s (probe `Rp022ThroughputProbe`,
  floor fijado en 20 MB/s). 200 MiB ~9s en pump; ~31s end-to-end.

## Hallazgo P2 — SQLITE_TOOBIG mata al event writer (corregido)

- **Síntoma:** el soak M5 de 1 GiB fallaba; el transcript completo viajaba en
  UN solo `EchoOutputCaptured` y excedía `SQLITE_MAX_LENGTH` (1e9 por defecto).
  El `sqlite-event-writer` moría y `flush()` reportaba el diagnóstico
  engañoso "flush barrier timed out".
- **Fix 1:** `ShExecution.emitTranscriptChunked` — los transcripts se dividen
  a 64 MiB por evento. Chunks contiguos, ordenados y sin pérdida (regla del
  proyecto: no se pierde ni duplica output). 3/3 tests verdes en
  `TranscriptChunkingTest` (un OOM inicial del propio test corregido
  verificando límites sin materializar concatenación de 128M chars).
- **Fix 2:** `SqliteEventStore.flush` — al expirar la barrera, relanza el
  error real del writer cuando existe en vez del mensaje genérico de timeout.
- **Verificación:** soak 1 GiB end-to-end exit=0 con transcript íntegro de
  1.073.747.116 bytes; sin TOOBIG; sin fallo de writer.

## Flake observado (abierto, no bloqueante)

Una ejecución M3 falló con `shell exited with code 141` (SIGPIPE al child) de
forma NO determinista; dos reruns idénticos salieron exit=0. Candidato a
investigación futura (posible cierre de pipe durante el pump); no bloquea la
línea base.

## Línea base medida (SHA 9393e34a, harness reproducible
`v2/compatibility/rp022_perf_baseline.sh`)

| Métrica | Valor |
| --- | --- |
| M1 echo-run (startup+compile+run) | mediana 4.93 s (5 iter, min 4.89 / max 4.96) |
| M1 echo-run fresh-db | mediana 5.07 s |
| M2 warm-db-rerun (cache compile) | mediana 5.01 s |
| M3 200 MiB stdout end-to-end | 30.7 s |
| M4 slow consumer (20x200ms) | 3.5 s (≈ overhead bajo, domina el sleep) |
| M5 1 GiB soak end-to-end | 137.1 s, maxRss JVM ~10.0 GB |
| M6 CPU echo-run | wall 4.88 s, user 12.67 s, sys 0.72 s, maxRss 416 MB |
| Throughput redactor | 23 MB/s (floor probe 20 MB/s) |

## Observaciones

1. El cache de compile apenas acorta la rerun (~0.1-0.3 s): el coste dominante
   es startup de JVM + composición del runtime (~5 s). SLO de startup debería
   centrarse ahí.
2. M5 maxRss ~10 GB con transcript de 1 GiB en memoria: el transcript completo
   se materializa como String antes del chunking. Funciona (sin pérdida) pero
   el pico de memoria es un candidato natural de mejora futura (streaming de
   chunks) si los SLOs posteriores lo exigen.
3. SLOs: el ROADMAP indica definirlos DESPUÉS de medir. Esta receipt aporta
   los números; la definición de SLOs queda para el siguiente paso de RP-2.

## Verificación ejecutada

- L1: `TranscriptChunkingTest*` 3/3 verde; `Rp022ThroughputProbe` 23 MB/s.
- L2/L3: `:pipeline-events:test` verde; `Lpr011*`, `UatLocal008*`,
  `UatLocal009*` 59/59 verde; módulos `pipeline-credentials-api` y
  `pipeline-architecture-tests` verdes (ronda previa).
- CI: run `35720331038` 7/7 SUCCESS en el SHA de esta receipt.

## Commits

- `c3faadb2` — P1: reescritura RedactingInputStream + probe de throughput + harness.
- `9393e34a` — P2: chunking de transcripts + diagnóstico de flush + tests.

## Cierre (checklist del proyecto)

```text
Reference implementation consulted: jenkinsci/durable-task (tee-gated transcript),
jenkinsci/ansible/redactsj patterns no aplicables; SQLite limits documentados (SQLITE_MAX_LENGTH default 1e9)
Behaviour adopted: transcript chunked lossless; flush surfaces real writer failure
Intentional deviations: chunk 64 MiB por evento (Jenkins no chunking; exigencia de SQLite)
Security implications reviewed: chunking no altera redacción (chunks redactados previamente); sin datos nuevos en claro
Tests demonstrating the contract: TranscriptChunkingTest (3), StreamingRedactor contract tests (23), Rp022ThroughputProbe
```
