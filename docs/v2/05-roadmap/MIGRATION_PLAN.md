# Migration Plan V1 → V2

## Principio

Migrar conceptos valiosos, no arrastrar implementaciones por inercia.

## Mapa inicial

| Área V1 | Acción | Destino V2 |
|---|---|---|
| `DslManager` mínimo | RETIRE | scripting/application use cases |
| `PipelineDslEngine` | ADAPT concepts / REWRITE | `pipeline-scripting-kotlin24` |
| `PipelineBlock/Stages/Steps` | REWRITE | declarative builders + durable runtime |
| `StageExecutor` closures | RETIRE | Stage definition + runtime state |
| runners duplicados | RETIRE | one `RunCoordinator` + WorkerRuntime |
| `IPipelineContext`/ServiceLocator | RETIRE | context parameters + ports |
| Koin wiring | ADAPT only at composition root | adapter DI |
| UnifiedStepRegistry reflection | RETIRE primary path | generated StepDescriptors |
| `@Step` annotations | KEEP concept | new Step SDK |
| FIR/IR plugin | SPIKE/OPTIONAL | diagnostics only |
| DomainEvent concepts | KEEP/REWRITE schemas | protobuf/domain events v2 |
| in-memory EventBus | RETIRE as truth | durable journal + projections |
| security abstractions | KEEP intent | OS/container security profiles |
| SecurityManager sandbox | RETIRE | container sandbox |
| Docker/K8s adapters | ADAPT selectively | capability adapters |
| pipeline-config YAML maps | REWRITE | typed resource manifests |
| LSP regex analyzer | RETIRE | generated metadata + Kotlin tooling |
| CLI concepts | ADAPT | V2 CLI |

## Compatibilidad temporal

No se intenta ejecutar un mismo run mezclando V1 y V2 Steps. La unidad de migración es Pipeline Definition/run mode:

```text
engine: v1 | v2
```

## Plan de retirada

1. marcar legacy packages;
2. evitar nuevas features V1 salvo fixes críticos;
3. introducir samples equivalentes V2;
4. migrar tests de comportamiento;
5. medir usage;
6. eliminar sólo después del milestone de reemplazo.
