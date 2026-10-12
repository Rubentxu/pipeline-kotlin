# Protocolo de release, integración remota y evidencia

**Contrato operativo por milestone M0..M7, no procedimiento universal que sobreescribe AGENTS.md de los repos.** M0 valida qué repos tienen push/tag permissions y cuál es la estrategia de release vigente (jlink/JReleaser/Maven/plugin HPI/etc.).

## Entrada al ciclo

- [ ] `git status --porcelain`, `git rev-parse HEAD`, rama base, remote heads, tags y repos upstream correspondientes registrados.
- [ ] Dependencias de Maven repos reales en versión publicada; no promover con `mavenLocal` como fuente única.
- [ ] Identificar tests focales y UAT/AAT del hito; baseline de CPU/mem/red/disco y de comportamiento antes de cambio.
- [ ] SDDK preflight: autoridad/ciclo/state/current/journal según el repo, sin cambios en las reglas del propio kernel.
- [ ] ADRs afectados enlazados y aceptación definida antes de modificar contrato público.

## Durante

- [ ] Cambios pequeños y commits `feat:`, `fix:`, `refactor:`, `test:`, `docs:`; cada uno con tests focales.
- [ ] ADTs exhaustivos, interfaces segregadas, domain puro, event reader sin mutar escritor vivo.
- [ ] Migration/rollback junto a nuevo formato; tests nuevos primero demuestran red/defecto en baseline.
- [ ] Sin nueva carpeta de estado runtime/pipeline bajo repos o workspace de usuario; usar volúmenes/XDG definidos.

## Gate y publicación

1. Demostrar con evidencia concreta cada UAT/AAT requerido, incluyendo escenarios de fault injection y durabilidad. `NOT_MEASURED` bloquea.
2. Ejecutar suite completa de integración/release **en repos afectados**; publicar conteos, skipped, detekt/arquitectura/ABI. La suite focal nunca sustituye a la de release.
3. Reproducir consumer de PK con **SDK publicado** si cambia ABI; comprobar N/N-1 real en matriz.
4. Crear release manifest por repo: nombre de milestone, head base/final, SHA, dependencies, artifacts, checksum SHA256, SBOM, tests, UAT/AAT, compat, risks, semantic version, state.
5. Revisar release/tag existente para evitar colisión. Versionado siguiendo SemVer del producto (no hardcodear `0.48.0` ni otro número desde documentación antigua).
6. Merge/integración en rama remota autorizada por método vigente. `git fetch` y comprobar que el commit tag es ancestro o exactamente head integrado cuando sea requisito; no fiarse del output del push únicamente.
7. Publicar tag remoto y artifact/distribución, esperar confirmación síncrona de comando, resolver desde repositorio/publication endpoint, smoke con artifact instalado.
8. Guardar recibo y cambios en ROADMAP general del repo; SDDK cierre, `git status --short` limpio. No usar CI GitHub Actions donde AGENTS.md obliga CI local.
9. Si no existe permiso o red: `BLOCKED_REMOTE_PUBLICATION` con SHA local, comando fallido, tags ausentes; no declarar hito cerrado o release creado.

## Modelo de recibo JSON/Markdown

```json
{
  "milestone": "M3",
  "status": "PASS|FAIL|BLOCKED|NOT_MEASURED",
  "repositories": [{"name": "pipelinek-fabric", "base_sha": "...", "verified_sha": "...", "remote_ref": "...", "remote_sha": "...", "tag": "...", "artifact_uri": "...", "artifact_sha256": "..."}],
  "upstream_compatibility": [{"producer_version": "...", "consumer_version": "...", "scenario": "...", "verdict": "..."}],
  "uat": [{"id":"UAT-RP-006", "status":"PASS|FAIL|NOT_MEASURED", "evidence":"path-or-command-output", "measured":"..."}],
  "aat": [{"id":"AAT-15", "status":"PASS|FAIL|NOT_MEASURED", "evidence":"..."}],
  "fitness": {"latency_p95": "...", "rss_peak": "...", "output_integrity": "..."},
  "known_limitations": [],
  "rollback_steps": "..."
}
```

No autogenerar recibos PASS a partir de checklist; los valores los obtiene el harness real. Revisar `release manifest` para evitar secretos o nombres internos no exportables.
