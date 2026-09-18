# WU-LPR-011 Secret-Redaction Slice — Receipt

Date: 2026-09-18
Base: `48408a8a` (WU-LPR-011 CLI product closure). Gate-1 blocker: streaming
secret redaction before durable transcript.

## 1. Problem (P5 carry-over, Gate-1 blocker)

The receipt-040 Gate-1 requirement: "streaming secret redaction before the
durable transcript." Characterization proved two leak planes:

- **Durable path (Ruta A):** `DurableShellExecutor` redirects raw process
  output to `console.log` on disk; `ShExecution.readConsoleTranscript` read
  the file with a whole-string read and emitted it unredacted as
  `EchoOutputCaptured`. The on-disk log stayed raw until success cleanup.
- **Non-durable path (Ruta B):** `ProcessDurableTaskRuntime` pump chunks
  accumulated into one `EchoOutputCaptured`; a secret split across chunk
  boundaries survived whole-string regex redaction downstream.

## 2. Change (evolution, reuse-first, zero sibling engines)

- **`pipeline-credentials-api` / `TranscriptRedactor`** (new): pure component
  that reads a file or stream through the existing WU-043
  `StreamingRedactor` — chunk-boundary-safe, bounded pending buffer.
- **`CanonicalRuntimeContext.secretPatternRegistry`** (new optional field):
  the active registry threaded as immutable data to the capability bridge.
- **`CanonicalDurableRunCoordinator.secretPatternRegistry`** (new optional
  constructor field) and **`Main.runCanonicalPipeline`**: production wire-up
  always supplies the registry the composition root already builds for
  `RedactingEventSink`. Null = legacy/test composition, explicitly preserved.
- **`ShOperationsAdapter`** and **`ShExecution.invokeShell` /
  `executeNonDurableInvocation`**: the observable console/event channel is
  redacted at the transcript seam BEFORE `EchoOutputCaptured` emission.

Channel separation preserved: only the OBSERVABLE transcript/event channel is
redacted. The typed value channel (`output.txt` / `capturedStdout`,
capture mode) stays exact — a typed value requested by a capture mode must
not be scrubbed.

Hexagonal direction: adapter depends on credentials-api contract; no reverse
dependency; no coordinator branching on step keys.

## 3. Verification (fresh, XML-canaried)

| Suite | Module | Result |
|---|---|---|
| `Lpr011SecretRedactionTranscriptUatTest` (6 tests: byte-boundary split, absent file, file seam, non-durable split-secret leak, null-registry legacy preservation, durable whole-secret) | pipeline-application | 6/6 green, `failures="0" errors="0"` |
| `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | pipeline-application | 11/11 |
| `A4_8LegacyRegistrySemanticParityTest` | pipeline-application | 12/12 |
| `CanonicalDurableRunCoordinatorTest` | pipeline-application | 26/26 |
| `Lpr040ObservationHarnessTest` | pipeline-events | 6/6 |
| `Lpr040OutputObservationHarnessTest` | pipeline-step-sdk:runtime | 6/6 |
| `pipeline-credentials-api:test` (full module) | pipeline-credentials-api | 48/48 |

The UAT asserts the P5 characterization shape end-to-end: a secret printed by
the child in two halves (split across process writes) NEVER appears raw in any
`EchoOutputCaptured` on the durable and non-durable paths, while the
null-registry composition explicitly preserves legacy raw behavior.

## 4. R2 — Secret Redaction At-Rest Closure

Law: `console.log` MUST receive only already-redacted bytes. Redaction happens
BEFORE persistence (streaming pump at the write boundary), never during
cleanup. A JVM crash can leave at most a partial SANITIZED transcript.

Flow change (single redaction engine reused; no second system):

```text
R1: child → raw console.log → TranscriptRedactor → EchoOutputCaptured   (LEAK)
R2: child → PIPE → StreamingRedactor pump → console.log REDACTED → events
```

Mechanics (`DurableShellExecutor`):

- With an active transcript redactor, the console transcript is no longer a
  `ProcessBuilder` redirect target; the merged stream is drained by a
  runtime-owned pump (`StreamingRedactor → BufferedOutputStream(console.log)`).
- The wrapper no longer opens its own `console.log` handle in redacted mode;
  the pump is the single writer. Capture-mode stdout (`output.txt`) stays an
  exact redirect: typed values are NEVER scrubbed.
- The wrapper kills its heartbeat subshell at exit (`kill $HB_PID`): the
  heartbeat held the pipe's write end and otherwise delayed EOF (and the
  final redactor drain) by up to CHECK_INTERVAL.
- The pump closes the redacting stream in its finally (EOF drain of the
  bounded pending buffer) and the executor joins the pump (bounded 10s)
  BEFORE projecting the transcript.

Port discipline: the SDK runtime receives a narrow wrap factory
`((InputStream) -> InputStream)?`; no credentials-api dependency and no
registry coupling in the SDK module. Null = explicit legacy/test composition
(raw transcript preserved and characterized by test).

### R2 evidence (UAT `Lpr011r2SecretRedactionAtRestUatTest`, 10/10 green)

```text
raw secrets in events       = ZERO   (R1 UAT + R2 assertions)
raw secrets in console.log  = ZERO   (whole / split-2-writes / byte-wise /
                                     stderr / failing+retained / timeout)
raw secrets in diagnostics  = ZERO   (retained failing control dir: sanitized
                                     transcript survives, marker present)
typed capturedStdout        = exact by contract (capture-mode UAT)
DURING-EXECUTION proof      : transcript observed sanitized while the child
                              was still alive (before-persistence, not
                              cleanup-time scrubbing)
null registry               : raw legacy behavior preserved (characterized)
large output                : 20k-line transcript streams within budget
```

The critical Gate-1 proof: child prints the secret and FAILS; the control dir
is retained (`cleanupRetainOnFailure=true` default); the surviving
`console.log` contains zero raw secret bytes and the redaction marker.
- `recoveryPolicy`/replay semantics untouched. No journal schema change.
- No test weakened or re-baselined; no pre-existing-failure classification
  needed (all suites green base-free at head).

## 5. Counters

Certified Steps: unchanged (no Step status change; `core.sh` stays CERTIFIED
with a hardened transcript seam).

## 6. Next

WU-LPR-061 → WU-LPR-103 → WU-LPR-060 → STOP at Product Certification
Checkpoint (Gate-1 redaction evidence: this receipt).
