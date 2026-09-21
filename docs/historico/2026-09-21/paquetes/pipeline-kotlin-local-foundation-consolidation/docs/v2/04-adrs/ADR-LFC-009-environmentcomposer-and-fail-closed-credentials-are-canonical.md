# ADR-LFC-009 — EnvironmentComposer and fail-closed credentials are canonical

**Status:** proposed

## Context

Environment is composed in multiple locations and credentials may have nullable execution paths.

## Decision

`EnvironmentComposer` owns precedence. Credential bindings require an available provider/resolver; missing credentials fail before body execution. `EnvModel` and nullable bypass semantics are removed.

## Consequences

Security and reproducibility improve; hidden host-env behavior is reduced.
