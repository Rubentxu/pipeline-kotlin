# ADR-0045: Pipeline Protocol Boundary Rule Implementation

## Status

Accepted

## Date

2026-08-25

## Supersedes

ADR-0045-farch012-namingclarification.md (fabricated; retracted)

## Context

The M4-R1 cycle design at
`cycle-artifacts/p-733fb505b5a6bd2d/m4-r1-proto-governance/design.md`
(the authoritative canonical source, resolved by the SDDK cycle machinery)
establishes D7: a single boundary fitness rule in `:pipeline-architecture-tests`
with three legs and three synthetic violation sub-tests.

The design specifies the test class must be named `PipelineProtocolBoundaryTest`
(File Changes table, though abbreviated, implies this canonical name).
F-ARCH-012 was already registered in `ARCHITECTURE_FITNESS.md` with semantics
"Documentation examples compile" — a different rule entirely.
Therefore this rule is registered as **F-ARCH-013**.

Additionally, investigation revealed that the prior token-based import scanner
(`findImports`) failed to detect real-world imports because:

1. `okhttp` token cannot match `okhttp3.OkHttpClient` (no `.` continuation)
2. `java.net.WebSocket` does not exist in the JDK; the correct package
   is `java.net.http.WebSocket`

The fix uses **prefix matching**: an import line matches if its FQCN
equals the prefix OR starts with `prefix + "."`.

## Decision

### Rule Registration

Register **F-ARCH-013 — Protocol module transport neutrality** in
`ARCHITECTURE_FITNESS.md`.

### Test Class

Create `PipelineProtocolBoundaryTest.kt` in
`v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/`.

Three functional legs:
1. Module dependency guard (`:pipeline-application`, `:pipeline-scripting-kotlin24`, `:pipeline-testkit` forbidden)
2. Proto schema surface (8 files exist, required `java_package` + `java_multiple_files` options per ADR-0044)
3. **Transport-neutral import scan** — scans BOTH `src/main/kotlin` AND `src/test/kotlin`
   (stronger than design minimum which only required `src/test/kotlin`)

### Prefix-Matching Scanner

New function `SourceScanner.findForbiddenImportPrefixes(root, prefixes)`:
- Parses `^import\s+(.+?)(?:\s+as\s+\w+)?\s*;?\s*$` to extract FQCN
- Handles alias imports (`import x.y as z`) by matching the original FQCN
- Skips `//` and `/* */` comment lines
- Matches if `fqcn == prefix` OR `fqcn.startsWith("$prefix.")`

### Forbidden Prefix List (with rationale)

| Prefix | Rationale |
|--------|----------|
| `java.net.Socket` | Direct TCP socket — bypasses all protocol framing |
| `java.net.ServerSocket` | Server socket — listen/bind semantics belong to transport |
| `java.net.http` | JDK 11+ HTTP client package; covers `HttpClient`, `WebSocket` |
| `okhttp3` | Versioned root for Square's OkHttp 3/4 |
| `okhttp` | Unversioned token — catches OkHttp without numeric prefix |
| `io.ktor.client` | Ktor HTTP client |
| `io.ktor.server` | Ktor server |
| `io.grpc` | gRPC `ManagedChannel` — transport binding |
| `javax.websocket` | JSR-356 client |
| `jakarta.websocket` | Jakarta WebSocket (javax replacement) |
| `org.java_websocket` | Java-WebSocket library (org.java_websocket package) |
| `org.springframework.web.socket` | Spring WebSocket handler |

### Changelog — Retraction of Prior Fabricated Claims

The previous `ADR-0045-farch012-namingclarification.md` was generated without
access to the canonical cycle design path and made the following **fabricated claims**:

- Stated no `D7` designation exists in `DESIGN.md` (FALSE — D7 exists in
  the cycle design artifact at the canonical path)
- Stated `PipelineProtocolBoundaryTest` does not exist and no rename is needed
  (PARTIALLY FALSE — the canonical design requires this exact name; name was
  missing from implementation)
- Concluded no divergence exists (PARTIALLY FALSE — there was a naming gap
  that required correction)

These claims are hereby **retracted** in full.  The correct facts are
documented above in this ADR.

## References

- [ARCHITECTURE_FITNESS.md](../06-quality/ARCHITECTURE_FITNESS.md) (F-ARCH-013 registration)
- [ADR-0044: Proto Package Structure Amendment](ADR-0044-proto-package-amendment.md)
- [ADR-0043: Proto-Governance](ADR-0043-proto-governance.md)
- Cycle design: `cycle-artifacts/p-733fb505b5a6bd2d/m4-r1-proto-governance/design.md`
