# SPEC-LFC-019 — Step and Plugin Certification

**Status:** proposed

## Principle

La misma suite certifica Step core y Step externo.

## Certification rows

### C01 Identity
- StepKey válido.
- namespace válido.
- duplicate StepKey rejected.

### C02 Contract completeness
- execution location.
- effects.
- replay.
- body.
- capabilities.
- failure.
- observability.

### C03 Input codec
- round-trip.
- schema version.
- malformed payload typed failure.

### C04 Output codec
- round-trip.
- replay restores equivalent typed output.

### C05 DSL façade
- positive compile.
- parameters map once.
- defaults preserved.
- source location preserved.

### C06 Negative DSL
- receiver invalid rejected.
- fake runtime-return declarative impossible.
- invalid value combinations rejected.

### C07 IR
- exactly one Invoke.
- correct StepKey.
- complete input.
- correct body shape.
- no duplicate emission.

### C08 Registry
- discoverable.
- unknown/incompatible fail closed.
- no core edit for external plugin.

### C09 Capabilities
For each required capability:
- present => executes.
- missing => side-effect counter remains zero.

### C10 Handler success
- typed result.
- expected effects.
- lifecycle events.

### C11 Failure
- expected failures map to FailureKind.
- no incidental exception as normal domain flow.

### C12 Cancellation
- cooperative cancellation.
- descendants killed.
- resources released.

### C13 Replay
Validate declared policy:
- reuse;
- rerun;
- never;
- divergence fail closed.

### C14 Observability
- generic lifecycle.
- semantic events when required.
- output contract.

### C15 Bodies
- shape validation.
- invocation count.
- re-entry through engine.
- parent context restored.

### C16 Security
If applicable:
- secret not persisted.
- temp materials cleaned.
- nested scope restore.

### C17 Real distribution
- T2 forked run.
- real plugin discovery.
- no test classpath leakage.

### C18 Jenkins compatibility
- metadata.
- matching behavior fixture.
- differences explicit.

### C19 Scenario
At least one executable `.pipeline.kts` scenario.

## Plugin-level certification

Adicionalmente:

- manifest;
- API/runtime compatibility;
- independent Gradle build;
- dependency isolation;
- generated resources;
- actionable incompatibility diagnostics.

## Independent reference fixture

Flujo obligatorio:

```text
publish SDK to temp Maven repo
 -> independent plugin build
 -> plugin JAR
 -> real distribution
 -> install/load plugin
 -> compile pipeline.kts
 -> execute
 -> assert result/events/replay
```

## Receipt

Ejemplo:

```json
{
  "stepKey": "example.uppercase",
  "contractVersion": 1,
  "status": "CERTIFIED",
  "cases": {
    "C01": "PASS",
    "C02": "PASS"
  }
}
```
