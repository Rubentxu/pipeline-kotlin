# ADR-0036 — V2 Package Namespace Convention

## Status
Accepted · 2026-08-24

## Context
V2 modules use `com.pipeline.v2.*` package declarations. V1 modules use `dev.rubentxu.pipeline.*`. The V2 group was chosen arbitrarily during early V2 scaffolding and is inconsistent with the V1 project group `dev.rubentxu.pipeline.*` (which uses the maintainer's personal domain as the Maven group identifier). On 2026-08-24, the user directed: "los paquetes son dev.rubentxu.pipeline no com.pipeline.v2, respeta el grupo del codigo original". The namespace must be migrated before M3-R4.2 (parallel frames) modifies ≥60 V2 files; a mixed intermediate state would not compile.

## Decision
ALL V2 source files, build files, KSP descriptors, and docs MUST use the sub-namespace `dev.rubentxu.pipeline.v2.*` (NOT `com.pipeline.v2.*`). This applies to the 15 V2 sub-packages + the `fitness` archtest qualifier:

- `dev.rubentxu.pipeline.v2.application{.durable, .support}`
- `dev.rubentxu.pipeline.v2.architecture`
- `dev.rubentxu.pipeline.v2.domain{.durable}`
- `dev.rubentxu.pipeline.v2.dsl`
- `dev.rubentxu.pipeline.v2.events{.durable}`
- `dev.rubentxu.pipeline.v2.scripting`
- `dev.rubentxu.pipeline.v2.sdk{.processor, .runtime{.durable}}`
- `dev.rubentxu.pipeline.v2.testkit`
- `dev.rubentxu.pipeline.v2.fitness` (archtest suffix)

Future V2 code MUST use this convention from day 1. No exceptions.

## Alternatives Considered

1. **No prefix (merge V2 into V1 group directly)**: `dev.rubentxu.pipeline.application` etc. Rejected — high risk of classpath conflict with V1's `dev.rubentxu.pipeline.{backend,cli,config,lsp-server,steps-system}`. Sub-namespace isolates V2 cleanly.
2. **Keep `com.pipeline.v2.*`**: Rejected — user directive binding; V1 group convention violated.
3. **Different prefix** (e.g., `io.pipeline.v2`): Rejected — abandons maintainer's personal domain anchor used by V1.

## Consequences

**Positive**:
- V1 group `dev.rubentxu.pipeline.*` is preserved (no V1 file references new namespace).
- V1/V2 classpath isolation guaranteed (sub-namespace prevents accidental dependency on V2 internals from V1).
- Consistency with V1 group convention; recognizable as a single project.
- Future M3-R4.2 work happens against correct namespace.

**Negative**:
- One-time mechanical refactor of 143 files (~500 LOC of imports).
- Single-cycle dependency: M3-R4.2 cannot start until this ADR + rename ships.

**Reversibility**: trivial via `git reset --hard <pre-cycle-commit>` or `git revert C2`.

## Evidence and Provenance

- **User directive** (2026-08-24): "los paquetes son dev.rubentxu.pipeline no com.pipeline.v2, respeta el grupo del codigo original"
- **Cycle**: `p-733fb505b5a6bd2d/m3-r4-0-package-namespace-migration` (A-lite)
- **Scope verification**: 127 source files + 11 Gradle + 1 KSP service + 1 KSP processor source + 1 ADR doc (143 total) — see exploration-report.md §2
- **V1 hygiene**: 0 V1 files reference `com.pipeline.v2.*` (verified in exploration §2)
- **Baseline tests**: 197/0/0/0 (M3-R4.1 machine-verified 2026-08-24)
