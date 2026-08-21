# Module Boundaries

## Core puro

### `pipeline-domain`
No framework dependencies. Idealmente sólo stdlib + serialization annotations si se decide aceptarlas como boundary-safe.

### `pipeline-application`
Use cases y interfaces. Puede depender de coroutines como abstraction de concurrencia interna, nunca como estado persistido.

## DSL/Compiler

### `pipeline-dsl-api`
Builders, annotations y façade pública. Debe cambiar lentamente.

### `pipeline-scripting-api`
Ports propios: `PipelineScriptEngine`, `PipelineCompiler`, diagnostics, source/artifact abstractions.

### `pipeline-scripting-kotlin24`
Único lugar autorizado inicialmente para `kotlin.script.experimental.*`. Fija compiler/scripting version.

### `pipeline-step-codegen`
KSP genera descriptors, typed façades, serializers/schema metadata, docs/LSP indexes. FIR/IR queda opcional.

## Runtime

### `pipeline-runtime`
Durable operation dispatcher, replay cursor, frames, parallel/retry/timeout, effect policy.

### `pipeline-worker-runtime`
Host del runtime, filesystem/process/container adapters, local journal, plugin loading, protocol session.

## Distribution

### `pipeline-protocol`
`.proto`, compatibility tests y generated models. No domain objects arbitrarios cruzan frontera sin wire mapping.

### `pipeline-worker-gateway`
Sessions, heartbeats, ACK/replay, lease ownership, admission, protocol negotiation.

### `pipeline-worker-kubernetes`
Provisioning y lifecycle de Pods. No lógica de DSL.

## Integrations

### `pipeline-jenkins-plugin`
Workflow `FlowDefinition/FlowExecution`, UI/config, FlowNode projector, Jenkins credential adapter.

### `pipeline-jenkins-kubernetes-bridge`
Traduce configuración/PodTemplate existente a `WorkerTemplate`; opcional.

## Regla anti-cycle

El build debe fallar si aparecen ciclos de módulos. `pipeline-testkit` puede depender de APIs públicas, nunca convertirse en dependencia de producción.
