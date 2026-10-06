# ADR-EVO-003 — Static manifest admission precedes plugin class loading

**Status:** Proposed

## Decision

A distributable plugin uses the accepted S6 unified manifest; a Shared Library remains a distinct authoring kind. Static transport metadata is read without contributor initialization. Complete immutable dependency bytes are hashed/admitted before executable discovery, including on compiled-artifact hits. No second plugin registry/schema authority.

Runtime registration is cross-checked against the admitted manifest.

## Rejected

- trust after `ServiceLoader` only;
- self-declared digest as proof;
- delivery class as implicit trust.
