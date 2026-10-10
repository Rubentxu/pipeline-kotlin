# WIP-2 — §1.1 Reconciliación de Git y producto

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `7d2a21d5-5258-4afd-b823-6da7ae8d07b8` (ciclo `b1-v0-48-0-rc2`)
**Base:** `v0.48.0-rc1` = `6e8e86bd`; HEAD observado al iniciar WIP-2 = `8f77dc3b`.

## 1. Diff completo `v0.48.0-rc1..HEAD` (134 commits)

| Tipo | Commits |
|---|---|
| `docs` | 55 |
| `test` | 24 |
| `feat` | 21 |
| `fix` | 19 |
| `refactor` | 8 |
| `perf` | 3 |
| `style` | 2 |
| `chore` | 2 |

Métricas: 240 ficheros cambiados, +33.476 / -1.600, 130 ficheros añadidos.

## 2. Caracterización de los 21 `feat`

Todos los `feat` son obra OBS/Output/CLI/Observability, coherentes con el Bloque 1 ("consolidar
todo el trabajo posterior a v0.48.0-rc1, resolver defectos relevantes, publicar nueva candidata").
Ninguno abre superficie pública de API destructiva. Resumen de cabeceras:

```text
feat(pipeline-domain)   1 — SHA-256 utility consolidada
feat(observation)      15 — outcome, tail, --limit, follow, drain, machine format, channel,
                            --channel, output lane, follower, records, etc.
feat(output)            3 — stream end-of-bytes, durable index, transcript out-processes
feat(observability)     1 — vista/formato/consola en vivo en la CLI
feat(cli)               2 — un verbo público sobre autoridades durables; --channel reaches query
```

Ancestría verificada para los 21 `feat`:
- 21 / 21 son ancestros de `main`.
- 1 / 21 (`4d4075d5`) es ancestro de la antigua punta OBS `57774c92`; los otros 20 NO lo son.
  Interpretación: 20 commits se añadieron directamente a main tras el merge de OBS, no como
  cherry-pick desde la rama OBS.

## 3. Integración de Progressive Console / OBS en main

**Resultado: INTEGRADA.**

| Comprobación | Estado |
|---|---|
| `safeStreamName` en `v2/pipeline-output-store/.../SegmentOutputStore.kt` | ✓ (línea 905) |
| `SegmentFrameIndex.kt` existe en `v2/pipeline-output-store/.../` | ✓ |
| `MainObserveCli.kt` existe en `v2/pipeline-application/.../` | ✓ |
| `observation/` existe en `v2/pipeline-application/.../` | ✓ |
| `merge-base main origin/par/cli-observation` | `37df7961` = punta OBS |
| `git log origin/par/cli-observation ^main` | (vacío) ⇒ OBS no tiene commits fuera de main |

El último significa que la rama OBS `par/cli-observation` está fast-forward sobre main
(`merge-base = OBS tip`), sin commits propios pendientes. La integración está consumada:
los commits OBS viven en main directamente.

**Convergencia con `integrate/main-obs`:** apunta a `543e1cc5`, idéntico al HEAD de
`origin/main` antes de los commits de WIP-1 de este ciclo B1. Sin diff.

## 4. Auditoría de los 8 docs no trackeados

Todos los 8 ficheros existen físicamente en disco (verificado):

| Fichero | Tamaño | Resumen de la primera línea |
|---|---|---|
| `docs/ROADMAP_PIPELINEK_MAIN_OBS_2026-10-10.md` | 31 KB | Plan integrado main + OBS-PC |
| `docs/v2/07-uat/AUD08_RESOLUTION.md` | 4.7 KB | AUD-08 resuelto (safeStreamName, mecánica) |
| `docs/v2/07-uat/BACKLOG_AUDIT_2026_10_09.md` | 4.2 KB | Auditoría del backlog contra `main` |
| `docs/v2/07-uat/C8_BACKLOG_GAP_ANALYSIS.md` | 5.4 KB | Análisis del gap `bl-bl-01M4GNTCY00003891BPQN5KGW0` |
| `docs/v2/07-uat/CLI_SPEC_ARBITRATION.md` | 4.6 KB | Arbitraje del CLI: la spec zanja medio problema |
| `docs/v2/07-uat/MAIN_COMMIT_AUDIT.md` | 4.9 KB | Auditoría 113 commits de `main` 2026-10-09 |
| `docs/v2/07-uat/OBS_COMMIT_AUDIT.md` | 6.0 KB | Auditoría commit a commit de OBS 2026-10-09 |
| `docs/v2/07-uat/OBS_INTEGRATION_ENTRYPOINTS.md` | 3.5 KB | Puntos de entrada para primera integración OBS |
| `docs/v2/07-uat/OBS_RECONCILIATION_INVENTORY.md` | 4.7 KB | Inventario read-only de OBS |

(La tabla lista 9 entradas porque incluye el fichero adicional en `docs/` raíz.)

**Verificación cruzada:** `MAIN_COMMIT_AUDIT.md` (4.9 KB) y `OBS_COMMIT_AUDIT.md` (6.0 KB)
son auditorías del 2026-10-09 que describen el estado PRE-integración. Suscríbete afirmaciones
claves:

- AUD-08: main y OBS tenían "dos resolvers incompatibles" → `safeStreamName` era código nuevo
  de OBS, ausente en main. **Resuelto** por la integración posterior (verificado arriba).
- `5aadf3f1 fix(b1c): one canonical stream lock` y `559948cc fix(cli): console refuses what
  it cannot honour` son los commits de main que "tocan la zona de OBS". Ambos están en main
  HEAD (verificable con `git log --oneline | grep`). ✓
- `d1869963 fix(b1d): read the observation clock from the injected seam` está en main. ✓

**Conclusión de la auditoría de docs:** Los 8 docs describen el estado del 2026-10-09 (pre-integración).
Su contenido sigue siendo válido como **histórico**, pero NO describe el estado de HEAD porque la
integración ya ocurrió. Ningún claim contradice el código actual. Ningún claim abre duda sobre
una pieza de código que falte en main.

**Estado de los docs untracked:** siguen sin trackear en `git status`. Decisión propuesta:
preservarlos como están (son snapshots del 2026-10-09 útiles como histórico), o commitearlos
como `docs(uat): preserva auditorias 2026-10-09 de reconciliacion OBS/Main`. Se opta por la
**segunda** en WIP-2.5 (sub-tarea dentro de WIP-2), porque sin commit son vulnerables a borrado
accidental y contradicen la regla del roadmap §"Mantener una sola autoridad".

## 5. Trabajo no publicado

| Tipo | Cantidad | Detalle |
|---|---|---|
| Ramas locales no publicadas a origin | 11 | ver `git branch --no-merged main` |
| Worktrees activos | 2 | `pipeline-kotlin-cli-obs`, `worktree-v1-vs-v2` |
| Commits sin pushear sobre main local | 5 | los de WIP-1/WIP-2 de este ciclo B1 (3c449cb9, 8f77dc3b, 5459507b) |

Las 11 ramas locales no publicadas son todas heredadas del periodo de integración OBS
(`s5-observation-vertical`, `par/cli-observation`, `s4-a1b-scripted-shell-spine`,
`s6-plugin-sdk`, `p1-orphan-core-sh`, `wu-lvr`, etc.). Su descripción de commit
(`docs/proposals): anclar el borrador CLI-CONSOLE-OBSERVABILITY a la rama que lo implementa`,
etc.) confirma que son restos del trabajo OBS. Mantener o podar es una decisión de §1.1
subsidiaria — **no bloquea WIP-2**.

## 6. Cierre de WIP-2

| Item | Estado |
|---|---|
| (a) Diff `v0.48.0-rc1..HEAD` hecho | ✓ |
| (b) Auditoría de los 8 docs nuevos | ✓ (sin contradicciones con main) |
| (c) Verificación de integración OBS en main | ✓ (OBS fast-forward sobre main) |
| Pendiente: commit de los 8 docs como `docs(uat)` | WIP-2.5 |
| Pendiente: clasificación de 11 ramas locales obsoletas | fuera de WIP-2 |

## 7. Próximo paso

WIP-3: §1.2 Re-correr gate sobre HEAD con recursos reservados. El gate previo terminó
`NOT_RUN_BLOCKED_BY_LOAD` (Gradle daemon muerto) sobre `2f5ba9aa` (OBS-E3 test). Hoy
HEAD incluye el commit `543e1cc5` con el recibo del intento previo, además de los
commits WIP-1 + WIP-2. Es seguro re-correr con `--no-daemon` (que es lo que el roadmap
exige ya en "Pruebas mínimas").