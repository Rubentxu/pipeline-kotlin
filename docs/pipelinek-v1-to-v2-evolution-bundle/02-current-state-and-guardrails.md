# Current state and guardrails

## 1. Baseline inspected

This proposal was grounded on `main @ 569a088cc76f1a826c619577eefa1475404c3fb4`.

Important current code seams include:

- `v2/pipeline-domain/.../step/StepRegistry.kt`
- `v2/pipeline-domain/.../StepDescriptor.kt`
- `v2/pipeline-domain/.../step/PluginManifest.kt`
- `v2/pipeline-domain/.../step/PluginReleaseRef.kt`
- `v2/pipeline-domain/.../step/StepProviderMetadata.kt`
- `v2/pipeline-domain/.../step/TrustMetadata.kt`
- `v2/pipeline-application/.../ExternalStepPluginDiscovery.kt`
- `v2/pipeline-application/.../BundledPluginClasspathPlan.kt`
- `v2/pipeline-application/.../MainRuntimeSupport.kt`
- `v2/pipeline-application/.../durable/RegistryExecutionPreparation.kt`
- `v2/pipeline-application/.../durable/RegistryExecutionBoundary.kt`
- `v2/pipeline-scripting-api/.../ScriptDefinition.kt`
- `v2/pipeline-scripting-api/.../CacheKey.kt`
- `v2/pipeline-scripting-kotlin24/.../Kotlin24ScriptingHost.kt`
- `v2/pipeline-step-sdk/api/.../LspMetadata.kt`
- `v2/pipeline-application/.../CliParser.kt`

## 2. Existing decisions this programme must preserve

### 2.1 Step execution

A new Step uses the open registry path and may not require a Step-key-specific coordinator branch.

### 2.2 Capability admission

Required capabilities are declared by the Step contract and fail closed before handler effects. Tooling may report that decision but cannot bypass or reinterpret it.

### 2.3 Plugin identity

Logical plugin identity and immutable plugin release identity are separate. Version and digest belong to the release, not the logical plugin.

### 2.4 Delivery is metadata, not trust

`CORE`, `OFFICIAL_PLUGIN`, `EXTERNAL_REFERENCE`, etc. must not become implicit allow/deny decisions.

### 2.5 Honest trust states

`TrustMetadata.Unverified` remains the only trust state until a concrete verifier produces evidence. No `Trusted` or `Signed` enum is introduced merely because a manifest says so.

### 2.6 Compilation/runtime classpath parity

The compiler and runtime discovery must see coherent plugin artifact identities. Any library support must extend this law rather than create another classpath composer.

### 2.7 Output vs events

Console/output bytes and semantic events remain separate planes with separate cursors and ownership.

### 2.8 Local-first workspace safety

Attached user workspaces and managed scratch workspaces retain typed ownership. New library/plugin resolution must never use the project checkout as a default cache or state directory.

### 2.9 No remote/controller expansion here

Current roadmap assigns controller/worker and Jenkins integration responsibilities outside `pipeline-kotlin`. This programme does not reopen that boundary.

## 3. Explicit anti-goals

The implementation must not introduce:

- `ServiceLocator`-style global context;
- `Map<String, Any?>` public contracts;
- hard-coded Step lists in CLI, LSP or MCP;
- `when(stepKey)` dispatch for plugin semantics;
- plugin-name-specific coordinator branches;
- a second journal or output authority for inline CLI operations;
- a second capability admission algorithm for `--plan`;
- `println`-based hidden diagnostics in domain code;
- a library that registers Steps through `ServiceLoader`;
- a plugin that is trusted solely because it is bundled;
- repo-local `.pipelinek` state/config by default;
- fake disk compilation cache entries;
- build-time Git clones as normal Shared Library resolution.

## 4. Compatibility policy

Changes are additive unless one of these is true:

1. the existing surface is semantically dishonest;
2. keeping it would create two authorities;
3. it prevents deterministic security/reproducibility;
4. a measured improvement justifies a controlled break with migration and compatibility evidence.

Every breaking change requires:

- current consumer inventory;
- compatibility fixture;
- migration note;
- explicit ADR;
- installed-distribution UAT;
- release note.

## 5. Compiler/admission authority reconciliation

The canonical `SCRIPTING_COMPILER_SPEC.md` already specifies compile/evaluate ports, explicit classpath, content identity and a strategic BTA/fallback boundary. Current `ScriptingHost.compile` still compiles/evaluates together. Implement the canonical contract incrementally; do not add a third public engine or copy comments as evidence of delivered caching.

S6 owns `PipelineKPluginManifest` across Steps/directives/events/reactors/capabilities and frozen registries. M3 reuses that schema/composition after its accepted contract is recovered from SDDK. It does not maintain a second plugin manifest registry. Shared Libraries remain a distinct authoring kind.

At the inspected SHA, the host key uses canonical paths and a manual revision; the reviewed host/caller path does not expose persistent compiled-artifact lookup/write. These observed gaps are distinct from proposed ports, not claims about the latest remote HEAD. Re-inventory the exact new SHA before starting work.

Classloader scope is not a security sandbox. The typed library/contributor distinction is enforceable structurally, but absence of arbitrary Kotlin I/O requires an actual execution restriction if promised. Never infer it from a manifest or compilation-cache hit.
