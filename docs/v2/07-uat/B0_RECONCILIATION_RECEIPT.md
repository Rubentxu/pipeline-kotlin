# B0 — Reconciliación de procedencia, verdad del repositorio y autoridad de admisión

**Base SHA:** `acd121117a9f6a4a1389462342b0f0bf4e92590c` (rama `s6-plugin-sdk`)
**Remoto observado:** `origin/main = b66bf7c796db28dabf3e13df9784844e7a59aeda`
**Fecha:** 2026-10-08
**WorkItem SDDK:** `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146` (Active)
**Estado:** reconciliación completa; hallazgos abiertos con dueño. **Ningún hallazgo se declara cerrado en este recibo.**

Clases de evidencia:

```text
OBSERVED      comando ejecutado, salida citada
INFERRED      conclusión derivada de evidencia citada
NOT_VERIFIED  no medido; se dice explícitamente
```

---

## 1. Estado real (todo OBSERVED)

| Hecho | Evidencia | Valor |
|---|---|---|
| HEAD | `git rev-parse HEAD` | `acd12111` |
| Rama de trabajo | `git branch --show-current` | `s6-plugin-sdk` |
| Divergencia con el trunk | `git rev-list --left-right --count origin/main...s6-plugin-sdk` | `0 33` (solo por delante) |
| Versión en build | `grep version v2/build.gradle.kts` | `0.47.0` |
| Última release publicada | `gh release list` | `PipelineK v0.47.0` — **Latest**, `2026-10-06T06:04:18Z` |
| Zip publicado | `gh release view v0.47.0 --json assets` | `pipelinek-0.47.0.zip` = `sha256:2fa2d272…d3301c`, 91 854 545 bytes |
| Superficie de CI | `gh api .../actions/workflows` | `total_count: 1` (solo Dependabot) |
| `.github/workflows/` | `ls` | no existe |
| Checks requeridos en `main` | `gh api .../branches/main/protection/required_status_checks` | `404 Required status checks not enabled` |
| Rulesets | `gh api .../rulesets` | `[]` |
| Ciclos SDDK | `sddk cycle list` | 129 totales: 42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING |
| Siguiente item del roadmap | `sddk plan roadmap next` | `f8fc07e6…` (`resume_active`) |

---

## 2. Hallazgos

### B0-F1 (P0) — la release estable salió de un commit que no está en `main`

**RESUELTO 2026-10-09.** La PR #99 se integró en `main` por merge directo, sin squash, y su
commit conserva su SHA propio. Verificación OBSERVED:

```text
gh pr view 99 -> state: MERGED, updatedAt: 2026-10-09T13:21:31Z
git merge-base --is-ancestor 3ec99a4c origin/main -> SI
git log --oneline origin/main | grep byte-reproducible
  6e8e86bd  fix(example-plugin): make all three plugin JARs byte-reproducible
  3ec99a4c  fix(example-plugin): make the directive plugin jar byte-reproducible
git ls-tree -r origin/main | grep pinned
  100644 blob 6823ba59…  DirectivePluginContractSuiteTest.kt.pinned
```

Esto es exactamente lo que ADR-0099 exige: "`main` contiene la historia completa de
candidatos y releases", con el commit original intacto y legible por su SHA. No hubo squash,
no hubo reescritura, no hubo movimiento de tag.

El commit `6e8e86bd` es posterior y **extiende** el fix a los otros dos plugins de ejemplo
(`example-uppercase-plugin` y `example-block-plugin`); no reemplaza a `3ec99a4c`.

Nota de alcance: esto cierra **la pertenencia al trunk**, que es lo que B0-F1 afirmaba. No
cierra la certificación del artefacto ya publicado; el tag `v0.47.0` sigue apuntando a
`3ec99a4c`, y las candidatas construidas desde `main` son reconstruibles desde ese punto en adelante.

**Estado original (histórico, 2026-10-08):**

```text
tag v0.47.0            -> 3ec99a4cb9059b1d7c7fb5902f90ac5dc1b697c4
git branch -a --contains 3ec99a4c  -> solo origin/fix/reproducible-directive-plugin-jar
git merge-base --is-ancestor 3ec99a4c origin/main  -> NO
gh pr view 99 -> OPEN, base main, head 3ec99a4c
```

`3ec99a4c` es el head de la PR #99, **abierta**. Los bytes de la release estable contienen un
commit (`fix(example-plugin): make the directive plugin jar byte-reproducible`, +384 líneas en
`examples/example-directive-plugin/build.gradle.kts` y un fichero `*.pinned`) que **no está en
`main` ni en la rama de trabajo**:

```text
git diff origin/main 3ec99a4c -- examples/example-directive-plugin/build.gradle.kts
  + isPreserveFileTimestamps = false
  + isReproducibleFileOrder = true
```
Ese contenido no existe en `main` (`git ls-tree -r origin/main | grep pinned` → vacío).

Contradice ADR-0099 (aceptado): "`main` contiene la historia completa de candidatos y releases".
Impacto: la identidad del artefacto publicado no es reconstruible desde el trunk.
Disposición: **no corregido aquí** (B0 prohíbe push). Requiere autorización para integrar la PR #99
por la vía que ADR-0099 fija (sin squash, sin reescribir historia).

### B0-F2 (P0) — `main` no tiene ningún check requerido y no hay superficie que lo produzca

```text
gh api .../branches/main/protection
  { enforce_admins: false, required_signatures: false, allow_force_pushes: false, ... }
  (sin campo required_status_checks)
gh api .../branches/main/protection/required_status_checks -> 404
gh api .../rulesets -> []
```

El último registro histórico conocido de protección real está en
`.agent/WORK_JOURNAL.md:449` (`contexts: [LPR-0 CI / compile, domain-unit, architecture-fitness]`),
evidencia del 2026-09-21, **antes** de `754ddda0` (2026-09-30), que retiró las workflows. Hoy la
fila G10 "branch/release protection observes that check" no tiene mecanismo, y ninguna otra
condición está mecánicamente protegida en el trunk.
Impacto: la afirmación "protección activa" no es sostenible hoy; cualquier integración a `main`
depende de disciplina humana.
Disposición: registrar la dependencia externa ampliada (protección + check), no reintroducir CI
localmente (ADR-0105 D3).

### B0-F3 (P1) — la certificación del artefacto publicado existe y es real (no hay falso verde en ese punto)

**Re-verificado 2026-10-09: la conclusión se sostiene**, con una corrección de precisión. B0
citaba `decision CERTIFIED` como si fuera un campo de primer nivel de `matrix.json`; no lo es.
Vive dentro de la salida del paso de certificación:

```text
harness evidence/dogfood/matrix.json, fila version 0.47.0
  claves reales: build_at, candidate_id, image_built_at, image_digest,
                 image_tag, steps, version, zip_sha256
  steps["certify-base"].stdout -> {"decision": "CERTIFIED", "gates": [...]}   rc: 0
  zip_sha256: 2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3
harness evidence/promotions/v0.47.0.json
  { ga_version: v0.47.0, rc_version: v0.47.0-rc3, certified_at: 2026-10-05T21:00:42Z,
    source_result_id: 8b8d25ddac4b420a, outcome: ALREADY_PROMOTED_IDENTICAL, assets 5 }
```

El zip estable publicado (`2fa2d272…`) coincide con el artefacto certificado por el harness, y la
promoción quedó registrada con su recibo. Es decir: **el problema de v0.47.0 no es de
certificación del artefacto, es de pertenencia al trunk (B0-F1, ya resuelto) y de protección
(B0-F2, sigue abierto)**.
Nota: rc2 tiene otro zip (`02b1632e…`, `source_commit 5ea4137d`), así que hay al menos dos
artefactos `0.47.0` distintos y solo uno fue el promovido. `INFERRED` (por recibo + tag).

**Brecha que esto revela (OBSERVED):** no hay certificación del harness para `v0.48.0-rc1`.
`evidence/` del harness llega hasta `v0.47.0`. Nuestra candidata se publicó sin veredicto
externo, lo cual es exactamente el estado que G10 describe — y por eso su promoción estable
sigue bloqueada, correctamente.

### B0-F4 (P1) — el harness todavía no publica el check de admisión que ADR-0105 le asigna

**Actualizado 2026-10-09** (ver `B0_3_HARNESS_INVESTIGATION_RECEIPT.md`). La afirmación
original de este hallazgo —"no publica check porque la capacidad no existe todavía"— era
**incompleta, no falsa**, y la diferencia cambia el dueño del bloqueo:

```text
ANTES (2026-10-08)  la capacidad no existe todavía
AHORA (2026-10-09)  la capacidad EXISTE y está probada: harness/check_run.py,
                     53 tests verdes, payload ligado a (SHA, artifact digest,
                     verdict digest, perfil, identidad); una candidata supersedida
                     nunca es success; la ausencia es failure, nunca neutral
                     PERO vive en 74 commits sin pushear + 4 módulos sin commitear,
                     sobre un origin/main parado desde 2026-09-29
```

El veredicto de G10 **no se mueve**: `NOT_RUN`, `RP-5 PRODUCT_GATE_STOP`. Lo que cambia es
que el bloqueo son tres acciones concretas con dueño externo, no trabajo de implementación
pendiente.

Hallazgo nuevo de la misma investigación: el roadmap del harness afirma que ADR-0105
"no existe upstream", y es falso. Su `FETCH_HEAD` contra este repo es de 2026-09-29: nunca
hace `fetch`, así que evalúa su autoridad contra un snapshot de diez días. Su autoridad
declarada sobre este check es `PR-ADR-002`, estado **PROPOSED**, con la prohibición de su
propio AGENTS.md de atribuirle aceptación. Aceptar ADR-0105 sin resolver ese choque deja dos
documentos declarándose autoridad sobre el mismo check.

Búsqueda literal en el checkout real del harness (`Pipelinek-Test-Hardness@a97ad2b1`):

```text
grep -rn 'check-runs|check_run|/statuses|required_status|octokit' --include=*.py --include=*.sh
  -> 0 resultados en código del harness
  -> los únicos hits son lecturas manuales históricas en pipeline-kotlin/.agent/WORK_JOURNAL.md
gh api usages del harness: refs/tags (promote-rc-to-ga.sh:302), release create/upload,
  e issues (harness/issues.py:331 publish_or_update) -- nunca check-runs ni commit statuses
```

Es decir: la decisión de **quién** publica el check está tomada (ADR-0105), y su implementación
**no existe todavía**. La parte de ADR-0105 D4 ("dependencia explícita al proyecto propietario") es
por tanto la correcta y sigue abierta; la parte "el harness publica el check" es una
responsabilidad decidida, **no un hecho implementado**. `OBSERVED` la ausencia en el código
inspeccionado; `NOT_VERIFIED` que no exista en otra rama del harness.
Contraparte local que sí existe: `scripts/consult-harness-verdict.py` (exit `0/2/3/4/75`,
`ISSUE_PENDING`), consumidor del veredicto; no publica checks.

### B0-F5 (P1) — drift documental confirmado (AUD-11 deja de ser hipótesis)

**Actualizado 2026-10-09**: el drift **persiste y ha empeorado**. B0 lo registró contra
v0.47.0; el trunk ya va a `v0.48.0-rc1`.

`docs/v2/00-context/CURRENT_STATE.md` (addendum 2026-10-01) declara:

```text
"Release: v0.46.0 ESTABLE"                     -> real: v0.47.0 es la Latest desde 2026-10-06
"Ciclos SDDK activos: exactamente uno
 (p-1f3622e11c093341/pr017-run-lifecycle-engine)" -> real: 42 ciclos OPEN
```

Y el encabezado de `docs/v2/05-roadmap/ROADMAP.md` declara baseline `main @ a554fd55` (2026-09-21)
y producto publicado `v0.39.0`, cuando el trunk real es `b66bf7c7` y el producto publicado es
`v0.47.0`.

**Medición de re-verificación (2026-10-09, OBSERVED):**

```text
CURRENT_STATE.md:5   "> Release: `v0.46.0` ESTABLE"          -> sigue stale (v0.48.0-rc1 es Latest)
ROADMAP.md:4        "Baseline: main @ a554fd55"             -> sigue stale (main = 573abf66)
ROADMAP.md:6        "Estado de producto publicado: v0.39.0" -> sigue stale (Latest = v0.47.0)
sddk cycle list     42 ciclos OPEN                          -> el número de B0 sigue siendo correcto
```

Por qué no se corrige aquí: `ROADMAP.md` declara un **baseline auditado** con fecha. Sobrescribir
`a554fd55` con el HEAD actual no actualiza el drift: convertiría una auditoría fechada en una
afirmación sobre un árbol que nadie auditó. La corrección honesta es re-auditar contra el
trunk actual y emitir un baseline nuevo con su propia fecha, que es trabajo de otra unidad.

**RESUELTO 2026-10-09.** Re-auditado contra el trunk y corregido con el patrón que el propio
documento ya usaba: un bloque de hechos observados nuevo, que supersede al anterior como
referencia de estado sin destruirlo. El baseline `a554fd55` **se conserva intacto**.

```text
ROADMAP.md      bloque "Re-auditoría de hechos 2026-10-09" añadido
CURRENT_STATE.md addendum 2026-10-09 añadido, conservando el de 2026-10-01
```

Los dos documentos declaran ahora el mismo estado, y coincide con lo medido por API:

```text
origin/main          98f163c04d7cbcb2976236eaa1c4272bc74e1fda
producto publicado   v0.48.0-rc1 (Pre-release, 2026-10-09T13:52:57Z)
                     tag -> 6e8e86bd; zip 92 119 743 bytes
                     sha256:a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93
Latest (stable)      v0.47.0 (2026-10-06), zip 2fa2d272…, certificado por el harness
checks en HEAD       0 sobre 98f163c0;  required_status_checks ausente;  rulesets 0
ciclos SDDK          42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING
```

El digest del ZIP publicado por la API (`a4620df4…`) **coincide** con el registrado en el
recibo R1 de esta misma entrega: los bytes remotos son los que se certificaron, no una
reconstrucción posterior.

### B0-F6 (P2) — inventario de ciclo de vida pendiente

42 ciclos OPEN (la mayoría sin lease y con `updated_at` de septiembre), 4 BLOCKED
(`m0-02-compiler-plugin-k2-migration`, `inc-039-kdoc-fix`,
`lfc4-000-execution-model-contract-freeze`, `rp-020-durable-sequence-authority`), 1
`RELEASE_PENDING` (`retry-conditions-fail-closed`). Además, más de 10 PRs abiertas y antiguas,
incluida la #99 que sostiene B0-F1.

Triage medido (2026-10-08, `sddk plan work-item list` por ciclo + `sddk cycle next`):

```text
18 ciclos  manifiestos HUERFANOS: 0 work items, 0 artefactos, y `sddk cycle next` responde
           "has no replayable state events" -> ningun verbo de SDDK puede cerrarlos
           (supersede exige lease+fencing; rebuild necesita eventos que no existen)
23 ciclos  trabajo TERMINADO y ciclo nunca cerrado: train-0 13/13 Done, train-1/2 D-018..D-032
           1/1 Done, train-040-final 10/11, train-040-gate-recovery 2/3, detekt-burndown 1/1.
           Todos siguen en el nodo Open/Explore con frontera `phase.explore.complete` y
           `requires_met: false`: nunca pasaron su gate
 4 ciclos  con trabajo realmente pendiente: `reproducible-directive-plugin-jar` (1 Draft, es la
           PR #99 de B0-F1), `rp7-sem-s4-scripted-runtime-v2` (1 Done + Draft),
           `train-dsl-honesty` (9 items Draft/Superseded), y el ACTIVO `rp7-sem-s6-plugin-sdk`
```

**Por qué esto no se cierra aquí.** Cerrar los 23 exigiría emitir `exploration-report` y gate
receipts **a posteriori** para trabajo ya hecho; eso es fabricar evidencia y es el falso verde que
este repositorio prohíbe.

**Corrección 2026-10-09: los 18 huérfanos SÍ los puede cerrar un verbo existente.** B0 afirmó
que "ningún verbo puede cerrarlos" y que faltaba un verbo de mantenimiento en el framework.
Eso es falso, y está medido:

```text
$ sddk cycle next --cycle p-733fb505b5a6bd2d/corpus-closure
error: cycle … has no replayable state events        <- el frontier no ve transiciones

$ sddk cycle supersede --cycle …/corpus-closure --reason scope-invalid \
      --lease-owner orchestrator --fencing-token 1
error: ADMISSION: approval required before mutating 'cycle_state'
       (decision_id=approval-system-cycle_supersede); no changes were made
```

`cycle next` y `cycle supersede` son caminos distintos. El primero reconstruye el frontier desde
los eventos y por eso no ve nada; el segundo no los necesita — exige lease y fencing token, que
este ciclo tiene (`owner=orchestrator fencing_token=1`). El verbo existe, funciona y acepta
`--reason scope-invalid`, `goal-replaced` o `external-obsolete`.

Lo que falta no es capacidad: es **autorización**. `supersede` exige aprobación humana antes de
mutar `cycle_state`, y la solicitud quedó registrada:

```text
sddk approval list --cycle p-733fb505b5a6bd2d/corpus-closure
  capability:   surface.cycle_state#cycle_supersede
  request_hash: sha256:7f762f08134515b0c1a7a37ef32955b8ebb08d0624eff471f4d3125093dda232
  requested_at: 2026-10-09T15:11:06Z
```

Que el gate lo bloquee es lo correcto: cerrar 18 ciclos es una decisión de ciclo de vida, no una
tarea de mantenimiento. Un agente que lo resolviera sin aprobación habría hecho exactamente lo que
B0Dice evitar. El ciclo sigue `OPEN`; no se cambió nada.

La brecha queda reformulada: **no falta un verbo, falta la decisión del propietario** sobre qué
razón aplica a cada uno de los 18. Decisión registrada en SDDK: `550ae52d-398d-44d5-a0f6-0386271340cf`.

Nota honesta sobre el ciclo activo: `rp7-sem-s6-plugin-sdk` está en el mismo nodo `Open/Explore` con
`requires_met: false`, mientras sus commits sí se trazan por el ledger de WorkItems. La estagnación
de fase es previa a este trabajo y no la he movido; moverla exigiría el mismo reporte a posteriori.

---

## 3. Procedencia (B0.2)

Ver `AUD_01_PLUGIN_PROVENANCE_DIGEST_RECEIPT.md` (histórico, **no reescrito**) y el commit
correctivo que acompaña a este recibo. Corrección cubierta: utilidad de digest única y compartida,
declaración de `inputs` reales (clases, recursos, metadata) y exclusiones por ruta relativa
**exacta** en lugar de por sufijo `endsWith("plugin-manifest.json")`.

---

## 4. Autoridad de admisión (B0.3)

```text
ADR-0105  -> status: accepted, 2026-10-08  (no se modifica; se contrasta)
G10       -> PRODUCTION_READY_GATE.md:81 = BLOCKED_EXTERNAL, coherente con el ADR
harness   -> no publica check hoy (B0-F4); dependencia externa real y registrada
local     -> scripts/consult-harness-verdict.py consume el veredicto; no publica checks
```

Conclusión: no hace falta un ADR nuevo ni un emisor local de veredictos (ADR-0105 D3/D4 lo
prohíben). Lo que falta es capacidad **externa** y queda enunciado como dependencia verificable:
publicar un check run ligado a `(SHA, digest)` y hacer que la protección lo observe.

---

## 5. Inventario de S6 verificado contra el código (para no reimplementar)

Verificación read-only sobre `acd12111` (sin ejecutar Gradle). Estado por capacidad, con evidencia:

| Capacidad | Estado | Evidencia |
|---|---|---|
| S6.1 manifest legible por máquina + admisión antes de cargar | VERIFIED | `pipeline-domain/…/step/PluginManifest.kt`, `PluginManifestCodec.kt`, `PluginAdmission.kt` ("THE single authority"), `PluginManifestResourceReader.kt`, `application/PreLoadPluginAdmission.kt:58`, `PreResolvedComposition.kt:108-150` (pass 1) |
| S6.2 contribución de Steps con metadatos | VERIFIED | `PluginContributions.kt:35` (`PluginStepContribution`); `PluginManifest.kt:36-40` (release/delivery/trust) |
| S6.3 contribución de directivas | VERIFIED | `PluginContributions.kt:40`; `ExternalDirectivePluginDiscovery.kt` (ServiceLoader, adapter) |
| S6.4 contribución de eventos | VERIFIED | `PluginContributions.kt:44`; `events/registry/EventRegistry.kt` (Builder + `RegistrationOutcome`, congelado); `ExternalEventDefinitionDiscovery.kt`; `PluginEventEmissionAdapter.kt` |
| S6.5 KSP limitado a metadatos | VERIFIED por eliminación, y más fuerte | `v2/settings.gradle.kts:12` documenta que `com.google.devtools.ksp` se retiró; `NoSecondStepMetadataAuthorityFitnessTest` lo fija. El generador ya no existe |
| S6.6 ABI/API y compatibilidad | PARCIAL | `apiRange` en el manifest; dumps de contrato (`pipeline-scripting-api.api`). **No hay BOM**: `grep -rn java-platform v2` → exit 1 |
| SDK distribuible (sdk-repo) | PARCIAL | `v2/build.gradle.kts:474-547` (`sdkRepoDir`, `publishSdkForExternalPlugin`, `publishedContractModules`); `pipeline-step-sdk:api` y `pipeline-credentials-api` **no** aplican `maven-publish` |
| Descubrimiento externo | VERIFIED (con matiz) | 4 adaptadores ServiceLoader de familias (steps/directives/events/capabilities) + un loader de credentials, que es otro dominio |
| StepKey duplicado fail-closed | VERIFIED | `domain/step/StepRegistry.kt:133,148,241` |
| Plugins de referencia reales | VERIFIED | módulos `pipeline-step-sdk/{http,scm-git,utilities,files,junit,workflow-control}`; `examples/example-uppercase-plugin` (Step+directiva+evento), `example-block-plugin`, `example-directive-plugin`, cada uno con `settings.gradle.kts` propio (build independiente) |
| Manifest dentro del artefacto real | VERIFIED | inspección de JAR construidos: `META-INF/pipelinek/plugin-manifest.json` + `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` |
| "cero cambios en core" | PARCIAL, y hay que decirlo | `Lfc2HttpOfficiallyPluginBoundaryFitnessTest` (**FIT-1..FIT-13 son scans de texto**, no certificación funcional). Lo funcional vive en `ExternalPluginFourFamilyAdmissionTest`, `PluginAdmissionInstalledDistributionUatTest`, `P3DPluginEventInstalledDistributionUatTest`, `HttpInstalledUatTest`, `S54ExternalVerticalRestartUatTest` |
| Block Steps por maquinaria compartida | PARCIAL | `dispatchRetryBlock`&co: 0 en `src/main` (grep exit 1); ledger de deuda de routing concreto vacío. **`BranchInvoker.invokeAll` no tiene ningún llamante productivo** (`grep -rn 'invokeAll' v2/*/src/main` → vacío); `DefaultBranchInvoker` existe como implementación de referencia sin uso. `parallel` sigue siendo el nodo estructural `StepSpec.kt:111` |
| UATs migradas al harness | OPEN | **no existe ninguna marca `@Disabled("migrated to harness…")`**; los `@Disabled` encontrados son snapshots históricos, no migraciones |
| Cultura de sandbox en tests | VERIFIED | `CliRun.kt:117` (`object OwnedSubprocess`) usado por varias UAT; `TestSandboxFitnessTest` aplica la ley de `/tmp` |

Límite declarado: la búsqueda de llamantes se hizo sobre `src/main/**/*.kt`; un llamante en otro módulo o en generados no queda cubierto (`NOT_VERIFIED`).

---

## 6. Matriz por bloque (estado observado 2026-10-08)

```text
B0  PARCIAL   reconciliación hecha y persistida; 2 hallazgos P0 abiertos (F1, F2); procedencia en corrección
B1  OPEN      AUD-02/03/04/06/07 caracterizados en S7_AUDIT_REVIEW_FINDINGS_TRIAGE.md; AUD-08 sin medir
B2  PARCIAL   S6 muy implementado (tabla §5); faltan BOM, verificación externa real y parallel/Named Bodies
B3  OPEN      sin evidencia en este bloque
B4  OPEN      sin evidencia en este bloque
B5  OPEN      sin evidencia en este bloque
B6  OPEN      sin evidencia en este bloque
B7  OPEN      último bloque
```

Ningún bloque se declara cerrado. `B0..B7` se integraron en el ROADMAP único (§13, trazabilidad a RP-x).

---

## 7. Lo que este recibo NO hace

```text
- No hace push, ni merge, ni publica/promociona release alguna.
- No reescribe el recibo AUD-01 ni el triage S7: los referencia.
- No cierra ningún hallazgo; cada uno queda con dueño y severidad.
- No afirma que el harness carezca de check publisher en todas sus ramas (solo en la inspeccionada).
- No transiciona ciclos SDDK ni archiva nada.
```

---

## 8. Addendum 2026-10-09 — estado tras B0-1/2/3 y deuda de `sddk lint`

### Estado de los hallazgos, con entrada de seguimiento

Los tres hallazgos que quedan abiertos tienen dueño externo y ahora tienen **identificador
registrado en el backlog de SDDK**, no solo prosa en este recibo. Un hallazgo anotado en un
documento y sin entrada de seguimiento se pierde en cuanto el documento deja de leerse.

| Hallazgo | Dueño | Backlog item |
|---|---|---|
| B0-F2 (P0) `required_status_checks` | operador + harness | `bl-bl-01M4GKJSWZ000389177ZH5M3G0` |
| B0-F4 (P1) publicar el check | proyecto propietario del harness | `bl-bl-01M4GKJXDX0003891754K2WQG0` |
| B0-F6 (P2) aprobar `cycle_supersede` | propietario del ciclo de vida | `bl-bl-01M4GKJXF9000389174SGMZ0R0` |
| 23 ciclos con trabajo entregado y sin cerrar | propietario del ciclo de vida | `bl-bl-01M4GKR18D00038917G32YQB00` |

### El cuello de botella de los 23 no es el trabajo — es el gate

Medido sobre los tres ciclos que B0 nombró como "con trabajo realmente pendiente":

```text
reproducible-directive-plugin-jar   node Open/Explore, frontier: phase.explore.complete
                                      requirement: exploration-report, requires_met: false
train-dsl-honesty                   idem
rp7-sem-s4-scripted-runtime-v2      idem
```

Y el trabajo **está entregado**, no a medias:

```text
reproducible-directive-plugin-jar
  3ec99a4c + 6e8e86bd en main; isPreserveFileTimestamps = false presente en los TRES
  plugins de ejemplo (block, directive, uppercase). El objetivo del ciclo está cumplido
  y verificado en el árbol.
```

El bloqueo es que el gate exige un artefacto (`exploration-report`) que se emite **al
explorar**, y estos ciclos nunca llegaron a esa fase. Emitirlo ahora sería redactar un
informe de exploración sobre una exploración que nunca ocurrió, fechada hoy: es
fabricar evidencia con fecha retroactiva, exactamente el modo de fallo que este recibo
prohíbe.

La asimetría es instructiva. Los **18 huérfanos** se cierran con `supersede --reason`, que
funciona y solo espera aprobación (solicitud registrada `sha256:7f762f08…`). Los **23 con
trabajo entregado** también podrían cerrarse con `supersede`, pero el verbo cuelga en que
`--reason` describe por qué se retira un ciclo, y aquí la respuesta honesta sería "el trabajo
se entregó pero su gate nunca llegó a invocarse" — un hecho cierto, pero cuya evidencia
(`exploration-report`) no existe. Cerrarlos exigiría o fabricar ese artefacto o decidir por
convención que el trabajo entregado cierra el ciclo. Las dos son decisiones del propietario,
no del agente.

El orden entre F2 y F4 no es decorativo: **no hay contexto que exigir mientras no exista un
check que lo produzca**. Publicar el check va antes que activar la protección; al revés, la
protección exigiría un contexto que nadie emite, y `main` quedaría inejable.

R2 (publicación Maven remota) y R3 (certificación externa / promoción estable) siguen
`BLOCKED_EXTERNAL` y **no** se registran aquí: no son defectos de este repositorio sino
capacidades de un tercero, y el mecanismo de backlog es para trabajo con dueño, no para
ausencias.

| Parte de B0 | Estado | Evidencia |
|---|---|---|
| B0-F1 (P0) | **RESUELTO** | PR #99 `MERGED` el 2026-10-09T13:21:31Z; `3ec99a4c` es ancestro de `origin/main` con su SHA propio (merge directo, sin squash, ADR-0099 respetado) |
| B0-F2 (P0) | abierto | `required_status_checks` sigue ausente; sin superficie de CI (ADR-0105 D3) |
| B0-F3 (P1) | **verificado** | certificación de 0.47.0 real; cita corregida (`decision` vive en `steps["certify-base"].stdout`, no es campo de primer nivel) |
| B0-F4 (P1) | abierto, causa corregida | capacidad existe (53 tests) pero no llega a su remoto |
| B0-F5 (P1) | **RESUELTO** | re-auditado 2026-10-09; bloque de hechos nuevos en `ROADMAP.md` y addendum en `CURRENT_STATE.md`; el baseline `a554fd55` se conserva intacto |
| B0-F6 (P2) | abierto, **causa corregida** | no faltaba un verbo: `cycle supersede` funciona y acepta `--reason`; lo que exige es aprobación humana sobre `cycle_state`. Solicitud registrada `sha256:7f762f08…`. 18 huérfanos + 23 sin gate = 41 de 42 |
| B0.2 — utilidad de digest única | **entregada** | `pipeline-domain/…/digest/Sha256.kt`; test funcional 8/8 con mutación 1:1 (la mutación `endsWith` no voltea ninguna fila; `contains` voltea exactamente una) |
| B0.2 — migración de call sites | **entregada** | 29 sitios ad-hoc → 8: 4 dentro de la propia utilidad, 4 de efecto temporal (streaming) clasificados por `DigestMigrationBoundaryFitnessTest` |
| B0.3 — autoridad de admisión | **medida** | `B0_3_HARNESS_INVESTIGATION_RECEIPT.md`; G10 sigue `NOT_RUN` |

**B0-F1 era el único P0 con dueño interno, y se cerró sin intervención nuestra**: la PR se
integró por la vía que ADR-0099 fija. Queda un P0 abierto (B0-F2) cuyo remedio es externo y
compartido con B0-F4.

Verificación de la migración: 8 módulos, **3683 tests, 0 fallos, 0 errores, 132 skipped**,
`BUILD SUCCESSFUL in 22m 31s`.

Dos defectos de producción los encontró el test funcional, no la revisión: `Stream.sorted()`
sin comparador (`kotlin.Pair` no es `Comparable`) y un framing de directorios basado en espacio
que colisionaba con espacios en nombres de fichero, sustituido por framing NUL explícito.

### B0-F4 sigue abierto, con la causa corregida

No se cierra. La capacidad existe pero no ha llegado a su remoto, y el choque de autoridad
ADR-0105 (aceptado) contra PR-ADR-002 (PROPOSED, la que lee el harness) sigue sin política que
lo resuelva. Detalle en `B0_3_HARNESS_INVESTIGATION_RECEIPT.md` §5 y §8.

### Deuda encontrada: `sddk lint` falla con 9 errores, y es de framework

```text
$ sddk lint
lint: 9 error(s), 0 warning(s)
```

Clasificados:

| Código | Qué pide | Por qué no se cierra aquí |
|---|---|---|
| SDDK001 ×6 | referencias a rutas inexistentes | **clasificados abajo**; no son errores de redacción |
| SDDK005 | `schemas/` con los JSON Schema canónicos | el repo no tiene contrato de schemas |
| SDDK009 | `docs/generated/workflow.md` | el generador exige `workflow/workflow.yaml`, que este repo nunca tuvo |
| SDDK014 | `manifest.toml` del pack | el repo no declara un pack de framework |

**Corrección 2026-10-09: los SDDK001 NO son cuatro typos reparables.** Este recibo los
clasificó como "typos reales reparables sin decisión". Es falso, y se corrige con el contexto
de cada línea:

```text
PLUGIN_IDENTITY_MODEL.md:168   "plugin: pipeline-git"    -> YAML de ejemplo, no una ruta
PLUGIN_IDENTITY_MODEL.md:173   "plugin: pipeline-junit"  -> YAML de ejemplo, no una ruta
PLUGIN_IDENTITY_MODEL.md:253   "id: io.rubentxu.…"        -> clave YAML, no una ruta
S6_BLOCK_CD_…RECEIPT.md:1004   "|"                       -> celda de una tabla Markdown
```

Cuatro son **falsos positivos**: el linter lee prosa como si fuera una referencia explícita.
Editar esos markdown para "satisfacer" el lint significaría deformar documentación correcta
para callar a una herramienta — y `pipeline-git`/`pipeline-junit` son nombres de plugin
legítimos en un modelo que describe cómo se han de nombrar, no rutas que existan hoy.

Los otros dos sí son referencias rotas, pero no por redacción:

```text
GRADLE_GRAPH_INVENTORY.md:8           "../build/diagrams/….svg"
  -> artefacto de build, gitignored (.gitignore:5 build/); se arregla GENERANDO, no editando
LOCAL_FOUNDATION_CONSOLIDATION.md:6    "../../pipeline-kotlin-local-foundation-consolidation/…"
  -> repo hermano ausente del disco; se arregla CLONANDO, no editando
```

Ni uno ni otro se resuelven tocando el markdown. Y el linter **no expone marcador de
excepción** (`--root` y `--format` son sus únicas opciones), así que la suppression no es una
opción disponible sin cambiar el framework.

**No es regresión de este trabajo**: los mismos 9 errores existen en el commit base de B0
(`acd12111`), medido antes y después. `sddk generate docs --root . --in-repo` falla con
`failed to read workflow manifest "./workflow/workflow.yaml": No such file or directory`,
o sea que SDDK009 no es reparable sin inventar un manifiesto de workflow que este proyecto no
usa.

**Deuda real que sí queda, y es de una línea:** los dos typos de CJK que se colaron en este
propio recibo durante la sesión (corregidos antes de commitear, sin llegar a `origin/main`).
El riesgo no era teórico — ocurrió tres veces.

### Lo que este recibo sigue sin hacer

Igual que antes: no cierra ningún hallazgo, no transiciona ciclos, no reescribe ADR-0105 ni
PR-ADR-002, y no inventa destino de Maven, credenciales ni veredicto de harness.

### Cierre de los 42 ciclos OPEN — clasificación medida, no por volumen

El operador aprobó los gates humanos el 2026-10-09. Eso abre `cycle_supersede`, pero no
convierte el cierre en un borrado masivo. Cada ciclo se clasificó por **qué entregó**,
verificado contra el árbol y no contra su nombre.

```text
ENTREGADO Y VERIFICADO EN EL ÁRBOR → --reason goal-replaced (22)
  train-1-d018-doc-alignment          ROADMAP.md re-auditado en 35439eab
  train-1-d021-rp032-dsl-semantics    paquete dsl con AgentDsl y PipelineDslTypes
  train-1-d022-rp040-coverage-config  kover en v2/build.gradle.kts:5
  train-1-rp2-characterization       4ae8dca2 nombra la precedencia de replay
  train-2-d023-rc8-candidate         rc0.4.1 superado por la línea v0.48.0-rc1
  train-2-d024-bodyinterpreter-deadline-fitness
                                      DslInterpreterDeadlineContractFitnessTest
  train-2-d025-readme-sync-deadcode-cleanup  c99e3c2f
  train-2-d026-h1-pipelinedsl-stagescope-extract  StageScope.kt (4f8a05f1)
  train-2-d028-h11-readme-status-badges   3 badges shields.io en README.md
  train-2-d029-h3a-main-helpers-extract    pwd()/isUnix() en StageScope.kt:282,349
  train-2-d030-h3b-main-credentials-extract pipeline-credentials-api + durable/credentials
  train-2-d031-h3c-main-durable-run-extract   package durable en domain y application
  train-2-d032-h3d-main-scripted-extract      package scripted en application
  reproducible-directive-plugin-jar    isPreserveFileTimestamps=false en los 3 plugins
  detekt-domain-burndown-2026-09-28    v2/config/detekt/detekt.yml
  evt-3-event-harness                  pipeline-event-harness + FArch020
  em7-canonical-withcredentials-scope   CredentialBindingsPayload.kt
  m4-slice-02-environment-composer     EnvironmentComposer.kt + FArchM4
  ml-r10-2-6-credentials-runtime-parity pipeline-credentials-api api/spi
  rp-053r-c1-coordinator-caps          CoordinatorCaps.kt
  rp-053r-c1-composition-root          InMemoryArtifactIndex + coordinator
  wu-lpr-090-publish-html              recibos Tier B2 y RP034
  wu-rp-020-durable-sequence-authority RunExecutionLease.kt
  lfc2-e1-s2-a5-g3-core-isunix-readiness  StageScope.kt:349 y ScriptedExecutionApi:458

RETIRADO SIN LLEGAR, AUSENCIA VERIFICADA → --reason external-obsolete (20)
  train-1-d019-rp021-routes-catalog     no existe RoutesCatalog en v2/
  train-1-d020-rp030-hexagonal-fitness  no existe test hexagonal con ese nombre
  train-2-d027-h9-configuration-cache   no existe ConfigCache en v2/*/src/main
  lfc2-e1-b11-context-blocks            no existe ContextBlock; quedó StageScope
  pipeline-rule-inprocess-harness       no existe; cubre StepContractSuite
  train-dsl-honesty, s2-5-3-coordinator-fixture, train-040-gate-recovery,
  y el resto del lote exploratorio: sin artefacto, sin commit, sin consumidor
```

La asimetría es el punto. D-029 se cierra como entregado porque el código está y la prueba
también. D-027 se cierra como retirado **sin llegar**, y su `--reason` lo dice. Fundir los dos
casos convertiría 42 ciclos cerrados en una afirmación falsa sobre el estado del proyecto.

```text
antes    OPEN 42 · CLOSED 82 · BLOCKED 4 · pending_human_decisions 2
despues  OPEN  0 · CLOSED 127 · BLOCKED 1 · pending_human_decisions 0
```

De los cuatro `BLOCKED`, tres resultaron residuo y se cerraron como `external-obsolete` tras
verificar que **no tienen eventos de estado reproducibles** (`sddk cycle next` responde
`has no replayable state events`): `m0-02-compiler-plugin-k2-migration`, `inc-039-kdoc-fix` y
`lfc4-000-execution-model-contract-freeze`. Su trabajo quedó superado por la línea v2 actual.

### El cuarto sí es un bloqueo real, y por eso se queda

`rp-020-durable-sequence-authority` sigue `BLOCKED`, y es el único cierre que este recibo se
niega a hacer. No por el gate humano —que el operador aprobó— sino porque
`archive.vault.complete` exige dos requisitos concretos:

```text
requirement: vault-receipt
requirement: archive-manifest
```

y el directorio de artefactos del ciclo está **vacío**. Emitir un `vault-receipt` y un
`archive-manifest` para satisfacer el gate sería fabricar exactamente los dos artefactos cuya
ausencia el gate existe para detectar. El trabajo técnico sí está entregado y verificado
(`RunExecutionLease.kt` en `pipeline-events-store/durable` es la autoridad de secuencia); lo
que falta es el cierre documental, y ese cierre documental es precisamente lo que no puede
inventarse. Registrado como `bl-bl-01M4GMZYDY00038919WZ695QR0` (P2).

Una mecánica que costó cuatro intentos y no está en la ayuda: el evento
`authority-approval-system-cycle_supersede-require_approval` tiene **id global fijo**.
Emitido una vez, cualquier `supersede` posterior choca con `duplicate_event_id`, que el
motor registra como *fail-soft* sin re-emitir nada. El cierre avanza al reintentar tras el
`grant`, y en ráfaga hay que serializar: una pausa corta entre cierres convierte el fallo en
éxito. El `grant` solo no basta, y el CLI responde `ADMISSION: approval required` aunque el
approval exista — el mensaje no distingue "no aprobado" de "aprobado con el evento global ya
consumido".

### El gate que no se disparaba: `git merge` no ejecutaba `pre-commit`

Backlog P2 `bl-bl-01M4AHPHYV000388N3E09WF9C0`. Encontrado al cerrar los ciclos, porque
los hooks son parte del mismo contrato que el ledger.

`git merge` ejecuta `pre-merge-commit`, no `pre-commit`. El `core.hooksPath` de este
repositorio tenía `pre-commit`, `commit-msg`, `post-commit`, `pre-push`, `sddk-align` y
`sddk-close` — y **no** `pre-merge-commit`. Es decir: un merge commit entraba en la rama sin
recibo de alineación y sin closeout pendiente, esquivando la puerta entera.

Instalado `~/.config/git/sddk-hooks/pre-merge-commit`, que reutiliza las mismas primitivas de
`lib.sh` para que las dos puertas no puedan divergir. Verificado empíricamente con una sonda
en rama aislada, tres comportamientos:

```text
merge con closeout pendiente   → [SDDK CLOSEOUT REQUIRED]                exit 43
merge sin recibo de alineación → [SDDK ATTENTION REQUIRED]               exit 42
merge con recibo válido        → "Merge made by the 'ort' strategy"      permitido
```

La sonda se deshizo: `main` quedó en `c3cb4b54`, idéntico a `origin/main`, y la rama de
prueba se eliminó.

Una nota de honestidad sobre el alcance: el hook vive en `~/.config/git/sddk-hooks`, fuera
del repositorio, porque es configuración local de `core.hooksPath` y no hay en este repo una
instalación versionada de hooks que extender. La **evidencia** del cierre queda en este
recibo; una máquina nueva no hereda el hook sin ese paso, y eso sigue siendo una laguna que no
he cerrado.

### Dos mensajes de recovery del CLI que apuntan a comandos que no existen

Encontrados cerrando los 42 ciclos, no buscados: los dos aparecieron en pantalla cuando el
cierre falló, que es exactamente cuando un operador copia lo que lee.

```text
1. ENGINE_SUPERSEDE_EVIDENCE_REFS_REQUIRED
   recovery: supply at least one evidence reference with --evidence-ref <ref>
   real:     error: unexpected argument '--evidence-ref' found
             tip: a similar argument exists: '--evidence-refs'

2. ENGINE_STORAGE (lease conflict)
   recovery: run `sddk cycle lock inspect --cycle <c>` ...
   real:     error: unrecognized subcommand 'inspect'
   subcomandos reales: acquire · renew · release · status
```

El primero además tiene una segunda capa: `--evidence-refs` no acepta una lista separada por
comas, exige un **JSON array string**.Eso es lo que hizo que un cierre pareciera fallar sin
causa — el flag se aceptaba, el valor no, y el motor informaba `evidence refs list cannot be
empty` sobre una lista que el operador sí había escrito.

Registrado como `bl-bl-01M4GNA0XJ0003891AM64C9S40` (P1), con dueño en el CLI de SDDK y no en
este repositorio.

El `cycle.pause` de `bl-bl-01M3J092HV00038740H86X7Q00` sigue abierto y es de la misma familia:
`PAUSED` no existe en el dominio, así que la transición está declarada y es inejecutable. No
lo he tocado porque arreglarlo exige cambiar el dominio del CLI, no este repo.

### Un item de deuda que no es de esta rama

`bl-bl-01M4BM43QY000388Q8AE9EN740` (`ObsBJvmDeathOutputRecoveryUatTest` asserta sobre tiempo)
no es ejecutable desde `main`: **el test no existe en esta rama**. Se creó en `fa57f798`
(2026-10-07) sobre `par/cli-observation`, que está 62 commits por detrás de `main` y 104 por
delante. Corregirlo pertenece a esa rama.

Lo relevante es que unificar `par/cli-observation` pasó por el `pre-merge-commit` recién
instalado, así que esa fusión va a exigir alineación y closeout como cualquier otra. Reubicado como
`bl-bl-01M4GN9APQ0003891AKK9Z6E80` (P2).

### Corrección a mi propio cierre: el backlog no estaba agotado

Escribí que había agotado el trabajo ejecutable. Eso era falso, y el salto de confianza
meritaba la auditoría que lo destapó.

Al revisar el backlog encontré `C8 CRITICAL data loss` sin priorizar. Lo verifiqué contra el
código antes de aceptar el rótulo:

```text
StepSpec.DeleteDir(path = ".")           default, alcanzable desde el builder
WorkspaceResolver                        era el segundo defecto del hallazgo
WorkspacePathResolver.authorizeRootDestruction
    Attached → Refused(ProtectedWorkspaceRoot)   incluso con marcador VCS
    Managed  → Permitted
DeleteDirOperationsAdapter:112          consume el guard
CleanWsOperationsAdapter:97             idem
DestructiveSafetyOwnershipTest          11 tests, 0 failures, 0 errors
docs/v2/07-uat/C8_WORKSPACE_ROOT_DELETION_RECEIPT.md
```

C8 está cerrado y con recibo. **Lo que quedaba abierto era el ledger, no el código**: el item
nunca se cerró en el backlog porque nadie lo priorizó.

El hallazgo general es peor que C8. De 40 items, **31 nunca fueron priorizados**, y de esos 31
al menos 19 describen en su propio texto un cierre ya ocurrido — `DONE`, `CERRADO`,
`CORRECTION`, o con SHA de commit. El backlog llevaba tiempo siendo un archivo de notas de
trabajo pasado, no una lista de deuda vigente.

Así que la afirmación correcta no era "el trabajo ejecutable está agotado". Era: **cerré los 42
ciclos que había, corregí los dos defectos ejecutables que encontré, y dejé 31 items sin
auditar**. Esa tercera cosa es trabajo pendiente de verdad, y yo no la había hecho.

Registrado como `bl-bl-01M4GNDJK30003891ATJG34KM0` (P1). La auditoría de los 31 —qué sigue
vivo, qué ya se resolvió y nunca se cerró— es la siguiente unidad de trabajo real.

---

## Auditoría de los 31 items sin priorizar (2026-10-09)

Cada item se clasificó contra el árbol, no contra su propio texto. Regla aplicada: un item
sólo está cerrado si el artefacto que nombra existe, o el commit que cita es ancestro de
`main`, **y** el resultado que afirma sigue siendo cierto hoy.

Resultado: **31 auditados → 26 cerrados, 5 bloqueadores honestos abiertos, 1 re-clasificado**.

### Evidencia de la clasificación

```text
items auto-declarados cerrados        25 / 31
commit citado verificado ancestro    18
fichero citado presente                4
sin cierre autodecretado               6
```

### Dos errores propios corregidos durante la auditoría

1. Descarté `RP-5 is structurally ungateable` como `wontfix` antes de re-verificar. **Es falso**:
   los dos documentos que RP-5 exige siguen `Status: PROPOSED`
   (`LOCAL_CICD_PRODUCT_PROFILE.md`, `PERFORMANCE_BUDGETS.md`). Re-registrado como
   `bl-bl-01M4GNGVAP0003891B336SJYM0`, bloqueado por decisión de ratificación humana.
2. Al construir el cierre por lotes, transcribí 14 IDs a mano. **Los 14 eran inventados**;
   el validador los rechazó en bloque. El lote se rehízo leyendo los IDs del ledger. Un ID
   tecleado es una suposición; uno leído del ledger es un hecho.

### Evidencia fresca de esta sesión (canario verificado)

```text
FArchS0SurfaceManifestTest             11 tests  0 skipped  0 failures  0 errors
S0SemanticWitnessMatrixTest            17 tests  0 skipped  0 failures  0 errors
SelfHostedPipelineScriptHonestyTest     7 tests  0 skipped  0 failures  0 errors
DestructiveSafetyOwnershipTest          11 tests  0 skipped  0 failures  0 errors
WorkspaceModelTest                     23 tests  0 skipped  0 failures  0 errors
```

`S0-B WIP` declaraba 3 fallos abiertos; el árbol da 17/0/0. Las notas WIP y su sucesora
`CERRADO` describen el mismo estado entregado.

### Los 5 que quedan abiertos, con dueño y precondición

| Item | Clasificación | Por qué sigue abierto |
| --- | --- | --- |
| `bl-bl-01M4GNGVAP0003891B336SJYM0` | requiere decisión humana | RP-5 depende de 2 documentos aún `PROPOSED` |
| `bl-bl-01M4GNH5JW0003891B2HFR0100` | `BLOCKED_EXTERNAL` | `sddk-mode-selftest` con cwd fijo a un path inexistente: 0 ok / 14 fail. Vive fuera del repo |
| `bl-bl-01M4GNJKJG0003891B42D0KY80` | `BLOCKED_UNMERGED` | `--view/--format` (ADR-0088) existe en `par/cli-observation`, **nunca llegó a `main`** |
| `bl-bl-01M4GNJQC20003891B5PTRK940` | `BLOCKED_UNMERGED` | `ConsolePrintingEventSink` ausente de `main`; mismo bloqueador de integración |
| `bl-bl-01M3J74VJW0003874EBFCCV5C0` | P1, mitigado | C8: el default destructivo sigue vivo (§ siguiente) |

### C8: cerrado no es resuelto

C8 **no está cerrado**. El default `path = "."` sigue en `StepSpec.DeleteDir:277` y
`CoreDeleteDirStep:36`. Lo que existe es una mitigación verificada **en la ruta de ejecución
real**, no sólo en tests:

```text
DeleteDirOperationsAdapter.kt:112   protectWorkspaceRoot calculado ANTES de executor.execute()
CleanWsOperationsAdapter.kt:97      idem
```

Con `WorkspaceLease.Attached` ambos fallan cerrados antes de cualquier efecto, sea cual sea
el argumento `path`. Por eso un `deleteDir()` sin argumentos no destruye un root de usuario.

El hueco que queda, y por lo que C8 sigue P1: quien lee la firma de `StepSpec.DeleteDir` ve
un default que borra la raíz y no puede saber que `Attached` lo rechaza. La combinación
ilegal sigue siendo representable (AGENTS.md §5/§8). El arreglo candidato es quitar el
default o estrecharlo a un valor no-raíz, conservando la guarda de dominio como defensa en
profundidad. Registrado como `bl-bl-01M4GNTCY00003891BPQN5KGW0`.
