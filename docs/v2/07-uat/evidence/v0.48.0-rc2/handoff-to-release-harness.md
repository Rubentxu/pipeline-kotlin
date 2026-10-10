# Handoff a `pipelinek-release-harness` — v0.48.0-rc2

**Fecha:** 2026-10-10
**Candidata:** v0.48.0-rc2
**Tag:** `v0.48.0-rc2` (anotado, peel = `74c5331e2c242658f6b4c9e83f896ce5f8a4fe98`)
**Estado B1:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace
el harness, no el repositorio de producto).

## Lo que el repositorio entrega

1. **Tag anotado inmutable** sobre el commit de build: `v0.48.0-rc2 → 74c5331`.
2. **Material inmutable** en `dist/candidates/v0.48.0-rc2/`:
   - `pipelinek-0.48.0-rc2.zip` (88.2 MB)
   - `pipelinek-0.48.0-rc2.sbom.json` (1.3 MB)
   - `SHA256SUMS`
3. **Recibo de la candidata** con la traza del gate y de los 312 commits entre
   `v0.47.0-rc3` y `v0.48.0-rc2`: `docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md`.
4. **Recibos por WIP** dentro de B1: `docs/v2/07-uat/WIP{1..11}_*.md` y el WIP-11 receipt
   enmendado (`WIP11_GATE_FINAL_STATUS.md`).
5. **Feature doc B1**: `odd/tasks/b1-v0.48.0-rc2.md` con las 12 WIPs marcadas.
6. **ROADMAP refrescado**: `docs/v2/05-roadmap/ROADMAP.md` con la fila `v0.48.0-rc2`
   en la sección "Re-auditoría de hechos 2026-10-09" (la fecha de auditoría del bloque se
   sustituye por la fecha del 2026-10-10 cuando el push confirme la candidata en `origin/main`).

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Push a `origin/main`** de las 5 commits de cierre:
  `b3e60c9`, `3f59c57`, `98e25f3`, `0d2ee1a`, `74c5331` y `417dc48` (release-receipt).
  Antes del push: `git fetch origin --tags --prune` para evitar el conflicto de tag que
  WIP-1 documenta en `V0470_RC1_ANNOTATED_TAG_PRESERVATION.md`.
- **Publicación del GitHub Prerelease** con `v0.48.0-rc2` como nombre, los assets zip +
  sbom + SHA256SUMS, y la nota de release. Verificación de que el asset re-descargado
  coincide con el SHA local (regla de v0.42.0-rc1 receipt: "Asset re-descargado y
  verificado: sha256 y bytes idénticos a los medidos antes de publicar").
- **Certificación (`CERTIFIED`)** y promoción a estable. Esto es el estado del roadmap
  §10.1 / §11.1: el repositorio entrega candidatas inmutables; `pipelinek-release-harness`
  decide cuándo promover, sin reconstrucción, reutilizando los mismos bytes.

## Lo que el harness debe saber del gate (S0.5)

- `./gradlew check --rerun-tasks --console=plain --no-daemon` sobre el commit del tag
  termina en `BUILD SUCCESSFUL in 31m 3s, 325 actionable tasks executed`.
- Global: 5611 tests, 0 failures, 0 errors, 144 skipped.
- `pipeline-application`: 2811 tests, 0 failed, 0 errors, 123 skipped.
- 144 skipped incluye:
  - 2 tests `@Disabled` documentados en WIP-4/5 (OUT-01 prune filter, OUT-02 safeStreamName),
    con la condición de habilitación en `SegmentOutputStoreTest.kt`.
  - Tests de plataforma excluidos explícitamente en este entorno (Linux-only assumptions).
- OBS-PC-208 pasa aislado (0.659s, 0 failures); bajo carga de 2811 tests es flake conocido
  y documentado en el KDoc del propio test.

## Determinismo del build

El distZip se construyó una vez para esta candidata. El patrón de v0.42.0-rc1 exige una
segunda construcción independiente y comparación de SHA-256 como prueba de
reproducibilidad byte-a-byte. **No se ha hecho la segunda construcción todavía**: queda
como tarea del harness antes de la promoción.

## Regresión potencial: WIP-9 dejó de fumar

WIP-9 (`WIP9_OBSERVABILITY_RECEIPT.md`) cerró la cobertura §1.4 sin tests nuevos: la
cobertura de Output/Event Plane / cursores / redaction ya existía en el árbol antes de
B1, y B1 se limitó a verificar que ninguno de los cambios de OUT/RUN/PATH/COV había
roto esa cobertura. El receipt lo documenta con la traza de los testigos ejercitados
en el gate v2.

## Cómo el harness verifica la candidata

```bash
# 1. Validar la inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.48.0-rc2^{commit}     # debe imprimir 74c5331e2c242658f6b4c9e83f896ce5f8a4fe98
git rev-parse origin/main              # debe imprimir lo mismo si el push ya pasó

# 2. Reconstruir localmente y comparar SHA-256.
cd v2 && ./gradlew :pipeline-application:distZip -q --console=plain --no-daemon
sha256sum pipeline-application/build/distributions/pipelinek-0.48.0-rc2.zip
# Debe coincidir con 0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7

# 3. Confirmar que el binario reporta 0.48.0-rc2.
unzip -p pipeline-application/build/distributions/pipelinek-0.48.0-rc2.zip \
  'pipelinek-0.48.0-rc2/lib/pipeline-application-0.48.0-rc2.jar' \
  | strings | grep -E 'Implementation-Version' | head -1
# Debe imprimir Implementation-Version: 0.48.0-rc2
```

## Hand-off

- **WorkItem del ciclo:** `83771962-d46a-46b1-b902-d08eec25fff2` (en `B1 / v0.48.0-rc2`)
- **Recibos vinculantes:** los 12 WIPs de B1 más el `release-receipt.md` y este handoff.
- **Deuda residual para el siguiente ciclo:** OUT-01 / OUT-02 (HIGH) — Output Plane
  migración de formato.
- **Próximo bloque del roadmap:** B2 (S6 plugin SDK v2 / plataforma extensible), ya cerrado
  en sustancia pero con dos residuos nombrados que el harness conoce.
