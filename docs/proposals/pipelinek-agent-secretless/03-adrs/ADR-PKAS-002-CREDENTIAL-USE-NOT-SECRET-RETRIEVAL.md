# ADR-PKAS-002 — Credential use, not secret retrieval

**Status:** proposed.

## Decision

La API nueva se centra en `prepareUse(CredentialUseRequest)` y `CredentialAccessPlan`, no en `getSecret()`.

## Rationale

Permite signer/proxy/helper/session backends y conserva la invariante de ASV de que el agente no recibe material secreto.

## Compatibility

La API `resolve(): SecretHandle` existente permanece como adapter legacy/materialization path, no como API agent-facing ni autoridad del planner.

