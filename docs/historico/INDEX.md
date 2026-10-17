# Histórico de propuestas y roadmaps — archivado 2026-09-21

**Este directorio NO define la cola activa.** Se preservan contenidos y Git history sin reescribir recibos, ADRs ni implementaciones. La autoridad vigente vive en docs/v2/05-roadmap/ROADMAP.md; navegación en docs/v2/00-governance/ACTIVE_DOCUMENTS.md. Baseline de procedencia: main a554fd5544f74f580bbd531c9b394cff1e073621.

## Documentos movidos sin cambiar su contenido

| Origen anterior | Destino histórico | Motivo |
|---|---|---|
| docs/v2/05-roadmap/ROADMAP.md (versión anterior) | docs/historico/2026-09-21/roadmaps/ROADMAP_V2_PRE_RP.md | cronología M0/M3/ML/EM y enmiendas, no cola actual |
| docs/v2/05-roadmap/LOCAL_PRODUCTION_READY_ROADMAP.md | docs/historico/2026-09-21/roadmaps/LOCAL_PRODUCTION_READY_ROADMAP_2026-09-18.md | primera propuesta LPR, superada en orden y gates |
| docs/v2/05-roadmap/ROADMAP_MIGRATION_LPR.md | docs/historico/2026-09-21/roadmaps/ROADMAP_MIGRATION_LPR.md | decisión de migración histórica; las decisiones aceptadas sobreviven |
| docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md | docs/historico/2026-09-21/roadmaps/LFC2_HONEST_DSL_CLOSURE.md | antiguo plan de cierre absorbido por LPR-3 / RP-3 |
| docs/pipeline-kotlin-event-spine-evolution/ | docs/historico/2026-09-21/paquetes/pipeline-kotlin-event-spine-evolution/ | paquete de propuesta original |
| docs/pipeline-kotlin-execution-model-proposal/ | docs/historico/2026-09-21/paquetes/pipeline-kotlin-execution-model-proposal/ | paquete de propuesta absorbido |
| docs/pipeline-kotlin-lfc2-step-testkit-evolution/ | docs/historico/2026-09-21/paquetes/pipeline-kotlin-lfc2-step-testkit-evolution/ | paquete original; normas integradas en docs/v2 |
| docs/pipeline-kotlin-local-foundation-consolidation/ | docs/historico/2026-09-21/paquetes/pipeline-kotlin-local-foundation-consolidation/ | paquete original previo a integración |
| docs/pipeline-kotlin-local-production-ready-2026-09-18/ | docs/historico/2026-09-21/paquetes/pipeline-kotlin-local-production-ready-2026-09-18/ | borrador empaquetado LPR |

Los enlaces antiguos a esos paquetes en notas/recibos históricos son referencias AL PATH ANTERIOR y pueden no resolver en HEAD; consultar este índice o navegar el commit de procedencia. No se cambia el contenido original solo para reparar enlaces históricos. Las referencias normativas de la documentación activa deben apuntar a docs/v2/ y a este índice.

**Conservados en su sitio deliberadamente:** ADRs aceptados y propuestos, especificaciones, receipts, evidence, UAT previas, LOCAL_FOUNDATION_CONSOLIDATION.md, LFC2_STEP_ECOSYSTEM_EXPANSION.md e INITIATIVE_LPR_001.md: contienen obligaciones técnicas/operativas todavía consultadas, aunque NO autorizan una cola paralela al roadmap activo. Otros informes antiguos permanecen como referencia hasta una migración de enlaces separada.

## Candidatas archivadas

| Candidata | Destino histórico | Train | Commit de publicación | Build commit | Veredicto externo |
|---|---|---|---|---|---|
| `evidence/0-61-rc2/` (en `main`) | `docs/historico/0-61-rc2/` | `0.61.0-rc2` | `6a5e9183` (`release: v0.61.0-rc2 — fix PRODUCT IDENTITY LAW, publish candidate`) | `4a6b161e` (post-PR #136) | pendiente del harness (`pipelinek-release-harness`); el veredicto NO se archiva aquí |

Esta fila existe porque `evidence/0-61-rc2/` dejó de ser directorio activo al publicarse la
siguiente candidata. El archive es byte-inmutable: ZIP, SBOM, git-head y `SHA256SUMS` con sus
sumas verificadas en `docs/historico/0-61-rc2/README.md`. La copia operativa puede continuar
en `evidence/0-61-rc2/` mientras el harness la necesite; si se borra, el archive es la
verdad histórica.

## Addendum 2026-10-16 — paquete delivery-first ejecutado

| Origen anterior | Destino histórico | Motivo |
|---|---|---|
| `docs/pipelinek-delivery-first-2026-10-16/` (untracked) | `docs/historico/2026-10-16/paquetes/pipelinek-delivery-first-2026-10-16/` | propuesta operativa del BQ4 vertical; H0..H4 ejecutados y sellados en `v0.62.0-rc6`; ADR-PK-DF-001 aceptada con Option A (commit `4e984747`); el paquete se preserva como propuesta original sin reescribirla |

**Relación con el cierre vivo:**

- Handoff rc6 (sellado): `docs/v2/06-evidence/0-62-rc6/HANDOFF.md`
- ADR-PK-DF-001 canónica (accepted): `docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md`
- Changelog desde rc5: `docs/v2/06-evidence/changelog-since-rc5-2026-10-16.md`

**Decisión autónoma de archivo:** la sesión 2026-10-17 (loop 15m, mandato del operador
"decide a tu criterio, apruebo cualquier gate") confirmó por preflight que los cinco hitos del
paquete están ejecutados con código + tests + SHA en `main`. El paquete nunca llegó a
integrarse vía `tools/integrate.py --apply` (el script rechaza la ADR `proposed` porque la
canónica está `accepted`); archivarlo preserva la propuesta original y deja el rastro normativo
en su sitio, sin reintroducir la cola paralela.

**PRs abiertas referenciadas por el paquete:**

- PR #142 (SDK/JAR-first, Option B): NO mergeable mientras AGENTS.md §8 nombre SDK ZIP / BOM
  JSON como assets APP. La ADR-PK-DF-001 (accepted, Option A) fija las seis condiciones que
  Option B debe satisfacer antes de sustituir.
- PR #140 (UAT/E2E retirement inventory, 169 entradas): DRAFT. 142 entradas dependen de
  equivalence proof del certifier (CandidateId, inputDigest, failureKind, symptom,
  causalBoundary), fuera del scope autónomo del repo por ADR-PK-QV-003.
