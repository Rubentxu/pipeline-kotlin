# ADR-PKAS-001 — Single process execution spine

**Status:** proposed.  
**Decision:** el primer programa agent-secretless no crea un executor de procesos paralelo a `core.sh`.

## Context

`core.sh` ya posee durable execution, journal, replay, timeout, cancellation, transcript, redaction y workspace. Un `core.exec` independiente duplicaría semántica crítica y podría divergir.

## Decision

- `pipelinek command`/inline argv baja a `core.sh` mediante encoder canónico.
- `pipelinek sh` baja directamente a `core.sh`.
- `pipelinek step` usa StepRegistry.
- No `DurableExecExecutor`, no journal adicional.

## Consequences

Positiva: riesgo bajo y reuse máximo.  
Negativa: la primera versión argv sigue pasando por shell. Esta limitación debe ser visible.

## Future

Un `ArgvInvocation` sólo puede introducirse tras extraer `DurableProcessRuntime` con pruebas de equivalencia de `sh`.

