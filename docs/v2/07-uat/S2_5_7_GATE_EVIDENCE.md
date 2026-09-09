# S2.5.7 Gate Evidence — sddk-verify + 19 pre-existing failures (2026-09-09)

This document records the evidence for closing **S2.5.7 spine consolidation** at
`fa7d91b0` and the formal gate acceptance before opening the S3 burn-down.

## Slice summary

S2.5.7 spine-consolidation slice — COMPLETE, 6 WUs landed:

| WU | Commit | Description |
|---|---|---|
| WU-1 | `9024e46e` | `StepAdmissionLaws` pure observer (7 `AdmissionReport` variants + `ReconciliationResolution` enum) + `StepAdmissionObserved` domain event (44-event sealed-hierarchy exhaustive; 5 stores + 2 tests follow-on). |
| WU-2 | `19abc508` | `FamilyRouter` + `FamilyRoutingDecision` sealed ADT (`LegacyOnly` data object \| `SeamedRouting(legacy, registry)` data class) + pure `decide(...)`. |
| WU-3 | `60663cc8` | `ExecutionBoundaryFactory` named producer seam + recorder decorator + `RecordingBoundary`. |
| WU-4 | `300e061e` | Forwarder in `CommonExecutionBoundary.kt` + **factory binary-policy correction** (bit-equivalent to the legacy inline `if (stepRegistry != null)` branch). |
| WU-5 | `f5549178` | Coordinator wires `ExecutionBoundaryFactory.build(...)` directly. |
| WU-6 | `df745d77` | `StepAdmissionObservedTest.kt` (9 tests). |

Gate `fa7d91b0` is the round close. Tree clean. 25 commits ahead of `origin/main` at `c3aaab8a`.

---

## Gate 1 — sddk-verify PASS

Verdict: **PASS — 0 regressions introduced by S2.5.7 spine-consolidation slice.**

Fresh canary evidence (HEAD `fa7d91b0`, 2026-09-09T09:33Z, all `--rerun-tasks`, sha256 + XML timestamps captured):

| Test class | tests | failures | errors | XML SHA-256 | timestamp |
|---|---|---|---|---|---|
| `StepAdmissionObservedTest` | 9 | 0 | 0 | `80994bc35d0232d3db34a404a1ae223251939a4efe5e63cf81e60a1b6a099d1a` | `2026-09-09T09:33:32.103Z` |
| `ExecutionBoundaryFactoryTest` | 4 | 0 | 0 | `0274fa18ed904dc4e425718c3c51a9461792cd3c32e40cd8d4c9a2cf88639086` | `2026-09-09T09:33:32.068Z` |
| `SeamedExecutionRouterTest` | 2 | 0 | 0 | `3d3afdea380b41fb98196a427d1409d7cf50634e344b04b377a03e80bbb7f195` | `2026-09-09T09:33:32.099Z` |
| `DurableProtocolInvocationCharacterizationTest` | 8 | 0 | 0 | `a2e2cd49da0d7dd144d298609566da564a681d91444d83d98f3aac59b120d272` | `2026-09-09T09:33:31.995Z` |
| `EchoDurableSpineTest` | 3 | 0 | 0 | `ef37dc776907352c118006cd077d925e0e503febfe4cfd0be94c452a422f1524` | `2026-09-09T09:33:32.057Z` |
| `RegistryDurableSpineTest` | 5 | 0 | 0 | `482d1be8da5358f00428c056373b3a1ccf542c998bd52ac2c62839fa821588f1` | `2026-09-09T09:33:32.080Z` |
| `CanonicalCoordinatorScopeStackTest` | 5 | 0 | 0 | `694b4f6095ea90e5b3278d0b14ccc2f38bef1cfb55f0f5714ee38c32eac8cb69` | `2026-09-09T09:33:31.406Z` |
| **TOTAL spine** | **36** | **0** | **0** | log `/tmp/verify-s257-spine.log` | — |

L4 fitness guardrail: `timeout 600 ./gradlew -p v2 :pipeline-architecture-tests:test --rerun-tasks` — BUILD SUCCESSFUL in 1m 3s (33 tasks executed), log `/tmp/verify-arch.log`.
L5 events module: `timeout 600 ./gradlew -p v2 :pipeline-events:test --rerun-tasks` — BUILD SUCCESSFUL in 9s.

### Invariants preserved

- **Single production routing authority** — `SeamedExecutionRouter` via `buildDefaultExecutionBoundary` (the new factory is bit-equivalent to the inline `if (stepRegistry != null)` branch).
- **No second router** introduced.
- **No new domain event** beyond `StepAdmissionObserved` (44-event sealed-hierarchy exhaustive; all stores updated).
- **Capabilities used == capabilities declared** (`EVENT_SINK_CAPABILITY` + fingerprint + replay policy); no new capability introduced.
- **Per-step observability** is the law — `StepAdmissionLaws.observe(resolution, executorCalls)` is the typed event each observer reads.

---

## Gate 2 — 19 pre-existing failures demonstrated at base `c3aaab8a`

Worktree method: fresh base worktree `/tmp/base-c3aa-fresh` cloned at `c3aaab8a` (the `origin/main` checkpoint); all 19 failures re-executed `--rerun-tasks` against the base. **All 19 reproduced with identical failure signatures.** Logs preserved with SHA-256:

- `/tmp/base-c3aa-fresh-scripts.log` sha256 `3505f708a6346165bb0794eeba2b5d148536ec379455d2349a3445a02b0c0504` (scripting-kotlin24 family)
- `/tmp/base-c3aa-fresh-uat-local.log` sha256 `2646fb989fa9f6ee47393c10044416e97bf6b477323f836bdbf7659d2cf8320b` (UAT-Local with-Path family)
- `/tmp/base-c3aa-fresh-app.log` sha256 `db98fe0bd4b76e1fcc62b14d084d99fe2759aa3d710c9db29ec7d1386f0ee32e` (corpus + grammar)

### Stable pre-existing failure table (19 rows)

| # | Test | Failure signature | Baseline commit | Classification |
|---|---|---|---|---|
| 1 | `dev.rubentxu.pipeline.v2.scripting.ScriptTextEscaperTest.SCR-N2 line comment is not processed` | assertion mismatch: comment `$`-escaping drift (impl escapes comments; test expects raw). EM-7 phase baseline dated 2026-09-08 | `c3aaab8a` (pre-`49111c88` body), pre-S2.5.x | pre-existing host/impl drift, NOT introduced by S2.5.x |
| 2 | `ScriptTextEscaperTest.SCR-N2 single quotes string is not processed` | assertion mismatch: same `$`-escaping drift inside single-quoted strings | `c3aaab8a` (and earlier `0ad4be3`) | pre-existing |
| 3 | `ScriptTextEscaperTest.SCR-N2 block comment is not processed` | assertion mismatch: same `$`-escaping drift inside `/* */` blocks | `c3aaab8a` (and earlier `0ad4be3`) | pre-existing |
| 4 | `WithCredentialsCompileIntegrationTest.IT-001 simple usernameColonPassword binding compiles and returns success` | `withCredentials`/`sh` unresolved + zip arity drift; EM-7/F-1..F-8 baseline | `c3aaab8a` (and earlier) | pre-existing (EM-7/credentials scope) |
| 5 | `WithCredentialsCompileIntegrationTest.IT-002 multiple bindings in single withCredentials compile successfully` | same EM-7 binding shape drift | `c3aaab8a` | pre-existing |
| 6 | `WithCredentialsCompileIntegrationTest.IT-003 mixed file and credential bindings compile successfully` | same EM-7 binding shape drift | `c3aaab8a` | pre-existing |
| 7 | `WithCredentialsCompileIntegrationTest.IT-006 zip factory binding compiles successfully` | zip factory arity drift | `c3aaab8a` | pre-existing |
| 8 | `CompatibilityCorpusTest.fixture14CredentialsBindings` | non-canonical `withCredentials` plugin; canonical bridge fails closed with exit 2 by design (AGENTS.md STEP SEMANTICS #3). Fixture classified as expected runtime fail. | `c3aaab8a` | pre-existing (INC-021c deferred) |
| 9 | `UatCompat001CorpusSmokeRunTest.corpus smoke-runs green and satisfies M2 exit criterion` | same fixture14 exit-2 classification propagates to the smoke run | `c3aaab8a` | pre-existing |
| 10 | `UatDsl005TimeoutGrammarTest.timeout-retry script emits retry attempt events` | timeout-retry script grammar gap (EM-6/EM-5 catchError/warnError semantically deferred; LFC1-007 catchError pre-compiler-rewritten deprecation) | `c3aaab8a` | pre-existing (EM-6 catchError scope) |
| 11 | `UatDsl005TimeoutGrammarTest.timeout-retry script emits timeout scheduled events` | same timeout-retry grammar gap | `c3aaab8a` | pre-existing |
| 12 | `UatLocal005CheckoutGitTest.SC-007 poll detects changed SHA and emits GitPollChanged` | IllegalStateException at `UatLocal005CheckoutGitTest.kt:714`; host-dependent (real git poll binary) | `c3aaab8a` | pre-existing host flake |
| 13 | `UatLocal007SandboxProfileTest.SB-S-008 parallel branches have isolated cwds` | AssertionFailedError at `UatLocal007SandboxProfileTest.kt:545` | `c3aaab8a` | pre-existing (sandbox profile gap, separate from E-EM-11 parallel) |
| 14 | `UatLocal007SandboxProfileTest.SB-S-010 resume with profile change none-to-local re-attaches` | AssertionFailedError at `UatLocal007SandboxProfileTest.kt:719` | `c3aaab8a` | pre-existing (sandbox profile resume gap) |
| 15 | `UatLocal008CredentialsTest.CR-BD-027 CredentialUsed per use` | credentials use-per-event gap (EM-7 hook surface partial) | `c3aaab8a` | pre-existing |
| 16 | `UatLocal008CredentialsTest.UAT-L8-CP-001 original 4 corpus files byte-identical to cycle base` | corpus byte-identity drift (credentials binding output changed) | `c3aaab8a` | pre-existing |
| 17 | `UatLocal009TopStepsTest.CR-U9-008 archiveArtifacts sha256 and size in event` | AssertionFailedError at `UatLocal009TopStepsTest.kt:?` (archiveArtifacts shape) | `c3aaab8a` | pre-existing (archiveArtifacts gap) |
| 18 | `UatLocal009TopStepsTest.CR-U9-011 archiveArtifacts AntStyleGlob pattern matches files` | same archiveArtifacts gap | `c3aaab8a` | pre-existing |
| 19 | `UatLocal009TopStepsTest.CR-U9-012 cross-step writeFile then archiveArtifacts picks up file` | AssertionFailedError at `UatLocal009TopStepsTest.kt:448` | `c3aaab8a` | pre-existing |

**Conclusion:** 0 regressions introduced by S2.5.7 spine-consolidation slice. All 19 failures pre-existing on `c3aaab8a`. None of the S2.5.7 WUs (`9024e46e`, `19abc508`, `60663cc8`, `300e061e`, `f5549178`, `df745d77`) altered the production call-site for any of these 19 tests. S2.5.7 spine is purely additive + bit-equivalent replacement of the inline `if (stepRegistry != null)` branch in `buildDefaultExecutionBoundary`.

### Evidence reuse from prior session

- The earlier receipt at `49111c88` body already cited 11 of the 19 failures as pre-existing (scripting-kotlin24 + the 4 application tests from corpus/grammar/credentials scope). 
- The earlier receipt at `2bef4d7b` S2.5.3 round gate captured 11 failures at base. 
- The current Gate 2 evidence **freshly reproduces** all 19 at base `c3aaab8a`, including the 8 UAT-Local-with-Path ones whose host-dependent flakes did not surface in the S2.5 gateway's earlier preset. 

---

## S2.5.7 close verdict

```text
S2.5.7 = DONE ✅
sddk-verify = PASS
slice regressions = 0
19 pre-existing failures = demonstrated at base c3aaab8a
remote checkpoint = TBD (push scheduled)
```

Next step: push `fa7d91b0` to `origin/main`, prove `LEGACY_UNREACHABLE` for `core.echo`, then begin S3.1 (`CanonicalCoreStepCommand.Echo` removal).
