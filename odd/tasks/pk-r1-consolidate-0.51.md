# PK-R1 0.51.0 — R1-E external consumer (T8)

## Goal

Link the v2 SDK to a project OUTSIDE `v2/`, observing its published contract
through ordinary Maven coordinates resolved from `v2/build/sdk-repo`, with no
`mavenLocal()` and no in-monorepo `project(...)` dependency. The user rule
"no sustituir un consumer externo por pruebas en el mismo monorepo" is the
guard this task implements.

## Why

Every other check in R1-A → R1-D lives in `v2/` or in `examples/` projects
that compile from the same checkout. An observer that resolves the SDK from
its published bytes — and not from a sibling composite build — is the only
proof that the published artifact is consumable by a real downstream.

## Scope

- Build `examples/example-external-consumer/`, an independent Gradle project
  with its own `settings.gradle.kts`.
- Resolve SDK from `-PsdkRepo=...` (defaults to `v2/build/sdk-repo`),
  never `mavenLocal()` or `~/.m2/repository`.
- Use `-PsdkVersion=0.51.0` (the product version of the train).
- Do not modify any code in `v2/`.
- Do not add a step to `v2/settings.gradle.kts`.

## Tasks

- [x] **T8.1** Read existing examples (`example-uppercase-plugin`,
  `sdk-external-execution`) to mirror the `-PsdkRepo` / `-PsdkVersion`
  pattern.
- [x] **T8.2** Survey the published SDK APIs the task names:
  `output.follow.v1` (OutputFollower surface),
  `events.follow.v1` (EventFollower surface),
  runtime read ports (OutputReadPort, OutputFrameIndex,
  EventRecordReadPort), and range read APIs (PipelineKApiRange, SemVer,
  Digest).
- [x] **T8.3** Create `examples/example-external-consumer/`:
  `settings.gradle.kts`, `build.gradle.kts`, four test sources.
- [x] **T8.4** Each test distinguishes DECLARATIVE CONTRACT (the type
  / field exists, with the documented shape) from EXECUTABLE CAPABILITY
  (invoking the method produces the right behaviour).
- [x] **T8.5** Run `./gradlew -PsdkVersion=0.51.0 test` and report.
- [x] **T8.6** Verify SHA reproducibility of the produced JAR across two
  `--rerun-tasks --no-build-cache` builds.

## Constraints (from R1-E brief)

- Consumer in `examples/example-external-consumer/`.
- SDK from `-PsdkRepo=...`, NEVER `mavenLocal()`.
- No modification of `v2/` code.
- No new step in `v2/settings.gradle.kts`.
- Use `-PsdkVersion=0.51.0`.
- Do NOT push, merge, commit. Leave the worktree dirty for inspection.
- "no tests vacuos con dobles que deciden lo que debería decidir producción"
  — call real SDK methods, never stub the production behaviour; for pure
  interfaces the consumer writes its own impl and exercises it, because
  the impl IS the consumer side of the contract.
- "no falsear PASS, no ocultar skipped" — report honestly.

## Surfaces tested (per brief)

1. `output.follow.v1` — OutputFollower / OutputFollowHandle / OutputFollowOptions /
   OutputFollowEvent / FollowState / FollowUntil.
2. `events.follow.v1` — EventFollower / EventFollowHandle / EventFollowOptions /
   EventFollowEvent / EventFollowState / EventFollowRefusal / EventFollowUntil.
3. Runtime read ports — OutputReadPort, OutputFrameIndex, EventRecordReadPort
   with sealed result types and refusal ADTs.
4. Range read APIs — PipelineKApiRange parsing and containment, SemVer
   ordering, Digest parsing, Capability value-class wrapping.

## Acceptance criteria

- Independent build resolves and compiles against published artifacts.
- Every test class declares its CONTRACT assertions and CAPABILITY
  assertions in separate methods, named `*Contract*` and `*Capability*`
  so the report can map directly.
- JAR SHA matches across two `--rerun-tasks --no-build-cache` runs.
- Test report totals: total / passed / failed / skipped.

## PK-R1 closure (2026-10-11)

- T1-T8 complete; T9 producer-side done. PK-R1 R1-A → R1-E all green.
- T9 outcome: candidate v0.51.0-rc2 published locally
  (`dist/candidates/v0.51.0-rc2/`, tag `v0.51.0-rc2 -> 56662425`,
  SHA-256 `b5b72636…`). Release-receipt and handoff documents
  written at `docs/v2/07-uat/evidence/v0.51.0-rc2/`.
- Push to origin: PENDING operator authorization.
- Gap documented (T8 finding): runtime publish/subscribe ports
  (RuntimeRecoverPort, RuntimeControlPort, RuntimeIntrospectionPort)
  are not in the published SDK; user chose to ship with the gap
  documented; certifier may require v0.51.0-rc3.
- CERTIFIED/GA: deferred to the release harness per release model v2.