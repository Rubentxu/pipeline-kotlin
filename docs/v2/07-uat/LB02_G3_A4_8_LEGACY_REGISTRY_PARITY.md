# LB-02 / A4.8 — legacy `core.sh` vs registry `core.sh` semantic parity

## Goal

Prove that the registry-routed `core.sh` (open-world Step path) and the legacy
canonical-dispatcher-routed `core.sh` are **semantically indistinguishable**
on the inputs a Jenkins-compatible pipeline can produce. Both paths MUST agree
on every observable: typed `ShellInvocationResult`, the canonical `StepOutcome`,
the emitted `EchoOutputCaptured`, the process-launch count, and the failure /
interruption mapping.

## Acceptance gates (all must be GREEN before `core.sh = REGISTRY_PRIMARY` flips)

### A4.8.1 — Output parity

For every `ShellInvocationResult` variant (UnitValue / Stdout / Status / Failed /
Interrupted), the canonical classifier [toStepOutcome] produces the same
`StepOutcome` for both paths. Concretely:

- **legacy path**: `ShExecution.runShellCommandTyped` = `invokeShell(...).toStepOutcome()`
- **registry path**: `CoreShellStep.handler` = `ShellOperations.invoke(...).toStepOutcome()`

Both call **literally the same public function** in
`dev.rubentxu.pipeline.v2.application.durable.toStepOutcome` (extracted in
A4.3 from the formerly-private `ShExecution.toStepOutcome` extension). The
compiler proves this; the A4.8 test pins it explicitly via:

- `classifier is the SAME function for both paths` — references the same
  `KFunction` symbol.
- `registry handler outcome == public classifier for every variant` — drives
  the handler against the 5-variant corpus and asserts equality.

### A4.8.2 — Event parity (real subprocess)

Both paths emit exactly one `EchoOutputCaptured` per step. Captured `content`
is byte-equivalent across paths for the same script. The differences MUST be
limited to non-deterministic identity fields (`eventId`, `sequence`,
`occurredAt`).

- `legacy path emits exactly one EchoOutputCaptured` — drives
  `ShExecution.invokeShell` with `controlDirRoot=null` (non-durable fallback)
  and verifies event count.
- `registry adapter path emits exactly one EchoOutputCaptured` — drives the
  adapter, which delegates to the SAME substrate.
- `legacy and registry paths emit EchoOutputCaptured with identical content
  for the same script` — drives both with `echo a48-parity` and asserts
  byte-equivalent captured content.

### A4.8.3 — Execution-count parity

No path double-launches:

- `handler invokes ShellOperations exactly once per step` — uses a
  `RecordingShellOps` stub; assertion: `callCount == 1` for each variant.
- `ShOperationsAdapter invoke calls ShExecution invokeShell exactly once` —
  uses a `RecordingEventSink` to count emissions.

### A4.8.4 — Ephemeral typed carrier (source-grep)

`CoreShellOutput` and `TypedStepOutput` MUST NOT appear in any module outside
`pipeline-application` (the marker itself lives in `pipeline-domain/durable/`
as a pure marker). The walk checks:

- `pipeline-domain/.../TypedStepOutput.kt` is the ONLY domain reference.
- `pipeline-events/` has zero references (event log substrate).
- `v2/.../CoreShellOutput` only inside `pipeline-application/`.
- No references leak to `v2/pipeline-{sdk,step,runtime,api,...}/`.

### A4.8.5 — Three-branch semantics

Frozen law from A4.3:

1. Ordinary registry Step (returns `String`, e.g. `core.echo`) → `Success`
   without typed carrier.
2. Handler throws → `Failure(FAILURE)` with `FailureKind.ENGINE`, no
   `encodedOutput`.
3. Typed terminal carrier (`TypedStepOutput`) → typed outcome via the
   public classifier.

Boundary code stays `(produced as? TypedStepOutput)?.outcome ?: Success`;
no `StepKey` branching.

`EchoStepContractSuiteTest` MUST stay 17/17 (the `core.echo` registry path
serves branch 1).

### A4.8 — Outcome parity table

| `ShellInvocationResult`     | `toStepOutcome()` (both paths) |
| --------------------------- | ------------------------------ |
| `UnitValue`                 | `Success`                      |
| `Stdout(value)`             | `Success`                      |
| `Status(exitCode)` (any)    | `Success`                      |
| `Failed(failure, ...)`      | `Failure(failure)`             |
| `Interrupted(interruption)` | `Failure(FailureKind.TIMEOUT)` |

The collapsed `Failure(TIMEOUT)` for `Interrupted` matches the legacy
convention; the original `InterruptionKind` survives on the typed carrier.

## Pre-existing failures (unchanged)

36 UAT-subprocess tests in `compat`/pipeline-application remain unchanged
(this slice did NOT touch their fixtures). The new A4.8 tests add 12 new
deterministic + real-subprocess assertions.

## Tests

`A4_8LegacyRegistrySemanticParityTest` — 12 tests:

- 3 tests for **A4.8.1 output parity** (single-classifier source-of-truth,
  handler-vs-classifier 5-variant loop, handler ×1 invocation).
- 3 tests for **A4.8.2 event parity** (legacy emit count, registry emit
  count, byte-equivalent captured content for same script).
- 1 test for **A4.8.3 execution-count** (adapter delegates to
  `ShExecution.invokeShell` exactly once).
- 1 test for **A4.8.4 ephemeral fitness** (source-grep proves the typed
  carrier stays in-flight).
- 4 tests for **A4.8.5 three-branch** (branch 1: Success without typed
  carrier; branch 2: Failure ENGINE from exception; branch 3: typed
  carrier routes Failure SCRIPT; branch 3b: typed carrier routes
  Interrupted → Failure TIMEOUT).

## Evidence (fresh XML canaries, slice landed <this commit>)

```
A4_8LegacyRegistrySemanticParityTest        12 tests / 0 failures / 0 errors  SHA <a48>
G7_CoreShellOutputCodecRoundTripTest        34 tests / 0 failures / 0 errors  SHA <g7>
A4_3TypedShellOutputIntegrationTest         20 tests / 0 failures / 0 errors  SHA <a43>
A4_2ShellOperationsCapabilityTest           14 tests / 0 failures / 0 errors  SHA <a42>
A4_1DescriptorRecoveryCharacterizationTest   7 tests / 0 failures / 0 errors  SHA <a41>
CoreShellStepTest                           11 tests / 0 failures / 0 errors  SHA <css>
EchoStepContractSuiteTest                   17 tests / 0 failures / 0 errors  SHA <echo>
RegistryExecutionBoundaryTest                6 tests / 0 failures / 0 errors  SHA <reb>
GenericRegistryExecutionCarrierTest          6 tests / 0 failures / 0 errors  SHA <grec>
ExecutionBoundaryFactoryTest                 4 tests / 0 failures / 0 errors  SHA <ebf>
FamilyRouterTest                             4 tests / 0 failures / 0 errors  SHA <fr>
TOTAL: 135 tests / 0 failures / 0 errors
```
