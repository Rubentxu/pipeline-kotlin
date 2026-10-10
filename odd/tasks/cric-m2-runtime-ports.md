# CRIC-M2 — runtime ports (inspect / control / recover)

**Branch:** `impl/cric-m2-runtime-ports-m1`
**Worktree:** `pk-cric-m2-impl2`
**Base:** `830655a8` (PK v0.49.0-rc1) + M2 audit `dc047cb2` + M2 design `8fc563a5` (HEAD)

## Objective

Implement the three M2 public ports (`RuntimeIntrospectionPort`, `RuntimeControlPort`, `RuntimeRecoverPort`) over the existing M1 read-side and the existing durable stores, with real-store tests. NO release work, NO contract update, NO `Capabilities` companion.

## Problem

The M2 audit confirms every primitive exists; the M2 design formalises three public verbs whose only novelty is composition + closed ADTs. Deferred: the `Capabilities` companion (the `Capability` class is absent in `:pipeline-domain`).

## Why

This closes the first half of CRIC Block 2; the release step / contract bump ships the second half later.

## Scope

- New module `:pipeline-runtime` with public types only (port signatures + ADTs).
- Three production adapters in the same module.
- Four test files: 3 adapter tests + 1 decision-matrix fitness test (real stores, on `@TempDir`).
- Cross-module compile check.

## Constraints

- No kotlinx-coroutines, no Flow.
- No `@PublishedApi internal` magic.
- Closed refusal hierarchies.
- No new leases, no new fencing, no new schedulers in PK.
- Conventional Commits, no `Co-Authored-By` trailer.
- `git push --no-verify`.
- Imports: `RunOwnerId` and `FencingToken` come from `events.durable` (NOT `events.identity` as the design says — design defect).

## Tasks

- [x] **T1** — New module `:pipeline-runtime` (public types only).
  - Acceptance: `:pipeline-runtime` compiles; 3 ports + ADTs are visible; `Capabilities` companion is NOT created (deferred).
  - Routes: delegated direct (writer creates whole module under delegation boundary 2+ non-trivial files).
  - Commit SHA: `3e0ecf15`
- [x] **T2** — Production adapters in `:pipeline-runtime` (IntrospectionAdapters, CancelAdapters, RecoverAdapters, RecoverReport).
  - Acceptance: adapters implement the three ports against real stores; RecoverReport is the data class.
  - Routes: delegated direct.
  - Commit SHA: `3836e63a`
- [x] **T3** — Test files (4): inspect / control / recover adapters + decision-matrix fitness.
  - Acceptance: ≥24 test cases all green on real Sqlite + Segment stores.
  - Actual: 36 test cases (8 + 8 + 8 + 12).
  - Routes: delegated direct.
  - Commit SHA: `47b142c7`
- [x] **T4** — Cross-module compile check (full M2 gate).
  - Acceptance: `:pipeline-runtime:test` + all M1 modules + `:pipeline-application:compileKotlin` green.
  - Routes: delegated direct (or inline for simple `./gradlew`).

## Out of scope (deferred)

- `Capabilities` companion (await `Capability` type decision).
- `INTERFACE_CONTRACT.md` update (release step).
- `CapabilityRegistrationTest` extension (release step).
- Release v0.50.0-rc1 (next sub-agent).

## Design defects routed around

1. **Imports**: `RunOwnerId`, `FencingToken` are in `events.durable.RunExecutionLease`, not `events.identity` as the design says.
2. **`Capability` class missing** — defer the `Capabilities` companion entirely.
3. **`observe(runId)` on `FileBackedRunExecutionLeaseStore`**: design §4.7.1 lists it as a NEW method. The introspection adapter can use the existing `FileBackedRunExecutionLeaseStore::isKnown` AND `readRecord`-equivalent (the lease file row) — OR call the new observe if I add it. Decision: USE existing `isKnown` + read the record via reflection-free path; later additions may add `observe`. For Phase-2 here, we use what the existing file exposes (isKnown, release, etc.). Implementation reads the lease record via the existing public surface.
