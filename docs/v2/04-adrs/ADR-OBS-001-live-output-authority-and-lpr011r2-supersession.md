# ADR-OBS-001 — Live Output Authority, and the supersession of `console.log` as a durable surface

- **Status:** accepted
- **Date:** 2026-10-07
- **Supersedes, in part:** the `console.log` claims of `docs/v2/07-uat/WU_LPR_011_SECRET_REDACTION_RECEIPT.md` and its Gate-1 UAT surface
- **Builds on:** ADR-M1 §D2 (`docs/v2/04-adrs/ADR-M1-output-authority.md`), `docs/v2/07-uat/M1_P2_SINGLE_BYTE_AUTHORITY_RECEIPT.md`, `OutputSingleAuthorityFitnessTest`
- **Implemented by:** OBS-B2 (`ProcessOutputSink`, `RedactingOutputIngress`, removal of `consoleTranscript`, `FArchTranscriptAuthorityLawFitnessTest`)

---

## 1. Context

Two accepted decisions collided, and the collision only became visible once the live ingress was attempted.

`M1_P2_SINGLE_BYTE_AUTHORITY_RECEIPT.md` already declared the single byte authority to be the Output
Plane, and already described `console.log` as a **staging buffer, not an authority** — with the
sentence that anticipated its own removal:

> the moment the wrapper grows a pipe protocol, this provider disappears and the child writes the
> store directly.

`WU_LPR_011_SECRET_REDACTION_RECEIPT.md` certified the redaction-at-rest law, and did so by opening
`console.log`.

The pipe arrived with OBS-B2. Implementing it therefore could not preserve both claims as written, and
the repository contained no rule saying which one outranks the other.

## 2. Decision

**`ADR-M1 D2` and Output-Plane byte authority govern transcript bytes.** `console.log` ceases to be a
write target and ceases to be a contractual surface on the canonical `sh` path.

The new law, stated positively rather than as a removal:

> No unsanitized byte of process output may reach durable storage. Sanitized bytes are persisted
> exactly once, in the Output Plane, which is also the only surface for live observation, replay and
> post-mortem retention.

```
Process stdout/stderr
        │
        ▼
StreamingRedactor          ← the only permitted redaction boundary, before persistence
        │
        ▼
ProcessOutputSink          ← SDK seam: the substrate decides WHEN, not WHERE
        │
        ▼
RedactingOutputIngress     ← application: one reserve/write/commit per chunk
        │
        ▼
SegmentOutputStore         ← single authority: live read · replay · post-mortem
```

## 3. Disposition of every prior claim

This is the traceability the supersession owes. Nothing is deleted; each certified claim is either
preserved, superseded, or strengthened, and the evidence moves with it.

| Prior claim | Disposition | Where it now lives |
|---|---|---|
| Redaction happens **before** durable persistence | **PRESERVED** | `Lpr011r2SecretRedactionAtRestUatTest`, row `sanitized bytes are committed to the plane while the child is still alive` |
| Zero raw secret bytes at rest | **PRESERVED** | every row, plus a new white-box scan of the physical store segments |
| Redaction correct across chunk boundaries | **PRESERVED** | rows `split across two child writes`, `emitted byte by byte` |
| A failing step keeps its transcript | **PRESERVED** | row `a failing child leaves a sanitized transcript available for post-mortem` — via the plane, not a retained file |
| A timed-out step keeps its transcript | **PRESERVED** | row `a timed-out child leaves a sanitized transcript available for post-mortem` |
| A JVM crash leaves at most a partial **sanitized** transcript | **STRENGTHENED** | reservations are durable before the first byte (O1) and recovery releases the uncommitted range; the crash/recovery UAT asserts acknowledged bytes survive and no raw bytes were fabricated |
| Typed `returnStdout` value is exact and never scrubbed | **PRESERVED** | row `capture mode keeps the typed stdout exact and the transcript safe` |
| Typed value never leaks into an observable surface | **PRESERVED** | row `the typed capturedStdout value never leaks raw into the transcript` |
| `console.log` contains only sanitized bytes | **SUPERSEDED** | the file is not written; `FArchTranscriptAuthorityLawFitnessTest` forbids naming it on the canonical path |
| `console.log` is the durable surface | **SUPERSEDED** | the Output Plane is, read through `OutputReadPort` |
| `console.log` is the post-mortem surface on failure | **SUPERSEDED** | retention is the Output Plane's responsibility, per `OutputRetentionPort` |
| Exactly one byte authority | **CANONICAL** | `OutputSingleAuthorityFitnessTest`; now strengthened by the terminal carrying no transcript field |

**The historical receipt is not edited.** `WU_LPR_011_SECRET_REDACTION_RECEIPT.md` records what was
certified at the time and against which surface. Rewriting it to claim it had always certified the
Output Plane would make the evidence trail lie. This ADR is the supersession record.

## 4. Two consequences that are deliberate, not incidental

**A field was removed rather than nulled.** `DurableTaskTerminal.Exited.consoleTranscript` is gone,
not nullable. A permanently-null transcript field is not neutral: it keeps the type claiming it can
carry observability, and the next consumer reaches for it. That is how a single-byte-authority law
quietly becomes two authorities.

**Post-mortem retention changed owner.** Retention used to be "a `finally` block happens to leave the
file behind". It is now the Output Plane's retention policy. This is strictly better — retention now
has one policy for all durable authorities — but it is a real transfer of responsibility, so it is
stated here rather than discovered later by an operator who expected a file.

## 5. Why live ingress could not be added alongside the old path

Writing the transcript to both `console.log` and the store would be two durable copies of the same
bytes, which `ADR-M1 D2` forbids outright. Tailing the growing file from a second reader would avoid
the second copy but replaces a push with a poll, which is strictly worse and would reintroduce
exactly the latency this work exists to remove.

There was therefore no additive option, only a supersession — which is why this is an ADR and not a
commit message.

## 6. Laws now enforced mechanically

`FArchTranscriptAuthorityLawFitnessTest` pins:

1. `DurableTaskTerminal` declares no transcript field. This is the guard that stops
   `consoleTranscript2` from appearing under another name.
2. The canonical durable shell path does not name `DurableShellFiles.CONSOLE_LOG`.
3. The shell substrate does not name `SegmentOutputStore`, `OutputPlaneProvider`,
   `OutputStreamHandle` or `OutputAppendPort` — the SDK writes to `ProcessOutputSink` and knows
   nothing about where bytes land.

`ObsBLiveOutputIngressTest` pins the behaviour; `OutputSingleAuthorityFitnessTest` keeps the single
authority; `Lpr011r2SecretRedactionAtRestUatTest` keeps the security guarantee on its new surface.