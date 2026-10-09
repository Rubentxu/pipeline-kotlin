# OBS-2 Nivel A — auditoría de cobertura de OBS-PC-201..207

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `a3423983` |
| **Status** | **4 de 7 filas no están cubiertas por lo que la UAT pide** — 3 parciales, 1 ausente |
| **Method** | read the rows, not the names. Class and method names were used only to *find* candidates; every verdict below rests on what the row actually asserts. |
| **Blocks** | closing OBS-2 Nivel A |

## Why this is an audit and not a claim

The block looked well covered: `ObsBLiveOutputIngressTest`, `ObsC23ChannelSeparationUatTest`,
`Lpr011SecretRedactionTranscriptUatTest`, `Lpr011r2SecretRedactionAtRestUatTest`, `ObsBJvmDeathOutputRecoveryUatTest`,
`ObsFConsumerContinuityUatTest`, `UatTimeoutBlockDurableTest`, `DurableShellExecutorAdversarialTest`. Twelve
promising classes.

Reading the rows says something different, and the difference is the point of doing this: **a row that
shares a topic with a UAT is not a row that discharges it.** Three failures below have exactly that
shape, and in two of them the gap is the half of the UAT that carries the guarantee.

## The matrix

| UAT | Row that actually covers it | Verdict |
|---|---|---|
| **201** lines appear while the child is alive | `ObsBLiveOutputIngressTest` — *a short transcript is visible mid-step, minus the redaction lookahead*; `Lpr011r2` — *sanitized bytes are committed to the plane while the child is still alive* | **COVERED**, with an honest qualifier |
| **202** stdout and stderr simultaneous, no deadlock | `Lpr040OutputObservationHarnessTest` — *P2 mixed streams 20MiB each preserve identity* (50 000 lines per channel, identity preserved) | **PARTIAL** — wrong layer |
| **203** secrets split across windows and channels | `Lpr011r2` — *secret split across two child writes*, *secret emitted byte by byte*, *secret on stderr is redacted* | **COVERED** |
| **204** `returnStdout` exact, no duplication, no accidental exposure | `Lpr011r2` — *capture mode keeps the typed stdout exact and the transcript safe*; *the typed capturedStdout value never leaks raw into the transcript* | **PARTIAL** — the no-duplication half is unpinned |
| **205** process ends with output still pending in the pumps | none found | **NOT COVERED** |
| **206** timeout and cancellation retaining the whole acknowledged prefix | `UatTimeoutBlockDurableTest` — *WL-T1/T2/T3*: deadline cancels the child and fails the run; rerun does not duplicate | **PARTIAL** — retention is unpinned |
| **207** independent observer dies | `ObsFConsumerContinuityUatTest` — *DISCONNECT-1 a consumer abandoned mid-run leaves the run untouched* | **COVERED**, with a note on what kind of "dies" |

## The four gaps

### 202 — proven in the substrate, not in the composition

`P2` is a real deadlock row: 50 000 lines to each channel, 20 MiB per side, checking that no `OUT-`
leaks into stderr and vice versa. That is exactly the right experiment.

But it runs through `ProcessDurableTaskRuntime` with a **`collectingSink()`**. The OBS composition puts
a `StreamingRedactor` and a `RedactingOutputIngress` in front of the same pipes, and then a durable
`reserve → write → commit` per chunk on a **per-channel** lock. That is three more stages, and the
redaction stage in particular buffers across chunk boundaries — a secret split across the two channels
is precisely the shape that makes it hold bytes back. The UAT asks about the system PipelineK actually
runs, and that system has not been put under 20 MiB per channel.

### 204 — the typed value is pinned; the absence of duplication is not

`capture mode keeps the typed stdout exact and the transcript safe` asserts that `output.txt` still
carries the canary verbatim and that the transcript is sanitised. Both are right, and both are about
what *is* present.

The row never asserts that stdout is **absent** from the transcript. The script deliberately writes to
both channels, so the absence is directly observable, and it is the half of the UAT that matters for
compatibility: in `returnStdout` mode stdout is the typed value, and projecting it into the console as
well would duplicate every byte in front of a user. The behaviour is correct today — `DurableShellExecutor`
redirects stdout to `output.txt` and pumps only stderr in capture mode — but **a correct behaviour with
no row is a behaviour that a later change can remove silently.**

### 205 — the drain exists, nothing asserts it happened

`DurableShellExecutor` closes the redacting stream *first* precisely so its pending buffer drains:
> "Close the redacting stream FIRST: its pending buffer (EOF drain) is what flushes the final sanitized bytes."

`ObsBLiveOutputIngressTest` proves a large transcript is committed mid-step and is complete, and
`ObsCChannelAndTailCharacterisationTest` proves a finished step reports a `Sealed` tail. Neither asserts
the specific thing the UAT names: that bytes still sitting in a pump when the child exits are committed
**before** the step reports terminal. A step that sealed its tail with 4 KiB still in a pump would pass
every row above.

### 206 — cancellation is proven, retention is not

`WL-T2` proves the deadline cancels the child and fails the run. `WL-T3` proves a rerun does not
duplicate execution. Both matter, and neither is what the UAT says.

"OBS-PC-206: timeout y cancelación **con retención de todo el prefijo reconocido**." A grep across the
timeout and cancellation tests for any assertion mentioning the prefix, acknowledgements or retention
returns nothing. A timeout that truncated the transcript to zero would pass `WL-T1..T3` today. That is
the worst shape of the four, because it is the one where a real regression would be invisible.

## Two notes that are not gaps

- **201 is bounded by the redaction lookahead**, and `ObsBLiveOutputIngressTest` has a row saying so
  explicitly. That is correct behaviour, not a defect — you cannot emit a prefix of a secret you have not
  yet seen the whole of — but the qualifier belongs in the UAT's status, not only in the test's name.
- **207's `DISCONNECT-1` is an abandoned consumer, not a killed process.** A real observer process
  dying is covered by `OBS-PC-101` and `OBS-PC-107` in OBS-1, which fork real readers. Between the two
  rows the guarantee holds; neither alone is the whole sentence.

## What this changes

OBS-2 Nivel A is **not** ready to close. Four rows are needed before it can, and none of them requires
new architecture — they are rows over behaviour that already exists:

1. `202` at the composition layer: 20 MiB per channel through `RedactingOutputIngress` into the plane.
2. `204` absence: in `returnStdout` mode, stdout appears in the typed value and **not** in the transcript.
3. `205` drain: bytes pending in a pump at child exit are committed before the step terminal.
4. `206` retention: a timeout or cancellation preserves the entire acknowledged prefix, byte for byte.

Each needs a mutation that kills it, and each must be pinned at the layer where the claim lives —
which for 202 and 205 is the OBS composition, not the substrate.

## NOT_RUN

- No row above has been written or executed by this block; this is an audit of what exists.
- STEP-CERT and PRODUCT-GATE on this SHA.
- HF2 (installed distribution) verification of any Level A row.