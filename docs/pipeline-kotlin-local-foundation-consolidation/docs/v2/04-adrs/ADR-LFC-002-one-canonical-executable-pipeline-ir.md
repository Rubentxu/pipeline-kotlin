# ADR-LFC-002 — One canonical executable pipeline IR

**Status:** proposed

## Context

Multiple pipeline/step model authorities and mapper/registry bridges create temporal/name/algorithm connascence and make execution, graphing and validation disagree.

## Decision

Introduce one `CompiledPipeline` IR. DSL compilation produces it; validator/planner/runtime/graph consume it. Plugin payloads are schema-versioned. Remove bridge registries/mappers after migration.

## Consequences

A single model becomes a major simplification and stable plugin/runtime boundary. Migration requires characterization tests and temporary adapters that must expire inside LFC-1.
