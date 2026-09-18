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

## 4. Honest scope notes

- The **on-disk `console.log`** written by the process-set during execution
  still holds raw bytes until success cleanup. This slice closes the
  observable event plane (what external observers and the journal see).
  Persistent on-disk redaction-at-rest remains a follow-up (retention
  semantics interplay: `cleanupRetainOnFailure`), documented as debt, NOT a
  Gate-1 claim.
- `recoveryPolicy`/replay semantics untouched. No journal schema change.
- No test weakened or re-baselined; no pre-existing-failure classification
  needed (all suites green base-free at head).

## 5. Counters

Certified Steps: unchanged (no Step status change; `core.sh` stays CERTIFIED
with a hardened transcript seam).

## 6. Next

WU-LPR-061 → WU-LPR-103 → WU-LPR-060 → STOP at Product Certification
Checkpoint (Gate-1 redaction evidence: this receipt).
