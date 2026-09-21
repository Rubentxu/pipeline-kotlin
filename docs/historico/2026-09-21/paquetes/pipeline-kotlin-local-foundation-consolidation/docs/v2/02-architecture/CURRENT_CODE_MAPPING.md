# Current code mapping to target architecture

This is an integration map based on the reviewed V2 baseline. Re-verify paths against HEAD before editing.

| Current concept/path | Assessment | Target | Action |
|---|---|---|---|
| `PipelineSpec` + `PipelineDefinition` | duplicate authority | `CompiledPipeline` | migrate + delete both old authorities/bridge |
| domain `StepDescriptor` | canonical static plugin metadata contract | one generated/static authority | **closed LFC1-003**; fitness guard remains |
| domain `Effect` / `ReplayPolicy` | canonical typed taxonomy | one typed taxonomy | **closed LFC1-003**; fitness guard remains |
| `SpecRegistry` | temporal bridge | none | delete after IR migration |
| `SpecDefinitionMapper` | synthetic/lossy mapping | none | delete after IR migration |
| `DurableRunDelegate` | legacy seam | `RunCoordinator` | migrate then delete |
| `LegacyOutcomeMapper` / strings | weak outcome model | typed outcomes | delete |
| `walkPipelineSpecDurable` | second execution engine | dispatcher/handlers | migrate all paths then delete |
| `PipelineRun.kt` orchestration | too many reasons to change | coordinator + handlers + block semantics | progressively split by ownership |
| `ProcessDurableTaskRuntime` | strong asset | canonical process adapter | keep, hide behind `ProcessService` |
| SDK `sh` creates runtime/temp/clock | boundary violation | injected capability | rewrite handler |
| `DurableShellExecutor` alternate algorithm | duplication risk | runtime coordinator or removal | route through canonical process runtime; delete if redundant |
| `EnvModel` | legacy | `EnvironmentComposer` | migrate callers, delete |
| `EnvironmentComposer` | right direction | canonical env composition | keep + wire everywhere |
| nullable credential path | unsafe | fail-closed `CredentialService` | remove bypass |
| `SecretHandle` | strong asset | capability internals | keep |
| streamed chunks re-aggregated into `EchoOutputCaptured` | scalability violation | `RunOutputStore` | replace with output refs |
| event log | strong if kept small | durable structured facts | keep |
| operation journal | strong | replay/recovery truth | keep distinct from output |
| graph spike/projection idea | right direction | rebuildable projector | implement after IDs/output stabilize |
| broad `StageScope` | DSL ambiguity | narrow receivers | replace |
| builder `pwd/isUnix/...` fake values | semantic mismatch | scripted runtime or real declarative node | remove/fix before F2 |
| current `script {}` shell concatenation | wrong semantic space | durable scripted Kotlin | replace |
| `ScriptTextEscaper` source rewrite | fragile | source-faithful shell literal design | spike; retire if feasible |
| KSP switch for `echo/sh/error/sleep` | not extensible | generic annotation/signature processor | remove switch |
| generic `StepContext` maps | coarse capability carrier | typed capabilities | migrate |
| protocol module | not needed local-first | deferred history | remove from active build if unconsumed |
| V1 root modules/workflows | product split-brain | legacy/archive | quarantine from V2 critical path |

## Immediate smoke fixes before architecture migration

- remove hardcoded debug paths;
- remove global cwd mutation;
- fix known duplicate `git/scmGit` node construction;
- add regression tests for `post`/`when` before rewriting the DSL.
