# SPEC-LFC-018 — Pipeline Test Harness

**Status:** proposed

## Module

`v2/pipeline-testkit` evoluciona a harness reusable.

Producción no depende de TestKit.

## PipelineExtension — T1

In-process:

```kotlin
@ExtendWith(PipelineExtension::class)
class ShellTest {
    @Test
    fun shell(pipeline: PipelineHarness) {
        val result = pipeline.runSource(
            """
            pipeline {
                stages {
                    stage("build") {
                        steps { sh("printf hello") }
                    }
                }
            }
            """.trimIndent()
        )

        result.shouldSucceed()
    }
}
```

Provisiona:

- temp workspace;
- temp/in-memory stores;
- deterministic Clock/IDs;
- recording events/output;
- plugin registry;
- cleanup registry.

## RealPipelineExtension — T2

Ejecuta la distribución real en otro proceso.

Debe:

- construir/reusar installDist;
- lanzar proceso/JVM separado;
- usar classpath real;
- instalar plugin JAR por mecanismo real;
- ejecutar CLI;
- capturar outputs/events;
- timeout;
- process-group cleanup.

Es autoridad para classloading/plugin discovery.

## PipelineSessionExtension — T3

Soporta:

```kotlin
pipeline.session {
    phase {
        start(scenario)
        awaitEvent(stepStarted("long"))
        killRunner()
    }

    restart()

    phase {
        resume()
        assertSideEffectCount("prepare", 1)
    }
}
```

Preserva stores/workspace/plugin set/run identity según contrato.

## SandboxPipelineExtension — T4/T5

Ejecuta el RealPipeline harness bajo isolation backend.

Podman rootless es primer backend de tests, sin convertir containers en arquitectura obligatoria del producto.

## Result API

```kotlin
data class PipelineTestResult(
    val compilation: CompilationEvidence,
    val run: RunEvidence?,
    val events: List<DomainEvent>,
    val stdout: CapturedOutput,
    val stderr: CapturedOutput,
    val workspace: WorkspaceSnapshot,
    val journal: JournalSnapshot?,
    val diagnostics: DiagnosticsBundle,
)
```

Preferir assertions tipadas frente a grep/substrings.

## Fixture services

- WorkspaceFixture
- ProcessFixture
- GitFixture
- CredentialFixture
- PluginFixture
- OutputFixture
- ClockFixture
- IdFixture
- FailureInjection

## Failure injection

Siempre en adapters/capabilities, nunca con hacks dentro de Steps.

Ejemplos conceptuales:

```kotlin
failures.process("compile").failAttempts(1, 2).thenSucceed()
failures.capability(NetworkCapability.Id).unavailable()
failures.killRunner(after = EventPredicate.StepStarted("deploy"))
```

## Process hygiene

Todo T2+:

- process group;
- JUnit timeout;
- external timeout;
- logs a fichero;
- collect diagnostics before cleanup;
- kill descendants in finally;
- verify zero owned children.

No sleeps para readiness: polling con deadline.

## Diagnostics

```text
artifacts/testkit/<scenario>/<run-id>/
  command.txt
  environment-redacted.json
  stdout.log
  stderr.log
  events.jsonl
  ir.json
  process-tree.txt
  workspace-tree.txt
  journal-summary.json
  container.log
```
