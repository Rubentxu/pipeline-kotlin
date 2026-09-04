# Migration from the current implementation

## Phase 1 — characterize

Before changing each reviewed hotspot, capture current required behavior with tests. Do not preserve known bugs as compatibility unless explicitly accepted.

## Model migration

1. Add canonical IR alongside existing models.
2. Compile DSL directly into canonical IR for a small fixture.
3. Migrate validator/planner.
4. Migrate runtime vertical slice.
5. Remove registry/mapper bridge and old model.
6. Add uniqueness fitness test.

## Execution migration

1. Make coordinator/dispatcher suspend.
2. Implement typed handler registry.
3. Move shell first, then echo/file/SCM/artifacts, then wrappers.
4. Make retry/parallel call dispatcher recursively rather than walker-specific code.
5. Remove walker/delegate.

## SDK migration

1. Keep current annotation syntax temporarily if source-compatible.
2. Replace hardcoded KSP switch with annotation/signature-derived metadata.
3. Generate parameter schemas.
4. Move executors out of SDK runtime implementation that constructs local adapters.
5. Add external reference plugin.

## Global state cleanup

Replace `System.user.dir`, `System.getenv`, temp hardcoding and direct clock construction with context/capability values before enabling broad parallelism guarantees.
