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

```text
harness evidence/dogfood/matrix.json -> version 0.47.0, zip_sha256 2fa2d272…, decision CERTIFIED
harness evidence/promotions/v0.47.0.json
  { ga_version: v0.47.0, rc_version: v0.47.0-rc3, certified_at: 2026-10-05T21:00:42Z,
    source_result_id: 8b8d25ddac4b420a, outcome: ALREADY_PROMOTED_IDENTICAL, assets 5 }
```

El zip estable publicado (`2fa2d272…`) coincide con el artefacto con `decision CERTIFIED` del
harness, y la promoción quedó registrada con su recibo. Es decir: **el problema de v0.47.0 no es de
certificación del artefacto, es de pertenencia al trunk (B0-F1) y de protección (B0-F2)**.
Nota: rc2 tiene otro zip (`02b1632e…`, `source_commit 5ea4137d`), así que hay al menos dos
artefactos `0.47.0` distintos y solo uno fue el promovido. `INFERRED` (por recibo + tag).

### B0-F4 (P1) — el harness todavía no publica el check de admisión que ADR-0105 le asigna

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

`docs/v2/00-context/CURRENT_STATE.md` (addendum 2026-10-01) declara:

```text
"Release: v0.46.0 ESTABLE"                     -> real: v0.47.0 es la Latest desde 2026-10-06
"Ciclos SDDK activos: exactamente uno
 (p-1f3622e11c093341/pr017-run-lifecycle-engine)" -> real: 42 ciclos OPEN
```

Y el encabezado de `docs/v2/05-roadmap/ROADMAP.md` declara baseline `main @ a554fd55` (2026-09-21)
y producto publicado `v0.39.0`, cuando el trunk real es `b66bf7c7` y el producto publicado es
`v0.47.0`.

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
este repositorio prohíbe. Los 18 huérfanos no los puede cerrar ningún verbo existente. Así que la
brecha queda registrada como **dependencia al proyecto propietario** (falta un verbo de mantenimiento
para retirar ciclos huérfanos o incompletos con razón explícita), no como una tarea local pendiente.
Decisión registrada en SDDK: `550ae52d-398d-44d5-a0f6-0386271340cf`.

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
