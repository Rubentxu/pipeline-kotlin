# Deletion and migration plan

## Delete immediately or in LFC-0/1

- hardcoded debug writes such as `/tmp/uat008-debug`;
- global `System.setProperty("user.dir", ...)` behavior;
- stale V1 release workflow from the active V2 release path;
- active-build protocol module if nothing on the local product consumes it;
- duplicate or obsolete documentation authority after links are migrated.

## Delete after canonical IR migration

- `SpecRegistry` as the runtime bridge between model worlds, after the
  LFC1-R1 canonical durable bridge, including LFC1R1-003a and LFC1R1-003,
  satisfies UAT-IR-003;
- `SpecDefinitionMapper` synthetic conversion, after the LFC1-R1 canonical
  durable bridge, including LFC1R1-003a and LFC1R1-003, satisfies UAT-IR-003;
- legacy outcome string mapper;
- duplicate `PipelineSpec`/`PipelineDefinition` authority;
- ~~duplicate SDK/domain `StepDescriptor`, `Effect` and replay taxonomy~~ — completed in LFC1-003; the domain declarations remain guarded by architecture fitness tests.

## Delete after execution-spine migration

- `walkPipelineSpecDurable` legacy execution path;
- `DurableRunDelegate` bridge;
- handler-local adapter construction;
- second process execution algorithms where `ProcessDurableTaskRuntime` is canonical.

## Delete after environment/output migration

- `EnvModel` after all production callers use `EnvironmentComposer`;
- event-based large `EchoOutputCaptured` blobs as the output store;
- merged stdout/stderr as the canonical persisted representation.

## DSL cleanup

Before advertising a Jenkins step as F2/F3:

- `pwd`, `isUnix`, `whenCondition`, `waitUntil`, `post`, `options.skip` and any other reviewed incomplete/fake surfaces must either obtain real semantics or be removed/downgraded;
- fix any double-append behavior in `git`/`scmGit` composition;
- replace current `script {}` shell concatenation semantics with the explicit durable scripted Kotlin model.

Git history is the preservation mechanism. Dead production code is not an archive.
