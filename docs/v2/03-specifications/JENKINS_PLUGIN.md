# Jenkins Workflow Plugin Specification

## 1. Objetivo

Registrar una nueva forma de ejecutar Pipeline que conviva con Groovy CPS pero delegue runtime/compilación a Pipeline Kotlin workers.

## 2. Definition types

- `KotlinPipelineDefinition` — inline/source definition.
- `KotlinScmPipelineDefinition` — source desde SCM.

Ambas implementan/usan el extension point Workflow `FlowDefinition`.

## 3. FlowExecution

`KotlinPipelineExecution` representa la proyección controller-side, no el motor de ejecución.

Persistencia mínima:
- runId;
- definition/source ref;
- current Flow heads;
- lastAppliedEventSequence;
- status;
- gateway/session logical ref;
- cancellation state.

No persistir:
- Kotlin coroutine;
- script instance runtime;
- worker process state;
- secret material.

## 4. Event → FlowGraph mapping

| Event | Jenkins projection |
|---|---|
| RunStarted | FlowStartNode |
| StageStarted | BlockStartNode/custom node + LabelAction |
| StepStarted | Atom/step node + metadata |
| StepFailed | ErrorAction / status |
| Warning | WarningAction |
| StageCompleted | BlockEndNode |
| RunCompleted/Failed | FlowEndNode |
| LogChunk | Pipeline log storage/proxy ref |

## 5. Controller restart

`onLoad` restaura la proyección pequeña, consulta last applied sequence y reanuda/replay events desde event store/gateway.

## 6. Queue/scheduling

Jenkins puede seguir gestionando Job/Queue/RBAC/SCM indexing. El worker scheduler V2 decide el Worker real. No se modela cada worker V2 como `Node/Computer` salvo bridge explícito para casos específicos.

## 7. Jenkins credentials

`JenkinsCredentialProviderAdapter` traduce `CredentialRef(credentialsId)` a una lease/projection autorizada. El provider no contamina domain.

## 8. Kubernetes bridge

Opcionalmente lee `KubernetesCloud/PodTemplate` y lo traduce a `WorkerTemplate`; no usa su inbound agent launcher.

## 9. UI incremental

V2.0 reutiliza UI/FlowGraph/logs Jenkins cuanto sea posible. UI graph-native propia es fase posterior; no bloquear el motor esperando una UI nueva.

## 10. Compatibility boundary

Plugins Jenkins arbitrarios no son soportados. Se construye ecosistema propio con familiar surfaces. Control steps específicos pueden tener adapters seleccionados.
