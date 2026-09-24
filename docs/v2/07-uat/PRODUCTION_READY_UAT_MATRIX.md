# Matriz de aceptación UAT — Production Ready V2

**Versión:** 2026-09-23 (actualización por auditoría honesta tras advertencia del operador). **Baseline:** main `87d7f2ef` (post WU-RP-045 + WP-RP-046 UAT-RP-019/020/021 init).
**Importante:** esta matriz se creó como DOCUMENTACIÓN; los resultados de las UAT se registran por recibo en `docs/v2/07-uat/`. v0.39.0 tiene su receipt histórico separado. Referencia normativa: CERTIFICATION_PROTOCOL.md.

## Estado por UAT a HEAD `87d7f2ef`

| ID | Estado | Recibo | Notas |
|---|---|---|---|
| UAT-RP-001 | COVERED | LPR-0 CI runs 35697487778 / 35705391067 / 35703522593 etc. | 7/7 jobs SUCCESS sostenido. |
| UAT-RP-002 | COVERED | LPR-0 CI run 35705391067 (artifact-upload correcto) | workflow `lpr0-ci.yml` con `v2/gradlew` y `actions/upload-artifact@v4`. |
| UAT-RP-003 | COVERED | ADR-0069 / WU-LPR-098 | core + plugin externo misma ruta genérica; missing capability rechazado. |
| UAT-RP-004 | COVERED | UatDsl001 / UatDsl003 / UatDsl005 / UatDsl006 | DSL scripts válidos compilan; negativos con diagnóstico. |
| **UAT-RP-005** | **PARTIAL (KNOWN_LIMITATION inv 3)** | WU_RP_010_RECEIPT.md / ADR-0095 | inv 1, 2, 4 cubiertas. inv 3 (MANIFEST.json archivado) **diferida a WU-RP-010 r2** por ADR-0095 (security boundary + contract freeze). |
| UAT-RP-006 | COVERED | WU_RP_011_RECEIPT.md | r1 commit `ae6b334e`. |
| UAT-RP-007 | COVERED | WU_RP_011_RECEIPT.md | r2 commit `d3e9b9b6`. |
| UAT-RP-008 | COVERED | WU_RP_012_RECEIPT.md | commit `b3f74e93`. |
| UAT-RP-009 | COVERED | WU_RP_012_RECEIPT.md | rp012-roundtrip test (bit-exact stash/unstash). |
| UAT-RP-010 | COVERED | UatEvt001 / UatEvt002 (replay) + WU-LPR-xxx event codec | roundtrip completo variantes. |
| UAT-RP-011 | COVERED (RP-2) | SqliteEventStoreConcurrencyCharacterisationTest (10 tests, pipeline-events) | N producers/1 writer, flush barrier, restart MAX(sequence), replay orden rowid, arrays anidados. |
| UAT-RP-012 | COVERED (RP-1) | WULpr011ResumeLifecycleUatTest + retry control journal (RETRY-D) | kill/resume sin duplicar efecto; terminal resume reuse. |
| UAT-RP-013 | COVERED (RP-1) | StrictFingerprintDivergenceDetector + coordinator tests | divergence fail-closed antes de efectos. |
| UAT-RP-014 | COVERED (RP-1/2) | WULpr302RetryEngineTest, timeout/parallel suites, B11ContextBlocks, ExecutionPathsCharacterisationTest P4/P6 | golden IR/eventos/outcomes. |
| UAT-RP-015 | COVERED (RP-2) | StreamingRedactorTest (23) + Lpr011SecretRedactionTranscriptUatTest + Lpr011r2SecretRedactionAtRestUatTest | secreto dividido entre chunks redactado en transcript/eventos/at-rest. |
| UAT-RP-016 | COVERED (RP-2) | WU_RP_022_RECEIPT.md + rp022_perf_baseline.sh (SHA 9393e34a) | M1-M6 medidas + SLOs aprobados (anexo del receipt). RSS soak sin SLO: documentado. |
| UAT-RP-017 | COVERED (RP-2) | WURp023ObservationModesUatTest (HF2, binario real) | run array stdout, events jsonl replay igual al stream del run, cursor reconnect sin re-ejecución, events verify PASS/FAIL/2, unknown run read-only. |
| UAT-RP-018 | COVERED (RP-4) | WURp045SandboxProfileTests + CLI pin (TC-003 ADR-0016 M5/M9) + `WU_RP_040_RECEIPT.md` (R2 SHA-pin, R3 supply chain) | LOCAL profile certificada a 12 caps + fail-closed pin en CLI para `os`. Marco previa PARTIAL obsoleta. |

| ID | Gate / capacidad | Escenario ejecutable y oráculo observable | Evidencia requerida para PASS |
|---|---|---|---|
| UAT-RP-001 | CI | checkout limpio del SHA; compilar y ejecutar domain/events/architecture/application/corpus sin jobs skipped | URL GitHub Actions, SHA, XML y exit 0 |
| UAT-RP-002 | Workflow | ruta válida v2/gradlew y artifact-upload correcto; simular fallo de arquitectura y recuperar su XML | workflow + job/log y artefacto descargable |
| UAT-RP-003 | Registry | core y plugin externo ejecutan misma ruta genérica; missing capability no llega al handler | resultado tipado, ausencia de efecto, fitness |
| UAT-RP-004 | DSL | script válido compila; tipos/receiver inválidos producen diagnóstico localizado sin ejecutar side effect | compiler tests positivo/negativo y CLI validate |
| UAT-RP-005 | Publish HTML | publicar index.html original y otro HTML; abrir informe y recalcular SHA256 de entradas FINALES | contenido byte a byte, paths, hashes, evento y rerun |
| UAT-RP-006 | HTML injection | nombres de fichero con comillas, ampersand, etiquetas/Unicode; índice no incorpora marcado activo del nombre | HTML final inspeccionado y test automatizado |
| UAT-RP-007 | Paths publish | reportDir fuera de workspace, enlace en directorio, enlace en fichero, traversal; rechazar antes de copiar | rechazo tipado, cero lectura/copias externas |
| UAT-RP-008 | Stash | symlink a archivo exterior, enlace en directorios origen/destino y nombre malicioso; no seguir escapes | snapshot de ambos árboles y errores tipados |
| UAT-RP-009 | Stash roundtrip | stage A stash, stage B unstash; mismo contenido y SHA; fresh/rerun/kill-resume por políticas declaradas | fixture real, manifests y journal íntegro |
| UAT-RP-010 | Event JSON | roundtrip completo de todas las variantes, arrays de objetos, caracteres especiales, payload nested | property tests y comparación íntegra del modelo |
| UAT-RP-011 | Concurrencia | N producers/1 writer con retrasos, restart y close concurrente; sin duplicados ni desorden respecto del contrato aprobado | sequence y orden, DB inspeccionada, hashes |
| UAT-RP-012 | Durable | matar proceso tras efecto persistido y antes de confirmación; restart sin duplicar efecto memoized | marker/counter=1, journal y eventos antes/después |
| UAT-RP-013 | Divergence | cambiar input/script y reusar runId; rechazo fail-closed antes de nuevos efectos | error tipado y marker inalterado |
| UAT-RP-014 | Body control | retry/timeout/parallel/scoped; errores, cancelación, contexto aislado, recuperación determinista | golden IR/eventos/journal/outcomes + kill/resume |
| UAT-RP-015 | Secretos | secreto dividido entre chunks, stdout/stderr, todos los modos, errores, eventos y archivos | bytes secretos ausentes en superficies observables |
| UAT-RP-016 | Performance | salida ≥200 MiB + soak ≥1 GiB, renderer normal y consumidor lento | CPU, RSS, duración, lag, bytes y presupuesto aprobado |
| UAT-RP-017 | Observación | normal/events/full/console/quiet, jsonl, cursor/follow reconectado; resultado/journal idénticos | compare snapshots y reconexión sin ejecutar de nuevo |
| UAT-RP-018 | Recursos | proceso con timeout/cancel y espacio/CPU/memoria acotados según perfil OS; cleanup | PID/exit, recursos, ficheros y política documentada |
| UAT-RP-019 | COVERED (RP-5, opt-in) | WURp019GradleRealUatTest (Gradle 8.14.5 real + fixture real; opt-in UAT_RP_019_RUN=1) | happy: pipelinek exit 0, JAR escrito. failure: pipelinek exit !=0. Run verde HEAD 87d7f2ef. |
| UAT-RP-020 | COVERED (RP-5, opt-in) | WURp020MavenRealUatTest (Maven 3.9.9 + fixture real; opt-in UAT_RP_020_RUN=1) | happy: pipelinek exit 0; failure: pipelinek exit !=0. Run verde HEAD 87d7f2ef. |
| UAT-RP-021 | COVERED (RP-5, opt-in) | WURp021NodeRealUatTest (Node 25.9.0 + fixture real; opt-in UAT_RP_021_RUN=1) | happy: dist/output.txt >0, marker presente. failure: pipelinek exit !=0. Run verde HEAD 87d7f2ef. |
| UAT-RP-022 | COVERED (RP-5) | WU_RP_042_S1_SLICE_RECEIPT.md (release byte-idéntico histórico, SHA 65afc24d) + **recertificación en este HEAD `2a66317c` (2026-09-23)**: doble build `./gradlew :pipeline-application:distZip` (incremental + `--rerun-tasks`) produce ZIPs idénticos sha256=`6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1`, 92 070 649 bytes, 44 entradas, todas con fecha `02-01-1980 00:00` (reproducible flags activos `isPreserveFileTimestamps=false`, `isReproducibleFileOrder=true`). Instalación limpia + `pipelinek version` → `pipeline 0.39.0` exit 0; `pipelinek doctor` → exit 0. Success path (`01-basic.pipeline.kts`): 9 events, `CompilationStarted`→`RunFinished success`, exit 0. Failure path (`sh "exit 1"`): exit 1 (verificación adicional). Slip-guard preservado: ZIP del SHA probado, no se reutilizó el antiguo. |
| UAT-RP-023 | COVERED (RP-5) | `WU_RP_040_RECEIPT.md` (R3.1 SBOM + R3.2 secret-scan + **R3.3 SAST detekt** + **R3.4 Dependabot**) + receipts individuales CI: SBOM CycloneDX job verde (49 componentes en `bom.json/xml`, sha256=`223f65d2...8cd1` y `7d261bb9...6b77`); gitleaks CI job verde con allowlist `.gitleaks.toml` documentada. **R3.3 CERRADO (WU-RP-040 R5, commits 4b59bbc8/0900e34a):** detekt 2.x sobre 21 módulos Kotlin, config curada + baseline de deuda congelado, ratchet verificado por canario, job `sast (detekt)` en lpr0-ci.yml; verde local en HEAD actual (exit 0). **R3.4 CERRADO (WU-RP-040 R6, commit 4663a3eb):** `.github/dependabot.yml` gradle `/v2` semanal + github-actions `/`; Dependabot Updates job operativo (run 36015383105 success). |
| UAT-RP-024 | KNOWN_LIMITATION | ningún dogfood ejecutable | requiere 2 repos ajenos; WU-RP-046 puede documentar 1 repo (este mismo) si se considera evidencia parcial. |
| UAT-RP-025 | NO_APLICA | SDKMAN_READY no declarado en v0.39.0 ni HEAD | canal opcional; no compromete RP-5 Gate local. |
| UAT-RP-026 | Remote (RP-8) | lease/fencing, reconnect/ACK, replay/cancel y partición de red | sólo exigible para el perfil REMOTE, no para local-v1 |
| UAT-RP-027 | Jenkins (RP-9) | eventos live de stages, reinicio dashboard, mismos outcomes y autorización | sólo exigible para el adaptador Jenkins |

**Criterio de obligatoriedad:** 001–024 son candidatas obligatorias para el perfil local cuando su capacidad forme parte del contrato anunciado; 025 exige la declaración SDKMAN_READY; 026 exige REMOTE_READY; 027 exige JENKINS_READY. Toda exclusión de una candidata local exige un recorte explícito del perfil aprobado y trazado, NUNCA marcar NO_APLICA tras un fallo sin decisión normativa. Para cada fila actualizar un receipt por SHA con PASS/FAIL/BLOCKED/NOT_RUN, entorno, argv, XML, huella y enlace al test; no editar esta tabla para simular ejecución.

**Fallos conocidos a resolver primero:** UAT-RP-005 inv 3 (MANIFEST.json archivado, ADR-0095, NO se reabre en RP-5); UAT-RP-024 dogfooding (KNOWN_LIMITATION).

**Update 2026-09-24 (reconciliación de deuda cerrada):** UAT-RP-022 recertificado (ver update 2026-09-23). UAT-RP-023 receipt consolidado: R3.3 (detekt SAST, WU-RP-040 R5) y R3.4 (Dependabot, WU-RP-040 R6) CERRADOS, fila actualizada. M3 SIGPIPE caracterizado en WU-RP-046 R2 (NO_REPRODUCIBLE_AT_CURRENT_HEAD, QUARANTINED) — deja de figurar como pendiente. WU-RP-040 R8 categoría C cerrada (commit bc7c05d4): 10 mutantes de replay cubiertos; el RED destapó defecto latente MEMOIZED SKIP con efectos mixtos, corregido sin impacto en Steps certificados. Deuda viva restante del triage R8: A (44 data-class equals/hashCode, aceptada), B (45 guards cubiertos por UAT de proceso), D (29 equivalentes).

**Update 2026-09-23 (recertificación + supply chain consolidado):** UAT-RP-022 RECERTIFICADO en HEAD 2a66317c (doble build bit-idéntico, ZIP sha256 verificado, instalación limpia con `pipeline 0.39.0` exit 0). UAT-RP-023 emitido como COVERED con KNOWN_GAP documentado (SAST + dependency-audit NO implementados — pendiente WU-RP-040 R5). WU-RP-040 RECEIPT consolidado publicado con R1 Kover PARTIAL (sólo 2 módulos cubiertos, agregado root vacío), R2 SHA-pin COVERED (34/34), R3.1 SBOM + R3.2 secret-scan COVERED, R3.3 SAST + R3.4 dependency-audit KNOWN_GAP, R4 pitest PARTIAL (mutation score 42% domain / 50% SDK sin triage). Defecto "CLI exit-code-0-on-typed-exception" del WU-RP-046 R1 re-caracterizado en este SHA: **NO es un defecto del binario pipelinek** (el binario retorna exit=1 correctamente cuando un step falla — verificado con `sh("exit 1")` exit=1); era un artefacto del bash pipe `... | tail` que enmascaraba exit codes en los scripts de tests. Documentado y mitigado en WU-RP-046 R1 con workaround `|| { echo ORACLE_X_BAD_FAIL; exit 1; }`.
