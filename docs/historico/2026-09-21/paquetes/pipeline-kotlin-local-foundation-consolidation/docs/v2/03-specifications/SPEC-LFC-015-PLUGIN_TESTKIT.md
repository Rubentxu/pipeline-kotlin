# SPEC-LFC-015 — Plugin TestKit and contract tests

**Status:** proposed

## Required plugin contract suite

Every published plugin must pass reusable tests for:

- descriptor/manifest validation;
- schema/serializer round trip;
- DSL compilation fixture;
- missing-capability failure;
- replay/idempotency declaration consistency;
- cancellation if effectful;
- failure taxonomy;
- output redaction contract when secrets are involved;
- Jenkins compatibility fixtures for claimed F-level;
- plugin API/runtime version compatibility.

## TestKit API sketch

```kotlin
pluginContract<JUnitPlugin> {
    compilesDsl("""steps { junit("**/*.xml") }""")
    missingCapability<WorkspaceService>().failsBeforeExecution()
    jenkinsCompatibility(F2)
}
```

TestKit should provide recording capabilities and temporary durable stores outside production domain packages.
