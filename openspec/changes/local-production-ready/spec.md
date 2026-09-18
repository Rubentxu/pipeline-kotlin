# Specification: local-production-ready

## Requirement LPR-R1 — One production execution spine

The installed CLI SHALL route supported pipelines through one canonical execution algorithm regardless of in-memory/SQLite storage, view or output format.

### Scenario: view does not select semantics

- GIVEN one pipeline and run inputs
- WHEN executed under each supported observation view
- THEN effects/outcome/journal/fingerprint and persisted semantic events are equivalent.

## Requirement LPR-R2 — Honest DSL

The declarative DSL SHALL construct typed IR and SHALL NOT manufacture runtime-return values.

### Scenario: runtime value

- GIVEN a pipeline that branches on `isUnix()`
- WHEN it executes in supported runtime/script scope
- THEN the boolean comes from actual execution environment
- AND no declaration-time fallback controls the branch.

## Requirement LPR-R3 — Generic body execution

Body-bearing Steps SHALL execute through BodyInvoker/BranchInvoker and BodyExecution policy; central dispatch SHALL NOT branch on concrete Step keys.

### Scenario: external body plugin

- GIVEN an independent plugin JAR with a Single body Step
- WHEN installed distribution compiles and runs it
- THEN no production core/compiler/coordinator edit specific to that plugin is required.

## Requirement LPR-R4 — Observation isolation

Renderer/consumer throughput SHALL NOT be in the child-process output drain critical path.

### Scenario: slow consumer

- GIVEN transcript persistence enabled
- AND a consumer throttled far below child output rate
- WHEN a high-output child runs
- THEN the child completes within the ratified performance delta
- AND the consumer catches up using cursor/offset.

## Requirement LPR-R5 — Bounded output memory

Console/event streaming SHALL use bounded memory independent of total output volume.

### Scenario: 1 GiB output

- WHEN a process emits >=1 GiB in soak testing
- THEN heap/RSS remains within the accepted bounded profile
- AND no whole-output list/string is retained.

## Requirement LPR-R6 — Channel separation

Console bytes SHALL NOT be persisted as high-volume semantic DomainEvents after migration; typed Step return values SHALL remain distinct from console projection.

## Requirement LPR-R7 — Efficient event persistence

SQLite event persistence SHALL avoid one connection/autocommit per event and SHALL preserve monotonic per-run sequence across reopen/resume.

## Requirement LPR-R8 — CLI observation contract

The CLI SHALL support `normal/events/full/console/quiet` views and `text/jsonl/json` formats with filters/projection defined by ADR-0088.

## Requirement LPR-R9 — Agent-efficient diagnosis

The CLI SHALL offer bounded failure inspection so an agent can obtain relevant semantic events and a limited transcript tail without ingesting the whole run.

## Requirement LPR-R10 — Fail-closed support profile

Any public DSL feature not supported in `local-core-v1` SHALL be rejected before effects or clearly guarded experimental; silent no-op/placeholder success is forbidden.

## Requirement LPR-R11 — Real-project certification

Gradle, Maven and Node real-shaped repositories SHALL pass success and selected failure scenarios through the installed distribution before Gate-1.

## Requirement LPR-R12 — Reproducible release artifact

The release SHALL have one canonical ZIP, checksum and version metadata; GitHub Release and SDKMAN SHALL reference that same artifact.

## Requirement LPR-R13 — SDKMAN post-publish proof

A release claimed SDKMAN-ready SHALL pass clean noninteractive installation and real-project execution using the SDKMAN-installed candidate.

## Requirement LPR-R14 — Compatibility boundary

After Gate-1 the explicitly supported DSL/CLI contract SHALL have versioned fixtures; experimental/internal surfaces are not implicitly frozen.

## Requirement LPR-R15 — Historical branch admission

Historical branch implementation SHALL NOT be merged solely because it exists. Integration SHALL be justified against current ADRs/tests and reimplemented from main when necessary.
