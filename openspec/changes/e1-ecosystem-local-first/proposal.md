# E1.ecosystem-local-first — Proposal

**Cycle:** `cycle/e1-ecosystem-local-first`
**Base:** `7f4ab469` (post F1 closure of `sh` variable-scope contract; tag `sh-var-scope-contract-f1`)
**Path:** A-lite (propose → spec → design → tasks → apply → verify → debt-verify → release → archive)
**Owner:** SDDK orchestrator (autonomy within table, GO on architectural forks only)
**Author:** SDDK orchestrator
**Date:** 2026-09-20T10:44Z

## Why

PipelineK today has a **partial CI/CD loop locally**: SCM via `core.sh`,
tests via `core.sh`, artifact production via `core.archiveArtifacts`
(REGISTRY_PRIMARY, G4), but **no first-class way to read the JUnit
report back into the pipeline**. Every existing `core.*` step is in
the catalog or in legacy-pending registration; the gap that closes
the loop locally is `core.junit` (read a JUnit XML into a typed
report).

This cycle is the **single-shot E1** of the LFC-2E ecosystem
expansion roadmap (E0..E10), constrained per `ROADMAP_MIGRATION_LPR`
to the parts that **do not block LPR-GATE-1**: the inventory is
already largely done in this repo; E1 is the "first functional
increment + first real CI/CD scenario" loop, four checkpoints inside
**one cycle** instead of four cycles.

The cycle does not re-implement existing functionality, does not
introduce a new plugin catalogue, and does not modify F1 contracts
or open F2 without its trigger condition.

## What changes

A single new family of steps (`core.junit` — read JUnit XML into a
typed report) plus minor gap-closure on artifact retention. No
storage backend, no remote distribution, no protocol additions.

Four checkpoints inside ONE cycle (no inter-checkpoint GO from the
operator):

| Checkpoint | Goal | Output |
|---|---|---|
| **E1.0** | Inventory + scope + UAT definition | OpenSpec specs/tasks frozen; no code yet |
| **E1.1** | Implement `core.junit` via the public SDK; run it from a real `.pipeline.kts` from a clean install | `core.junit` plugin registry entry, codec, contract, handler, fixtures, contract suite |
| **E1.2** | Bridge to `core.archiveArtifacts` so a build output + JUnit report survive as named, retrievable artifacts | New capability (`ARTIFACT_INDEX_CAPABILITY` or equivalent) and a query path; no remote backend |
| **E1.3** | UAT integral: clean install → checkout → build → tests → read JUnit → publish + query artifact; failure modes; resume traces where they touch contracts | Closure receipt + roadmap pointer |

Each checkpoint ends with a checkpoint receipt; the cycle ends with
a single closure receipt.

## Why this scope (and not broader)

The LFC-2E sequence originally proposed E0..E10 (broad plugin
coverage). `ROADMAP_MIGRATION_LPR.md` defers E2..E10 past LPR-GATE-1
because product feedback must precede catalog expansion. E0 is
already done implicitly (this repo's existing inventory is the
inventory). E1 is the smallest slice that **demonstrates a complete
local CI/CD scenario end-to-end**. Going wider would force decisions
on remote storage, worker distribution, and protocol surfaces — all
explicitly outside the operator's authorization table for this cycle.

## What is NOT in scope (out-of-cycle)

- F2 (offset map in `Kotlin24ScriptingHost.mapDiagnostic`).
- New StepKey families beyond `core.junit` and the artifact-bridge capability.
- Remote artifact storage (S3, GCS, Azure Blob, OCI).
- Worker distribution / multi-node coordination.
- New public DSL surface incompatible with the existing DSL contract.
- Changing F1 (`sh` variable-scope contract) or `core.sh` certification status.
- Re-implementing `core.archiveArtifacts` (it is already REGISTRY_PRIMARY).
- Modifying receipts, releases, or tagged SHAs from prior cycles.

## Operator authorization table (verbatim from intake)

| Authorized (agent acts) | Requires operator GO |
|---|---|
| Investigate inventory; pick an increment inside agreed scope | Change a public certified semantics |
| Write OpenSpec, ADR if needed, tasks, UAT | Modify `sh` contract or open F2 without trigger |
| Implement plugins via existing SDK | Add a plugin-specific exception to engine or coordinator |
| Fix locally-reproduced defects that don't break contracts | Introduce remote storage, new protocols, or incompatible public API |
| Run tests, capture evidence, commit, advance to next checkpoint | Alter historical receipts, replace a published release, delete foreign work |
| Close cycle, integrate, archive via SDDK flow | Continue if the integral goal is technically infeasible inside the limits above |

F2 stays out unless its trigger condition fires (i.e. a user-
perceivable diagnostic drift on a credential-bound `$VAR` reference,
which F1 deliberately gates).

## Architecture invariants enforced

Per project AGENTS.md:

- Closed execution structure, open Step registry: `core.junit` is
  implemented through `StepDefinition` + `StepContract` + codecs +
  declared capabilities, like `core.sh` and `core.echo`.
- DSL describes; runtime executes: `core.junit(...)` is a
  declarative DSL function that emits `RegistryStepSpec` only.
- Hexagonal dependency direction: `core.junit` is in the
  application module; it depends on the domain SDK + a thin
  application adapter for the JUnit XML format. No dependency
  reverse into plugins, persistence, or coordinator.
- Typed errors are values: JUnit parse failures and missing-report
  failures use the project's typed result algebra, not raw
  exceptions.

## Coordination with adjacent work

- **`WU-LPR-062..064`** (Gradle/Maven/Node installed-distribution
  fixtures) cover the **build side** of the CI loop. E1.3
  re-uses the Gradle fixture as its default build driver; the
  JUnit report read by `core.junit` is the same `TEST-*.xml` that
  WU-LPR-062 produces.
- **`LB-02 / S6.8`** `core.sh` certification — orthogonal; F1 just
  closed the contract, formal CERTIFICATION row is a separate slice.
  E1.1 does not block on `core.sh = CERTIFIED`.
- **`ROADMAP_MIGRATION_LPR`** — E1 is the **first executable** slice
  of LPR-2 ecosystem expansion; it does not commit to any LPR-GATE-1
  outcome.

## Risks

1. **`core.junit` parser scope.** JUnit XML has a small surface
   area but historical Jenkins/Maven variants exist. The cycle
   constrains itself to the canonical Ant/Maven schema
   (`testsuite/testcase/failure/error/skipped`) — anything else
   fails closed with a typed `UnsupportedJUnitSchemaError`.
2. **Artifact retention.** `core.archiveArtifacts` already
   captures files; the cycle adds an **index/query** capability so
   E1.3 can ask "where did the JUnit report end up?" without
   scanning the filesystem. Risk: the index becomes a second
   source of truth. Mitigation: the index is derived, never
   authoritative; the filesystem remains the durable record.
3. **Clean-install baseline.** E1.3 runs from a freshly-built
   distribution; Gradle's content-hash cache may produce stale
   results. Mitigation: explicit `--rerun-tasks` on the gate,
   plus a per-task freshness oracle (deleted XML + regenerated).

## Acceptance criteria (cycle exit)

The cycle closes green when ALL of the following hold:

- `core.junit` is registered, contract-tested, and runs in a real
  `.pipeline.kts` from a clean install with both happy-path and
  the four named failure modes (compile-fail, test-fail,
  missing-report, missing-artifact).
- Artifact retrieval round-trips: a build's published artifact
  can be queried by name and its content verified byte-for-byte.
- A `docs/v2/07-uat/E1_ECOSYSTEM_LOCAL_FIRST_CLOSURE_RECEIPT.md`
  exists with the checkpoint receipts, sha256 anchors, and the
  inventory delta (what's now available vs the start of cycle).
- The roadmap pointer in `LOCAL_PRODUCTION_READY_ROADMAP.md` is
  updated to mark E1 as done and E2..E10 as deferred.
- F1, F2 status unchanged in `SH_VAR_SCOPE_CONTRACT.md` §8 and
  the cycle's closure receipt.
