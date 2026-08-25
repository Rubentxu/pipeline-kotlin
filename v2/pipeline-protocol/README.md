# pipeline-protocol — Proto-Governance Module

## Overview

The `pipeline-protocol` module defines the immutable protobuf schemas for the
Worker Protocol communication between the control plane and worker nodes.

## Schema Files (7 Total)

| File | Description |
|------|-------------|
| `worker_hello.proto` | Worker registration, capabilities, system info |
| `negotiated_session.proto` | Session negotiation and configuration |
| `commands.proto` | Controller-to-worker command messages |
| `events.proto` | Worker-to-controller event messages |
| `ack_replay.proto` | ACK/replay semantics for reliability |
| `leases.proto` | Lease management and fencing tokens |
| `heartbeat.proto` | Liveness detection and backpressure |

## Governance

All protocol messages are governed by `ProtocolGovernance`:

- `MAX_MESSAGE_SIZE_BYTES`: 10 MB
- `DEFAULT_HEARTBEAT_INTERVAL_SECONDS`: 30
- `MAX_RECONNECT_ATTEMPTS`: 5
- `LEASE_TIMEOUT_SECONDS`: 300

## Architecture Rules

See [ADR-0043: Proto-Governance for Worker Protocol Module](../../04-adrs/ADR-0043-proto-governance.md)

## Build

```bash
./gradlew -p v2/pipeline-protocol build
```

## Test

```bash
./gradlew -p v2/pipeline-protocol test
```
