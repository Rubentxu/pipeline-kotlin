# UAT — Observability, Streaming and Performance

Status: PROPOSED
Related: ADR-0085..0088.

## PERF-001 — renderer isolation

Run an output-heavy child with transcript persistence enabled twice:

A. no live renderer;  
B. renderer/pipe throttled to a deliberately low rate.

Child completion delta must meet the ratified LPR budget. Renderer is allowed to lag and catches up later.

## PERF-002 — bounded memory

Generate >= 200 MiB per-PR/CI stress and >= 1 GiB soak outside fast lane. Peak heap/RSS must plateau with configured buffers and not scale linearly with output bytes.

## PERF-003 — stdout/stderr drain

Produce both streams concurrently at high rate. No deadlock, pipe blockage or lost transcript bytes after redaction rules are accounted for.

## PERF-004 — event batching

Generate a synthetic event burst. Assert:

- sequence monotonic;
- every event persisted exactly once;
- transaction/commit count << event count;
- orderly final flush complete;
- restart continues sequence above durable max.

## PERF-005 — abrupt kill

Kill runner with event batch potentially in flight. Restart and classify ObservationStatus honestly. Journal/replay correctness must not depend on missing unflushed observation events.

## PERF-006 — console cursor reconnect

Consume first N bytes/frames, disconnect, allow run to continue, reconnect from byte offset and reconstruct remainder without requiring execution replay.

## PERF-007 — event cursor reconnect

Same for EventCursor sequence. Never timestamp-based.

## PERF-008 — slow JSONL

Pipe `--view full --format jsonl` to a slow reader. Child runtime remains within performance budget; observation lag grows instead of process execution time.

## PERF-009 — secret split boundary

Emit a secret whose bytes are split across adjacent process chunks. Streaming redactor must remove it before transcript persistence and live rendering.

## PERF-010 — no output duplication

After shell-output migration, large `sh` output size must not cause proportional DomainEvent payload growth. Event history size remains bounded by semantic event count; transcript owns output bytes.

## PERF-011 — view/filter CPU

Run same large history with no filter and representative agent filter. Filtering happens read-side; producer execution profile is unchanged.

## Evidence format

Record SHA, argv, JDK, OS/kernel, CPU, storage, repetitions, median/p95, peak RSS, total bytes, event count, transaction count and hashes of result artifacts.
