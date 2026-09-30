# Contrato de compatibilidad y breaking-change policy

## 1. Objetivo

Permitir evolución agent-first sin degradar el activo más costoso de PipelineK: la ejecución durable y reproducible de `sh`.

## 2. Política

### Compatible/aditivo

- nuevos provider adapters;
- profiles;
- nuevo RegistryBlock para credential scopes;
- nuevos ToolAdapters;
- CLI `step`;
- CLI command facade que baja a `core.sh`;
- output policies posteriores a redaction;
- nuevos events puramente aditivos con versionado compatible.

### Riesgo medio

- config overlays;
- automatic tool detection;
- credential requirement resolver en plugins;
- flow efímero;
- nueva metadata de postura.

### Breaking/prohibido en ASX V1

- cambiar payload de `StepSpec.Shell`;
- cambiar fingerprint de `core.sh` existente;
- cambiar `withCredentials(CredentialsBinding...)`;
- crear un segundo journal de procesos;
- reemplazar `DurableShellExecutor` durante el mismo tren;
- cambiar replay/cancel semantics para facilitar secretless;
- hacer que profiles legacy se auto-apliquen a pipelines existentes;
- convertir output filtering en una ruta que salte redaction.

## 3. Compatibility law

Para todo fixture legacy `F` que no use las nuevas APIs:

```text
compile_old(F) == compile_new(F)             // canonical relevant IR
fingerprint_old(F) == fingerprint_new(F)
events_semantics_old(F) == events_semantics_new(F)
outcome_old(F) == outcome_new(F)
replay_old(F) == replay_new(F)
```

Cuando bytes/event envelopes incluyan metadata global legítimamente aditiva, la prueba debe comparar el contrato congelado, no exigir identidad de campos no normativos.

## 4. Legacy `withCredentials`

Congelado como projection explícita. El nuevo planner sólo se activa con tipos nuevos:

```text
CredentialsBinding        → legacy semantics
CredentialProfileRef      → new planner
CredentialUseSpec         → new planner
```

Esto evita comportamiento diferente por recompilar el mismo script.

## 5. Schema/versioning

Nuevos scopes:

```text
core.credentials.scope / schema v1
```

Nunca reciclar el schema del bloque legacy.

Profiles deben tener versión explícita:

```toml
schema = "pipelinek-profile/v1"
```

## 6. Installed binary proof

No basta con unit tests. Toda WU que cambie:

- DSL;
- CLI;
- provider discovery;
- config overlay;
- packaging;

requiere prueba con `installDist`/artefacto candidato.

## 7. Deletion rule

Ninguna API legacy se elimina dentro de este programa. La deprecación, si alguna vez se decide, es un programa posterior con telemetry/evidence y release-major policy.

