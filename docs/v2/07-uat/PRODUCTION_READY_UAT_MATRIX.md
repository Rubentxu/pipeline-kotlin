# Matriz de aceptación UAT — Production Ready V2 (CONTRATO NORMATIVO)

**Versión:** 2026-09-26 (PRDY-003: separación de normativa y estado mutable).
**Baseline normativo:** `87d7f2ef` (post WU-RP-045 + WU-RP-046 UAT-RP-019/020/021 init).
**Importante:** este archivo contiene **únicamente la matriz normativa** (Gate / Escenario ejecutable / Evidencia requerida). El estado actual por UAT se genera desde los recibos en `docs/v2/07-uat/` y vive en `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (regenerable, máquina-verificable, SHA-stamped).
**Referencia normativa:** `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`.

---

## Contrato normativo por UAT

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
| UAT-RP-019 | Gradle real (RP-5, opt-in) | WURp019GradleRealUatTest (Gradle 8.14.5 real + fixture real; opt-in UAT_RP_019_RUN=1) | happy: pipelinek exit 0, JAR escrito. failure: pipelinek exit !=0 |
| UAT-RP-020 | Maven real (RP-5, opt-in) | WURp020MavenRealUatTest (Maven 3.9.9 + fixture real; opt-in UAT_RP_020_RUN=1) | happy: pipelinek exit 0; failure: pipelinek exit !=0 |
| UAT-RP-021 | Node real (RP-5, opt-in) | WURp021NodeRealUatTest (Node 25.9.0 + fixture real; opt-in UAT_RP_021_RUN=1) | happy: dist/output.txt >0, marker presente. failure: pipelinek exit !=0 |
| UAT-RP-022 | Release reproducibilidad (RP-5) | Doble build `:pipeline-application:distZip` (incremental + `--rerun-tasks`) produce ZIPs idénticos (sha256, bytes, fecha reproducible); instalación limpia + `pipelinek version` + `pipelinek doctor` exit 0; success path + failure path de un script mínimo | ZIPs byte-idénticos (sha256 + bytes), 9 events success, exit 1 en failure path |
| UAT-RP-023 | Supply chain (RP-5) | SBOM CycloneDX + secret-scan gitleaks + SAST detekt + Dependabot operativo | cada componente verde en CI, allowlist `.gitleaks.toml`, baseline de deuda congelada |
| UAT-RP-024 | Dogfooding (RP-5) | pipelinek ejecuta `.pipeline.kts` real sobre 2 repos ajenos; replay memoized mismo --db/--control-root exit 0 | 2 repos dogfood con artefactos producidos, sandbox escape rechazado |
| UAT-RP-025 | SDKMAN_READY (opt) | canal SDKMAN instalable + verificable | sólo exigible si se declara SDKMAN_READY |
| UAT-RP-026 | Remote profile (RP-8) | lease/fencing, reconnect/ACK, replay/cancel y partición de red | sólo exigible para el perfil REMOTE |
| UAT-RP-027 | Jenkins adapter (RP-9) | eventos live de stages, reinicio dashboard, mismos outcomes y autorización | sólo exigible para el adaptador Jenkins |

---

## Criterio de obligatoriedad

001–024 son candidatas obligatorias para el perfil local cuando su capacidad forme parte del contrato anunciado; 025 exige la declaración SDKMAN_READY; 026 exige REMOTE_READY; 027 exige JENKINS_READY. Toda exclusión de una candidata local exige un recorte explícito del perfil aprobado y trazado, NUNCA marcar NO_APLICA tras un fallo sin decisión normativa. Para cada fila actualizar un receipt por SHA con PASS/FAIL/BLOCKED/NOT_RUN, entorno, argv, XML, huella y enlace al test; **no editar esta tabla para simular ejecución**.

---

## Estado mutable: dónde encontrarlo

El estado actual (COVERED / PARTIAL / FAIL_PROVEN / NOT_RUN / REFERENCED) por UAT **no vive en este archivo**. Se genera desde `docs/v2/07-uat/` mediante:

```bash
python3 scripts/gen-current-uat-status.py
```

y se materializa en `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (con SHA-stamp de la corrida y HEAD de Git).

---

## Cambios respecto a la versión anterior (PRDY-003)

- Eliminada la sección "Estado por UAT a HEAD 87d7f2ef" (estado mutable).
- Eliminados los "Update 2026-09-XX" cronológicos al pie (estado mutable).
- La columna "Evidencia requerida para PASS" reemplaza los recibos concretos (los recibos concretos viven en `docs/v2/07-uat/`).
- Las notas de "obligatoriedad" y "fallos conocidos" siguen aquí porque son **normativas** (reglas, no estado).
- Contrato preservado byte-a-byte en la tabla Gate/Escenario/Evidencia (verificado por inspección contra `git show HEAD~1:docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` líneas 29–57).
