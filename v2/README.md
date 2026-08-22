# V2 Included Build — Pipeline V2 Skeleton

A Gradle composite (`includedBuild`) lane pinned to Kotlin 2.4.10 / JVM 21,
entirely isolated from the V1 build toolchain.

## Module Map

| Module | Seed types | Inward edges |
|--------|-----------|--------------|
| `:pipeline-domain` | `PipelineDefinition`, `StepDescriptor` | *(none — framework-free)* |
| `:pipeline-application` | `PipelineUseCase`, `StepRegistryUseCase` | → `:pipeline-domain` |
| `:pipeline-scripting-api` | `ScriptingHost`, `ScriptEvaluationContext`, `ScriptingDiagnostic` | → `:pipeline-domain` |
| `:pipeline-testkit` | `PipelineFixture`, `StepDescriptorAssertions`, `pipelineDefinitionShould` | → `:pipeline-domain`, `:pipeline-application` |

## Toolchain

- **Kotlin**: `2.4.10` (pinned in `v2/gradle/libs.versions.toml`)
- **JVM**: 21 (`jvmToolchain(21)` per module)
- **Language version**: `KOTLIN_2_4`
- **API version**: `KOTLIN_2_4`

## Root Wiring

V1 (root project) wires this included build via a single line in
`settings.gradle.kts`:

```kotlin
includeBuild("v2")
```

No other root file is touched. V1 keeps its own Kotlin toolchain and catalog.

## Building

From the repository root (V1 side):

```bash
./gradlew -p v2 check
```

Or from inside the `v2/` directory:

```bash
cd v2 && ./gradlew check
```

All four modules must pass for the included build to be considered healthy.
