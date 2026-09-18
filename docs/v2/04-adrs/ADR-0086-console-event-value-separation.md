---
type: adr
id: ADR-0086
title: "Console transcript, DomainEvents and typed Step values are distinct channels"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0077
  - ADR-0085
---

# ADR-0086 — Console/Event/Value separation

## Context

The runtime already distinguishes `capturedStdout` from `consoleTranscript`, but current event compatibility includes `EchoOutputCaptured(content)` and some shell paths can duplicate process output into DomainEvents. High-volume stdout/stderr serialized into semantic events is expensive, duplicates data and confuses authorities.

## Decision

- `TypedStepValue`: program value; e.g. `sh(returnStdout=true)` result.
- `ConsoleTranscript`: high-volume observable process bytes, redacted before persistence.
- `DomainEvent`: bounded semantic facts/lifecycle; not a console transport.
- `OperationJournal`: durable execution authority; never reconstructed from console/events.

For process `sh`, migrate high-volume output away from `EchoOutputCaptured(content)` toward transcript-backed observation. Events may contain bounded metadata/reference such as operation ref, byte count, digest or truncation marker if a real consumer needs it.

Semantic `echo("hello")` may continue to emit a bounded semantic output event because the text is the Step's intended action rather than arbitrary subprocess output.

## Security

Raw process output must pass streaming redaction before durable transcript append. Renderer-time redaction alone is insufficient.

## Compatibility migration

Do not delete existing event assertions in one step. Add transcript parity tests, prove equivalent observable behavior, then narrow/deprecate the shell-output event payload.
