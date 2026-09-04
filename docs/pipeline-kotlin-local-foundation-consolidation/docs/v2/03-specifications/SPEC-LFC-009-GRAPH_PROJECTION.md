# SPEC-LFC-009 — Execution graph projection

**Status:** proposed

## Principle

The graph is rebuilt from canonical definition + durable execution facts. Runtime correctness never depends on graph database availability.

## Initial local projection

Represent:

- pipeline definition;
- stages/parallel branches/matrix cells;
- step definitions/executions/attempts;
- artifacts;
- credential references (never values);
- causal/retry/resume relationships;
- output ranges.

## API

```bash
pipeline graph <run-id> --format json
pipeline graph <run-id> --format dot
pipeline graph <run-id> --format mermaid
```

A richer graph database is deferred until scale/query UAT demonstrates a real need. The projector contract should permit replacement without changing execution.
