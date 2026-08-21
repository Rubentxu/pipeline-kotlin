# DSL & Kotlin Compatibility Corpus

## Objetivo

Detectar cambios semánticos del compiler/scripting host antes de actualizar la versión certificada.

## Corpus mínimo

```text
compatibility/
  basic.pipeline.kts
  environment.pipeline.kts
  stages.pipeline.kts
  sh-results.pipeline.kts
  scripted-if.pipeline.kts
  loop.pipeline.kts
  retry.pipeline.kts
  timeout.pipeline.kts
  parallel.pipeline.kts
  credentials.pipeline.kts
  kubernetes-agent.pipeline.kts
  plugin-imports.pipeline.kts
  compilation-error.pipeline.kts
  deprecated-api.pipeline.kts
  source-mapping.pipeline.kts
```

## Outputs comparados

- compile success/failure;
- normalized diagnostics;
- source ranges;
- generated descriptors;
- declarative skeleton;
- event trace para deterministic fixtures;
- compiled cache key dimensions;
- startup/compile benchmark.

## Matriz

### Blocking
Kotlin estable certificada.

### Pre-adoption
Siguiente estable/RC candidato.

### Early warning
EAP/nightly si viable.

## Diff policy

Todo diff se clasifica:
- expected language change;
- diagnostics-only;
- performance regression;
- scripting API break;
- semantic execution change;
- compiler bug.

Semantic changes requieren ADR/release note/migration o bloqueo de upgrade.
