# ADR-PKAS-005 — Congelar semántica legacy de `withCredentials`

**Status:** proposed.

## Decision

`withCredentials(CredentialsBinding...)` conserva payload y semántica actual.

El nuevo modelo usa tipos nuevos y un block contract distinto:

```text
withCredentials(CredentialProfileRef...)
→ core.credentials.scope/v1
```

## Rationale

Evita que recompilar una pipeline antigua cambie la forma de inyectar credenciales o su durable fingerprint.

