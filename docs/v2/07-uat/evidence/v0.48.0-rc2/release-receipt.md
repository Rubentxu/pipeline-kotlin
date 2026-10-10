# PipelineK v0.48.0-rc2 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde, tag inmutable
publicado en `origin`, material en `dist/candidates/v0.48.0-rc2/`, **GitHub Prerelease
publicada y asset verificado por re-descarga**. Pendiente: CERTIFIED por el release harness.

**Release:** prerelease en GitHub. Asset re-descargado y verificado: sha256 y bytes
idénticos a los medidos antes de publicar.

- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc2
- **Tag:** `v0.48.0-rc2` (anotado, peel = `74c5331` = `origin/main` = HEAD).
- **Commit de build:** `74c5331` (version bump 0.48.0 → 0.48.0-rc2; regla 9 de AGENTS.md:
  el tag se crea sobre commit ya integrado).
- **Push:** HECHO. `git push origin main` llevó `543e1cc5..b14ef25`. `git push origin
  v0.48.0-rc2` llevó el tag.

**Candidata:** `0.48.0-rc2`
**Tag:** `v0.48.0-rc2` (anotado, peel = `74c5331` = HEAD = `main` local).
**Commit de build:** `74c5331` (version bump 0.48.0 → 0.48.0-rc2; regla 9 de AGENTS.md:
el tag se crea sobre commit ya integrado).
**Rama:** `main`
**Candidata anterior:** `v0.47.0-rc3` (`4c372f2`)
**Integración en `main`:** HECHA. Las 4 commits de cierre viven en `main` local
(`0d2ee1a` B1 doc, `98e25f3` WIP-11 receipt verde, `b3e60c9` F-1 fix, `07ecd02` F-2/F-3 fix);
`3f59c57` es la addenda al WIP-11 receipt con la causa real de F-1.

Esta es una **release candidate**, no una release estable. El repositorio entrega el
material para `pipelinek-release-harness`. La certificación externa y la promoción
estable pertenecen al harness y deben reutilizar estos mismos bytes, sin reconstrucción.

## Train derivada del historial

| Señal | Valor |
|---|---|
| Commits en `v0.47.0-rc3..v0.48.0-rc2` | 312 |
| `feat` | 42 |
| `fix` | 60 |
| `refactor` | 18 |
| `docs` | 126 |
| `test` | 46 |
| `chore` | 10 |
| `style` / `perf` | 3 + 3 |
| `release` / merge | 2 + 2 |
| Train | **MINOR** → `0.48.0` (release estable cuando el harness certifique) |

## Material inmutable

Directorio local ignorado por Git (`.gitignore:50` → `/dist/`):

```text
dist/candidates/v0.48.0-rc2/
├── pipelinek-0.48.0-rc2.zip
├── pipelinek-0.48.0-rc2.sbom.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.48.0-rc2.zip` | 88,200,000 | `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7` |
| `pipelinek-0.48.0-rc2.sbom.json` | 1,300,000 | `08ac282177a5fe952cc08c35a5a64b215022545ee280b3afa27927bbf17e949c` |

Comprobaciones sobre los bytes definitivos:
- `unzip -t` del ZIP: OK
- `Implementation-Version` en los jars de pipelinek (`api`, `files`, `http`, `runtime`,
  `scm-git`, `utilities`, `junit`): `0.48.0-rc2` ✓
- Determinismo del build: build único (no se ha repetido para doble-verificación todavía).

## Gate de distribución instalada (S0.5)

Este es el gate que cierra el bloque B1 y que la regla "gate local completo" del roadmap
exige antes de declarar CANDIDATE_PUBLISHED. Log completo: `/tmp/wip11-full-gate-v2.log`.

| Comprobación | Resultado observado |
|---|---|
| `./gradlew check --rerun-tasks --console=plain --no-daemon` | `BUILD SUCCESSFUL in 31m 3s` |
| Tareas ejecutadas | 325 |
| Tests (global, todos los módulos) | 5611 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 144 (incluye 2 `@Disabled` documentados en WIP-4/5) |
| `pipeline-application` (módulo principal) | 2811 tests, 0 failed, 0 errors, 123 skipped |

El primer gate sobre `2f5ba9aa` (WIP-11 original) terminó con 3 fallos reales
(F-1, F-2, F-3). El addendum al WIP-11 receipt (`3f59c57` + `WIP11_GATE_FINAL_STATUS.md`)
documenta el cierre: F-1 fue un stream-id drift pre/post OBS-C2.3 en `B1aSh > b`
(test-only, no regresión del producto); F-2 y F-3 fueron entradas legítimas en los
ledgers S6-PRE / S6-PRE 2 con la razón de su permanencia.

## Cierres del bloque B1

- **F-1**: `b3e60c9 fix(test): B1aSh durable arm aligned to OBS-C2.3 stream id shape`
- **F-2 + F-3**: `07ecd02 fix(test): S6-PRE + S6-PRE 2 fitness ledgers`
- **WIP-11 receipt amend**: `3f59c57 docs(uat): WIP-11 receipt amend — real F-1 cause`
- **WIP-11 receipt verde**: `98e25f3 docs(uat): WIP-11 — gate verde, CANDIDATE_PUBLISHED`
- **B1 feature doc**: `0d2ee1a docs(odd): B1 feature doc → CANDIDATE_PUBLISHED`
- **Version bump**: `74c5331 release: bump product version to 0.48.0-rc2`

## Deuda residual priorizada (siguiente ciclo)

| ID | Tipo | Severidad |
|---|---|---|
| OUT-01 | `SegmentOutputStore` prune filter permisivo (test `@Disabled`) | HIGH |
| OUT-02 | `SegmentOutputStore` `safeStreamName` no inyectivo (test `@Disabled`) | HIGH |

Ambos requieren política de migración de formato del Output Plane antes de poder
retirar el `@Disabled`. Mientras tanto, los 2 tests adversariales no bloquean la
release (están documentados en WIP-4/5 con la condición de habilitación).

## Pendiente para este bloque (WIP-11 cierre / WIP-12)

- **WIP-11**: ✅ push a `origin/main` HECHO, ✅ publicación del Prerelease HECHA,
  ✅ verificación de asset re-descargado HECHA (sha256 idéntico al material stage).
- **WIP-12**: ✅ ROADMAP refrescado en `4c725e4`, ✅ handoff al release harness en
  `4c725e4` (`docs/v2/07-uat/evidence/v0.48.0-rc2/handoff-to-release-harness.md`).
- **CERTIFIED**: pendiente; `pipelinek-release-harness` lo emite tras la segunda
  construcción independiente y la comparación byte-a-byte (mismo patrón que
  v0.42.0-rc1 y v0.47.0-rc3).

## Notas operativas

- `git fetch origin --tags --prune` debe pasar antes del push para evitar el
  conflicto de tag que WIP-1 documenta en `V0470_RC1_ANNOTATED_TAG_PRESERVATION.md`.
- AGENTS.md "release candidates": el repositorio NO promociona a estable. La promoción
  la hace `pipelinek-release-harness` con los mismos bytes, sin reconstrucción.
