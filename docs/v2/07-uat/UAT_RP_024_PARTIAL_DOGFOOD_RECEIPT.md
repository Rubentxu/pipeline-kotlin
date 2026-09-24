# UAT-RP-024 — Dogfooding (evidencia parcial 1-repo) — RECEIPT

**Fecha:** 2026-09-24T16:03Z · **SHA binario:** rc3 (v0.39.1-rc3, tag → 2a2f2eba) · **Estado:** PARCIAL (1 de ≥2 repos)

## Escenario ejecutado (mismo repo, src reales del motor)

Pipeline de dogfood real (`/tmp/rp024/ws/dogfood.pipeline.kts`) ejecutado con el
binario rc3 recién publicado sobre las fuentes de ESTE repositorio:

```
stage("inventory"):
  sh: find v2 -name '*.kt' -path '*/main/*' | wc -l > kotlin-files.txt   (453 archivos)
  sh: echo header > report-header.txt
  sh: mkdir -p dist && cat ... > dist/DOGFOOD-REPORT.txt
```

## Resultados observados

| Comprobación | Resultado | Evidencia |
|---|---|---|
| Ejecución fresca exit 0 | PASS | `RunFinished success`, duración ~5s |
| Artefacto real producido | PASS | `dist/DOGFOOD-REPORT.txt` con cabecera + `453` |
| Sandbox workspace por stage | PASS (comportamiento correcto) | intento previo con `../` falló con `shell exited with code 1` — el sandbox rechaza escapar del workspace (aislamiento funciona) |
| Replay mismo `--db`/`--control-root` | PASS | 2 replays: exit 0, 3 steps, outcome success; mismos controles sin re-ejecución destructiva |
| Smoke adicional ya cubierto | PASS | version/doctor/e2e dir+sh (recibo rc3) |

## Hallazgo colateral (shutdown race, NO bloqueante, 1/3)

El PRIMER intento de replay (17:49Z) colgó el JVM: thread dump mostró
`DestroyJavaVM` esperando con hilo no-daemon `sqlite-event-writer` vivo.
Reproducibilidad: 1 de 3 ejecuciones idénticas (las 2 siguientes: exit 0 en
10s y 5s). Caracterizado como **NOT_REPRODUCIBLE shutdown race** de la
familia de flakes de shutdown ya conocida (M3 SIGPIPE, QUARANTINED).
Orphan JVM (pid 3423809) terminado manualmente. No es defecto del
pipeline DSL ni del replay; candidato a seguimiento del leak de hilo
no-daemon en apagado (deuda nueva, prioridad baja).

## Clasificación UAT-RP-024

**PARCIAL**: evidencia de 1 repo (este mismo, con src reales del motor y
binario candidato). El criterio completo (≥2 repos de naturaleza distinta,
con actualización y fallo intencionado recuperable) permanece abierto para
el harness o para un segundo repo externo. La matriz NO se marca COVERED.
