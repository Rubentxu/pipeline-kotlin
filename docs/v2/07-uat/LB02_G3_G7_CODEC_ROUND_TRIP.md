# LB-02 / G7 — `CoreShellOutput` codec decode/round-trip

## Goal

Close the durable-side `core.sh` codec gap by implementing `decode` on
`CoreShellOutput.outputCodec` such that:

- Round-trip `decode(encode(O)) == O` is lossless for every contractual variant
  (Unit / Stdout / Status / Failed / Interrupted), preserving every
  contractual field including `durableFailure`, `interruption.causedBy`,
  `interruption.deadlineEpochMillis`, and `interruption.details`.
- Malformed payloads surface as `CoreShellCodecException` (typed decode
  failure). No silent defaults, no `Success` by malformed data, no swallowing.
- Encode is canonical-deterministic: same instance → identical bytes across
  repeated calls; Map iteration order, timestamps, object identity, and
  non-canonical ordering MUST NOT leak into the encoded form.

## Field contract preserved across the wire

The encoded form is a JSON object whose `kind` discriminant matches the
existing scripted-runtime names (`UNIT` / `STDOUT` / `STATUS` / `FAILED` /
`INTERRUPTED`). Per-variant payload:

| Variant | Mandatory fields | Optional fields |
| --- | --- | --- |
| `UNIT` | — | — |
| `STDOUT` | `value: String` | — |
| `STATUS` | `exitCode: Int` | — |
| `FAILED` | `failureKind: FailureKind.name`, `failureMessage: String` | `failureCauseClass: String`, `durableFailure: {...}`, `exitCode: Int` |
| `INTERRUPTED` | `interruptionKind: InterruptionKind.name`, `interruptionMessage: String`, `operationId: String` | `causedBy: String`, `deadlineEpochMillis: Long`, `details: Map<String,String>` |

`outcome` is a top-level mandatory field (`SUCCESS` / `UNSTABLE` / `FAILURE`).

### Documented intentional loss

`PipelineFailure.cause: Throwable` is NEVER reconstituted across the wire
(matches `FailureRecord`'s documented design rule: "a `Throwable` may be
retained by an in-process exception for diagnostics, but it is deliberately
not part of this persisted contract"). The class name IS preserved via
`failureCauseClass` for tooling that needs it.

### Runtime-only fields NOT in the typed carrier

- `OperationOutput.durationMs` / `finishedAt` — substrate's concern.
- `OperationOutput.result: JsonElement` — wire-level; downstream serializes
  via the codec.
- `capturedStdout` (A4.2 field, removed in A4.3) — derivable from
  `result.value` when `kind == "STDOUT"`.
- `stderr` — `core.sh` has no stderr contract today; the legacy `sh` step
  does not capture stderr either. Documented as a future extension if Jenkins
  parity demands it.

## Failure modes (decode)

`CoreShellCodecException` is a `RuntimeException`. The boundary's
`try / catch (e: Exception)` adapter turns it into
`CommonExecutionResult(outcome = Failure(ENGINE), encodedOutput = null)`. The
boundary NEVER silently coerces a malformed payload to `Success`.

The `outcome` discriminant is cross-checked against the variant payload BEFORE
the typed `StepOutcome.Failure(...)` is constructed, so a `kind=STDOUT` with
`outcome=FAILURE` (or any other inconsistency) surfaces as
`CoreShellCodecException("outcome/variant mismatch: ...")` instead of the
untyped `IllegalStateException` that `pipelineFailureFor` would otherwise throw
when called on a non-failure variant.

## Determinism guarantees

- Field order in `buildJsonObject { put(...) }` is explicit and stable.
- `details: Map<String, String>` is encoded via `toSortedMap()` (canonical
  key order); two `FailureRecord` instances carrying the same content but
  with different insertion order produce byte-identical encoded bytes.
- No `now()` / `timestamp` / `finishedAt` field is written to the carrier.
- No object-identity marker (`@`, `0x...`) leaks into the encoded form.

## Tests

`G7_CoreShellOutputCodecRoundTripTest` — 34 tests:

- **G7.1 round-trip (8 tests)**: Unit, Stdout, Status exit-0, Status non-zero,
  Failed (kind/message/exitCode), Failed with full `durableFailure`,
  Failed with `failureCauseClass`, Interrupted (all fields).
- **G7.2 negative corpus (16 tests)**: unknown kind, missing kind, missing
  outcome, non-JSON, STDOUT missing value, STDOUT wrong-typed value,
  STATUS missing exitCode, STATUS non-integer exitCode, FAILED missing
  failureKind, FAILED missing failureMessage, FAILED invalid FailureKind,
  FAILED malformed durableFailure, INTERRUPTED missing interruptionKind,
  INTERRUPTED invalid InterruptionKind, INTERRUPTED missing operationId,
  invalid outcome discriminant, inconsistent variant (STDOUT+FAILURE),
  inconsistent outcome (FAILED+SUCCESS), and a sweep across 10 malformed
  payloads asserting none of them decode to Success.
- **G7.3 determinism (6 tests)**: same-instance deterministic, equal-content
  instances produce same bytes, `FailureRecord.details` map order independence,
  `InterruptionRecord.details` map order independence, no runtime timestamps
  in encoded form, no identity markers, 50x repeat determinism.
- **G7.2 sweep (1 test)**: 10 malformed payloads MUST all fail with
  `CoreShellCodecException` (no silent Success).

## Pre-existing failures (unchanged)

36 UAT-subprocess tests in `compat`/pipeline-application remain unchanged.
This slice did NOT introduce new failures.

## Evidence (fresh XML canaries, slice landed c3ceadf5)

```
G7_CoreShellOutputCodecRoundTripTest           34 tests / 0 failures / 0 errors  SHA 7d5fea575ae5ba70
A4_3TypedShellOutputIntegrationTest            20 tests / 0 failures / 0 errors  SHA 0897181d85311d0b
A4_2ShellOperationsCapabilityTest              14 tests / 0 failures / 0 errors  SHA 35af6eb2759fa5c6
A4_1DescriptorRecoveryCharacterizationTest      7 tests / 0 failures / 0 errors  SHA a4e256ec2c4c81e4
CoreShellStepTest                              11 tests / 0 failures / 0 errors  SHA 3e3740d5be3b24b0
RegistryExecutionBoundaryTest                   6 tests / 0 failures / 0 errors  SHA bae76e1f5f2b21c0
GenericRegistryExecutionCarrierTest             6 tests / 0 failures / 0 errors  SHA 84e7ddbd88047893
ExecutionBoundaryFactoryTest                    4 tests / 0 failures / 0 errors  SHA 79d9d39b3a48cc9c
FamilyRouterTest                                4 tests / 0 failures / 0 errors  SHA 6abc07dd2df91d32
TOTAL: 106 tests / 0 failures / 0 errors
```
