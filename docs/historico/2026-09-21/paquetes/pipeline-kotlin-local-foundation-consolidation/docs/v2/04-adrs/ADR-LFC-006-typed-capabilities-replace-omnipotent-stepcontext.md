# ADR-LFC-006 — Typed capabilities replace omnipotent StepContext

**Status:** proposed

## Context

A generic context or service locator hides dependencies and lets plugins couple to infrastructure.

## Decision

Runtime handlers declare minimum platform services using typed interfaces and Kotlin context parameters where appropriate. Constructor DI is reserved for private plugin collaborators.

## Consequences

Dependencies are explicit, testable and admission-checkable. Existing StepContext usages must migrate.
