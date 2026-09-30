# Work Units propuestos

Los IDs son provisionales; al integrar deben asignarse según SDDK/ROADMAP vigentes.

## ASX-000 — Characterization baseline

**Cambios:** tests/docs únicamente.  
**Pruebas:** legacy DSL, core.sh payload/fingerprint/events, replay, withCredentials.  
**STOP:** cualquier fixture actual no reproducible.

## ASX-001 — IntegrationPosture + domain ADTs

Pure domain. Sin I/O. Property/exhaustiveness tests.

## ASX-002 — Profile schema v1

Parser total, unknown keys policy, composition laws, no secrets in schema, XDG resolution.

## ASX-003 — CredentialPlanner pure core

Decision table provider × adapter × posture × audience. Gate Teeth para preference inversion y fail-open.

## ASX-004 — `core.credentials.scope` contract

RegistryBlock versionado, body invocation canonical, no StepKey branch en coordinator.

## ASX-005 — DSL `withCredentials(profile)`

Overload nuevo. Compile positive/negative. Fitness test que legacy overload sigue generando payload original.

## ASX-006 — Per-Step credential lease lifecycle

Prepare/revoke alrededor del Step, cleanup en failure/timeout/cancel.

## ASX-010 — Local provider adapter

Reusar LocalSecretStore; no duplicar cifrado.

## ASX-011 — Linux Secret Service provider

D-Bus adapter; discovery; unavailable/locked cases fail-loud.

## ASX-012 — ASV adapter

Session/capability API. Fitness que prohíbe método `getSecret` en agent-facing adapter.

## ASX-013 — Legacy environment compatibility

Disponible sólo con posture RAW_PROCESS_EXPOSURE y opt-in/policy compatible.

## ASX-020 — Projection lifecycle

Temp dir, permissions, cleanup, retention policy.

## ASX-021 — Git/SSH adapters

Credential helper + SSH_AUTH_SOCK signer.

## ASX-022 — HTTP/curl adapter

Proxy preference; netrc fallback.

## ASX-023 — Maven adapter

Typed settings.xml overlay.

## ASX-024 — Gradle adapter

Typed properties/config overlay + cache separation.

## ASX-025 — npm adapter

Typed `.npmrc` + `npm_config_userconfig`.

## ASX-026 — Docker adapter

Credential helper preference + temporary DOCKER_CONFIG fallback.

## ASX-030 — OutputPolicy

Redaction ordering fitness + agent renderer.

## ASX-031 — Inline `pipelinek command`

Argv parser + canonical POSIX shell encoder; lowers to `core.sh`.

## ASX-032 — Inline `pipelinek step`

Registry-backed generic invocation + schema introspection.

## ASX-033 — Steps/profiles describe

Machine-readable introspection.

## ASX-034 — No-repo-litter runtime dirs

XDG state/config/cache resolution and cleanup.

## ASX-040 — Ephemeral flow

Transient PipelineSpec, same coordinator, no scripting compiler required.

## ASX-041 — MCP/skill adapter

Thin adapter over application ports. No credential bytes.

## ASX-050 — Adversarial/UAT closure

Falsifiability tests, installed distribution, tool matrix, replay/cancel, leak scans.

## ASX-051 — Dogfooding

Dos repos distintos; al menos un publish/auth operation real en entorno de prueba y un failure/recovery.

## ASX-052 — Release gate

Docs, limitations, security posture, migration guide, compatibility receipt.

