# Consolidation debt register

| ID | Debt | Risk | Required closure | Target |
|---|---|---:|---|---|
| D-001 | `PipelineSpec` / `PipelineDefinition` dual authority | Critical | canonical IR | LFC-1 |
| D-002 | ~~multiple StepDescriptor/Effect/Replay definitions~~ | Critical | **Closed 2026-09-04:** one domain contract plus architecture fitness guard | LFC-1 |
| D-003 | legacy durable walker beside dispatcher/coordinator | Critical | single execution spine | LFC-4 |
| D-004 | fake/incomplete DSL semantics | Critical | typed grammar + remove/fix | LFC-2 |
| D-005 | SDK KSP hardcodes known steps | High | generic KSP model | LFC-3 |
| D-006 | step executors construct concrete process runtime | High | capability injection | LFC-3/4 |
| D-007 | `EnvModel` and `EnvironmentComposer` coexist | High | migrate and delete EnvModel | LFC-5 |
| D-008 | credential provider can be absent/injection skipped | Critical | fail-closed binding | LFC-5 |
| D-009 | stdout/stderr aggregation in event path | High | local RunOutputStore | LFC-6 |
| D-010 | `System.user.dir` / global process state mutation | Critical | explicit workspace context | LFC-4 |
| D-011 | hardcoded debug `/tmp` paths | Medium | delete | LFC-0/1 |
| D-012 | application depends on concrete local adapters | High | module boundary refactor | LFC-4 |
| D-013 | protocol/controller active-scope ambiguity | High | defer/remove from active build | LFC-0 |
| D-014 | V1/V2 product split-brain | High | quarantine legacy product | LFC-0 |
| D-015 | stale root release workflow | High | V2 release pipeline | LFC-9 |
| D-016 | no formal `.pipeline.kts` script definition | Medium | typed script configuration | LFC-2 |
| D-017 | `ScriptTextEscaper` rewrites source text | Medium | spike and retire if possible | LFC-2 |
| D-018 | graph identifiers depend on unstable execution/model IDs | Medium | stabilize IDs before projector | LFC-6 |
| D-019 | no generic plugin lock/resolution lifecycle | High | manifest + lockfile + phase-A resolution | LFC-7/8 |
| D-020 | current sandbox wording overstates isolation | Medium | explicit execution/isolation profiles | LFC-7 |

Debt is closed only when the old path is removed and a fitness/UAT check prevents regression.
