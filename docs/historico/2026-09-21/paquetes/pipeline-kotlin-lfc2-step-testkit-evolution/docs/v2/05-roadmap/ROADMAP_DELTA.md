# ROADMAP delta — LFC-2 Step/TestKit evolution

> Fusionar en el ROADMAP LFC canónico.

## Delivery policy amendment

Añadir:

> Un Step sólo puede considerarse completo cuando está `CERTIFIED` según ADR-LFC-023/SPEC-LFC-019. La existencia de DSL façade, handler o unit test no constituye cierre. Core y plugins externos deben usar el mismo execution path.

## LFC-2 — Honest Jenkins-like DSL + certified Step seam

**Outcome:** DSL familiar sin fake runtime values, respaldado por una arquitectura de Steps extensible demostrada y examples ejecutables.

### LFC2-H00 — Step Constitution

- aceptar ADR-LFC-018/019/022/023;
- aceptar SPEC-LFC-016;
- añadir reglas AGENTS;
- añadir fitness tests.

**Gate:** no ambigüedad sobre StepDefinition, capabilities, bodies, declarative/scripted o certification.

### LFC2-TK0 — ScenarioRunner

- manifest;
- soporte layout actual de examples;
- compile/run/exit/events/diagnostics;
- eliminar expected-exit hardcoded del runner actual tras parity.

**Gate:** los mismos examples del README se ejecutan realmente.

### LFC2-TK1 — PipelineExtension

Expandir `pipeline-testkit` con:

- isolated workspace/stores;
- recording capabilities;
- typed assertions;
- deterministic clock/ids.

### LFC2-X01 — Generic atomic Step seam

Promoción mínima LFC-3/LFC-4:

- StepDefinition;
- StepContract;
- StepRegistry;
- capability resolver/context bridge;
- erased runtime adapter;
- canonical Invoke.

Migrar sólo `echo` y `sh`.

**Gate:** dispatcher/compiler canónico no tiene casos concretos echo/sh.

### LFC2-TK2 — RealPipelineExtension

- fork installDist;
- real classpath;
- plugin discovery real;
- process cleanup.

### LFC2-X02 — External plugin proof

Plugin independiente `example.uppercase`.

**Gate:** compila/ejecuta desde `.pipeline.kts` sin modificar domain/application/compiler/dispatcher.

### LFC2-TK3 — StepContractSuite

Implementar C01..C19.

**Gate:** echo, sh y external reference = CERTIFIED.

### LFC2-X03 — Strict DSL

Incluye:

- @PipelineDsl;
- narrow scopes;
- no mutate-last-step;
- scmGit puro;
- runtime-return declarative removed.

### LFC2-X04 — Generic bodies

- StepBodies;
- BodyInvoker;
- context patches;
- migrar dir/withEnv/timestamps.

### LFC2-X05 — Retry + timeout

BodyInvoker-based.

**Gate:** retry count/timeout config sobreviven IR y hay efectos reales.

### LFC2-X06 — Composable parallel

Named bodies + BranchInvoker.

**Gate:** siblings seriales, overlap real, failure/cancel/replay green.

### LFC2-X07 — Durable scripted values

Mover runtime-valued APIs al boundary scripted.

Mínimo: `shStdout`.

### LFC2-X08 — Conditions + post

- StageCondition ADT;
- typed skip;
- post lifecycle matrix.

### LFC2-X09 — Formal scripting + source fidelity

- dedicated @KotlinScript;
- source mapping;
- retirar source-wide ScriptTextEscaper.

### LFC2-TK4 — Restart + sandbox

- PipelineSessionExtension;
- T3 kill/resume;
- T4 Podman security lane.

## LFC-2 final gate

LFC-2 sólo cierra cuando:

1. cero mandatory UAT disabled/quarantined;
2. echo/sh certified;
3. un external Step certified;
4. Jenkins fixtures -> expected IR;
5. no fake declarative runtime values;
6. dir/withEnv sobre BodyInvoker;
7. retry/timeout/parallel con semántica real;
8. README examples ejecutados por ScenarioRunner;
9. fitness sin central Step switch/KSP known-name;
10. T2 real plugin proof green.

## LFC-3 refinement

LFC-3 estabiliza y expande el seam ya probado:

- extension kinds completos;
- manifest/schema compatibility;
- KSP generic;
- generated façades;
- public Plugin TestKit;
- atomic + block reference plugins;
- compatibility matrix.

## LFC-4 refinement

Generalizar spine probado por echo/sh al resto de atomic Steps.

## LFC-5 refinement

Generalizar BodyInvoker probado en LFC-2 a credentials/error wrappers/nested matrix.
