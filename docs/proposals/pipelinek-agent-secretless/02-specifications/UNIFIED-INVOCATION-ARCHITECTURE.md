# Arquitectura — Unified Invocation sobre la espina existente

## 1. Regla estructural

No existe un runtime de CLI, otro de DSL y otro de MCP.

```text
┌──────────────┐
│ pipeline.kts │
└──────┬───────┘
       │
┌──────▼───────┐      ┌──────────────┐
│ CLI inline   │─────▶│ Invocation   │
└──────────────┘      │ Facade       │
                      └──────┬───────┘
┌──────────────┐             │
│ MCP / skill  │─────────────┘
└──────────────┘
                              ▼
                    canonical Step IR
                              │
                              ▼
                       StepRegistry
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
             core.sh    scm-git.checkout  plugins...
                │
                ▼
         ShellOperations
                │
                ▼
 ShExecution / DurableShellExecutor
```

## 2. Invocation model

La fachada puede modelar intención sin convertirse en nueva autoridad durable.

```kotlin
sealed interface InvocationTarget {
    data class RegisteredStep(
        val stepKey: PluginStepId,
        val input: EncodedStepValue,
    ) : InvocationTarget

    data class Command(
        val executable: String,
        val args: List<String>,
    ) : InvocationTarget

    data class Shell(
        val script: String,
    ) : InvocationTarget
}
```

`RegisteredStep` baja directamente al registry. `Shell` baja a `core.sh`. `Command` **en el primer tren también baja a `core.sh`**, mediante `CanonicalShellArgvEncoder`.

## 3. Por qué no `core.exec` todavía

Un `core.exec` independiente introduciría preguntas nuevas en todas las propiedades que ya son difíciles y costosas:

- operation identity;
- wrapper lifecycle;
- heartbeat;
- transcript;
- timeout;
- cancellation;
- process-tree kill;
- replay/reconciliation;
- workspace;
- env;
- redaction;
- returnStdout/value channel;
- fingerprints.

Duplicar esas decisiones es un riesgo arquitectónico superior al beneficio inicial.

## 4. Command facade shell-backed

El modo command ofrece argv seguro al consumidor, pero la implementación inicial conserva la shell durable.

```text
["gh", "release", "create", "v1.0", "a b.zip"]
                 │
                 ▼
CanonicalShellArgvEncoder
                 │
                 ▼
'gh' 'release' 'create' 'v1.0' 'a b.zip'
                 │
                 ▼
core.sh
```

Requisitos:

- encoder total y por plataforma;
- inicialmente POSIX/Linux si no existe equivalente probado para Windows;
- ningún string de secreto en argv;
- quoting property-tested;
- metadata/evento indica `SHELL_BACKED_ARGV` para no prometer una primitiva shell-free;
- mismo coordinator y journal que `sh`.

El nombre de CLI final (`command`, `invoke`, `exec`) puede decidirse en spike UX. `exec` no debe sugerir semántica distinta si todavía usa shell internamente.

## 5. Inline generic Step

`pipelinek step` crea un envelope canónico y lo entrega al mismo `StepRegistry` que una pipeline compilada.

```text
CLI schema input
     │
     ▼
StepDefinition.inputCodec
     │
     ▼
RegistryStepSpec / equivalent canonical node
     │
     ▼
CanonicalDurableRunCoordinator
```

No existe handler especial para inline.

## 6. Ephemeral flow

Un modo futuro `pipelinek flow` compone varios Steps sin crear un archivo en el repo.

```text
pipelinek flow
   │
   ▼
Transient PipelineSpec
   │
   ▼
Dsl-independent compiler/assembler
   │
   ▼
same coordinator
```

Estado y evidencias van a un control root en usuario, por ejemplo:

```text
${XDG_STATE_HOME:-~/.local/state}/pipelinek/agent-runs/<run-id>/
```

No a `.pipelinek/` en el proyecto por defecto.

## 7. Capabilities

La fachada nunca salta admisión. Toda invocación debe construir el mismo set de capabilities que un `pipelinek run` equivalente.

Capabilities nuevas propuestas:

```text
credential.use
credential.project
execution.command-facade
network.egress
profile.resolve
output.project
```

No se deben convertir en bypasses del registry.

## 8. Future process runtime refactor

Fuera del primer tren:

```text
                   DurableProcessRuntime
                       ▲          ▲
                       │          │
               ShellInvocation  ArgvInvocation
                       ▲          ▲
                     core.sh   future core.exec
```

Precondiciones:

1. characterization completa de `DurableShellExecutor`;
2. extracción sólo de responsabilidades realmente comunes;
3. `core.sh` sigue produciendo payload/fingerprint/eventos equivalentes;
4. replay y kill/resume certificados;
5. no merge si requiere un segundo journal o coordinator.

