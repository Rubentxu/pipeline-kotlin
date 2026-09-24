## Reconciliación 2026-09-24T10:09Z — CONSIGNA ARQUITECTÓNICA CROSS-REPO: pipeline-kotlin ↔ pipelinek-release-harness (autoridad operativa vigente)

- **Decisión del operador:** el desarrollo de PipelineK (pipeline-kotlin) se separa de la certificación externa (repositorio independiente `Rubentxu/pipelinek-release-harness`). Cada uno con su `AGENTS.md`, su identidad material y su round gate independiente. Comunicación vía manifiesto inmutable + resultado estructurado + issues en GitHub (huella estable por contrato+escenario+causa, sin SHA de candidata). **NO** por comentarios libres.
- **Certificación NO depende exclusivamente de GitHub:** el resultado+evidencia viven en el harness; los checks son vista cómoda; manifiesto y recibo son fuente de verdad.
- **Medir antes de retirar:** registrar tiempo actual DEV/integración antes de mover UATs externas al harness. Migrar una UAT, demostrar equivalencia, re-medir.
- **Bloqueo del harness no paraliza todo:** una candidata bloqueada en el harness NO detiene el desarrollo de la siguiente WU en pipeline-kotlin.
- **Round gate integral del repo:** sigue siendo necesario para CERTIFIED_FULL interno; deja de ser prerrequisito para que el harness examine una candidata. La nueva candidata sólo necesita (i) manifiestos verificables y (ii) tests quirúrgicos de los contratos afectados verdes.

### Acción de este ciclo (AUTO)

1. **Bloque `AGENTS.md`** creado y commitado en una rama dedicada para no contaminar WU-RP-043:
   - Rama: `wu/rp-harness-coordination` (HEAD `8a44418d`, base `origin/main` 74b40a65).
   - Diff: AGENTS.md +17 líneas, sólo el bloque literal 'Coordinación con Release Harness — desarrollo y correcciones' al final del archivo. No se han tocado otras secciones.
   - Mensaje del commit: `docs(agents): add coordination block with pipelinek-release-harness`.
   - Pendiente: añadir el mismo bloque al `AGENTS.md` de `Rubentxu/pipelinek-release-harness` (repositorio todavía no creado).
   - Detalles técnicos (esquemas JSON, permisos, deduplicación, estados) viven en las especificaciones del harness, NO en este AGENTS.md.
2. **WU-RP-043** preservada en `wu/rp-043-integration-clean` (HEAD `262cc11e`), 4 commits atómicos, sin mezcla. Pendiente de decisión del operador sobre promover a `main` ahora (con divulgaciones) o esperar a que el harness la examine.
3. **WIP del operador preservado** en working tree (`docs/pipeline-kotlin-config-overlay-package/`, `scripts/run-pipelinek.sh`, scripts/, fixtures, ADRs del operador, recibos WU-RP-053). Recuperable con `git stash` cuando corresponda.

### Identidad material del proyecto (PRE)

- HEAD base: 9ed0a4f2.
- origin/main: `74b40a65`.
- Binario estable `/home/rubentxu/.local/bin/pipelinek`: NO modificado.
- Rama preservada: `wu/rp-043-self-hosted-ci` (12614b60) sobre 9ed0a4f2.
- Candidata limpia: `wu/rp-043-integration-clean` (262cc11e) sobre 74b40a65.
- Bloque coordinación: `wu/rp-harness-coordination` (8a44418d) sobre 74b40a65.

### DECLARACIÓN

- WU-RP-043 sigue CERTIFIED_WITH_DISCLOSURE (v0.39.0 canónico: round gate 06:48Z 2194/2194 verde). Recibo canónico intacto.
- Esta nueva consigna arquitectónica NO invalida WU-RP-043 ni su recibo canónico. REASIGNA el camino futuro: la próxima candidata se entrega al harness, no se promueve por gate local verde.
- Próximo corte ejecutable (sin pedir permiso): siguiente WU de PipelineK que no esté en `wu/rp-043-*` ni en `wu/rp-harness-*`, manteniendo el modo AUTO del operador.

## Reconciliación 2026-09-24T09:41Z — CIERRE AUTO WU-RP-043 (D-002 + dist aislada + N3A + rama limpia)

- **5 pasos completados** del plan del operador (08:57Z):
  1. ✅ D-002 caracterización con metodología controlada: warmup=1,3,5 × 4 reps. Sin bajar threshold. Decisión: warmup=3 (1 línea).
  2. ✅ Distribución candidata construida e instalada: ZIP 71394ec9…, BIN 045412d2… en install/candidate-262cc11e. JAR contiene ensureParentDirectory + Files.createDirectories + "not a directory".
  3. ✅ N3A sin workaround: 3/4 EXECUTED_PASS (S1, S2, S3) / 1 FAIL S4 por cache de Gradle (no del fix). Recibo con sha256s.
  4. ✅ Rama wu/rp-043-integration-clean basada en origin/main (74b40a65), 4 commits atómicos, 8 archivos, sin contaminación. Ancestro común con 9ed0a4f2 = 74b40a65 ✓.
  5. ⚠️ Round gate check TIMEOUT 1800s: 321 suites / 1476 tests / 0 failures / 0 errors en XMLs visibles. Rp022 PASS warmup=3 (23,4 MB/s). Corpus no llegó.

- **4 commits de la candidata (262cc11e):**
  - 262cc11e perf(wu-rp-043-d002): increase Rp022ThroughputProbe warmup 1 → 3
  - 07706ee3 fix(pipeline-events): SqliteConnectionFactory crea el directorio padre del --db
  - a315692d docs(wu-rp-043): recibos N1 + N3
  - 1c568dde ci(wu-rp-043): dogfooding CI local + verificador externo N3

- **Recibos emitidos esta sesión (4):**
  - D_002_RP022_FLAKE_DIAGNOSIS.md (132 líneas)
  - D_002_RP022_WARMUP_FIX_RECEIPT.md (129 líneas)
  - WU_RP_043_N3A_DIST_VERIFY_RECEIPT.md (127 líneas)
  - WU_RP_043_INTEGRATION_CLEAN_RECEIPT.md (150 líneas)
  - WU_RP_043_CLOSURE_RECEIPT.md (180 líneas)

- **Identidad material preservada:**
  - HEAD 9ed0a4f2 intacto.
  - Binario estable /home/rubentxu/.local/bin/pipelinek NO modificado.
  - WIP del operador + 33 fixtures + paquete overlay + ADRs en working tree (recuperable con git stash).
  - Rama wu/rp-043-self-hosted-ci (12614b60) preservada.

- **DECLARACIÓN:** WU-RP-043 sigue CERTIFIED_WITH_DISCLOSURE.
  - Divulgaciones: round gate 06:48Z verde (canónico) + round gate 08:43Z parcial (1874/1874 con 1 flake D-002 mitigado) + distribución aislada + rama limpia.
  - NO declaro integración certificada (el round gate actual TIMEOUTEÓ, D-002 mitigado no se re-intento en check completo).

- **Próximo paso:** re-intentar `./gradlew -p v2 check` incremental sobre la candidata limpia. Si verde: promover `wu/rp-043-integration-clean` a main.

## Reconciliación 2026-09-24T08:46Z — WU-RP-043 N6 ROUND GATE PARCIAL + D-002 DIAGNÓSTICO (autoridad operativa vigente)

- **Round gate parcial ejecutado:** `./gradlew -p v2 check` con timeout 1800s. **1874 tests / 1 failure pre-existente / 0 errors / 10 skipped**. Único FAIL: `Rp022ThroughputProbe.redactor throughput floor()` con 17,6 MB/s vs threshold 20 MB/s. **NO regresión de WU-RP-043** — `git diff 9ed0a4f2 HEAD -- Rp022ThroughputProbe.kt` devuelve vacío.
- **Diagnóstico de la flake:** el test PASA aislado (22,4 MB/s, 12% sobre threshold) y FALLA en round gate concurrente (17,6 MB/s, 12% bajo). Causa: contención de CPU entre 320 tests paralelos. SHA-256 del XML fallido: `e7546603…`.
- **Veredicto round gate:** NO verde. Mantengo `CERTIFIED_WITH_DISCLOSURE` para WU-RP-043 (canónico sigue siendo el round gate 06:48Z 2194/2194 verde).
- **D-002 diagnosticado:** 4 opciones documentadas (A: warmup 1→3, B: threshold 20→15, C: retry, D: aceptar+divulgar). Recomendación del agente: opción A. Decisión pendiente del operador.
- **Recibo D-002 emitido:** `docs/v2/07-uat/D_002_RP022_FLAKE_DIAGNOSIS.md` (132 líneas).
- **Recibo N6 emitido:** `docs/v2/07-uat/WU_RP_043_N6_ROUND_GATE_PARTIAL_RECEIPT.md` (99 líneas).
- **Pendiente decisión operador:** opción A/B/C/D para D-002.
- **Próximo corte ejecutable (sin pedir permiso):** implementar opción A (warmup 1 → 3) y emitir recibo si el operador no indica lo contrario en el siguiente turno. Si el operador prefiere mantener el estado actual, respeto.

## Reconciliación 2026-09-24T08:43Z — ROUND GATE PARCIAL WU-RP-043 (autoridad operativa vigente)

- **Round gate `./gradlew -p v2 check` ejecutado:** timeout 1800s, presupuesto derivado 1170s. Cortado por timeout, no por gradle.
- **Resultado agregado:** 321 clases, 1874 tests, 1 failure pre-existente, 0 errors, 10 skipped.
- **Único FAIL:** `dev.rubentxu.pipeline.v2.credentials.api.Rp022ThroughputProbe.redactor throughput floor()` con `IllegalStateException: throughput below floor: 2834 ms for 50MiB (17,6 MB/s)`. NO es regresión — es flake pre-existente de D-002 (warmup pendiente del operador), `v2/pipeline-credentials-api/.../Rp022ThroughputProbe.kt` NO fue modificado por WU-RP-043. SHA-256 del XML `e7546603…`.
- **Veredicto:** round gate NO verde. Mantengo `CERTIFIED_WITH_DISCLOSURE` para WU-RP-043 (estado del round gate 06:48Z verde 2194/2194 sigue siendo el canónico). La divulgación añade este round gate parcial (1873/1874) como evidencia de no-regresión del trabajo nuevo.
- **Comparación:**
  - Previo (`9ed0a4f2`, 06:48Z): 2194 tests, 0 failures, 900.32s.
  - Este (`12614b60` + mi trabajo, 08:43Z): 1874 tests, 1 failure, timeout 1800s.
- **Recibo N6 emitido:** `docs/v2/07-uat/WU_RP_043_N6_ROUND_GATE_PARTIAL_RECEIPT.md` (99 líneas).
- **Pendiente:** abordar D-002 (warmup Rp022) o continuar con la siguiente WU del roadmap.
- **Estado WU-RP-043 al cierre:**
  - N1 canario ✅
  - N2 DEV profile ✅
  - N3 verificador externo 4/4 ✅
  - Fix SQLite en source ✅
  - Round gate canónico (06:48Z) ✅ 2194/2194 verde
  - Round gate actual (08:43Z) ⚠️ 1873/1874, 1 flake pre-existente

## Reconciliación 2026-09-24T08:09Z — WU-RP-043 N5 FIX SQLITE + DEUDA §2.1 SALDADA (autoridad operativa vigente)

- **3 commits atómicos en rama `wu/rp-043-self-hosted-ci`:**
  - `3cf35dbd` (impl): `.pipeline.kts` + `scripts/run-pipelinek` + `scripts/verify-rp-043.py`.
  - `0a73b8e6` (docs): N1 + N3 recibos.
  - `12614b60` (fix motor): parche en `SqliteConnectionFactory.open()` con `ensureParentDirectory` (4 tests nuevos, 192/192 PASS).
- **Deuda §2.1 saldada en source:** el factory ahora crea el directorio padre del --db SQLite con `Files.createDirectories`, propaga `FileSystemException` explícita si el padre existe pero NO es directorio, y NO intenta crear CWD para basename. Los errores de permisos / path inválido / init de SQLite se propagan sin enmascarar.
- **Tests añadidos:** `SqliteConnectionFactoryParentDirectoryTest.kt` con 4 tests (crea dir ausente, no-op con dir existente, propaga IOException, basename no-op con cleanup de sidecar SQLite).
- **Activación del fix en binario:** pendiente. El binario `92d0f67d…` sigue siendo el viejo. Hasta que el operador re-instale PipelineK, `purge_state_dir()` en el verificador es necesario.
- **Verificación cruzada del verificador externo:** 4/4 EXECUTED_PASS, exit=0, 66s, contra el binario viejo.
- **Cambio adicional en el commit N5:** timeout de S1 en el verificador 60s → 180s (justificado por la stage `runDevSuite` que tarda ~80-90s). Limpieza de stray del test 4 (basename).
- **Recibo N5 emitido:** `docs/v2/07-uat/WU_RP_043_N5_SQLITE_FIX_RECEIPT.md` (115 líneas).
- **No push.**
- **Próximo corte (sin pedir permiso):** ejecutar `./gradlew -p v2 check` UNA vez (presupuesto derivado: 1170s, ceil 1800s). NO interpretar como certificación integral.
- **Pendiente para el operador:**
  1. Revisar commits y decidir si promover la rama `wu/rp-043-self-hosted-ci` a main.
  2. Decidir si re-instala el binario (activaría el fix en N3 verificador sin work-around).
  3. Cierre de WU-RP-053 (rama ADR separada).
- **Primer comando de reanudación mañana:** `git log --oneline 9ed0a4f2..HEAD && python3 scripts/verify-rp-043.py && echo OK-N3`. Debe imprimir 4 líneas NDJSON con `state: EXECUTED_PASS` y exit code 0.

## Reconciliación 2026-09-24T08:00Z — WU-RP-043 N2 + COMMITS ATÓMICOS (autoridad operativa vigente)

- **2 commits atómicos entregados en rama `wu/rp-043-self-hosted-ci` (creada desde `9ed0a4f2`):**
  - `3cf35dbd` (impl): `.pipeline.kts` (NUEVO, 72 líneas, 3 stages), `scripts/run-pipelinek` (NUEVO, 163 líneas), `scripts/verify-rp-043.py` (NUEVO, 503 líneas, 4 escenarios). 738 líneas en total.
  - `0a73b8e6` (docs): `WU_RP_043_N1_DOGFOODING_RECEIPT.md` + `WU_RP_043_N3_VERIFIER_RECEIPT.md`. 324 líneas.
- **N2 — DEV profile real ejecutado:** stage `runDevSuite` corre `./gradlew :pipeline-application:test --tests 'UatLocal005*' --tests 'UatDsl001*'`. Resultado: **9 clases / 46 tests / 0 failures / 0 errors / 2 skipped** en 86s. XML JUnit frescos (age=15s).
- **N3 — verificador externo 4 escenarios:** 4/4 EXECUTED_PASS, exit_global=0, 70s.
- **Estado operativo bajo XDG:** artefactos del verificador en `$XDG_STATE_HOME/pipelinek/verify/wu-rp-043/<scenario>/<run-id>/`. **3 ejecuciones consecutivas confirman idempotencia** — `find . -name 'rp-043-verify' -not -path './.git/*'` devuelve vacío tras cada run. Evidencia histórica preservada en `~/.local/state/pipelinek/verify/wu-rp-043/history/9ed0a4f2-n3/`.
- **Recibo N4 emitido:** `docs/v2/07-uat/WU_RP_043_N4_CLOSURE_RECEIPT.md` (157 líneas).
- **Identidad material preservada:** HEAD base `9ed0a4f2`, binario `92d0f67d…`, WIP WU-RP-053 (3 productivos + 3 tests), paquete overlay ADR-0094, 33 fixtures restaurados, recibos WU-RP-053 — todo en disco como `M`/`??`, **NO commiteado**.
- **No push** (directiva explícita del operador).
- **Próximo WU (sin pedir permiso):** corregir defecto SQLite (deuda §2.1 del recibo N4) — parche en `SqliteConnectionFactory.open()` con `Files.createDirectories(parent)`, conservando errores explícitos de permisos/ruta inválida. Tests nuevos para dir nuevo, dir existente, ruta no-creable. Verificar que el verificador sigue 4/4 PASS sin `purge_state_dir()`.
- **Pendiente para el operador:**
  1. Revisar commits en `wu/rp-043-self-hosted-ci`.
  2. Decidir si promueve `wu/rp-043-self-hosted-ci` a `main` o la deja como rama de espera.
  3. Decisión sobre cierre de WU-RP-053 y promoción a CERTIFIED_FULL.
- **Primer comando de reanudación mañana:** `git log --oneline 9ed0a4f2..HEAD && python3 scripts/verify-rp-043.py && echo OK-N3`. Debe imprimir 4 líneas NDJSON con `state: EXECUTED_PASS` y exit code 0.

## Reconciliación 2026-09-24T07:37Z — WU-RP-043 N3 VERIFICADOR EXTERNO (autoridad operativa vigente)

- **Acción crítica tomada:** el verificador externo `scripts/verify-rp-043.py` (428 líneas, sha256 `9ede74246cd907f5afcbc4f9b8d2811eacb29b414f3c81c6b873e988f11ea2ff`) ejecutado en su totalidad. **3/3 EXECUTED_PASS, exit_global=0.**
- **Defecto del motor encontrado y work-around aplicado:** `SqliteConnectionFactory` (PipelineK 0.39.0) NO crea el directorio padre del `--db` SQLite; aborta con `SQLException` si `journal/` no existe. Work-around: pre-crear `journal/`, `control/`, `events/`, `logs/`, `runs/` antes de invocar el binario, y vaciar sólo su contenido (no los directorios). Documentado como gap del motor en el recibo N3 §6.1.
- **Evidencia cruda persistida** en `build/rp-043-verify/` con sha256 de cada artefacto (s1/s2/s3 stdout.json + stderr.log). NDJSON del verificador: 3 líneas con `state: EXECUTED_PASS` cada una.
- **Identidad material preservada:** HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` sin cambios; binario `92d0f67d…` sin cambios; WIP de WU-RP-053 (Main.kt, WorkspaceOperations.kt, CanonicalRuntimeCapabilityAccess.kt + 3 tests) intacto; paquete overlay intacto.
- **WIP propio no comprometido todavía:** `.pipeline.kts`, `scripts/run-pipelinek`, `scripts/verify-rp-043.py`, recibos `WU_RP_053_PROMOTION_RECEIPT_CORRECTION_2026_09_24.md`, `WU_RP_043_N1_DOGFOODING_RECEIPT.md`, `WU_RP_043_N3_VERIFIER_RECEIPT.md` (todos untracked).
- **Próximo WU:** N2 — extender `.pipeline.kts` con stage `runDevSuite` ejecutando `--tests 'UatLocal005*' --tests 'UatDsl001*' --tests 'WorkspaceOperations*'` (~30s, perfil DEV). El operador prohibió L5 de 900s por iteración.
- **Pendiente para el operador:**
  1. Luz verde para commit atómico (incluiría: `.pipeline.kts`, `scripts/run-pipelinek`, `scripts/verify-rp-043.py`, recibos N1+N3+corrección WU-RP-053).
  2. Autorización para abordar N2.
- **Primer comando de reanudación mañana:** `cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin && python3 scripts/verify-rp-043.py && echo OK-N3`. Debe imprimir 3 líneas NDJSON con `state: EXECUTED_PASS` y exit code 0.

## Reconciliación 2026-09-24T07:17Z — CORRECCIÓN WU-RP-053 + WU-RP-043 N1 CANARIO (autoridad operativa vigente)

- **Acción crítica tomada:** 33 archivos de `v2/compatibility/` (31 fixtures + baseline.json + rp022_perf_baseline.sh) estaban borrados localmente sin commit previo. **Restaurados** vía `git checkout HEAD -- v2/compatibility/`. Verificado: `git hash-object` coincide con `git ls-tree HEAD` byte-a-byte. Cobertura contractual de `CompatibilityCorpusTest` y `UatCompat001CorpusSmokeRunTest` restaurada.
- **Recibo WU-RP-053 corregido (no destructivo):** `docs/v2/07-uat/WU_RP_053_PROMOTION_RECEIPT_CORRECTION_2026_09_24.md` anexa — sin reescribir `WU_RP_053_PROMOTION_RECEIPT.md` (sha256 inalterable: `21a671c12d5f3366e810c67da112a0c8161fe4e0df9482893ef15792165f43a5`). Distingue tres estados que el operador marcó explícitamente: `L5_PASS_AT_SHA` (TRUE), `WU-RP-053_CERTIFIED_AT_SHA` (TRUE), `RP-5_PRODUCT_GATE_GO` (NOT TRUE / NO ACREDITADO).
- **Decisión irrevocable del operador aplicada:** GitHub Actions descartado, CI 100% local con `pipelinek` (registrada en `AGENTS.md` §"POLÍTICA DE CI" 2026-09-24). Ya no requiere CI remoto verde para certificación; L5 verde + divulgaciones son suficientes.
- **WU-RP-043 N1 canario ejecutado y demostrado:** PASS y FAIL verificables en < 16 s sin tocar el L5. Recibo: `docs/v2/07-uat/WU_RP_043_N1_DOGFOODING_RECEIPT.md`.
  - `.pipeline.kts` (NUEVO, 54 líneas): dos stages, `${GRADLE_BIN:-gradle} :good:buildJar` (escribe jar `good.jar` 9 bytes), `false` inyectable vía `PIPELINEK_FORCE_FAIL=1`.
  - `scripts/run-pipelinek` (M): inyecta `--workspace "$REPO_ROOT"` por defecto (sin tocar el de estado externo).
  - **Resultado PASS:** exit=0, 15 eventos, `RunFinished outcome=success`, jar escrito en disco, estado XDG en `~/.local/state/pipelinek/projects/pipeline-kotlin-3fda2f2cf251/`.
  - **Resultado FAIL:** exit=1, `StepFailed assertcandetectfailure/sh-1 failureKind=SCRIPT message="shell exited with code 1"`, `RunFinished outcome=failure`.
  - Los **6 criterios del operador** cumplidos (CLI real, comprobaciones reales, workspace coherente, estado fuera del repo, éxito observable, fallo detectable).
- **Git al cierre:** HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` (sin cambios). Working tree ahora tiene, además de los 3 archivos productivos previos, los nuevos: `.pipeline.kts` (untracked), `scripts/run-pipelinek` (untracked, antes ya untracked), recibos `WU_RP_053_PROMOTION_RECEIPT_CORRECTION_2026_09_24.md` y `WU_RP_043_N1_DOGFOODING_RECEIPT.md` (untracked).
- **Próximo WU:** el operador decide entre N2 (L5 desde dentro del pipeline, 900 s con daemon), N3 (integración con rama protegida vía hooks/pre-push), archivo de `scripts/run-pipelinek.sh`, o pasar a RP-6 markdown-toolkit-plugin o D-002 Rp022 flake warmup.
- **Pendiente para el operador:** (1) decidir próximo WU; (2) luz verde opcional para commit (no se commitea nada por instrucción previa).
- **Primer comando de reanudación mañana:** `git status --short && git rev-parse HEAD && bash scripts/run-pipelinek run ./.pipeline.kts && echo pass-ok`. Si esto último falla, WU-RP-043 N1 no está certificado y requiere revisión.

## Reconciliación 2026-09-24T07:05Z — PROMOCIÓN FINAL WU-RP-053 + POLÍTICA CI LOCAL (autoridad operativa vigente)

- **WU-RP-053 → CERTIFIED_FULL** (declarado 2026-09-24T07:05Z bajo paraguas AUTO).
- **Decisión arquitectónica irrevocable del operador:** GitHub Actions queda **descartado** como infraestructura de CI/gate de certificación. Round gate L5 = `cd v2 && ./gradlew check` en local, ejecutado por el operador o por pipelinek (dogfooding). Workflows en `.github/workflows/` preservados pero NO invocados desde RP-5. Política registrada en `AGENTS.md` §"POLÍTICA DE CI: 100% LOCAL CON PIPELINEK (DESCARTADO GitHub Actions) — 2026-09-24".
- Git al cierre: HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` (sin cambios). Rama `adr/0094-impact-policy-and-overlay-id-gap`. origin/main local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655` (sin cambios). Working tree **dirty** (instrucción previa del operador de no commit; nueva divulgación CI local no requiere commit; divulgaciones UAT-RP-005 inv3 + R5 + política CI local tampoco).
- **L5 round gate PASS (nuevo, 2026-09-24T07:03:41Z):** `cd v2 && timeout 1500 ./gradlew check --console=plain`, duración 900.32 s, exit=0. Aggregate JUnit 330 XMLs: `tests=2194 failures=0 errors=0 skipped=11`. Por módulo: application 19, architecture-tests 66, domain 113, events 36, scripting-kotlin24 14, credentials-api 11, credentials-local 9, scripting-api 8, binding-factory/artefacts-local/credentials-executor/credentials-multipart/event-harness/protocol 2-3 c/u, testkit 1.
- **Detekt (SAST) y Kover-verify (lfc-verify):** incluidos en el L5 gate dentro de pipeline-architecture-tests. Cero findings detekt; `koverVerify` UP-TO-DATE sin cambios desde el último verde.
- **Divulgaciones registradas (movidas de CI remoto a CI local):**
  - `AGENTS.md` §"POLÍTICA DE CI" (2026-09-24T07:00Z): declara CI local como round gate oficial y descarta CI GH Actions.
  - `docs/v2/07-uat/WU_RP_053_PROMOTION_RECEIPT.md`: recibo `CERTIFIED_FULL` firmado con L5 verde + divulgaciones (UAT-RP-005 inv3 + R5 + CI descartado). Texto sugerido para release notes actualizado.
- **Evidencia total acumulada:** 1166/1166 focal + verificaciones binarias (L1+L3 17/17, L4 installDist, UAT-RP-024, replay `--db/--control-root`, negativos API Step, StepContractSuite 370/370, domain 559/559, events 188/188, UAT-DSL 27/27) **+ L5 2194/2194** = **3380/3380**.
- **Próximo WU per ROADMAP §8 actualizado:** **WU-RP-043** (self-hosted CI / dogfooding — ahora primer puesto post-RP-5, al consolidar la nueva política CI local con pipelinek), seguido de RP-6 LFC-2E ecosistema, o D-002 (Rp022 flake warmup) si el operador prefiere.
- **Pendiente para el operador:**
  1. Decisión próximo WU (RP-043 vs RP-6 vs D-002).
  2. `git add AGENTS.md docs/v2/05-roadmap/ROADMAP.md docs/v2/07-uat/WU_RP_053_PROMOTION_RECEIPT.md && git commit -m "ci: 100% local con pipelinek (descarta GH Actions) + WU-RP-053 CERTIFIED_FULL"` (cuando el operador dé luz verde al commit).
- **Primer comando de reanudación mañana:** `git status --short && git rev-parse HEAD && git log -1 --oneline`. (Sustituye a `gh run list --workflow=lpr0-ci.yml` que ya no es vinculante; sigue siendo válido para auditoría histórica pero NO para certificación.)

## Reconciliación 2026-09-24T06:25Z — CIERRE DE CICLO WU-RP-053 (autoridad operativa HOY OBSOLETA — promovido a CERTIFIED_FULL a 07:05Z)

- **WU-RP-053 → CERTIFIED_WITH_DISCLOSURE** (declarado 2026-09-24T06:25Z bajo paraguas AUTO).
- Git al cierre: HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` (sin cambios). Rama `adr/0094-impact-policy-and-overlay-id-gap`. origin/main local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655` (sin cambios). Working tree **dirty** (instrucción previa del operador de no commit).
- **Evidencia local acreditada (1166/1166 tests PASS):** L1+L3 focal 17/17 + StepContractSuite 370/370 (18 clases) + domain 559/559 (113 clases) + events 188/188 (36 clases) + UAT-DSL local 27/27 + L4 installDist canario (binario verificado) + UAT-RP-024 2 repos (binario verificado) + replay mismo `--db`/`--control-root` (binario verificado) + 3 negativos del API Step (binario verificado).
- **CI remoto BLOQUEADO_EXTERNO_INFRA:** 4 runs consecutivos cancelados o fallidos sin runner GH Actions en la ventana 22:00Z–06:25Z. Runs: `35931142967` (failure shard `uat-dsl` + 3 cancelled), `35961451718` (cola 20min), `35962937347` (cola 13min), `35963911928` (main, cola 5min). Únicos jobs CI acreditados: run `35931142967` 7/11 verde (compile, sbom, dogfood, sast, domain-unit, secret-scan, architecture-fitness).
- **Divulgaciones registradas:**
  - `docs/v2/07-uat/WU_RP_053_SLICE_RECEIPT.md` §Divulgaciones obligatorias del gate RP-5: UAT-RP-005 inv3 (ADR-0095, contract freeze) + R5 (SAST PASS, Dependabot BLOQUEADO_EXTERNO, Kover-all KNOWN_GAP_INSTRUMENTACIÓN).
  - `docs/v2/07-uat/WU_RP_053_SLICE_RECEIPT.md` §Estado final WU-RP-053 al 2026-09-24T06:25Z: texto sugerido para release notes (BLOQUEADO_EXTERNO_INFRA + caveat sobre regresión oculta en path del adapter).
  - `docs/v2/07-uat/WU_RP_053_PROMOTION_RECEIPT.md`: recibo de promoción a CERTIFIED_WITH_DISCLOSURE firmado.
- **Próximo WU per ROADMAP:** RP-6 LFC-2E ecosistema (§8) o D-002 (Rp022 flake warmup) si operador prefiere.
- **Pendiente para el operador:** (1) ejecutar CI SHA-pinned cuando GH Actions tenga runner disponible para promover a CERTIFIED_FULL; (2) integrar WU-RP-053 a main si se requiere para que main registre el fix.
- **Primer comando de reanudación mañana:** `git status --short && git rev-parse HEAD && gh run list --workflow=lpr0-ci.yml --limit 3 --json databaseId,status,conclusion,headBranch,createdAt`. Si GH Actions sigue sin runner, continuar con D-002 (Rp022 flake warmup, 1 línea, bajo riesgo) o abordar RP-6.

## Reconciliación 2026-09-24T05:46Z — REANUDACIÓN (autoridad operativa HOY OBSOLETA)

- **Operador reanuda sesión** con mensaje "ok seguimos a tu criterio segun las recomendaciones". Decisión AUTO (per INITIATIVE_LPR_001 §2.4 + §3): ejecutar sin más confirmación.
- Git observado (reanudación): rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` (sin cambios desde 23:26Z). `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`. Working tree **dirty** (instrucción previa del operador: no commit sin confirmación). 
- **Corrección a la reconciliación 23:26Z:** el run `35931142967` NO estaba "atascado en Install just HTTP 403" como creía SESSION_POINTER. **Terminó `failure` a 23:55:27Z** con UN job rojo (`application-shard (uat-dsl, UatDsl*)` exit≠0) y 3 shards `cancelled`. Logs purgados (>10h); artefactos del shard fallido no se generaron. 7 jobs verdes: compile, sbom, dogfood, sast (23:00:19Z PASS), domain-unit, secret-scan, architecture-fitness.
- **Diagnóstico local del shard fallido:** mismo SHA `9ed0a4f2`, mismo comando del shard (`cd v2 && ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.UatDsl*' -PexcludeSlowTests=true --no-daemon`) → **27/27 PASS** (5 clases, XML frescos). Causa muy probable: flakiness de runner remoto, no regresión.
- **Nuevo run disparado:** `gh run rerun 35931142967 --failed` devolvió "cannot be rerun; already running" (el run quedó `queued` 18h+); bypass = `gh workflow run lpr0-ci.yml --ref adr/0094-impact-policy-and-overlay-id-gap` → nuevo run **35961451718** creado a 2026-09-24T05:46:43Z sobre el mismo SHA. Run viejo pasó automáticamente a `cancelled`. Poller Python en background (`/tmp/ci_poll.py`, PID 111241) refresca cada 60s.
- **WU-RP-053:** L1+L3 focal (17/17), L4 installDist canario PASS, UAT-RP-024 PASS, **replay mismo `--db`/`--control-root` PASS** (cero duplicación de efectos, mismo `runId`), **pruebas negativas del API Step PASS** (textual containment, canonical containment, `.v2` reservada). Divulgaciones RP-5 (UAT-RP-005 inv3 + R5 decisión) añadidas al slice receipt. Sigue OPEN hasta CI completo SHA-pinned.
- **Pendiente:** run `35961451718` terminar; tras verde + divulgaciones ya documentadas, promover WU-RP-053 a CERTIFIED y abordar siguiente bloque del roadmap.
- **Primer comando de reanudación mañana:** `tail -n 50 /tmp/ci-poll-35961451718.log; gh run view 35961451718 --json status,conclusion`.

## Reconciliación 2026-09-23T23:26Z — CIERRE DE SESIÓN (autoridad operativa HOY OBSOLETA)

- **Operador cierra sesión a las 23:26Z** con CI `lpr0-ci.yml` aún en curso sobre `adr/0094-impact-policy-and-overlay-id-gap`. Sesión se reanuda mañana; contexto completo persistido.
- Git observado (cierre): rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f`, `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`. Working tree **dirty**, sin commit (instrucción reiterada del operador). CI del HEAD exacto: **in_progress** (run `35931142967`, started 22:57:50Z, URL `https://github.com/Rubentxu/pipeline-kotlin/actions/runs/35931142967`).
- **Estado WU-RP-053 al cierre:**
  - ✅ **Funcionalmente cerrado**: L1+L3 (17/17 PASS, 3 XML SHA256 frescos en 22:49Z), L4 installDist canario (`/tmp/proj-cki-real` zero traza), UAT-RP-024 (PASS `Hello-World` + `Spoon-Knife`, recibo en `docs/v2/07-uat/UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md`).
  - ⏳ **Gate RP-5 parcial**: jobs CI verdes al cierre: `compile`, `sbom (cyclonedx)`, `dogfood (pipelinek runs .pipeline.kts of same SHA)`, `sast (detekt)`, `domain-unit`, `secret-scan (gitleaks)`, `architecture-fitness`. Pendiente: 4 shards `application-shard` atascados en `Install just` (HTTP 403 transient de `https://just.systems/install.sh`, retry con backoff documentado en `lpr0-ci.yml` líneas 109-127 / WU-RP-051); divulgación UAT-RP-005 inv3; decisión R5.
  - **R5 auditor (slot herb)**: reportado `blocked`/`FAIL` global con justificación opaca ("3 hits de MaxLinear"). Probe local mío: `gh api dependabot/alerts` → **404 Not Found** → Dependabot NO configurado → categoría correcta `BLOQUEADO_EXTERNO` (no FAIL). SAST PASS lo cubrió CI remoto job `sast (detekt)` a las 23:00:14Z. Kover-all = parte del shard engine.
- **Pendiente CRÍTICO para retomar mañana (en orden):**
  1. `gh run watch 35931142967 --exit-status` (o `gh api .../actions/runs/35931142967 | jq .status,.conclusion`) → registrar jobs verdes/rojos en `WORK_JOURNAL.md` 23:26Z+.
  2. Si los 4 shards siguen atascados por `Install just` HTTP 403, NO re-lanzar el run entero: solo el shard afectado, o usar `apt-get install just` local y confiar en CI caches.
  3. Añadir divulgación UAT-RP-005 inv3 al slice receipt (`docs/v2/07-uat/WU_RP_053_SLICE_RECEIPT.md`).
  4. Decidir R5 (Dependabot = BLOQUEADO_EXTERNO; Kover = covered por CI engine shard).
  5. Si todo verde: commit + push del diff WU-RP-053 — **PREVIAMENTE BLOQUEADO** por instrucción del operador. Confirmar antes de cualquier commit.
  6. Cuando RP-5 cierre: promover WU-RP-053 a CERTIFIED y abordar siguiente bloque del roadmap.
- **Primer comando de reanudación mañana:** `cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin && gh run watch 35931142967 --exit-status; tail -n 80 /tmp/ci-run-35931142967.log 2>/dev/null; git rev-parse HEAD && git status --short && git log -1 --oneline`.

## Reconciliación 2026-09-23T22:58Z (autoridad operativa vigente)

- Git observado: rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f` (sin cambio desde 22:53Z). `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`. Árbol de trabajo dirty: cambios implementación + nuevo receipt `docs/v2/07-uat/UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md` (sin commit). CI del HEAD exacto: **NOT_RUN**.
- ACTIVE_PHASE: RP-5, **WU-RP-053 sigue OPEN — UAT-RP-024 PASS**. Slot palmtree ejecutó UAT-RP-024 sobre el binario instalado (`v2/pipeline-application/build/install/pipelinek/bin/pipelinek`, jar SHA256 `911d4b01e456f014dd437633ee62347563a6a5a81e07b66130fa8f152a5ead3a`, mtime 2026-09-24 00:52Z). Dos repos externos clonados en `/tmp/rp024-prep/{hw,sk}` (NO en workspace pipeline-kotlin): `octocat/Hello-World` y `octocat/Spoon-Knife` (ambos `--depth 1` vía HTTPS, red OK). PROBE idéntico en ambos: `pipeline { stages { stage("probe") { sh("pwd"); sh("ls README*") } } }`. Comando: `env -i HOME PATH JAVA_HOME TERM bash -c 'unset PIPELINEK_* V2_* WORKSPACE_*; timeout 60 "$0" run --control-root "$1" PROBE.pipeline.kts'` desde dentro del repo. **hw: exit 0, pwd=/tmp/rp024-prep/hw, ls=README, RunFinished success, find post-run vacío**. **sk: exit 0, pwd=/tmp/rp024-prep/sk, ls=README.md, RunFinished success, find post-run vacío**. ZERO artefactos (`.v2/`/`workspace/`/`journal*`/`*.db`) en ambos checkouts. Veredicto **PASS/PASS**. Receipt: `docs/v2/07-uat/UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md`. KNOWN_LIMITATION "UAT-RP-024 imposible en sesión autónoma" queda **DESCARTADA** (ejecutada en esta sesión con red disponible).
- Quedan **NOT_RUN**: replay completo mismo `--db`/`--control-root`, sha-pinned CI run para SHA nuevo, divulgação UAT-RP-005 inv3, R5 (SAST/Dependabot/Kover-all). WU-RP-053 NO se promueve a CERTIFIED sin esos gates.
- NEXT_WU: integrar el diff de seguridad (ya en `9ed0a4f2`), correr CI completo sobre el SHA nuevo + replay; entonces cerrar WU-RP-053 y atender R5 + divulgação UAT-RP-005 inv3.
- Primer comando de reanudación: `git status --short && git rev-parse HEAD && git log -1 --oneline && git branch --show-current`; comparar SHA con `WU_RP_053_SLICE_RECEIPT.md`, `UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md` y `WORK_JOURNAL.md` 22:58Z. Gradle desde `v2/`.

## Reconciliación 2026-09-23T22:53Z (autoridad operativa vigente)

- Git observado: rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f`, `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`. Árbol de trabajo dirty con cambios de implementación + docs actualizados. NO se hizo commit (instrucción L4 canary). CI del HEAD exacto: **NOT_RUN**.
- ACTIVE_PHASE: RP-5, **WU-RP-053 L1+L3+L4 PASS, sigue OPEN por gate RP-5**. Tras L1+L3 focal (17/17 PASS, 3 XML SHA256 frescos, daemon caliente 34s), `--rerun-tasks :pipeline-application:installDist` regeneró el wrapper (jar mtime 2026-09-24 00:52:09 +0200, SHA256 `911d4b01e456f014dd437633ee62347563a6a5a81e07b66130fa8f152a5ead3a`). L4 canario con `/tmp/proj-cki-real` (checkout) vs `/tmp/rp-053-ctrl-real` (control): `sh("pwd")` resuelve al checkout, `sh("cat README")` lee su `README` interno, `RunFinished outcome=success`, y `/tmp/proj-cki-real` queda **vacío de rastro** (cero `.v2/`, cero `workspace/`, cero `journal*`, cero `*.db`); el state root mantiene `journal.db`, `last-run/`, `retry-control/`, `wait-until-control/`.
- Quedan **NOT_RUN**: replay completo mismo `--db`/`--control-root`, sha-pinned CI run para SHA nuevo, UAT-RP-024 (dos repos), divulgación UAT-RP-005 inv3, R5 (SAST/Dependabot/Kover-all). WU-RP-053 NO se promueve a CERTIFIED sin esos gates.
- NEXT_WU: integrar el diff de seguridad (ya integrado en `9ed0a4f2`) y correr CI completo sobre el SHA nuevo; entonces cerrar WU-RP-053 y atender R5, UAT-RP-024 y divulgación UAT-RP-005 inv3.
- Primer comando de reanudación: `git status --short && git rev-parse HEAD && git log -1 --oneline && git branch --show-current`; comparar SHA con `WU_RP_053_SLICE_RECEIPT.md` (L4 canary) y `WORK_JOURNAL.md` 22:53Z. Gradle desde `v2/`.

## Reconciliación 2026-09-23T22:50Z (autoridad operativa vigente)

- Git observado: rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f`, `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`; árbol de trabajo dirty con cambios de implementación + 5 docs actualizados. NO se hizo commit (instrucción del verifier). CI del HEAD exacto (`9ed0a4f2`): **NOT_RUN**.
- ACTIVE_PHASE: RP-5, **WU-RP-053 sigue OPEN**. Verificación independiente orquestada (L1+L3, daemon caliente, 34s) reejecutó la batería dirigida sobre `9ed0a4f2` y obtuvo **17/17 PASS** (0 failures, 0 errors): `WorkspaceOperationsEffectiveRootTest` 12/0/0 sha256 `974a55bd3f0f0bef5e25244223acecea5f221c39dc3a5aafe278a06ab990419f`, `cli.WURp053WorkspaceCliTest` 2/0/0 sha256 `796bcb773e895012f4b9421adc050ba627e1b833e08b75106f36d8859b01d4e2`, `scripted.DirFilesystemEndToEndTest` 3/0/0 sha256 `6a90743b9df61bd4fba2e7d83925094a47c6f418fa7ba8b9f61f84eed6b8a2c8`. XML fresco con timestamps 2026-09-23T22:49:18..22:49:22Z (canary verificado). NO se tocó producción ni fixtures.
- Quedan **NOT_RUN**: replay completo mismo `--db`/`--control-root`, sha-pinned CI run para SHA nuevo, aislamiento bajo `.v2` reservado, parallel stage isolation, rootless sandbox profile, UAT-RP-024 (dos repos), divulgación UAT-RP-005 inv3. WU-RP-053 NO se promueve a CERTIFIED sin esos gates.
- NEXT_WU: integrar el diff de seguridad, distribuir el binario y ejecutar `installDist` + canarios sobre checkout real + replay mismo `--db`/`--control-root` + CI completo sobre el SHA final; entonces cerrar WU-RP-053 y atender R5, UAT-RP-024 y divulgación UAT-RP-005 inv3.
- Primer comando de reanudación: `git status --short && git rev-parse HEAD && git log -1 --oneline && git branch --show-current`; comparar SHA con `WU_RP_053_SLICE_RECEIPT.md` y `WORK_JOURNAL.md` 22:50Z. Gradle desde `v2/`.

## Reconciliación 2026-09-23T22:45Z (autoridad operativa vigente)

- Git observado: rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f`, `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`; árbol de trabajo dirty con cambios de implementación. NO se hizo commit (instrucción del security worker). CI del HEAD: **NOT_RUN**.
- ACTIVE_PHASE: RP-5, **WU-RP-053 security seam implementado + L1 verde**. Se cerró el defecto funcional `WorkspaceOperationsAdapter.effectiveRoot` (el cwd del `dir(...)` se había convertido en la raíz autorizada para writeFile/readFile/fileExists, rompiendo ADR-0052). El adaptador ahora distingue **`authorizedWorkspaceRoot` (inmutable, WIDE guard)** de **`effectiveWorkingDirectory` (cwd, LOCAL/substrate guard)**. Validación focal: **WorkspaceOperationsEffectiveRootTest 12/12 + DirFilesystemEndToEndTest 3/3**. Diff intacto en árbol de trabajo: `WorkspaceOperations.kt` (337 líneas) + `WorkspaceOperationsEffectiveRootTest.kt` (296 líneas, +10 adversarial rows). Pendiente: distribuir el binario y ejecutar `installDist`+canarios sobre checkout real, replay mismo `--db`/`--control-root`, CI completo para SHA final, gate RP-5 (R5, UAT-RP-024, divulgación UAT-RP-005 inv3 siguen OPEN). No avanzar RP-6.
- NEXT_WU: tras cierre e instalación de la distribución en checkout real + evidencia de replay + CI completo sobre el SHA final, retomar WU-RP-040 R5 + UAT-RP-024. WU-RP-053 continúa OPEN hasta que la verificación end-to-end sobre la distribución instalada en el checkout real esté documentada.
- Primer comando de reanudación: `git status --short && git rev-parse HEAD && git log -1 --oneline && git branch --show-current`; comparar diff con el recibo `WU_RP_053_SLICE_RECEIPT.md` y `WORK_JOURNAL.md` 22:45Z. Gradle desde `v2/`.

## Reconciliación 2026-09-23T22:18Z (autoridad operativa vigente)

- Git observado: rama `adr/0094-impact-policy-and-overlay-id-gap`, HEAD `9ed0a4f2d1acda225236b843ecd782da5c68014f`, `origin/main` local `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`; árbol de trabajo con cambios de implementación y artefactos no seguidos. El encabezado histórico inferior (`1d38d778`, main) está **OBSOLETO** y no acredita este HEAD. No hay CI acreditado para `9ed0a4f2`: **NOT_RUN**.
- ACTIVE_PHASE: RP-5, **WU-RP-053 OPEN** (corrección workspace en checkout real), base `9ed0a4f2`; alcance y aceptación en `docs/v2/05-roadmap/ROADMAP.md` §7 y `docs/v2/07-uat/WU_RP_053_SLICE_RECEIPT.md`. No certificar ni publicar todavía. El último cierre histórico de WU-RP-051 se conserva para su SHA solamente.
- NEXT_WU: completar implementación en curso y verificar WU-RP-053 con L0, test focalizado, clase, distribución instalada en checkout real, negativos de rutas/aislamiento y replay; después CI completo sobre SHA final y gates RP-5 pendientes (R5 y UAT-RP-024). No avanzar RP-6.
- Primer comando de reanudación: `git status --short && git rev-parse HEAD && git log -1 --oneline && git branch --show-current`; después comparar diff de implementación con el recibo WU-RP-053. Gradle desde `v2/`.

**Actualizado:** 2026-09-23T13:50Z. **Tipo de cambio de esta sesión:** **CIERRE WU-RP-051 — push + CI verificación + 2 CI-infra fixes**. WU-RP-050 cerrada en `36f240fb` (slice receipt `4fb79b01`). WU-RP-051 extendió con bootstrap push + 2 fixes (Install just hardened `bd52fa1b` + sbom cache `63220a5c`) + slice receipt `70cde8e6`. **HEAD = 1d38d778** (LOCAL + REMOTE sincronizados, push final). LPR-0 CI verde run `35865298485` (10/10 success) + verificación final run `35866370854` pending. **NO_GO estricto**: NO_RELEASE, no tocar Step core, no Step framework OS-level, no overlay package.

**Código auditado:** main @ 1d38d778. WU head = 1d38d778.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-5 GATE — preparación honesta, pendiente WU-RP-040 R5 + UAT-RP-024 + UAT-RP-005 inv3 disclosure**. **WU-RP-051 CERRADA** (`1d38d778`): push + CI gate verde run `35865298485` (10/10 success) + verificación final `35866553565` 10/10 success (3 runs consecutivos verde). LPR-0 gate está verde sobre WU-RP-050 + 2 CI-infra fixes prophylactic (Install just hardened `bd52fa1b` + sbom gradle cache `63220a5c`). Bootstrap pattern (DELETE/PUT protection) verificado 2x. SEMVER: PATCH bump apropiado, NO_RELEASE vigente.
- LAST_CLOSED_WU: **WU-RP-051** (`1d38d778`, LOCAL=REMOTE, LPR-0 verde `35865298485` 10/10 + final verification `35866370854` pending): bootstrap push + 2 CI-infra fixes. Cubre WU-RP-050 (5 commits consolidación) + push + hardening CI.
- KNOWN_LIMITATIONS adicional detectada en esta sesión:
  - **WU-RP-050 consolidación parcial:** Las 2 duplicaciones de `LinkedSecretRef` en producción están consolidadas. La única llamada directa restante a `SecretStore.getAsSecretHandle` fuera del adapter es `LocalCredentialProvider.resolve` (legítimo: SPI implementation).
  - **CI-infra dependencies (D-003/D-004 RESUELTOS):** HTTP 403 transitorios de just.systems y Maven Central — ahora manejados con retry + fallback (just) y gradle cache (sbom). Sin recurrencia en run `35865298485`.
- WUs previas cerradas (histórico, sin suavizar):
  - **WU-RP-049 R1** (25818c10, CI 35855686796 SUCCESS 10/10): LF-0403 cerrado vía port domain hexagonal. ADR-0097 firmado + 6 commits (9649872e..25818c10). 18/18 tests projector PASS. 3 KNOWN_FLAKE pre-existentes documentados en `WU_RP_049_R1_SLICE_RECEIPT.md` como no-regresiones.
  - **LF-0403 SSH/cert passphrase-password LinkedSecretRef (defecto funcional):** CERRADO en WU-RP-049 R1 (25818c10). Port `CredentialLinkedSecretResolver` + adapter `SpiCredentialLinkedSecretResolver` resuelven `LinkedSecretRef` → `SecretHandle` real. SSH keystore handshakes ahora funcionan. Tests: 18/18 PASS (5 nuevos en `Lf0403LinkedSecretResolverTest` + 13 pre-existentes en `DefaultCredentialProjectorTest`).
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0099.
- KNOWN LIMITATIONS (vigentes — sin suavizar, arrastradas de WU-RP-046 R2 + auditorías previas):
  - **UAT-RP-005 inv3 (MANIFEST.json archivado):** FAIL_PROVEN, ADR-0095, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - **UAT-RP-019/020/021 (Gradle/Maven/Node real):** COVERED opt-in (`UAT_RP_019_RUN=1`, etc.); 6/6 PASS HEAD 87d7f2ef (CI 35846205928). SKIP por defecto en CI por coste.
  - **UAT-RP-022 (Release byte-idéntico):** COVERED HEAD 2a66317c (recertificado WU-RP-046 R2, ZIP sha256 `6c30e6b6...` doble build idéntico).
  - **UAT-RP-023 (Cadena suministro):** COVERED en parte (R3.1 SBOM + R3.2 secret-scan). SAST/detekt (R3.3) y Dependabot (R3.4) son KNOWN_GAP — bloqueante RP-5 Gate.
  - **UAT-RP-024 (Dogfooding en dos repos):** **PASS en `9ed0a4f2`** (2026-09-23T22:58Z, slot palmtree). Receipt `docs/v2/07-uat/UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md`. NO es KNOWN_LIMITATION.
  - **M3 SIGPIPE flake:** NO REPRODUCIBLE en HEAD 2a66317c (10 ejecuciones todas exit=0). QUARANTINED + NO_REPRODUCIBLE_AT_CURRENT_HEAD.
  - **CLI exit-code-0-on-typed-exception:** RECLASIFICADO — no es defecto del binario (retorna 0/1/2 correcto); artefacto de bash `... | tail`. Workaround `|| { ... }` válido en scripts bash de tests.
  - **UAT-RP-018 LOCAL COVERED a 12 caps.** `os` fail-closed ADR-0016 M5/M9 (RP-7+ scope).
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado NO integrado (colisión identificadores con ADRs/WUs vigentes); incorporar tras RP-5.
  - **WU-RP-040 R1 Kover PARTIAL:** domain (82.64%) + events (77.62%); 12 módulos sin cobertura.
  - **WU-RP-040 R4 pitest PARTIAL:** mutation 42% domain / 50% SDK; 128 mutantes sobrevivientes.
- NEXT_WU: **WU-RP-040 R5** (SAST/detekt + Dependabot + Kover-all + triage mutantes). Sigue **WU-RP-048** (dogfooding 1-repo fork para UAT-RP-024). **NO_RELEASE** hasta: (a) WU-RP-040 R5 verde, (b) UAT-RP-024 evidencia 1-repo dogfooding, (c) divulgación UAT-RP-005 inv3 release notes. LF-0403 cerrado. WU-RP-050 cerrado. WU-RP-051 cerrado.
- Próximo WU técnicamente: D-002 (Rp022ThroughputProbe warmup, P2) si se desea cerrar flake pre-existente ANTES de WU-RP-040 R5. Bajo riesgo, 1 línea.
- BLOCKERS: ninguno técnico. Política RP-5 Gate: SAST + Dependabot pendientes; dogfooding ≥2 repos estructuralmente imposible; divulgação UAT-RP-005 inv3 pendiente. **UAT-RP-024 ejecutado y PASS en 22:58Z** (descartada como limitación).
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5. **Prerrequisito irreducible:** UAT-RP-019/020/021 ejecutables en HEAD + UAT-RP-022 reproducibilidad + UAT-RP-018 matriz COVERED + M3 SIGPIPE caracterizado.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 1d38d778.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 1d38d778 (LPR-0 run `35865298485` 10/10 verde). Si verificación final `35866553565` terminó verde, ese es el certificado definitivo. Si terminó rojo, diagnosticar y arreglar antes de proseguir.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 1d38d778 + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-040 R5 (SAST + Dependabot + Kover-all + triage mutantes) — próximo WU per ROADMAP. Alternativa: D-002 (Rp022 flake warmup) si se prefiere cerrar flake pre-existente primero.

## Reconciliación 2026-09-24T11:46Z — SHA DEL HARNESS CONFIRMADO: ca2a91c0 + PR #76 sigue esperando motor (autoridad operativa vigente)

- **Estado verificado por el operador 2026-09-24T11:46Z sobre GitHub**:
  - `pipeline-kotlin/main` = `2ba36069` (pre-#84/#85; estado al cierre del operador de la sesión de consolidación 4-PRs). Después de la sesión: `d5eb9781` (post-#84 #85).
  - **`pipelinek-release-harness/main` = `ca2a91c0`** (dato nuevo; el operador confirma que el harness YA está inicializado y tiene SHA concreto).
  - **PR #76 de pipeline-kotlin** sigue ABIERTA. El operador **NO fusionaría** #76 sólo porque los prompts estén terminados: es un cambio del motor que debe superar sus propios gates.

- **Implicación para los prompts**: el agente del harness debe **referenciar su propio SHA** (`ca2a91c0` o el que tenga HEAD cuando arranque) en todos los artefactos publicados, NO un SHA genérico. Actualizado en `HARNESS_PROMPTS_2026_09_24.md`.

- **Posición actual de pipeline-kotlin (AUTO)**:
  - main: `d5eb9781` (12 commits squash sobre línea base `74b40a65`).
  - PR #76 OPEN: candidata WU-RP-043 rebased; NO la auto-mergeo per directiva 11:46Z.
  - WIP del operador: 30 archivos intactos.
  - Distribución v0.39.0: ZIP + instalador bash en main (DIST-1, DIST-2 cerrados).

- **Próximos cortes (AUTO, sin pedir permiso adicional)**:
  1. Operador arranca H0.3 (o el siguiente requisito si H0.3 ya está cerrado) en el harness con Prompt A.
  2. Si el operador quiere más prompts o quiere que ajuste la trazabilidad del SHA, los entrego en este repo.
  3. PR #76 sigue en espera hasta que el operador decida por motivos del motor, no del circuito.

- **Estado material**:
  - pipeline-kotlin/main: `d5eb9781`.
  - pipelinek-release-harness/main: `ca2a91c0` (referencia; el agente del harness conoce el SHA actualizado).
  - wu/rp-043-integration-clean: `d8145632` (PR #76 OPEN).

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -5
```

Debe mostrar: HEAD `d5eb9781 docs(agent): record CLI real args + Spring REST prompt directive (#85)`. Log anterior: `2c077409 docs(uat): harness prompts with real CLI args + Spring REST prompt (#84)`.

## Reconciliación 2026-09-24T11:36Z — PROMPTS DEL HARNESS CON ARGUMENTOS REALES + SPRING REST (autoridad operativa vigente)

- **Directiva del operador 2026-09-24T11:34Z sobre el circuito cross-repo** (refinamiento):
  - CLI REAL del harness: `run --image ... --project ... --scenario ...` (NO `run v0.39.0`).
  - CLI adicional: `candidates` (lista candidatas nuevas de pipeline-kotlin).
  - Si H0.3 ya está cerrado con ejecución real válida, NO repetirlo por ceremonia; continuar con el siguiente requisito pendiente.
  - **publish-issue**: usar incidencia de ensayo o adaptador de pruebas; NO abrir incidencia real inventando defecto.
  - **Sin credenciales de escritura**: resultado = `ISSUE_PENDING`, NO stack trace.
  - **Errores del harness NO generan issues de producto** (sólo defectos reproducibles del motor).
  - **reverify-cycle**: ejecutar la candidata correctiva realmente, NO reutilizar recibo anterior.
  - **No crear ledger paralelo**: reutilizar estado y contratos existentes.
  - **No reenviar handoffs manualmente** entre repositorios: candidatas, recibos e issues accesibles vía GitHub.
  - **Después del circuito**: incorporar primer proyecto externo pequeño (Spring REST) con compilación, tests y fallo intencionado en Podman. Añadir Docker y demás lenguajes progresivamente, sin bloquear la entrega vertical.

- **acción inmediata en este repo**: reescritura de `HARNESS_PROMPTS_2026_09_24.md` (PR #84 mergeada, commit `2c077409`):
  - **Prompt A**: cerrar H0.3 sin ceremonia. Usar CLI REAL (`run --image ... --project ... --scenario ...`). Si ya está cerrado, continuar con el siguiente requisito.
  - **Prompt B**: publish-issue controlado con huella de ensayo; verificación de deduplicación y de comportamiento `ISSUE_PENDING` sin credenciales.
  - **Prompt C**: reverify-cycle con candidata correctora real o controlada; ejecutar la distribución nueva, no reusar recibos.
  - **Prompt D (nuevo)**: primer proyecto externo pequeño — Spring REST en Podman, con escenarios de éxito y fallo intencionado; sin abrir issues de producto por fallos del proyecto.

- **main final del turno**: `2c077409 docs(uat): harness prompts with real CLI args + Spring REST prompt (#84)`.

- **Estado consolidado**:
  - origin/main: `2c077409` (11 commits squash sobre línea base `74b40a65`).
  - PR #76 (candidata WU-RP-043 rebased): sigue OPEN, código de motor, decisión del operador.
  - WIP del operador: 30 archivos intactos.
  - Binario estable: NO modificado.
  - Distribución v0.39.0 en main: ZIP + instalador bash. Resto en el harness.

- **Identidad material**:
  - origin/main: `2c077409`.
  - wu/rp-043-integration-clean: `d8145632` (PR #76 OPEN).

### Próximos cortes (AUTO, sin pedir permiso adicional)

1. **PR #76 (candidata WU-RP-043 rebased)** — operador decide merge a main. **No la auto-mergeo**.
2. **El operador arranca H0.3** en el harness con Prompt A. Yo entrego prompts ajustados y no toco el harness.
3. **Si el operador quiere prompts adicionales** (más detallados o nuevas WUs), los entrego en este repo como docs sin tocar código.

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -5
```

Debe mostrar: HEAD `2c077409 docs(uat): harness prompts with real CLI args + Spring REST prompt (#84)`. Log anterior: `2ba36069 docs(agent): record H0.3 + reverify-cycle prompts directive (#83)`.

## Reconciliación 2026-09-24T11:28Z — HARNESS H0.3 + REVERIFY EN MARCHA: prompts operativos listos (autoridad operativa vigente)

- **Directiva del operador 2026-09-24T11:26Z sobre el harness** (cambio material de modelo):
  - El harness YA TIENE: clasificación de fallos, publicación/actualización de issues, deduplicación, registro local de reverificación, CLI con `run` / `publish-issue` / `reverify-cycle` / `certify`.
  - **Perfiles**: Base certificado. Real ampliada NO cerrado. Certificación completa NO cerrado.
  - **Siguiente paso del harness = H0.3**: demostrar circuito ejecutable con la candidata v0.39.0 ya publicada. NO empezar con 5 lenguajes, benchmarks, ni release publication.
  - **PR #76** de pipeline-kotlin sigue abierta; el harness puede continuar con v0.39.0 sin esperarla.
  - **Tabla explícita de comunicación cross-repo** (GitHub como canal compartido):
    - Candidata + ZIP → pipeline-kotlin produce; harness consulta artefacto + manifiesto + commit.
    - Resultado de certificación → harness produce; pipeline-kotlin consulta recibo + estado + commit/PR.
    - Defecto reproducible → harness produce issue en pipeline-kotlin.
    - Corrección → pipeline-kotlin produce commit/PR + nueva candidata.
    - Reverificación → harness produce recibo + actualiza issue.
    - Release estable → harness produce (flujo promoción autorizado).
  - **Distinción crítica**: commits, PR y recibos de pipeline-kotlin ofrecen trazabilidad, pero NO debemos dar por hecho que existe un servicio que detecta candidatas, crea issues y promociona releases hasta que sus pruebas de extremo a extremo lo demuestren. H0.3 ES esa prueba.

- **acción inmediata en este repo**: reescritura de `docs/v2/07-uat/HARNESS_PROMPTS_2026_09_24.md` (PR #82 mergeada, commit `6c1cdb9f`). Los prompts ya no son de inicialización, sino de activación del circuito sobre lo que existe:
  - **Prompt A**: cerrar H0.3 ejecutando `pipelinek-harness run v0.39.0` con la CLI existente.
  - **Prompt B**: reverify-cycle cuando pipeline-kotlin publique una candidata correctiva.
  - **Prompt C**: política autónoma del harness (ledger append-only, dedupe por huella, issues en pipeline-kotlin, sin PR cross-repo).

- **Limpieza de ramas remotas obsoletas** (todas ya mergeadas):
  - Borradas: `wu/dist-002-installer`, `wu/dist-003-readme-install-section`, `wu/dist-roadmap-refresh-2026-09-24`, `wu/readme-harness-split-mise-asdf`, `wu/state-2026-09-24-dist-2-merge`, `wu/state-2026-09-24-distribution-closure`, `wu/rp-harness-coordination`.
  - Conservadas: `wu/rp-043-integration-clean` (PR #76 OPEN, candidata del motor), `wu/harness-prompts-h03-reverify` (mergeada en PR #82; borrar opcionalmente).

- **main final del turno**: `6c1cdb9f docs(uat): rewrite HARNESS_PROMPTS for H0.3 + reverify cycle (#82)`.

- **Estado consolidado**:
  - origin/main: `6c1cdb9f` (9 commits squash sobre línea base `74b40a65`).
  - PR #76 (candidata WU-RP-043 rebased): sigue OPEN, código de motor, decisión del operador.
  - WIP del operador: 30 archivos intactos.
  - Binario estable: NO modificado.
  - Distribución v0.39.0 en main: ZIP + instalador bash (DIST-1, DIST-2 cerrados). Resto (DIST-3/4/5/6/7) en el harness.

- **Identidad material**:
  - origin/main: `6c1cdb9f`.
  - wu/rp-043-integration-clean: `d8145632` (PR #76 OPEN).

### Próximos cortes (AUTO, sin pedir permiso adicional)

1. **PR #76 (candidata WU-RP-043 rebased)** — operador decide merge a main. **No la auto-mergeo**.
2. **El operador arranca H0.3** en el harness con Prompt A. Yo entrego el material que necesita pero no toco el harness.
3. **Si el operador quiere otra ronda de prompts** (más detallados, con escenarios concretos, etc.), los entrego en este repo como docs sin tocar código.

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -5
```

Debe mostrar: HEAD `6c1cdb9f docs(uat): rewrite HARNESS_PROMPTS for H0.3 + reverify cycle (#82)`. Log anterior: `95039aec docs(agent): distribution closure — ZIP + bash ready, harness absorbs rest (#81)`.

## Reconciliación 2026-09-24T11:22Z — DISTRIBUCIÓN v0.39.0 CERRADA EN ESTE REPO: ZIP + BASH. HARNESS ABSORBE EL RESTO (autoridad operativa vigente)

- **Cierre explícito del flujo de distribución** para v0.39.0 en este repo:
  - **GitHub Releases ZIP** — canal primario; ZIP SHA-256 `385b140c…cbb8`.
  - **Multi-version installer** — `scripts/install-pipelinek.sh` (DIST-2) en main (commit `9a4a09a3`). SHA-256 del script: `f86d1d2f3edcf22a9c568c0f303e59074eb389591e37c3710cc60346d8d983f7`. 22/22 tests manuales verdes.
  - **SDKMAN, mise, asdf-vm, Homebrew, OCI, Scoop** — todos viven en el harness `pipelinek-release-harness`, no en este repo. Marcados como `Future (harness)` o `Pending (harness)` en `DISTRIBUTION_ROADMAP.md` §1 y en `README.md` §Distribution channels.

- **Directiva del operador 2026-09-24T11:20Z** que confirma el modelo:
  - SDKMAN no es necesario para distribuir PipelineK ahora mismo.
  - El roadmap contempla varios canales y ya tenemos dos opciones utilizables.
  - Ambos consumen el mismo artefacto; SDKMAN sigue siendo un canal pendiente, no un requisito para utilizar el producto.
  - Conviene separar distribución (cerrada en main) de PR #76 (candidata WU-RP-043, código del motor, OPEN).
  - La siguiente release pasará por el nuevo harness y su proceso de promoción antes de anunciarse como estable.

- **PRs mergeadas en este turno (6 totales, 11:02Z..11:22Z)**:
  - PR #73 → `12371ca0` (coordinación cross-repo + README + 3 docs roadmap + handover).
  - PR #74 → `9a4a09a3` (DIST-2 instalador bash + plan + recibo).
  - PR #75 → `aa2bad28` (state update post-DIST-2).
  - PR #77 → `fa08829f` (README §1.1 + Dist channels row).
  - PR #78 → `8b583983` (state update consolidado 4-PRs).
  - PR #79 → `a4a7370d` (DISTRIBUTION_ROADMAP §1 refresh + harness split).
  - PR #80 → `79a6e2fb` (README mise/asdf marcadas como `Future (harness)`).

- **main final del turno**: `79a6e2fb docs(readme): mark mise/asdf as 'Future (harness)' in §Distribution channels (#80)`.

- **Rebase de WU-RP-043** sobre nuevo main (HEAD nuevo `d8145632`, PR #76 OPEN, NO auto-mergeada por ser código de motor).

- **Estado consolidado**:
  - main: `79a6e2fb` (7 commits squash sobre línea base `74b40a65`).
  - DIST-1 (README manual): ✅ cerrado.
  - DIST-2 (instalador bash): ✅ cerrado.
  - DIST-3-docs (README §1.1): ✅ cerrado.
  - DIST-3 (OCI), DIST-4 (mise), DIST-5 (Homebrew), DIST-6 (SDKMAN), DIST-7 (asdf): ⏸ pendientes en el harness.
  - WIP del operador: 30 archivos intactos.
  - Binario estable: NO modificado.

- **Identidad material preservada**:
  - origin/main: `79a6e2fb`.
  - wu/rp-043-integration-clean: `d8145632` (rebased, PR #76 OPEN).
  - Ramas archivadas (mergeadas): `wu/dist-002-installer`, `wu/dist-003-readme-install-section`, `wu/dist-roadmap-refresh-2026-09-24`, `wu/readme-harness-split-mise-asdf`, `wu/state-2026-09-24-dist-2-merge`, `wu/rp-harness-coordination`.

### Próximos cortes (AUTO, sin pedir permiso adicional)

1. **PR #76 (candidata WU-RP-043 rebased)** — operador decide si mergea a main. **No la auto-mergeo** porque es código de motor; el operador debe validar la integración. Una vez mergeada, WU-RP-043 (self-hosted CI) entra al roadmap como completado; siguientes WUs en este repo: WU-RP-006 / WU-RP-007 (defensivas UP_FRESHNESS_CHECK_*) per ROADMAP §8 backlog post-RP-5.
2. **Inicializar `pipelinek-release-harness`** con `HARNESS_AGENTS_TEMPLATE.md` (55 líneas) como AGENTS.md raíz. Trabajo del operador en el otro repo.
3. **Migrar DIST-3 OCI / DIST-4 mise / DIST-5 Homebrew / DIST-6 SDKMAN / DIST-7 asdf** al harness una vez inicializado.
4. **No más trabajo en este repo** mientras PR #76 espera decisión.

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -5
```

Debe mostrar: HEAD `79a6e2fb docs(readme): mark mise/asdf as 'Future (harness)' in §Distribution channels (#80)`. Log anterior: `a4a7370d docs(dist): refresh §1 with v0.39.0 reality + harness split (#79)`.

## Reconciliación 2026-09-24T11:15Z — CONSOLIDADO POST-4-PRs: DIST-3-docs + rebase WU-RP-043 + state update (autoridad operativa vigente)

- **4 PRs mergeadas en este turno** (consolidado 2026-09-24T11:02Z..11:15Z):
  - **PR #73** → commit `12371ca0` (10 commits squash, 8 files, +1765/-86). Trae bloque AGENTS.md cross-repo + README reescrito + 3 docs roadmap + HARNESS_INVENTORY_HANDOVER.
  - **PR #74** → commit `9a4a09a3` (2 commits squash, 3 files, +693). Trae `scripts/install-pipelinek.sh` (DIST-2, SHA-256 `f86d1d2f3edcf22a9c568c0f303e59074eb389591e37c3710cc60346d8d983f7`) + plan + recibo. 22/22 tests manuales verdes.
  - **PR #75** → commit `aa2bad28` (1 commit squash, 1 file, +101/-1). State update de SESSION_POINTER.
  - **PR #77** → commit `fa08829f` (1 commit squash, 1 file, +32). README §1.1 'Install with the script (multi-version manager)' + fila en §Distribution channels.

- **Rebase de `wu/rp-043-integration-clean`** sobre nuevo main:
  - Operador autorizó "hazlo tu". Verificado cero solapamiento (`comm -12` vacío).
  - Stash WIP operador → rebase → stash pop → push forzado con `--force-with-lease`.
  - HEAD nuevo: `d8145632 perf(wu-rp-043-d002): increase Rp022ThroughputProbe warmup 1 → 3`.
  - **PR #76 abierta** (https://github.com/Rubentxu/pipeline-kotlin/pull/76). **NO mergeada automáticamente** porque es código de producto (4 commits byte-a-byte sobre 8 archivos del motor).
  - 4 commits: `d324a6ab`, `e14c0423`, `29b1f90a`, `d8145632`.

- **WIP del operador intacto** (30 archivos: 6 modified + 24 untracked) tras cada checkout de rama. Stash aplicado y restaurado sin pérdida.

- **Estado consolidado de main (`fa08829f`)**:
  - origin/main = `fa08829f docs(readme): add installer section §1.1 + Distribution channels row (#77)`.
  - 5 commits squash consecutivos sobre la línea base `74b40a65`.
  - Distribución: scripts/install-pipelinek.sh versionado + 3 docs nuevos (DISTRIBUTION_ROADMAP, RESPONSIBILITY_MIGRATION_ROADMAP, HARNESS_AGENTS_TEMPLATE) + HARNESS_INVENTORY_HANDOVER.
  - README: 4 secciones nuevas (Quickstart §1.1, fila Dist channels, §Verified, etc.).
  - AGENTS.md: 2 bloques nuevos (coordinación cross-repo, frontera de responsabilidad).
  - .agent/: SESSION_POINTER actualizado.

- **Estado del roadmap de PipelineK**:
  - RP-0..RP-5 cerrados (WU-RP-053 declarado CERTIFIED_FULL).
  - DIST-1 (README + manual): cerrado.
  - DIST-2 (instalador bash): cerrado.
  - DIST-3-docs (README §1.1): cerrado.
  - DIST-3 OCI, DIST-4 mise, DIST-5 Homebrew, DIST-6 SDKMAN: **pendientes en el harness**.
  - WU-RP-020..023 ya cerradas (RP-2); mi propuesta inicial era errónea, corregida por inspección de `RP2_GATE_RECEIPT.md`.

- **Identidad material preservada**:
  - origin/main: `fa08829f` (intacto, sólo documental + 1 script).
  - wu/rp-043-integration-clean: `d8145632` (rebased, byte-a-byte sobre los 4 commits originales).
  - wu/dist-002-installer: `0ab5eebb` (intacta, ya mergeada).
  - wu/rp-harness-coordination: `596f57eb` (intacta, ya mergeada).
  - wu/dist-003-readme-install-section: `3fc42706` (intacta, ya mergeada).
  - Binario estable: NO modificado.
  - WIP del operador: NO modificado.

### Próximos cortes (AUTO, sin pedir permiso adicional)

1. **PR #76 (candidata WU-RP-043 rebased)** — el operador decide si mergea a main. No la auto-mergeo porque es código de motor; el operador debe validar la integración.
2. **Inicializar `pipelinek-release-harness`** pegando `HARNESS_AGENTS_TEMPLATE.md` (55 líneas) como AGENTS.md raíz. Trabajo del operador en el otro repo.
3. **Migrar DIST-3 OCI, DIST-4 mise, DIST-5 Homebrew, DIST-6 SDKMAN al harness** una vez inicializado. Distribución cross-repo formalizada en DISTRIBUTION_ROADMAP §8.
4. **Sin más trabajo en este repo** mientras PR #76 espera decisión. Si el operador autoriza merge de PR #76, proseguir con WU-RP-006 / WU-RP-007 (UP_FRESHNESS_CHECK_*_RACE / TWO_PHASE_INVALIDATION) per ROADMAP §8 backlog post-RP-5.

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -5
```

Debe mostrar: HEAD `fa08829f` con mensaje `docs(readme): add installer section §1.1 + Distribution channels row (#77)`. Próximo log `aa2bad28 docs(agent): update SESSION_POINTER for WU-DIST-2 closure (#75)`.

## Reconciliación 2026-09-24T11:09Z — WU-DIST-2 CERRADA: instalador bash + PR #74 MERGED (autoridad operativa vigente)

- **Ciclo 7 cerrado — WU-DIST-2 instalador bash versionado**.
  - Rama: `wu/dist-002-installer` (HEAD `0ab5eebb feat(dist): add install-pipelinek.sh autonomous installer (DIST-2)`).
  - 2 commits sobre el nuevo main `12371ca0`: plan (`1c6772c9`) + impl+recibo (`0ab5eebb`).
  - Script: `scripts/install-pipelinek.sh` (390 líneas, ejecutable, `set -Eeuo pipefail`, shellcheck limpio, bash -n OK).
  - SHA-256 del script: `f86d1d2f3edcf22a9c568c0f303e59074eb389591e37c3710cc60346d8d983f7`.
  - Subcomandos: install, use, list, uninstall, doctor, help.
  - URL allowlist fail-closed (github.com, objects.githubusercontent.com).
  - SHA-256 via `sha256sum -c -`; reescribe path del `.sha256` para apuntar al basename local.
  - Idempotente (install/uninstall/use).
  - Sin sudo, sin daemon, sin package manager.
  - Probado contra el binario público oficial v0.39.0 (descargado de GitHub Releases, NO de installDist local): 22/22 tests manuales verdes.

- **PR #74 mergeada** a `2026-09-24T11:08:57Z`.
  - `gh pr merge 74 --squash --delete-branch=false` ejecutado.
  - Merge commit: pendiente confirmar post-fetch (squash de los 2 commits en 1).
  - URL: https://github.com/Rubentxu/pipeline-kotlin/pull/74 (CLOSED).

- **Identidad material post-WU-DIST-2**:
  - origin/main: pasa de `12371ca0` a `12371ca0`+1 squash (pendiente fetch).
  - wu/dist-002-installer: `0ab5eebb` (conservada).
  - WIP del operador: preservado en working tree.
  - Binario estable: NO modificado.

- **Próximo corte ejecutable (AUTO)**:
  - Confirmar el merge con `git fetch origin main && git checkout main && git pull origin main`.
  - Si el operador quiere anexar el instalador al README (`§Verified` o nueva sección §Install), abrir WU-DIST-3-docs.
  - Si no, WU-RP-020 caracterización SqliteEventStore per ROADMAP §3 RP-2 (siguiente WU en el roadmap del motor, no de la frontera de distribución).

### Primer comando de reanudación (WU-DIST-2 cerrada)

```bash
git fetch origin main && git checkout main && git pull origin main && \
git rev-parse HEAD && git log --oneline -3
```

Debe mostrar: HEAD = squash de `feat(dist): add install-pipelinek.sh autonomous installer (DIST-2)`, main en `12371ca0` + 1.

## Reconciliación 2026-09-24T11:03Z — INTEGRACIÓN A MAIN: PR #73 MERGED (autoridad operativa vigente)

- **Merge exitoso de PR #73 a main**: `gh pr merge 73 --squash --delete-branch=false` ejecutado a 2026-09-24T11:02:57Z.
  - Merge commit: `12371ca0dd40f6ba7eafa50604ee4c117a5b5e8d`.
  - origin/main pasa de `74b40a65` a `12371ca0`.
  - Squash de los 10 commits documentales en 1 solo commit limpio.
  - PR accesible: https://github.com/Rubentxu/pipeline-kotlin/pull/73 (CLOSED).

- **Cambios en main** (8 files, +1765/-86):
  - `AGENTS.md` (+43): bloques 'Release candidates' + 'Frontera de responsabilidad'.
  - `README.md` (+210/-83): reescrito para el usuario, VERSION+sha256sum, sin SDKMAN en el primer bloque.
  - `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` (NUEVO, 163): tabla con 46 UATs a migrar al harness.
  - `docs/v2/05-roadmap/HARNESS_AGENTS_TEMPLATE.md` (NUEVO, 55): plantilla AGENTS.md del harness.
  - `docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md` (NUEVO, 153): DIST-1..DIST-6 con criterios de cierre.
  - `docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md` (NUEVO, 131): material movible verbatim al harness.
  - `.agent/SESSION_POINTER.md` (+345/-3): reconciliación 2026-09-24T10:55Z.
  - `.agent/WORK_JOURNAL.md` (+665): entrada 2026-09-24T10:55Z del ciclo README+ROADMAP.

- **Verificación empírica contra binario público oficial v0.39.0** (en el ciclo anterior, sigue válida):
  - ZIP SHA-256 `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`.
  - Binary SHA-256 `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`.
  - Ejemplos 01–10 ejecutados, exit codes y outcomes documentados en el README.
  - `pipelinek doctor`: jdk 24.0.2 (Eclipse Adoptium), os Linux, workdir writable.

- **Repuesta a las 5 preguntas del operador antes de la integración:**
  1. Distribuibles resueltos: **parcialmente**. DIST-1 cerrado; DIST-2..DIST-6 planeados con tabla cross-repo. SDKMAN pendiente de vendor onboarding.
  2. Delegado al harness: **DIST-3 OCI, DIST-5 Homebrew, DIST-6 SDKMAN**. Este repo se limita al ZIP+SBOM+manifest.
  3. Separación de responsabilidades: AGENTS.md + 4 docs nuevos, 10 commits documentales, 0 código tocado.
  4. Traspaso entre repos: ZIP+SHA-256+manifest; issue con huella estable (contrato+escenario+tipo+causa, NO SHA candidata); resultado estructurado en el harness; PR comments NO son fuente de verdad; identidades GH separadas (Checks:write vs Contents:write).
  5. Reglas en AGENTS.md de cada proyecto: **asimétrico**. pipeline-kotlin AGENTS.md SÍ tiene los 2 bloques. pipelinek-release-harness AGENTS.md NO existe todavía; HARNESS_AGENTS_TEMPLATE.md está como propuesta en este repo.

- **Pendiente para el operador (decisión):**
  1. **Rebase de wu/rp-043-integration-clean sobre el nuevo main** (HEAD 12371ca0). Verificado: cero solapamiento entre los archivos de la candidata (8) y los de la rama coord (8). El rebase es trivial; cambia el SHA del commit de la candidata pero conserva los archivos byte-a-byte. Si se rebasea, hay que re-apuntar el recibo `WU_RP_043_INTEGRATION_CLEAN_RECEIPT.md` a la candidata rebased.
  2. **Inicializar pipelinek-release-harness con su AGENTS.md** pegando HARNESS_AGENTS_TEMPLATE.md. Trabajo del operador en el otro repo; necesario para tener contrato simétrico.
  3. **Cerrar la issue #2 del harness** si se considera entregada la documentación.

- **Próximo corte ejecutable (AUTO):**
  **WU-DIST-2** — `scripts/install-pipelinek.sh` (instalador bash versionado) sobre el nuevo main (`12371ca0`), en rama nueva `wu/dist-002-installer`. Siguiente hongo tangible de la frontera pipeline-kotlin per DISTRIBUTION_ROADMAP §2. Distribución cross-repo:
    - DIST-2 (instalador bash): pipeline-kotlin.
    - DIST-3 (OCI), DIST-5 (Homebrew tap), DIST-6 (SDKMAN): pipelinek-release-harness.
  Antes de codificar: commit de planificación tipo `docs(wu-dist-002): installer design + subcommands contract`. Plan: install <v>, use <v>, list, uninstall <v>, doctor. Fail-closed ante digest mismatch. URL allowlist (github.com/Rubentxu/pipeline-kotlin/releases/download/v$VERSION/...) para evitar ZIP hostil.

### Identidad material post-integración

- origin/main: `12371ca0` (squash de los 10 commits).
- HEAD actual (working): `12371ca0` en `main`.
- wu/rp-043-integration-clean: `262cc11e` (intacto, basado en `74b40a65`).
- wu/rp-harness-coordination: `596f57eb` (conservada, ya mergeada).
- wu/rp-043-self-hosted-ci: `12614b60` (intacto).
- HEAD base pre-RP-5: `9ed0a4f2` (intacto).
- Binario estable: NO modificado.
- WIP del operador: preservado en working tree (Main.kt, WorkspaceOperations.kt, CanonicalRuntimeCapabilityAccess.kt, scripts/, fixtures, recibos WU-RP-053, paquete overlay, .agent/TESTING-STATE.md).

### Primer comando de reanudación

```bash
git checkout main && git pull origin main && \
git rev-parse HEAD && \
git log --oneline -1
```

Debe mostrar: main en `12371ca0`, mensaje `docs(agents,uat,readme,roadmap): cross-repo coordination + user README + DIST-1..DIST-6 (#73)`.

### Acción de este ciclo (AUTO)

1. **4 commits atómicos sobre `wu/rp-harness-coordination`** (HEAD `6dcef432`, base `origin/main` 74b40a65):
   - `cc372595` docs(readme): rewrite README.md for users, drop internal links.
   - `6d6d9752` docs(readme): correct distribution channels — SDKMAN is pending, not available.
   - `ed610a08` docs(readme): split Distribution channels table per installer.
   - `6dcef432` docs(readme,roadmap): VERSION+sha256sum snippet + DIST-1..DIST-6 roadmap.

2. **Verificación empírica contra binario público `pipelinek-0.39.0`** (Digest ZIP `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`; Digest binario `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`):
   - Descarga `pipelinek-0.39.0.zip` (91.416.100 bytes, 1.5s).
   - Descomprime en `/tmp/pipelinek-verify/pipelinek-0.39.0/`.
   - Ejemplos 01..10 ejecutados con el binario público oficial:
     - 01-hello: exit=0, outcome=success.
     - 02-multi-stage: exit=0, outcome=success.
     - 03-shell: exit=0, outcome=success.
     - 04-kotlin-control-flow: exit=0, outcome=success.
     - 05-failing-step: exit=1, outcome=failure (StepFailed kind=SCRIPT, message="shell exited with code 3").
     - 06-durable: exit=0, outcome=success (con `--db`).
     - 07-catch-error: exit=0, outcome=unstable.
     - 08-parallel: exit=0, outcome=success.
     - 09-retry: exit=0, outcome=success (con `--db`).
     - 10-timeout: exit=1, outcome=failure (TimeoutScheduled, StepFailed, abort).
   - `pipelinek doctor`: jdk 24.0.2 (Eclipse Adoptium), os Linux 7.2.4-ogc3.1.fc44.x86_64, workdir writable.

3. **Contenido del README reescrito** (130 → 231 líneas, +117/-34 vs cc372595, +205/-26 vs 6d6d9752, etc.):
   - Hero: VERSION=0.39.0; curl -fL; sha256sum -c -; unzip; ./pipelinek-VERSION/bin/pipelinek {version,doctor,run}.
   - Quickstart §1: misma receta + nota blockquote 'SDKMAN is not yet available' con enlace al §Distribution channels.
   - §Capabilities: capacidades reales alineadas con examples/07-10 (parallel, retry, timeout, catchError disponibles).
   - §Examples: tabla con 10 ejemplos y exit code esperado (0/1), con instrucciones para SDKMAN o repo.
   - §Distribution channels: tabla de 7 canales (GitHub Releases ZIP / Direct download / SDKMAN / Homebrew / mise / asdf / Scoop / Container) con estado Available/Pending/Future + enlaces a LFC9-004/006/007, WU-LPR-080, ADR-0089.
   - §Verified against the published artifact: ZIP SHA-256, binary SHA-256, certified commit, examples results, doctor output.

4. **DISTRIBUTION_ROADMAP.md (NUEVO, 153 líneas)**: hoja de ruta DIST-1..DIST-6 con entregables y criterios de cierre por hito (DIST-1 cerrado hoy; DIST-2 instalador autónomo `scripts/install-pipelinek.sh`; DIST-3 imagen OCI mínima; DIST-4 mise Aqua; DIST-5 Homebrew tap; DIST-6 SDKMAN/Scoop/native cuando proceda). Distribución de tareas cross-repo: este repo sólo construye ZIP+SBOM; harness verifica/publica OCI/formula Homebrew/etc.

5. **WIP del operador preservado** intacto en working tree (recuperable vía `git stash`):
   - `scripts/run-pipelinek.sh`, `scripts/run-pipelinek`, `scripts/verify-rp-043.py`, `scripts/gen-certification-ledger.py`.
   - `docs/pipeline-kotlin-config-overlay-package/`.
   - 33 fixtures `v2/compatibility/*.pipeline.kts` (baselined).
   - `v2/pipeline-application/src/main/{Main.kt, WorkspaceOperations.kt, CanonicalRuntimeCapabilityAccess.kt}` y tests WU-RP-053.
   - Recibos WU-RP-053 y WU-RP-043 (`docs/v2/07-uat/*.md`).
   - `.agent/{SESSION_POINTER,TESTING-STATE,WORK_JOURNAL}.md`.

### Identidad material preservada

- HEAD base pre-RP-5: 9ed0a4f2 (intacto).
- HEAD candidata WU-RP-043: 262cc11e en `wu/rp-043-integration-clean` (intacto, NO tocado, NO cherry-pickeado).
- HEAD rama coordinación: `6dcef432` en `wu/rp-harness-coordination` (4 commits atómicos sobre origin/main 74b40a65).
- origin/main local: 74b40a65 (intacto).
- Binario estable `/home/rubentxu/.local/bin/pipelinek` (92d0f67d…): NO modificado.
- ZIP oficial `pipelinek-0.39.0.zip` SHA-256 `385b140c…cbb8` (verificado) disponible en `/tmp/pipelinek-verify/`.

### DECLARACIÓN

- DIST-1 (README + instalación manual del ZIP) **cerrado** en este turno.
- SDKMAN sigue **pendiente** (vendor onboarding); no bloquea otras releases per directiva del operador 2026-09-24T10:53Z.
- Imagen OCI (DIST-3) pertenece al harness; este repo se limita a entregar el ZIP canónico.
- No push de `wu/rp-harness-coordination` este turno (espera confirmación del operador).

### Pendiente para el operador

1. Decidir si abre PR documental separada de #73 o anexa los 4 commits del README al handover.
2. Decidir si puja `wu/rp-harness-coordination` a origin.
3. Decidir si promueve `wu/rp-043-integration-clean` a main (con la nueva arquitectura, ya no es prerrequisito para entrega al harness).

### Próximo corte ejecutable (AUTO, sin pedir permiso)

**WU-RP-020 — caracterización SqliteEventStore** sobre `origin/main` (`74b40a65`), en rama nueva `wu/rp-020-sqlite-event-store-characterization`. Es la siguiente WU per ROADMAP §3 (RP-2 arranca con WU-RP-020). Caracterizar: sequence asignada frente a orden de inserción/lectura, flush/close con productores activos, reinicio, gap, error de writer, replay y arrays anidados. **No modificar el contrato de secuencia** hasta reproducir o descartar el riesgo. Test determinista, criterios observables, sin tocar la candidata `262cc11e`.

Antes de arrancar: planificar alcance + criterios observables + invariantse, en commit de planificación tipo `docs(wu-rp-020): characterization plan`. Sin tests aún (TDD red-green).

### Primer comando de reanudación

```bash
git rev-parse --abbrev-ref HEAD && git rev-parse HEAD && \
git log --oneline origin/main..HEAD && \
git status --short | head
```

Debe mostrar: rama `wu/rp-harness-coordination`, HEAD `6dcef432`, 4 commits sobre origin/main, README.md y docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md en `M`/`A` y el WIP del operador preservado.

## Reconciliación 2026-09-24T11:50Z — CONSUMIDOR MÍNIMO DEL CIRCUITO CROSS-REPO (PR #87)

- **Directiva del operador (11:48Z)**: "Implementa en PipelineK únicamente el consumidor mínimo necesario mediante su flujo de coordinación autorizado; no crees otro ledger". "No termines proponiendo otra ronda de prompts".
- **Implementación entregada (PR #87 → `5f574eeb`)**:
  - `scripts/consult-harness-verdict.py` (412 líneas): consumidor mínimo del veredicto del harness desde GitHub API. Clasifica tipadamente PASS / FAIL_REPRODUCIBLE / FAIL_NON_REPRODUCIBLE / INVALID / MISSING. Cruza ZIP SHA-256 del veredicto con GitHub Releases de pipeline-kotlin. Verifica publisher allowlist (`pipelinek-harness[bot]` por defecto). Si FAIL_REPRODUCIBLE con `--open-issue`, abre issue con huella estable; sin credenciales imprime `ISSUE_PENDING` con exit 75 sin stack trace. Recibo inmutable en `docs/v2/07-uat/RECEIPTS/consult/<candidate>-<ts>/verdict.json`.
  - `scripts/test_consult_harness_verdict.py` (9 tests): MISSING / INVALID ZIP / INVALID publisher / PASS / FAIL_REPRODUCIBLE sin/con issue / FAIL non-repro / idempotency / JSON output. **9/9 verdes.**
  - Recibo real MISSING contra `v0.39.0` commiteado: el harness NO tiene `evidence/v0.39.0/verdict.json` aún (esperado, H0.3 sin cerrar).

### DECLARACIÓN

- **NO se crea ledger paralelo**. El recibo vive en `docs/v2/07-uat/RECEIPTS/consult/`, convención existente del proyecto.
- **NO se toca el harness**. Toda interacción es read-only vía GitHub API sobre el repo del harness.
- **NO se auto-mergea PR #76** (motor, no circuito). El operador lo confirma a las 11:46Z y 11:48Z.
- **NO se reabre una ronda de prompts**. Los prompts del harness siguen anclados al SHA `ca2a91c0` (PR #86).
- **Estado del circuito desde mi lado**: consumidor mínimo entregable. El agente del harness ahora puede publicar veredictos con huella estable, y el consumidor los ingestará sin necesidad de tocar este repo.

### Identidad material del proyecto (POST #87)

- HEAD `pipeline-kotlin/main`: `5f574eeb` (PR #87 mergeada).
- HEAD `pipelinek-release-harness/main` (verificado por el operador 11:46Z): `ca2a91c0`.
- PR #76 (motor): sigue OPEN por decisión del operador (no circuito).
- Binario estable: sin modificar.
- WIP del operador: 30 archivos intactos (stash + restore limpio).

### Pendiente (responsabilidad del agente del harness, no de pipeline-kotlin)

1. Publicar `evidence/v0.39.0/verdict.json` desde el harness (cuando arranque H0.3 / próximo requisito con Podman real).
2. Verificar identidad del productor (permisos Checks + Contents, separación verificador/publicador per HARNESS_INVENTORY §5.4).
3. Cerrar el ciclo de la issue de ensayo: defecto reproducible → issue → candidata correctora → reverify-cycle → cierre.
4. Demostrar casos negativos (INVALID ZIP, publisher NO allowlisted, MISSING) contra el consumidor; mis tests ya cubren esos casos negativos del lado consumidor.
5. Ejecutar Spring REST en Podman (build/test real + fallo intencionado).

### Primer comando de reanudación

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
git rev-parse --abbrev-ref HEAD && git rev-parse HEAD && \
git log --oneline origin/main..HEAD && \
git status --short | wc -l && \
python3 scripts/consult-harness-verdict.py --candidate v0.39.0 2>&1 | tail -3
```

Debe mostrar: rama `main`, HEAD `5f574eeb`, sin commits sobre origin/main, `30` (WIP del operador), `exit_code=4 / status=MISSING` mientras el harness no haya publicado `evidence/v0.39.0/verdict.json`.
