# Glosario

| Término | Definición |
|---|---|
| Pipeline Definition | Código `.pipeline.kts` o definición compilada. |
| Pipeline Run | Instancia concreta de una definición. |
| Stage | Agrupación observable de trabajo. |
| Step | Operación invocable desde DSL con contrato, effects, inputs y outputs. |
| Attempt | Intento concreto de ejecutar un Step. |
| Durable Operation | Operación cuyo resultado puede reusarse durante recovery. |
| Replay | Reejecución usando resultados durables previos para no repetir effects confirmados. |
| Frame | Contexto run-local para ramas paralelas que convergen. |
| Fork | Nuevo run que comparte historia hasta un evento y diverge después. |
| Event Journal | Registro append-only de un run. |
| Projection | Estado derivado reconstruible desde eventos. |
| Execution Graph | Run, StageRun, StepRun, Attempt, WorkerLease y causalidad. |
| Provenance Graph | Commit, inputs, builds, artifacts, SBOM, images y deployments. |
| Worker | Runtime que compila/evalúa el pipeline y ejecuta workload. |
| Worker Pool | Fuente lógica de workers con provisioning/capabilities/policies. |
| Worker Lease | Derecho temporal exclusivo a ejecutar una unidad. |
| Fencing Token | Número monotónico que invalida eventos de owners antiguos. |
| Gateway | Gestiona sesiones worker, ACK/replay y transport. |
| Control Plane | Scheduling, coordinación, policies, projections y adapters. |
| Capability | Requisito/servicio tipado de Step o Worker. |
| CredentialRef | Referencia lógica a credencial. |
| CredentialLease | Autorización temporal/scoped. |
| CredentialProjection | Env/file/volume/OIDC/SSH-agent/etc. |
| Plugin Surface | API Kotlin visible al developer. |
| Plugin Descriptor | Metadata de Steps, schemas, effects y versions. |
| Jenkins Familiarity | Similaridad intencional sin compatibilidad binaria. |
| Effect | Side effect declarado de un Step. |
| Replay Policy | Regla para reusar/repetir/simular/aprobar/prohibir un efecto. |
