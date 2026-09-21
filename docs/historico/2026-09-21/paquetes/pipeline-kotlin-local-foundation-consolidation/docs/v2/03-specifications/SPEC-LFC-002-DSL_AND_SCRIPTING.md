# SPEC-LFC-002 — Jenkins-like Kotlin DSL and scripting boundary

**Status:** proposed

## Declarative DSL

Use Kotlin type-safe builders with `@DslMarker` and narrow receivers:

```text
PipelineScope
  StagesScope
    StageScope
      StepsScope
      WhenScope
      PostScope
      ParallelScope
      MatrixScope
```

Declarative calls build canonical IR only.

## Formal script definition

Define `.pipeline.kts` through `@KotlinScript` and a dedicated `ScriptCompilationConfiguration`; do not use a generic `Any` template as the final product API.

## Runtime values

A declarative builder MUST NOT pretend to return a runtime value.

Correct:

```kotlin
steps { sh("./gradlew build") }
```

Runtime Kotlin is explicit:

```kotlin
script {
    val branch = shStdout("git branch --show-current").trim()
    if (branch == "main") sh("./deploy.sh")
}
```

The scripted runtime API is durable: each effect is an operation with replay semantics. Arbitrary unjournaled side effects are outside guarantees and should be documented/restricted.

## Shell dollar handling

Do not permanently maintain a source-wide lexical rewrite if standard Kotlin forms are sufficient. Spike alternatives:

- `${'$'}HOME` in Kotlin strings;
- a raw shell literal helper;
- compiler/plugin-level targeted handling with accurate source maps.

Whichever option wins must preserve compiler diagnostics and source fidelity.

## Grammar constraints

The type system should prevent invalid placement when possible. Semantic validator handles constraints that cannot be represented naturally by receivers/types.
