# EVT-3 Implementation Plan

T1. Module scaffold `v2/pipeline-event-harness` (build.gradle.kts, settings include):
    deps pipeline-domain, pipeline-events, snakeyaml, kotlinx-serialization-json.
T2. model/ ADTs: EventContract, EventConstraint, EventSelector, FieldMatch,
    RelationScope, VerificationResult, EventViolation, AcceptanceOutcome, VerificationReport.
T3. verify/EventPayloadAccessor: envelope→DomainEvent typed field extraction
    (exhaustive when over relevant kinds).
T4. verify/EventHarness + ProtocolGrammar: constraints + universal laws, partial
    order by typed keys, bounded counterexample windows.
T5. codec/YamlEventContractCodec v1: golden fixtures from examples/contracts (updated
    schema), unknown version/constraint fail-closed.
T6. HF0 tests: ADT laws, selector matching, partial order, grammar valid/invalid,
    counterexample slicing, YAML decode round, fail-closed.
T7. Mutation tests: 5 familias sobre historias reales capturadas (fixtures JSON).
T8. CLI: `pipeline events verify --db --run --contract`.
T9. Capture real histories 01–10 (installDist), convert sidecars 07–10 to v1 schema,
    differential parity harness-verdict vs run.sh assertions (07–10) x2 consecutive.
T10. run.sh: add harness verification step (parity mode; legacy assertions intact).
T11. Fitness gates in pipeline-architecture-tests; update EVENT_SPINE_EVOLUTION.md.
T12. Full validation ladder + P4-EX green + receipts.
