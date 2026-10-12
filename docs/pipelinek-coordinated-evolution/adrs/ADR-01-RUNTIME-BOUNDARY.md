# ADR-EVO-01 — PipelineK es runtime autónomo; Fabric Worker es supervisor reconciliador

- Estado: **PROPOSED**; fecha 2026-10-10; propietarios: PipelineK+Fabric.
- Contexto: los proyectos ya separan proceso ejecutor, registry de Fabric y puertos observacionales. Kubernetes distingue kubelet y CRI, pero CRI exige gestionar sandboxes de Pods, imágenes, streams exec y lifecycle de contenedores.
- Alternativas: (A) PipelineK implementa CRI, (B) Fabric implementa toda ejecución, (C) contrato mínimo Worker/Runtime con PipelineK independiente.
- **Decisión: C.** PipelineK conserva journal/recovery, stdout/stderr y hechos; Fabric controla desired state, leases, reconciliación, capacidad y replicación. Contrato pequeño e interfaces segregadas `Lifecycle/Inspection/OutputRead/EventRead`; no `GodRuntime` omnipotente.
- Consecuencias: no adoptamos CRI ni OCI; interfaces deben ser ABI-safe y permitir embedded/external process. Una ejecución no se repite al perder la sesión; hay que observar/recover. Supervisión standalone sigue posible sin Fabric. La frontera sigue DIP/hexagonal.
- Gate: UAT-PK-001, UAT-FW-001/003, AAT-05/07. Reabrir únicamente si se requiere que Kubernetes reconozca al producto como runtime de Pods y se demuestra un caso de negocio y esfuerzo sostenido.
- Referencias: `specifications/SPEC-01-PIPELINEK-LIVE-PLANES.md`, `SPEC-02-RUNTIME-WORKER-RECONCILIATION.md`, `docs/decisions/ADR-0022` de Fabric.
