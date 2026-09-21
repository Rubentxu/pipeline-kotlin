# ADR-LFC-016 — Plugin TestKit is a supported SDK component

**Status:** proposed

## Context

Third-party plugin quality cannot depend on every author recreating runtime mocks and Jenkins compatibility checks.

## Decision

Ship reusable recording capabilities, fixture compiler and contract suites as `pipeline-plugin-testkit`.

## Consequences

Plugin ecosystem quality is enforceable and reference test adapters leave production domain modules.
