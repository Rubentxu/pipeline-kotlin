# Scripted Runtime Evolution

## 1. Preserve the good decision

Do not adopt Jenkins Groovy CPS. PipelineK already chose a better foundation:

```text
normal Kotlin suspend control flow
+ durable operation journal
+ deterministic call-site identity
+ typed output codecs
```

The existing `ScriptedStepFacade` / `ScriptedRegistryInvoker` / journal-before-capability-reuse model should remain authoritative.

## 2. Current weakness

The runtime seam is stronger than the current source lowering.

Today the path includes:

```text
Kotlin PSI mapping
-> line/column offsets
-> source text replacement
-> generated Kotlin
-> compile
```

This is acceptable as a migration scaffold, but is too fragile as a long-term stable compiler contract for arbitrary Kotlin expressions, arguments and nested control flow.

## 3. Target public model

A scripted runtime body is explicit:

```kotlin
scripted {
    val branch = sh("git branch --show-current", returnStdout = true)
    if (branch.trim() == "main") {
        echo("main")
    }
}
```

or an equivalent `suspend`-capable stage body, but it must be a distinct `StageBody` case:

```kotlin
sealed interface StageBody {
    data class Declarative(val root: ExecutionNode) : StageBody
    data class Scripted(val artifact: ScriptedArtifactRef) : StageBody
}
```

Avoid implicit hybrid bodies until lexical ordering between eager and runtime calls is formally defined and certified.

## 4. Generic typed invocation

Generalize per-Step scripted façade code into one open-world seam:

```kotlin
suspend fun <I : Any, O : Any> ScriptedStepFacade.invokeTyped(
    callSite: ScriptedCallSiteId,
    definition: StepDefinition<I, O>,
    input: I,
): O
```

Internally:

```text
StepDefinition inputCodec
-> ScriptedRegistryInvoker
-> journal lookup first
-> registry/capability only if FRESH/RERUN
-> handler
-> outputCodec
-> O
```

Plugin-owned ergonomic functions become thin wrappers and require zero compiler/core cases.

## 5. Call-site identity evolution

### Release-safe short term

Keep compiler-backed PSI mapping, but strengthen it:

- mapped call carries exact source range, callee identity and original argument expressions;
- lowering must validate the exact PSI node before rewriting;
- any mismatch -> compilation rejection, never `continue`;
- no fabricated `""` argument placeholders;
- generated source must preserve source map diagnostics.

### Target investigation

Run a bounded spike for a **K2 compiler/IR call-site metadata injector**, not a CPS transform.

Scope of plugin if adopted:

```text
@DurableDslCall call
-> inject stable source identity
```

The plugin must not:

- interpret Step keys;
- execute or schedule Steps;
- implement replay;
- transform control flow into CPS;
- know specific Step semantics.

Adoption gate:

- diagnostics at least as good as PSI path;
- compile-time overhead within agreed budget;
- no Step-specific compiler changes;
- deterministic identities;
- plugin optional behind a port until proven.

If it fails these gates, retain improved PSI lowering.

## 6. Runtime-returning APIs

Stable scripted values must use the generic typed seam:

- `pwd(): String`
- `isUnix(): Boolean`
- `readFile(): String`
- `fileExists(): Boolean`
- `sh(returnStdout=true): String`
- `sh(returnStatus=true): Int`

No eager placeholder values.

## 7. Replay laws

For each runtime call:

1. call-site identity derives from stable source identity + location + semantic kind;
2. loop invocation adds deterministic ordinal/scope identity;
3. REUSE checks journal before capability creation;
4. reused output decodes with the same Step-owned codec;
5. source/artifact compatibility mismatch fails closed;
6. runtime branch decisions are reproducible because their inputs are journaled values.

## 8. Performance

- cache compiled scripted artifacts by full compatibility fingerprint;
- freeze registries after composition root;
- pre-resolve Step definitions for scripted artifact where possible;
- avoid repeated ServiceLoader/reflection on invocation hot path;
- journal lookup remains O(log n) or indexed O(1)-like by operation identity;
- no serialization of Kotlin continuations.
