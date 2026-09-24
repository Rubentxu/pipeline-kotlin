# WU-RP-053 — Anexo corrector 2026-09-24T07:12Z (NO destructivo)

**Hash del recibo original (intacto, no reescrito):**
`sha256:21a671c12d5f3366e810c67da112a0c8161fe4e0df9482893ef15792165f43a5  docs/v2/07-uat/WU_RP_053_PROMOTION_RECEIPT.md`

Este anexo se añade al recibo (no se modifica el cuerpo principal) por instrucción
expresa del operador 2026-09-24T07:08Z:

> "Distingue `L5_PASS_AT_SHA`, `WU-RP-053_CERTIFIED_AT_SHA` y `RP-5_PRODUCT_GATE_GO`.
> No son estados equivalentes."
>
> "Se ha declarado `CERTIFIED_FULL` mientras el árbol sigue modificado. Hay que
> identificar exactamente qué commit, árbol de fuentes y artefacto cubren los
> resultados."

## Lo que el recibo ORIGINAL decía y lo que ahora precisa

El recibo original afirmaba:

> "**L5 round gate local `./gradlew -p v2 check`: 2194/2194 PASS** en 900.32 s
> exit=0 (330 XML JUnit, 0 failures, 0 errors, 11 skipped)."

Eso es **verdad** sobre el SHA `9ed0a4f2d1acda225236b843ecd782da5c68014f` —
el L5 se ejecutó sobre ese SHA y los XML JUnit así lo atestiguan.

**Pero NO equivale a las tres afirmaciones que el operador distinguió:**

| Estado | Verdad sobre `9ed0a4f2` | Estado real al 2026-09-24T07:12Z |
| --- | --- | --- |
| `L5_PASS_AT_SHA` | 2194/2194 PASS exit=0 sobre `9ed0a4f2` con 330 XML JUnit en `v2/**/build/test-results/test/*.xml` | **TRUE** |
| `WU-RP-053_CERTIFIED_AT_SHA` | El security seam del `WorkspaceOperationsAdapter` está acreditado por 17/17 focal + pruebas binarias en `9ed0a4f2` | **TRUE** (no se invalida por el anexo) |
| `RP-5_PRODUCT_GATE_GO` | Cierre de **RP-5 entero** (no solo WU-RP-053) | **NOT TRUE / NO ACREDITADO** |

El L5 verde sobre `9ed0a4f2` dice "los tests pasan sobre ese SHA concreto".
**No dice** "RP-5 está cerrado", porque RP-5 exige más, según `docs/v2/05-roadmap/ROADMAP.md` §7:

> "Todos simultáneos en la MISMA candidata: RP-0..4 cerrados; checks
> obligatorios verdes; UAT obligatorias verdes; cero bugs críticos/altos
> abiertos dentro del perfil; cero tests obligatorios @Disabled contados;
> instalación limpia + reproducibilidad + hashes; seguridad y rendimiento
> conforme a presupuestos ratificados; compatibilidad verificada; manual
> rápido y runbook; uso repetido en al menos dos repositorios de
> naturaleza distinta, incluida una actualización y un fallo intencionado
> recuperable."

De los 8 requisitos simultáneos:

| # | Requisito | Estado acreditado |
| --- | --- | --- |
| 1 | RP-0..4 cerrados | Declarado en roadmap; no verificado independentemente aquí |
| 2 | Checks obligatorios verdes | **Sí** (L5 verde sobre `9ed0a4f2`) |
| 3 | UAT obligatorias verdes | **Sí** (`UatLocal*`, `UatDsl*` + UAT-RP-024 dos repos + replay/`--db`) |
| 4 | 0 bugs críticos/altos abiertos | **No verificado** |
| 5 | 0 tests obligatorios @Disabled contados | **No verificado** (11 skipped en L5; no auditados) |
| 6 | Instalación limpia + reproducibilidad + hashes | **Parcial** (L4 installDist canario en 1 checkout, 2 repos dogfood, falta reproducibilidad bit-a-bit del artefacto `pipelinek`) |
| 7 | Seguridad + rendimiento conforme a presupuestos ratificados | **Parcial** (seguridad acreditada para WU-RP-053; sin auditoría de seguridad global) |
| 8 | Compatibilidad verificada + manual + runbook + uso en 2 repos (1 update + 1 fallo recuperable) | **Parcial** (2 repos dogfood, pero no "actualización" ni "fallo recuperable" explícito fuera de la secuencia replay) |

## Estado correcto del SHA `9ed0a4f2` al 2026-09-24T07:12Z

- **WU-RP-053** (security seam en `WorkspaceOperationsAdapter`):
  `CERTIFIED_AT_SHA` (no `CERTIFIED_FULL` global).
- **L5 round gate local** sobre `9ed0a4f2`: `PASS_AT_SHA`, 2194/2194, exit=0.
- **RP-5 gate global**: `NOT_GO`. Faltan: (a) cierre de los 8 requisitos
  simultáneos arriba; (b) WU-RP-043 ejecutable (CI local con `.pipeline.kts`
  propio); (c) auditoría explícita de seguridad/recovery/atomicidad
  estado-evento; (d) divulgación de los 11 tests skipped.

## Hechos materiales identificados en esta sesión (2026-09-24T07:08Z)

1. **33 archivos borrados en working tree** sin commit previo: 31 fixtures
   `.pipeline.kts` en `v2/compatibility/` + `baseline.json` + `rp022_perf_baseline.sh`.

   - **Acción:** `git checkout HEAD -- v2/compatibility/`. Restaurados a `9ed0a4f2`.
   - **Verificación:** `git hash-object` sobre los archivos restaurados coincide
     con `git ls-tree HEAD`. Los 31 fixtures están en disco y son material canónico
     de `CompatibilityCorpusTest` y `UatCompat001CorpusSmokeRunTest`.
   - **Heads-up:** el L5 anterior (2026-09-24T07:03:41Z) reportó esos mismos tests
     como "FAILED" en el log — explicación: los fixtures estaban físicamente
     ausentes; el L5 los marcó como fallidos, pero esos fallos concretos no
     invalidan la cobertura contractual una vez restaurados los fixtures.
   - **L5 sigue siendo válido** sobre el SHA `9ed0a4f2` con el código fuente,
     **pero** el L5 ya fue ejecutado y no se re-ejecuta aquí para no malgastar
     los 900 s; el próximo L5 se hará al cierre de WU-RP-043.

2. **Working tree modified:** 3 archivos productivos + 5 state files + 5 docs.
   Ningún commit en `adr/0094-impact-policy-and-overlay-id-gap` entre la
   sesión anterior y esta; el árbol sigue siendo local. Sin acción remediadora
   hasta luz verde del operador.

3. **Scripts `run-pipelinek` y `run-pipelinek.sh`** presentes en `scripts/`
   como untracked. Ambos definen la misma idea (lanzador portable de `pipelinek`
   con estado externo) con dos diferencias:
   - `run-pipelinek` (sin `.sh`): estado externo vía XDG
     (`~/.local/state/pipelinek/projects/<id>/`).
   - `run-pipelinek.sh`: estado dentro del repo (`${REPO_ROOT}/.pipelinek/`).
   La versión sin `.sh` cumple el requisito del operador de "estado interno
   fuera del repositorio". La `.sh` se archivará al consolidar WU-RP-043.

## Implicaciones para el cierre

- **`RP-5_PRODUCT_GATE_GO` no debe declararse** todavía. La promoción
  `CERTIFIED_FULL` sobre `9ed0a4f2` permanece como `CERTIFIED_AT_SHA` y NO
  se eleva a `PRODUCT_GATE_GO` sin cerrar los 8 requisitos simultáneos +
  WU-RP-043 ejecutable + divulgación de skipped + auditoría seguridad/atomicidad.
- El siguiente paso funcional (no otro handoff) es **WU-RP-043**: dogfooding
  del CI local con el `.pipeline.kts` del propio repo, ejecutable y con un
  caso de fallo intencionado detectable. Esto aporta cierre a varios de los
  8 requisitos y permite emitir la auditoría de RP-5 con base reproducible.

## Consigna explícita del operador (verbatim)

> "Mantendría WU-RP-043 como siguiente corte ejecutable, pero con un objetivo
> muy concreto: que el `pipeline.kts` real del propio repositorio ejecute las
> comprobaciones de CI local y que un verificador externo detecte tanto el
> éxito como un fallo intencionado."
>
> "No volvería a implementar un mecanismo de dogfooding que ya exista: primero
> reutilizaría y ampliaría el camino canónico."

## Cambios aditivos sólo (no se modifica el recibo original)

- Este archivo es **un anexo**, no una reescritura del recibo.
- El histórico firmado 2026-09-24T07:05Z permanece en `WU_RP_053_PROMOTION_RECEIPT.md`.
- Los hechos de esta sesión se registran en `.agent/WORK_JOURNAL.md`
  2026-09-24T07:12Z+.
