# ADR-EVO-009 — PipelineK-owned config/cache/state defaults to XDG user directories

**Status:** Proposed

## Decision

Library catalogues, artifact caches, agent runs and discovery caches default to XDG config/cache/state locations. PipelineK does not create persistent project-repository state/config by default.

Explicit future opt-in project config requires its own user-facing contract.
