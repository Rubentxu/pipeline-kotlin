# UAT — Local Production Ready

Status: PROPOSED

## UAT-LPR-001 — clean distribution execution

GIVEN a clean machine/runner with supported Java 21  
AND only the built distribution ZIP installed  
WHEN `pipelinek doctor`, `validate`, `run` execute a hello/build fixture  
THEN no source checkout/Gradle project is required  
AND exit codes match contract.

## UAT-LPR-002 — Gradle real project

Run wrapper clean/test/build, archive produced JAR, observe stage/step lifecycle. Repeat from clean workspace.

## UAT-LPR-003 — Maven real project

Run wrapper test/package, archive artifact, surface compiler/test failure with useful normal-view context.

## UAT-LPR-004 — Node real project

`npm ci`, test/build from lockfile. Failure exit propagates as pipeline exit 1.

## UAT-LPR-005 — credentials

A pipeline injects a synthetic credential into an allowed process path. Canary must be absent from:

- DomainEvent JSON/history;
- console transcript;
- normal/full/console view;
- JSON/JSONL output;
- diagnostics;
- archived release test logs.

## UAT-LPR-006 — durable resume

Kill runner in a supported durable effect window, restart/resume and prove completed effect is not re-executed. Journal is authority; event history may report observation completeness separately.

## UAT-LPR-007 — unsupported DSL fails closed

Each Gate-1 excluded surface with existing syntax is tested: compile/admission must fail before effects or be explicitly experimental with a hard guard. No no-op success.

## UAT-LPR-008 — view parity

Execute same pipeline in normal/events/full/console/quiet. Assert identical:

- PipelineOutcome;
- operation journal/fingerprints;
- persisted DomainEvents (allow UI-only diagnostics outside event authority);
- side effects/artifacts.

## UAT-LPR-009 — version upgrade compatibility

Run a prior supported `pipeline.kts` fixture against current release. If migration is intentionally breaking, test the documented rejection/migration path rather than claiming compatibility.

## UAT-LPR-010 — project default

From project root, `pipelinek run` and `validate` discover `pipeline.kts`; explicit alternate path overrides convention.
