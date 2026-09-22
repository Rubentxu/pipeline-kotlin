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

## R4 RESULTADO (2026-09-22, SHA c74146e4+)

**Setup final:** pitest via gradle-pitest-plugin 1.19.0 (pluginManagement en settings.gradle.kts;
1.15 no soporta class-file major 68/JDK24: "Unsupported class file major version 68").

**Módulos objetivo y configuración:**
- `pipeline-domain`: paquete `domain.durable.*` contra `*Test` del mismo paquete.
- `pipeline-step-sdk:runtime`: `EffectReplayPolicy*` (interface + DefaultEffectReplayPolicy).

**Resultados (fresh):**
- domain: 762 mutantes, 322 KILLED (42%), 118 SURVIVED, 322 NO_COVERAGE, 2 TIMED_OUT.
- runtime: 20 mutantes, 10 KILLED (50%), 10 SURVIVED.

**Clasificación de supervivientes (no se debilita nada, se documenta):**
- runtime: los 10 supervivientes son mutantes EQUIVALENTES por construcción — guardas
  (`MEMOIZED&&!hasJournal`, `EXECUTES_SUBPROCESS`, `WRITES_WORKSPACE`, READ_ONLY/SUCCEEDED)
  cuya rama devuelve RERUN, idéntico al default fall-through. Eliminar la guarda no
  cambia el output observable. Verificado contra la tabla de decisión del contract test.
- domain: 322 NO_COVERAGE concentrados en clases de datos/identidad
  (DurableTaskTerminal 140, OperationInput/Output, RetryControlIdentity...) —
  equals/hashCode/copy sin test directo. Se registra como deuda clasificada:
  (a)mutantes en data classes con igualdad estructural requieren tests de igualdad
  explícitos de bajo valor relativo; (b)Reconcilers con supervivientes parciales
  (RetryReconciler 41, Fingerprint 23) son objetivo REAL de refuerzo en futura ronda.

**Decisión:** pitest se queda cableado como tarea EXPLÍCITA (no en `check`); budget
controlado por PIT_THREADS. El umbral de kill-rate se introducirá cuando los
NO_COVERAGE de data classes se clasifiquen/excluyan, para no convertir el gate en
ruido. Round gate `check` verde con la nueva configuración de build.
