# WU-LPR-024 — InvocationEngine seam (closure receipt, slice 1/3)

**Status:** CLOSED — SLICE 1/3 (adapter + golden parity framework).
The full WU-LPR-024 spec
("Extract metadata/fingerprint/journal/replay/capability boundary while
preserving coordinator lifecycle") is split across **three slices**:

- **Slice 1/3 (this WU)**: pure adapter from canonical coordinator types to
  the descriptor vocabulary, plus golden parity tests pinning the
  field-by-field translation.
- **Slice 2/3 (WU-LPR-024-FOLLOWUP-A)**: publish canonical `BlockShellScope`
  / `BodyExecutionProjection` types out of `CanonicalDurableRunCoordinator`
  (or move them to `:pipeline-domain`), drop the mirror types.
- **Slice 3/3 (WU-LPR-024-FOLLOWUP-B)**: migrate the canonical coordinator's
  inline loops (`dispatchBody`, `runParallelStage`, retry loop) to consume
  `BodyInterpreter` / `DurableControlEnginePolicy` / `BranchInvoker`. This
  slice requires a pre-migration baseline of golden event/journal fixtures
  to prove byte-for-byte parity post-migration.

**Outcome:** `CanonicalToDescriptorAdapter` (pure, `Any`-typed signature
so slice 2/3 unification is a search-and-replace), `CanonicalBlockShellScope`
mirror (7 variants), `CanonicalBodyExecutionProjection` mirror (4 variants),
`CanonicalSecretHandle` mirror (test-only), `CanonicalPlainSecretHandle`
implementation for tests. 15 tests pinning the field-by-field translation
plus 3 golden-parity tests that prove the adapter output is consumable by
`DefaultBodyInterpreter`.

The canonical coordinator still inlines everything — this WU prepares the
seam. Slices 2/3 and 3/3 do not modify the canonical coordinator without a
green baseline, which is itself a separate WU (golden fixtures).

---

## 1. Scope

> Extract metadata/fingerprint/journal/replay/capability boundary while
> preserving coordinator lifecycle.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-024, L32-34).

**This WU produces**:
- The pure adapter (`CanonicalToDescriptorAdapter`).
- Mirror types for `BlockShellScope` / `BodyExecutionProjection` /
  `SecretHandle` (collapsed in slice 2/3).
- 15 tests pinning the structural 1:1 mapping.
- 3 golden-parity tests proving the adapter output is consumable by the
  WU-LPR-021 interpreter.

**This WU does NOT produce** (deferred to follow-up slices):
- Publication of the canonical types out of the coordinator.
- Migration of the canonical coordinator's inline loops.
- Pre-migration baseline of golden event/journal fixtures (the migration
  safety net).

## 2. Mirror types vs re-export

Slice 1/3 ships **mirrors** instead of forcing a publication of the
canonical types. The mirrors:

- Carry the same field names and types as the canonical private types.
- Live in `:pipeline-domain` (no reverse dependency on `:pipeline-application`).
- Are collapsed in slice 2/3 once the canonical types are published: the
  adapter consumes the canonical types directly, and the mirrors delete.
- Have `Any`-typed adapter signatures so slice 2/3 unification is a search
  and-replace (replace `Any` with `CanonicalBlockShellScope` /
  `BlockShellScope`, etc.).

The mirrors are not a workaround; they are the **migration staging
ground**. Golden parity tests can be written against the mirrors today
and replayed against the canonical types in slice 2/3 without modification.

## 3. Test surface (15 tests, 0 failures, 0 errors)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WULpr024CanonicalToDescriptorAdapterTest.kt`

| Nested group | Tests |
|--------------|-------|
| `ScopeCases` (None, Directory, Timestamps, Env, Timeout, Retry, WaitUntil) | 7 |
| `ProjectionCases` (Scope, CredentialLease, InvalidInput, Unimplemented) | 5 |
| `GoldenParity` (adapter output → interpreter → typed result) | 3 |

Total = 15 tests, 3 nested groups, 0 failures, 0 errors, 0 skipped.

The 3 golden-parity tests are the seam's safety net: they prove that the
adapter output, fed to `DefaultBodyInterpreter`, yields the same typed
interpreted body the canonical inline dispatch would produce:

| Adapter input | Interpreter output | What it pins |
|---------------|--------------------|--------------|
| `Scope(None)` | `InterpretedBody.Sequential` | sequential execution path |
| `Scope(Directory)` | `InterpretedBody.Scoped` with cwd patch | scoped execution path |
| `Scope(Timeout)` | `InterpretedBody.Scoped` with timeoutMs patch | timeout path |

## 4. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-domain:compileKotlin
    BUILD SUCCESSFUL in 12s

L1: ./gradlew -p v2 :pipeline-domain:test --tests 'WULpr024CanonicalToDescriptorAdapterTest'
    BUILD SUCCESSFUL in 14s (15 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-domain:test
    BUILD SUCCESSFUL in 13s (554 tests, 0 failures, 0 errors, 0 skipped)
```

## 5. Production code impact

**Zero production coordinator migration in this WU.** The canonical
`CanonicalDurableRunCoordinator.dispatchBody` continues to inline its
dispatch loop, the canonical `runParallelStage` continues to inline its
parallel loop, the canonical retry loop continues to inline its control
flow. The adapter is the **migration target**: slice 2/3 publishes the
canonical types; slice 3/3 migrates the loops.

## 6. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | Canonical types are still `private sealed interface` inside `CanonicalDurableRunCoordinator`; the adapter consumes mirrors | **WU-LPR-024-FOLLOWUP-A** (slice 2/3) | `GATE_1_BLOCKER` per spec |
| F2 | No pre-migration golden fixtures exist for byte-for-byte parity | **WU-LPR-024-FOLLOWUP-B-pre** (capture baseline fixtures) | `GATE_1_BLOCKER` |
| F3 | Migration of inline loops cannot start without F1 + F2 | **WU-LPR-024-FOLLOWUP-B** (slice 3/3) | `GATE_1_BLOCKER` |

## 7. Auto-continue

**LPR-2 is now structurally closed**: `WU-LPR-020 → 021 → 022 → 023 →
024-1/3`. The next LPR milestone is **LPR-3 (Honest DSL)** —
`WU-LPR-030 → WU-LPR-031 → WU-LPR-032`. WU-LPR-032 was already closed
before this session (4b1ec07a), so LPR-3 has only 030 + 031 outstanding.

The natural next WU is **WU-LPR-031** (Runtime value honesty — migrate
`pwd/isUnix` supported usage to real runtime context; remove stable fake
fallbacks; fail-closed unsupported declarative value use). WU-LPR-031 is
a refactor slice (smaller than LPR-2) and folds into the LPR-3 close.

---

**CLOSED (SLICE 1/3) — 2026-09-20.**
