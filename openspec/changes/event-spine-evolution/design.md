# Design: event-spine-evolution

## Closed execution, open observation

The canonical coordinator remains execution authority. Observation consumes emitted facts and must not
introduce Step-specific or observer-specific branching into the coordinator.

```text
execution -> EventPublisher -> EventHistory adapter
                               |-> POST_RUN verifier
                               |-> EventTail -> detached relay
```

## Separation

- OperationJournal: durable execution truth.
- EventHistory: observable structured history.
- ExecutionOutput: high-volume console/output stream.
- Assessment: verifier/policy findings about a run.

No one of these becomes a substitute for the others.

## Failure model

Observer failure is data (`ObserverHealth`/Assessment), not pipeline exception. Trace persistence failure
marks observation completeness; verification profile may fail acceptance. Only a future explicit
PolicyAdmission can intentionally deny a protected effect.

## Evolution seams

- local SQLite adapter today;
- CloudEvents mapping at external boundary;
- transport chosen by M4 needs after EVT-5 measurements;
- controller/Jenkins consume same envelope/ResourceRef.
