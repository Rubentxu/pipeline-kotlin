# M3 exit evaluation — PASS

Date: 2026-09-02
Base: `7264e24` (M2 exit head, pre-M3)
Scope: LF-0301..LF-0309
Head: `f5fabfb` (LF-0308 ProcessExecutor deleted; LF-0309 gate enforced by FArchM3)

## Decision

**M3 exits PASS.** The milestone delivers the **Canonical Task Runtime**:
one execution algorithm (`ProcessDurableTaskRuntime` inside the
`pipeline-step-sdk:runtime` module) that all process-spawning paths
flow through — git, tar, sh, SDK sh. The exit criterion — *ProcessBuilder
exists in exactly one authorised module; outcome is faithful; memory
is bounded* — holds **by construction** because FArchM3 enforces it
as a CI-gating test (census + negative-existence pin).

The runtime invariants, hard-won over LF-0302..0308:

| Invariant | Where enforced | Why it matters |
|---|---|---|
| One `ProcessBuilder(` per `TaskSpec` type | `ProcessDurableTaskRuntime` switch | Storage / step type / mode can no longer pick a different spawn algorithm |
| Bounded memory on stdout / stderr | `ExecutionOutputSink` → 8 KB chunks via `Dispatchers.IO` pump | A 100 MB log never materialises in heap |
| Tree-kill on timeout | `descendants() + destroyForcibly()` in `finally`, before pump join | A forked `git fetch` cannot leak past its parent process |
| Env coercion at hand-off | `TaskExecutionRequest.env: Map<String, SecretHandle>` — runtime materialises | Secrets are never serialised, logged, or persisted |
| Coroutine-aware cancellation | Pumps dispatch on `Dispatchers.IO`; caller decides dispatch | Tests can cancel mid-port deterministically |

## Exit criterion checklist

> ProcessBuilder existe en un único módulo autorizado; outcome
> es fiel; memoria acotada. Cualquier llamada nueva fuera del módulo
> runtime falla el build.

| Sub-criterion | Status | Evidence |
|---|---|---|
| LF-0301 TaskSpec domain port | PASS | `TaskSpecTest` 14/14 — sealed hierarchy: `ExecTask(argv)`, `ShellScriptTask(interpreter, script, args)`, `StreamTask(consumer)`. Effects & replay policies are domain-level enums |
| LF-0302 DurableTaskRuntime port | PASS | `ProcessDurableTaskRuntimeTest` 11/11; tree-kill probes (`pgrep -P`), bounded chunks (8 KB), 100 MB in 0.22s, `runBlocking` per-chunk bridge |
| LF-0303 + LF-0304 production adapter | PASS | single switch in `ProcessDurableTaskRuntime.execute` covers all 3 `TaskSpec` variants; `descendants()` walked before `destroyForcibly()` |
| LF-0305 git checkout / poll / changelog via ExecTask | PASS | `GitCheckoutExecutor`, `GitPollExecutor`, `GitChangelogWriter` all routed via `runtime.runCaptured`; stdout+stderr merged into one capture per logical op |
| LF-0306 tar archive via ExecTask | PASS | `TarWriter` streams via `OutputStream.write + MessageDigest.update` chunk-by-chunk — `readAllBytes()` removed |
| LF-0307 sh non-durable fallback via ShellScriptTask | PASS | `ShExecution.executeNonDurable` (4 sites: 76/155/191/232) routes through `ProcessDurableTaskRuntime`; env flows typed through runtime |
| LF-0308 delete ProcessExecutor | PASS | `ProcessExecutor.kt`, `ProcessExecutorTest.kt`, `ShellResult.kt` deleted; `StepExecutors.sh` is now `suspend fun sh(...): Int` returning exit code, going through runtime; dead `ShellResult` import removed from `PipelineRun.kt` |
| LF-0309 single-home gate | PASS | FArchM3 global census: every `ProcessBuilder(` across the v2 tree (excluding `pipeline-step-sdk/runtime/src/main/kotlin`) must be inside a comment / string literal; live occurrences fail the build. Three negative-existence assertions cover the recently-deleted legacy classes |

## UAT M3-001..010 status

| UAT | Requirement | Status | Evidence |
|---|---|---|---|
| M3-001 | one authorised ProcessBuilder home | **PASS** | FArchM3 census pin; live code search confirms zero outside runtime module |
| M3-002 | git tasks use runtime | **PASS** | `GitCheckoutExecutorTest`, `GitPollExecutorTest`, `GitChangelogWriterTest` (8/8 on git credentials applier + 4/4 fold + 4/4 scrub) |
| M3-003 | tar tasks use runtime with bounded memory | **PASS** | `TarWriterTest` 3/3; `LocalArtifactStoreTest` 15/15; 100 MB tar in 0.22s |
| M3-004 | sh tasks use runtime (non-durable) | **PASS** | `UatStep001..004` 8/8 (executor / failure-count / echo / sleep-timing) |
| M3-005 | SDK sh migrated to runtime | **PASS** | `ParallelFrameExecutorConcurrentTest` 6/6 — FIRST_SUCCESS + ANY_COMPLETE across 3 concurrent branches |
| M3-006 | sh-env integrity (no string coercion) | **PASS** | `ShOptionsTest` 7/7, `DurableShConfigTest` 8/8, `EnvModel*` 39/39 (path-plus + masked-entry + base) |
| M3-007 | bounded memory on capture | **PASS** | `ProcessDurableTaskRuntimeTest` chunk-bounded case: 100 MB never materialises |
| M3-008 | tree-kill on timeout | **PASS** | `ProcessDurableTaskRuntimeTest` pgrep probe: child + grandchild die; pump joins succeed; no orphaned shell processes (pgrep clean) |
| M3-009 | exit-code fidelity (success + non-zero + signal) | **PASS** | 11/11 tests cover exit 0, exit 42, SIGKILL, SIGTERM, timeout, no-such-command, malformed-args |
| M3-010 | cancellation hygiene | **PASS** | `ParallelFrameExecutorConcurrentTest` exercises cancellation across concurrent branches |

## Characterisation GREEN

Round-gate receipts on head `f5fabfb`:

| Suite group | Tests | Status |
|---|---:|---|
| Architecture M3 (Canonical Task Runtime pin) | 7/7 | PASS — fresh XMLs |
| runtime module (DurableShellExecutor adversarial + reconciler + envmodel + sandbox + shoptions + lsp metadata + parallel frame + ProcessDurableTaskRuntime) | 154/154 | PASS |
| scm-git (FoldInGitChk + GitCredentialsApplier + ReasonScrub + structural pin tests) | 16/16 | PASS |
| artefacts-local (AntStyleGlob + LocalArtifactStore + TarWriter) | 32/32 | PASS |
| application affected (UatLocal004-009 + UatEvt + UatStep + UatDsl + ParallelFrame + PipelineRun families) | 125 | 9 pre-existing base-isolated (see register) — zero regressions |

Selected SHA-256 receipts (full set in build/test-results):

| Suite | SHA-256 |
|---|---|
| `FArchM3CanonicalTaskRuntimeTest` 7/7 | `8955455075b6d282b58248e407844dbc87b2576e358947e845b9572c84321912` |
| `ProcessDurableTaskRuntimeTest` 11/11 | `3d33f56c40baaaee6c047058fee8c4b0f791aa899d607d688082d75892f2d77f` |
| `DurableShellExecutorAdversarialTest` 28/28 | `7f693f0acf27b2448486c8f38af43fbe223fd658b42a945c815338197c660acb` |
| `ParallelFrameExecutorConcurrentTest` 6/6 | `367db0b35b0845b8e07ee2b2b8c468d387f35e9770b4858457538a0ff2a496d3` |
| `EnvModelTest` 18/18 | `576d3e7279127830b3cb4075d23f3ba8220fdad13aecf41188e5a75875491992` |
| `LocalArtifactStoreTest` 15/15 | `628a07ab5831b4415d13ce3f0e9af78b5e025d58844f01481fd27f0014c53128` |
| `TarWriterTest` 3/3 | `91a19cb963f05ed918b2e71733da9124e627bad5d0c205ba5fdfcc1dc3eadef2` |
| `GitCredentialsApplierTest` 8/8 | `5f750e097f6b186bc57e4203a4a794528427649973207f618233f2803c82104b` |
| `UatLocal007SandboxProfileTest` 12/12 | `f3ed18556c0e8d127532992aa1276a745e5673b33425ba6b29de47c021c0528d` |
| `UatLocal005CheckoutGitTest` 11/12 | `74cf964699d952e345a1b8d18fbb67d3e2af3399f883833290154f652cd177e8` *(pre-existing SC-007 red)* |
| `UatLocal008CredentialsTest` 18/26 | `761c69d3059966633696ed1abdcc70f96f5bc6f21b3616c58bee7ee832193284` *(pre-existing CR-BD-018/019/020/021/022/026/027/032 reds)* |

## Known-red register (pre-existing, base-vs-head evidence per AGENTS §16)

All reproduced at the M2 base (`7264e24` lineage, worktree at PR #16
head) **before** LF-0302+0303+0304 code existed; further isolated at
`3d7a69b` (LF-0305+0306 merge) and `10039d7` (LF-0307 merge) before
this commit — none are M3 regressions:

| Item | Owner | Evidence |
|---|---|---|
| `UatLocal008` ×8 (CR-BD-018/019/020/021/022/026/027/032) | M4 credentials consolidation | identical failures at base worktree run; CR-BD-018/019/020/021/022 are path-binding-to-env-var tests awaiting M4 lease→projection contract; CR-BD-026/027/032 are CredentialBound/CredentialUsed/withCredentials-shadow semantics, both gate on M4 |
| `UatLocal005.SC-007` | environment (host git-wrapper fail-closed policy) | identical `IllegalStateException` at base; reproduced at PR #8 head during M1 and PR #16 head during M2 |
| `UatLocal007.SB-S-007` (flaky 1/3) | test fragility (`findOpId` substring match) | not in this run (12/12 here); base-isolated during M1/M2; carried as fragility rather than regression |
| `walkParallelFrame` internal dispatch not yet via `StepDispatcher` port | carried into M4 | boundary documented in PR #15/#16; storage can no longer select it (M2 invariant preserved) |
| sh stdout capture inert on credentials paths (CR-BD-022) | M4 | runtime path proves it works (TarWriter, GitCheckoutExecutor tests); the credential path is a separate pipeline still routing through a stub |

## Boundary carried into M4

1. **Credentials lease / projection / environment composer** —
   the CR-BD-018..032 reds are unblocked when M4 lands:
   `CredentialLease`, `Projection`, `EnvironmentComposer` must produce
   `Map<String, SecretHandle>` that the runtime materialises at hand-off.
2. **Workspace access** — runtime invariant extends to `chmod` /
   bind-mount composition for sandboxed step variants.
3. **Streaming output** — the streaming-sink branch of `TaskSpec`
   (`StreamTask`) is the contract hook but no production user yet;
   `TarWriter`'s streaming-sink consumer is the reference.
4. **Durable sh path migration** — `DurableShellExecutor` inside the
   runtime module still owns its own `ProcessBuilder(` (within the
   authorised home). Routing that through the canonical runtime
   is a separate slice — its `descendants()` + `wipeInFinally` semantics
   would need to compose with the runtime's own tree-kill.
5. **`PipelineRun.kt`** remains long; further decomposition is ongoing
   debt work, not M3 scope.

## Cross-references

- Roadmap: `docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md` §M3
- Spec: `docs/v2/03-specifications/DURABLE_TASK_RUNTIME_SPEC.md`
- UAT plan: `docs/v2/07-uat/LOCAL_FIRST_UAT_PLAN.md` §M3
- PR stack: #18 (LF-0301+0302 ports), #19 (LF-0302/0303/0304 + LF-0305+0306), commits `10039d7` (LF-0307) and `f5fabfb` (LF-0308)
- Architecture pin: `v2/pipeline-architecture-tests/.../architecture/FArchM3CanonicalTaskRuntimeTest.kt`
- Single runtime spine: `docs/v2/02-architecture/SINGLE_RUNTIME_SPINE.md`
