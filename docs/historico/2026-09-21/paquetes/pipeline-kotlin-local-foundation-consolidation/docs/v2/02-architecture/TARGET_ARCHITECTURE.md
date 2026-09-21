# Target architecture

```text
                         pipeline-cli
                      (composition root)
                              |
               +--------------+--------------+
               |                             |
        plugin resolution              script compiler
     manifest + lock + verify          .pipeline.kts/K2
               |                             |
               +--------------+--------------+
                              v
                    Canonical Pipeline IR
                              |
                       ModelValidator
                              |
                         RunCoordinator
                              |
                       ExecutionPlanner
                              |
                       StepDispatcher
                              |
                   typed StepHandler<T>
                              |
     +------------+-----------+-----------+-------------+
     |            |           |           |             |
 Process      Workspace   Environment  Credentials    Output
 Service       Service      Service      Service      Service
     |            |           |           |             |
     +------------+-----------+-----------+-------------+
                              |
                       local adapters

 EventStore + OperationJournal + RunOutputStore + ArtifactStore
                              |
                     GraphProjector (derived)
```

## Layer responsibilities

### Domain
Pure identifiers, canonical IR, outcome/failure taxonomy, durable semantics, event contracts and policy types. No filesystem/process/DB/plugin loader implementation.

### Application
Use cases: compile/validate orchestration, `RunCoordinator`, planner, dispatcher contracts, capability ports, output/artifact/event/journal ports. Depends inward only.

### Plugin API
Stable extension contracts, annotations, descriptors, capability identifiers and schemas. It does not depend on local adapters.

### Local runtime adapters
Process runtime, filesystem workspace, local credential provider, SQLite/file event/output stores, local artifact store, clock/platform implementation, sandbox backends.

### CLI
Argument parsing, config discovery, plugin resolution composition, adapter wiring and presentation.

## Fundamental invariant

A pipeline step performs an effect only through a capability supplied by the runtime. There is no `ProcessBuilder`, `System.getenv`, filesystem singleton, credential store construction or output sink construction in plugin handlers or application use cases.
