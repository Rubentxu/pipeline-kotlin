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
