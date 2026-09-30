# P0 — `core.sh` invocation working directory receipt

**Status:** IMPLEMENTED / LOCALLY VERIFIED (not externally certified)
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin` (OPEN, Build at verification time)
**SDDK WorkItem:** `b578a3aa-87f2-4bc8-82cc-598d3c83e272`
**Backlog source:** `bl-bl-01M3T5PGQ0000387MBFPM65TC0` (P0)
**Base SHA:** `28dcd5c5a4da6364ca13b94aab94cb97676aa1fe`
**Implementation SHA:** `8e838e6d5e8ef43187bb74a733f0c092aa62e629`

## Contract and change

When `--workspace` is omitted, the `sh` process working directory is the PipelineK invocation CWD. When `--workspace <dir>` is supplied, that explicit workspace remains authoritative. No-workspace file/workspace operations continue using their legacy per-stage storage root.

`CompositionRoot.kt` now supplies normalized process CWD as immutable `ShOptions.workingDirectory` only when `workspaceBase == null`. `ShExecution` already interprets that explicit value before the workspace root, and the canonical workspace-identity bridge follows the same effective directory. The per-stage `WorkspaceResolver` and `workspaceBase` are not changed. This keeps shell/`dir` context distinct from file-operation storage while making shell relative paths behave as requested.

## Reference implementation and compatibility decision

- **Reference implementation:** Jenkins Pipeline `workflow-durable-task-step`, source snapshot commit [`46cb22ff3686c9e427aab1096bd255489b003140`](https://github.com/jenkinsci/workflow-durable-task-step-plugin/commit/46cb22ff3686c9e427aab1096bd255489b003140), retrieved 2026-10-01.
- **Public contract:** [Pipeline: Nodes and Processes steps](https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/). `sh` runs a Bourne shell script; `dir` changes the current directory and the base for relative paths.
- **Implementation inspected:** `src/main/java/org/jenkinsci/plugins/workflow/steps/durable_task/ShellStep.java` delegates to `DurableTaskStep`; `DurableTaskStep.java` requires the workspace `FilePath` and launches the task against that workspace. `src/test/java/org/jenkinsci/plugins/workflow/steps/durable_task/ShellStepTest.java` was reviewed for the maintained step test patterns.
- **Behavior adopted:** shell execution inherits the invocation's workspace context by default, while `dir`/explicit workspace context remains authoritative.
- **Intentional deviation:** absent `--workspace`, PipelineK's filesystem/workspace adapters keep their per-stage scratch layout. Only shell execution CWD and the associated `ShOptions.workingDirectory` context change in this WU.
- **Reuse/licensing:** no Jenkins source code was copied. Only behavior/design was adapted; the referenced plugin source carries the MIT license.

## Verification evidence

### RED characterization

Before the production change, the new CLI integration case failed for the expected reason: expected invocation path `/tmp/junit-4489415617757137127/invocation`, observed `/tmp/junit-4489415617757137127/control/workspace/working-directory-0`. JUnit reported one assertion failure and zero errors. The red Gradle log SHA-256 was `6f546031801500bc8647dde3acdb0a0ce236c7b28550f7cb631c52d847c4c3b2`.

### Focused project tests

- `timeout 600 v2/gradlew -p v2 :pipeline-application:compileTestKotlin` — exit 0.
- `timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.CliShellWorkingDirectoryIntegrationTest' --fail-fast` — exit 0.
- Fresh JUnit XML: 3 tests, 0 skipped, 0 failures, 0 errors; SHA-256 `92a70ef197d9270c3ec14841d63b6a60c8c6d6866868a86afcba30a636596c56`.
- Final class Gradle log SHA-256: `5bbbc9e5fdadcc2c82c340b0b8c22c7d728259564f8650db6d88287314642c6d`.

The permanent `CliShellWorkingDirectoryIntegrationTest` runs real `MainKt` child JVMs and covers:
1. durable CLI, no `--workspace`: shell `pwd` is invocation CWD, with a different control root;
2. no-DB in-memory CLI, no `--workspace`: shell `pwd` is invocation CWD;
3. durable CLI with explicit `--workspace`: shell `pwd` is the requested workspace, distinct from invocation and control roots.

### Installed distribution acceptance

The locally installed distribution was built by `:pipeline-application:installDist`; `pipelinek version` reported `pipeline 0.44.0`. The actual `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` was launched from scratch directories distinct from control roots and explicit workspace.

| Scenario | Result | Log SHA-256 |
| --- | --- | --- |
| Durable, no `--workspace` | exit 0; `pwd` canonicalizes to invocation CWD | `87ad2dae1211305b3de2841724ddd75260cc53977e275c5217944148fbdbe42b` |
| Durable, explicit `--workspace` | exit 0; `pwd` canonicalizes to explicit workspace | `ba68c5cebf00451b07f03759c8cfcb2714462043eff5d58d8e815436a982fd1d` |
| In-memory, no `--workspace` or `--db` | exit 0; `pwd` canonicalizes to invocation CWD | `de6e801d3187176622e419ec3e5122cb6a0e7956b92b44fe453fc5349d9a645b` |

The host exposes `/home` as a symlink to `/var/home`. One initial shell-only string comparison reported a mismatch between those spellings; exit status was 0, and `realpath` comparison confirmed identical directories. The regression tests compare canonical paths.

## Security implications

Without `--workspace`, shell commands now resolve relative paths in the invocation directory instead of isolated synthetic scratch space. That intentionally grants the pipeline's shell process access to the invoked project tree under the process's existing OS permissions. It adds no capability, privilege, or environment mutation. It does not claim to sandbox untrusted pipeline scripts; the existing trusted-local execution model still applies. Explicit `--workspace` remains the deterministic override.

## Scope and remaining gates

- No full repository `check`, release candidate, or external `pipelinek-release-harness` certification was run. The evidence is bounded to the owning application test class and the locally installed 0.44.0 distribution.
- The SDDK TRAIN is not closed by this receipt. This receipt closes only WorkItem `b578a3aa-87f2-4bc8-82cc-598d3c83e272`; the TRAIN returns to Verify for its remaining cycle gates.
