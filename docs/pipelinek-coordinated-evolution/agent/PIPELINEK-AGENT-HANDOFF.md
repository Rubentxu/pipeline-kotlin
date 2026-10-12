# Handoff al agente propietario de PipelineK

Eres responsable SOLO de https://github.com/Rubentxu/pipeline-kotlin. Tu contraparte es https://github.com/Rubentxu/pipelinek-fabric. Lee `README.md`, `roadmap/PIPELINEK-ROADMAP.md`, `coordination/INTERFACE_CONTRACT.md`, `coordination/PAIR_RELEASE_FLOW.md`, specs PK y ADR existentes del repo. Trabaja conforme a `AGENTS.md` y SDDK sin cambiar su configuración. Mueve estado/ciclos a home y no añadas morralla al repo. Conserva las decisiones vigentes de Observer/Output/Event/Recovery y la API pública.

Antes de cada bloque: HEAD local/remoto, release/artifact y SHA; comparar contra baseline; identificar contrato `CRIC-1` y par M actual; verificar si existe `PAIR_CERTIFIED` previo. Si NO hay par anterior certificado, únicamente puedes ejecutar bootstrap de M0 o trabajo aislado, no declarar M1 integrado.

**Entrega al Fabric Consumer Agent antes de integrar:** fichero `PK_CONTRACT_HANDOFF.md` con PK SHA, tag candidato, Maven coordinates, ABI diff, capability set, esquema de ids/event/output, ADTs/refusals, 5 fixtures reales (byte partial, stderr concurrente, redaction chunk, SIGKILL, reconnect), checksum artifact, pruebas focal/full release, riesgos y matrix N/N-1. No tomar ACK o release verbal por bueno.

Esperar `FAB_CONSUMER_VERDICT.md` respaldado por tests del consumidor en otra JVM. Si la adaptación rompe invariantes, priorizar compatibilidad o usar feature-gate con fallback tipado; no añadir dependencia Fabric en PK. Tras candidate green, publicar release PK compatible, verificar remote tag+artifact, entregarlo al agente Fabric. **No marcar M(n) DONE** hasta que Pair Integrator certifique ambas releases juntas y autorice M(n+1).

Tests: quirúrgicos al desarrollar, suite integrada y UAT/AAT reales al release; no bajar guardrails, sin GitHub Actions cuando CI local es obligatorio. Commits Conventional. Subir solo a branches/repo autorizado; no hacer force-push ni sobrescribir cambios ajenos.
