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

### 1. Seven-Topic Schema Structure

The `pipeline-protocol` module contains **seven canonical topic schemas** plus
one supporting infrastructure file:

| Schema | Purpose | Status |
|--------|---------|--------|
| `worker_hello.proto` | Worker registration and capability exchange | Topic |
| `negotiated_session.proto` | Session negotiation and configuration | Topic |
| `commands.proto` | Controller-to-worker command messages | Topic |
| `events.proto` | Worker-to-controller event messages | Topic |
| `ack_replay.proto` | Acknowledgement and replay semantics | Topic |
| `leases.proto` | Lease management and fencing tokens | Topic |
| `heartbeat.proto` | Liveness detection and backpressure | Topic |
| `common.proto` | Shared types (LeaseContext, Capabilities, Version) | Infrastructure |

**Note:** The "seven-schema" design refers to the seven canonical protocol topics.
`common.proto` provides shared type definitions to avoid duplicate message declarations
across topics and is considered supporting infrastructure, not a protocol topic.

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

### 4. Immutable Message Design (D3 Constraints)

Every `.proto` file **must** declare the following (D3 design constraints):

```proto
syntax = "proto3";

package dev.rubentxu.pipeline.v2.protocol;

option java_package = "dev.rubentxu.pipeline.v2.protocol";
option java_multiple_files = true;
option java_outer_classname = "<SchemaName>Protos";
```

| Option | Required Value | Rationale |
|--------|---------------|-----------|
| `java_package` | `"dev.rubentxu.pipeline.v2.protocol"` | Namespace alignment with Kotlin package |
| `java_multiple_files` | `true` | Avoid monolithic generated classes |
| `java_outer_classname` | Per-schema name (e.g., `CommandsProtos`) | IDE discoverability |

**Forbidden:** Custom options outside `dev.rubentxu.pipeline.v2.protocol` namespace.

### 5. Version Pinning

All protobuf dependencies are pinned in `v2/gradle/libs.versions.toml`:

```toml
[versions]
protobuf = "3.25.5"

[libraries]
protobuf-kotlin-lite = { module = "com.google.protobuf:protobuf-kotlin-lite", version.ref = "protobuf" }

[plugins]
protobuf = { id = "com.google.protobuf", version.ref = "protobuf" }
```

### 6. Binary Compatibility

`GoldenBinaryCompatibilityTest` verifies round-trip serialization for all schemas:
- Binary roundtrip preserves data
- SHA-256 fingerprinting for deterministic verification
- Size within `MAX_MESSAGE_SIZE_BYTES` governance limit

### 7. Governance Constraints

- `ProtocolGovernance` object provides infrastructure constants:
  - `MAX_MESSAGE_SIZE_BYTES = 10 * 1024 * 1024` (10MB)
  - `DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30`
  - `MAX_RECONNECT_ATTEMPTS = 5`
  - `LEASE_TIMEOUT_SECONDS = 300`
- These are parameter values for negotiation, not operational behavior

### 6. Golden Fixture Harness (E5-01 Compliant Subset)

- `GoldenFixtureHarness` object provides factory methods for **schema declaration
  verification only**
- Factory methods create message builders with preset fields for testing schema structure
- E5-08 operational factories (health aggregation, lease management) are excluded:
  - `createHeartbeat()` — excluded (E5-08)
  - `createLeaseGrant()` — excluded (E5-08)
  - `ProtocolEvent.HeartbeatSent/LeaseGranted/etc.` — excluded (E5-08)

## Consequences

### Positive

- Clear separation between protocol schema and application logic
- Protobuf code generation integrated into Gradle build
- Architecture tests enforce module dependency rules
- Golden fixtures enable reproducible test scenarios
- Strict boundary between E5-01 schema declarations and E5-02..E5-10 operational semantics

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

**E5-01 Scope:** Schema declarations only. Any operational behavior
(health aggregation, lease management, liveness detection) is E5-08 and excluded.

## References

- [WORKER_PROTOCOL.md](../../03-specifications/WORKER_PROTOCOL.md)
- [ADR-0036: V2 Package Namespace Convention](../ADR-0036-v2-package-namespace-convention.md)
