# Step & Plugin SDK Specification

## 1. Modelo

Un Step tiene cuatro representaciones separadas:

```text
Kotlin façade visible
      ↓
StepDescriptor
      ↓
StepCommand / durable operation
      ↓
StepHandler implementation
```

La façade puede evolucionar manteniendo un wire/domain contract estable.

## 2. Annotation

```kotlin
@Step(
    id = "core.sh",
    name = "sh",
    execution = ExecutionLocation.WORKER,
    effects = [Effect.PROCESS, Effect.FILESYSTEM],
    replay = ReplayPolicy.REUSE_RESULT
)
@JenkinsSurface(
    step = "sh",
    compatibility = CompatibilityLevel.MIGRATION
)
context(process: ProcessExecutor)
suspend fun sh(
    script: String,
    returnStdout: Boolean = false,
    returnStatus: Boolean = false
): ShellResult
```

## 3. Context parameters

Cada Step solicita capabilities mínimas:

```kotlin
context(fs: Workspace)
suspend fun readFile(path: String): String

context(creds: CredentialResolver, process: ProcessExecutor)
suspend fun git(...): GitCheckoutResult
```

No existe `PipelineContext` omnipotente como requisito universal.

## 4. KSP/codegen

KSP genera:
- `StepDescriptor` estático;
- typed façade/bridge cuando sea necesario;
- input/output schemas;
- serializer references;
- plugin manifest fragment;
- LSP completion metadata;
- markdown/API docs;
- Jenkins familiarity mapping;
- test fixtures/builders.

## 5. Descriptor

Campos mínimos:

```text
stepId
name
pluginId
pluginVersion
apiVersion
executionLocation
inputSchema
outputSchema
requiredCapabilities
effects
replayPolicy
idempotencyModel
timeoutModel
jenkinsSurface
securityProfile
deprecation
```

## 6. Plugin manifest

```yaml
apiVersion: pipeline.dev/v1alpha1
kind: PipelinePlugin
metadata:
  name: git
spec:
  version: 2.0.0
  pluginApi: v1
  runtimeCompatibility: ">=2.0 <3"
  steps:
    - coreRef: git.checkout
  permissions:
    network:
      outbound: true
    credentials:
      types: [usernamePassword, sshPrivateKey]
```

## 7. Packaging

Objetivo OCI:

```text
plugin.jar
plugin.yaml
schemas/
docs/
sbom.spdx.json
provenance.json
signature metadata
```

El lockfile usa digest:

```yaml
plugins:
  - id: pipeline.git
    version: 2.1.0
    digest: sha256:...
```

## 8. Loading

Worker resuelve/verifica plugins antes de compile/evaluate. No se permite que un script descargue plugin code arbitrario durante ejecución.

## 9. Step compatibility

Plugin API y DSL surface se versionan separadamente. Un alias/deprecation layer permite mantener firmas conocidas mientras cambia la implementación.

## 10. Test contract de un plugin

Todo plugin debe aportar:
- descriptor validation;
- serializer round-trip;
- replay contract;
- idempotency/effect tests;
- capability missing tests;
- error taxonomy tests;
- compatibility DSL snippets;
- supply-chain metadata.

## 11. Ecosystem delivery classes

The SDK is also the boundary that keeps core small. Every Step family is classified by
`STEP_ECOSYSTEM_POLICY.md` as one of:

- `CORE`;
- `OFFICIAL_PLUGIN`;
- `EXTERNAL_REFERENCE`;
- `DEFERRED_REMOTE`;
- `REJECTED_JENKINS_INTERNAL`.

A first-party/official plugin MUST use the same registration and execution path as an independently built
external plugin. Being maintained in the pipeline-kotlin organization does not grant privileged coordinator
access or a private dispatcher path.

Default placement for a non-universal new family is `OFFICIAL_PLUGIN`.

## 12. Promotion to core

A plugin Step is not promoted to core because it is popular or bundled by Jenkins. Promotion requires evidence
that the concept is universal and cannot be expressed cleanly through the public plugin/capability seam without
exposing a genuine engine primitive.

Promotion MUST NOT create a second execution path. The same `StepDefinition`/codec/handler/capability laws apply.

## 13. Plugin development as SDK pressure test

LFC-2E intentionally implements increasingly demanding families outside core:

```text
uppercase (pure)
 -> utilities (filesystem + typed values)
 -> testing (structured reports + events)
 -> HTTP (network + credentials)
 -> Git (process + network + credentials + filesystem)
 -> containers (nested scopes + resources + registry credentials)
 -> vendor reference (network + credentials + artifacts + vendor failures)
```

If a non-universal plugin requires a Step-specific change in `CanonicalDurableRunCoordinator`, a central
`when(stepName)`/dispatcher case, or another privileged production path, implementation stops and the missing
**generic** SDK capability is documented and repaired. A plugin-name-specific exception is forbidden.

At least one second consumer/regression proof SHOULD exercise a new generic SDK seam before it is treated as stable.

## 14. Jenkins-compatible vs native Kotlin surface

Compatibility façade and native typed façade may coexist when both compile to the same stable Step contract.
Examples:

- `readJSON(...)` compatibility plus typed `JsonValue`/structured-data native result;
- `git(...)` / `checkout(...)` compatibility plus native `scm.checkout(...)`;
- Docker-compatible façade plus provider-neutral `container.*` API.

Jenkins familiarity is therefore a migration/product surface, not the architecture boundary.

## 15. Event Harness certification after EVT-3

After EVT-3, a plugin Step with observable user-facing lifecycle/outcome is not `CERTIFIED` until its real
`.pipeline.kts` scenario is checked by a reusable Event Harness contract in addition to the common Step/Plugin
Contract Suite.

The verifier is post-run acceptance evidence; it does not become execution authority and does not change
`PipelineOutcome`.
