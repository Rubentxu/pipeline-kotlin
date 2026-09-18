# Local Production Ready (LPR) — definición de producto

Status: PROPOSED  
Programme: `openspec/changes/local-production-ready`

## 1. Product statement

`pipeline-kotlin` debe convertirse primero en una herramienta **local-first de CI/CD real**, instalable independientemente del repositorio fuente y adecuada para definir el ciclo build/test/package/artifact de proyectos de software.

El primer release LPR no pretende sustituir todo Jenkins ni construir todavía controller/workers remotos. Pretende ser una herramienta local confiable que, por su arquitectura, pueda evolucionar después a esas topologías.

## 2. User journey objetivo

```text
install
  ↓
pipelinek doctor
  ↓
pipelinek validate pipeline.kts
  ↓
pipelinek run pipeline.kts
  ↓
structured events + safe console + durable history
  ↓
exit code consumible por shell/IDE/agent
```

Por convención `pipelinek run` busca `./pipeline.kts` si no se proporciona ruta.

## 3. Supported profile: `local-core-v1`

El release solo promete aquello que pasa certificación. La tabla es una intención de producto, no una declaración de estado actual.

### Required for LPR-GATE-1

- ejecución: `echo`, `sh`, `error`, `sleep`;
- workspace/context: `dir`, `withEnv`, `withCredentials`, `pwd`, `isUnix`;
- filesystem: `readFile`, `writeFile`, `fileExists` (WU-LPR-104: ambas CERTIFIED con eventos `FileRead`/`FileExistsChecked`), `deleteDir`, `cleanWs`;
- control: `retry`, `timeout`, `catchError`, `warnError`, `parallel`;
- SCM: checkout/git local certificado — **MOVED OUT of `local-core-v1` (certification checkpoint 2026-09-18)**: the DSL fun does not even exist (compile crash), so there is no surface to certify; SCM checkout is deferred to a post-LPR profile amendment with its own WU, keeping the profile honest (only what is certified is promised);
- artifacts: `archiveArtifacts` mínimo certificado;
- runtime/replay: durable operation identity, replay/reconciliation y resume para efectos que lo requieran;
- observabilidad: Stage/Step/Run lifecycle, typed failure, console transcript separado, history/cursor;
- secrets: redaction before persistence/presentation.

### Explicitly non-blocking for first LPR

- `load`;
- `waitUntil` si su integración genérica `RepeatUntil` no está certificada;
- `when`/`post` avanzados si no tienen semantics reales y typed outcomes;
- controller/Jenkins UI;
- remote workers;
- Kubernetes workers;
- broker/event relay remoto;
- catálogo E2..E10 completo de plugins.

Una superficie existente que no entre en `local-core-v1` debe ser **experimental o rejected fail-closed**, nunca parecer soportada mientras haga un no-op o devuelva un placeholder.

## 4. Product modes

### Human default

`pipelinek run` usa `view=normal`, `format=text`:

- lifecycle semántico compacto;
- nada de firehose de console durante success;
- ante Step failure, muestra tail acotado del transcript relevante;
- resumen final + run id + duración.

### Verbose/full

`pipelinek run -v` == `--view full`: eventos + console live.

### Machine/agent

```bash
pipelinek run --view events --format jsonl
pipelinek inspect <run> --failed --context 5 --log-tail 100 --format json
```

No existe un `--agent-mode` especial: la capacidad agentic se compone con formatos, filtros, fields, cursores y consultas tipadas.

## 5. Production-readiness definition

Un release puede declararse `LOCAL_PRODUCTION_READY` solo si:

- se instala sin checkout del repo;
- sus archives son reproducibles;
- CLI/DSL supported subset está versionado;
- los real-project gates pasan desde la distribución instalada;
- ninguna view cambia effects, journal, fingerprints o outcomes;
- observability no crece O(total-output) en memoria;
- un consumer lento no frena al child process;
- secrets no aparecen en events/transcript/diagnostics;
- unsupported syntax falla antes de efectos;
- release artifacts tienen checksum y provenance/SBOM según el release gate;
- la misma distribución que se publica es la que se certifica.

## 6. Dogfooding

Tras LPR-GATE-1, el proyecto entra en una fase obligatoria de uso real. Ninguna expansión grande de catálogo precede a al menos varios pipelines reales mantenidos con `pipeline-kotlin`; los defects encontrados alimentan el siguiente milestone por riesgo y frecuencia, no por deseo de paridad exhaustiva con Jenkins.
