# UAT — DSL and Execution Hardening

Status: PROPOSED

## DSL-001 — declarative purity

Architecture fitness proves builders do not start processes, touch journal/store adapters or read ambient mutable runtime state for a returned runtime value.

## DSL-002 — no fake values

`pwd` and `isUnix` fixtures either execute in ScriptRuntimeScope and return real values or fail admission. Deliberately injected fake fallback must make the fitness test fail.

## DSL-003 — Body policy exhaustiveness

Adding a new BodyExecutionPolicy case breaks exhaustive compile/tests until engine support/admission is decided. No `else` hides it.

## DSL-004 — external Single body plugin

Independent plugin JAR contributes a block Step, receives BodyInvoker capability and executes body without Step-specific edits to compiler/coordinator/core registry.

## DSL-005 — external Named bodies plugin

If named bodies are claimed stable for Gate-1 plugin SDK, independent plugin drives branches through BranchInvoker. Otherwise this test gates the later plugin-SDK body capability release.

## DSL-006 — retry restart

Retry attempts preserve deterministic body/attempt identity across process restart; completed child effects are not repeated incorrectly.

## DSL-007 — parallel composability

A stage may contain sibling steps around `parallel`; compiler lowers to composable structure and execution emits typed branch events/outcomes.

## DSL-008 — unsupported when/post

If not implemented for Gate-1, fixture is rejected before effects. It must not append body unconditionally or silently omit post behavior.

## DSL-009 — coordinator fitness

Static/structural test rejects concrete `PluginStepId("core.*")`/Step-key semantic switching inside CanonicalDurableRunCoordinator for migrated supported semantics.
