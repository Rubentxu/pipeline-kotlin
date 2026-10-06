# References

## Repository anchors inspected

Baseline: `569a088cc76f1a826c619577eefa1475404c3fb4`

Current implementation/design anchors used by this bundle:

- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepRegistry.kt`
- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt`
- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/PluginManifest.kt`
- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/PluginReleaseRef.kt`
- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepProviderMetadata.kt`
- `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/TrustMetadata.kt`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ExternalStepPluginDiscovery.kt`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/BundledPluginClasspathPlan.kt`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainRuntimeSupport.kt`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionPreparation.kt`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt`
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptDefinition.kt`
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/CacheKey.kt`
- `v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/Kotlin24ScriptingHost.kt`
- `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/LspMetadata.kt`
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptingHost.kt`
- `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedExecutionApi.kt`
- `v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/KotlinScriptedSourceMapper.kt`
- `docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md`
- `docs/v2/05-roadmap/ROADMAP.md`
- `docs/pipelinek-semantic-evolution/05-step-plugin-sdk-v2.md`
- `docs/pipelinek-semantic-evolution/08-roadmap.md`
- `docs/proposals/pipelinek-agent-secretless/02-specifications/INLINE-AGENT-CLI.md`
- `docs/proposals/pipelinek-agent-secretless/02-specifications/UNIFIED-INVOCATION-ARCHITECTURE.md`
- `docs/v2/02-architecture/PLUGIN_IDENTITY_MODEL.md`
- `docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md`
- `docs/v2/04-adrs/ADR-0024-lsp-metadata-json-schema.md`

Historical V1 capability anchors include the root-era `core/library`, `core/error`, `core/compilation`, `core/execution`, `pipeline-lsp-server`, `pipeline-backend`, `pipeline-config` and `pipeline-steps-system` areas. Their implementations are inputs to disposition, not targets to port.

## External reference implementations / concepts

These are design references, not dependencies:

- Jenkins Shared Libraries: https://www.jenkins.io/doc/book/pipeline/shared-libraries/
  - relevant idea: library source/classes are made available to Pipeline compilation; static pre-compilation loading enables typed references.
- Terraform machine-readable provider schema: https://developer.hashicorp.com/terraform/cli/commands/providers/schema
  - relevant idea: versioned, machine-readable schema projection separated from execution.
- Terraform provider schema model: https://developer.hashicorp.com/terraform/plugin/framework/handling-data/schemas
- Kubernetes `kubectl api-resources`: https://kubernetes.io/docs/reference/kubectl/generated/kubectl_api-resources/
  - relevant idea: discover supported resources.
- Kubernetes `kubectl explain`: https://kubernetes.io/docs/reference/kubectl/generated/kubectl_explain/
  - relevant idea: explain resource fields from machine-readable schema.
- RFC 8288 Web Linking: https://www.rfc-editor.org/rfc/rfc8288
  - relevant idea: typed relations between resources.
- JSON Hyper-Schema: https://json-schema.org/specification/json-hyper-schema
  - relevant idea: machine-described links/actions and discoverability. PipelineK adopts the concept, not the HTTP requirement or specification wholesale.

## Interpretation rule

External systems are references for useful interaction patterns only. PipelineK must implement them through its existing V2 authorities and constraints, not copy their internal architectures.


## Version-pinned Gradle and Kotlin study

Inspection date: 2026-10-06. Compare the repository wrapper **Gradle 8.14.5** with **Gradle 9.8.0** specifically. The former embeds Kotlin 2.0.21 and uses Kotlin DSL language/API 1.8; the latter inspected compiler config uses language/API 2.2/K2. PipelineK's Kotlin 2.4.10 adapter is a different combination. Do not attribute one release's behaviour to all Gradle versions.

| Primary source | Observed fact | PipelineK transfer / limit |
| --- | --- | --- |
| [Gradle 8.14.5 KotlinCompiler](https://github.com/gradle/gradle/blob/v8.14.5/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/support/KotlinCompiler.kt) and [9.8.0 KotlinCompiler](https://github.com/gradle/gradle/blob/v9.8.0/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/support/KotlinCompiler.kt) | Owned compiler configuration and a class-files-generating evaluator | Complete effective profile; compile/load/evaluate split inside canonical adapter |
| [Kotlin 2.4.10 script saving](https://github.com/JetBrains/kotlin/blob/v2.4.10/libraries/scripting/jvm-host/src/kotlin/script/experimental/jvmhost/jvmScriptSaving.kt) | Saving/generation APIs write real compiler output and return a non-evaluated result | Real representation spike; no assumed PipelineK metadata round trip |
| [Gradle 8.14.5 ProgramId](https://github.com/gradle/gradle/blob/v8.14.5/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/execution/ProgramId.kt) and [9.8.0 ProgramId](https://github.com/gradle/gradle/blob/v9.8.0/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/execution/ProgramId.kt) | Compilation identity includes source/template/compiler/classpath context | Identity comes from effective admitted inputs; preserve semantic order |
| [Gradle 8.14.5 KotlinScriptEvaluator](https://github.com/gradle/gradle/blob/v8.14.5/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/provider/KotlinScriptEvaluator.kt) | Separate persistent workspace compilation and loaded-program cache; fingerprints target/options/source/template/classpaths | Disk and in-memory class reuse are separate decisions; no evaluated invocation caching |
| [Gradle 9.8.0 Interpreter](https://github.com/gradle/gradle/blob/v9.8.0/platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/execution/Interpreter.kt) | Staged dependency/plugin preparation, compiled program reuse and subsequent evaluation | Existing PipelineK config/admission/plan prepares dependencies; no new dependency DSL |
| [Gradle 8.14.5 JpmsConfiguration](https://github.com/gradle/gradle/blob/v8.14.5/platforms/core-runtime/base-services/src/main/java/org/gradle/internal/jvm/JpmsConfiguration.java) and [9.8.0 JpmsConfiguration](https://github.com/gradle/gradle/blob/v9.8.0/platforms/core-runtime/base-services/src/main/java/org/gradle/internal/jvm/JpmsConfiguration.java) | 9.8 adds Unsafe allow for Java 24+ daemon warning suppression | Version-specific evidence; suppression is not removal and is not the proposed compiler fix |
| [Kotlin 2.4.10 FastJar](https://github.com/JetBrains/kotlin/blob/v2.4.10/compiler/cli/cli-base/src/org/jetbrains/kotlin/cli/jvm/compiler/jarfs/FastJarFileSystem.kt) and [2.4.20 FastJar](https://github.com/JetBrains/kotlin/blob/v2.4.20/compiler/cli/cli-base/src/org/jetbrains/kotlin/cli/jvm/compiler/jarfs/FastJarFileSystem.kt) | Mapped-buffer cleanup invokes Unsafe; inspected file blob is identical in both releases | Explicit standard reader and deny-mode regression; patch upgrade is not a demonstrated fix |
| [KotlinCoreEnvironment 2.4.10](https://github.com/JetBrains/kotlin/blob/v2.4.10/compiler/cli/cli-base/src/org/jetbrains/kotlin/cli/jvm/compiler/KotlinCoreEnvironment.kt) and [JVM arguments 2.4.10](https://github.com/JetBrains/kotlin/blob/v2.4.10/compiler/arguments/src/org/jetbrains/kotlin/arguments/description/JvmCompilerArguments.kt) | Reader selection follows actual compiler configuration/options | Host and mapper must consume the explicit shared policy |
| [Oracle Java 25 launcher](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) | Unsafe access modes distinguish warning suppression from denied operation | Fresh JDK 24+ deny probe; full supported-JDK matrix remains separately required |

Official concepts consulted: [Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html) and [configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html), observed as version 9.8.0. These current links can change; the source tags/blob hashes above and [evidence snapshot](quality/gradle-primary-evidence.json) define this study. Configuration cache has a separate model/input discipline and is not a reason to cache an evaluated PipelineK plan.

## Canonical overlap and evidence limits

- [PipelineK canonical compiler specification](https://github.com/Rubentxu/pipeline-kotlin/blob/569a088cc76f1a826c619577eefa1475404c3fb4/docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md) already owns compiler ports/cache policy. Public implementation compatibility is inventoried before migration.
- [PipelineK S6 SDK specification](https://github.com/Rubentxu/pipeline-kotlin/blob/569a088cc76f1a826c619577eefa1475404c3fb4/docs/pipelinek-semantic-evolution/05-step-plugin-sdk-v2.md) owns unified manifests/contributor families and frozen registries. No second manifest authority is added.
- The earlier focused Unsafe study provides limited regression evidence; it certifies neither this architecture nor a future cache. JDK 25 and the full repository/release gate were not certified there.
- This refinement executes documentation consistency/integrity checks only. New implementation UAT/AAT/benchmarks remain NOT_RUN; all new ADRs remain Proposed.
