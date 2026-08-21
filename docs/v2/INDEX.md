# Documentation Index

| Documento | Propósito / título |
|---|---|
| [`00-context/CURRENT_STATE.md`](00-context/CURRENT_STATE.md) | Estado actual y deuda que V2 debe resolver |
| [`00-context/GLOSSARY.md`](00-context/GLOSSARY.md) | Glosario |
| [`00-context/OPEN_QUESTIONS.md`](00-context/OPEN_QUESTIONS.md) | Open Questions |
| [`00-context/PRINCIPLES.md`](00-context/PRINCIPLES.md) | Principios |
| [`00-context/RISK_REGISTER.md`](00-context/RISK_REGISTER.md) | Risk Register |
| [`00-context/TRACEABILITY.md`](00-context/TRACEABILITY.md) | Matriz de trazabilidad |
| [`00-context/VISION.md`](00-context/VISION.md) | Visión V2 |
| [`01-product/DEVELOPER_EXPERIENCE.md`](01-product/DEVELOPER_EXPERIENCE.md) | Developer Experience |
| [`01-product/JENKINS_FAMILIARITY.md`](01-product/JENKINS_FAMILIARITY.md) | Jenkins Familiarity Contract |
| [`01-product/PRD_V2.md`](01-product/PRD_V2.md) | PRD — Pipeline Kotlin V2 |
| [`02-architecture/ARCHITECTURE.md`](02-architecture/ARCHITECTURE.md) | Arquitectura V2 |
| [`02-architecture/C4.md`](02-architecture/C4.md) | C4 Model |
| [`02-architecture/EVENT_GRAPH_ARCHITECTURE.md`](02-architecture/EVENT_GRAPH_ARCHITECTURE.md) | Event + Graph Architecture |
| [`02-architecture/MODULES.md`](02-architecture/MODULES.md) | Module Boundaries |
| [`02-architecture/OBSERVABILITY.md`](02-architecture/OBSERVABILITY.md) | Observability |
| [`02-architecture/RUNTIME_MODEL.md`](02-architecture/RUNTIME_MODEL.md) | Runtime Model — Durable Kotlin without CPS |
| [`02-architecture/SECURITY.md`](02-architecture/SECURITY.md) | Security Architecture |
| [`03-specifications/ARTIFACTS_SUPPLY_CHAIN.md`](03-specifications/ARTIFACTS_SUPPLY_CHAIN.md) | Artifacts & Software Supply Chain Specification |
| [`03-specifications/CONFIG_MANIFESTS.md`](03-specifications/CONFIG_MANIFESTS.md) | Configuration Manifests Specification |
| [`03-specifications/CREDENTIALS_PROVIDERS.md`](03-specifications/CREDENTIALS_PROVIDERS.md) | Credentials Provider Specification |
| [`03-specifications/DSL_SPEC.md`](03-specifications/DSL_SPEC.md) | DSL Specification V2 |
| [`03-specifications/EVENT_MODEL.md`](03-specifications/EVENT_MODEL.md) | Event Model Specification |
| [`03-specifications/GRAPH_MODEL.md`](03-specifications/GRAPH_MODEL.md) | Graph Model Specification |
| [`03-specifications/JENKINS_PLUGIN.md`](03-specifications/JENKINS_PLUGIN.md) | Jenkins Workflow Plugin Specification |
| [`03-specifications/KUBERNETES_WORKERS.md`](03-specifications/KUBERNETES_WORKERS.md) | Kubernetes Ephemeral Workers Specification |
| [`03-specifications/RECOVERY_DURABILITY.md`](03-specifications/RECOVERY_DURABILITY.md) | Recovery & Durability Specification |
| [`03-specifications/SCRIPTING_COMPILER_SPEC.md`](03-specifications/SCRIPTING_COMPILER_SPEC.md) | Kotlin Scripting & Compiler Specification |
| [`03-specifications/STEP_PLUGIN_SDK.md`](03-specifications/STEP_PLUGIN_SDK.md) | Step & Plugin SDK Specification |
| [`03-specifications/WORKER_PROTOCOL.md`](03-specifications/WORKER_PROTOCOL.md) | Worker Protocol Specification |
| [`04-adrs/ADR-0001.md`](04-adrs/ADR-0001.md) | ADR-0001: Motor independiente; Jenkins como adapter |
| [`04-adrs/ADR-0002.md`](04-adrs/ADR-0002.md) | ADR-0002: Kotlin Custom Scripting como frontend principal |
| [`04-adrs/ADR-0003.md`](04-adrs/ADR-0003.md) | ADR-0003: Context parameters para capabilities de Steps |
| [`04-adrs/ADR-0004.md`](04-adrs/ADR-0004.md) | ADR-0004: KSP como codegen principal; FIR/IR opcional |
| [`04-adrs/ADR-0005.md`](04-adrs/ADR-0005.md) | ADR-0005: Jenkins Familiarity Contract |
| [`04-adrs/ADR-0006.md`](04-adrs/ADR-0006.md) | ADR-0006: Durable replay en lugar de CPS |
| [`04-adrs/ADR-0007.md`](04-adrs/ADR-0007.md) | ADR-0007: Event Log como fuente de verdad |
| [`04-adrs/ADR-0008.md`](04-adrs/ADR-0008.md) | ADR-0008: Graph-native mediante proyecciones |
| [`04-adrs/ADR-0009.md`](04-adrs/ADR-0009.md) | ADR-0009: Protobuf para contratos wire |
| [`04-adrs/ADR-0010.md`](04-adrs/ADR-0010.md) | ADR-0010: WebSocket MVP y gRPC Gateway como target |
| [`04-adrs/ADR-0011.md`](04-adrs/ADR-0011.md) | ADR-0011: At-least-once, ACK/replay, leases y fencing |
| [`04-adrs/ADR-0012.md`](04-adrs/ADR-0012.md) | ADR-0012: Workers V2 no son Jenkins Node/Computer por defecto |
| [`04-adrs/ADR-0013.md`](04-adrs/ADR-0013.md) | ADR-0013: Kubernetes como WorkerProvisioner |
| [`04-adrs/ADR-0014.md`](04-adrs/ADR-0014.md) | ADR-0014: Credential provider + lease + projection |
| [`04-adrs/ADR-0015.md`](04-adrs/ADR-0015.md) | ADR-0015: Plugins propios verificables y distribuibles |
| [`04-adrs/ADR-0016.md`](04-adrs/ADR-0016.md) | ADR-0016: Sandbox OS/container, no Java Security Manager |
| [`04-adrs/ADR-0017.md`](04-adrs/ADR-0017.md) | ADR-0017: Artifacts direct-to-store y provenance nativa |
| [`04-adrs/ADR-0018.md`](04-adrs/ADR-0018.md) | ADR-0018: Configuración resource-style inspirada en Kubernetes |
| [`04-adrs/ADR-0019.md`](04-adrs/ADR-0019.md) | ADR-0019: Matriz certificada Kotlin/runtime/plugin API |
| [`04-adrs/ADR-0020.md`](04-adrs/ADR-0020.md) | ADR-0020: BTA como adapter futuro, no dependencia V2.0 |
| [`04-adrs/README.md`](04-adrs/README.md) | ADR Index |
| [`05-roadmap/DEVELOPMENT_STRATEGY.md`](05-roadmap/DEVELOPMENT_STRATEGY.md) | Estrategia de desarrollo evolutivo |
| [`05-roadmap/IMPLEMENTATION_BACKLOG.md`](05-roadmap/IMPLEMENTATION_BACKLOG.md) | Implementation Backlog |
| [`05-roadmap/MIGRATION_PLAN.md`](05-roadmap/MIGRATION_PLAN.md) | Migration Plan V1 → V2 |
| [`05-roadmap/MILESTONES.md`](05-roadmap/MILESTONES.md) | Milestone Gates y Definition of Done |
| [`05-roadmap/RELEASE_STRATEGY.md`](05-roadmap/RELEASE_STRATEGY.md) | Release & Compatibility Strategy |
| [`05-roadmap/ROADMAP.md`](05-roadmap/ROADMAP.md) | Roadmap V2 — Desarrollo evolutivo guiado por UAT |
| [`06-quality/ARCHITECTURE_FITNESS.md`](06-quality/ARCHITECTURE_FITNESS.md) | Architecture Fitness Functions |
| [`06-quality/COMPATIBILITY_CORPUS.md`](06-quality/COMPATIBILITY_CORPUS.md) | DSL & Kotlin Compatibility Corpus |
| [`06-quality/PERFORMANCE_BENCHMARKS.md`](06-quality/PERFORMANCE_BENCHMARKS.md) | Performance Benchmark Plan |
| [`06-quality/TEST_STRATEGY.md`](06-quality/TEST_STRATEGY.md) | Test Strategy |
| [`07-uat/UAT_ACCEPTANCE_MATRIX.md`](07-uat/UAT_ACCEPTANCE_MATRIX.md) | UAT Acceptance Matrix |
| [`07-uat/UAT_MASTER_PLAN.md`](07-uat/UAT_MASTER_PLAN.md) | UAT Master Plan |
| [`07-uat/UAT_SCENARIOS.md`](07-uat/UAT_SCENARIOS.md) | UAT Scenarios |
| [`08-spikes/SPIKES.md`](08-spikes/SPIKES.md) | Spike Backlog |
| [`09-operations/RUNBOOK.md`](09-operations/RUNBOOK.md) | Operations Runbook |
| [`09-operations/SLO_SLA.md`](09-operations/SLO_SLA.md) | SLO / Reliability Targets |
| [`10-templates/ADR_TEMPLATE.md`](10-templates/ADR_TEMPLATE.md) | ADR-NNNN: Título |
| [`10-templates/MILESTONE_TEMPLATE.md`](10-templates/MILESTONE_TEMPLATE.md) | Mx — Milestone name |
| [`10-templates/SPEC_TEMPLATE.md`](10-templates/SPEC_TEMPLATE.md) | Specification: Name |
| [`10-templates/UAT_TEMPLATE.md`](10-templates/UAT_TEMPLATE.md) | UAT-XXX-NNN — Title |
| [`DESIGN.md`](DESIGN.md) | DESIGN — Pipeline Kotlin V2 |
| [`INTEGRATION_GUIDE.md`](INTEGRATION_GUIDE.md) | Integration Guide |
| [`README.md`](README.md) | Pipeline Kotlin V2 — Architecture & Delivery Pack |
| [`REFERENCES.md`](REFERENCES.md) | Research References / Evidence Baseline |
