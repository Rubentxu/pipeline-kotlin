# S2-A2 / G1 — `core.sleep` Registry Candidate Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`  
> Slice: S2-A2 (`core.sleep`)  
> Gate: **G1 — registry candidate, no cutover**  
> Base: `5df378bf` (G0 audit)  
> Date: 2026-09-11

## 1. Goal and traceability

Authority: `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`, the LFC-2 Step
Constitution, and S2-A2's G0 audit at
`docs/v2/07-uat/S2_A2_CORE_SLEEP_G0_AUDIT.md`.

G1's exit criterion is deliberately narrow:

```text
A typed, coroutine-cooperative core.sleep StepDefinition is available through the
production core registry, while LEGACY_PLUGIN_IDS membership still makes the legacy
canonical path the sole production authority.
```

This is a candidate authority proof, not a cutover, parity, removal, contract-suite,
or installed-distribution certification gate.

## 2. Production changes

### 2.1 Candidate StepDefinition

New file:

```text
v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepStep.kt
```

It provides:

- `CoreSleepInput(seconds: Long)` with `require(seconds > 0)` at typed-input
  construction and codec decode time, before duration conversion or effects.
- Whole-envelope input codec, byte-compatible with the legacy compiler payload:
  `{"kind":"sleep","seconds":N}`.
- `CoreSleepOutput`, a `TypedStepOutput` carrier projecting `StepOutcome.Success`.
- `StepDescriptor`: `CONTROLLER`, `Effect.READ_ONLY`, `ReplayPolicy.MEMOIZED`.
- `requiredCapabilities = emptySet()`.
- A suspend handler using `delay(input.seconds.seconds)`. The duration API avoids
  the former `seconds * 1000L` overflow conversion and does not block a worker
  thread.

No `TemporalCapability`, clock, scheduler, delay port, or temporal framework was
introduced. A temporal operation does not imply a temporal capability.

### 2.2 Registry composition, without cutover

`CoreStepRegistryFactory.registry()` now registers `CoreSleepStep` alongside the
already registered Steps. This is the normal open Step registry composition seam.

It intentionally does **not** edit `LEGACY_PLUGIN_IDS`, canonical metadata, decoder,
command algebra, `CanonicalNodeDispatcher`, or `CanonicalSleepNodeDispatcher`.
`StructuralFamilyResolver` therefore still classifies `core.sleep` as `LegacyCore`:
legacy membership wins over registry presence.

### 2.3 Cancellation classification at the generic boundary

`RegistryExecutionBoundary` now handles `TimeoutCancellationException` before its
`CancellationException` supertype and produces a typed `FailureKind.TIMEOUT` outcome
for an internally surfaced handler timeout. It rethrows ordinary
`CancellationException` unchanged.

This is generic, key-agnostic boundary behavior. It does not teach the coordinator
about `core.sleep`, and the candidate Step does not catch or reinterpret cancellation.
It preserves the PAR-D law: ordinary cancellation is structured execution control,
not an ENGINE error or a durable terminal fact. A parent `withTimeout` remains owned
by that parent/coordinator and propagates its cancellation structurally.

## 3. Differential matrix

| Case | Legacy (G0 characterization) | Candidate at G1 |
|---|---|---|
| `sleep(1)` | blocking success | suspendable success |
| `sleep(0)` | immediate success | deterministic decode/input rejection |
| `sleep(-1)` | untyped dispatcher exception | deterministic decode/input rejection |
| `Long.MAX_VALUE` | overflow then exception | no milliseconds multiplication; representable duration input |
| ordinary coroutine cancellation | not cooperative | cancellation propagates structurally |
| timeout produced inside a registry handler | not applicable | typed `FailureKind.TIMEOUT`, before generic cancellation handling |
| parent/coordinator deadline | legacy ignores it today | parent cancellation remains owned by parent, not swallowed by candidate |
| replay | legacy `MEMOIZED` | descriptor declares equivalent `MEMOIZED` policy |

The candidate intentionally does **not** reproduce legacy defects. The live timeout
block still routes to legacy sleep at G1, so this receipt does not claim the G0 CLI
timeout defect is repaired. That requires the later routing and block-timeout gates.

## 4. Candidate proof

New focused test:

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepStepUnitTest.kt
```

Its 10 passing rows prove:

1. zero and negative seconds are rejected before duration conversion;
2. complete input-codec round-trip and legacy envelope compatibility;
3. `Long.MAX_VALUE` input codec round-trip without legacy multiplication;
4. typed success carrier output-codec round-trip;
5. descriptor fidelity;
6. empty required capabilities;
7. production factory registration plus `LegacyCore` classification;
8. real `RegistryExecutionPreparation → Ready → RegistryExecutionBoundary.coexecute`
   normal completion;
9. ordinary cancellation is rethrown to its structured owner;
10. an internal timeout is classified `TIMEOUT` before `CancellationException`.

The missing-capability row is **NOT_APPLICABLE**. The contract declares no
capabilities, so there is no capability denial to fabricate.

## 5. Legacy authority and counter proof

Source inspection after G1 confirms:

```text
LEGACY_PLUGIN_IDS              = 11
CanonicalCoreStepMetadata rows = 11
per-Step dispatcher files      = 11

core.sleep registry            = PRESENT
core.sleep candidate           = CoreSleepStep
core.sleep canonical authority = LegacyCore
core.sleep certification       = IMPLEMENTED_UNCERTIFIED / pre-cutover
```

The legacy `core.sleep` decoder branch, metadata row, command subtype, and
`CanonicalSleepNodeDispatcher` remain present. G0 characterization files are
unchanged and still pass.

## 6. Verification evidence

All commands used a `timeout 600` budget. Fresh JUnit XML canaries were removed
before the focused test and local regression run.

| Level | Command / scope | Result |
|---|---|---|
| L0 | `:pipeline-application:compileTestKotlin` | PASS |
| L1 | `:pipeline-application:test --tests '...CoreSleepStepUnitTest'` | 10/10 PASS |
| L2 | candidate + both G0 sleep classes + generic registry carrier + typed shell boundary consumer + echo/error certified regressions | 114/114 PASS |

L2 XML totals:

```text
CoreSleepStepUnitTest                         10 / 0 / 0
CoreSleepLegacyCharacterizationTest            7 / 0 / 0
CoreSleepCoordinatorCharacterizationTest       3 / 0 / 0
GenericRegistryExecutionCarrierTest            6 / 0 / 0
A4_3TypedShellOutputIntegrationTest           20 / 0 / 0
EchoStepContractSuiteTest                     17 / 0 / 0
CoreErrorStepUnitTest                         20 / 0 / 0
CoreErrorRegistryPrimaryFitnessTest           14 / 0 / 0
ErrorStepContractSuiteTest                    17 / 0 / 0
```

Captured log digests:

```text
/tmp/s2a2-g1-l0-initial.log       sha256=362e15cf9261fa0612f394e34a7f2cc0e71fa7aae718726900b87d2888e8f112
/tmp/s2a2-g1-l0-compile-test.log  sha256=e3457038a7764854e2d1ef2f0b56dba03cb434aaadde059b34c6954fdd118533
/tmp/s2a2-g1-l1-core-sleep.log    sha256=cc1c2204215fc72b446fa0f6bb3e40e437432467d85bfe02bdd48086d57fc86e
/tmp/s2a2-g1-l2-regression.log    sha256=51a62695205c733ea1a84c8f5e769dbc11408dfbf8023f8570a80243cfee1a0b
```

## 7. Explicit non-goals and handoff

Not done at G1:

- no legacy routing flip;
- no legacy decoder/dispatcher/catalogue/metadata deletion;
- no claim that the legacy timeout CLI scenario is repaired;
- no `waitUntil` refactor;
- no temporal abstraction;
- no G3 parity, G5 registry-primary, G6 removal, G7 contract certification, or G8
  installed-distribution certification.

The next gate is a separately approved differential/parity proof. Until then,
`core.sleep` must be reported as **IMPLEMENTED_UNCERTIFIED**, never CERTIFIED.

---

**G1 closed as candidate registration only.**
