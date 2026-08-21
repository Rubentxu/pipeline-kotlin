# Spike Backlog

Todos los spikes son time-boxed y terminan en evidence + ADR accept/reject.

## SPIKE-001 — Kotlin 2.4 Scripting Host
**Pregunta:** ¿qué cambia respecto a la implementación actual y qué APIs experimentales necesitamos realmente?  
**Output:** adapter mínimo, compile diagnostics, classpath explícito, benchmark, API surface inventory.  
**Decisión:** ADR-0002/0019.

## SPIKE-002 — Context parameters + Step façade
**Pregunta:** ¿podemos eliminar inyección IR de PipelineContext conservando la sintaxis deseada?  
**Output:** `sh/echo/readFile` con context capabilities + tests.  
**Decisión:** ADR-0003/0004.

## SPIKE-003 — Durable replay
**Pregunta:** ¿puede Kotlin normal recuperar un run sin CPS?  
**Scenario:** `sh -> if -> sh`, kill/restart entre operaciones.  
**Output:** journal + operation fingerprint + divergence test.

## SPIKE-004 — Custom Jenkins FlowExecution
**Pregunta:** ¿qué mínimo necesita Jenkins Workflow para mostrar un FlowGraph persistente alimentado por eventos externos?  
**Output:** plugin demo FlowStart/Stage/Step/FlowEnd + controller restart.

## SPIKE-005 — Protobuf over Jenkins WebSocket
**Pregunta:** ¿podemos terminar worker sessions vía endpoint/plugin HTTPS/WebSocket sin Remoting?  
**Output:** hello/heartbeat/event/ack prototype y auth model.

## SPIKE-006 — gRPC Worker Gateway
**Pregunta:** coste/operación de gateway externo y load a 100/1000 simulated workers.  
**Output:** conformance parity, mTLS, benchmark.

## SPIKE-007 — Kotlin Build Tools API
**Pregunta:** ¿BTA aporta incremental/daemon/compatibility suficiente para reemplazar parte del compiler adapter cuando esté disponible para integradores?  
**Output:** feasibility notes, API churn, performance, adoption recommendation.

## SPIKE-008 — Native Kotlin compiler worker
**Pregunta:** si la línea nativa experimental madura, ¿reduce cold start de Pods?  
**Output:** benchmark contra JVM compiler; no production adoption sin stable evidence.

## SPIKE-009 — Kubernetes PodTemplate bridge
**Pregunta:** qué conceptos del plugin Jenkins Kubernetes pueden traducirse limpiamente a WorkerTemplate.  
**Scope:** inheritFrom, YAML merge, defaultContainer, serviceAccount, volumes, retries.  
**No scope:** inbound launcher/Remoting.

## SPIKE-010 — Credential projection
Comparar Jenkins secret binding vs K8s CSI/OIDC y confirmar que secret bytes pueden evitar gateway/controller en camino K8s.

## SPIKE-011 — Graph store benchmark
Comparar embedded/local y central options para projection queries. Event log sigue siendo truth; seleccionar por benchmark, no preferencia.

## SPIKE-012 — OCI plugin packaging
Prototype plugin artifact + manifest + SBOM + digest + lock resolution + signature verification hook.

## SPIKE-013 — Fork/diff safety
Definir qué effects permiten fork automatic, cuáles requieren simulate/approval y cómo detectar divergence.

## SPIKE-014 — Jenkinsfile migrator
Medir porcentaje de corpus declarative traducible por parser/rules antes de incorporar agentes IA para casos complejos.
