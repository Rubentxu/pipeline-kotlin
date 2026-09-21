# Local Foundation Consolidation Roadmap

## Delivery policy

Each milestone is a vertical increment with an exit gate. **No milestone is complete merely because new code exists; the superseded path must be deleted or explicitly quarantined.**

## Current progress — 2026-09-04

LFC-0 remains open because the source UAT catalogue is missing
`UAT-GOV-003` and `UAT-GOV-004`. Within LFC-1, `LFC1-001` through `LFC1-006`
are complete. The canonical IDs, serializable `CompiledPipeline` shape, typed
descriptor/effect/replay contracts, typed outcomes, direct DSL-to-IR
compilation, deterministic canonical validation/planning, and direct reference
execution are now protected by fresh architecture/module evidence.

`LFC1-R1` is the approved prerequisite to `LFC1-007`: establish the direct
durable execution bridge for canonical `dsl-v1` payloads. `LFC1-007` then
deletes `SpecRegistry`, `SpecDefinitionMapper`, and the old IR authority.

## LFC1-R1 — Canonical durable execution bridge

**Outcome:** `pipeline run` can execute the existing canonical `dsl-v1`
payloads directly, without reconstructing a `PipelineSpec` or looking up a
registry entry.

- decode the versioned payloads already emitted for `echo`, `sh`, `error`, and
  `sleep` into typed durable commands;
- route the resulting `StepNode` through one canonical durable dispatcher;
- preserve the existing journal/replay dependencies while replacing only the
  pipeline-model input;
- make the CLI select the canonical bridge for these supported payloads;
- retain block steps, credentials, retries, and genuine concurrent execution
  in their existing LFC-4/LFC-5 backlog items.

**Gate:** UAT-IR-003 and the applicable baseline of UAT-RUN-001 are green;
the direct durable bridge has no dependency on `PipelineSpec`, `SpecRegistry`,
or `SpecDefinitionMapper`.

**Approved decomposition:** LFC1R1-003a provides the journal-aware canonical
run coordinator before LFC1R1-003 changes the CLI composition root. Its exit
criterion is a linear canonical core run that preserves the durable operation
journal, replay cursor, sandbox, and typed step outcomes without accepting a
`PipelineSpec`. Its focused gate is the direct coordinator test; the milestone
gate remains UAT-IR-003 plus the applicable UAT-RUN-001 baseline.

**Approved scope promotion:** LFC1R1-003a promotes the minimum LFC-4 execution
spine needed for that coordinator: suspend canonical dispatch, typed context,
and linear journal/replay flow. It must be extracted as the future LFC-4 spine,
not copied as a temporary bridge. Parallelism, retry policies, block semantics,
and credentials remain in their respective LFC-4/LFC-5 tasks.

## LFC-0 — Repository truth and scope freeze

**Outcome:** one active product story.

- declare V2 local-first active;
- remove/defer protocol module from active build if unconsumed;
- quarantine V1 build/docs/release path;
- rewrite root README/current roadmap links;
- accept debt register and authority rules;
- delete global debug artifacts.

**Gate:** UAT-GOV-001..004 and architecture baseline green.

## LFC-1 — Canonical model

**Outcome:** compiled == validated == executed == graphed model.

- implement `CompiledPipeline` canonical IR and stable typed IDs;
- unify descriptor/effect/replay/outcome authorities;
- migrate DSL/compiler/planner consumers;
- remove SpecRegistry/SpecDefinitionMapper/legacy model bridges.

**Gate:** no duplicate authority fitness violations; deterministic IR golden tests.

## LFC-2 — Honest Jenkins-like DSL

**Outcome:** familiar DSL with no fake runtime values.

- `@DslMarker`, narrow receivers, closed StageBody;
- formal `.pipeline.kts` `@KotlinScript` definition;
- repair/remove incomplete `post`, `when`, `waitUntil`, `pwd`, `isUnix`, etc.;
- fix `git/scmGit` duplicate construction issue;
- spike/resolve shell dollar handling and source rewriting;
- define durable `script {}` boundary.

**Gate:** representative Jenkins fixtures compile to expected IR; no fake-return DSL fitness violation.

## LFC-3 — Plugin API + capabilities + KSP v2

**Outcome:** a third party can add a typed step without core source changes.

- plugin API v1 and extension kinds;
- capability service contracts;
- generic KSP descriptors/schemas/façades/metadata;
- remove hardcoded core-step switch;
- plugin TestKit first slice;
- migrate one external-style reference plugin (JUnit recommended).

**Gate:** reference plugin built in a separate Gradle module/project passes contract suite and is callable from DSL.

## LFC-4 — Single execution spine

**Outcome:** one way to execute any step.

- suspend `RunCoordinator`/`StepDispatcher`;
- typed handlers;
- explicit `StepExecutionContext` and IDs;
- route process execution via capability;
- eliminate global cwd/env access;
- migrate serial, parallel, retry, resume;
- delete durable walker/delegate/legacy outcome strings.

**Gate:** execution trace proves every step routes through dispatcher; cancellation/parallel/replay UAT green.

## LFC-5 — Scoped block semantics, environment and credentials

**Outcome:** nested pipeline behavior is composable and secure.

- generic block-step execution substrate;
- `withEnv`, `dir`, `withCredentials`, `timeout`, `timestamps`, `retry`, `catchError` migrations;
- EnvironmentComposer fully wired; delete EnvModel;
- credential provider fail-closed; secret lifecycle/redaction UAT.

**Gate:** nested block matrix UAT + credential security suite green.

## LFC-6 — Output/event/journal convergence and local graph

**Outcome:** production-grade local observability with bounded memory.

- local `RunOutputStore`;
- stdout/stderr/system channels, cursor pagination/follow;
- events reference output ranges;
- journal stays recovery-only;
- graph projector + JSON/DOT/Mermaid export;
- 1 GiB output performance UAT.

**Gate:** bounded-memory/load test and run inspection UX green.

## LFC-7 — Local product UX and execution profiles

**Outcome:** usable like a normal local CI tool.

- project discovery/config/lock conventions;
- stable CLI commands and exit codes;
- `pipeline doctor`;
- cache/run cleanup policy;
- standard local execution profile;
- optional Linux hardened sandbox spike.

**Gate:** clean-machine project UAT using Maven/Gradle/Node sample projects.

## LFC-8 — Plugin ecosystem and Jenkins migration

**Outcome:** extensibility without core churn and smooth Jenkins onboarding.

- plugin manifest/resolution/lock workflow;
- plugin install/update/verify UX;
- generated Jenkins familiarity tests/docs;
- Jenkinsfile migration tool for supported declarative subset;
- core standard plugin bundle;
- plugin author docs/TestKit stabilized.

**Gate:** third-party sample plugin + Jenkins migration corpus achieve target compatibility score.

## LFC-9 — Release and universal local installation

**Outcome:** public users can install `pipeline` with ordinary tool managers.

- versioning/release workflow;
- Jlink platform archives;
- checksums/SBOM/signing/provenance;
- JReleaser;
- SDKMAN vendor onboarding/publication;
- Homebrew tap then core eligibility later;
- asdf plugin/packager;
- mise Aqua/GitHub-release registry path;
- Windows package option later if needed.

**Gate:** install/upgrade/rollback UAT on supported OS/architectures.

## LFC-10 — 1.0 stabilization

**Outcome:** no architectural critical debt on the local-first path.

- performance soak;
- backward compatibility checks;
- plugin API freeze for 1.x;
- docs/tutorials/migration cookbook;
- release candidate dogfooding across multiple real repositories;
- close all critical/high LFC debt or explicitly defer with accepted ADR.

**Gate:** `1.0.0` release readiness review.
