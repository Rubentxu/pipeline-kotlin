# Matriz de trazabilidad

| Goal | Especificación | ADR | Milestone | UAT |
|---|---|---|---|---|
| DSL familiar Jenkins | `DSL_SPEC` | ADR-0005 | M2 | UAT-DSL-* |
| Kotlin Scripting | `SCRIPTING_COMPILER_SPEC` | ADR-0002/0019 | M1/M2 | UAT-COMP-* |
| Context parameters | `STEP_PLUGIN_SDK` | ADR-0003 | M2 | UAT-STEP-* |
| Reducir FIR/IR | `STEP_PLUGIN_SDK` | ADR-0004 | M2 | UAT-STEP-004 |
| Runtime fuera controller | `RUNTIME_MODEL` | ADR-0001/0012 | M3/M6 | UAT-JENKINS-003 |
| Durable replay | `RECOVERY_DURABILITY` | ADR-0006 | M3 | UAT-REC-* |
| Durable sh (durable-task pattern) | `RECOVERY_DURABILITY` | ADR-0046 | ML | UAT-LOCAL-001, UAT-REC-002 |
| Event source of truth | `EVENT_MODEL` | ADR-0007 | M1/M3 | UAT-EVT-* |
| Graph-native | `GRAPH_MODEL` | ADR-0008 | M3/M8 | UAT-GRAPH-* |
| Protobuf protocol | `WORKER_PROTOCOL` | ADR-0009/0043/0044 | M4 | UAT-PROT-* |
| WS MVP / gRPC gateway | `WORKER_PROTOCOL` | ADR-0010 | M4/M8 | UAT-PROT-005 |
| Leases/fencing | `WORKER_PROTOCOL` | ADR-0011 | M4 | UAT-REC-005 |
| Workers Kubernetes | `KUBERNETES_WORKERS` | ADR-0013 | M5 | UAT-K8S-* |
| Credentials providers | `CREDENTIALS_PROVIDERS` | ADR-0014 | M5 | UAT-CRED-* |
| Plugins propios | `STEP_PLUGIN_SDK` | ADR-0015 | M7 | UAT-PLUGIN-* |
| Sandbox moderna | `SECURITY` | ADR-0016 | M5/M9 | UAT-SEC-* |
| Supply chain | `ARTIFACTS_SUPPLY_CHAIN` | ADR-0017 | M8 | UAT-SC-* |
| Config manifests | `CONFIG_MANIFESTS` | ADR-0018 | M5 | UAT-CONFIG-* |
| Jenkins Workflow | `JENKINS_PLUGIN` | ADR-0001/0012 | M6 | UAT-JENKINS-* |
| Compat Kotlin | `SCRIPTING_COMPILER_SPEC` | ADR-0019 | M0+ | UAT-COMP-006 |
| BTA futuro | `SCRIPTING_COMPILER_SPEC` | ADR-0020 | spike | SPIKE-007 |
| Sandbox local | `EXECUTION_SANDBOX` | [[ADR-0048-sandbox-profile-local]] | ML | UAT-LOCAL-003 + UAT-LOCAL-007 |
| Credentials local + secret redaction | `CREDENTIALS_PROVIDERS` | [[ADR-0049-credentials-local]] | ML-R4 | UAT-LOCAL-008 |
| Checkout / git step (L5) | `SCM_CHECKOUT` | [[ADR-0050-checkout-git-step]] | ML-R5 | UAT-LOCAL-005 |
| Typed durable terminal result | `JENKINS_SH_CONTRACT` | ADR-0065 | EM-1 | UAT-JEP-008..010 |
| Central step lifecycle | `FAILURE_INTERRUPTION_MODEL` | ADR-0065 | EM-2 | UAT-JEP-029..030 |
| Jenkins sh parity | `JENKINS_SH_CONTRACT` | ADR-0065 | EM-3 | UAT-JEP-001..010 |
| First-class body steps | `BLOCK_STEP_EXECUTION` | ADR-0065 | EM-4 | UAT-JEP-014..023 |
| Durable timeout | `BLOCK_STEP_EXECUTION` | ADR-0065 | EM-5 | UAT-JEP-011..013 |
| Scripted replay | `DURABLE_KOTLIN_EXECUTION` | ADR-0065 | EM-8 | UAT-JEP-024..028 + SPIKE-016 N1..N6 |
| `schemaVersion` persisted records | `DURABLE_KOTLIN_EXECUTION` | ADR-0067 | EM-1/EM-8 | SPIKE-016-N3 |
| `OperationStatus.INTERRUPTED` canonical terminal | `FAILURE_INTERRUPTION_MODEL` | ADR-0068 | EM-5 | SPIKE-016-N4/N5 |
| `staticCallSiteId` compiler-derived identity | `DURABLE_KOTLIN_EXECUTION` | ADR-0066 | EM-8 | SPIKE-016-N2 + UAT-JEP-024..028 |
| try/catch retry `attemptId` | `DURABLE_KOTLIN_EXECUTION` | ADR-0066 | EM-6 | SPIKE-016-E5c |
