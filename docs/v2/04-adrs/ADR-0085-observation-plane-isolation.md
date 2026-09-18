---
type: adr
id: ADR-0085
title: "Observation plane is read-side and cannot backpressure pipeline execution"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0077
  - ADR-0078
  - ADR-0079
  - docs/v2/02-architecture/OBSERVATION_STREAM_ARCHITECTURE.md
---

# ADR-0085 — Observation plane isolation

## Context

The CLI must show live events/console and later support IDE/agent/controller consumers. Directly rendering from producer callbacks would couple terminal/network speed to child-process draining and pipeline progress.

## Decision

Separate execution and observation planes. Execution persists semantic events and console transcript through fast local writers. Views, filters, JSON rendering, color, tail and agent queries are read-side projections.

Consumers continue using durable cursors/offsets. In-memory live notifications are wakeup hints only. Losing a wakeup cannot lose authoritative data.

### Non-negotiable law

A slow or crashed renderer/consumer must not slow, cancel or fail an otherwise valid pipeline execution.

This law applies to `normal`, `events`, `full`, `console`, `quiet`, JSONL pipes, IDE and future detached observers.

## Consequences

- no renderer in `ExecutionOutputSink` hot path;
- no CLI filters on Event producer path;
- future remote relay consumes the same history/tail seams rather than coordinator callbacks;
- observer lag is measurable and acceptable; execution lag caused by observer is a defect.
