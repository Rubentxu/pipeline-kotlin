# UAT catalogue

## Governance / model

- **UAT-GOV-001:** default build and README identify V2 local-first path only.
- **UAT-GOV-002:** no local product task depends on protocol/controller modules.
- **UAT-IR-001:** identical source+lock compiles byte-for-byte/structurally identical IR.
- **UAT-IR-002:** source locations survive into validation diagnostics.
- **UAT-IR-003:** no synthetic registry is required to execute compiled IR.

## DSL

- **UAT-DSL-001:** canonical Jenkins-like basic pipeline compiles.
- **UAT-DSL-002:** invalid stage with both `steps` and `parallel` is impossible or rejected with source position.
- **UAT-DSL-003:** `post` conditions execute in correct lifecycle order.
- **UAT-DSL-004:** `when` false skips the stage and emits typed skip reason.
- **UAT-DSL-005:** runtime-returning operations are unavailable from declarative builder scope.
- **UAT-SCRIPT-001:** scripted `shStdout` result drives Kotlin `if` after durable execution.
- **UAT-SCRIPT-002:** resume/replay does not blindly repeat a memoized scripted operation.

## Plugin SDK

- **UAT-PLUG-001:** external JUnit-like plugin adds a typed DSL call without editing core runtime.
- **UAT-PLUG-002:** plugin missing required capability is rejected before handler side effects.
- **UAT-PLUG-003:** incompatible plugin API/runtime version fails with actionable diagnostic.
- **UAT-PLUG-004:** same lockfile resolves same artifacts/digests.
- **UAT-PLUG-005:** KSP generated descriptor includes real parameters/schemas/capabilities.

## Runtime

- **UAT-RUN-001:** non-zero `sh` returns typed failure and preserves separate stderr.
- **UAT-RUN-002:** timeout kills child process tree.
- **UAT-RUN-003:** Ctrl-C/cancellation kills child process tree and closes stores.
- **UAT-RUN-004:** parallel branches genuinely overlap and join according to policy.
- **UAT-RUN-005:** retry produces distinct attempts and a single logical step identity.
- **UAT-RUN-006:** resumed run reuses/reruns operations exactly per replay policy.

## Environment/credentials

- **UAT-ENV-001:** documented precedence produces expected process env.
- **UAT-ENV-002:** nested `withEnv` scopes restore parent environment.
- **UAT-CRED-001:** missing provider prevents body execution.
- **UAT-CRED-002:** secret visible to intended process but absent from persisted output/events.
- **UAT-CRED-003:** temporary secret file removed after success/failure/cancellation.
- **UAT-CRED-004:** nested credential bindings restore outer projection correctly.

## Output

- **UAT-OUT-001:** stdout and stderr preserve independent channel identity/order sequence.
- **UAT-OUT-002:** pagination from cursor has no gaps/duplicates.
- **UAT-OUT-003:** follow mode receives bounded chunks while process runs.
- **UAT-OUT-004:** 1 GiB producer completes under agreed memory ceiling.
- **UAT-OUT-005:** event store size does not grow approximately 1:1 with output payload.

## Graph

- **UAT-GRAPH-001:** deleting projection and rebuilding from durable truth yields equivalent graph.
- **UAT-GRAPH-002:** retry/parallel relationships are represented correctly.

## Jenkins migration

- **UAT-JENK-001:** basic declarative Jenkinsfile migrates mechanically.
- **UAT-JENK-002:** common `environment/options/post/when` corpus migrates.
- **UAT-JENK-003:** unsupported dynamic Groovy emits explicit TODO/diagnostic, never silent semantic change.
- **UAT-JENK-004:** every F2/F3 standard step has behavior fixture.

## Distribution

- **UAT-DIST-001:** fresh Linux x64 installs with SDKMAN and runs `pipeline version` without system JDK dependency.
- **UAT-DIST-002:** fresh macOS arm64 installs via Homebrew and executes sample pipeline.
- **UAT-DIST-003:** asdf install/upgrade selects requested semantic version.
- **UAT-DIST-004:** mise installation from chosen backend selects pinned version.
- **UAT-DIST-005:** checksums/signatures/SBOM are present and validated.
- **UAT-DIST-006:** upgrade preserves user config and does not corrupt run data.
