# ADR-0021: Adapter isolation — scripting-api as the versioned port boundary

- **Status:** Accepted
- **Date:** 2026-08-23
- **Decision owners:** Pipeline Kotlin maintainers

## Context

The V2 scripting stack exposes `kotlin.script.experimental` types inside
`:pipeline-scripting-kotlin24` (the adapter) while keeping `:pipeline-scripting-api`
free of experimental imports. This is a deliberate containment boundary (F-ARCH-003).

During M1-R2, we introduced a versioned `CacheKey` type and moved it into
`:pipeline-scripting-api`. The question arose: should `CacheKey` live in the
scripting-api port, or duplicated per adapter?

## Decision

`CacheKey` lives in `:pipeline-scripting-api` as a versioned data class
(`CacheKey(value: String, version: String)`) with companion constants `V1`
and `V2`. Future adapters (KTA, BTA) MUST emit `CacheKey` via the same
contract, not duplicate it. The adapter (:pipeline-scripting-kotlin24) owns
the experimental scripting types and maps them to the port contract.

`ScriptCompilationResult.cacheKey` changed from `String` to `CacheKey` in
M1-R2, which is a source-level break for existing callers. The break was
applied atomically with updated UatComp tests.

### Cache-key versioning contract

`CacheKey.V1 = "v1"` is the active algorithm (SHA-256 over
`scriptText | sortedClasspath | kotlinVersion | hostVersion`).

`CacheKey.V2 = "v2"` is reserved. `CacheKey.v2.compute(...)` throws
`UnsupportedOperationException("v2 reserved — algorithm not introduced in M1-R2")`.
The v2 algorithm will be introduced in a future milestone with a migration
plan.

The KDoc for `sha256Hex` is verbatim: "joins parts with `|`, then SHA-256s
UTF-8 bytes".

## Consequences

- All adapters (Kotlin 2.4, KTA, BTA) share the same `CacheKey` contract.
- Port/adapter separation is preserved: `pipeline-scripting-api` has no
  `kotlin.script.experimental` imports (F-ARCH-003).
- `CacheKey` versioning is explicit in the type system.
- `ScriptCompilationResult.cacheKey` type change requires callers to update
  (atomic with M1-R2 commit).
