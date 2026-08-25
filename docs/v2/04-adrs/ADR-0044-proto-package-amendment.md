# ADR-0044: Proto Package Structure Amendment for E5-01

## Status

Accepted

## Date

2026-08-25

## Context

ADR-0043 §4 (Immutable Message Design) established D3 constraints for proto package structure.
Initial interpretation assumed per-topic packages (e.g., `dev.rubentxu.pipeline.v2.protocol.worker_hello`).
Implementation chose a shared package approach (`dev.rubentxu.pipeline.v2.protocol`) for all schemas.

This ADR formally amends the design to document the shared package decision.

## Decision

### Shared Package Approach (E5-01)

For E5-01 (schema declarations only), all proto schemas use a **shared package**:

```proto
package dev.rubentxu.pipeline.v2.protocol;
option java_package = "dev.rubentxu.pipeline.v2.protocol";
```

This applies to all 8 proto files:
- `common.proto`
- `worker_hello.proto`
- `negotiated_session.proto`
- `commands.proto`
- `events.proto`
- `ack_replay.proto`
- `leases.proto`
- `heartbeat.proto`

### Rationale

1. **Simplicity**: Single package reduces import complexity in generated code
2. **Schema-only scope**: E5-01 produces no operational code, only declarations
3. **Avoids breaking changes**: Per-topic packages would require significant refactoring
4. **Consistency**: All schemas share the same namespace, matching the Kotlin package structure

### Future Consideration (E5-02..E5-10)

If future phases require per-topic packages (e.g., for service generation or isolation),
the following migration path is recommended:

1. Create ADR for the migration
2. Update each proto file with per-topic `java_package`
3. Run codegen verification
4. Update all consuming code

## Relationship to D3

This ADR **amends** the original D3 constraint interpretation from WORKER_PROTOCOL.md.
The **intent** of D3 (namespace alignment with Kotlin package) is **preserved** by using the
shared package. The **form** (per-topic vs shared) is adjusted to match E5-01 scope.

## References

- [ADR-0043: Proto-Governance](ADR-0043-proto-governance.md)
- [WORKER_PROTOCOL.md](../../03-specifications/WORKER_PROTOCOL.md)
