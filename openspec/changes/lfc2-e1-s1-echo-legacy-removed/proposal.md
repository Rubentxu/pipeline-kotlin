# Change: lfc2-e1-s1-echo-legacy-removed

## Why

Per the LB-01 ordering (`CERTIFIED` requires `LEGACY_REMOVED`), and per
the LFC-2E0 inventory's `core.echo` row classification, this cycle
**formalizes** `core.echo` as the oracle/reference Step for the
`CERTIFIED + LEGACY_REMOVED` combined verdict.

The mechanical evidence is already on `main` (`ca550da0`):

| Layer | Suite | Result |
|---|---|---|
| G4 architecture fitness | `S3EchoLegacyRemovedFitnessTest` (pipeline-architecture-tests) | **7/7 GREEN** |
| G7 StepContractSuite | `EchoStepContractSuiteTest` (pipeline-application) | **17/17 GREEN** |
| Real execution parity | `examples/01-hello.pipeline.kts` | SUCCESS, 9 events, 1 EchoOutputCaptured |
| Echo test suite (5 files) | LegacyEchoUnreachable + EchoDurable + UatStep002 + CoreEchoSeam + EchoStepContractSuite | **31/31 GREEN** |

Per the user's directive (2026-09-11T09:12:31Z):

> "S1 debe ser mayoritariamente recording/closure. No reinventes
> `core.echo` si ya está mecánicamente probado."

S1 is **recording/closure only** — no production code change. No new
fitness test. No test refixture. No core.echo reimplementation.

## Outcomes

- `docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md` — formal certification
  receipt citing the four mechanical proofs above.
- `docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md` — S3-equivalent
  fitness activation receipt (for `core.echo`).
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` — update the `core.echo`
  row from `CERTIFIED (S3 burn-down)` to
  `CERTIFIED + LEGACY_REMOVED (S1 certification recording)`.
- `openspec/changes/lfc2-e1-s1-echo-legacy-removed/{proposal,design,tasks}.md`.
- LB-01 burn-down ledger updated.

## Non-goals

- modifying production code (registry, dispatcher, decoder, metadata)
- re-running / modifying the existing 31 echo tests
- re-running / modifying `S3EchoLegacyRemovedFitnessTest`
- changing `core.sh` (already CERTIFIED + LEGACY_REMOVED per LB-02 S6.8)
- changing the 12 legacy keys (S2 burn-down scope)
- creating `S3ShLegacyRemovedFitnessTest` (DEDICATED_FITNESS_GAP, NOT
  a CERTIFICATION_GAP; can be added later for symmetry, but does not
  block S1 or S2)

## Exit criterion

A single, machine-derived, unambiguous statement about `core.echo`:

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

No fabrication. No production change. Just the receipt + ledger update.

## Cycle ledger increment

```text
S3 burn-down cycle (already closed):
  core.echo: CERTIFIED (after S3.1 + S3.2 + S3.3 source removal)

S1 cycle (this):
  core.echo: CERTIFIED + LEGACY_REMOVED
            (LB-01 ordering: CERTIFIED requires LEGACY_REMOVED;
             the four proofs above mechanically establish both)
```

S2 (next, separate cycle, NOT in this PR):
  Burn-down the 12 legacy keys one at a time, grouped by semantic
  family (per LFC-2E0 priority P0/P1/P2). NOT this cycle's scope.
