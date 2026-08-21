# Implementation Backlog

El backlog está ordenado por dependencia y riesgo. Los IDs pueden convertirse directamente en issues.

## Epic E0 — Baseline
- **E0-01** Crear source sets/módulos V2 sin `compileKotlin.exclude`.
- **E0-02** Upgrade V2 toolchain a Kotlin 2.4.10.
- **E0-03** Crear CI build/test/document examples.
- **E0-04** Añadir dependency fitness rules.
- **E0-05** Clasificar V1 KEEP/ADAPT/REWRITE/RETIRE.
- **E0-06** Eliminar/renombrar snapshots Repomix obsoletos o regenerarlos desde source actual.
- **E0-07** Alinear README con capabilities verificadas.

## Epic E1 — Domain/Event spine
- **E1-01** Typed IDs Run/Stage/Step/Attempt/Worker/Lease/Event.
- **E1-02** Failure taxonomy.
- **E1-03** Run/Attempt state machines.
- **E1-04** EventEnvelope domain model.
- **E1-05** EventStore port.
- **E1-06** InMemory store.
- **E1-07** SQLite reference store.
- **E1-08** deterministic Clock/IdGenerator ports.
- **E1-09** projector/reducer contract.

## Epic E2 — Kotlin scripting
- **E2-01** `PipelineScriptEngine` SPI.
- **E2-02** Kotlin24 ScriptDefinition.
- **E2-03** explicit classpath builder.
- **E2-04** source diagnostics mapping.
- **E2-05** compilation cache key/storage.
- **E2-06** compiler compatibility corpus harness.
- **E2-07** kotlinc fallback harness.
- **E2-08** stable/RC/EAP matrix workflow.

## Epic E3 — DSL/Step SDK
- **E3-01** pipeline/stages/stage/steps builders.
- **E3-02** environment/post/options.
- **E3-03** agent abstraction.
- **E3-04** context capability API.
- **E3-05** `@Step` v2 annotation.
- **E3-06** StepDescriptor schema.
- **E3-07** KSP descriptor generator.
- **E3-08** generated LSP/docs metadata.
- **E3-09** `echo`, `sh`, `error`, `sleep`.
- **E3-10** JenkinsSurface metadata/compat levels.

## Epic E4 — Durable runtime
- **E4-01** DurableOperation abstraction.
- **E4-02** operation fingerprint.
- **E4-03** result journal.
- **E4-04** replay cursor.
- **E4-05** divergence detection.
- **E4-06** effect/replay policy.
- **E4-07** `script {}` bridge.
- **E4-08** retry Attempts.
- **E4-09** durable timeout.
- **E4-10** parallel Frames/join.
- **E4-11** durable process task/reattach model.

## Epic E5 — Protocol/Gateway
- **E5-01** `.proto` v1 repo layout/governance.
- **E5-02** hello/negotiation.
- **E5-03** commands/events mappings.
- **E5-04** local outbox/ACK.
- **E5-05** reconnect/replay.
- **E5-06** heartbeat/liveness.
- **E5-07** WorkerLease/fencing.
- **E5-08** WebSocket transport.
- **E5-09** Gateway service.
- **E5-10** protocol conformance suite.

## Epic E6 — Kubernetes/Credentials
- **E6-01** WorkerProvisioner port.
- **E6-02** WorkerTemplate/WorkerPool manifests.
- **E6-03** K8s Pod provisioner.
- **E6-04** capabilities discovery.
- **E6-05** scheduler hard/soft matching.
- **E6-06** Pod lifecycle/reconciliation.
- **E6-07** security context baseline.
- **E6-08** CredentialProvider port.
- **E6-09** Jenkins credentials adapter.
- **E6-10** K8s/CSI/OIDC projection.
- **E6-11** redaction pipeline.

## Epic E7 — Jenkins adapter
- **E7-01** plugin skeleton modern Jenkins baseline.
- **E7-02** KotlinPipelineDefinition.
- **E7-03** SCM definition.
- **E7-04** KotlinPipelineExecution persistence model.
- **E7-05** FlowStart/End projection.
- **E7-06** Stage/Step FlowNodes/actions.
- **E7-07** log projection/storage.
- **E7-08** cancel/interrupt.
- **E7-09** `onLoad`/restart recovery.
- **E7-10** PodTemplate bridge spike.

## Epic E8 — Plugins
- **E8-01** plugin manifest/API compatibility resolver.
- **E8-02** lockfile.
- **E8-03** git/checkout.
- **E8-04** credentials-binding façades.
- **E8-05** junit parser/result model.
- **E8-06** archiveArtifacts direct upload.
- **E8-07** stash/unstash.
- **E8-08** container/docker.
- **E8-09** Kubernetes helper DSL.
- **E8-10** httpRequest.
- **E8-11** input/approval.
- **E8-12** OCI packaging/signature hook.

## Epic E9 — Graph/Supply-chain
- **E9-01** ExecutionGraph projector.
- **E9-02** ProvenanceGraph projector.
- **E9-03** query API.
- **E9-04** ArtifactStore port/direct upload.
- **E9-05** digest verification.
- **E9-06** SBOM entity/relations.
- **E9-07** provenance generation.
- **E9-08** signature/attestation model.
- **E9-09** deployment relation/policy.
- **E9-10** fork/diff MVP.

## Epic E10 — Production hardening
- **E10-01** OpenTelemetry traces/metrics.
- **E10-02** snapshots/compaction.
- **E10-03** gRPC/mTLS gateway.
- **E10-04** warm pool.
- **E10-05** data-locality scoring.
- **E10-06** chaos suite.
- **E10-07** security assessment.
- **E10-08** performance budgets/SLO.
- **E10-09** upgrade/rollback tests.
- **E10-10** release provenance/SBOM.

## Dependency rule

No empezar E8 por amplitud funcional antes de haber demostrado E4/E5/E6/E7 con el walking skeleton. Hacerlo produciría plugins sobre un runtime aún no validado.
