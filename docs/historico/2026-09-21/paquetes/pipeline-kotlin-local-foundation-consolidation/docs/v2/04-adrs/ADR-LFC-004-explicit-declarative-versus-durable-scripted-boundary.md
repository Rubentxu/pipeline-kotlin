# ADR-LFC-004 — Explicit declarative versus durable scripted boundary

**Status:** proposed

## Context

Jenkins users need runtime branching/results, but pretending builder calls like `pwd()` or `sh(returnStdout)` execute during model construction is incorrect.

## Decision

Keep declarative builders static. Provide an explicit `script {}` durable Kotlin runtime space for runtime values and control flow. Replace the current shell-concatenation interpretation of `script`.

## Consequences

Runtime dynamism becomes honest and durable. Some Jenkins Groovy expressions require migration recipes rather than literal transliteration.
