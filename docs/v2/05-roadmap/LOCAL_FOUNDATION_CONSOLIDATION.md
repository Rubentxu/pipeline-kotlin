# Local Foundation Consolidation Roadmap

**Status:** active  
**Authority:** ADR-0064  
**Detailed source:**
[`pipeline-kotlin-local-foundation-consolidation`](../../pipeline-kotlin-local-foundation-consolidation/docs/v2/05-roadmap/ROADMAP.md)

The active delivery sequence is LFC-0 through LFC-10. The detailed roadmap,
ordered backlog, specifications, ADR proposals, UAT catalogue, and traceability
matrix live in the Local Foundation Consolidation source pack. Existing M0–M8
and ML material is retained as historical evidence and migration input.

Work starts with LFC-0. It establishes repository truth and scope freeze before
any major refactor. Its ordered first task is `LFC0-001`: accept document
authority and the active-scope ADR. Subsequent LFC-0 tasks inventory the Gradle
graph, remove unconsumed protocol scope, quarantine V1 entry points, align root
documentation, remove global debug/state writes, and install the initial
architecture fitness suite.

No LFC milestone is complete merely because code exists: its superseded path
must be deleted or explicitly quarantined, and its exit evidence must cover the
declared UAT and architecture gate.

## LFC-0 progress

| Item | Status | Evidence |
|---|---|---|
| LFC0-001 | complete | ADR-0064 and document-authority record |
| LFC0-002 | complete | [Gradle graph inventory](../02-architecture/GRADLE_GRAPH_INVENTORY.md) and Mermaid source |
| LFC0-003 | complete | `:pipeline-protocol` removed from active V2 settings; `Lfc0ProtocolScopeFitnessTest` and architecture module suite green |
| LFC0-004 | complete | V1 removed from root settings; tag release quarantined; V2 root `:check` aggregation validated by task graph |
| LFC0-005 | complete | root README identifies the V2 local-first product and V1 quarantine |
| LFC0-006 | complete | production debug writes and `user.dir` mutation removed; `dir` propagates an explicit workspace context through `ShOptions` |
| LFC0-007 | complete | architecture fitness suite protects deferred protocol scope and the removed global-state/debug paths |
| LFC0-008 | complete | UAT-GOV-003 + UAT-GOV-004 contracts defined; Lfc0V1QuarantineFitnessTest green; LFC-0 gate closes with UAT-GOV-001..004 + architecture baseline green |

The LFC-0 gate closes with UAT-GOV-001..004 + architecture baseline green;
`Lfc0V1QuarantineFitnessTest` and `Lfc0GlobalStateFitnessTest` are the evidence.

## LFC-1 progress

| Item | Status | Evidence |
|---|---|---|
| LFC1-001 | complete | stable domain value types for stage, step, plugin step, attempt, and operation identity; `PipelineIdsTest` green |
| LFC1-002 | complete | serializable `CompiledPipeline`, explicit `StageBody`, and opaque versioned step payloads; domain suite green |
| LFC1-003 | complete | domain owns `StepDescriptor`, `ExecutionLocation`, `Effect`, `ReplayPolicy`, and typed outcomes; affected module and architecture suites green |
| LFC1-004 | complete | representative DSL fixture compiles directly to `CompiledPipeline`; deterministic source/lock identity and node IDs verified |
| LFC1-005 | complete | canonical validator rejects invalid IR and the deterministic planner represents sequential and one-step parallel execution; focused domain evidence green |
| LFC1-006 | complete | reference adapter executes `CompiledPipeline` through `CompiledStepDispatcher` without a synthetic registry; typed outcome reduction and deterministic parallel flattening verified |

`LFC1-R1` is next: establish the approved canonical durable-execution
prerequisite for `LFC1-007`. LFC-1 remains open until the legacy model
authorities and bridge are removed.
