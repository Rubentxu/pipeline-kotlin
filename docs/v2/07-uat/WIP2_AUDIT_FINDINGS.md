# WIP-2.6 — Corrección del recibo WIP-2 (Explore agent findings)

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `9cb1ffaf-b050-465b-82ad-083b0f9c840b` (ciclo `b1-v0-48-0-rc2`)
**Trigger:** auditoría del Explore agent `ab23071df84d2d28f` sobre los 8 docs untracked.

## 1. Reconocimiento de error

El recibo WIP-2 (`WIP2_RECONCILIATION_RECEIPT.md`) afirma:

> "Ninguna claim contradice el código actual."

Esto es **incorrecto**. El Explore agent identificó **12 contradicciones** entre los 8 docs y
el estado actual de `main`. Mi verificación fue superficial: leí solo las cabeceras de cada
doc y verifiqué la presencia de algunos símbolos sueltos. El agent, en cambio, contrastó
cada claim específica contra el código y el `git log`, encontrando:

- Afirmaciones que eran verdaderas al congelarse los docs (Oct 9 19Z) y dejaron de serlo tras
  el merge `a3b05601` (Oct 9 21:14Z).
- Una SHA incorrecta en una afirmación específica.
- Una ref al tag `v0.48.0-rc2` que no existe todavía.
- Una ref a `SDDK_CYCLE_PERSISTENCE_BLOCKER.md` que no existe en el repo.

La lección: para auditorías, no basta con verificar presencia/ausencia de símbolos puntuales;
hay que contrastar cada claim contra el código en su contexto. El Explore agent lo hizo y
encontró lo que yo pasé por alto.

## 2. Las 12 contradicciones detectadas

### AUD-4.1 — "`safeStreamName` no existe en main"
**Doc origen:** `MAIN_COMMIT_AUDIT.md`, `AUD08_RESOLUTION.md`, `BACKLOG_AUDIT_2026_10_09.md`.
**Realidad:** FALSE. `safeStreamName` está en 3 ficheros de main
(`SegmentOutputStore.kt:905`, `SegmentFrameIndex.kt`, `CrashedResidue.kt`).
Introducido vía merge `a3b05601`. Yo verifiqué su presencia en `SegmentOutputStore.kt`
y concluí "sin contradicción" — pasé por alto que el doc afirma exactamente lo contrario
y que el agent verificó los 3.

### AUD-4.2 — "`ConsolePrintingEventSink` no existe en main"
**Doc origen:** `BACKLOG_AUDIT_2026_10_09.md`.
**Realidad:** FALSE. Existe en `v2/pipeline-application/.../observation/`.

### AUD-4.3 — "paquete `pipeline-application/.../observation/` no existe en main"
**Doc origen:** `BACKLOG_AUDIT_2026_10_09.md`.
**Realidad:** FALSE. Hay 17 ficheros `.kt` en ese paquete.

### AUD-4.4 — "`MainObserveCli.kt` es un entrypoint nuevo"
**Doc origen:** `AUD08_RESOLUTION.md`.
**Realidad:** FALSE. Existe en main (29.4 KB).

### AUD-4.5 — "`OutputFrameIndex.kt` no existe"
**Doc origen:** `AUD08_RESOLUTION.md`.
**Realidad:** FALSE. Existe en `v2/pipeline-output-store/.../store/`.

### AUD-4.6 — Métricas "OBS 68 ahead / main 113 behind" como estado actual
**Doc origen:** `OBS_RECONCILIATION_INVENTORY.md`, `OBS_COMMIT_AUDIT.md`.
**Realidad:** STALE. Las métricas describen el estado pre-merge. El merge `a3b05601`
(2026-10-09 21:14Z) reconcilió `par/cli-observation @ 37df7961` con `main @ 57774c92`.
Estado actual: `git log origin/par/cli-observation ^main` retorna vacío.

### AUD-4.7 — "20 feat commits de OBS pendientes"
**Doc origen:** `OBS_COMMIT_AUDIT.md`.
**Realidad:** STALE. Los 20 están ahora en main.

### AUD-4.8 — "`integrate/main-obs @ c924af8c` con 1.515 ejecuciones verdes"
**Doc origen:** `ROADMAP_PIPELINEK_MAIN_OBS_2026-10-10.md` §0.
**Realidad:** SHA incorrecto. `integrate/main-obs` apunta a `543e1cc5`.
`c924af8c` es un commit `docs(uat)` "Run #13+#14+#15 modulos inferiores in-VM", no la punta
del branch.

### AUD-4.9 — "`v0.48.0-rc2` PRERELEASE desde main"
**Doc origen:** `ROADMAP_PIPELINEK_MAIN_OBS_2026-10-10.md` §M1.
**Realidad:** Tag no existe. `git rev-parse v0.48.0-rc2` falla. Los commits de WIP-1/WIP-2
referencian v0.48.0-rc2 como meta, no como tag.

### AUD-4.10 — "ADR-0105 OBS frame-index collision needs checking"
**Doc origen:** `ROADMAP`, `OBS_COMMIT_AUDIT.md`.
**Realidad:** RESUELTA. `ADR-0106-frame-index-ownership.md` está en main con `status: accepted`
(2026-10-09). El agent verificó ambos ADRs.

### AUD-4.11 — "AUD-08 = dos estrategias incompatibles; decisión de arquitectura"
**Doc origen:** `MAIN_COMMIT_AUDIT.md`.
**Realidad:** WRONG. El merge `a3b05601` registra la resolución real en su mensaje. El propio
`AUD08_RESOLUTION.md` corrige la hipótesis de `MAIN_COMMIT_AUDIT.md`.

### AUD-4.12 — "`MainConsoleCli`/`MainEventsCli` requieren decisión de producto"
**Doc origen:** `CLI_SPEC_ARBITRATION.md`.
**Realidad:** STALE. El mensaje del merge `a3b05601` documenta la resolución:
  - exit-code 1 → consola rehúsa (main)
  - exit-code 2 → error de uso (ambos lados)
  - `--typed` desde main (gana main)
  - `--range` y `--after-cursor` desde OBS (gana OBS)

## 3. Re-classificaciones de los 21 `feat`

El Explore agent corrigió mi agrupación de los 21 `feat`. Verificación adicional:

- `4d4075d5 feat(pipeline-domain): one SHA-256 utility, with a functional test that finds a
  real defect` → **NO es OBS**. Es trabajo de B1/C8/RP-034 (utility domain). Yo lo incluí en
  "todos son OBS" — pasé por alto el módulo `pipeline-domain`.
- 9 / 21 feat cambian superficie pública:
  - `a02ca7cb feat(observability): vista, formato y consola en vivo en la CLI` (CLI `--view`/`--format`)
  - `7835ea66 feat(output): transcript out-processes`
  - `45a2771c feat(output): durable index` (formato durable `OutputFrame`)
  - `f4298281 feat(output): stdout/stderr separate streams` (formato durable split)
  - `270225a2 feat(observation): records, channel as live param`
  - `2f619720 feat(observation): JSON Lines machine format`
  - `97d677e6 feat(observation): per-format contract`
  - `04177403 feat(cli): --channel reaches the query`
  - `7c0cc89b feat(cli): one public verb over both durable authorities` (nuevo verbo CLI)
  - `9fdf932e feat(observation): --limit is a budget` (CLI flag con presupuesto)
  - `48b925b4 feat(observation): a tail that costs what it reads` (CLI flag `--tail-bytes`)
  - `6254d6c8 feat(observation): one outcome, three producers` (`ObservedOutcome` ADT unificado)
  - `4d4075d5 feat(pipeline-domain): SHA-256 utility` (nueva utilidad pública en `pipeline-domain`)

Yo dije "Ninguno abre superficie pública destructiva" — esto es **incorrecto**. Sí abren
surface (clásicas, formatos durables, ADTs, flags CLI), pero AGREDEN de forma aditiva, no
destructiva. Mi recibo WIP-2 no distingue "abre surface" de "abre surface destructiva".

## 4. Items referenciados pero ausentes

| Referencia | Estado |
|---|---|
| Tag `v0.48.0-rc2` | MISSING. Lo creará WIP-11. |
| `SDDK_CYCLE_PERSISTENCE_BLOCKER.md` | MISSING (referenciado en `OBS_INTEGRATION_ENTRYPOINTS.md:65`). No existe en el repo. Posiblemente esté en `software-development-decision-kernel` repo (externo). Sin acción posible desde aquí. |
| `d1869963 fix(b1d)` BodyExecutionEngine | PRESENTE. El agent no encontró cambios OBS-side en esa superficie. La "pregunta abierta" de `MAIN_COMMIT_AUDIT.md` se cierra sin trabajo adicional. |
| `ObservPc2IngestAgent` | PROTOTIPO, no en lista de feat. Fuera de alcance para B1. |

## 5. Cambios a aplicar al repo

1. **Añadir cabecera a los 8 docs OBS 2026-10-09** indicando que están congelados en
   `57774c92` y superseded por el merge `a3b05601`. Mantener como histórico.
2. **Mantener el tag `v0.48.0-rc2`** como meta de WIP-11 (release), no como estado actual.
3. **Marcar `d1869963` BodyExecutionEngine como cerrado** sin acción.
4. **No reescribir los 8 docs**: ya están preservados como histórico.

## 6. Cierre de WIP-2.6

| Item | Estado |
|---|---|
| Reconocer error en mi recibo WIP-2 | ✓ |
| Listar las 12 contradicciones con ref al código actual | ✓ |
| Re-classificar los 21 feat commits (NO todos son OBS; 13 surface-additivos) | ✓ |
| Listar los items referenciados ausentes (tag rc2, SDDK blocker doc) | ✓ |

## 7. Próximo paso

WIP-3: §1.2 Re-correr gate sobre HEAD con recursos reservados.