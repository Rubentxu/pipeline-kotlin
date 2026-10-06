# ADR-EVO-002 — Introduce `ScriptDependencyPlan` as the single dependency composition authority

**Status:** Proposed

## Decision

Evolve raw classpath composition into one typed, frozen plan separating approved SDK API, plugins, Shared Libraries and attached transitive support nodes. Derive ordered compile/evaluation and plugin-discovery views from that plan. Hash/admit immutable snapshots; never reopen mutable paths as the verified bytes. Preserve effective precedence and reuse canonical S6 contributor families.

## Compatibility

Preserve current effective ordering for existing no-library runs during the migration and retain `--plugin-jar` as an adapter input.
