# WU-LPR-062 Receipt — Real Gradle project via installed distribution (`--workspace`)

## Goal
Run a real Gradle JVM project through pipelinek (installed distribution) end to end:
`sh("./gradlew build")` must execute in the project directory, producing the jar,
with a typed failure path when a test breaks.

## Change: CLI flag `--workspace <dir>`
Semantics (product decision, Jenkins-familiar single workspace):
- With `--workspace <dir>`, all stage workspaces resolve to `<dir>` itself
  (stages share the workspace, like Jenkins). Without it, the legacy layout
  `<controlDirRoot>/workspace/<stage>-<index>` is unchanged.
- Journal, artefacts, retry/waitUntil control journals and locks stay under the
  control root. Only the sh CWD / stage workspace moves.

Plumbing:
- `PipelineCliConfig.workspace` + `--workspace` parse in `Main.kt`.
- `runCanonicalPipeline(..., workspaceBase)` → `CanonicalDurableRunCoordinator(workspaceBase=...)`.
- `CanonicalRuntimeContext.workspaceBase` → capability bridge threads it to
  `WorkspaceOperationsAdapter`, `DeleteDirOperationsAdapter`,
  `CleanWsOperationsAdapter`, `ArchiveArtifactsOperationsAdapter`.
- `ShExecution.executeBranchStep(..., workspaceBase)` (parallel branch steps).
- `WorkspaceResolver(controlDirRoot, workspaceBase)` resolves shared vs legacy layout.
- Scripted frontend path also receives `workspaceBase`.

## Fixture: `integration/gradle-demo/`
Real Gradle project (Kotlin DSL, kotlin jvm 2.1.20, toolchain 21, wrapper 8.14.5,
App.kt + tests, jar with Main-Class). `pipeline.kts` (success) and
`pipeline-fail.kts` (`-Pdemo.broken=true` → deliberate test failure).

## Evidence (OBSERVED, installed distribution)
- Success path: `pipeline-application run --db /tmp/pk-demo-db/run.db --workspace . pipeline.kts`
  → exit 0, `RunFinished outcome=success`, `GRADLE-DEMO-OK` echoed, `build/libs/gradle-demo.jar` produced.
- Failure path: `... --workspace . pipeline-fail.kts` → exit 1 (CLI contract),
  `RunFinished outcome=failure`, `StepFailed failureKind=SCRIPT message="shell exited with code 1"`.
- Targeted L2 (engine/adapters touched): DurableShellCommandTest, CtxPConcurrencyOwnershipTest,
  Core DeleteDir/CleanWs/ArchiveArtifacts contract+unit, UatLocal004 →
  131 tests, 0 failures, 0 errors (fresh XML canary).
- Compatibility corpus: 23/23 green (fresh XML).
- `:pipeline-application:compileTestKotlin` green after edits.

## Notes
- First E2E attempt failed with exit 127 (`./gradlew` not found) — that failure
  motivated the flag; deterministic repro kept in session log.
- `--workspace` is CLI/runtime plumbing required by the release-train gate for
  real projects; it adds no Step and does not touch the frozen `local-core-v1`
  profile.

## Counters
Certified Steps: 15 SUPPORTED_CERTIFIED (+1 EXPERIMENTAL) — unchanged.
