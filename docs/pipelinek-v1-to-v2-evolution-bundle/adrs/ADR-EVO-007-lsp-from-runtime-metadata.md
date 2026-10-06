# ADR-EVO-007 — LSP v2 consumes the common introspection model

**Status:** Proposed

## Decision

Retire V1 hard-coded completion/AST heuristics. V2 LSP obtains Step/plugin metadata from the common introspection snapshot plus scripting diagnostics/source mapping.

Installing a plugin must not require editing LSP production code.
