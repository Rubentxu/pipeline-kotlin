# LB-02 / External Plugin Proof — EP-0 contract + opening audit

## EP-0 — target plugin

`example.uppercase`: `Input { text: String }` → `Output { text: String }`, "hello" → "HELLO".
No subprocess/network/filesystem; pure extensibility proof. Typed I/O, codecs, StepContract,
StepDescriptor, handler, registry/discovery metadata.

Goal: demonstrate a core-external Step traversing the shared spine with **ZERO production changes**
(no StepKey branch, no coordinator/dispatcher/compiler/durable modification, no core catalogue row).

## EP-2 — public SDK audit (result: SPI is public)

The Step-authoring SPI lives in **public `pipeline-domain`**:
`StepDefinition`, `StepContract`, `StepCodec`, `StepHandler`, `StepDescriptor`, `StepRegistry`,
`StepCapability` are all declared there. `CoreEchoStep`/`CoreShellStep` construct these types from
`pipeline-application` but the types themselves are domain-public. A separate plugin module that
depends on `:pipeline-domain` can compile a `StepDefinition` without importing application internal
packages. **SDK-API sufficiency: no hard import gap found at the type-authoring level.**

## EP-6 — StructuralRegistry routing (result: routes without a closed catalogue)

The structural classifier is open: a plugin key not in `LEGACY_PLUGIN_IDS` classifies as
`StructuralStepFamily.Registry` when present in a `StepRegistry` (data-driven; no `when(stepKey)`).
Adding `example.uppercase` to a registry would route it to `RegistryStepMetadataResolver` +
`RegistryExecutionPreparation` + `CommonExecutionBoundary` exactly like `core.sh`. **No closed
Step enum / catalogue membership is required for the key.**

## EP-4 — discovery into the runtime registry (gap)

There is **no generic Step discovery mechanism** into the runtime registry. The registry is built by
`CoreStepRegistryFactory.registry()` (in `pipeline-application`) and injected by the composition
root (`Main`). The only extension path today is the documented manual composition:

```kotlin
val registry = CoreStepRegistryFactory.registry()
externalPlugin.registerInto(registry)   // plugin on the classpath, wired at the composition root
CanonicalDurableRunCoordinator(..., stepRegistry = registry)
```

No `ServiceLoader`/`META-INF/services` Step-contribution SPI exists (the repo uses that SPI pattern
only for credential `ContributedBindingFactory`). So a plugin JAR cannot be auto-discovered into the
runtime registry today.

**Classification: discovery gap** (EP-4) — a generic Step-registry contribution SPI is absent.

## EP-11 — real-DSL external step invocation (STOP CONDITION)

The DSL model is a **closed sealed `StepSpec` hierarchy** (`Echo`, `Shell`, `Error`, `Sleep`,
`Checkout`, `WriteFile`, block/control-flow types). `DslCompiledPipelineCompiler` maps each known
`StepSpec` to a concrete `pluginStepId` (`core.*`). There is **no generic/custom/plugin StepSpec** and
no way to invoke an arbitrary external Step (e.g. `example.uppercase`) from a `.pipeline.kts`.

To invoke an external Step from the real DSL today you would have to:
- add a new sealed `StepSpec` variant AND a compiler case (production change), or
- introduce a generic DSL step-representation + compiler hook that the plugin registers into.

Neither exists. The compiler/KSP would have to **know the StepKey** to emit it.

**Classification: DSL-extension gap** (EP-11) — this is a real stop condition per the proof rules:

> 2. compiler/KSP necesita conocer su StepKey;
> "Si hoy la DSL no puede descubrir/extender Steps externos sin regenerar core compiler/KSP,
> eso es un gap importante y debemos detenernos ahí."

## Gate status

| Evidence row | Value |
| --- | --- |
| coordinator modifications for example.uppercase | 0 (none made) |
| dispatcher modifications | 0 |
| durable modifications | 0 |
| compiler known-Step cases | would be >0 (blocked) |
| core metadata rows | 0 |
| Real DSL `.pipeline.kts` invocation | **not possible today (DSL-extension gap)** |

## Finding

The **execution spine** (StructuralRegistry → composite metadata → registry preparation →
CommonExecutionBoundary) is genuinely open and could run an external Step with zero spine changes.
The blockers are the **authoring/registration front door**:

1. **Discovery gap (EP-4)**: no generic Step-contribution SPI into the runtime registry (only manual
   composition-root wiring).
2. **DSL-extension gap (EP-11, STOP)**: the DSL/compiler is closed over a sealed `StepSpec`; an
   external Step cannot be invoked from a real `.pipeline.kts` without compiler/KSP knowledge.

These are real architectural findings, not code gaps to work around. Solving them (a generic plugin
discovery SPI plus a generic DSL external-step representation/compiler hook registered by plugins) is
the substance of the External Plugin Proof's next phase. `example.uppercase` is therefore
`IMPLEMENTED_UNCERTIFIED`; it cannot be `CERTIFIED` until the DSL-extension gap is closed with a
mechanism that does not require per-StepKey production changes.
