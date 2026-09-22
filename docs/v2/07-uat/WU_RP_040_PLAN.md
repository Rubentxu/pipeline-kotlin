# WU-RP-040 PLAN — Calidad transversal: cobertura, mutación selectiva, suministro

**Estado:** PLANNED (aprobado por operador, modo AUTO). **Base:** 49e42a56.
**Criterio roadmap:** cobertura por módulo con umbrales fundamentados por riesgo
(branch/line) sobre partes críticas; mutación selectiva de codecs/políticas;
exclusiones y @Disabled clasificados (nunca contar omitidos como PASS);
informes SAST/dependency audit/secret scan/SBOM; fijación de acciones por SHA.

## Rondas

### R1 — Cobertura + umbrales (Kover)
- Plugin `org.jetbrains.kotlinx.kover` vía catálogo de versiones (libs.versions.toml).
- Módulos críticos con umbrales branch/line fundamentados:
  - `pipeline-domain` (step/replay/retry policy, reconcilers): umbral ALTO.
  - `pipeline-step-sdk:api` (codecs, BlockStepFlattener): umbral ALTO.
  - `pipeline-events` (store/codec): umbral ALTO.
  - `pipeline-application` (coordinator glue): umbral MEDIO (gran parte es composición cableada por UAT de proceso real).
  - scripting/CLI: sin umbral duro inicial; reporte informativo.
- Exclusiones documentadas (generados, fixtures de proceso, main functions).
- Verificación: informe Kover + fallo intencional al bajar cobertura (canary).

### R2 — Acciones fijadas por SHA (supply chain)
- Pin por SHA (con comentario de versión) de: checkout, setup-java,
  wrapper-validation-action, upload-artifact y el resto de `uses:` del repo.
- Verificación: CI verde sobre el workflow pinchado.

### R3 — SAST / dependency audit / secret scan / SBOM
- dependency audit: `gradle/dependency-check` o `dependencySubmissions`+`GitHub Dependabot` según coste; preferencia por tarea Gradle en CI.
- secret scan: gitleaks action pinchada por SHA o tarea local en CI.
- SBOM: CycloneDX Gradle plugin (pinchado), artefacto subido por run.
- SAST: detekt/pushdoor según existente; si ya hay análisis, documentar y NO duplicar.
- Verificación: artefactos SBOM + informes por run de CI, receipts con SHA.

### R4 — Mutación selectiva (codecs/políticas)
- Objetivo acotado: StepCodecs + EffectReplayPolicy/RetryPolicy.
- Herramienta: pitest (gradle-pitest-plugin) solo en los módulos objetivo, budget de tiempo.
- Verificación: informe de mutación + mutantes sobrevivientes clasificados.

## Reglas vigentes aplicables
- Economía de Gradle (reglas 1-6), cero fabricación, no debilitar tests.
- Cobertura NO sustituye a las UAT obligatorias; es prevención de huecos.
- Cada ronda: evidencia local + CI del SHA antes de cerrar.
