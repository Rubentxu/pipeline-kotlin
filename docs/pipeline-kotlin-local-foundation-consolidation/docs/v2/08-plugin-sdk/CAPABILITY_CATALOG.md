# Capability catalogue

## `ProcessService`
Durable subprocess execution, process-tree cancellation, timeout, stdin policy and streamed output.

## `WorkspaceService`
Workspace-rooted path access, cwd views, safe temp paths, atomic file primitives required by steps.

## `EnvironmentService`
Read-only effective environment view and scoped composition interfaces. Plugins do not mutate global process environment.

## `CredentialService`
Resolve/bind secret handles and scoped leases. Raw material only exists within explicit borrow scopes.

## `OutputService`
Emit stdout/stderr/system records and bounded structured annotations. Redaction occurs before durable sinks.

## `ArtifactService`
Publish/list/materialize run artifacts with content metadata.

## `RunStateService`
Small durable plugin/step state when explicitly required. It is not a generic database escape hatch.

## `Clock`
Deterministic/testable time source.

## `RuntimeConfig`
Read-only selected platform/runtime configuration. It is not a generic map of environment variables.

## Adding a capability

Requires an ADR if it becomes part of stable Plugin API. Prove at least two independent extension use cases or a strong platform-level invariant before adding a broad global service.
