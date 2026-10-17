# STATUS — Paquete archivado (proposal → executed)

**Estado:** HISTORICAL — proposal executed via `v0.62.0-rc6`.
**Fecha de archivo:** 2026-10-17
**Decisión de archivo:** autónoma (sesión del 2026-10-17, /loop 15m)
**Origen:** `docs/pipelinek-delivery-first-2026-10-16/` (untracked)

---

## Por qué se archiva y no se integra

Este paquete contiene la propuesta operativa que guió el cierre del scope **H0..H4** del BQ4
vertical. Esa propuesta **ya está ejecutada en `main`** y sellada en `v0.62.0-rc6`. Integrar
los archivos del paquete como overlay vivo reintroduciría:

- `ADR-PK-DF-001-release-asset-policy-transition.md` con `status: proposed` que **contradice** la
  ADR canónica en `docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md` con
  `status: accepted` (commit `4e984747`).
- Referencias a PRs y commits anteriores al cierre, que no deben reentrar en la cola activa.

El script `tools/integrate.py` del paquete **rechaza correctamente** la integración silenciosa
cuando la ADR destino diverge; esa divergencia es la evidencia de que la propuesta fue
aceptada y su contenido debe preservarse **como propuesta**, no como documento normativo.

## Estado de cada H0..H4 al momento del archivo

| Hito | TODO | Estado | Evidencia |
|---|---|---|---|
| **H0** | 1 (ExitVerdict), 2 (PromotionEvidence), 3 (UAT/AAT fixture), 4 (JsonOracleLedger) | DONE | commit `3020f2e9` |
| **H1** | 1+2 (ChangedInput ADT + sidecar), 3+4+5 (gradle DAG, lanes, métricas) | DONE | commit `f66abfe2`, 283/283 verde |
| **H2** | 1 (ADR-PK-DF-001) | DONE | commit `4e984747`, **Option A accepted** |
| **H2** | 2 (manifest-driven publisher) | DONE | commit `4da3d400`, 11/11 compliance test |
| **H2** | 3 (BOM 5/5 constraints) | DONE | commit `efd569fe` |
| **H2** | 4 (`SDK_PUBLISHED=BLOCKED_EXTERNAL`) | DONE | commit `53ab91c9` |
| **H2** | 5 (ephemeral UAT budget — 2 families) | DONE | commit `5e4cea31`, recibos en `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md` |
| **H3** | OBS-3/4 falsifiers narrowing | DONE | commits `0a01bc15`, `48467cc7`, `f4a55fa4`, `a12ca648`, `ebde1314` |
| **H4** | batches 1/2/3 (UAT MigratePending + sandbox retired) | DONE | commits `b3fc8f74`, `002ccc9c`, `cf255ba0`, `2fa36077`, `f33dd0ae`, `0253d3d6` (28 final) |
| **rc6** | release tag | PUBLICADA | tag `v0.62.0-rc6` en commit `0253d3d6`, ZIP SHA-256 `dd4508022a6d2e8313dc2c2982c138f3b7ea07ad6eecdd78d83e1bb3ab1b8111` |

## ADR-PK-DF-001: estado canónico vigente

La ADR-PK-DF-001 fue **aceptada con Option A** por el product owner el 2026-10-16
(commit `4e984747`). Su texto canónico en `docs/v2/04-adrs/` lista explícitamente los seis
criterios que deben satisfacerse antes de aceptar Option B (split APP/SDK channels):

1. Decisión registrada por el usuario.
2. Consumidor real instala el APP ZIP y verifica `version --json`, SHA, archive root y `SHA256SUMS`.
3. Maven remoto resuelve las 6 coordenadas sin `sdk-repo` ni `includeBuild`.
4. Certifier/installer/SDKMAN/plugin-authors confirman no depender de SDK ZIP/BOM JSON en APP.
5. Prueba remota descubre missing/extra/duplicados antes de declarar éxito.
6. Actualización atómica de `AGENTS.md` §8 y specs.

Hasta que los seis hold, **Option A es la ley ejecutable**.

## Consecuencia normativa sobre PRs abiertas

- **PR #142** (SDK/JAR-first policy, Option B): **NO debe mergearse** mientras AGENTS.md §8
  nombre SDK ZIP y BOM JSON como assets APP. La PR contradice la ADR aceptada.
  Estado: queda OPEN con la anotación de que su cierre requiere cumplir los seis criterios
  de Option B arriba.
- **PR #140** (UAT/E2E retirement inventory, 169 entradas): sigue DRAFT. 142 entradas dependen
  del certifier para equivalence proof (CandidateId, inputDigest, failureKind, symptom,
  causalBoundary). Fuera del scope autónomo de este repo por `ADR-PK-QV-003`.

## Cómo encontrar la verdad actual

- **Roadmap activo:** `docs/v2/05-roadmap/ROADMAP.md`
- **Addendum operativo:** `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md`
- **ADR-PK-DF-001 canónica:** `docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md`
- **Handoff rc6:** `docs/v2/06-evidence/0-62-rc6/HANDOFF.md`
- **Changelog desde rc5:** `docs/v2/06-evidence/changelog-since-rc5-2026-10-16.md`
- **SKD_PUBLISHED status:** `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md`
- **H2 TODO 5 receipts:** `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md`

---

**Aviso para futuros agentes:** si una sesión encuentra `docs/pipelinek-delivery-first-2026-10-16/`
untracked o esta copia archivada, NO la trates como cola pendiente. El roadmap del paquete está
cerrado; la cola activa vive en `docs/v2/05-roadmap/ROADMAP.md` y `docs/v2/00-governance/ACTIVE_DOCUMENTS.md`.