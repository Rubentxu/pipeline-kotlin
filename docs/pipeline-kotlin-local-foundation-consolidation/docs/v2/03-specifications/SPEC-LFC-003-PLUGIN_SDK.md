# SPEC-LFC-003 — Plugin SDK v1

**Status:** proposed

## Core separation

```text
Kotlin DSL façade
      -> StepDescriptor
      -> StepCommand / payload
      -> StepHandler
```

The façade is compile-time UX. The descriptor is static metadata. The command is durable input. The handler performs runtime work through capabilities.

## Extension kinds

```kotlin
sealed interface ExtensionKind {
    data object Step : ExtensionKind
    data object BlockStep : ExtensionKind
    data object Agent : ExtensionKind
    data object Condition : ExtensionKind
    data object Option : ExtensionKind
    data object Tool : ExtensionKind
    data object Parameter : ExtensionKind
    data object CredentialBinding : ExtensionKind
}
```

Not every Jenkins-like construct is a step.

## Step implementation model

```kotlin
@PipelineStep(
    id = "junit.junit",
    effects = [Effect.WORKSPACE_READ],
    replay = ReplayPolicy.REUSE_RESULT,
)
@JenkinsSurface(
    step = "junit",
    plugin = "junit",
    compatibility = CompatibilityLevel.BEHAVIORAL,
)
context(
    workspace: WorkspaceService,
    output: OutputService,
)
suspend fun junit(
    testResults: String,
    allowEmptyResults: Boolean = false,
): JUnitResult
```

## KSP generation

KSP MUST derive/generate, without a hardcoded core-step switch:

- descriptor;
- input/output schema;
- serializer references;
- required capability IDs from declared context parameters (or a generated equivalent if KSP limitations require annotations);
- declarative façade;
- runtime registration bridge;
- plugin manifest fragment;
- IDE/LSP metadata;
- Markdown API docs;
- Jenkins compatibility metadata;
- TestKit fixtures/builders where practical.

## Plugin API stability

Plugin API and DSL surface are separately versioned. A plugin implementation may evolve without changing DSL compatibility if command/schema and behavior remain compatible.
