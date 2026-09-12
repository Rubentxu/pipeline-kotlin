# S2-A5 / G6 — CONTRACT_SUITE: `core.isUnix`

**Slice:** S2-A5 (`core.isUnix`; G0..G5 complete; G6 = contract certification)
**Gate:** G6 — canonical Step contract certification of the post-legacy registry Step
**Branch:** `cycle/lfc2-e1-s2-a5-g6-core-isunix-contract-suite`
**Base:** `main` @ `10d673bd` (S2-A5/G5 LEGACY_REMOVED + FF)
**Production code changes: ZERO** (certification only, per gate mandate)
**Date:** 2026-09-12

## State certified

```text
core.isUnix:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true
  CONTRACT_SUITE     = true   (this slice — 22/0/0/0, fresh XML)
  CERTIFIED          = false  (reserved for G8 with installed-distribution/UAT evidence)

StructuralFamily = Registry
legacy counters  = 7 / 7 / 7
```

## New suite

`CoreIsUnixStepContractSuiteTest` (pipeline-application) — **22 tests / 0 failures** (sha256
`d22207107fcaa57b8f4c2d80992d65593ba0bfda8250898e3c42b0bb96627264`). Following the gate
mandate: two sections, GENERIC STEP CONTRACT and ISUNIX SEMANTIC CONTRACT.

### GENERIC STEP CONTRACT ("is this a correct registry Step?")

1. **Identity**: key `core.isUnix`, duplicate registration fails closed.
2. **Contract completeness**: descriptor (name=`isUnix`, effects exactly `{READ_ONLY}`,
   `ReplayPolicy.MEMOIZED`), codecs present, requiredCapabilities present.
3. **Capabilities declaration**: declared set is EXACTLY `{PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY}`
   — the handler reaches the runtime capability bridge to read the single `System.getProperty("os.name")`
   and to publish the durable `UnixDetected` observation. The handler MUST never call System.getProperty
   or append events directly.
4. **Input codec round-trip**: unit input (`IsUnixInput` data object) → canonical empty envelope `{}`
   → decoded back to `IsUnixInput`.
5. **Input codec rejection**: non-object JSON values (`null`, primitives, arrays) fail closed
   before reaching the handler.
6. **Output codec round-trip**: `IsUnixOutput(true)` and `IsUnixOutput(false)` encode to
   `{"kind":"isUnix","isUnix":<bool>}` and round-trip back to the typed object.
7. **Output codec rejection**: non-isUnix kind and missing-isUnix-field fail closed.
8. **Canonical envelope**: the input codec envelope `{}` is byte-identical to the legacy dsl-v1
   `core.isUnix` envelope, preserving durable fingerprint identity for replay.
9. **Production registry resolution**: `CoreStepRegistryFactory.registry()` contains
   `core.isUnix` and resolves to the canonical `CoreIsUnixStep.definition` instance.
10. **Fresh factory consistency**: `CoreStepRegistryFactory.registry()` produces fresh per-call
    registries; both contain the same `core.isUnix` and resolve to the same definition instance.
11. **Capability admission (both available)**: `RegistryExecutionPreparation.prepare` with both
    declared capabilities available → `ExecutionPreparation.Ready`.
12. **Missing PLATFORM_IDENTITY**: admission is rejected, rejection reason names the missing capability.
13. **Missing EVENT_SINK**: admission is rejected, rejection reason names the missing capability.

### ISUNIX SEMANTIC CONTRACT ("does it keep its specific semantics?")

14. **Success via canonical coordinator**: `CanonicalDurableRunCoordinator.run` with a registry-resolved
    `OpaqueStepNode(payload={"schemaVersion":"dsl-v1","encoded":"{}"})` succeeds with one terminal
    `OperationStatus.SUCCEEDED` row journaled.
15. **Typed failure (handler exception)**: a handler that throws surfaces as `RunOutcome.Failure`
    (typed), not a silent success or silent swallow. Failure-class law preserved (per AGENTS.md
    REPLAY POLICY / Test law).
16. **Fresh durable**: first execution writes exactly one terminal SUCCEEDED operation row.
17. **Replay (MEMOIZED)**: a second `coord.run` at the SAME runId MUST NOT re-run the handler —
    observed via:
    - `StepStarted` event count stays at 1 across two runs
    - `UnixDetected` event count stays at 1 across two runs (durable observation is reproduced, not duplicated)
    - journal row count stays at 1 across two runs
18. **No-divergence by construction**: unit input (`IsUnixInput` → `{}`) yields a single durable
    fingerprint regardless of invocation timing. Two replays at the same runId both succeed —
    the durable path is structurally monotonic for this Step. This pins the property so future
    codec changes cannot silently introduce a divergent path.
19. **Observability**: every registry run emits a `StepStarted` + `StepFinished` lifecycle pair.
20. **UnixDetected event payload (frozen fields)**: the durable observation is byte-equivalent to
    the legacy `CanonicalIsUnixNodeDispatcher` (LEGACY_REMOVED in G5): uuid `eventId` (parses
    as UUID), `kind = "UnixDetected"`, monotonically increasing `sequence` (reassigned by
    `InMemoryEventStore`), exact `osName` (mirror of `PlatformIdentity`), exact `isUnix` (matches
    `UnixPlatformClassifier.classifyUnix(osName)`), exact `sha256` (hex SHA-256 of the osName).
21. **Real registry path scenario**: end-to-end registry seam exercised through the production
    `CanonicalDurableRunCoordinator` + `RegistryExecutionPreparation` + `RegistryExecutionBoundary`
    path with the canonical `CoreStepRegistryFactory.registry()` and BOTH declared capabilities.
    The suite does NOT depend on the DSL compiler (which currently lowers `isUnix()` to the legacy
    `StepSpec.IsUnix` — out of scope for G6, covered separately by `ScriptedIsUnixRuntimeTest` and
    `ScriptedIsUnixCompilerMappingTest`). This is the registry seam end-to-end without touching
    the scripting-api compiler.

### Architecture fitness

Delegated to `S3IsUnixLegacyRemovedFitnessTest` (G5) + `CoreIsUnixRegistryPrimaryFitnessTest`
(G4). Both remain green after this slice (no production changes).

## Coverage matrix (22 rows)

```
Row  Description                                                       Type
 1   identity — KEY + duplicate registration                            generic
 2   contract completeness — descriptor + 2 caps + MEMOIZED            generic
 3   input codec round-trip                                             generic
 4   input codec rejection (non-object)                                 generic
 5   output codec round-trip                                            semantic
 6   output codec rejection (non-isUnix kind)                           semantic
 6b  output codec rejection (missing isUnix)                            semantic
 7   canonical envelope == legacy dsl-v1                                generic
 8   production registry resolution                                     generic
 9   fresh factory consistency                                          generic
10   capability declaration EXACT set                                   generic
11   capability admission both available                                 generic
12   missing PLATFORM_IDENTITY rejection                                 generic
13   missing EVENT_SINK rejection                                       generic
14   success via canonical coordinator                                  semantic
15   typed failure (handler exception)                                  semantic
16   fresh durable (1 SUCCEEDED row)                                    semantic
17   replay MEMOIZED (no handler re-run, no duplicate UnixDetected)     semantic
18   no-divergence by construction                                      semantic
19   observability (StepStarted/StepFinished)                           semantic
20   UnixDetected event payload (frozen fields)                          semantic
21   real registry path scenario                                         semantic
```

## Test evidence (fresh run, XML-verified)

| Class | Tests | Failures | Errors | SHA256 |
| --- | --- | --- | --- | --- |
| `CoreIsUnixStepContractSuiteTest` | 22 | 0 | 0 | d22207107fcaa57b8f4c2d80992d65593ba0bfda8250898e3c42b0bb96627264 |

XML: `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CoreIsUnixStepContractSuiteTest.xml`

## Forbidden in G6 (held)

- **Zero production code changes** (per gate mandate). Only a new test file.
- No changes to `CoreIsUnixStep.kt`, the registry, the codec, the descriptor, or the handler.
- No new fitness or new sibling pin updates (those belong to G5/G4).
- No `core.pwd`, `core.milestone`, G3-A4.2 / ShellOperations work — separate slices.
- No FF to `main` without explicit user GO.

## Files changed (1)

```
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixStepContractSuiteTest.kt  (NEW, 723 lines)
```

## Next step

Await user GO before:
1. `git add -A`
2. `git commit -m "S2-A5/G6 — CONTRACT_SUITE: CoreIsUnixStepContractSuiteTest certifies core.isUnix"`
3. `git push -u origin cycle/lfc2-e1-s2-a5-g6-core-isunix-contract-suite`
4. STOP — no FF to main without explicit user approval.

After FF to main: continue with **S2-A5/G7 — `CoreIsUnixStep` StepContractSuite G7 row additions
(if any) + G8 (CERTIFIED) with installed-distribution evidence**.
