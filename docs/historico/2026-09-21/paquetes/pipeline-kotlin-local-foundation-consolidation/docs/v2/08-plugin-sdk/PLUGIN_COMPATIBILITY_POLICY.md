# Plugin compatibility policy

## Version domains

Track separately:

- CLI/runtime product version;
- DSL API version;
- Plugin API version;
- plugin's own semantic version;
- canonical IR schema version where persisted/exchanged.

## 1.x promise

After plugin API v1 is declared stable, compatible 1.x runtime releases SHOULD load plugins built for plugin API 1 unless a documented security incompatibility prevents it.

## Breaking changes

Breaking Plugin API changes require a major Plugin API version and migration documentation. They do not necessarily require the CLI product major version to match numerically, but release notes must make compatibility explicit.

## Descriptor evolution

Unknown optional fields are tolerated according to schema rules; required semantic changes require schema/API versioning.

## Deprecation

Public DSL/plugin APIs should receive at least one documented minor-release deprecation window when feasible. Internal legacy paths remain subject to the shorter consolidation removal budget.
