# ADR-EVO-005 — Introspection is a read-only projection, not an authority

**Status:** Proposed

## Decision

Create one application-level introspection service that projects StepRegistry, descriptors, provider metadata, LSP metadata, profiles and artifact catalogues.

No CLI/LSP/MCP adapter may maintain its own semantic Step catalogue.
