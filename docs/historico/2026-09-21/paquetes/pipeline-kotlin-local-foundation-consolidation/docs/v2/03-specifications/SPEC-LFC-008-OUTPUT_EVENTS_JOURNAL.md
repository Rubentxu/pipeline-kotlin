# SPEC-LFC-008 — Output, events and journal separation

**Status:** proposed

## Three different concerns

### Domain/Event log
Small structured durable facts: run/step started, completed, failed, artifact published, output range committed.

### RunOutputStore
Potentially huge ordered records:

```kotlin
data class OutputRecord(
    val runId: RunId,
    val stepId: StepExecutionId,
    val sequence: Long,
    val stream: OutputStreamKind, // STDOUT, STDERR, SYSTEM
    val bytes: ByteArray,
    val timestamp: Instant,
)
```

### OperationJournal
Recovery/replay facts for effectful operations. It is not the user log.

## Requirements

- independent stdout/stderr identity;
- bounded chunking;
- cursor pagination;
- tail/follow with bounded subscriber buffers;
- redaction before durable persistence;
- no accumulation proportional to total process output;
- events refer to output ranges/refs, not complete output blobs.

## Performance gate

A process producing >= 1 GiB combined output must execute with bounded memory under the milestone's agreed memory ceiling and without event-log growth proportional to payload size.
