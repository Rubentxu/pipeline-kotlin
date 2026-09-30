# ADR-PKAS-008 — Redaction before filtering

**Status:** proposed.

## Decision

Toda extensión de filtros/rendering opera únicamente sobre eventos/resultados ya sanitizados.

```text
raw → mandatory redaction → canonical sanitized data → optional filters
```

No existe hook configurable antes de redaction.

