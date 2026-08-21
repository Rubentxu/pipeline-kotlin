# Principios

1. **Familiarity first, compatibility explicit.** La superficie se parece a Jenkins; la compatibilidad se declara.
2. **Core independiente de Jenkins.** Domain/application no importan Jenkins, Kubernetes, Koin, Docker ni storage concreto.
3. **Worker owns execution.** Compilación, Kotlin, workspace, procesos y build steps viven en worker salvo `CONTROLLER_CONTROL`.
4. **Event log is truth.** Estado reconstruible desde eventos append-only.
5. **Graphs are projections.** Un graph store puede borrarse y regenerarse.
6. **Durable operations, not serialized continuations.** No persistir stacks/coroutines del usuario.
7. **At-least-once + idempotency.** Sequence, ACK, deduplicación, leases y fencing.
8. **Capabilities, not hidden services.** Cada Step pide sólo lo que necesita.
9. **Version every boundary.** Protocol, event schema, manifests, plugin API, DSL API y artifact format.
10. **Secure by construction.** Sin secretos en eventos; workload identity preferida; sandbox OS/container.
11. **Determinism is a feature.** Replay, cache, fork, UAT y debugging dependen de inputs/effects explícitos.
12. **Make illegal states difficult.** IDs tipados, sealed hierarchies, state machines y schemas.
13. **Vertical evolution.** Todo milestone termina en demo integrada + UAT.
14. **Architecture as executable policy.** Fitness functions protegen las fronteras.
15. **Observability is protocol-level.** IDs y causalidad nacen en dominio.
16. **Supply chain is domain.** Artifact, digest, SBOM, provenance y deployment son entidades.
17. **No vendor lock-in at ports.** Providers/adapters para infra.
18. **Documentation participates in CI.** Ejemplos DSL/manifests/protobuf/UAT se validan.
