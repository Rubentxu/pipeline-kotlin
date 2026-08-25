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
