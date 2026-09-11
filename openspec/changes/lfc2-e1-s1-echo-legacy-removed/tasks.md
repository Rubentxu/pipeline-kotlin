# Tasks: lfc2-e1-s1-echo-legacy-removed

## S1 scope (this cycle)

- [x] characterize the delta for `core.echo` (E50 evidence sweep)
- [x] verify the 4 proof layers fresh on `main == origin/main == ca550da0`:
      - G4 fitness (`S3EchoLegacyRemovedFitnessTest` 7/7 GREEN, sha=78b4650b...)
      - G7 contract suite (`EchoStepContractSuiteTest` 17/17 GREEN, sha=9ea8f96e...)
      - Echo test suite (5 files, 31/31 GREEN, sha=82554dd7...)
      - Real scenario (`examples/01-hello.pipeline.kts` 9 events SUCCESS, sha=a1d5ee77...)
- [x] write `openspec/changes/lfc2-e1-s1-echo-legacy-removed/proposal.md`
- [x] write `openspec/changes/lfc2-e1-s1-echo-legacy-removed/design.md`
- [x] write this tasks.md
- [ ] write `docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md`
      (S3-equivalent fitness activation receipt)
- [ ] write `docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md`
      (formal CERTIFIED + LEGACY_REMOVED receipt)
- [ ] update `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` row for `core.echo`
      from `CERTIFIED (S3 burn-down)` to
      `CERTIFIED + LEGACY_REMOVED (S1 certification recording)`
- [ ] update LB-01 burn-down ledger
- [ ] re-run LFC-2E0 gates against current main (no regression from S1)
- [ ] commit + push to `main`
- [ ] final trunk SHA recorded; main == origin/main

## Out of scope (separate cycles)

### LFC-2E1-S2 — burn-down of the 12 legacy keys (next cycle, NOT S1)

Per `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` and
`STEP_INVENTORY_LFC2E0.md`, the 12 legacy keys are:

```text
core.error, core.sleep, core.file.writeFile, core.emit.event,
core.milestone, core.deleteDir, core.cleanWs, core.load,
core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts
```

Burn-down grouped by semantic family (NOT a single commit):

```text
P0 first:
  - core.error       (typed failure, ABORTS_PIPELINE, NEVER replay)
  - core.sleep       (atomic, READ_ONLY, MEMOIZED)
  - core.pwd         (must produce typed String value)
  - core.isUnix      (must produce typed Boolean value)

P1 second:
  - core.deleteDir   (atomic filesystem write)
  - core.cleanWs     (atomic filesystem write)
  - core.waitUntil   (control flow block)

P2 third:
  - core.emit.event  (control flow block)
  - core.milestone   (control flow block)
  - core.load        (script loading)
  - core.archiveArtifacts (filesystem artifact staging)
  - core.file.writeFile (filesystem write)
```

S2 must derive from `LEGACY_PLUGIN_IDS` and demonstrate exactly 12
members before touching code.

### LFC-2E1-S3 — DEDICATED_FITNESS_GAP for `core.sh` (optional symmetry)

If we want symmetry with echo's G4 fitness, we may add a dedicated
`S3ShLegacyRemovedFitnessTest` later. This is **NOT a CERTIFICATION_GAP**
— `core.sh` already has A5 proof + StepContractSuite + canonical-core
gate. Out of scope for S1.

## Forbidden changes in S1

```text
- any production code change
- any test refixture (the 31 echo tests already pass)
- touching core.sh (already CERTIFIED + LEGACY_REMOVED per S6.8)
- touching the 12 legacy keys (S2 scope)
- adding new Step implementations or plugins
- creating S3ShLegacyRemovedFitnessTest (deferred to S3)
```
