# PRD — Pipeline Kotlin V2

## 1. Resumen

Pipeline Kotlin V2 es un motor CI/CD Kotlin-first que conserva la experiencia mental de Jenkins Pipeline y sustituye sus acoplamientos internos por un runtime distribuido, durable, event-sourced y graph-native.

## 2. Usuarios

### Developer de aplicación
Quiere escribir pipelines familiares, tipados, con autocompletado y errores tempranos.

### Platform engineer
Quiere gobernar workers, credentials, policies, plugins, caches y templates sin acoplar el motor a un único proveedor.

### Jenkins administrator
Quiere mantener Jenkins como UI/orquestador, reduciendo carga del controller y sin depender de Groovy CPS para nuevos pipelines.

### Security/Supply-chain engineer
Quiere trazabilidad de artefactos, SBOM, firmas, provenance y quién/qué produjo cada resultado.

### Operador
Quiere entender por qué un run está bloqueado, qué worker lo ejecuta, qué pasó durante recovery y qué eventos causaron un fallo.

## 3. Jobs to be done

1. “Quiero migrar un Jenkinsfile común a Kotlin sin reaprender CI/CD.”
2. “Quiero que el controller no ejecute el código pesado del pipeline.”
3. “Quiero que perder un Pod no destruya el estado lógico del run.”
4. “Quiero añadir un Step/plugin con firma familiar y contrato tipado.”
5. “Quiero usar Jenkins Credentials, Kubernetes/CSI, Vault u OIDC detrás de una misma abstracción.”
6. “Quiero saber de qué commit procede un artifact desplegado.”
7. “Quiero comparar un run con una variante sin tocar el original.”
8. “Quiero probar RC/EAP de Kotlin sin poner producción en riesgo.”

## 4. Alcance V2.0

### Incluido
- `.pipeline.kts` mediante Kotlin Scripting Host encapsulado.
- Kotlin 2.4.10 como baseline inicial certificada.
- DSL declarative familiar + `script {}` para Kotlin dinámico durable.
- Steps `echo`, `sh`, `error`, `sleep`, `retry`, `timeout`, `checkout/git`, `withCredentials`, `junit`, `archiveArtifacts`.
- Runtime local/worker con event journal y recovery.
- Protocol Protobuf con ACK/replay, leases, fencing, heartbeat.
- Worker provider local + Kubernetes.
- Jenkins Workflow adapter.
- Credentials Jenkins + Kubernetes/workload identity; Vault como siguiente adapter prioritario.
- Execution/Provenance graph projections básicas.
- Plugin SDK y packaging reproducible.
- Observabilidad OpenTelemetry básica.

### Fuera de V2.0
- Compatibilidad binaria con plugins Jenkins.
- Emulación transparente de cualquier `StepContext` Jenkins.
- Reimplementación de CPS.
- SaaS multi-tenant completo.
- Scheduler ML/AI.
- Graph DB obligatoria en hot path.
- Plugin marketplace público.

## 5. Requisitos funcionales

### FR-DSL
- FR-DSL-001: `pipeline`, `agent`, `environment`, `stages`, `stage`, `steps`, `post`.
- FR-DSL-002: `script {}` permite Kotlin normal y durable Steps.
- FR-DSL-003: nombres/firma de Steps pueden mapear a Jenkins Familiarity metadata.
- FR-DSL-004: diagnostics incluyen fichero/línea/columna y Step/plugin responsable.
- FR-DSL-005: el classpath del script se construye explícitamente desde API + plugins lockeados.

### FR-RUNTIME
- FR-RUN-001: el worker compila y ejecuta `.pipeline.kts`.
- FR-RUN-002: resultados de operaciones durables pueden ser reusados tras restart.
- FR-RUN-003: side effects declaran policy de replay.
- FR-RUN-004: `parallel`, `retry`, `timeout`, cancelación y loss de worker son estados explícitos.

### FR-WORKER
- FR-WRK-001: workers anuncian capabilities tipadas.
- FR-WRK-002: workers usan lease + fencing token.
- FR-WRK-003: worker journal conserva eventos no ACK.
- FR-WRK-004: Pod efímero puede reemplazarse sin perder el run lógico.

### FR-JENKINS
- FR-JEN-001: nuevo tipo “Kotlin Pipeline” y “Kotlin Pipeline from SCM”.
- FR-JEN-002: `FlowExecution` controller-side es una proyección ligera.
- FR-JEN-003: Stage/Step/fallo se muestran como FlowNodes/actions.
- FR-JEN-004: controller restart rehidrata proyección y reanuda sesión/event replay.

### FR-PLUGIN
- FR-PLG-001: descriptor tipado y versionado.
- FR-PLG-002: Step API no depende de Jenkins.
- FR-PLG-003: codegen genera DSL façade, schema, docs y LSP metadata.
- FR-PLG-004: plugin declara effects, capabilities y replay policy.

### FR-GRAPH
- FR-GRA-001: Event Log es la fuente de verdad.
- FR-GRA-002: Execution y Provenance Graph son reconstruibles.
- FR-GRA-003: causalidad `causationId` y `correlationId` es consultable.
- FR-GRA-004: fork/diff se implementará primero para escenarios sin external mutation no reversible.

## 6. Requisitos no funcionales

- NFR-001: controller no debe compilar/evaluar user pipeline.
- NFR-002: no Java serialization en el worker protocol.
- NFR-003: ningún secreto en EventEnvelope/log/projection.
- NFR-004: schemas backward-compatible dentro de una major.
- NFR-005: replay de 100k eventos debe poder reconstruirse de forma incremental y medible.
- NFR-006: cold-start Kubernetes medido desde Pod Requested hasta Worker Ready.
- NFR-007: worker/gateway reconnect soporta duplicados sin duplicar state transitions.
- NFR-008: domain/application sin dependencias a Jenkins/Kubernetes/Koin.
- NFR-009: cada release publica compatibility matrix Kotlin/JDK/Jenkins/protocol/plugin API.

## 7. Métricas de producto

- % Jenkinsfiles de corpus migrables automáticamente.
- tiempo de feedback de compile error.
- controller CPU/memory por run V2 vs Pipeline Groovy equivalente.
- worker cold-start p50/p95.
- recovery success rate tras Pod kill.
- event lag gateway/controller.
- porcentaje de Steps con descriptor/effects/replay definidos.
- provenance completeness.
- compatibility corpus pass rate en Kotlin stable/RC/EAP.

## 8. No-go conditions

No promover a V2.0 si:
- recovery repite un `EXTERNAL_MUTATION` confirmado;
- fencing permite aceptar eventos del worker antiguo;
- controller ejecuta user script como camino normal;
- secrets aparecen en journal/event payload;
- UAT crítica tiene fallos abiertos;
- el DSL corpus no compila de forma reproducible con la versión certificada.
