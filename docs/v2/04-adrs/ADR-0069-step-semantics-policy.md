---
type: adr
id: ADR-0069
title: "Step semantics policy: Jenkins familiarity, per-step typed events, and fail-closed coverage on every run path"
status: accepted
date: 2026-09-07
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0005
  - ADR-0007
  - ADR-0054
  - ADR-0068
  - docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md
  - docs/v2/03-specifications/FAILURE_INTERRUPTION_MODEL.md
  - docs/v2/03-specifications/RECOVERY_DURABILITY.md
---

# ADR-0069 — Step semantics policy: Jenkins familiarity, per-step typed events, and fail-closed coverage on every run path

## Status

Accepted (2026-09-07). Already implemented in v0.32.2 via commit `741ebc0`. This
ADR documents the decision retroactively.

## Context

The LFC1-followup cycle (`p-733fb505b5a6bd2d/lfc1-followup-direct`, v0.32.2)
discovered that the in-memory CLI run path lacked the fail-closed eligibility
gate that the durable path already had. Specifically, non-canonical step families
were silently passing on the in-memory path while failing closed on the durable
path — the exact kind of behavioral drift that a fail-closed policy is designed
to prevent. This asymmetry was caught by `CliNonCanonicalInMemoryExitsTwoTest`
and its durable-path equivalent before release.

Three properties are non-negotiable for every step family in the pipeline
runtime: Jenkins-compatible surface area, per-step typed observability, and
fail-closed enforcement on every run path (durable and in-memory).

## Decision

1. **Jenkins familiarity.** Step names, parameters, semantics, and outcomes MUST
   match Jenkins behavior per `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`
   so Jenkins users can adopt pipelines without relearning. Relevant step
   families include but are not limited to: `dir`, `timeout`, `retry`,
   `catchError`, `warnError`, `unstable`, `milestone`, `deleteDir`,
   `cleanWs`, `pwd`, `isUnix`, `load`, `waitUntil`.

2. **Per-step typed domain events.** Every step family MUST emit its own typed
   domain events (e.g. `DirEntered`/`DirExited`, `DirDeleted`, `WsCleaned`,
   `WaitUntilPolled`/`WaitUntilCompleted`, `MilestoneReached`,
   `MilestoneAborted`). External systems can observe and react from separate
   processes. A step whose only observable effect is its return value is
   incomplete.

3. **Fail-closed coverage on every run path.** A step family without canonical
   decoder/dispatcher support MUST be rejected before execution on EVERY run
   path (durable and in-memory). It MUST NOT be silently converted to a
   comment, no-op, or empty shell. The `CanonicalCoreStepCommand.ALL_PLUGIN_IDS`
   registry and its accompanying registry test enforce this invariant.

## Decision drivers

- **Jenkins familiarity:** ADR-0005 establishes the Jenkins Familiarity Contract
  as the basis for user adoption; step names and semantics are part of that
  contract.
- **Per-step observability:** ADR-0007 designates the event log as the source
  of truth. Per-step typed events are the mechanism by which the event log is
  populated — return-value-only steps are invisible to external observers and
  replay systems.
- **Hexagonal fail-closed coverage:** The hexagonal architecture requires that
  every run path (durable and in-memory) apply the same eligibility gate.
  ADR-0054 establishes related fail-closed concerns for block step nesting.
  The in-memory path is not exempt because it is a first-class run path.

## Options considered

### (a) Implicit rules via reviewer discipline

Allow the three properties to be enforced through code review only. This approach
was rejected because the LFC1-followup cycle demonstrated that reviewer discipline
alone cannot prevent drift: the in-memory path already lacked the fail-closed
gate that the durable path had, and the gap was discovered only through
integration testing.

### (b) Codify in AGENTS.md only

Omit a formal ADR and rely solely on AGENTS.md as the governing document. This
was rejected because AGENTS.md is a team conventions file, not an architectural
governance artifact. Without a formal ADR, the policy has no standing in the
decision log and cannot be referenced by later ADRs or specs as a canonical
constraint.

### (c) Full ADR + AGENTS.md cross-reference (CHOSEN)

Promote the policy to a formal ADR with full context, decision drivers, options,
and consequences. Add a cross-reference from AGENTS.md to the ADR. This is the
chosen option: the ADR provides architectural governance and traceability; the
AGENTS.md section provides day-to-day developer orientation.

## Consequences

- Every new step family MUST register in `CanonicalCoreStepCommand.ALL_PLUGIN_IDS`
  and pass the registry test; unregistered step families fail closed on both
  durable and in-memory paths.
- Non-canonical step families MUST NOT be silently converted to a comment, no-op,
  or empty shell on any run path. The `CliNonCanonicalInMemoryExitsTwoTest` and
  its durable-path equivalent are the regression safety nets.
- A future legacy run path that omits the fail-closed eligibility gate is a
  regression, not a feature.
- Per-step typed events enable cross-process observability and replay fidelity
  (ADR-0007); steps that emit no domain events cannot be observed externally
  and cannot participate in the event-log-as-truth model.
- Jenkins familiarity ensures that users familiar with Jenkins step semantics
  can adopt the pipeline DSL without relearning parameter names, outcome
  semantics, or behavioral defaults.

## References

- [ADR-0005: Jenkins Familiarity Contract](ADR-0005.md)
- [ADR-0007: Event Log as Source of Truth](ADR-0007.md)
- [ADR-0054: Block step nesting](ADR-0054.md) (related fail-closed concerns)
- `AGENTS.md` §STEP SEMANTICS
- `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`
- Cycle `p-733fb505b5a6bd2d/lfc1-followup-direct` (v0.32.2)
- Commit `741ebc0`
