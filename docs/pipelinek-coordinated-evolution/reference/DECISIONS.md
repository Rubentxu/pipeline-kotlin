# Selección de propuestas: máxima utilidad / mínima complejidad accidental

## Principio de inversión

Aprobar solo capacidades que (a) cierran una garantía real, (b) permiten primera experiencia Jenkins observable, (c) reducen riesgo operacional medible, o (d) constituyen una frontera intercambiable necesaria. Las optimizaciones no demostradas se quedan como adaptadores/spikes opcionales, nunca en la ruta crítica. **No reabrir un ADR adoptado sin evidencia y supersession explícita.**

| Idea previa o nueva | Decisión | Propietario | Razón / límite |
|---|---|---|---|
| kubelet: reconciliación desired/observed | **ADOPTAR** | Fabric Worker | Evita duplicidad de ejecuciones y falsos terminales |
| kubelet CRI: contrato Worker/Runtime | **ADAPTAR** | Contrato de frontera | Puerto pequeño, no implementar CRI |
| Container Runtime / OCI / CRI en PipelineK | **DESCARTAR** | — | Imágenes, Pods, red y runtime fuera del dominio |
| containerd shim como inspiración | **ADOPTAR CONDICIONAL** | PipelineK | Reattach/supervisión, sin daemon nuevo salvo necesidad probada |
| Proveedores local/static y k8s | **MANTENER + OPC-K** | Fabric | Capacidad plugin; Kubernetes opcional y posterior |
| Event, Output y Telemetry como planos | **ADOPTAR** | PipelineK | Autoridades/garantías separadas; control como cuarto plano lógico |
| stdout/stderr por operación y frames | **ADOPTAR** | PipelineK | Bytes canónicos, orden observado y offsets individuales |
| Superstream ordenado global | **DESCARTAR** | — | Generaría causalidad ficticia |
| Wakeups + replay durable | **ADOPTAR** | PipelineK | Reduce polling, wakeups nunca autoridad |
| Streaming transporte bidireccional | **REUTILIZAR ADR-0022** | Fabric | No duplicar carrier; aislamiento control medido |
| ACK de páginas e idempotencia por rango | **ADOPTAR URGENTE** | Fabric | Bloquea replicación segura y liberación local |
| Outbox de referencias/pinning | **EVOLUCIONAR** | Fabric + seam PK | Evitar doble disco solo con retención garantizada |
| Snapshot+Watch / Replay+Follow | **ADOPTAR** | Fabric | Estado incremental ≠ historia de bytes |
| Watch cache / proyección incremental | **REUTILIZAR** | Fabric | Un fold por run, muchos lectores |
| Jenkins tradicional sin CPS | **CONSERVAR** | Fabric Jenkins | `WorkflowRun`, `LogStorage`, Stage View; sin runtime Groovy |
| Native per-step FlowNodes completos | **DIFERIR** | Fabric Jenkins | Coste/compatibilidad sin UAT de valor |
| ELK como fuente única de bytes | **DESCARTAR** | — | Documento/indexación ≠ byte offsets |
| Archivo remoto de logs | **ADOPTAR** | Fabric | Local pequeño con durabilidad independiente |
| Archive backend local + S3 compatible | **ADOPTAR** | Fabric | Primer seam intercambiable sin dependencia cloud |
| Elastic/OpenSearch/Loki | **DIFERIR COMO INDEXADORES** | Fabric | No bloquear replicación/Jenkins; plugin opcional tras evidencia |
| OTel Collector para ACK canónico | **DESCARTAR** | — | Su cola no demuestra nuestras leyes de offsets |
| OTel para telemetría y exportación | **ADOPTAR OPT-IN** | Ambos | Fallo de exporter no cambia outcomes |
| Clasificador APF/DRR y presión | **ADOPTAR PROGRESIVAMENTE** | Fabric | No starvation del control y fairness por run |
| Regex y filtros en producer | **DESCARTAR** | — | Filtrado de vistas no debe afectar evidencia |
| Consulta estructural pushdown | **ADOPTAR** | Fabric | Bajo coste con índices por stage/step/canal |
| DSL booleana general en PipelineK | **DESCARTAR** | — | `ObservationQuery` debe ser simple |
| Multi-run discovery estilo Stern | **ADOPTAR** | Fabric | Seguir productores futuros con permisos |
| Re2J/texto seguro | **CONDICIONAL** | Fabric | Si se habilita regex de usuarios, timeout no sustituye linealidad |
| Política `ReliableBuffered` | **DEFAULT** | Fabric | Conserva datos ante caídas dentro de presupuesto |
| `RemoteRequired` / `BestEffort` | **DIFERIR** | Fabric | UX compleja; nunca para hechos obligatorios |
| subida directa worker→S3 | **DIFERIR** | Fabric | Solo si gateway es cuello real |
| Kafka, etcd, NATS, otro broker | **NO DEPENDENCIA** | — | Stores y carrier existentes primero |
| MCP obligatorio en Fabric | **DESCARTAR** | — | CLI agent-first autodescriptiva; MCP si un cliente lo necesita |
| Filtros de datos para degradar red | **ADAPTAR** | Fabric | Diferir transporte, no descartar bytes durables |
| Artefactos por el stream de consola | **DESCARTAR** | — | Objetos grandes tienen seam propio, no stdout |

## Reglas de no-regresión

1. La lentitud del usuario no llega al sink del subprocess. La persistencia local sigue siendo un recurso finito.
2. Los eventos semánticos, el resultado del run y los valores tipados son canales independientes de la consola.
3. Solo un recibo de durabilidad autoriza el borrado de una copia necesaria; el fichero local no puede desaparecer por mera terminalidad si está pinneado para réplica.
4. La reclamación de una ejecución y la continuidad de un stream se protegen con fencing, secuencia, integridad e identidad. Un silencio no es conclusión.
5. Nada de reescribir `RunRegistry`, `ProjectionReducer` o `EventStore` por comodidad de transporte; adapters en fronteras.
6. El diseño es reproducible, extensible y seguro por defecto. Sin obligación de arrancar Elastic, Kubernetes o S3 para `pipelinek run`.
