# WU-RP-040-R3.4 PLAN — Dependency audit CI job (gradle dependency-submission)

**Estado:** CLOSED. **Tipo:** CI-only. **Base:** `wu/rp-053-followup-workspace-mode @ 87f40ffa`.
**Commit:** `5039e43a` (on top). **Receipt:**
[WU_RP_040_R3_4_DEPENDENCY_AUDIT_RECEIPT.md](../../07-uat/WU_RP_040_R3_4_DEPENDENCY_AUDIT_RECEIPT.md).

## Razón de existir

El plan R3 de [WU_RP_040_PLAN.md](../WU_RP_040_PLAN.md) menciona
explícitamente `dependencySubmissions + GitHub Dependabot` como una de las
dos opciones para dependency-audit en CI. Antes de este WU:

- Dependabot version-update (`dependabot.yml`) sí funcionaba (parsea
  `*.gradle.kts` directamente).
- Dependabot security alerts NO funcionaba (requiere submission del grafo
  resuelto a la dependency-graph API de GitHub).

Resultado: la pestaña "Security → Dependabot alerts" del repo quedaba en
blanco aunque el código tuviese vulnerabilidades conocidas en
dependencias transitivas (no era una vulnerabilidad silenciosa; era
incapacidad de evaluar).

## Estrategia

`gradle/actions/dependency-submission@v3` (action oficial GH). Por cada
proyecto del monorepo, corre `./gradlew :app:dependencies`, parsea el
grafo resuelto (versiones exactas, transitivas, BOM-resolved) y lo POSTea
a la dependency-graph API del SHA en cuestión.

| Característica | Valor |
|---|---|
| Coste de runtime | Bajo (~30s extra de build por `gradle help` + submission) |
| CVE evaluation | NO (alimenta a Dependabot, no evalúa) |
| Coste de red externo | Cero (solo POST a API GH) |
| Self-hosted runner | Compatible (mismo patrón que `sbom`) |
| Authorization | `contents: write` (default en jobs de `main` + self-hosted) |

## Por qué NO OWASP `dependency-check`

Ver
[WU_RP_040_R3_4_DEPENDENCY_AUDIT_RECEIPT.md § Decisión](../../07-uat/WU_RP_040_R3_4_DEPENDENCY_AUDIT_RECEIPT.md).
Resumen:

1. HTTP 403 risk en cold-cache runs (lección WU-RP-051-bis).
2. Coste de NVD feed (cientos de MB).
3. Ya tenemos `dependabot.yml` que evalúa contra GH Security Advisories.

## Cambios (delta acotado)

**`v2/.github/workflows/lpr0-ci.yml`** — un nuevo job `dependency-audit`
al final del workflow:

- 53 líneas nuevas (incluyendo 18 líneas de documentación inline).
- Patrón idéntico al job `sbom` (self-hosted, temurin-21, warm-cache).
- Cache key independiente `lpr0-deps-`.
- Step extra: `./gradlew help` para resolver plugin classpath.
- Step core: `gradle/actions/dependency-submission@v3` con
  `gradle-project-root-path: v2`.

Cero cambios en producción. Cero cambios en `*.gradle.kts`. Cero nuevos
plugins. Cero nuevos SHA pins (la action es el tag oficial `v3`).

## Verificación

| Check | Cómo | Resultado |
|---|---|---|
| YAML válido | `python3 -c "import yaml; yaml.safe_load(...)"` | OK |
| Action lint (shellcheck) | `actionlint lpr0-ci.yml` | Pre-existing warning línea 142, no introducido |
| Gradle help | `./gradlew -q help` local | OK en 1.6s |
| CI run del job | Push a `main` (rule 6: bloqueado sin operador) | NOT_RUN |

## Lo que queda abierto (ratchet explícito)

- [ ] CVE evaluation offline (OWASP dependency-check) — WU futuro cuando se
      resuelva feed-cache de NVD. Marcar como WU-RP-040-R3.4b si se prioriza.
- [ ] Secret scan (gitleaks) — R3 del plan sigue abierto; no en alcance de
      este WU. WU-RP-040-R3-SC recomendado.
- [ ] Alertas Dependabot en `Security` tab — requiere push + CI verde con
      la action autorizada, lo cual es gate de release.
