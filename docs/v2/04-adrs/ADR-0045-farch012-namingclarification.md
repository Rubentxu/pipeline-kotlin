# ADR-0045: FArch012 Test Naming Clarification

## Status

Accepted

## Date

2026-08-25

## Context

Review of M4-R1 hotfix identified potential naming divergence between canonical design
expectations and implemented test names for the protocol module boundary layer.

## Investigation Findings

### D7 Reference
No `D7` designation exists in `docs/v2/DESIGN.md` or any architecture document.
DESIGN.md invariant #7 states: "No stable Step without effects/replay/capability metadata."
This maps to F-ARCH-010 in `ARCHITECTURE_FITNESS.md`, not to a protocol boundary test.

### PipelineProtocolBoundaryTest
No test named `PipelineProtocolBoundaryTest` exists in the codebase. The protocol
module boundary is covered by `FArch012ProtocolModuleStructureTest` which verifies:

1. Protocol module depends only on allowed modules
2. All required proto schema files exist
3. Each proto file declares required package and options
4. Each topic proto file has corresponding golden fixtures
5. No forbidden network imports in protocol test sources (new leg-3)

### Naming Convention
The `FArch012*` naming follows the established F-ARCH-NNN pattern from
`ARCHITECTURE_FITNESS.md`. No rename is required.

## Decision

No divergence between design expectations and implementation. The protocol boundary
is properly governed by `FArch012ProtocolModuleStructureTest` following the
canonical naming convention.

## References

- [ARCHITECTURE_FITNESS.md](../06-quality/ARCHITECTURE_FITNESS.md)
- [DESIGN.md](../DESIGN.md)
- [FArch012ProtocolModuleStructureTest.kt](../../v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/FArch012ProtocolModuleStructureTest.kt)
