# ADR-PK-NEXT-R0.3 — stdout / LaunchReceipt / DomainEvent contract

**Status:** PROPOSED (PK-NEXT R0.3 candidate)
**Date:** 2026-10-12
**Supersedes:** the implicit contract that emerged from PK-1, where the
LaunchReceipt was emitted on stdout regardless of view mode and the JSON
event stream was appended after.

## 1. Problem

PipelineK's CLI writes to stdout in two modes (`NORMAL` human view
and `EVENTS` JSON view). Both modes intermix on stdout:

- A line `{"schema":"pipelinek.launch/1","runId":…,"correlationId":…}`
  is emitted before the human lines, regardless of view.
- With `--format json`, the same LaunchReceipt line precedes the JSON
  event array.
- A line `Pipeline finished with SUCCESS` follows the array in the
  JSON view.

The result: tests and consumers that assert `stdout.startsWith("[")`
break. PK-NEXT R0.4 enumerates ~20 failing tests in
`:pipeline-application:test` for this reason. A pre-existing test
class assumed "stdout = JSON array" and that assumption is no longer
true because the LaunchReceipt was added on top of it without
redefining the contract.

The first reaction — patch the tests to skip the first line — was
explicitly forbidden: "NO debe resolverse cambiando indiscriminadamente
los tests para ignorar la primera línea." The fix is to define the
public contract, route the channels correctly, and have the tests
follow.

## 2. Decision

### 2.1 Two-channel split

| Channel | Content (default view) | Content (`--format json`) |
|---|---|---|
| **stdout** | human lines: `[PipelineK] Run correlated: <id>`, `[PipelineK] Compiling pipeline`, …, `[PipelineK] Finished: <outcome>` | LaunchReceipt (one JSON object on line 1), then the JSON event document (one bracketed array on line 2) |
| **stderr** | LaunchReceipt JSON line, compile diagnostics, plugin search output, errors | compile diagnostics, plugin search output, errors |

The LaunchReceipt is durable, machine-readable metadata, not a human
message. Default view moves it to stderr where it does not pollute the
human view but stays observable for ops who `2>&1`.

### 2.2 Versioned contract

- LaunchReceipt: `schema: "pipelinek.launch/1"`. The field set is
  fixed: `runId`, `correlationId`, `ownerId`, `startedAtMs`,
  `controlDir`, `status`. Future versions MUST add a new schema token.
- DomainEvent: existing `kind` discriminator continues to be the
  parser's selector. In `--format json` the events are emitted on
  stdout as ONE bracketed JSON document on line 2, and ALSO written
  to the durable EventStore. The two channels are independent: a
  missing LaunchReceipt on stdout is not the same as missing the
  durable `RunCorrelated` event.
- The LaunchReceipt is **not** a substitute for the EventStore. The
  certifier MUST validate the durable `RunCorrelated` event, not
  trust the LaunchReceipt alone, for the correlation to bind.

### 2.3 What changes for existing clients

- A consumer that reads stdout as a JSON array will need to handle the
  LaunchReceipt line separately. The contract: in `--format json`,
  line 1 is the receipt (a JSON object), line 2 is the event document
  (a JSON array). In the default (human) view, line 1 of stdout is
  the first human line; the receipt lives on stderr.
- A consumer that reads stdout as a human log will not see the
  receipt in default view (it went to stderr). Operators that want
  it can use `--format json` or `2>&1` to merge.

### 2.4 Testkit obligations

A new testkit in
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/CliContractTestKit.kt`
(or appended to the existing `CliRun`/`Subprocess` modules) provides:

- Separate stdout / stderr capture (already in `Subprocess.run`).
- `decodeLaunchContract(stdoutLines, stderrLines, view): LaunchContract`
  parses both channels according to the view.
- `decodeEventStream(stdoutLines, view): List<DomainEvent>` for the
  events; in the JSON view this consumes line 2 as a single
  `JsonEventLog.decode` document. Malformed input produces an
  error, not `emptyList()`.
- `assertLaunchAndDurableCorrelate(receipt, eventStream)`: receipts
  `correlationId == firstEvent.correlationId` (the durable
  `RunCorrelated`).
- `assertNoDuplicateReceipts(receipts)`: exactly one receipt per run.

No testkit method swallows a decode failure. Every test that depends
on the contract asserts what the contract promises, and a deviation
fails the test.

## 3. Test rewrite order

Per R0.3: "Actualizar los tests de aplicación después de fijar la
semántica, no antes." The test rewrites are sequenced:

1. Land the channel split (this ADR + code change).
2. Land the testkit.
3. Land the rewrites of the ~20 failing tests in
   `:pipeline-application:test` so they consume the testkit and the
   new contract.
4. Run `:pipeline-application:test --rerun-tasks` to verify zero
   failures from this family.

A test that is in family A (framing and LaunchReceipt) and is not
rewritten in step 3 stays red; that is the explicit signal that the
test is using the old contract.

## 4. What this ADR does NOT change

- The human renderer (`ConsoleFamilyLines`) is unchanged. Its
  output is one of the contributors to the default-view stdout.
- The durable EventStore contract is unchanged. DomainEvent
  decoding is unchanged.
- The CLI flags (`run`, `validate`, `--format`, `--control-root`,
  `--correlation-id`, `--db`) are unchanged.
- The PK-1 contract with Fabric is unchanged: the consumer still
  reads the durable event log; the LaunchReceipt is local metadata
  for the operator that started the run.

## 5. Compatibility note

A test or client that previously read stdout expecting a single JSON
array (the rc3 / pre-PK-1 contract) is now on the wrong channel.
The fix is to read the LaunchReceipt separately and parse events
line by line. There is no shim that hides the contract change —
a silent shim would re-create the rc3-class drift in reverse.

## 6. Acceptance

- The CLI run output is documented in `docs/v2/CLI-OUTPUT-CONTRACT.md`
  (or appended to `INTEGRATION_GUIDE.md`) with both modes.
- All previously-passing tests in `:pipeline-application:test` that
  were broken by the LaunchReceipt are rewritten on top of the
  testkit, not by `stdout.take(N)`.
- The certifier's `13 originales` execute against the new contract
  via the testkit, and the receipt + durable event are correlated
  per the testkit assertion.
- The `--format json` channel decodes to a `LaunchContract` with
  exactly one receipt and N events, both streams parseable, no
  exception swallowed.
