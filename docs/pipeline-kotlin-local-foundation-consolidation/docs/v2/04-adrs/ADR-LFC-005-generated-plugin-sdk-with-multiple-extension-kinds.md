# ADR-LFC-005 — Generated plugin SDK with multiple extension kinds

**Status:** proposed

## Context

The current SDK direction is sound but metadata generation is partially hardcoded and treating all extensibility as steps would recreate Jenkins internals poorly.

## Decision

Define plugin API v1 with Step, BlockStep, Agent, Condition, Option, Tool, Parameter and CredentialBinding extension kinds. KSP generates descriptors, schemas, DSL façades, metadata and registration without a core-step switch.

## Consequences

Plugins become first-class and IDE-friendly. KSP/compiler compatibility becomes a maintained build-time API.
