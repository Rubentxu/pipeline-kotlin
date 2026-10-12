# Fuentes e índice de trazabilidad

**Verificado para esta investigación:** lectura directa de los árboles y ficheros de los dos repositorios en SHAs del README; documentación upstream consultada el 2026-10-10. Los enlaces externos pueden evolucionar. Ninguno es evidencia de que el trabajo propuesto esté ya hecho.

## Fuentes Kubernetes y ecosystem

- CRI overview oficial: https://kubernetes.io/docs/concepts/containers/cri/ — kubelet gRPC runtime/image services; motiva descartar CRI en PipelineK.
- Node pressure eviction: https://kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/ — condiciones de recurso/eviction; adaptar sin pérdida silenciosa.
- API Priority and Fairness: https://kubernetes.io/docs/concepts/cluster-administration/flow-control/ — fairness/admission; long-running log tail puede estar fuera de APF y exige governor propio.
- Kubernetes watch/list: https://kubernetes.io/docs/reference/using-api/api-concepts/ — resourceVersion/watch y reconstrucción de estado.
- Scheduling framework: https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/ — Filter/Score/Reserve/Bind, inspiración limitada al Fabric Scheduler.
- Kubernetes Node leases: https://kubernetes.io/docs/concepts/architecture/leases/ — diferenciar heartbeat de ejecución.
- Kubernetes logging: https://kubernetes.io/docs/concepts/cluster-administration/logging/ — logs nodo y backend central externo.
- containerd Runtime v2: https://github.com/containerd/containerd/blob/main/docs/runtime-v2.md — shim como inspiración, no replicar API.
- OpenTelemetry Collector resilience: https://opentelemetry.io/docs/collector/resiliency/ — colas persistentes/retries son auxiliares, no sustituyen ACK de Fabric.
- OTel Semantic Conventions CI/CD: https://opentelemetry.io/docs/specs/semconv/registry/attributes/cicd/ — correlación de spans/métricas.
- Grafana Loki labels: https://grafana.com/docs/loki/latest/get-started/labels/ — evitar alta cardinalidad y preferir structured metadata.
- Grafana Loki structured metadata: https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/ — metadata de alta cardinalidad como run/trace id.
- S3 strong read-after-write: https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html — AWS S3; no extrapolar sin prueba a clones compatibles.
- Elasticsearch bulk ingest: https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html — documentos e indexación, no byte stream canónico.

## Código y ADR PipelineK (main)

- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/02-architecture/OBSERVATION_STREAM_ARCHITECTURE.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0085-observation-plane-isolation.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0086-console-event-value-separation.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0088-cli-observation-contract.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStore.kt
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunOutputRetention.kt
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/observation/ObservationQuery.kt

## Código y ADR Fabric (main)

- https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/decisions/ADR-0022-the-carrier-is-a-persistent-stream.md
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/decisions/ADR-0024-the-fencing-token-is-an-identity.md
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/decisions/ADR-0012-observation-and-console-boundaries.md
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/pipelinek-fabric-proposal-2026-10-03/01-specifications/JENKINS_SEAMS_AND_RED_LINES.md
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/worker/spool/src/main/kotlin/io/jenkins/pipelinek/fabric/worker/spool/FileSpool.kt
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/worker/agent/src/main/kotlin/io/jenkins/pipelinek/fabric/worker/agent/SpoolDrain.kt
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/control-plane/application/src/main/kotlin/io/jenkins/pipelinek/fabric/controlplane/application/observation/FactNoticeReceiver.kt
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/worker/pipelinek-runtime-adapter/src/main/kotlin/io/jenkins/pipelinek/fabric/worker/pipelinekadapter/local/LocalPipelineKExecutionStrategy.kt
- https://github.com/Rubentxu/pipelinek-fabric/blob/main/adapters/control-api/proto/src/main/proto/fabric_control.proto

## Mapa fuentes ↔ decisiones

| Fuente | ADR/propuesta |
|---|---|
| CRI/containerd | ADR-01 y ADR-10 |
| Kubelet reconciliation/Lease | ADR-03, SPEC-02 |
| Output/Event model existing | ADR-02, SPEC-01 |
| Fabric worker notice/spool code | ADR-04, SPEC-03 |
| Kubernetes API watch | ADR-05, SPEC-04 |
| Logging + S3/Elastic/Loki | ADR-06, SPEC-05 |
| APF/node pressure | ADR-07, SPEC-06 |
| Loki indexing and safe query | ADR-08, SPEC-07 |
| OTel & security boundary | ADR-09, SPEC-08 |
