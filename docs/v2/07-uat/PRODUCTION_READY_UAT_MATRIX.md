# Matriz de aceptación UAT — Production Ready V2

**Versión:** 2026-09-22. **Baseline:** main `f4aa20dcb709aad3fadb6922f26b4325e49ba61b` (post RP-1 closure).
**Importante:** esta matriz se creó como DOCUMENTACIÓN; los resultados de las UAT se registran por recibo en `docs/v2/07-uat/`. v0.39.0 tiene su receipt histórico separado. Referencia normativa: CERTIFICATION_PROTOCOL.md.

## Estado por UAT a HEAD `f4aa20dc`

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
| UAT-RP-018 | PARTIAL (RP-4) | --sandbox-profile {none,local}; 'os' rechazado (ADR-0016) | límites de recursos OS requieren M5/M9 (RP-4/5); best-effort fuera de perfil no confiable. |

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
| UAT-RP-019 | Gradle real | construir y fallar build real desde installed CLI; artefacto final existe y failure sale no-cero | ZIP instalado, exit, hash, eventos y diagnóstico |
| UAT-RP-020 | Maven real | mismo contrato con wrapper/proyecto Maven completo | ZIP instalado, exit, hash y eventos |
| UAT-RP-021 | Node real | mismo contrato con lockfile y tests Node | ZIP instalado, exit, hash y eventos |
| UAT-RP-022 | Release | dos builds aislados mismos inputs; ZIP byte-idéntico; instalar limpio, version/doctor/validate/run | hashes iguales, SBOM, manifest, CI y logs |
| UAT-RP-023 | Cadena suministro | dependencias, acciones, secretos y artefactos revisados; vulnerabilidades priorizadas | SBOM + SCA/SAST/secret scan con fechas y decisiones |
| UAT-RP-024 | Dogfooding | dos repos distintos con ejecuciones repetidas, upgrade, release y fallo diagnosticado | diario reproducible, historial, defectos, tiempos |
| UAT-RP-025 | SDKMAN (canal opcional para ZIP) | publicar → instalar limpio → correr proyecto → verificar default sólo tras PASS | URL vendor, UAT y estado remoto |
| UAT-RP-026 | Remote (RP-8) | lease/fencing, reconnect/ACK, replay/cancel y partición de red | sólo exigible para el perfil REMOTE, no para local-v1 |
| UAT-RP-027 | Jenkins (RP-9) | eventos live de stages, reinicio dashboard, mismos outcomes y autorización | sólo exigible para el adaptador Jenkins |

**Criterio de obligatoriedad:** 001–024 son candidatas obligatorias para el perfil local cuando su capacidad forme parte del contrato anunciado; 025 exige la declaración SDKMAN_READY; 026 exige REMOTE_READY; 027 exige JENKINS_READY. Toda exclusión de una candidata local exige un recorte explícito del perfil aprobado y trazado, NUNCA marcar NO_APLICA tras un fallo sin decisión normativa. Para cada fila actualizar un receipt por SHA con PASS/FAIL/BLOCKED/NOT_RUN, entorno, argv, XML, huella y enlace al test; no editar esta tabla para simular ejecución.

**Fallos conocidos a resolver primero:** UAT-RP-001/002 ruta Gradle/CI; 005–009 integridad, HTML y symlinks; 010 serialización. Los restantes requieren baseline en HEAD aunque existan recibos históricos.
