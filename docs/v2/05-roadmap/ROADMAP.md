# Roadmap V2 — Desarrollo evolutivo guiado por UAT

## Estrategia

El roadmap no sigue “primero domain, después infra, después UI”. Sigue **vertical slices** que atraviesan DSL→runtime→eventos→storage→adapter y terminan en una capacidad demostrable.

Cada milestone tiene cinco gates:

1. **Architecture Gate** — dependencias y ADR respetados.
2. **Quality Gate** — tests/coverage relevante + static analysis.
3. **Compatibility Gate** — schemas/DSL corpus.
4. **Operational Gate** — métricas, logs, failure handling.
5. **UAT Gate** — escenario observable aprobado.

## M0 — Baseline honesta y carril V2

### Objetivo
Poder construir y medir V2 sin seguir escondiendo errores mediante excludes.

### Entregables
- Kotlin 2.4.10 en V2 modules.
- CI real para módulos V2.
- clasificación KEEP/ADAPT/REWRITE/RETIRE de V1.
- nueva estructura de módulos base.
- architecture fitness tests.
- compatibility matrix inicial.
- docs V2 incorporadas.

### No hacer
No “arreglar todo V1” antes de empezar V2.

### Exit/UAT
- `./gradlew check` de V2 green.
- ningún source V2 excluido del compile task.
- UAT-M0-001: sample hello pipeline compila/valida de forma reproducible.

## M1 — Kotlin Scripting vertical + event spine

### Objetivo
Ejecutar `.pipeline.kts` mínimo con Kotlin 2.4.10 y emitir historia estructurada.

### Entregables
- `pipeline-domain`, `application`, `scripting-api`, `scripting-kotlin24`.
- ScriptDefinition con classpath explícito.
- events Run/Compilation.
- event store in-memory + SQLite reference.
- `pipeline validate` y `pipeline run` local mínimos.
- compiler cache key.

### Exit/UAT
- UAT-COMP-001/002: compile success/error source-mapped.
- UAT-EVT-001: run reconstruible desde event log.
- no `wholeClasspath=true` en production path.

## M2 — DSL Jenkins-familiar + Step SDK V2

### Objetivo
Conseguir la experiencia visible de producto antes de distribución.

### Entregables
- `pipeline/stages/stage/steps/post/environment/agent`.
- context parameters.
- KSP StepDescriptor generation.
- Steps: echo, sh, error, sleep.
- Jenkins Familiarity metadata.
- LSP metadata básica.
- compatibility corpus.

### Exit/UAT
- UAT-DSL-001..005.
- UAT-STEP-001..004.
- pipeline Jenkins-like compila sin compiler plugin FIR/IR.

## M3 — Worker runtime local + durable replay

### Objetivo
Demostrar la tesis diferencial: Kotlin dinámico durable sin CPS.

### Entregables
- worker runtime local;
- local journal SQLite WAL;
- durable operation fingerprints/results;
- `script {}`;
- retry/timeout/parallel frames;
- recovery algorithm;
- execution graph projection local.

### Exit/UAT
- matar worker después de `sh` completado y recuperar sin repetirlo;
- replay divergence fail-closed;
- parallel/retry state reproducible.

## M4 — Protocol + Gateway

### Objetivo
Separar físicamente control plane y worker.

### Entregables
- Protobuf v1;
- binary WebSocket transport;
- gateway;
- ACK/replay;
- heartbeats;
- leases/fencing;
- protocol compatibility suite;
- optional gRPC spike.

### Exit/UAT
- disconnect/reconnect sin pérdida;
- duplicate events idempotentes;
- old fencing token rechazado;
- cancel remote run.

## M5 — Kubernetes ephemeral workers + credentials

### Objetivo
Ejecutar el walking skeleton en Pods efímeros con identidad/credenciales seguras.

### Entregables
- WorkerProvisioner Kubernetes;
- WorkerPool/ExecutionProfile manifests;
- capabilities scheduler básico;
- Pod lifecycle events;
- Jenkins/Kubernetes credential providers;
- workload identity/CSI path;
- container security profile.

### Exit/UAT
- Pod por run;
- kill/evict Pod y retry/recovery según policy;
- secret no aparece en logs/events;
- `inheritFrom/yaml/defaultContainer` demostrables.

## M6 — Jenkins Workflow engine plugin

### Objetivo
Hacer de Jenkins un adapter real y visible.

### Entregables
- Kotlin Pipeline / Kotlin Pipeline from SCM;
- FlowDefinition/FlowExecution;
- event→FlowNode reducer;
- logs/status/cancel;
- controller restart recovery;
- Jenkins credential adapter;
- Kubernetes PodTemplate bridge spike/adaptación.

### Exit/UAT
- run visible en Jenkins UI;
- controller no compila user pipeline;
- controller restart conserva run;
- worker activity visible como Stage/Step.

## M7 — Ecosistema plugins familiar

### Objetivo
Cubrir el 80% de pipelines reales sin depender de plugins Jenkins.

### Plugins iniciales
- git/checkout;
- credentials-binding;
- junit;
- artifacts/stash;
- docker/container;
- kubernetes helpers;
- httpRequest;
- input/control.

### Entregables
- plugin manifest + lockfile;
- OCI packaging prototype;
- plugin compatibility matrix;
- migration recipes.

### Exit/UAT
Aplicación demo completa checkout→build→test→artifact→approval con sólo plugins V2.

## M8 — Graph/Provenance/Supply-chain + Gateway gRPC

### Objetivo
Convertir historia en conocimiento operacional y escalar conexiones.

### Entregables
- global Execution/Provenance projectors;
- graph query API;
- artifact direct upload;
- SBOM/provenance/signature relations;
- fork/diff MVP;
- gRPC/mTLS gateway production path;
- data-locality scheduler hints.

### Exit/UAT
- preguntar “qué commit produjo este artifact” y obtener chain verificable;
- fork de run seguro + diff;
- 100/500/1000 worker session load tests según entorno.

## M9 — Hardening, performance y policies

### Objetivo
Preparar release candidate.

### Entregables
- chaos suite;
- security profiles;
- policy engine;
- event snapshots/compaction;
- warm pools/caches;
- performance budgets;
- upgrade/rollback runbooks.

### Exit/UAT
- UAT master crítica 100%;
- SLO targets alcanzados en benchmark environment;
- security review sin Critical/High abiertas.

## M10 — V2.0 GA y migración

### Objetivo
Release estable con camino claro desde V1/Jenkins.

### Entregables
- semver/public compatibility policy;
- migration CLI alpha;
- docs/recipes;
- supported matrix;
- deprecation plan V1;
- release artifacts/SBOM/provenance.

### Exit
- dos aplicaciones de referencia migradas;
- upgrade/rollback ensayado;
- release candidate soak estable;
- UAT business/developer sign-off.

## UAT-M0-001 vs UAT-COMP-001 Disambiguation
- **UAT-M0-001** (M0 exit): V2 minimal API + HelloPipeline fixture
  reproduces the build greenly; no `.pipeline.kts` Custom Scripting involved.
- **UAT-COMP-001** (M1 exit): first real `.pipeline.kts` with
  `pipeline/stage/sh` compiled via Custom Scripting, diagnostics empty,
  artifact/cache identity recorded. First M1 exit, not M0.
