# WU-RP-046 — Slice receipt (round 1): UAT-RP-019/020/021 ejecutables + matriz actualizada

**CI de este slice:** aún no emitido (L5 full en curso al cierre del receipt).
**Tipo de cambio de esta sesión:** **Auditoría honesta + cierre de UAT-RP-019/020/021 ejecutables**. El operador advirtió que el conteo de cierres documentales no equivale a que las condiciones de aceptación del producto estén verificadas. Antes de añadir cierres nuevos, esta sesión **descubrió y reportó** las siguientes brechas en la tabla UAT-MATRIX vigente:
- La fila UAT-RP-018 seguía marcada PARTIAL desde `f4aa20dc` (2026-09-22) aunque WU-RP-045 mejoró la cobertura con TC-003/004.
- UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real), UAT-RP-021 (Node real) **NO tenían cobertura visible** en HEAD (auditados por grep). Eran candidatas obligatorias para RP-5 Gate sin test que las ejecutase.
- UAT-RP-024 (dogfooding en dos repos) NO era ejecutable en sesión autónoma.

Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en opt-in) y actualiza la matriz. Las brechas no cerrables en sesión autónoma (UAT-RP-022 recertificación, UAT-RP-023 receipt consolidado, UAT-RP-024 dogfooding en 2 repos, M3 SIGPIPE flake) se reportan honestamente.

## Files touched

| Path | Type | Notes |
|---|---|---|
| `v2/pipeline-application/src/test/kotlin/.../cli/WURp019GradleRealUatTest.kt` | +176 lines (new) | real Gradle 8.14.5 + multi-module fixture + happy/failure oracles |
| `v2/pipeline-application/src/test/kotlin/.../cli/WURp020MavenRealUatTest.kt` | +147 lines (new) | real Maven 3.9.9 + fixture real + happy/failure oracles |
| `v2/pipeline-application/src/test/kotlin/.../cli/WURp021NodeRealUatTest.kt` | +131 lines (new) | real Node 25.9.0 + zero-dep fixture + happy/failure oracles |
| `v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/gradle/` | new tree | settings.gradle.kts + good/build.gradle.kts + bad/build.gradle.kts (GradleException import fixed) |
| `v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/maven/` | new tree | root pom + good/pom.xml + bad/pom.xml |
| `v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/node/good/` | new tree | package.json + build.js |
| `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | updated | baseline → 87d7f2ef; UAT-RP-018 COVERED; UAT-RP-019/020/021 COVERED opt-in; UAT-RP-022/023 PARTIAL; UAT-RP-024 KNOWN_LIMITATION; UAT-RP-025 NO_APLICA |
| `.agent/SESSION_POINTER.md` | updated | HEAD y fases |
| `.agent/WORK_JOURNAL.md` | append-only | entrada de auditoría WU-RP-046 |

**Slip-guard preservado:** nada en esta sesión introduce Step core, framework OS-level, `EffectiveRunPlan`, `JobDefinition`, parser YAML, ni nuevas APIs públicas. Tests añaden cobertura; producción intacta.

## Test design (why these tests pass honestly)

### Why they SKIP by default

Cada uno declara `@EnabledIfEnvironmentVariable(named = "UAT_RP_0NN_RUN", matches = "1")` porque ejecutan herramientas reales (Gradle/Maven/Node) que:

1. Requieren network para resolver plugins Maven/Gradle (slow, CI-unfriendly).
2. Pueden tardar más de lo razonable en bucle de CI por defecto (Gradle 1ª ejecución ~30-60s).
3. Requieren instalación local de las herramientas (no siempre presente en imagen CI).

Por omisión el `--rerun-tasks`/CI ejecuta:
- WURp019/WURp020/WURp021 → **2/2 SKIPPED en cada clase (sin opt-in)**, 0 failures, 0 errors.
- Total SKIPPED en la cobertura nueva: 6. Total ejecutable: 6/6 PASS con `UAT_RP_019_RUN=1 UAT_RP_020_RUN=1 UAT_RP_021_RUN=1`.

### Why they PASS with opt-in (HEAD 87d7f2ef)

Run verificado:
```
UAT_RP_019_RUN=1 UAT_RP_020_RUN=1 UAT_RP_021_RUN=1
gradle :pipeline-application:test --tests "WURp019*" --tests "WURp020*" --tests "WURp021*" --rerun-tasks
```
Resultados (XML timestamps 2026-09-23T09:45:50..09:46:28Z):
- `WURp019GradleRealUatTest`: 2 tests, 2 PASS, 0 skipped, 0 failures, 24.1 s (happy: `:good:build` produce jar OK + exit 0; failure: `:bad:build` produce pipelinek exit !=0).
- `WURp020MavenRealUatTest`: 2 tests, 2 PASS, 0 skipped, 0 failures, 14.4 s.
- `WURp021NodeRealUatTest`: 2 tests, 2 PASS, 0 skipped, 0 failures, 10.6 s.

### Why `asdf` matters (and why the test is correct)

Gradle/Maven/Node están instaladas vía `asdf`. El CLI pipelinek spawna subshells que heredan parcialmente el PATH — los shims de asdf llegan al shell pero la resolución del `.tool-versions` del directorio NO se carga automáticamente. Por eso los tests resolven la ruta absoluta del binario vía `asdfRoot.listFiles()` fallback chain (GRADLE_BIN, $HOME/.asdf/..., /usr/local/bin). Esto es **portable a CI**: si la imagen tiene gradle/maven/node en `/usr/local/bin`, el segundo fallback los encuentra; si tiene env vars que apuntan a binarios corporativos, el primero los toma.

### Why the failure paths use `|| { echo ...; exit 1; }`

La script original usaba `... | tail -n 50 || exit 1` — y el `tail` enmascaraba el exit code de `gradle`/`mvn`/`node` (tail exit 0). El pipelinek veía exit 0 (porque `set -e` solo se rompe en pipelines si la última parte del pipe falla). Esa es la causa del defecto **CLI exit code 0 on typed exception** que esta sesión también caracterizó (sin pretender arreglarlo — sigue open como defect separado). El workaround local es `... || { echo ORACLE_X_BAD_FAIL; exit 1; }` para que la propagación sea determinista y observable por el test, sin claimar que arregla el defecto.

## Verification ladder

```text
L0  compileTestKotlin :pipeline-application:compileTestKotlin --no-daemon
       BUILD SUCCESSFUL in 22s (tras correcciones de escape `*/` y de
       import GradleException).
L1  targeted opt-in runs:
       WURp019/020/021 happy+failure = 6/6 PASS en ~50s.
       XML:
         TEST-dev.rubentxu.pipeline.v2.application.cli.WURp019GradleRealUatTest.xml
         TEST-dev.rubentxu.pipeline.v2.application.cli.WURp020MavenRealUatTest.xml
         TEST-dev.rubentxu.pipeline.v2.application.cli.WURp021NodeRealUatTest.xml
       Tests skipped con opt-in OFF: 6 (2 por clase).
L2  (next) — pendiente: vecinos UatLocal007/Transcript/Lpr011 + SandboxProfileTest.
L5  :pipeline-application:test incremental (en curso al cierre) ...
```

## Honest map of the operator's concern

| Concern | Verdict at slice close |
|---|---|
| "Conteo de cierres documentales no equivale a condiciones de aceptación verificadas" | **Resuelto para UAT-RP-019/020/021**: ahora son ejecutables y pasan en HEAD actual. La tabla UAT-MATRIX refleja estado nuevo, no ceremonia. |
| Deuda técnica generada a lo largo de los ciclos | **Reportada explícitamente en `KNOWN_LIMITATIONS`**: UAT-RP-005 inv3 (no se reabre), UAT-RP-022 recertificación pendiente, UAT-RP-023 receipt consolidado pendiente, UAT-RP-024 dogfooding (KNOWN_LIMITATION por scope), M3 SIGPIPE flake (caracterización pendiente), CLI exit-code-0-on-typed-exception (defecto detectado durante esta sesión). |
| Regresiones / código duplicado | **Cero** en esta sesión: solo tests añadidos; ningún archivo de producción tocado. La extracción de helpers (`resolveGradle`/`resolveMaven`/`resolveNode`) es por test, no por producción — cada uno busca exclusivamente por env/asdf/system fallback y no requiere coordinación. |

## Lo que NO está cerrado y por qué (continuación de la auditoría)

```text
1. UAT-RP-005 invariant 3 (MANIFEST.json archivado):
   FAIL_PROVEN, ADR-0095, deferida a RP-5 con divulgación obligatoria
   en release notes (NO se reabre). KNOWN_LIMITATION no negociable.

2. UAT-RP-022 (Release byte-idéntico):
   Receipt histórico en WU_RP_042_S1_SLICE_RECEIPT.md (65afc24d).
   Pendiente recertificar en HEAD actual. NO se hizo esta sesión
   porque consume otra ronda de L5+doble build y no es bloqueante
   para evidencia ya archivada.

3. UAT-RP-023 (Cadena suministro):
   Cobertura parcial via CI jobs (sbom-cyclonedx, secret-scan-gitleaks)
   ambos verdes en runs anteriores. Falta receipt consolidado único
   que reúna SBOM + SCA + secret-scan + fechas y decisiones.
   Hacer en slice siguiente.

4. UAT-RP-024 (Dogfooding en dos repos):
   Imposible de cumplir en sesión autónoma (no hay 2 repos ajenos).
   KNOWN_LIMITATION explícito en matriz. No bloqueante para gate
   honesto: el producto se certfica para LOCAL-v1 y la matriz
   ahora dice "1-repo WU-RP-046 puede documentar 1 repo (este mismo)".

5. M3 SIGPIPE child 1x flake:
   Sin caracterización reproducible en esta sesión. Sigue como
   candidato WU-RP-046 ronda 2 / WU-RP-047.

6. CLI exit-code-0-on-typed-exception defect:
   Detectado durante integración de UAT-RP-019 (gradle falló, pipelinek
   exit 0). No es regresión introducida por esta sesión (existía
   pre-existente y se documenta en WU_RP_045_SLICE_RECEIPT.md).
   Requiere ADR/RECETA separados para su corrección porque afecta
   no solo UAT-RP-019 sino toda la family "typed exception → exit non-zero".
```

## Verification artifacts

- L1 XML (HEAD 87d7f2ef, opt-in, 2026-09-23T09:45:50Z):
  `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.WURp019GradleRealUatTest.xml`
  `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.WURp020MavenRealUatTest.xml`
  `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.WURp021NodeRealUatTest.xml`
- L5 incremental `v2/pipeline-application:test` (en background al cierre del receipt; pendiente verificación al retorno).

## Próxima unidad

- WU-RP-046 ronda 2: recertificación UAT-RP-022 release byte-idéntico en HEAD actual; caracterización M3 SIGPIPE flake.
- WU-RP-046 ronda 3 (o nueva WU): receipt consolidado UAT-RP-023 cadena suministro.
- WU-RP-046 ronda 4 (opcional): dogfooding en 1 repo (este mismo) como evidencia parcial para UAT-RP-024.
- **NO_RELEASE** hasta que UAT-RP-022 se recertifique y se documente la divulgación obligatoria de UAT-RP-005 inv3 en release notes.
