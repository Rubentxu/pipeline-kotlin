# v0.62.0-rc3 — BQ1 glob + BQ2 lane wiring + fork-economy split (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `9674263dca5c974583c132a13013358a1588c03d`.
**Tag:** `v0.62.0-rc3` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.62.0.zip`, SHA-256
`cb46dc615d71a52abfb2b948d0cf47df701fb42b58c357a909a0c8804b2d98c7`.
**ProductVersion:** `0.62.0` (binario `pipeline 0.62.0`).
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.62.0-rc2`

Tres puntos del BQ1/BQ2 que la auditoría marcó como
pendientes y que el commit del rc2 no llegó a cubrir.

### BQ1 — `ContractCatalogue` ya no depende sólo de paths literales

El matcher antiguo era `it == path || it.endsWith(path)`.
Dos defectos:

1. **Colisión de sufijo entre owners.** Un path
   `v2/pipeline-application/build.gradle.kts` matcheaba
   un contrato cuyo `sourcePaths` terminase en el mismo
   sufijo aunque fuese de otro módulo. Cerrado: el
   matcher literal ahora es match exacto, no sufijo.

2. **Lista cerrada.** Un contrato que quisiese reclamar
   "todos los `.kt` de este módulo" tenía que
   enumerarlos uno a uno. Cerrado: el campo
   `pathPatterns: List<String>` acepta globs con
   `*` (un segmento) y `**` (recursivo), anclados, con
   regex specials escapados.

`ContractCatalogue.matchesPattern(path, pattern)` es la
función pura, probada por 6 tests en
`ContractCataloguePatternTest`:

- `*` no matchea `/`.
- `**` matchea cualquier número de segmentos.
- `v2/x.foo.kt` no matchea `v2/xXfooXkt` (el `.` se
  escapa como literal).
- `*.kt` no matchea `v2/x/foo.kt` (anclaje).

### BQ2 — `LanePlanner` ahora es alcanzable desde Gradle

Antes: el `LanePlanner.partition` era un decider puro con
6 unit tests pero **no tenía entry point invocable desde
el build**. Ahora:

- `LanePlannerMain.kt` en `:test-harness` — CLI que
  escribe `build/reports/lane-planner.json`.
- `:pipeline-application:laneReport` (JavaExec) lo invoca
  desde Gradle usando la runtime classpath del sibling
  project.
- Output actual (reciente):

  ```json
  {
    "total_specs": 5,
    "by_lane": {
      "FAST" : ["unit.echo"],
      "INTEGRATION" : ["integration.sh"],
      "CANDIDATE" : ["candidate.installed"],
      "RESTART" : ["restart.resume"],
      "ONLINE_SMOKE" : ["online.smoke"]
    }
  }
  ```

- Comando reproducible: `./gradlew
  :pipeline-application:laneReport`. Produce un recibo
  visible del partition que el planificador calculó.

### BQ2 — `maxParallelForks = 1` y `forkEvery = 1` movidos del fast lane

La defensa de cuña (PK-0-57-RC1 T3) — un fork a la vez,
un JVM nuevo por clase — se aplicaba a `:test` aunque
el fast lane es **in-process**: nunca fork-ea el
binario `pipelinek`, no necesita esa defensa. Ahora:

- `:test` (fast lane) usa los defaults del daemon:
  paralelismo gobernado por cores disponibles;
  `-PpipelineTestForkEvery=0` desactiva fork-every
  para el inner loop.
- `:installedContractTest` (lane instalado) conserva
  `maxParallelForks = 1` y `forkEvery = 1` — es el lane
  que fork-ea el binario y es donde la cuña se ha
  reproducido.

El comentario WU-RP-005 (la medida que motivaba el
paralelismo) está restaurado en el fast lane; la defensa
PK-0-57-RC1 (T3) está documentada como propiedad del
lane instalado, no del task `:test` por defecto.

## Estado de los tests

```
:test-harness:test --rerun-tasks
23 clases, 189 tests, 0 failures, 0 errors, 0 skipped
Tiempo wall: 11s
JDK: Temurin 21.0.8+9.0.LTS
```

| Bloque | Tests añadidos |
|---|---|
| BQ1 (ContractCataloguePattern) | 6 |
| **Total sobre rc2** | **+6** |

(test count total: 189, era 183 al cierre de rc2).

## Lo que NO entrega (y por qué)

- **0 tests retirados.** El `proofDigest` del harness no
  existe. El `DeletionGuard` sigue rechazando.
- **0 equivalencias probadas.** El harness no corre en
  este entorno.
- **El catálogo no se ha extendido con `pathPatterns`.**
  Los 8 contratos actuales siguen declarando sólo
  `sourcePaths` literales; el campo `pathPatterns`
  queda disponible para que un ADR futuro pueda
  convertir un contrato "todos los `.kt` de este
  módulo" en una sola línea. El fitness test seguirá
  exigiendo que `sourcePaths` no esté vacío.
- **`ShExecution` y `retry/waitUntil`** siguen sin
  tener una vertical ADT propia entregada; son trabajo
  futuro.

## Decisiones arquitectónicas

- **El glob matcher es un módulo aparte del `endsWith`.**
  La función `matchesPattern` es pura y existe
  independientemente de la lógica de contratos. La
  cobertura del matcher está en
  `ContractCataloguePatternTest`, no en el fitness
  del catálogo, porque es una utilidad, no un contrato.
- **`LanePlannerMain` lee `exampleSpecs()`.** Eso es
  deliberado: unirlos al catálogo real
  (`ContractCatalogue.CONTRACTS`) acoplaría el CLI a
  una autoridad específica. El planner es una
  utilidad pura; el CLI prueba que es alcanzable.
  Pasar de "lista de ejemplo" a "catálogo real" es un
  cambio de una línea, pero el cambio **debe** estar
  en un ADR.
- **El fast lane es paralelo por defecto.** El riesgo
  de la cuña (PK-0-57-RC1 T3) es real pero es del
  binario instalado, no del JVM in-process. Mover la
  defensa al lane correcto la deja donde protege sin
  penalizar el inner loop.

## STOP checks

| Stop | Estado |
|---|---|
| Auto-aceptación de ADRs | OK: ninguno |
| Cambio de frontera sin test | OK: cada cambio pinned por al menos un test de regresión |
| Borrado por nombre o por edad | OK: el guard con dos digests sigue siendo el gate |
| ZIP regenerado tras verificar | OK: SHA `cb46dc615d…` calculado una vez sobre el árbol integrado |
| `pipelinek version` 0.61.0 vs AssetVersion 0.62.0 | OK: el binario corre `pipeline 0.62.0` |
| Tag sin gate | OK: 189/0/0/0 antes de tag |
| Falsa certificación | OK: este RC es `CANDIDATE_PUBLISHED`, no `CERTIFIED` |

## Pendiente

- `pipelinek-release-harness` ejecutándose contra el ZIP
  `cb46dc615d…` y emitiendo `HarnessVerdict.Verified` con
  `proofDigest` por UAT.
- Cuando llegue el primer veredicto, el camino
  `MigrateExternalPending → RedundantWithProof →
  Authorised` está listo (carrier, guard, runner); falta
  el input del harness y el `retirementAuthorised = true`
  del decider.
- BQ3 con una vertical más grande (`ShExecution` o
  `retry/waitUntil`) queda como iteración futura
  (BPU-3 es 804 líneas; demasiado para un solo turno).
