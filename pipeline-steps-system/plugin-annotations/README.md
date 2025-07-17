# Plugin Annotations

This module contains the core annotations used by the Pipeline @Step system.

## Overview

The `plugin-annotations` module provides lightweight annotations that can be used in user code to mark functions as pipeline steps. This module is deliberately kept minimal with no external dependencies.

## Annotations

### `@Step`

Marks a function as a pipeline step that should have PipelineContext automatically injected.

```kotlin
@Step(
    name = "myStep",
    description = "Description of what this step does",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun myStep(parameter: String) {
    // PipelineContext is automatically injected by the compiler plugin
    // Access it directly without LocalPipelineContext.current
}
```

### `StepCategory`

Categorizes steps for organization and discovery:
- `GENERAL`: General-purpose steps
- `SCM`: Source control management (git, checkout, etc.)
- `BUILD`: Build tools (gradle, maven, etc.)
- `TEST`: Testing tools (junit, test runners, etc.)
- `DEPLOY`: Deployment tools (docker, k8s, cloud providers)
- `SECURITY`: Security scanning, credentials, etc.
- `UTIL`: Utility functions (echo, readFile, writeFile)
- `NOTIFICATION`: Notifications (slack, email, etc.)

### `SecurityLevel`

Defines the security isolation level for step execution:
- `TRUSTED`: Full access to PipelineContext capabilities
- `RESTRICTED`: Limited access with resource limits (default)
- `ISOLATED`: Maximum sandbox with minimal capabilities

## Usage

This module is automatically included when using the Pipeline @Step system. Users typically don't need to depend on it directly, as it's included through the Gradle plugin.

## Design

Following the structure of the official Kotlin compiler plugin template, this module is kept separate from the core pipeline system to:
- Reduce coupling between annotations and runtime
- Enable faster compilation
- Provide clear separation of concerns
- Allow independent versioning of annotations

## Dependencies

This module has no external dependencies to keep it lightweight and avoid version conflicts.