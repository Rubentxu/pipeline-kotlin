# UAT Scenarios

## UAT-M0-001 — Baseline reproducible
**Actor:** Developer  
**Given:** checkout limpio y JDK soportado  
**When:** ejecuta build/validate V2  
**Then:** todos los módulos V2 compilan sin source excludes y sample hello valida.  
**Critical:** sí

## UAT-COMP-001 — Pipeline válido
Compilar `.pipeline.kts` con `pipeline/stage/sh`; diagnostics vacíos; artifact/cache identity registrada.

## UAT-COMP-002 — Error source-mapped
Introducir parámetro/type incorrecto; mostrar fichero/línea/columna y símbolo útil.

## UAT-COMP-006 — Upgrade Kotlin compatibility
Ejecutar corpus contra versión candidata y generar semantic/diagnostic diff antes de promoverla.

## UAT-DSL-001 — Jenkins familiarity
Developer Jenkins sin documentación extensa reconoce estructura y puede modificar command/stage/agent.

## UAT-DSL-002 — Dynamic scripted logic
`sh(returnStdout=true)` decide un `if` dentro de `script {}` y produce branch correcto.

## UAT-DSL-003 — Parallel
Dos ramas corren independientemente, UI/event graph conserva nombres y join.

## UAT-DSL-004 — Retry
Failure infrastructure crea Attempt nuevo; build failure no retry si policy lo excluye.

## UAT-DSL-005 — Timeout durable
Timeout sigue siendo efectivo tras worker reconnect/recovery.

## UAT-STEP-001 — Capability injection
`sh` recibe ProcessExecutor mediante context capability; test no usa Service Locator.

## UAT-STEP-004 — Sin FIR/IR
Pipeline completo compila/ejecuta con compiler plugin custom deshabilitado.

## UAT-EVT-001 — Rebuild
Eliminar projection y reconstruir desde journal obteniendo mismo run state.

## UAT-EVT-002 — Causal chain
Desde RetryScheduled se puede navegar a AttemptLost y WorkerLost causante.

## UAT-REC-001 — Worker crash después de Step
Step `sh` produce marker externo controlado, worker muere después del completion event; recovery no vuelve a ejecutar el Step.

## UAT-REC-002 — Crash antes de confirmation
Worker muere durante durable process; reconciliation determina RUNNING/LOST y policy produce resultado consistente, no asume éxito.

## UAT-REC-003 — Replay divergence
Cambiar source digest/control flow y forzar replay; runtime falla closed y propone fork/migration.

## UAT-REC-005 — Fencing split brain
Worker A token 42 pierde lease, B obtiene 43; eventos tardíos A son rechazados y auditados.

## UAT-PROT-001 — Reconnect/replay
Cortar conexión, producir eventos, reconectar; tail llega exactamente como state transitions aunque el delivery sea at-least-once.

## UAT-PROT-002 — Duplicate events
Reenviar EventEnvelope; reducer no duplica Stage/Step state.

## UAT-PROT-003 — Protocol mismatch
Worker sin versión compatible es rechazado antes de recibir workload.

## UAT-PROT-005 — Transport parity
Mismo conformance suite sobre WebSocket y gRPC gateway cuando esté disponible.

## UAT-K8S-001 — Ephemeral Pod
Run solicita Pod, worker conecta, ejecuta y Pod termina según lifecycle.

## UAT-K8S-002 — Pod eviction
Eliminar Pod durante Step retryable; run clasifica InfrastructureFailure y continúa en nuevo worker según policy.

## UAT-K8S-003 — Familiar Pod template
`inheritFrom`, `yamlFile` y `defaultContainer` producen effective WorkerTemplate esperado.

## UAT-CRED-001 — Jenkins credentials
`credentialsId` resuelve y proyecta credencial sin aparecer en event/journal/log.

## UAT-CRED-002 — Workload identity
Worker accede a recurso mediante identity/CSI sin secret bytes atravesando controller.

## UAT-SEC-001 — Secret redaction
Imprimir accidentalmente token conocido; logs almacenados/transmitidos quedan redacted según contrato.

## UAT-SEC-002 — Sandbox profile
Pipeline no puede elevar privilegios ni escribir fuera de mounts permitidos en security profile baseline.

## UAT-JENKINS-001 — New Pipeline type
Crear “Kotlin Pipeline from SCM” y ejecutar desde Jenkins.

## UAT-JENKINS-002 — FlowGraph projection
Stages/Steps/failure aparecen en UI/history con nombres esperados.

## UAT-JENKINS-003 — Lightweight controller
Durante compile/build, evidencia confirma que Kotlin compile y shell workload ocurren en worker, no controller.

## UAT-JENKINS-004 — Controller restart
Reiniciar Jenkins durante run; FlowExecution rehidrata y continúa recibiendo/reproyectando eventos.

## UAT-PLUGIN-001 — Install locked plugin
Resolver plugin por version+digest, verificar descriptor y compilar DSL façade.

## UAT-PLUGIN-002 — Incompatible plugin
Plugin API/runtime range incompatible falla antes de workload.

## UAT-E2E-001 — Reference Java pipeline
checkout → Gradle build → junit → artifact → provenance con worker K8s y vista Jenkins.

## UAT-GRAPH-001 — Execution query
Desde failed Step obtener Attempts, Workers y causal chain.

## UAT-GRAPH-002 — Fork/diff
Fork seguro desde evento, cambiar worker/runtime config, ejecutar y comparar timings/results sin modificar parent.

## UAT-SC-001 — Artifact provenance
Dado artifact digest, navegar hasta commit, run, Step, worker image, plugin lock y SBOM.

## UAT-SC-002 — Deployment policy
Bloquear deployment a production si artifact carece de required SBOM/signature relation.

## UAT-CHAOS-001 — Gateway restart
Reiniciar gateway con runs activos; workers reconectan y replay sin corrupción.

## UAT-CHAOS-002 — Burst duplicates/delay
Introducir duplicates y reordering permitido; estado final consistente.

## UAT-PERF-001 — Controller comparison
Ejecutar carga equivalente Groovy/V2 y registrar CPU/heap/controller throughput.

## UAT-LOCAL-001 — Kill durante sh (at-most-once) — ML/L1
Matar el runner mientras `sh` está EN ejecución (subproceso vivo); al hacer `--resume`, el step NO se re-ejecuta: exit code y log se recuperan de `result.txt`/log de disco; sin result ni heartbeat → LOST declarado, nunca éxito asumido. **Cierra UAT-REC-002.**

## UAT-LOCAL-002 — Workspace/env/returnStdout — ML/L2
`sh(returnStdout=true)` devuelve stdout exacto vía output file (secreto en env nunca aparece en argv); cada stage tiene workspace propio; JAVA_HOME/M2_HOME propagan a PATH; timeout mata el subproceso de forma duradera.

## UAT-LOCAL-003 — Sandbox local — ML/L3
Pipeline con `sh` malicioso (escribir fuera del workspace, leer `$HOME/.ssh`) es bloqueado/reporteado por el perfil local best-effort. Perfil completo OS/container: M5/M9 (ADR-0016).

## UAT-LOCAL-004 — Credenciales locales + redacción — ML/L4
`credentialsId` resuelve desde store local; el secreto queda enmascarado en log/events/journal incluso imprimiéndolo. (Partial UAT-SEC-001.)

## UAT-LOCAL-005 — Checkout repo real — ML/L5
`checkout` clona un repositorio público real dentro del workspace aislado y expone commit/branch al resto del pipeline.

## UAT-LOCAL-006 — Smoke build real — ML/L7
Pipeline end-to-end sobre un proyecto open-source famoso (pequeño, con Gradle/Maven wrapper): checkout → build → test-report → artifact, ejecutado por steps V2 con runner local durable. Clases: `UatLocal010SmokeE2ESandboxTest` (online) + `UatLocal010SmokeE2ESandboxOfflineTest` (offline, no network).

## UAT-LOCAL-011 — Workflow-control steps — ML/L9
12 escenarios SC-011-01..12 cubriendo los 16 nuevos step kinds de workflow-control y utilidad: `dir`, `deleteDir`, `cleanWs`, `timeout`-block, `retry`-block, `pwd`, `isUnix`, `load`, `waitUntil`, `timestamps`, `ansiColor`, `node` no-op. Incluye replay de bloques anidados (SC-011-07/08) y canary round-gate `__ml_r9_canary__` cero-ocurrencia en todos los canales de salida. Clase: `UatLocal011WorkflowControlTest`.

## UAT-LOCAL-012 — Error-handling steps — ML/L9
8 escenarios SC-012-01..08 cubriendo error-handling con 3-state outcome: `catchError` (default UNSTABLE, buildResult=FAILURE re-throws, buildResult=SUCCESS downgrades), `warnError`, `unstable`. Verifica que el outcome se propagó correctamente a `RunFinished.outcome`. Clase: `UatLocal012ErrorHandlingTest`.

## UAT-LOCAL-013 — Milestone timing — ML/L9
4 escenarios SC-013-01..04 cubriendo `milestone(ordinal, label?)`: orden ordinal estrictamente creciente dentro de un run, labels opcionales, y coordinación cross-build ( MilestoneReached / MilestoneAborted events). Clase: `UatLocal013MilestoneTimingTest`.
