# LFC-2E0 Follow-up: pre-S1 evidence audit (2026-09-11)

Status: **evidence-gathering note, NOT a cycle. S1 still requires explicit user direction to open.**

After LFC-2E0 merge to main @ `bbb2e584`, a defensive validation pass
was run on trunk to confirm the LFC-2E0 inventory claims hold against
the actual code. This note captures findings that materially affect
the **actual scope** of the next cycle, LFC-2E1-S1.

## Findings (all on trunk `main == origin/main == bbb2e584`)

### Finding 1: Inventory claims reproduce against current code

Re-enumerated with grep + grep -c on the production sources:

```text
LEGACY_PLUGIN_IDS (CanonicalCoreStepDecoder.kt L60-72):
  count: 12  (= inventory claim)
  keys:  core.error, core.sleep, core.file.writeFile, core.emit.event,
         core.milestone, core.deleteDir, core.cleanWs, core.load,
         core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts
  core.echo NOT in LEGACY_PLUGIN_IDS                          CONFIRMED

CoreStepRegistryFactory (registry entries):
  count: 2   (= inventory claim)
  L29:   CoreEchoStep.registerInto(this)
  L37:   CoreShellStep.registerInto(this)

External plugin (ServiceLoader):
  count: 1   (= inventory claim)
  example.uppercase via examples/example-uppercase-plugin/
```

### Finding 2: Event Harness verdict preserved post-merge

```text
:pipeline-event-harness:test --rerun-tasks --tests 'EventHarnessContractTest' \
                                              --tests 'RealHistoryParityTest'
  EventHarnessContractTest:  tests=9  failures=0  errors=0
  RealHistoryParityTest:     tests=10 failures=0  errors=0
  TOTAL:                      19 / 0 / 0
  log sha256:  b1466f91234d538c7c68e82e60815beacdb4c11c2f5946db183d902087e2e842
```

Same as EVT-3 closure cycle. EVT-3 verdict is intact through the LFC-2E0 merge.

### Finding 3: All architecture fitness tests pass

```text
:pipeline-architecture-tests:test --rerun-tasks
  TOTAL: 193 tests, 0 failures, 0 errors, 0 skipped
  log sha256:  f419e9f4a247e4738c3316260f0a10875bd29e1c06fd6d303a05fe668dd21ff8
```

Including all critical FArch* families (domain framework free, application
depends inward, credentials cycle free, canonical bridge/coverage/outcomes,
event payload opacity, durable coordinator scope, etc.).

### Finding 4 (CRITICAL): S3EchoLegacyRemovedFitnessTest is already active

The LFC-2E0 LB-01 anchor said "activate S3EchoLegacyRemovedFitnessTest".
**On current main it is already active and GREEN.**

```text
File: v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3EchoLegacyRemovedFitnessTest.kt
  size: 10702 bytes
  @Disabled/@Ignore/@Quarantined: NONE
  Result: 7/7 GREEN
```

It performs structural fitness (not grep-fragile):
1. `core.echo` IS in `StepRegistry` (via `CoreStepRegistryFactory`)
2. `core.echo` is NOT in `LEGACY_PLUGIN_IDS`
3. `core.echo` is NOT in `CanonicalCoreStepDecoder` (no legacy decode path)
4. `core.echo` is NOT in `CanonicalNodeDispatcher` legacy semantics
5. `core.echo` is NOT in legacy metadata
6. CERTIFIED ∩ LEGACY_EXECUTABLE = ∅ policy enforced
7. LEGACY_REMOVED rule: decoder absent, dispatcher absent, registration absent

### Finding 5 (CRITICAL): All 5 echo tests are GREEN on current main

The LFC-2E0 LB-01 anchor said "refixture" the 5 echo tests.
**On current main all 5 are GREEN without modification.**

```text
:pipeline-application:test --rerun-tasks \
  --tests 'LegacyEchoUnreachableProofTest' \
  --tests 'EchoDurableSpineTest' \
  --tests 'UatStep002EchoCaptureTest' \
  --tests 'CoreEchoSeamTest' \
  --tests 'EchoStepContractSuiteTest'

  CoreEchoSeamTest:              tests=7  failures=0  errors=0
  EchoStepContractSuiteTest:     tests=17 failures=0  errors=0   (LB-02 contract suite, +1 over 16/17 LB-01 anchor)
  LegacyEchoUnreachableProofTest: tests=4 failures=0  errors=0
  UatStep002EchoCaptureTest:      tests=1 failures=0  errors=0
  EchoDurableSpineTest:           tests=2 failures=0  errors=0
  TOTAL:                          31 / 0 / 0
  log sha256:  a7f53b2f7ad2989a271c1bef39d16d9f46e4127927bce6cc07406091d4f879d3
```

## Implication for LFC-2E1-S1 scope

The user's original plan for S1 said:
- "refixture 5 echo tests" → not needed; already GREEN
- "activate S3EchoLegacyRemovedFitnessTest" → not needed; already active

The **actual scope of S1** is now:

```text
S1 (revised) — CERTIFICATION RECORDING, not implementation

  1. Inventory what is already certified (core.echo) vs LEGACY_REMOVED.
  2. Update LB-01 ledger to formally record core.echo's CERTIFIED +
     LEGACY_REMOVED status with all required evidence:
       - registry entry (CoreStepRegistryFactory.kt L29)
       - no legacy decoder entry
       - no legacy dispatcher entry
       - no legacy metadata entry
       - 17/17 contract suite
       - 7/7 S3EchoLegacyRemovedFitnessTest (structural, not grep)
       - 31/31 the 5 refixture tests (all green on main)
  3. Produce a CORE_ECHO_CERTIFICATION.md receipt following the same
     pattern as S3_ECHO_BURNDOWN_CERTIFICATION.md but explicitly for
     the CERTIFIED + LEGACY_REMOVED combined verdict.
  4. Update STEP_INVENTORY_LFC2E0.md row for core.echo from "CERTIFIED
     (S3 burn-down)" to "CERTIFIED + LEGACY_REMOVED (S1 certification
     recording)".
  5. Re-run the LFC-2E0 inventory gates against current main (no
     regression from the certification recording).
```

If, on opening S1, the cycle discovers any of the 5 tests actually
exercise legacy implementation details (rather than canonical behavior),
that becomes a real refixture task. The current evidence does NOT
support that conclusion — the tests are GREEN and the LB-02 burn-down
was already performed. But S1 must verify this directly.

## What this note is NOT

This is **not** an S1 cycle opening. Per the user's standing instruction:

> "NO empieces todavía el burn-down de las 12 legacy keys.
> Primero abre un ciclo separado: `lfc2-e1-s1-echo-legacy-removed`"

S1 opening still requires explicit user direction. This note exists
so that when S1 opens, its scope is calibrated against real evidence
rather than the LFC-2E0 LB-01 anchor (which appears to have been
written from a pre-LB-02-burn-down perspective).
