# Execution Profiles

## 1. Profile como policy compuesta

Un profile no es sólo credenciales. Es una receta de admisión y ejecución reusable.

Ejemplo conceptual:

```toml
schema = "pipelinek-profile/v1"

[profile.release-base.execution]
sandbox = "local"
timeout = "2m"

[profile.release-base.output]
mode = "agent"
events = ["StepFailed", "RunFinished"]

[profile.github-release]
extends = ["release-base"]

[profile.github-release.credentials.github]
ref = "github/release"
provider = "agent-secretless"
audience = ["api.github.com", "uploads.github.com"]
minimum_posture = "STRONG_SECRETLESS"

[profile.github-release.network]
allow = ["api.github.com", "uploads.github.com"]
```

## 2. Merge semantics

Profile composition debe ser determinista:

- scalar: child overrides parent;
- set allowlists: intersección por defecto para seguridad, salvo operador explícito;
- credential refs: unión por alias, conflicto fail-closed;
- minimum posture: tomar la más fuerte;
- deny rules: unión;
- output selection: child puede reducir, no ampliar acceso a datos no sanitizados.

## 3. Scope

Sources ordenadas propuestas:

1. built-in defaults;
2. user global profile;
3. user project profile;
4. explicit `--profile-file`;
5. CLI safe overrides no-secret.

No leer automáticamente un profile del repo salvo opt-in documentado.

## 4. Secret-free config

Los profiles contienen refs/policy, nunca secret values.

## 5. `auto` provider

`provider = "auto"` significa discovery entre providers permitidos, no “usa cualquier cosa”. El planner evalúa:

- availability;
- policy;
- posture;
- audience;
- tool support;
- operator preferences.

Debe emitir una decisión auditable sin revelar secreto.

