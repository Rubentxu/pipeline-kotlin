# ADR-LFC-015 — Sandbox strength is explicit and layered

**Status:** proposed

## Context

Current local profile semantics can be mistaken for OS isolation.

## Decision

Distinguish standard local execution from hardened isolation. Keep strong sandbox backends behind adapters; spike bubblewrap for Linux only after core runtime convergence.

## Consequences

Security claims remain accurate and the portable runtime is not blocked by platform-specific isolation.
