# Plugin author guide

## Minimal step plugin

```kotlin
@PipelineStep(
    id = "example.greet",
    effects = [Effect.READ_ONLY],
    replay = ReplayPolicy.REUSE_RESULT,
)
suspend fun greet(name: String): GreetingResult = GreetingResult("Hello $name")
```

The SDK/KSP generates the descriptor, serializers/schema, DSL façade and registration metadata.

## Effectful handler

```kotlin
@PipelineStep(
    id = "example.tool",
    effects = [Effect.PROCESS],
    replay = ReplayPolicy.RERUN,
)
context(process: ProcessService, output: OutputService)
suspend fun tool(args: List<String>): ToolResult {
    return process.exec(ProcessRequest(argv = listOf("tool") + args))
}
```

Do not create `ProcessBuilder`, credential stores, filesystem roots or output databases inside the plugin.

## Choosing an extension kind

- command in `steps` -> Step;
- wrapper with body -> BlockStep;
- pipeline/stage execution target -> Agent;
- predicate under `when` -> Condition;
- declarative behavior toggle -> Option;
- tool declaration -> Tool;
- parameter declaration -> Parameter;
- secret projection -> CredentialBinding.

## Publish checklist

- semantic version;
- plugin API/runtime range;
- generated manifest/schema/docs;
- TestKit contract suite;
- license/SBOM;
- Jenkins compatibility metadata if claimed;
- no undeclared capabilities.
