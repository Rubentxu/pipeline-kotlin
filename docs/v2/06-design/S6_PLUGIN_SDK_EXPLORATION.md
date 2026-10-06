# S6 Plugin SDK v2 — Exploration

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk` · **Path:** A-full · **Branch:** `s6-plugin-sdk`
**Canonical spec:** `docs/pipelinek-semantic-evolution/05-step-plugin-sdk-v2.md` (124 lines, 9 sections)
**Date:** 2026-10-06 · **Phase:** `explore`

Every claim below carries `ruta:línea`. This is a reading of the code, not a plan.
Three findings in this document were **corrections of a first pass that was wrong**,
and they are kept rather than quietly fixed, because each correction was itself a
harness failure worth not repeating.

---

## 1. The gap in one line

The spec asks for a **machine-readable** plugin manifest, **admission before
classloading**, and **frozen, pre-resolved registries**. The repository has an
**in-memory, Step-only** manifest validated *after* the classes are already loaded,
and **mutable** Step and Event registries that are never frozen.

## 2. §2 Plugin manifest — absent in the required form

The spec requires every distributable plugin to publish:

```kotlin
data class PipelineKPluginManifest(
    pluginId, pluginVersion, apiRange,
    steps: List<StepManifestEntry>, directives: List<DirectiveManifestEntry>,
    events: List<EventManifestEntry>, reactors: List<ReactorManifestEntry>,
    capabilities: Set<CapabilityKey>,
)
```

| Symbol required by §2 | Occurrences in `v2/**`, `examples/**` |
|---|---:|
| `PipelineKPluginManifest` | **0** |
| `StepManifestEntry` | **0** |
| `DirectiveManifestEntry` | **0** |
| `EventManifestEntry` | **0** |
| `ReactorManifestEntry` | **0** |

What exists is `PluginManifest`
(`v2/pipeline-domain/…/domain/step/PluginManifest.kt:18-26`):

| §2 requires | `PluginManifest` today |
|---|---|
| `steps` + `directives` + `events` + `reactors` | only `stepManifests: List<StepManifest>` |
| `pluginVersion`, `apiRange` | `release: PluginReleaseRef` — **no compatibility range** |
| top-level `capabilities` | `declaredCapabilities`, duplicated per Step |
| **machine-readable** | **in-memory, built at runtime** |

### 2.1 The validator is real and is wired — this corrects the first pass

`PluginManifestValidator` exists at
`v2/pipeline-domain/…/domain/step/PluginManifestValidator.kt:26` and does genuine
fail-closed work: manifest ⊆ definitions (`:39`), definitions ⊆ manifest (`:48`),
and exact `declaredCapabilities == contract.requiredCapabilities` (`:61-62`).

**Four production plugins call it:**

| Plugin | Call site |
|---|---|
| `http` | `…/sdk/http/HttpStepDefinitionContributor.kt:50` |
| `scm-git` | `…/sdk/git/step/ScmGitStepDefinitionContributor.kt:68` |
| `utilities` | `…/sdk/utilities/step/CoreUtilsStepDefinitionContributor.kt:61` |
| `junit` | `…/sdk/junit/step/JUnitStepDefinitionContributor.kt:43` |

The first pass in this cycle asserted the validator had **no production callers**.
That was wrong, and the cause is worth recording: the search used
`v2/*/src/main`, a single-level glob, which silently omits
`v2/pipeline-step-sdk/*/src/main`. All four callers live two levels down. This is
the same trap already recorded for test counting, and it cost a wrong conclusion
here. **Recursive searches only, always.**

Coverage is partial even so: `example.uppercase`, `example.block` and
`example.lock` never construct a `PluginManifest` at all, and there is no
validator counterpart for Directives or Events — the Step validator's signature
takes only `List<StepDefinition<*, *>>` (`PluginManifestValidator.kt:34`).

## 3. §9 High-performance composition — startup correct, freeze partial

Startup discovery is real and correctly placed in the application adapter:

| Site | Line |
|---|---|
| `ExternalStepPluginDiscovery.kt` | `:36` |
| `ExternalDirectivePluginDiscovery.kt` | `:32` |
| `ExternalCapabilityContributorDiscovery.kt` | `:61` |
| `ExternalEventDefinitionDiscovery.kt` | `:52` |

There are **four** sites, not three; the first pass missed the Event one.

The freeze requirement is met for one registry and missed for two:

| Registry | Immutable? | Evidence |
|---|---|---|
| `DirectiveRegistry` | **YES** | private constructor (`:111`), `Builder.build()` copies into a `LinkedHashMap` (`:167`) |
| `StepRegistry` | **NO** | open `register()` (`StepRegistry.kt:150`, `:158`) over a live `linkedMapOf` (`:183`) |
| `EventRegistry` | **NO** | public `register` (`:39`) over a mutable `LinkedHashMap` |

So a Directive cannot be added after composition, but a Step or an Event can, at
any point in a run. There is no sealed state and no pre-resolved handler/codec/
capability plan anywhere: resolution is a map lookup performed per invocation
(`RegistryStepInvoker` at `StepRegistry.kt:227-255`, plus independent lookups in
`ScriptedRegistryInvoker.kt:286,466` and `StepDispatchEngine.kt:443,594`).

The per-invocation lookup is O(1) and is not reflection, so it does not violate
§9's hot-path list by its letter. But "pre-resolved codec/handler/capability plan"
is unimplemented, and against a mutable registry the resolution result is not even
stable for the life of a run.

## 4. §8 No-core-change law — satisfied, and enforced by a real fitness test

The roadmap nominates `http.request` as the worked proof. That claim holds **under
§8's wording** and would be **false** read loosely, so both readings are recorded.

`Lfc2HttpOfficiallyPluginBoundaryFitnessTest.kt:67` enforces it with FIT-1..FIT-8
(lines 105, 120, 134, 147, 185, 208, 225, 237): the compiler contains no
`httpRequest` key, `StepSpec` has no HttpRequest variant, the core registry does
not register `http`, and `pipeline-application` does not import the plugin's
vocabulary. That is genuine enforcement — for Steps.

The introducing commit `5db30afc` also changed:

| File | Nature |
|---|---|
| `v2/settings.gradle.kts`, `v2/build.gradle.kts` | build wiring |
| `pipeline-domain/…/StepDefinitionContributor.kt` | +1 line, a defaulted `registrations()` |
| `pipeline-application/…/CliParser.kt` | **new `--allow-network` flag** |
| `pipeline-application/…/CompositionRoot.kt` | wiring the egress verdict |

No semantic dispatch, no compiler switch, no coordinator `StepKey` branch. §8 is
**satisfied** — but not as "zero core changes". The precise rule, which is the
useful part:

> A plugin contributing a **new Step shape** costs zero core changes. A plugin
> needing a **capability the runtime does not yet produce** costs exactly one
> generic capability, produced by the runtime and denied by default. `CliParser`
> is that capability's production point, and the default-deny is enforced as a
> *missing capability at admission* rather than a check inside a Step.

## 5. Two defects found that are not in the spec's list

### 5.1 The KSP layer branches on concrete Step names

`StepDescriptorGenerator.kt:146-151` decides `ExecutionLocation`, `ReplayPolicy`
and `failureKindBridge` with a `when` over string prefixes:

```kotlin
val (location, replayPolicy, failureKindBridge) = when {
    jenkinsSurfaceTriple.startsWith("echo|")  -> Triple("CONTROLLER", "MEMOIZED", "INFRASTRUCTURE")
    jenkinsSurfaceTriple.startsWith("sh|")    -> Triple("WORKER",    "RERUN",    "PROCESS")
    jenkinsSurfaceTriple.startsWith("error|") -> Triple("AGENT",     "NEVER",    "USER")
    jenkinsSurfaceTriple.startsWith("sleep|") -> Triple("CONTROLLER", "MEMOIZED", "INFRASTRUCTURE")
    else -> Triple("WORKER", "MEMOIZED", "UNKNOWN")
}
```

This is a semantic dispatcher over concrete Step names inside the KSP layer, which
`AGENTS.md`'s compiler/lowering law names directly: KSP "MUST NOT contain
concrete Step or directive semantics, nor a `when(key)` semantic dispatcher". It
is the same defect class as debt D1, where `CoreWaitUntilStep.kt:69` rebuilds a
`StepOutcome` by comparing a `String` — one decision made from a name, in two
layers. The impact is attenuated because the output is LSP metadata, but the law
does not exempt it for being metadata.

### 5.2 The LSP metadata is emitted under a name no loader can match

`StepDescriptorGenerator.kt:129-132`:

```kotlin
codeGenerator.createNewFile(
    packageName = "",
    fileName = "META-INF/pipeline/step-metadata/$stepId.json",  // already ends in .json
    extensionName = "json",                                     // KSP appends this again
    …
)
```

KSP appends `extensionName` to `fileName`, so the emitted resource is
`core.echo.json.json`. Confirmed on disk under
`v2/pipeline-step-sdk/runtime/build/generated/ksp/main/resources/META-INF/pipeline/step-metadata/`.

The reader that would consume it, `LspMetadataLoader.kt:4`, has **zero
consumers** in `v2/**` — the symbol appears only on its own declaration line.

Two independent defects that compound: the file nobody reads is also named so that
the reader could not read it. Together they mean the KSP metadata path is dead
code that looks live.

## 6. Why S6 can start, and what it must not absorb

S6 can start: RP-0/RP-1 are not open, and the debt that gates a block — D5,
redefining `PRODUCT-GATE` — is required **before S7**. D2 is assigned to S7.

S6 must not absorb:

- **S5.5's reactor.** ADR-0104 deferred it and named S6 as where a
  plugin-contributed reactive construct belongs. `ReactorManifestEntry` is a §2
  slot, but §1's "optional reactive observers/reactors" stays a slot until a real
  consumer exists. A manifest entry with no consumer is the exact defect ADR-0104
  refused to let S5.5 commit.
- **Certification industrialization.** That is S7. S6 delivers the seams.

## 7. Open questions for the specification phase

1. **Extend `PluginManifest` toward §2, or mint `PipelineKPluginManifest` as a
   new type with a codec?** The first migrates four already-wired plugins; the
   second leaves two authorities of "what a plugin declares" alive until the
   first is deleted. This choice determines the shape of everything else.
2. **Is freezing `StepRegistry` a structural change needing an ADR?** §8 permits
   "generic bug fix or adding a new structural shape via explicit ADR". Sealing
   `register()` changes a published interface, so it is arguably a third thing.
   `DirectiveRegistry` already solved it with a private constructor plus a
   `Builder` — that precedent may settle it without an ADR.
3. **Where does the machine-readable manifest live?** §2 says KSP "**may**"
   generate the boilerplate — permissive. But §9 forbids jar scanning on the hot
   path and M3a wants admission before classloading, and both push toward a
   resource the plugin **ships**, not one the compiler invents at build time.
4. **Is §5.1 in S6's scope, or is it a separate debt?** It lives in the plugin
   SDK's own KSP module, but it is an execution-semantics defect, not an SDK
   surface gap. It is also the same class as D1.
