---
type: adr
id: ADR-0087
title: "Local event persistence uses a bounded single-writer and batched SQLite transactions"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0077
  - ADR-0085
  - docs/v2/03-specifications/PERFORMANCE_BUDGETS.md
---

# ADR-0087 — Batched event persistence

## Context

Baseline `SqliteEventStore.append()` opens/closes a connection and autocommits one INSERT per event. This is simple but unnecessarily expensive for production local runs and a live observation stream.

## Decision

Evolve the SQLite adapter to:

- one long-lived writer connection per event database/run context;
- one writer ownership point;
- prepared statement reuse;
- bounded ingress;
- micro-batched transactions;
- WAL;
- sequence assignment at the store boundary;
- durable sequence initialization from persisted maximum on resume/reopen;
- explicit terminal/shutdown flush barriers.

Batch size/time are benchmark-derived implementation settings, not public API.

## Failure semantics

Domain events are never silently dropped. Queue saturation/storage failure produces typed observation health/degradation and an explicit fallback/failure policy. Live notification loss is recoverable and not equivalent to durable event loss.

## Why not an external broker

A local MPSC/single-writer adapter is enough for current volume and has smaller operational/latency cost. Broker selection remains EVT-5/M4 work.
