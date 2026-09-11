# CORE_ECHO_CERTIFICATION.md — formal `CERTIFIED + LEGACY_REMOVED`

## Cycle context

```text
Cycle:       lfc2-e1-s1-echo-legacy-removed
Branch:      cycle/lfc2-e1-s1-echo-legacy-removed
Baseline:    main == origin/main == ca550da0 (after pre-S1 reconciliation)
Receipt:     docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md
G4 receipt:  docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md
Pre-S1:      docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md
Inventory:   docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md
```

## Final statement (machine-derived)

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

## Proof layer 1 — G4 architecture fitness

`S3EchoLegacyRemovedFitnessTest` proves the **source-level absence** of
every legacy path:

```text
1. core echo is registered in the production StepRegistry
2. core echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS
3. core echo is NOT decodable by CanonicalCoreStepDecoder
4. core echo is NOT dispatched by CanonicalNodeDispatcher
5. core echo is NOT in the legacy metadata table
6. certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS
7. core echo satisfies the LEGACY_REMOVED rule
   (decoder absent AND dispatcher absent AND registration absent)

Fresh execution:
  :pipeline-architecture-tests:test --tests 'S3EchoLegacyRemovedFitnessTest'
  exit: 0
  XML: tests="7" skipped="0" failures="0" errors="0"
  log sha256: 78b4650b141c2d2985eed9f69f659760eeb76975a6b62497e434db9d28bb298b
             /tmp/lfc2e1-s1-echo-s3.log
```

See `CORE_ECHO_G4_FITNESS_RECEIPT.md` for full detail.

## Proof layer 2 ��� G7 StepContractSuite

`EchoStepContractSuiteTest` (pipeline-application, 17 named contracts):

```text
identity
contract completeness (key, descriptor, input codec, output codec,
                       required capabilities)
codec input — encode and round-trip preserve text and envelope
codec input — decode rejects a non-echo payload
codec output — opaque raw-text (durable eligibility) round-trips
              byte-identically
canonical envelope — emitted envelope is well-formed JSON
                    (durable eligibility)
registry resolution — production factory contains core.echo
registry resolution — factory is fresh per call, consistent
capability admission — succeeds when EVENT_SINK_CAPABILITY present
success — registry-routed echo emits exactly one EchoOutputCaptured
            and SUCCEEDS
typed failure — registry-routed echo whose handler throws surfaces
                as RunOutcome Failure
fresh durable — first execution writes one terminal SUCCEEDED op
replay — previously SUCCEEDED echo reused without re-running handler
divergence — replaying SUCCEEDED echo with different text fails
             closed as typed divergence
observability — StepStarted/Finished pair + EchoOutputCaptured
missing capability — admission rejects when EVENT_SINK absent
real pipeline scenario — public DSL pipeline stage echo hello
                         registry runs end-to-end
+ 1 extra

Fresh execution:
  :pipeline-application:test --tests 'EchoStepContractSuiteTest'
  exit: 0
  XML: tests="17" skipped="0" failures="0" errors="0"
  log sha256: 9ea8f96effbe1cfe9460af099b1c1b1b98eb1775e22ebaa8286124cf7e3f0f7f
             /tmp/lfc2e1-s1-echo-contract.log
```

## Proof layer 3 — Echo test suite (5 files, 31/31)

```text
LegacyEchoUnreachableProofTest      4/4 GREEN
EchoDurableSpineTest                2/2 GREEN  (in durable subpackage)
UatStep002EchoCaptureTest           1/1 GREEN
CoreEchoSeamTest                    7/7 GREEN
EchoStepContractSuiteTest          17/17 GREEN
                                    ---
TOTAL                               31/31 GREEN

Fresh execution:
  :pipeline-application:test \
    --tests 'LegacyEchoUnreachableProofTest' \
    --tests 'durable.EchoDurableSpineTest' \
    --tests 'UatStep002EchoCaptureTest' \
    --tests 'CoreEchoSeamTest' \
    --tests 'EchoStepContractSuiteTest' \
    --rerun-tasks
  exit: 0
  All 5 XMLs: 0 failures, 0 errors
  log sha256: 82554dd709c420e95ef5e206b4b7b52bf006ab4a638333d893c1cec107d3a1eb
             /tmp/lfc2e1-s1-echo-all5.log
```

## Proof layer 4 — Real execution parity

`examples/01-hello.pipeline.kts` against the installed production
binary (built from `main == origin/main == ca550da0`):

```text
$ pipeline-application run --db /tmp/lfc2e1-s1-real/run.db \
    --control-root <CTL> examples/01-hello.pipeline.kts

Pipeline finished with SUCCESS
9 events emitted
1 EchoOutputCaptured ("hello from pipeline-kotlin v2\n")
Final outcome: success

log sha256: a1d5ee77f438719fa3febc8ca54a8a702c10f6c46394b464d18ae6bc67a1d4fa
            /tmp/lfc2e1-s1-real.txt
```

The `EchoOutputCaptured` event proves `core.echo` resolves through
the **registry path** at runtime, not through any legacy code path.

## Cycle boundary (forbidden changes)

S1 did NOT touch:

```text
- any production code (registry, dispatcher, decoder, metadata)
- any of the 31 echo tests (all already GREEN pre-S1)
- core.sh (already CERTIFIED + LEGACY_REMOVED per LB-02 S6.8)
- the 12 legacy keys (S2 burn-down scope)
- S3ShLegacyRemovedFitnessTest creation (DEDICATED_FITNESS_GAP, deferred)
```

The delta was **purely documentary**:

```text
+ docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md       (this file)
+ docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md
+ openspec/changes/lfc2-e1-s1-echo-legacy-removed/{proposal,design,tasks}.md
M docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md       (core.echo row updated)
```

## LB-01 burn-down ledger entry

```text
S3 burn-down cycle (already closed, prior to S1):
  core.echo: CERTIFIED (after S3.1 + S3.2 + S3.3 source removal)

S1 cycle (this, closed):
  core.echo: CERTIFIED + LEGACY_REMOVED
            (LB-01 ordering: CERTIFIED requires LEGACY_REMOVED;
             the four mechanical proofs above establish both)
            Trunk: ca550da0 + 4 commits (proposal, design, tasks,
                                       G4 receipt, certification,
                                       inventory update)
```

## Trunk

Final trunk after S1 cycle closure: see the cycle commit log on
`cycle/lfc2-e1-s1-echo-legacy-removed`. The branch is the cycle
handoff; merge to main follows the standard SDDK pattern (separate PR).
