# SPEC-LFC-004 — Typed capability services

**Status:** proposed

## Rule

Runtime handlers request minimum capabilities; they do not receive an omnipotent context/service locator.

## Initial capability catalogue

- `ProcessService`
- `WorkspaceService`
- `EnvironmentService`
- `CredentialService`
- `OutputService`
- `ArtifactService`
- `ScmService` only if a stable cross-plugin primitive is justified
- `RunStateService`
- `Clock`
- `RuntimeConfig`

## Dependency style

Use Kotlin context parameters in runtime implementation APIs where they materially improve clarity:

```kotlin
context(
    process: ProcessService,
    workspace: WorkspaceService,
    output: OutputService,
)
suspend fun ShHandler.execute(command: ShCommand): ShResult
```

Constructor injection remains appropriate for private collaborators internal to a plugin.

## Lifecycle

Capabilities have an explicit scope:

```text
PROCESS(runtime installation)
RUN
STAGE
BLOCK
STEP
```

Resource-owning capabilities must close/release deterministically. Credential leases and temporary material are never global singletons.

## Admission

Before executing a step, the dispatcher verifies that required capabilities are available. Missing capability yields a typed failure before side effects begin.
