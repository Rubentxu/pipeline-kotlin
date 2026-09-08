# Legacy Burn-down Policy (LFC-2)

Status: ADOPTED. Applies from `B1.2c3` (core.echo) onward. Owner: LFC-2 change.

## Principle

A Step is NOT migrated while its legacy implementation remains reachable. The deletion of the legacy
execution path is part of the Definition of Done of each migrated Step, not a separate "cleanup" at
the end. Keeping two execution worlds alive indefinitely duplicates authorities, raises connascence,
makes tests ambiguous, and lets new work silently fall back to the old path.

Per-Step lifecycle (state machine):

```text
LEGACY
  ↓
DUAL_AVAILABLE
  ↓
REGISTRY_PRIMARY        (production routes through the registry family)
  ↓
LEGACY_UNREACHABLE      (legacy execution no longer reachable from the canonical path)
  ↓
LEGACY_REMOVED          (decoder/dispatcher/catalogue entries deleted; type shrinking)
  ↓
CERTIFIED
```

**CERTIFIED requires LEGACY_REMOVED**, unless a durable compatibility path is explicitly approved.

## Execution duplication vs data compatibility

- **Legacy execution path**: remove aggressively when a Step migrates.
- **Legacy compatibility** (reading/replaying old persisted data through a compatibility decoder into the
  new canonical form): MAY remain when needed, explicitly labelled.

Rule: *keep data compatibility when needed; remove execution duplication.*

## Direction of dependencies

```text
new architecture
      ↑
legacy adapter
```

Never:

```text
new architecture
      ↓
legacy internals
```

Do NOT build `LegacyCompatibilityManager`/`LegacyExecutionFramework`/etc. to "organize" legacy. Legacy
must have LESS architecture over time, not more. At most a clearly delimited `legacy` surface with
deprecation and fitness that forbids new dependencies into it.

## B1.2c3 close criterion

B1.2c3 is NOT closed when core.echo runs by Registry. It closes when core.echo runs by Registry AND its
legacy execution path has been removed:

```text
ANTES  core.echo { legacy decoder, legacy metadata, legacy dispatcher, registry definition }
DESPUÉS core.echo { registry definition -> durable spine -> handler }
```

Removed: `CanonicalCoreStepCommand.Echo`, legacy decode of Echo, legacy dispatcher Echo case, legacy
metadata Echo, legacy `ALL_PLUGIN_IDS` entry, and legacy tests that only exercise that path.

## Fitness (prevents reintroduction)

For a certified Step:

```kotlin
certifiedSteps.intersect(legacyExecutableSteps).isEmpty()
```

Concretely for core.echo after migration:

```text
core.echo ∉ LegacyStepCatalogue
core.echo ∉ CanonicalCoreStepDecoder (legacy execution)
core.echo ∉ CanonicalNodeDispatcher (legacy execution)
core.echo ∈ StepRegistry
```

## Component burn-down (owners)

| Legacy component | Destination |
| --- | --- |
| `CanonicalCoreStepCommand` | Shrink Step-by-step; vanish or remain only as a compatibility model |
| `CanonicalCoreStepDecoder` | Shrink Step-by-step |
| `CanonicalNodeDispatcher` concrete cases | Shrink Step-by-step |
| `ALL_PLUGIN_IDS` | Rename/scope to the real legacy catalogue if that is its meaning; finally eliminate |
| legacy Step metadata table | Shrink Step-by-step |
| KSP known-step semantics | Remove when the registry/KSP generic is authority |
| legacy block dispatchers | Removed later with `BodyInvoker` |
| legacy runtime-value hacks | Removed with scripted runtime model |

## Burn-down backlog (roadmap view)

| LB | Step | Status |
| --- | --- | --- |
| LB-01 | core.echo | REGISTRY_PRIMARY (legacy reachable only dual) |
| LB-02 | core.sh | PENDING |
| LB-03 | ... (each legacy Step) | PENDING |

Metric (descending curve): `Legacy execution cases remaining` / `Registry execution cases` /
`Certified Steps`. Progress is measured by the curve decreasing, not by new-feature count.
