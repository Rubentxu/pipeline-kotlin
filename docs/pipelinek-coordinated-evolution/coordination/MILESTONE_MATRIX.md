# Matriz coordinada de hitos (un mismo M-ID para dos repos)

| Par | PipelineK | Fabric | Evidencia conjunta de promoción |
|---|---|---|---|
| **M0** | Comprobar ABI publicada/capacidades; sin cambio salvo defecto demostrado | Sanear Fact ACK, dedupe y corrupción spool | Contrato base verde, 2 páginas distintas mismo executor, corrupción no escondida |
| **M1** | Output/Event reader safe live + ABI publicada; release PK si cambia | Adapter incremental real para PK publicado, sin `waitFor`/`StringBuilder` gigante | Mismo run vivo observado desde ambos procesos antes del terminal |
| **M2** | `inspect/recover/cancel` si ya existe: publicar solo seam faltante, sin semántica Fabric | Worker reconciler + desired/observed, lease y fencing | SIGKILL worker, process vivo, reattach sin doble efecto |
| **M3** | Retención estable y lecturas por rango; cambios solo si falta seam | Durable multi-stream, receiver OutputSpooled, gap/ACK exacto, prioridad control | Red caída/ACK perdido, replay byte por byte y cancel progresiva |
| **M4** | Ningún Jenkins import; ABI solo cuando está demostrado | Snapshot+Watch, consola virtual, Stage View y Jenkins Live | Jenkins + inspector simultáneos sobre SAME RUN con restart |
| **M5** | Política pin/release de segmentos solo si es necesaria y cabe como port genérico | ArchivePort local/S3, catálogo/retención y backend publicado | Commit objeto→manifiesto→ACK; sin borrado hasta réplica confirmada |
| **M6** | Contexto run/stage/step en índices locales y telemetría de presión acotada | Fairness, quotas, QueryPlanner, filtros y CLI agent-first | Flood de stdout, error en segundo run visible y queries seguras |
| **M7** | Instrumentación OTel, seguridad, soak, ABI/release | Cross-release/certificación completa, OTel, seguridad y soak | Suite matrix N/N-1, Jenkins real, caída redes/índice y SLO medidos |
| **OPC-K** | CERO CRI/OCI propio; portable | CapacityProvider K8s opcional | Pod/Job real, sin ejecutar efectos dos veces, no bloquea M0-M7 |

## Fases necesarias de cada M

`DISCOVERED → CONTRACT_LOCKED → PREPARED_BOTH → CANDIDATE_INTEROP_GREEN → PK_PUBLISHED_IF_CHANGED → FABRIC_PUBLISHED_IF_CHANGED → PAIR_CERTIFIED → NEXT_UNLOCKED`.

Cambios en contrato, ABI, wire, commit, test harness, versión del artefacto o firma del par después de `CANDIDATE_INTEROP_GREEN` obligan a repetir pruebas necesarias. Cambios en tags publicados exigen nuevo recibo completo.

## Evidencia mínima por paquete

- PK: SHA remoto exacto, tag o release compatible publicado, ABI/artifact SHA-256, matrix de capabilities y test UAT PK.
- Fabric: SHA remoto exacto, tag publicado, servicio/adapter exacto, TestKit de interoperabilidad contra el artefacto PK publicado, UAT/AAT conjuntas.
- Pair: CRIC hash idéntico, refs remotas y SHA, logs de tests con hash, catalogación del par, plan de rollback y `PAIR_CERTIFIED` con fecha/autoridad.

Los hitos cuyo código solo cambia en Fabric no exigen publicar artificialmente una versión PK nueva: certifican la última PK publicada contra la nueva Fabric e indican `changed=false` en el recibo.
