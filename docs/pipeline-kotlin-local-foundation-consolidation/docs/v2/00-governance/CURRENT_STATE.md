# Current state — consolidation baseline

## Purpose

This document is the short, current truth for the consolidation programme. It is not a historical roadmap and it does not attempt to describe future remote execution.

## Assets that are already directionally correct

The following ideas/implementations should be preserved and made canonical rather than replaced again:

- durable local process runtime (`ProcessDurableTaskRuntime` direction);
- operation journal and replay semantics;
- event-log based durable facts;
- secret handles and typed credential abstractions;
- `EnvironmentComposer` direction;
- streaming redaction;
- Jenkins familiarity catalogue and F0/F1/F2/F3 concept;
- KSP-based plugin metadata/code generation direction;
- local-first output store spike direction;
- graph as a rebuildable projection, not runtime truth.

## Current structural problems to close

1. `PipelineSpec` and `PipelineDefinition` remain separate pre-IR authorities; `StepDescriptor`, `Effect`, `ReplayPolicy`, and typed outcomes were consolidated under the domain in LFC1-003, and one DSL fixture now compiles directly to `CompiledPipeline` in LFC1-004.
2. Multiple execution paths (`walkPipelineSpecDurable`, coordinator/dispatcher seams, SDK executors that create concrete runtime adapters themselves).
3. DSL surfaces that look executable but are builders returning fake values or discarding semantics (`pwd`, `isUnix`, `whenCondition`, `waitUntil`, `post` and related incomplete paths observed during review).
4. Environment composition split between legacy `EnvModel` and `EnvironmentComposer`.
5. Output/events/journal concerns still overlap; some paths aggregate potentially large stdout/stderr into event payloads.
6. Application-layer code constructs concrete local adapters and therefore violates the intended hexagonal dependency direction.
7. V1 and V2 remain visible in the active repository/build narrative.
8. protocol/controller modules and documentation create product-scope ambiguity even though the local-first foundation is not yet finished.
9. plugin SDK metadata generation is still partially hardcoded around known core steps instead of being truly generic.
10. root/release documentation and workflows contain stale assumptions from earlier product shapes.

## Active product scope

The active scope is **local-first CI/CD**:

- compile `.pipeline.kts`;
- validate and inspect a canonical model;
- execute locally and durably;
- offer Jenkins-familiar steps and declarative structure;
- load verified local plugins before compilation;
- store output, artifacts, events and run metadata locally;
- support repeatable local CI use in arbitrary projects;
- distribute the CLI as a normal developer tool.

## Explicitly deferred

- controller/worker network protocol;
- Jenkins controller integration;
- Jenkins plugin runtime;
- remote scheduling;
- leases/heartbeats/remote command streams;
- multi-tenant SaaS control plane;
- remote log transport.

Deferred means "not maintained on the critical path", not "forbidden forever".
