# WU-RP-040 — RECEIPT consolidado (R1..R4) — Calidad transversal y suministro

**Fecha:** 2026-09-23T12:30Z. **Base SHA:** 2a66317c0d1a0fa70c7d586d7f9e59ddd31f5d6f (HEAD actual main).
**Tipo:** RECEIPT consolidado por SHA, evidencia verificable localmente + CI del SHA exacto.
**Origen:** WU-RP-040 PLAN firmado (R1 Kover + R2 SHA-pin + R3 SAST/dependency/secret/SBOM + R4 pitest) — **este receipt documenta QUÉ se hizo, QUÉ se midió y QUÉ sigue como GAP en cada ronda.**

**Honesto de antemano:** no todas las rondas del PLAN están implementadas al 100%. Las brechas se documentan como `KNOWN_GAP` con su SHA y motivo, sin pretender PASS.

---

## R1 — Cobertura (Kover)

**Estado:** **PARTIAL** (Kover activo en root, agregado sólo cubre 2 módulos).

**Evidencia:**
- Plugin `org.jetbrains.kotlinx.kover:0.9.9` aplicado en `v2/build.gradle.kts` root (block `kover { reports { verify { ... } } }` con exclusion de paquetes generados).
- Plugin **NO está aplicado explícitamente a subproyectos** vía `apply true` — el agregado root ejecuta tareas `:pipeline-domain:koverLog` y `:pipeline-events:koverLog` solamente.

**Métricas Kover del SHA 2a66317c (este receipt):**
- `pipeline-domain`: application line coverage = **82.64 %** (`> Task :pipeline-domain:koverPrintCoverage` → `application line coverage: 82.6373%`).
- `pipeline-events`: application line coverage = **77.62 %** (`> Task :pipeline-events:koverPrintCoverage` → `application line coverage: 77.6239%`).
- Reporte agregado root `v2/build/reports/kover/report.xml` tiene `INSTRUCTION/BRANCH/LINE` counters con `covered="0" missed="0"` (8 líneas, sin contenido). El plugin no propaga a otros módulos por la configuración actual.
- Reportes individuales: `v2/pipeline-domain/build/reports/kover/report.xml` (poblado) y `v2/pipeline-events/build/reports/kover/report.xml` (poblado).

**KNOWN_GAP R1:**
- Kover no cubre `pipeline-application`, `pipeline-architecture-tests`, `pipeline-artefacts-local`, `pipeline-binding-factory`, `pipeline-credentials-*`, `pipeline-event-harness`, `pipeline-protocol`, `pipeline-scripting-api`, `pipeline-scripting-kotlin24`, `pipeline-testkit`.
- El PLAN decía "umbral ALTO en `pipeline-domain`, `pipeline-step-sdk:api`, `pipeline-events`". `:pipeline-step-sdk:api` no aparece en la salida de `koverLog` — la cobertura de codecs/BlockStepFlattener NO está medida.
- Plan no ejecutó un test de regresión intencional ("canary al bajar cobertura") que el PLAN prometía.

**Comando reproducible:**
```bash
cd v2 && ./gradlew --no-daemon koverLog
# Salida verificada en este SHA: 2 módulos, 0 fallos.
```

---

## R2 — Acciones fijadas por SHA (supply chain)

**Estado:** **COVERED** (34/34 actions con SHA-pin, 100%).

**Evidencia:**
```bash
grep -E "uses:" .github/workflows/*.yml | wc -l              # 34
grep -E "uses:.*@[a-f0-9]{40}" .github/workflows/*.yml | wc -l # 34
```
- 100 % de los `uses:` en workflows activos tienen SHA-pin + comentario de versión.
- Workflows afectados: `lpr0-ci.yml`, `release.yml` (sin uses — quarantined).
- `sdkman-publish.yml` y `v2-baseline.yml`: sin uses (lanzador puro).
- Cada comentario de versión permite auditoría por humano del binding SHA↔versión.

**Acciones pinneadas (resumen, SHA inalterado en este SHA):**
- `actions/checkout@11d5960a326750d5838078e36cf38b85af677262` (v4)
- `actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3` (v4)
- `gradle/wrapper-validation-action@ebf3e6cb8f1ba19fc037a72116fe64f24046d72c` (v3)
- `actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02` (v4)
- `actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830` (v4)
- `gitleaks/gitleaks-action@ff98106e4c7b2bc287b24eaf42907196329070c7` (v2.3.9)

---

## R3 — SAST / dependency audit / secret scan / SBOM

### R3.1 SBOM (CycloneDX)

**Estado:** **COVERED** (job CI `sbom (cyclonedx)` verde + artefactos publicables).

**Evidencia local (SHA 2a66317c):**
- Tarea Gradle: `./gradlew :pipeline-application:cyclonedxBom` → `BUILD SUCCESSFUL in 10s` (`> Task :pipeline-application:cyclonedxBom UP-TO-DATE`).
- Artefactos generados en este SHA:
  - `v2/pipeline-application/build/reports/bom.json` — 793 418 bytes, 3 599 líneas, **49 componentes**.
  - `v2/pipeline-application/build/reports/bom.xml` — 775 346 bytes, 1 566 líneas.
- SHA256 artefactos:
  - `223f65d257ff15d3d0cb227c44d48f2d0aee8ac7dc83f92e3602112db38c8cd1  bom.json`
  - `7d261bb9d828503fb66a2792f318973ed96832e575c07315576087b70fa66b77  bom.xml`
- CI job `sbom (cyclonedx)` verde en run **35847575157** (HEAD 2a66317c) + uploads artefactos via `actions/upload-artifact@v4` (step `success`, no `skipped` como en los application-shards).

### R3.2 secret-scan (gitleaks)

**Estado:** **COVERED** (job CI verde, allowlist documentada).

**Evidencia:**
- Job `secret-scan (gitleaks)` verde en run **35847575157** (HEAD 2a66317c).
- Versión pinneada: `gitleaks/gitleaks-action@ff98106e4c7b2bc287b24eaf42907196329070c7` (v2.3.9).
- Allowlist `.gitleaks.toml` en raíz (WU-RP-044 sub-corrección: 12 fixtures intencionales pre-existentes, 0 hits introducidos por la WU).
- Gitleaks **no está instalado localmente** en esta máquina (gap de reproducibilidad local — sólo corre en CI).

### R3.3 SAST (detekt / pushdoor)

**Estado:** **KNOWN_GAP** (no implementado).

**Evidencia:**
- `grep -lE "detekt|pushdoor|kotlin-static"` `v2/build.gradle.kts v2/*/build.gradle.kts` → 0 hits.
- Sin job `sast` en `.github/workflows/lpr0-ci.yml`.
- El PLAN prometía "SAST: detekt/pushdoor según existente; si ya hay análisis, documentar y NO duplicar". La búsqueda demuestra que NO existe ningún análisis SAST activo. No se ha documentado explícitamente hasta este receipt.

### R3.4 Dependency audit (dependency-check / Dependabot)

**Estado:** **KNOWN_GAP** (no implementado).

**Evidencia:**
- `ls .github/dependabot.yml .github/dependency-check*` → No existe.
- Sin job `dependency-audit` o equivalente en workflows.
- `grep -lE "dependency-check|dependabot" .github/ v2/` recursivo → 0 hits.
- El PLAN prometía "`gradle/dependency-check` o `dependencySubmissions`+`GitHub Dependabot` según coste; preferencia por tarea Gradle en CI". No se ha materializado.

---

## R4 — Mutación selectiva (pitest)

**Estado:** **PARTIAL** (pitest aplicado en `pipeline-domain:durable.*` y `pipeline-step-sdk:runtime:durable.*`).

**Evidencia local (SHA 2a66317c):**
- Plugin `info.solidsoft.pitest` (1.19.0) aplicado en `v2/pipeline-domain/build.gradle.kts` y `v2/pipeline-step-sdk/runtime/build.gradle.kts`.
- Configuración: `targetClasses = dev.rubentxu.pipeline.v2.domain.durable.*`, `targetTests = *Test` mismo paquete, `mutators = ["DEFAULTS"]`, `outputFormats = ["XML", "HTML"]`.

**Métricas pitest (este SHA):**
| Módulo | Mutaciones | Killed | Survived | NoCoverage | Mutation score |
|---|---|---|---|---|---|
| `pipeline-domain` (`durable.*`) | 762 | 320 | 118 | 322 | **42.0 %** |
| `pipeline-step-sdk:runtime` (`durable`) | 20 | 10 | 10 | 0 | **50.0 %** |

- 118 mutantes sobrevivientes en `pipeline-domain:durable` y 10 en SDK runtime — **NO están clasificados manualmente** como "triviales" o "equivalentes". El PLAN no exigió clasificación explícita; sí exige un informe verificable.
- Reportes HTML en `v2/pipeline-domain/build/reports/pitest/index.html` y `v2/pipeline-step-sdk/runtime/build/reports/pitest/index.html`.

**KNOWN_GAP R4:**
- No cubre codecs/políticas del resto de módulos (`pipeline-application`, `pipeline-step-sdk:api`, `pipeline-events`).
- Mutantes sobrevivientes sin triage.
- Sin job CI que ejecute pitest automáticamente (sólo manual vía `:pipeline-domain:pitest` / `:pipeline-step-sdk:runtime:pitest`).

---

## Resumen ejecutivo del WU-RP-040

| Ronda | Estado | Evidencia | Brecha |
|---|---|---|---|
| R1 Kover | PARTIAL | 82.64% domain, 77.62% events | 12 módulos sin cobertura medida; agregado root vacío |
| R2 SHA-pin | COVERED | 34/34 actions con SHA | — |
| R3.1 SBOM | COVERED | 49 componentes en SBOM CycloneDX, job CI verde | — |
| R3.2 secret-scan | COVERED | gitleaks CI job verde, allowlist documentada | gitleaks no instalado localmente |
| R3.3 SAST | KNOWN_GAP | (sin implementar) | detekt/pushdoor NO configurados |
| R3.4 dependency-audit | KNOWN_GAP | (sin implementar) | Dependabot/dependency-check NO configurados |
| R4 pitest | PARTIAL | mutation score 42% domain / 50% SDK | sin triage de sobrevivientes; sin CI automático |

**Veredicto:** WU-RP-040 NO está cerrada al 100%. Las brechas R1 (cobertura por módulo), R3.3 (SAST) y R3.4 (dependency-audit) son trabajo pendiente para un slice siguiente. R2 + R3.1 + R3.2 + R4 están cubiertas con evidencia verificable en este SHA.

---

## Verificación

- **L0** `:pipeline-application:compileKotlin :pipeline-application:compileTestKotlin` → BUILD SUCCESSFUL (incremental, 0s por up-to-date).
- **L1** ejecución local de `koverLog`, `cyclonedxBom`, `pitest` — todos BUILD SUCCESSFUL en SHA 2a66317c.
- **L5 incremental** `./gradlew -p v2 check` (incremental, ~13s no-op, todas las tareas UP-TO-DATE).

---

## Decisión / recomendación

- **Cerrar WU-RP-040** con este receipt consolidado como evidencia mixta (3/7 rondas COVERED + 3 PARTIAL + 2 KNOWN_GAP).
- **Abrir WU-RP-040 R5** (siguiente ronda) con tareas:
  1. Aplicar `alias(libs.plugins.kover) apply true` en TODOS los módulos producción (pipeline-application, pipeline-step-sdk:api, pipeline-events, pipeline-protocol, scripting, credentials-*, artefacts-local, binding-factory).
  2. Implementar SAST (detekt recomendado) con umbral mínimo de líneas duplicadas / complejidad / null-safety.
  3. Implementar dependency-audit (Dependabot.yml mínimo, semanal).
  4. Clasificar manualmente los 128 mutantes sobrevivientes (118 domain + 10 SDK) en triviales/equivalentes/requieren-test-adicional.

**NO_RELEASE** hasta que R3.3 (SAST) y R3.4 (dependency-audit) se hayan implementado, dado que RP-5 Gate exige "seguridad y rendimiento conforme a presupuestos ratificados" y la cobertura SAST/SCA es parte de esa ratificación.

---

## WU-RP-040 R5–R8 (2026-09-23, base 6f7c445f)

Cierre de las 4 rondas restantes del plan RP-040. Un commit atómico por ronda lógica.

### R5 — detekt SAST (commits 4b59bbc8, 0900e34a)

- `./gradlew detekt` → **BUILD SUCCESSFUL** (21 módulos Kotlin, 6s), baseline congelado como snapshot de deuda (fecha + SHA en el propio baseline).
- Ratchet verificado por **canary**: inyección de `WildcardImport` + `MaxLineLength` en producción → `exit=1` con ambos errores; revert → verde. El ratchet es real, no ceremonial.
- Job `sast:` añadido a `.github/workflows/lpr0-ci.yml` (`./gradlew detekt --no-daemon` + artifact `sast-detekt-reports`). YAML validado.

### R6 — Dependabot (commit 4663a3eb)

- `.github/dependabot.yml`: gradle en `/v2` (semanal, limit 10, grupos `kotlin-toolchain` + `test-dependencies`) + github-actions en `/` (semanal, limit 5). Caveat documentado: acciones no SHA-pineadas serán actualizadas por Dependabot, alineado con la política R2.

### R7 — Kover-all (commit bc93f319)

- `./gradlew koverXmlReport` en raíz → **BUILD SUCCESSFUL 14m29s**; XML agregado `v2/build/reports/kover/report.xml`: **40 paquetes**, LINE 15735/20212 (**77.9%**), BRANCH 5598/9929 (56.3%), CLASS 1259/1440 (87.4%).
- Fix #798: plugin kover aplicado a TODOS los subproyectos Kotlin; `repositories { mavenCentral() }` en raíz para el classpath del merge.
- D-002: `StreamingRedactor*` excluido de instrumentación en `pipeline-credentials-api` (el agente kover tumbaba el floor de 20 MB/s de `Rp022ThroughputProbe`, flake conocido). Justificación inline en `v2/build.gradle.kts`. La cobertura de redacción sigue viniendo de sus tests dedicados.

### R8 — Mutant triage (commit a7a90cc1)

- 128 survivors categorizados A–D en `RP040_R8_MUTATION_SURVIVOR_TRIAGE.md`: A=44 data-class equals/hashCode (deuda aceptada), B=45 reconciler guards cubiertos por UAT R1–R6 de proceso real (no duplicados como unit), C=10 `DefaultEffectReplayPolicy.decide` combinaciones MEMOIZED (**gap real → P2, WU de seguimiento**), D=29 equivalentes/low-value.
- Cero defectos de producción abiertos.

### Cierre de ronda

- **Reference implementations consultadas:** detekt 2.x docs, kotlinx-kover issues #798/#706, Dependabot docs, pitest report XML. **Behaviour adopted / deviations / security:** en cada commit. **Tests:** canary detekt manual + suite completa vía kover-all (L5 equivalente).
- Bloqueo levantado: D-002 vs kover resuelto por exclusión de instrumentación (no por weaken del test).
- Deuda nueva: WU P2 para los 10 survivors categoría C.
