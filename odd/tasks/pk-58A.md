# PK-58A — Lectura viva/recuperable y presupuesto de recursos

**Última actualización:** 2026-10-13T13:30Z
**Estado:** T1 cerrado · Decisión recomendada: **NO_NEW_PORT** · T2/T3/T7 pendientes de autorización
**Rama prevista:** `pk-release-0-58-consolidation` (nueva, basada en `main` @ `280e72fd`)
**Release objetivo:** `v0.58.0-rc1` (publicable con `NO_NEW_PORT` si el operador lo autoriza)
**T1 audit:** [`docs/v2/07-uat/PK-58A-ports-audit.md`](../../docs/v2/07-uat/PK-58A-ports-audit.md)

## Estado tras T1 (auditoría cerrada)

**SPEC-01 PK-L1..PK-L7 contrastado contra `main@0a1e5c88`:** 6/7 cumplido, 1/7
parcial (PK-L3 wakeup = opcional, no implementado por diseño, polling es la
ruta actual a 25ms con `FOLLOW_IDLE_MILLIS`). Ver auditoría completa.

**Separación de planos (T2) ya implementada** en el grafo de módulos Gradle:
- `:pipeline-output` — sólo `api` deps sobre stdlib; cero deps a events/domain
- `:pipeline-output-store` — implementación local del write side
- `:pipeline-events` — contratos read-only independientes
- `:pipeline-events-store` — InMemory + SQLite EventStore
- `:pipeline-application` — composición de followers y `ObservationWakeup`

**`ObservationWakeup` ya es correctamente ADT de orquestación** (KDoc:
"a wakeup names a POSITION and nothing else"). No es autoridad durable.

**Gap principal:** `OutputCommitWakeupPort` no existe. **Es opcional por
SPEC-01.** El polling a 25ms con cursor durable + cross-process tests
cumple correctness. Eficiencia de polling no está medida pero no es el
camino crítico para un block RC.

## Tareas (revisadas tras T1)

### Cerradas

- [x] **T1** Auditoría SPEC-01 PK-L1..PK-L7 contra código. Documentada en
  `docs/v2/07-uat/PK-58A-ports-audit.md` con tabla per-requisito, mapa
  de separación de planos, tabla implementadas/declaradas/ausentes, mapeo
  UAT-PK-M1-001..008 / AAT-M1-01 contra tests OBS-PC existentes.
  Commit `280e72fd`.

### Pendientes (decisión del operador)

- [ ] **T2** ADR-arquitectura descriptivo de la separación de planos
  (`:pipeline-output` / `:pipeline-output-store` / `:pipeline-events` /
  `:pipeline-events-store` / `:pipeline-application`). Documenta el estado
  actual; no cambia código. **No requiere nueva autorización** porque
  describe lo que ya existe.
- [ ] **T3** `OutputCommitWakeupPort` opcional en `:pipeline-output`.
  **Recomendación del audit: NO construible en este bloque sin baseline
  de eficiencia que lo justifique** (PERFORMANCE_BUDGETS.md está
  PROPOSED, sin baselines medidos).
- [ ] **T4** Mapeo de tests OBS-PC a UAT-PK-M1-001..008 / AAT-M1-01 ya
  documentado en el audit (sección 4). No requiere renombrar tests
  existentes. **Gap:** AAT-M1-01 cross-JVM e2e no existe como test
  separado. `M1_FOLLOW_DESIGN §10` cita `M1-D` como el e2e requerido.

### Pendientes (release)

- [ ] **T5** Regenerar artefactos ZIP/SBOM/POMs/JAR/MODULE a HEAD con
  ABI documentada para la línea 0.58 (incluyendo el delta desde 0.57).
- [ ] **T6** ReleaseGate end-to-end (`:pipeline-release:releaseGate`).
  Con `NO_NEW_PORT`, el gate sigue siendo el del bloque anterior con
  artefactos regenerados.
- [ ] **T7** Push rama `pk-release-0-58-consolidation` → PR base=main →
  merge → tag `v0.58.0-rc1` → `gh release create --prerelease` con
  zip/sdk/sbom/poms/module + SHA256SUMS + ABI note 0.58.
- [ ] **T8** `PK_CONTRACT_HANDOFF` a Fabric con `product_version=0.58.0-rc1`
  y `abi_diff` (delta 0.57 → 0.58 con `NO_NEW_PORT` documentado).

## Decisión recomendada al operador

**NO_NEW_PORT en este bloque.** Razones:
1. Correctness ya está cubierta (audit §1).
2. Separación de planos ya implementada (audit §2).
3. `OutputCommitWakeupPort` es opcional por SPEC-01.
4. PERFORMANCE_BUDGETS.md sin baselines medidos; sin datos, no se justifica
   construir un seam decorativo.
5. La medición `polling-solo vs wakeup+polling` es T3, no se puede hacer
   sin construir el wakeup. Costo: T3 entero para comparar. Beneficio si
   polling cumple: ninguno. Beneficio si polling no cumple: documento
   concreto para T3-bis en otro bloque.

**Publicación propuesta:** `v0.58.0-rc1` con la línea 0.58 ABI
documentada (incluyendo `NO_NEW_PORT` como capacidad no construida, no
como capacidad omitida).

## Decisión pendiente

1. ¿Confirmar `NO_NEW_PORT` y proceder con T2 (ADR) + T4-T8 (release)?
2. ¿O autorizar T3 con un baseline medido explícito antes de T7?

Si la respuesta es 1, el bloque cierra con `v0.58.0-rc1` publicado
como CANDIDATE_PUBLISHED, `OutputCommitWakeupPort` queda como capacidad
**no construida con criterio de activación documentado** (futuro bloque).
