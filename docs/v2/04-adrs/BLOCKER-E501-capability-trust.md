# Capability Trust Blocker — M4-R1

## Blocker Record

**Date**: 2026-08-25
**Commit**: `f112370` (M4-R1 hotfix)
**Scope**: E5-01 proto governance corrections

## Issue

No governed catalog process exists for capability trust verification.

### What "Capability Trust" Means

WorkerHello messages contain `WorkerCapabilities` which self-report what a worker can do
(e.g., `kotlin_compiler_available`, `supports_parallel_frames`, `max_concurrent_steps`).
Currently there is no mechanism to verify these capabilities match reality before trusting them.

### Expected Mechanism

A "governed catalog process" would:
1. Define a catalog of known-capable workers/images
2. Verify worker capabilities against this catalog before trusting
3. Block or warn if a worker claims capabilities not in the catalog

### Current Blocker

E5-01 scope (proto schema declarations only) does not include capability trust verification.
No task authority exists within M4-R1 to create a governed catalog process.

## Resolution Required

This blocker requires:
1. A new epic/backlog item (e.g., E5-11) to define the governed catalog process
2. Product/architecture decision on trust model
3. Implementation authority beyond M4-R1 hotfix scope

## References

- [WORKER_PROTOCOL.md](../../03-specifications/WORKER_PROTOCOL.md)
- [ADR-0043: Proto-Governance](ADR-0043-proto-governance.md)
