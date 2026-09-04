# ADR-LFC-012 — Resolve plugins before pipeline compilation and lock them

**Status:** proposed

## Context

Typed plugin DSL requires plugin classes/metadata to be known at compile time, and reproducible CI requires an immutable plugin set.

## Decision

Resolve and verify plugins in phase A, then compile the pipeline in phase B. Persist a digest-based lockfile. Runtime scripts may not resolve arbitrary new executable plugins.

## Consequences

IDE/compilation can expose typed façades; CI becomes reproducible; plugin update is an explicit operation.
