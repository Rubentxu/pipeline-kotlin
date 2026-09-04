# Implementation backlog

Tasks are deliberately ordered. IDs can become GitHub issues.

## LFC-0

- **LFC0-001** accept document authority and active-scope ADR.
- **LFC0-002** inventory root/V1/V2 Gradle graph and publish dependency diagram.
- **LFC0-003** remove unconsumed protocol module from active V2 settings/build.
- **LFC0-004** quarantine V1 default build/release entry points.
- **LFC0-005** rewrite root README for V2 local-first.
- **LFC0-006** delete debug `/tmp` writes and global `user.dir` mutation.
- **LFC0-007** install initial architecture fitness suite.

## LFC-1

- **LFC1-001** define stable ID value types. **Done 2026-09-04.**
- **LFC1-002** implement `CompiledPipeline` and `StageBody`. **Done 2026-09-04.**
- **LFC1-003** unify StepDescriptor/Effect/ReplayPolicy/Outcome. **Done 2026-09-04.**
- **LFC1-004** compile one DSL fixture directly to new IR. **Done 2026-09-04.**
- **LFC1-005** migrate validator/planner. **Done 2026-09-04.**
- **LFC1-006** migrate reference pipeline execution adapter temporarily. **Done 2026-09-04.**
- **LFC1-007** delete SpecRegistry/SpecDefinitionMapper/old IR authority. **Next.**

## LFC1-R1 — Approved durable prerequisite

- **LFC1R1-001** decode `dsl-v1` opaque payloads into typed core durable commands. **Done 2026-09-04.**
- **LFC1R1-002** dispatch canonical nodes through the durable execution spine. **Done 2026-09-04:** `CanonicalNodeDispatcher` composes the specialized handlers for `core.sh`, `core.echo`, `core.error`, and `core.sleep` behind one seam.
- **LFC1R1-003a** provide a journal-aware canonical durable coordinator. **In progress; approved LFC-4 scope promotion:** its exit criterion is a linear canonical core run with suspend dispatch, journal, replay cursor, sandbox, and typed outcomes, without `PipelineSpec` input.
- **LFC1R1-003** route CLI durable runs through the canonical bridge after LFC1R1-003a. **Blocked by dependency.**
- **LFC1R1-004** delete `SpecRegistry` and `SpecDefinitionMapper`; complete LFC1-007.

## LFC-2

- **LFC2-001** introduce `@PipelineDsl` and narrow scopes.
- **LFC2-002** formal `@KotlinScript` definition.
- **LFC2-003** implement declarative grammar golden corpus.
- **LFC2-004** repair `post`, `when` and stage-body semantics.
- **LFC2-005** remove fake runtime return APIs from declarative scope.
- **LFC2-006** fix `git/scmGit` construction semantics.
- **LFC2-007** spike `$VAR`/ScriptTextEscaper alternatives.
- **LFC2-008** specify/implement first durable scripted operation result.

## LFC-3

- **LFC3-001** plugin API v1 extension-kind hierarchy.
- **LFC3-002** stable descriptor/manifest schema.
- **LFC3-003** capability API v1.
- **LFC3-004** KSP v2 derives parameters/schemas/capabilities.
- **LFC3-005** remove hardcoded known-step switch.
- **LFC3-006** generated typed DSL façade.
- **LFC3-007** Plugin TestKit minimum viable contract suite.
- **LFC3-008** external-style JUnit reference plugin.

## LFC-4

- **LFC4-001** suspend coordinator contract. **Minimum linear subset promoted into LFC1R1-003a; remaining generalization stays here.**
- **LFC4-002** suspend dispatcher + handler registry. **Minimum canonical dispatcher subset promoted into LFC1R1-003a; registry work stays here.**
- **LFC4-003** typed `StepExecutionContext`/IDs. **Minimum canonical context subset promoted into LFC1R1-003a; general contract work stays here.**
- **LFC4-004** shell handler via ProcessService.
- **LFC4-005** migrate remaining atomic core handlers.
- **LFC4-006** parallel/retry/resume through dispatcher.
- **LFC4-007** delete walker/delegate/string outcomes.
- **LFC4-008** enforce adapter/global-state fitness rules.

## LFC-5

- **LFC5-001** generic block-step body execution.
- **LFC5-002** environment overlay transform.
- **LFC5-003** workspace/dir transform.
- **LFC5-004** credential lease transform.
- **LFC5-005** deadline/timeout transform.
- **LFC5-006** retry/catchError policy wrappers.
- **LFC5-007** EnvironmentComposer all callers; remove EnvModel.
- **LFC5-008** fail-closed credential provider and cleanup UAT.

## LFC-6

- **LFC6-001** RunOutputStore port + local adapter.
- **LFC6-002** channel/sequence/cursor contract.
- **LFC6-003** terminal fan-out/follow.
- **LFC6-004** events reference output ranges.
- **LFC6-005** remove large output event payloads.
- **LFC6-006** graph projector.
- **LFC6-007** 1 GiB stress UAT and store-growth benchmark.

## LFC-7

- **LFC7-001** config/discovery conventions.
- **LFC7-002** stable CLI commands/exit codes.
- **LFC7-003** doctor/diagnostics.
- **LFC7-004** retention/cleanup commands.
- **LFC7-005** Maven/Gradle/Node real project fixtures.
- **LFC7-006** hardened Linux sandbox spike.

## LFC-8

- **LFC8-001** manifest resolver + lockfile.
- **LFC8-002** compile classpath phase A/B.
- **LFC8-003** plugin verify/update CLI.
- **LFC8-004** machine-readable Jenkins catalogue validation.
- **LFC8-005** migration parser/transformer for supported declarative subset.
- **LFC8-006** standard plugin bundle and compatibility corpus.

## LFC-9

- **LFC9-001** Jlink build spike/matrix.
- **LFC9-002** JReleaser configuration.
- **LFC9-003** checksums/SBOM/signing/provenance.
- **LFC9-004** project Homebrew tap.
- **LFC9-005** SDKMAN vendor onboarding/publish.
- **LFC9-006** asdf packaging/plugin.
- **LFC9-007** mise Aqua/GitHub registry entry.
- **LFC9-008** clean-host install/upgrade UAT.

## LFC-10

- **LFC10-001** RC dogfooding across real projects.
- **LFC10-002** plugin API compatibility baseline.
- **LFC10-003** performance/cancellation soak.
- **LFC10-004** documentation/migration cookbook.
- **LFC10-005** 1.0 readiness and debt closure review.
