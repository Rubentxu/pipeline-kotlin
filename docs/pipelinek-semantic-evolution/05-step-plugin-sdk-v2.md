# Step / Directive / Event Plugin SDK v2

## 1. One extension constitution

An external library may contribute:

- atomic Steps;
- block Steps;
- declarative directives;
- event definitions;
- optional reactive observers/reactors;
- typed DSL façades.

It may not introduce a parallel execution engine.

## 2. Plugin manifest

Every distributable plugin publishes a machine-readable manifest:

```kotlin
data class PipelineKPluginManifest(
    val pluginId: PluginId,
    val pluginVersion: SemVer,
    val apiRange: VersionRange,
    val steps: List<StepManifestEntry>,
    val directives: List<DirectiveManifestEntry>,
    val events: List<EventManifestEntry>,
    val reactors: List<ReactorManifestEntry>,
    val capabilities: Set<CapabilityKey>,
)
```

KSP may generate the manifest/registration boilerplate. KSP must not generate semantic dispatch switches.

## 3. Step requirements

Every Step declares:

- stable key;
- input/output codecs;
- effects;
- replay policy;
- recovery policy;
- required capabilities;
- body declaration if any;
- emitted event definitions or delegated emission authority;
- compatibility version.

No `Map<String, Any?>` public contract.

## 4. Block Step requirements

A plugin Block Step must use an existing `BodyExecutionPolicy` shape.

External plugin cannot request a bespoke coordinator branch.

If no existing shape represents the semantics, the Step is **not implementable as an external plugin yet**. Propose a core structural ADT evolution first.

## 5. Directive requirements

Same rule using `DirectiveExecutionPolicy`.

A plugin may add a new key but not a new lifecycle phase or hidden stage scheduler.

## 6. Event requirements

A plugin event:

- has stable namespaced key;
- versioned codec;
- no secrets/raw credential material;
- deterministic causation/correlation linkage;
- declared emission authority;
- compatibility test fixture.

## 7. DSL façade requirements

A public plugin DSL façade is either:

- pure builder;
- eager typed invocation constructor;
- block invocation constructor;
- scripted suspend runtime call.

The façade must not:

- execute I/O;
- inspect global state;
- mutate a previously emitted Step;
- manufacture runtime return values;
- silently downgrade unsupported options.

## 8. No-core-change law

A new plugin Step/directive/event that uses existing structural shapes must be installable and executable with:

```text
0 changes to pipeline-domain
0 changes to pipeline-application semantic dispatch
0 changes to compiler semantic switches
0 changes to coordinator StepKey/directiveKey branches
```

Allowed core change: generic bug fix or adding a new **structural shape** via explicit ADR/versioned engine capability, never a plugin-specific case.

## 9. High-performance composition

At startup:

```text
ServiceLoader/KSP metadata
-> validate manifests
-> build StepRegistry / DirectiveRegistry / EventRegistry
-> freeze immutable registries
-> precompute resolved plans
```

Hot path:

- no ServiceLoader;
- no reflection lookup per operation;
- no scanning jars;
- no string switch over keys;
- pre-resolved codec/handler/capability plan.
