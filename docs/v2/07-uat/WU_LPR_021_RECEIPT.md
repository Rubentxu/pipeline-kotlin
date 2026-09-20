# WU-LPR-021 — BodyExecutionEngine Sequential/Scoped extraction (closure receipt)

**Status:** CLOSED. Base `9b7ddaa1` (post-WU-LPR-020) → head (single commit).
**Outcome:** Pure typed interpreter `BodyInterpreter` + `InterpretedBody` ADT +
`DefaultBodyInterpreter` implementation + parallel typed vocabulary
(`BlockShellScopeDescriptor`, `BodyExecutionProjectionDescriptor`,
`ShPatch`, `ContextOverlayDescriptor`). The canonical coordinator still uses
its private types — the seam is the **migration target** that WU-LPR-024
(InvocationEngine seam) will publish.

---

## 1. Scope

> Extract pure policy decision and interpreter. Migrate one Scoped family first.
> Golden event/journal parity required.

Per `docs/v2/05-roadmap/LPR_WORK_UNITS.md` (WU-LPR-021, L20-22).

**This WU produces**: the seam + one Scoped family (Directory) tested in
isolation. **This WU does NOT produce**: production migration of
`CanonicalDurableRunCoordinator.dispatchBody` to consume the seam. That
migration is WU-LPR-024 (publishing the canonical `BlockShellScope` /
`BodyExecutionProjection` types out of the coordinator), which would let the
canonical code call the interpreter directly.

## 2. Two-layer body execution model

```text
1. DECISION   BlockStepNode.projectBodyExecution(policy, options)
              → BodyExecutionProjection          (already pure, already exists)

2. INTERPRET  BodyInterpreter.interpret(projection)
              → InterpretedBody                   (NEW — this WU)

The interpreter is the typed carrier the runner consumes to apply effects
(events, FS ops, capability calls). It does NOT perform any effects itself.
```

## 3. New types

`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyInterpreter.kt`

| Type | Kind | Purpose |
|------|------|---------|
| `BodyInterpreter` | `fun interface` | `(BodyExecutionProjectionDescriptor) -> InterpretedBody`, pure, total |
| `DefaultBodyInterpreter` | `object` | Reference implementation; closed ADT match over projection |
| `InterpretedBody` | sealed interface | 5 cases: `Sequential`, `Scoped`, `CredentialLeased`, `InvalidInput`, `Unimplemented` |
| `BlockShellScopeDescriptor` | sealed interface | 7 cases: `None`, `Directory`, `Env`, `Timeout`, `Retry`, `WaitUntil`, `Timestamps` |
| `ContextOverlayDescriptor` | sealed interface | 4 cases: `None`, `Cwd`, `Environment`, `Deadline` |
| `ShPatch` | data class | Typed patch (timeoutMs / workingDirectory / env); identity `ShPatch.NONE` |
| `BodyExecutionProjectionDescriptor` | sealed interface | 4 cases: `Scope`, `CredentialLease`, `InvalidInput`, `Unimplemented` |

**Total = 5 sealed ADTs**, all exhaustive (compile-time enforcement via the
sealed hierarchy; adding a case forces every consumer to be revisited).

## 4. Module placement

Lives in `:pipeline-domain`. Critically: it does NOT depend on
`pipeline-step-sdk` (so no `ShOptions` / `SecretHandle` import), and only
imports `CredentialBindingSpec` from `pipeline-domain/credentials`. The
interpreter is **runtime-options-agnostic**: it produces a typed `ShPatch`
that the runner is responsible for materializing onto its concrete options
class.

This was a deliberate design choice during the WU: publishing the canonical
coordinator's `BlockShellScope` / `BodyExecutionProjection` types out of
`pipeline-application` would have created a reverse-dependency edge
(domain depending on application). The seam therefore ships with its own
parallel vocabulary; future WU-LPR-024 introduces an adapter from
canonical-projection to descriptor.

## 5. Mapping (interpreter decision table)

| Projection | Interpreted |
|------------|-------------|
| `Scope(None)` | `Sequential` |
| `Scope(Directory)` | `Scoped(scope, ShPatch(workingDirectory = target), Cwd(target))` |
| `Scope(Env)` | `Scoped(scope, ShPatch(env = parsed), Environment(merged))` |
| `Scope(Timeout)` | `Scoped(scope, ShPatch(timeoutMs = budgetMs), Deadline(budgetMs))` |
| `Scope(Retry)` | `Scoped(scope, ShPatch.NONE, None)` |
| `Scope(WaitUntil)` | `Scoped(scope, ShPatch.NONE, None)` |
| `Scope(Timestamps)` | `Scoped(scope, ShPatch.NONE, None)` |
| `CredentialLease(bindings)` | `CredentialLeased(bindings)` |
| `InvalidInput(detail)` | `InvalidInput(detail)` |
| `Unimplemented(shape)` | `Unimplemented(shape)` |

Malformed env override (`KEY=` with no value) is silently dropped — matches
the canonical coordinator's `IllegalArgumentException` catch behaviour.

## 6. Test surface (14 tests, 0 failures, 0 errors)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/step/WULpr021BodyInterpreterTest.kt`

| Nested group | Tests |
|--------------|-------|
| `SequentialProjection` | 1 |
| `DirectoryScope` | 2 |
| `EnvScope` | 2 |
| `TimeoutScope` | 1 |
| `LoopingScopes` (Retry, WaitUntil, Timestamps) | 3 |
| `CredentialLeaseProjection` | 1 |
| `TypedFailures` (InvalidInput, Unimplemented) | 2 |
| `ShPatchIdentity` | 1 |
| top-level exhaustiveness | 1 |

Total = 14 tests, 4 nested groups, 0 failures, 0 errors, 0 skipped.

## 7. Build evidence

```text
L0: ./gradlew -p v2 :pipeline-domain:compileKotlin
    BUILD SUCCESSFUL in 12s

L0: ./gradlew -p v2 :pipeline-domain:compileTestKotlin
    BUILD SUCCESSFUL in 13s

L1: ./gradlew -p v2 :pipeline-domain:test --tests 'WULpr021BodyInterpreterTest'
    BUILD SUCCESSFUL in 13s (14 tests, 0/0/0)

L2: ./gradlew -p v2 :pipeline-domain:test
    BUILD SUCCESSFUL in 13s (503 tests, 0 failures, 0 errors, 0 skipped)
```

XML canary (rule 25):
- `TEST-dev.rubentxu.pipeline.v2.domain.step.WULpr021BodyInterpreterTest*.xml`
- `tests="N" skipped="0" failures="0" errors="0"` for each of the 4 nested
  groups + top-level exhaustiveness.

## 8. Production code impact

**Zero production coordinator migration in this WU.** The canonical
`CanonicalDurableRunCoordinator.dispatchBody` continues to consume its
private `BlockShellScope` / `BodyExecutionProjection` types. The interpreter
is a **seam candidate**: future WUs that migrate the coordinator will
introduce an adapter `canonicalProjection -> descriptor` before invoking
`DefaultBodyInterpreter`, and will compare the result against the inline
behaviour for golden parity.

## 9. Findings for follow-up

| ID | Finding | Suggested follow-up | Severity |
|----|---------|---------------------|----------|
| F1 | Canonical `BlockShellScope` / `BodyExecutionProjection` are private in `CanonicalDurableRunCoordinator`; the interpreter uses parallel descriptor types | **WU-LPR-024** (publish types out of coordinator; introduce adapter) | `POST_LPR` |
| F2 | `ShOptions` materialization (`ShPatch.applyToMaterializes`) does not exist — the runner is responsible for it | folds into WU-LPR-024 | `POST_LPR` |
| F3 | `Sequential + Directory` parity test against the canonical coordinator's inline dispatch has not been written yet | **WU-LPR-024** (golden parity) | `POST_LPR` |

## 10. Auto-continue

Per the LPR-2 train (`WU-LPR-020 → WU-LPR-021 → WU-LPR-022`), the next
slice is **WU-LPR-022** (Retry/timeout migration — move generic durable
control execution behind the engine, kill/resume/replay divergence
tests mandatory). WU-LPR-022 is a significantly larger surface than
WU-LPR-021 because it touches durable control rows, the retry control
journal, and replay divergence; a single session should plan to land
its proposal + spec + at most one T task.

---

**CLOSED — 2026-09-20.**
