# ADR-0043: Proto-Governance for Worker Protocol Module

## Status

Accepted

## Date

2026-08-25

## Context

The M4-R1 milestone introduces a proto-governance framework for the Worker Protocol
module (`pipeline-protocol`). This ADR establishes the architectural decisions
governing the protobuf schema design, code generation, and module boundaries for
the immutable protocol package.

## Decision

We will implement the following decisions:

### 1. Seven-Schema Module Structure

The `pipeline-protocol` module contains exactly seven protobuf schema files:

| Schema | Purpose |
|--------|---------|
| `worker_hello.proto` | Worker registration and capability exchange |
| `negotiated_session.proto` | Session negotiation and configuration |
| `commands.proto` | Controller-to-worker command messages |
| `events.proto` | Worker-to-controller event messages |
| `ack_replay.proto` | Acknowledgement and replay semantics |
| `leases.proto` | Lease management and fencing tokens |
| `heartbeat.proto` | Liveness detection and backpressure |

### 2. Protobuf Code Generation

- Use `protobuf-kotlin-lite` runtime (no descriptor introspection)
- Generate both Java and Kotlin code via `com.google.protobuf` plugin
- JVM toolchain pinned to Java 21
- Language version `KOTLIN_2_0`

### 3. Module Dependency Rules

- `pipeline-protocol` depends only on `pipeline-domain`
- `pipeline-protocol` MUST NOT depend on `pipeline-application`, `pipeline-testkit`,
  or any scripting module beyond `pipeline-scripting-api`
- This boundary ensures the protocol is portable across execution contexts

### 4. Immutable Message Design

- All protocol messages use `proto3` syntax for forward compatibility
- `java_package` set to `dev.rubentxu.pipeline.v2.protocol`
- `java_multiple_files = true` to avoid monolithic generated classes
- `java_outer_classname` set per-schema for IDE discoverability

### 5. Governance Constraints

- `ProtocolGovernance` object enforces:
  - `MAX_MESSAGE_SIZE_BYTES = 10 * 1024 * 1024` (10MB)
  - `DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30`
  - `MAX_RECONNECT_ATTEMPTS = 5`
  - `LEASE_TIMEOUT_SECONDS = 300`

### 6. Golden Fixture Harness

- `GoldenFixtureHarness` object provides factory methods for all message types
- Test resources contain canonical JSON fixtures for each schema
- Fixtures are deterministic and version-controlled

## Consequences

### Positive

- Clear separation between protocol schema and application logic
- Protobuf code generation integrated into Gradle build
- Architecture tests enforce module dependency rules
- Golden fixtures enable reproducible test scenarios

### Negative

- Protocol changes require regenerating Kotlin code (handled by Gradle task)
- Proto3 message immutability requires careful schema versioning

## Exclusions (E5-02..E5-10)

The following are explicitly excluded from this implementation:
- E5-02: gRPC transport binding (future phase)
- E5-03: Worker gateway service implementation (future phase)
- E5-04: Session persistence layer (future phase)
- E5-05: Certificate rotation mechanism (future phase)
- E5-06: Multi-worker load balancing (future phase)
- E5-07: Protocol negotiation replay cache (future phase)
- E5-08: Worker health score aggregation (future phase)
- E5-09: Dynamic protocol version upgrade (future phase)
- E5-10: Cross-datacenter replication (future phase)

## References

- [WORKER_PROTOCOL.md](../../03-specifications/WORKER_PROTOCOL.md)
- [ADR-0036: V2 Package Namespace Convention](../ADR-0036-v2-package-namespace-convention.md)
