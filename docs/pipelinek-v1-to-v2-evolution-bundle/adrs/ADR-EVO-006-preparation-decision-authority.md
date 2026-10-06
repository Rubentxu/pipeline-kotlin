# ADR-EVO-006 — One registry preparation decision powers runtime admission and planning

**Status:** Proposed

## Decision

Extract the side-effect-free typed decision from current `RegistryExecutionPreparation`. Runtime execution preparation, `--plan`, `why-not`, diagnostics and affordances consume that decision.

There is no second policy/admission engine for tooling.
