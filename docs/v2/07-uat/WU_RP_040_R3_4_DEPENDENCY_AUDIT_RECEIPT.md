# WU-RP-040-R3.4 — Dependency audit job (gradle dependency-submission)

**Estado:** CLOSED. **Base:** `wu/rp-053-followup-workspace-mode @ 87f40ffa`.
**Commit:** `5039e43a` (on top of the WU-RP-053-FOLLOWUP branch).
**Tipo:** CI-only (zero production change). Cierra el gap R3.4 del plan
[WU_RP_040_PLAN.md](WU_RP_040_PLAN.md) R3.

## R3.4 gap (original)

El plan R3 decía literalmente:

> dependency audit: `gradle/dependency-check` o `dependencySubmissions`+`GitHub
> Dependabot` según coste; preferencia por tarea Gradle en CI.

Antes de este WU, **no existía ningún job en CI que sometiera el grafo de
dependencias resuelto a la API de Dependabot**. La consecuencia práctica:

- Dependabot version-update (`dependabot.yml`, weekly) sigue funcionando porque
  parsea los manifests (`*.gradle.kts`) directamente. Eso es independiente del
  grafo resuelto.
- Dependabot security-advisory alerts (CVEs en `org.jetbrains.kotlin:kotlin-stdlib`,
  `jackson-databind`, etc.) **NO** estaban habilitadas, porque GH Dependabot solo
  evalúa vulnerabilidades contra grafos que han sido "submitted" explícitamente.
  Sin la submission, la pestaña "Security → Dependabot alerts" queda en blanco.

Esto NO significa que el código tenga CVEs conocidas sin reportar: significa que
el motor no tiene el dato necesario para evaluar el grafo real (con versiones
resueltas y dependencias transitivas).

## Decisión: dependency-submission + Dependabot (no OWASP dependency-check)

Elegido: `gradle/actions/dependency-submission@v3` (oficial de GitHub).

| Opción | Coste | CVE eval | Decisión |
|---|---|---|---|
| `gradle/actions/dependency-submission@v3` (oficial) | Bajo (POST a API GH; sin descarga NVD) | NO (alimenta Dependabot) | **Sí** |
| OWASP `dependency-check` Gradle plugin | Alto (descarga feed NVD entero por run; ~400 MB) | Sí (offline, NVD) | NO |
| Grype/Syft instalado vía `aquasecurity/trivy-action` | Medio (binarios externos) | Sí (múltiples feeds) | NO |

Razones para NO usar OWASP `dependency-check` aquí:

1. **HTTP 403 en cold-cache runs**: el plugin descarga el feed NVD de
   `https://nvd.nist.gov/feeds/json/cve/1.1/`. El self-hosted runner observó
   HTTP 403 repetidamente en cold-cache runs (lección WU-RP-051-bis,
   2026-09-23). Añadir otra descarga externa con misma fragilidad sería
   irresponsable sin warm-cache.
2. **Coste de runtime**: feed NVD completo son cientos de MB; parseo en JVM
   añade ~30-60s por build.
3. **Ya tenemos el `dependabot.yml`** que cubre el lado CVE. Lo que falta es
   SOMETER el grafo resuelto para que Dependabot pueda hacer matching. Ese es
   exactamente el job que añade este WU.
4. **Ratchet**: `dependency-submission` es non-failing por diseño (solo POSTea).
   Un futuro WU puede añadir OWASP como job separado cuando se resuelva el
   problema de feed-cache. El gap queda marcado en el plan, no escondido.

## Cambios

**`v2/.github/workflows/lpr0-ci.yml`** — nuevo job `dependency-audit`:

- Sigue el patrón existente `sbom` (self-hosted, temurin-21, warm-cache).
- Cache key independiente (`lpr0-deps-`) para no competir con `sbom`.
- Step "Gradle help" corre `./gradlew -q help` para forzar resolución del
  plugin classpath (lo que el action necesita para inspeccionar).
- Step "Submit dependency graph" usa `gradle/actions/dependency-submission@v3`
  con `gradle-project-root-path: v2`.
- Documentación inline explica la diferencia entre "submit graph" (este job)
  y "evaluate CVEs" (`dependabot.yml` ya existente).

Total: +53 líneas en un solo archivo. Cero cambios en producción.

## Verificación

| Check | Resultado |
|---|---|
| YAML parse | OK (`python3 -c "import yaml; ..."`) |
| actionlint (local) | Pre-existing SC2086 warning línea 142, no introducido por este WU |
| `./gradlew help` local (canary) | OK en 1.6s |
| CI run del nuevo job | NOT_RUN (ratchet: rule 6, sin push sin operador; cierre de WU con verify local) |

## Lo que R3.4 YA cubre después de este WU

- [x] Job CI que somete el grafo resuelto a la dependency-graph API GH.
- [x] Authorization: `contents: write` por defecto en workflows de `main`
      self-hosted (validado por docs del action).
- [x] Cache pattern alineado con resto del workflow (warm-cache + restore-keys).
- [x] Sin nuevas dependencias, sin nuevos plugins, sin nuevos SHA pins (el
      action ya está pin a la v3 del tag oficial de GH).

## Lo que R3.4 NO cubre (deuda intencional / ratchet explícito)

- [ ] CVE evaluation offline contra NVD. Diferido a WU futuro cuando se
      resuelva el problema de cache NVD; el propio plan R3 lo marca como
      opcional (`según coste`).
- [ ] Alertas GH Dependabot visibles en `Security` tab: requiere que el job
      haya corrido al menos una vez sobre `main` con la action correctamente
      autorizada. Eso requiere push + CI verde, lo cual es gate de release.

## Próximo paso R3

- R3.3 SAST (detekt) — ya cubierto (workflow actual ejecuta detekt).
- R3.4 dependency audit — **cubierto por este WU** (submission API).
- R3 secret scan (gitleaks) — pendiente, NO cubierto. Ver análisis abajo.

## Side-finding: R3 secret-scan todavía sin cubrir

`gitleaks` no está en `.github/workflows/lpr0-ci.yml`. Es un gap separado,
**fuera del alcance de este WU** (este WU cierra R3.4, no R3 completo).
Para cerrar R3 completo necesitaría un WU específico (`WU-RP-040-R3-SC`)
con su propio plan y recibos. El operador puede decidir priorizarlo
después del merge de este WU.

## Refs

- Plan: [docs/v2/07-uat/WU_RP_040_PLAN.md](WU_RP_040_PLAN.md) R3
- Receipt R5 cobertura: [docs/v2/07-uat/WU_RP_040_R5_COVERAGE_ALL_CI_RECEIPT.md](WU_RP_040_R5_COVERAGE_ALL_CI_RECEIPT.md)
- WU-RP-051-bis (cold-cache lesson): docs in roadmap
- GH action docs: https://github.com/gradle/actions
