# S2-A6 / G6 — CONTRACT_SUITE: `core.pwd`

**Slice:** S2-A6 (`core.pwd`; G0..G5 complete; G6 = contract certification)
**Gate:** G6 — canonical Step contract certification of the post-legacy registry Step
**Branch:** `cycle/lfc2-e1-s2-a6-g6-core-pwd-contract-suite`
**Base:** `main` @ `04984923` (S2-A6/G5 LEGACY_REMOVED + corrective + FF)
**Production code changes: ZERO** (certification only, per gate mandate)
**Date:** 2026-09-12
**Pattern:** Mirrors `S2_A5_CORE_ISUNIX_G6_CONTRACT_CERTIFICATION_RECEIPT.md` exactly.

## State certified

```text
core.pwd:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true
  CONTRACT_SUITE     = true   (this slice — 23/0/0, fresh XML)
  CERTIFIED          = false  (reserved for G8 with installed-distribution/UAT evidence)

StructuralFamily = Registry
legacy counters  = 6 / 6 / 6
```

## New suite

`CorePwdStepContractSuiteTest` (pipeline-application) — **23 tests / 0 failures** (sha256
`94dbd039282cd9df`). Following the gate mandate: two sections, GENERIC STEP CONTRACT and
PWD SEMANTIC CONTRACT.

### GENERIC STEP CONTRACT ("is this a correct registry Step?")

1. **Identity**: key `core.pwd`, duplicate registration fails closed.
2. **Contract completeness**: descriptor (name=`pwd`, effects exactly `{READ_ONLY}`,
   `ReplayPolicy.MEMOIZED`), codecs present, requiredCapabilities present.
3. **Capabilities declaration**: declared set is EXACTLY `{WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY}`
   — the handler reaches the runtime capability bridge to observe the canonical workspace
   identity and to publish the durable `PwdResolved` observation. The handler MUST never reach
   `user.dir` / `controlDirRoot` or append events directly.
4. **Input codec round-trip**: `PwdInput(tmp=false)` → canonical envelope
   `{"kind":"pwd","tmp":false}` → decoded back to the typed object.
5. **Input codec rejection (foreign kind)**: non-pwd kinds fail closed before reaching the handler.
6. **Input codec rejection (PWD_TMP_TRUE_DISPOSITION)**: `{"kind":"pwd","tmp":true}` fails
   closed at decode with a message naming the disposition blocker — the StepKey authority
   cannot be abused to serve the non-deterministic legacy `tmp=true` path.
7. **Output codec round-trip**: `PwdOutput(path)` encodes to `{"kind":"pwd","path":...}` and
   round-trips back to the typed object (TYPED_RUNTIME_OUTPUT, S2-A6/G2 decision D2).
8. **Output codec rejection**: non-pwd kind and missing-path-field fail closed.
9. **Canonical envelope**: the input codec envelope `{"kind":"pwd","tmp":false}` is
   byte-identical to the legacy dsl-v1 `core.pwd` envelope, preserving durable fingerprint
   identity for replay.
10. **Production registry resolution**: `CoreStepRegistryFactory.registry()` contains
    `core.pwd` and resolves to the canonical `CorePwdStep.definition` instance.
11. **Fresh factory consistency**: `CoreStepRegistryFactory.registry()` produces fresh per-call
    registries; both contain the same `core.pwd` and resolve to the same definition instance.
12. **Capability admission (both available)**: `RegistryExecutionPreparation.prepare` with both
    declared capabilities available → `ExecutionPreparation.Ready`.
13. **Missing WORKSPACE_IDENTITY**: admission is rejected, rejection reason names the missing capability.
14. **Missing EVENT_SINK**: admission is rejected, rejection reason names the missing capability.

### PWD SEMANTIC CONTRACT ("does it keep its specific semantics?")

15. **Success via canonical coordinator**: `CanonicalDurableRunCoordinator.run` with a registry-resolved
    `OpaqueStepNode(payload={"schemaVersion":"dsl-v1","encoded":"{\"kind\":\"pwd\",\"tmp\":false}"})`
    succeeds with one terminal `OperationStatus.SUCCEEDED` row journaled.
16. **Typed failure (handler exception)**: a handler that throws surfaces as `RunOutcome.Failure`
    (typed), not a silent success or silent swallow. Failure-class law preserved (per AGENTS.md
    REPLAY POLICY / Test law).
17. **Fresh durable**: first execution writes exactly one terminal SUCCEEDED operation row.
18. **Replay (MEMOIZED)**: a second `coord.run` at the SAME runId MUST NOT re-run the handler —
    observed via:
    - `StepStarted` event count stays at 1 across two runs
    - `PwdResolved` event count stays at 1 across two runs (durable observation is reproduced,
      not duplicated)
    - journal row count stays at 1 across two runs
19. **No-divergence by construction**: unit input (`PwdInput(tmp=false)` → the single canonical
    envelope) yields a single durable fingerprint regardless of invocation timing — `tmp=true`
    never reaches a fingerprint because decode rejects it. Three coordinated runs at the same
    runId all succeed.
20. **Observability**: every registry run emits a `StepStarted` + `StepFinished` lifecycle pair.
21. **PwdResolved event payload (frozen fields)**: the durable observation is byte-equivalent to
    the legacy `CanonicalPwdNodeDispatcher` (LEGACY_REMOVED in G5): uuid `eventId` (parses as
    UUID), `kind = "PwdResolved"`, monotonically increasing `sequence` (reassigned by
    `InMemoryEventStore`), `path == workspaceRoot` (tmp=false projection: absolute path of the
    canonical stage workspace), exact `sha256` (hex SHA-256 of the resolved path).
22. **Real registry path scenario**: end-to-end registry seam exercised through the production
    `CanonicalDurableRunCoordinator` + `RegistryExecutionPreparation` + `RegistryExecutionBoundary`
    path with the canonical `CoreStepRegistryFactory.registry()` and BOTH declared capabilities.
    The boundary `coexecute` path runs in an INDEPENDENT `CanonicalRuntimeContext` (`ShOptions.EMPTY`),
    so its workspace observation legitimately differs from the coordinated run's context — each
    execution projects ITS OWN workspace; cross-context path equality is NOT a contract invariant
    (documented in-suite). The suite does NOT depend on the DSL compiler (DSL lowering is covered
    separately by `PipelineDslPwdLoweringTest` in scripting-api).

## Coverage matrix (23 rows)

```
Row  Description                                                       Type
 1    identity — KEY + duplicate registration                            generic
 2    contract completeness — descriptor + 2 caps + MEMOIZED            generic
 3    input codec round-trip (legacy envelope)                           generic
 4    input codec rejection (foreign kind)                               generic
 4b   input codec rejection (tmp=true fail-closed)                       pwd-specific
 5    output codec round-trip                                            generic
 6    output codec rejection (non-pwd kind)                              generic
 6b   output codec rejection (missing path)                              generic
 7    canonical envelope == legacy dsl-v1                                generic
 8    production registry resolution                                     generic
 9    fresh factory consistency                                          generic
10    capability declaration EXACT set                                   generic
11    capability admission both available                                generic
12    missing WORKSPACE_IDENTITY rejection                               generic
13    missing EVENT_SINK rejection                                       generic
14    success via canonical coordinator                                  semantic
15    typed failure (handler exception)                                  semantic
16    fresh durable (1 SUCCEEDED row)                                    semantic
17    replay MEMOIZED (no handler re-run, no duplicate PwdResolved)      semantic
18    no-divergence by construction                                      semantic
19    observability (StepStarted/StepFinished)                           semantic
20    PwdResolved event payload (frozen fields, exact sha)               pwd-specific
21    real registry path scenario                                        semantic
```

## Architecture fitness

Delegated to `S3PwdLegacyRemovedFitnessTest` (G5) + `CorePwdRegistryPrimaryFitnessTest`
(G4). Both remain green after this slice (no production changes).

## Test evidence (fresh run, XML-verified)

| Class | Tests | Failures | Errors | SHA256 |
| --- | --- | --- | --- | --- |
| `CorePwdStepContractSuiteTest` | 23 | 0 | 0 | 94dbd039282cd9df |

XML: `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CorePwdStepContractSuiteTest.xml`

## Forbidden in G6 (held)

- **Zero production code changes** (per gate mandate). Only a new test file.
- No changes to `CorePwdStep.kt`, the registry, the codec, the descriptor, or the handler.
- No new fitness or new sibling pin updates (those belong to G5/G4).
- No `core.milestone` / G3-A4.2 / ShellOperations work — separate slices.
- No FF to `main` without explicit user GO.

## Files changed (1)

```
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdStepContractSuiteTest.kt  (NEW, ~710 lines)
```

## Next step

Await user GO before:
1. `git add -A`
2. `git commit -m "S2-A6/G6 — CONTRACT_SUITE: CorePwdStepContractSuiteTest certifies core.pwd"`
3. `git push -u origin cycle/lfc2-e1-s2-a6-g6-core-pwd-contract-suite`
4. STOP — no FF to main without explicit user approval.

After FF to main: continue with **S2-A6/G8 (CERTIFIED) with installed-distribution/UAT evidence**
(the IsUnix precedent's G7 row additions were folded into G6 there; pwd follows the same shape).
