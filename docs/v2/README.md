# Pipeline Kotlin V2 — Architecture & Delivery Pack

**Estado:** Propuesta arquitectónica lista para ejecución incremental  
**Fecha de referencia:** 2026-08-21  
**Proyecto objetivo:** `Rubentxu/pipeline-kotlin`

Este paquete convierte las propuestas refinadas para `pipeline-kotlin` en un sistema documental ejecutable: visión, PRD, arquitectura, especificaciones, ADR, roadmap por hitos, migración, testing, UAT, spikes y operación.

## Objetivo

Evolucionar `pipeline-kotlin` hacia un motor de CI/CD:

- **Kotlin-first y Jenkins-familiar** en la experiencia del developer.
- **Worker-first**: compilación y ejecución real fuera del controller.
- **Kubernetes-first**, pero no Kubernetes-coupled.
- **Durable y recuperable** sin CPS mediante replay de operaciones durables.
- **Event-sourced y graph-native**: el event log es la fuente de verdad y los grafos son proyecciones.
- **Plugin-first**, con ecosistema propio y APIs familiares inspiradas en Jenkins.
- **Provider-neutral** para credentials, workers, artifacts, caches, graph stores y event stores.
- **Supply-chain aware**: provenance, SBOM, firmas y digests forman parte del dominio.
- **Jenkins como adapter/control-plane inicial**, no como dependencia del core.

## Decisiones clave

1. `.pipeline.kts` continúa como experiencia principal usando **Kotlin Custom Scripting** encapsulado detrás de un port propio.
2. Kotlin **2.4.10** es la línea inicial de referencia; se aprovechan **context parameters** estables.
3. El uso de `kotlin.script.experimental.*` se acepta deliberadamente y se contiene en adapters versionados.
4. El compiler plugin FIR/IR deja de ser crítico. **KSP/codegen + context parameters** cubren la mayor parte del Step SDK.
5. El DSL preserva la familiaridad de Jenkins declarative/scripted, pero no sus limitaciones internas.
6. El runtime no serializa continuaciones. Registra resultados de operaciones durables y reconstruye ejecución por **replay**.
7. Controller y workers intercambian contratos **Protobuf versionados**; se evita Java serialization/Remoting como runtime del nuevo motor.
8. Kubernetes provisiona workers efímeros mediante un `WorkerProvisioner`; un bridge opcional reutiliza `PodTemplate` Jenkins.
9. Credentials se modelan como `CredentialRef -> CredentialLease -> CredentialProjection`.
10. El event log es source of truth; Execution, Provenance, Plan y Jenkins FlowGraph son proyecciones.
11. Plugins se distribuyen como artefactos versionados y verificables; OCI es el objetivo de distribución.
12. Jenkins Workflow se integra implementando `FlowDefinition`/`FlowExecution` y proyectando eventos a `FlowNode`.

## Incorporación recomendada

Copiar este árbol bajo `docs/v2/`. No reemplazar de golpe la documentación V1: V1 y V2 deben convivir hasta que cada capacidad alcance su gate de UAT.

## Orden de lectura

1. `00-context/VISION.md`
2. `00-context/CURRENT_STATE.md`
3. `01-product/PRD_V2.md`
4. `02-architecture/ARCHITECTURE.md`
5. `02-architecture/RUNTIME_MODEL.md`
6. `03-specifications/DSL_SPEC.md`
7. `03-specifications/EVENT_MODEL.md`
8. `03-specifications/WORKER_PROTOCOL.md`
9. `04-adrs/README.md`
10. `05-roadmap/ROADMAP.md`
11. `07-uat/UAT_MASTER_PLAN.md`

## Gobierno documental

Ninguna decisión estructural nueva debe entrar sin:

- especificación actualizada;
- ADR si es difícil de revertir;
- tests/fitness functions que protejan el contrato;
- UAT asociado si modifica comportamiento observable;
- actualización de la matriz de trazabilidad.
