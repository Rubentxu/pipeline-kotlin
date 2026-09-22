# WU-RP-031 E3 Receipt — DurableTypedInputPreparation extraction

Base: `88651cc6` (E2). Date: 2026-09-22.

## What

Third extraction slice: family classification + typed admission
(`StructuralFamilyResolver.classify` → `LegacyExecutionBoundary.prepare` /
`RegistryExecutionPreparation.prepare`, the CDE.2-c/d + CDE.3-b3/e4.3 block
inside `dispatch`/Execute) moved verbatim into new
`DurableTypedInputPreparation.kt` (internal). Closed outcome ADT
`TypedPreparation { Ready(PreparedExecution); Rejected(reason) }`; the
coordinator interprets each case. Selection remains by CLOSED structural
family, never by concrete Step name. Coordinator 1829 → 1811 lines
(E1: 2346 → 1943 → E2: 1943 net with stubs → now 1811). Public constructor
signature unchanged.

## Behavior-equivalence notes

- Same fail-closed semantics: a `Rejected` admission is a terminal SCHEMA
  rejection (`rejectSchema`), common executor never runs.
- `EngineInvariantViolation` for registry-family steps without a bound
  registry preserved verbatim.

## Verification (this working state, fresh runs)

| Check | Result |
| --- | --- |
| `:pipeline-application:compileKotlin` | BUILD SUCCESSFUL |
| durable + arch/fitness suites | 547/547 GREEN (XML-verified) |
| kill/resume/UAT-local | 148/148 GREEN |

## Validation ladder

L0 compile → L2 durable package + L3 arch/fitness → L3 kill/resume UAT smoke.
Full `check` deferred to round gate.

## Next

E4 (StepExecutor extraction) closes WU-RP-031; then WU-RP-032 (DSL semantics).
