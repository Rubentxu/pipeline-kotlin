---
type: adr
id: ADR-0063
title: "M1 — LF-0105 Effect/ReplayPolicy canonical authority + sdk:api quarantine"
status: proposed
cycle: "local-first-evolution/M1 (LF-0105)"
date: 2026-09-02
deciders: "sddk-design (orchestrator)"
supersedes: null
superseded_by: null
related:
  - docs/pipelinek-local-first-evolution/docs/v2/03-specifications/CANONICAL_CONTRACTS_SPEC.md §Step metadata
  - docs/pipelinek-local-first-evolution/docs/v2/06-quality/CODE_CHANGE_MAP.md §"Effect/ReplayPolicy duplicates"
  - ADR-0056  # single semantic authority
---

# ADR-0063 — LF-0105 Effect/ReplayPolicy canonical authority + sdk:api quarantine

> **Cycle:** local-first-evolution/M1 (LF-0105)
> **Phase:** apply (slice conservador sobre LF-0101..LF-0108)
> **Authority:** `docs/pipelinek-local-first-evolution/docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md` §M1 +
>   `CANONICAL_CONTRACTS_SPEC.md` §Step metadata + `CODE_CHANGE_MAP.md` §"Effect/ReplayPolicy duplicates"
> **Base SHA:** `61100c2` (LF-0104 head on `feat/lf-m1-typed-outcomes`, PR #7)

## Context

The V2 domain already declares typed enums for the two side-effect classification
contracts that `CANONICAL_CONTRACTS_SPEC.md §Step metadata` demands:

```kotlin
// v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Effect.kt
enum class Effect {
    READ_ONLY,
    EXECUTES_SUBPROCESS,
    ABORTS_PIPELINE,
    WRITES_WORKSPACE,
}

// v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt
enum class ReplayPolicy {
    MEMOIZED,
    RERUN,
    NEVER,
}
```

However, `CODE_CHANGE_MAP.md:45` records an outstanding duplication:

> | `Effect`/`ReplayPolicy` duplicates | consolidar | pipeline-contracts |

The duplicates live in `:pipeline-step-sdk:api`:

```kotlin
// v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/Effect.kt
enum class Effect { READ_ONLY, EXECUTES_SUBPROCESS, ABORTS_PIPELINE, WRITES_WORKSPACE }

// v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/ReplayPolicy.kt
enum class ReplayPolicy { MEMOIZED, RERUN, NEVER }
```

The KDoc on each duplicate openly admits the duplication and the planned
consolidation:

> "Duplicated from `[dev.rubentxu.pipeline.v2.sdk.*]` to avoid circular dependency
> between `:pipeline-domain` and `:pipeline-step-sdk:api`. M3-R2 should reconcile
> these into a single source of truth."

Meanwhile, `StepDescriptor` (also in domain) still uses raw strings for both:

```kotlin
// v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt
data class StepDescriptor(
    ...
    val effects: List<String> = emptyList(),        // ← raw string, should be List<Effect>
    val replayPolicy: String = "MEMOIZED",          // ← raw string, should be ReplayPolicy
    ...
)
```

This violates the LF-0105 contract ("typed Effect/ReplayPolicy") and breaks
`CANONICAL_CONTRACTS_SPEC.md §Step metadata`'s "una única fuente produce
name/effect/replay/execution location/capabilities" rule.

Downstream impact (currently using the sdk:api duplicate, not the domain canonical):
- `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/EffectReplayPolicy.kt`
  imports `dev.rubentxu.pipeline.v2.sdk.{Effect, ReplayPolicy}`.
- `v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/StepExecutors.kt`
  constructs `effects = [Effect.READ_ONLY]` using the sdk:api enum.
- `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/Step.kt`
  and `StepDescriptor.kt` reference the sdk:api types.

## Decision

### D1 — `pipeline-domain` is the canonical authority

`dev.rubentxu.pipeline.v2.domain.durable.Effect` and
`dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy` are the **single source of
truth** for the contracts. This is consistent with the existing rule that
`:pipeline-domain` must be framework-free (F-ARCH-001) and is the module that
every other module can depend on without violating the inward-only constraint
(F-ARCH-002).

### D2 — sdk:api duplicates are quarantined (NOT removed in this slice)

The duplicates in `:pipeline-step-sdk:api` (`dev.rubentxu.pipeline.v2.sdk.Effect`
and `dev.rubentxu.pipeline.v2.sdk.ReplayPolicy`) remain in place. They are
quarantined with a precise allowlist (one path per enum, sdk:api only) and
explicitly tracked by `FArchM1CanonicalEffectsTest` as the **legacy quarantine
copy**. The original M3-R2 commitment in the KDoc is preserved.

Removing the duplicates in this slice would force a cross-module migration of
four downstream files (`EffectReplayPolicy.kt`, `StepExecutors.kt`,
`pipeline-step-sdk/api/.../Step.kt`, `pipeline-step-sdk/api/.../StepDescriptor.kt`)
that belong to M2/M3 (LF-0205 redirect CLI, LF-0307 migrate sh, LF-0308 delete
ProcessExecutor/fallbacks) when the runtime consolidation lands. That is **out
of scope** for the LF-0105 conservative slice.

### D3 — `StepDescriptor.effects` / `StepDescriptor.replayPolicy` migration is deferred to M2

The raw-string fields `effects: List<String>` and `replayPolicy: String = "MEMOIZED"`
on `StepDescriptor` (domain) stay as-is. Migrating them now would force every
fixture (`HelloPipelineFixture`, `StepDescriptorGenerator` in
`:pipeline-step-sdk:processor`, all KSP-generated descriptors, all UAT tests
that build descriptors with `effects = listOf("READ_ONLY")`) to switch to typed
constructors in one slice. That work belongs to the M2 slices that touch
`StepDescriptor` end-to-end (LF-0205 redirect CLI, LF-0307 migrate sh).

The fitness test does **not** pin "no raw strings in StepDescriptor" yet — that
pin will land with the M2 migration, so each replacement has a clean regression
net.

### D4 — Fitness gate `FArchM1CanonicalEffectsTest`

Mirrors the existing `FArchM1CanonicalIdsTest` / `FArchM1CanonicalClockTest`
/ `FArchM1CanonicalOutcomesTest` pattern:

1. `pipeline-domain` owns the canonical contracts — `Effect.kt` declares
   `enum class Effect` with `READ_ONLY`, `EXECUTES_SUBPROCESS`, `ABORTS_PIPELINE`,
   `WRITES_WORKSPACE`; `ReplayPolicy.kt` declares `enum class ReplayPolicy` with
   `MEMOIZED`, `RERUN`, `NEVER`.
2. V2 enum declarations match the canonical + legacy-quarantine allowlist exactly —
   the `Effect` enum is declared in `pipeline-domain` (canonical) and
   `pipeline-step-sdk/api` (legacy quarantine); the `ReplayPolicy` enum has the
   same dual declaration. No other module may declare them.
3. The two duplicate enums in sdk:api are flagged as quarantine by an explicit
   allowlist entry annotated `// M1 legacy quarantine: remove when M3-R2
   consolidates`.

### D5 — Consolidation plan documented for M3-R2 (carry-forward)

When M3 lands (LF-0308 delete ProcessExecutor/fallbacks, M3-R2 commitment in the
duplicate KDoc), the consolidation is:
1. Keep `dev.rubentxu.pipeline.v2.domain.durable.{Effect, ReplayPolicy}` only.
2. Replace every `dev.rubentxu.pipeline.v2.sdk.{Effect, ReplayPolicy}` import
   with the domain equivalent (in `EffectReplayPolicy.kt`,
   `StepExecutors.kt`, `Step.kt`, `StepDescriptor.kt`, `StepDescriptorSchemaTest.kt`).
3. Delete the sdk:api duplicates.
4. Update `FArchM1CanonicalEffectsTest` allowlist to remove the sdk:api entries.

This consolidation lives behind the M3 gate, not in LF-0105.

## Threat Model

| Surface | Description | Mitigation |
|---|---|---|
| **Drift between canonical and quarantine enum** | Someone adds a new `Effect` (e.g. `READS_NETWORK`) to domain but forgets the sdk:api quarantine copy, so the two diverge silently. | Fitness test enumerates the allowed paths (domain + sdk:api) AND scans the body of each enum for its declared values; any divergence is caught by the allowlist check. M3-R2 closes the gap. |
| **New canonical enum introduced bypassing domain** | A future contributor adds a third `Effect` declaration in another module (sdk:processor, application). | Fitness test pins "no Effect declarations outside the allowlist" — same pattern as FArchM1CanonicalIdsTest §"V2 id type declarations match the canonical and legacy allowlist exactly". |
| **StepDescriptor raw strings grow** | Someone adds more `effects: List<String>` call sites before M2 migration, deepening the migration debt. | Out of scope for LF-0105. M2 migration introduces the typed overload alongside the raw string field (precedent: `id` + `type` + `configRef` retained for backward compat in `StepDescriptor` widening). |
| **sdk:api module gets removed from the build** | A future refactor drops `pipeline-step-sdk:api`; the quarantine enum goes with it. | The fitness test will fail (sdk:api not in allowlist, but quarantine enum not declared). The error message points to the consolidation plan in D5. |

## Consequences

### Positive

- **Single canonical authority for Effect and ReplayPolicy** is declared in
  `:pipeline-domain`, the framework-free inward-most module. Every other module
  can depend on it without violating F-ARCH-002.
- **Quarantine is explicit and testable**: the dual declaration is visible in
  the fitness test's allowlist with a comment that links to this ADR. Removing
  the quarantine is a one-line edit when M3-R2 closes.
- **Zero behavioral change**: nothing in the runtime is touched. Existing
  consumers of `sdk.{Effect, ReplayPolicy}` keep working. Existing consumers of
  `domain.durable.{Effect, ReplayPolicy}` (Fingerprint, DurableOperation) keep
  working.
- **Reversible**: the entire LF-0105 slice is a single commit with no
  production-code changes. Removing it is a `git revert`.
- **Documents the consolidation intent**: ADR-0063 §D5 captures the M3-R2 plan
  that was previously only present as a comment in two file headers, making
  it discoverable from the architecture graph.

### Negative

- **Duplication persists**: until M3-R2, two enums with identical members
  exist in two packages. Callers can pick either. The fitness test does not
  prevent a third copy from appearing — it only enforces the allowlist, and
  any addition to the allowlist requires an ADR update.
- **No StepDescriptor migration**: the raw strings in `StepDescriptor.effects`
  and `StepDescriptor.replayPolicy` remain. The LF-0105 pin does not cover
  them; that pin lands with M2 (LF-0205 or LF-0307).
- **No unit tests added**: the enum values are already exercised by
  `FingerprintTest`, `DurableOperationTest`, and `EffectReplayPolicyContractTest`.
  Adding redundant enum-shape tests would inflate the suite without raising
  signal.

## Future

### Q-1 — Consolidation (M3-R2 / LF-0308)

`dev.rubentxu.pipeline.v2.sdk.{Effect, ReplayPolicy}` are deleted; all
imports are migrated to `dev.rubentxu.pipeline.v2.domain.durable.{Effect,
ReplayPolicy}`. The `FArchM1CanonicalEffectsTest` allowlist drops the sdk:api
entries. Tracked in `CODE_CHANGE_MAP.md §"Effect/ReplayPolicy duplicates"`.

### Q-2 — StepDescriptor typed migration (M2 / LF-0205 or LF-0307)

`StepDescriptor.effects: List<Effect>` and `StepDescriptor.replayPolicy:
ReplayPolicy` (typed). The raw-string fields are deprecated but kept
(defaulted to `emptyList()` / `"MEMOIZED"`) for backward compat with
`HelloPipelineFixture` and KSP-generated descriptors until the migration
ripples through the fixture corpus.

### Q-3 — Fitness pin widening (M2 follow-on)

Once M2 starts migrating `StepDescriptor`, `FArchM1CanonicalEffectsTest`
gains a fourth test that asserts "no raw `effects`/`replayPolicy` strings
inside application/runtime, allowlist exact per path" — same pattern as the
forthcoming raw-outcome-strings pin (see LF-0104 §"Out of scope").

---

## Changelog

- 2026-09-02T13:30:00Z | created | status=proposed | valid_from=2026-09-02 | valid_to=∞

---

## Appendix: Allowlist shape (informative)

```kotlin
private val allowedEffectDeclarations = mapOf(
    "Effect" to listOf(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/Effect.kt",
        // M1 legacy quarantine: remove when M3-R2 consolidates (ADR-0063 §D5).
        "pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/Effect.kt",
    ).sorted(),
)

private val allowedReplayPolicyDeclarations = mapOf(
    "ReplayPolicy" to listOf(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt",
        // M1 legacy quarantine: remove when M3-R2 consolidates (ADR-0063 §D5).
        "pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/ReplayPolicy.kt",
    ).sorted(),
)
```

The two `sorted()` lists are the exact paths the fitness test expects; any
deviation (new enum declaration elsewhere, missing enum declaration in domain,
sdk:api duplicate removed early) fails the test with a message that points to
this ADR.
