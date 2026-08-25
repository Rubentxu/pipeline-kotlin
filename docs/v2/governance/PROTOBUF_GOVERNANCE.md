# Protobuf Governance

This document establishes the immutable rules for all `.proto` files in
`pipeline-protocol/src/main/proto/`.

## Schema Files

| File | Canonical Topic | Infrastructure |
|------|-----------------|----------------|
| `worker_hello.proto` | Worker registration and capability exchange | — |
| `negotiated_session.proto` | Session negotiation and configuration | — |
| `commands.proto` | Controller-to-worker command messages | — |
| `events.proto` | Worker-to-controller event messages | — |
| `ack_replay.proto` | Acknowledgement and replay semantics | — |
| `leases.proto` | Lease management and fencing tokens | — |
| `heartbeat.proto` | Liveness detection and backpressure | — |
| `common.proto` | Shared types across all schemas | Infrastructure |

## Package and Option Constraints (D3)

Every `.proto` file **must** declare:

```proto
syntax = "proto3";

package dev.rubentxu.pipeline.v2.protocol;

option java_package = "dev.rubentxu.pipeline.v2.protocol";
option java_multiple_files = true;
```

**Note:** Per ADR-0044, E5-01 uses a **shared package** approach where all schemas
use the same `java_package`. Future phases (E5-02..E5-10) may adopt per-topic packages.

### Option Rules

| Option | Required Value | Rationale |
|--------|---------------|-----------|
| `java_package` | `"dev.rubentxu.pipeline.v2.protocol"` | Shared namespace (ADR-0044) |
| `java_multiple_files` | `true` | Avoid monolithic generated classes |
| `java_outer_classname` | Per-schema name (e.g., `CommandsProtos`) | IDE discoverability |

### Forbidden Options

- `java_outer_classname` with value equal to a message name (causes duplicate class errors)
- `option java_generate_equals_and_hash = true` (not supported in proto3 lite)
- Any custom option outside `dev.rubentxu.pipeline.v2.protocol` namespace

## Message Naming

- CamelCase message names (e.g., `WorkerHello`, `NegotiatedSession`)
- Suffix enum types with the parent concept (e.g., `CommandType`, `EventType`)
- Field names: lowercase with underscores (proto3 convention)

## Shared Types (common.proto)

The following types **must** be defined in `common.proto` and imported where needed:

- `LeaseContext` — used across commands, events, ack_replay, leases schemas
- `Capabilities` — used across worker_hello and negotiated_session
- `Version` — used in negotiated_session

Messages that are schema-specific (e.g., `StepCompleted` belongs in `events.proto`)
**must not** be duplicated or redefined in `common.proto`.

## Import Rules

- Imports **must** use the explicit `import "filename.proto"` form
- No wildcard imports
- `common.proto` may be imported by any schema
- No circular dependencies

## Field Number Constraints

- Field numbers 1-15 use 1 byte encoding (most frequent fields)
- Field numbers 16-2047 use 2+ bytes
- Field numbers 19000-19999 are reserved for protobuf internal use
- Never reuse a field number within a message

## Version Pinning

| Dependency | Version | Location |
|------------|---------|----------|
| `com.google.protobuf:protoc` | pinned in `libs.versions.toml` | v2/gradle/libs.versions.toml |
| `com.google.protobuf:protobuf-kotlin-lite` | must match protoc | pipeline-protocol/build.gradle.kts |

## Binary Compatibility

All schema changes require:
1. A `.pb` golden fixture (binary protobuf encoded)
2. A `SHA-256` checksum of the fixture
3. A `GoldenBinaryCompatibilityTest` that verifies round-trip serialization

## References

- [ADR-0043: Proto-Governance](../04-adrs/ADR-0043-proto-governance.md)
- [ADR-0044: Proto Package Structure Amendment](../04-adrs/ADR-0044-proto-package-amendment.md)
- [WORKER_PROTOCOL.md](../03-specifications/WORKER_PROTOCOL.md)
