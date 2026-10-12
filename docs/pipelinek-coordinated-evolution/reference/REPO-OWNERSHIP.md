# Ownership por repositorio, permisos de modificación y paquetes recomendados

| Capacidad | `pipeline-kotlin` | `pipelinek-fabric` |
|---|---|---|
| Execution semantics, journal, recovery | Owner | Cliente autorizado de inspección |
| Capture, redactor, stdout/stderr, frame index | Owner | Réplica y lectura, no nueva captura canónica |
| EventStore/cursor | Owner | Ingest + `ProjectionFact`, no segundo event source |
| Reader/wakeup/typed values | Owner | Consume ports, jamás reescribe typed values como logs |
| Runtime capabilities/version manifest | Publica | Negotiates/inspects |
| Desired state y worker leases | No | Owner |
| Reconciler y runtime orchestration | No | Owner |
| Stream transport, outbox y ACK | No | Owner |
| Local storage retention | Owner de segmento | Pin solicitud, ACK y elegibilidad de GC |
| Archive local/S3, metadata, manifest | No | Owner |
| Elastic/Loki search adapters | No | Opt-in |
| Query multi-run, authn/authz | No | Owner |
| Jenkins UI/API | No | Owner |
| Gobernador red, consultas y viewer | Telemetría de presión local | Owner |
| OTel spans PK vs Fabric spans | Owner de sus spans | Owner de sus spans |
| Kubernetes Capacity Provider | No | Adapter opcional |

## Enlaces a código relevantes

PipelineK: `v2/pipeline-output/`, `v2/pipeline-output-store/`, `v2/pipeline-events/`, `v2/pipeline-application/` y `v2/pipeline-step-sdk/runtime/`. Fabric: `contracts/`, `worker/agent/`, `worker/spool/`, `worker/pipelinek-runtime-adapter/`, `control-plane/`, `adapters/control-api/`, `adapters/jenkins/plugin/`, `projection/`, `providers/capacity/`.

## Arquitectura hexagonal tests

- PK core/application no importa nombre de paquete Fabric/Jenkins/S3/Elastic/OTel exporter.
- Fabric contracts no importa adapters / Jenkins / transport networking / object client.
- Fabric projection kernel no importa controller/worker/HTTP/legacy Groovy.
- Todo adapter implements/consumes port y mapea refusals exhaustivamente.
- Gradle module boundaries y build scans sin `test` dependencies usadas en `src/main`.
- Ports públicos con test de fixture mínimo y consumer de SDK realmente publicado.
