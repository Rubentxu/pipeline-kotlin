# EVT-3 Design — Event Harness

## Módulo nuevo: `v2/pipeline-event-harness`
Justificación (grounding Q5): depende de `pipeline-domain` (ResourceRef) y `pipeline-events`
(puertos de lectura, envelope, JsonEventLog). Ningún módulo interior depende de él; la CLI lo
enlaza en el adapter de aplicación. Añade snakeyaml SOLO aquí (frontend de deserialización).

## Paquetes
```
dev.rubentxu.pipeline.v2.harness/
  model/      EventContract, EventConstraint, EventSelector, FieldMatch,
              ExpectedOutcome, RelationScope, VerificationResult, EventViolation,
              AcceptanceOutcome, VerificationReport
  verify/     EventHarness (pure verifier), ProtocolGrammar (universal laws),
              EventPayloadAccessor (typed field extraction via JsonEventLog decode)
  codec/      YamlEventContractCodec (snakeyaml → typed ADT, fail-closed, versioned v1)
```

## Flujo
```
List<PipelineEventEnvelope> (orden de sequence, whole-run o sub-historia por cursor)
  → EventPayloadAccessor: envelope → (envelope, decoded DomainEvent) pares tipados
  → ProtocolGrammar: leyes universales → violaciones
  → EventContract.constraints: Exactly/Never/Before/Outcome → violaciones
  → VerificationResult (Valid | Invalid(violations con ventana acotada))
  → AcceptanceOutcome (PASSED si contract.expect == terminal outcome observado Y result Valid)
```

## Decisiones clave
1. **Payload tipado**: FieldMatch ADT cerrado; el accessor hace `when(decoded)` exhaustivo sobre
   los DomainEvent relevantes y expone solo campos con nombre de dominio. Sin Map<String,Any>.
2. **RelationScope**: `Global` | `SameSubject` | `SameBranch(branchKey)`. Before evalúa
   happens-before por sequence dentro del scope. `After` NO se duplica (inverso de Before).
3. **Partial order**: nunca orden global. Leyes de recurso (Started→Finished) agrupan por clave
   tipada (branchKey = parentStageIndex:branchIndex; stageKey = stageIndex; stepKey attempt).
4. **08 replay**: el contract se verifica sobre la SUB-historia sequence > cursor de la 1ª
   ejecución (EventCursor EVT-2), nunca por timestamps. El run.sh pasa la sub-historia.
5. **Counterexample 80/20**: para cada violación: índices de los eventos que dispararon la
   regla ± ventana acotada (máx ~6 eventos), con `Missing:`/`Unexpected:` textual.
6. **Determinismo**: sin relojes, sin aleatoriedad; salida idéntica ante la misma historia.
7. **Fitness gates** (pipeline-architecture-tests): harness no importa coordinator/journal/
   JDBC/Step execution; domain y producers no importan harness.

## Lo que NO se construye
observe(eventsHarness()) DSL, observers live, LTL/CEP, SPI de plugins, Event↔Journal
consistency, persistencia de AcceptanceOutcome como DomainEvent, TOML.

## CLI
`pipeline events verify --db <path> --run <runId> --contract <file>` en MainEventsCli;
reutiliza la apertura del store de `events`; exit 0/1.
