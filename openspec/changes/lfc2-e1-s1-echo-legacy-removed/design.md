# Design: lfc2-e1-s1-echo-legacy-removed

## Law

The LB-01 ordering rule:

```text
CERTIFIED requires LEGACY_REMOVED
```

`LEGACY_REMOVED` is a **source property** (the legacy path is absent
from the source tree), not merely a runtime property
(`LEGACY_UNREACHABLE`).

S1 establishes that `core.echo` satisfies **both** halves of the
combined verdict via mechanical, non-grep-fragile proofs.

## Proof layers (all on `main == origin/main == ca550da0`)

### Layer 1 — G4 architecture fitness

`S3EchoLegacyRemovedFitnessTest` (pipeline-architecture-tests, 206 lines):

```text
7 tests, all GREEN, exit 0:
1. core echo is registered in the production StepRegistry
2. core echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS
3. core echo is NOT decodable by CanonicalCoreStepDecoder
4. core echo is NOT dispatched by CanonicalNodeDispatcher
5. core echo is NOT in the legacy metadata table
6. certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS
7. core echo satisfies the LEGACY_REMOVED rule
   (decoder absent AND dispatcher absent AND registration absent)
```

This is the G4 architecture fitness layer. Source-scanning assertions
(not runtime) prove the absence of legacy paths.

### Layer 2 — G7 StepContractSuite

`EchoStepContractSuiteTest` (pipeline-application, 17 named contracts):

```text
17 tests, all GREEN, exit 0:
identity
contract completeness
codec input (round-trip preserves text and envelope)
codec input (rejects non-echo payload)
codec output (opaque raw-text round-trips byte-identically)
canonical envelope (well-formed JSON, durable eligibility)
registry resolution (production factory contains core.echo)
registry resolution (factory is fresh per call, consistent)
capability admission (succeeds with EVENT_SINK_CAPABILITY)
success (registry-routed echo emits exactly one EchoOutputCaptured)
typed failure (handler throw → RunOutcome Failure)
fresh durable (first execution writes terminal SUCCEEDED)
replay (SUCCEEDED echo reused, no re-run)
divergence (replay with different text → typed divergence)
observability (StepStarted/Finished + EchoOutputCaptured)
missing capability (admission rejects when EVENT_SINK absent)
real pipeline scenario (public DSL pipeline stage echo hello registry)
+ 1 extra
```

This is the G7 StepContractSuite layer. Behavioral + structural
assertions prove the full contract coverage.

### Layer 3 — Echo test suite (5 files)

```text
LegacyEchoUnreachableProofTest      4/4 GREEN
EchoDurableSpineTest                2/2 GREEN
UatStep002EchoCaptureTest           1/1 GREEN
CoreEchoSeamTest                    7/7 GREEN
EchoStepContractSuiteTest          17/17 GREEN
                                    ---
TOTAL                               31/31 GREEN
```

### Layer 4 — Real execution parity

`examples/01-hello.pipeline.kts`:

```text
Pipeline finished with SUCCESS
9 events emitted
1 EchoOutputCaptured ("hello from pipeline-kotlin v2\n")
Final outcome: success
```

This is the strongest behavioral proof: `core.echo` resolves through
the registry path (not legacy) when invoked via the real production
CLI binary on a real `.pipeline.kts` script.

## Single, machine-derived statement

```text
core.echo:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

proof:
  - G4 architecture fitness (S3EchoLegacyRemovedFitnessTest, 7/7 GREEN)
  - G7 StepContractSuite (EchoStepContractSuiteTest, 17/17 GREEN)
  - Echo test suite (5 files, 31/31 GREEN)
  - Real execution parity (examples/01-hello.pipeline.kts, SUCCESS)
```

## Cycle boundary

S1 is **bounded**:

| What S1 does | What S1 does NOT do |
|---|---|
| Capture `CORE_ECHO_CERTIFICATION.md` | Modify any production code |
| Capture `CORE_ECHO_G4_FITNESS_RECEIPT.md` | Modify `S3EchoLegacyRemovedFitnessTest` |
| Update `STEP_INVENTORY_LFC2E0.md` row | Modify `EchoStepContractSuiteTest` |
| Update LB-01 burn-down ledger | Touch `core.sh` |
| Re-run mechanical proofs (XML digests) | Touch the 12 legacy keys |
| | Create `S3ShLegacyRemovedFitnessTest` (DEDICATED_FITNESS_GAP) |
