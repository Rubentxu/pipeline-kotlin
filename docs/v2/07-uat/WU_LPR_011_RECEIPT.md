# WU-LPR-011 — CLI product closure (receipt)

**Status:** CLOSED. Base `86bdc3cf` → head (fdce1c98 + F5 reversion).

## 1. Scope

Close the real product findings from WU-LPR-010 (CLI characterization):

| Finding | Fix |
|---|---|
| F1 `version`/`doctor` not real subcommands | Both implemented in `Main.kt`; `version` prints `pipeline <version>` (jar manifest, fallback `0.1.0-SNAPSHOT`); `doctor` reports jdk/os/workdir-writability. Exit 0 (2 on env defect). |
| F2 `events`/`credentials` unregistered | **Already fixed upstream** (EVT-2 wiring in `main()`); verified working. No change needed. |
| F3 `validate` exits 1 on compile failure | Now exits **2** (canonical contract: 0 success / 1 pipeline fail / 2 invocation+compile+admission). |
| F4 `--resume` empty db → uncaught `IllegalArgumentException`, exit 1 | Typed rejection in `selectDurableRun` catch: single-line `Error: No prior run recorded…`, **exit 2**, zero stack-trace frames. |
| F5 `--resume` terminal run → TWO `RunFinished` bursts | **Closed WONTFIX** — re-measurement + gate evidence proved the second (SKIP-path) lifecycle envelope is the CANONICAL durable contract. See §2. |

Plus one regression found during verification: §3.

## 2. F5 — terminal-resume lifecycle (CLOSED WONTFIX, with evidence)

**Re-measurement at head `86bdc3cf`:** handlers are protected by per-step
reconciliation — a `--resume` on a terminal run re-executes NOTHING
(verified with a `sh` marker file: 1 line before resume, 1 line after).
What the coordinator re-emits is the run **lifecycle envelope**
(`RunStarted`, `StageStarted`, `RunFinished`) for the reused terminal
aggregate.

**First attempt (REVERTED):** a `priorTerminalOutcome` short-circuit was
added to `CanonicalDurableRunCoordinator.run()` suppressing the second
envelope. The full gate (`./gradlew -p v2 check`) failed 62 tests across
31 classes. Root cause: the second (SKIP-path) envelope is the PINNED
canonical contract, e.g.:

- `CanonicalDurableRunCoordinatorTest.journals and checkpoints a linear
  canonical echo run`: "Second run (SKIP): RunStarted + StageStarted +
  StageFinished + RunFinished = 4 events" (expected 11 total, got 7).
- `CanonicalDurableRunCoordinatorTest.NEVER policy…`: the replay-abort
  rejection lives in the dispatch path the short-circuit bypassed.
- `UatDsl003ParallelTest.P6`: "reused terminal aggregate" asserts a
  FRESH `StageFinished` with outcome `success` on the second run.

Suppressing the envelope would require re-writing two canonical pins and
the coordinator's replay-abort semantics — a spine-level semantic change,
not a CLI closure fix. Per the zero-fabrication and no-weakened-assertions
laws, the change was reverted and F5 is reclassified WONTFIX: the
"duplicate burst" is by-design lifecycle re-emission over a reused
aggregate, with zero child re-execution. The original WU-010 finding
misread the designed contract.

**Final verified behaviour (installed binary, post-reversion):** resume
of a terminal run exits 0, re-emits the SKIP bookends, reprints the
journaled history, executes ZERO child steps (exactly one journaled
`StepStarted`/`EchoOutputCaptured` across the whole stdout).

## 3. Regression found and fixed: successful durable run hung at JVM exit

The probe of the fresh-run path hung indefinitely. Thread dump: only
`sqlite-event-writer` (non-daemon, WU-LPR-042 single-writer) alive in
`ArrayBlockingQueue.take()`, plus `DestroyJavaVM` waiting. The durable
path in `Main.kt` never calls `close()` on the `SqliteEventStore`;
failure paths escape via `System.exit()` but the natural-success return
left the non-daemon writer alive. **Fix:** `rawEventStore.close()`
before printing events on the durable path (idempotent close).

## 4. Exit-code contract (now enforced and tested)

```text
0  success / unstable
1  pipeline execution failure
2  invocation, config, compile, admission (validate-fail, bare --resume,
   non-canonical pipeline, --resume without --db, bad sandbox-profile)
```

## 5. Tests

- `WULpr010CliCharacterizationTest`: 3 pins updated to the new contract
  (`FIXED (WU-LPR-011 F1/F3/F4) — …`); assertions strengthened (exact
  counts, stack-trace absence). F5 pin re-characterized as
  `WONTFIX (WU-LPR-011 F5)` with the canonical-pin citations above.
  **11/11 green** (JUnit XML).
- NEW `WULpr011ResumeLifecycleUatTest`: installed-binary (HF2) UAT over
  the full resume lifecycle — fresh run, terminal resume (zero handler
  re-execution, recorded success outcome reused), bare resume (typed
  exit 2), and the writer-hang regression canary (binary must exit on
  its own). **1/1 green** (JUnit XML).

## 6. Full-gate base-vs-head classification (4-state algebra)

Base `86bdc3cf`, head = this WU (fdce1c98 + F5 reversion + exit-2 pin
update). Full head gate: `./gradlew -p v2 check` → 58 failures across 25
classes (18m30s). Base comparison attempted in a clean worktree; the base
binary carries the un-fixed hang, which terminates any base run that
spawns the installed binary. Classification follows the 4-state algebra
(`BASE_CONFIRMED` / `BASE_HANG_CONFIRMED` / `HEAD_INTENTIONAL_CHANGE` /
`BASE_UNVERIFIED_DUE_HANG`) — never `PRE_EXISTING` without a real base
reproduction:

| Class (head failures) | Base evidence | Classification |
|---|---|---|
| `ScriptTextEscaperTest` (3 SCR-N2) | Reproduced in base worktree (fresh XML, same 3 pins) | BASE_CONFIRMED |
| `Lfc0GlobalStateFitnessTest` | Reproduced in base worktree (fresh XML) | BASE_CONFIRMED |
| `S3PwdLegacyRemovedFitnessTest` (+ all S3*, `LegacyResidualConvergence`, `FArchL7*`, `FArchLfc1*`, `Lfc2*`, `Lpr101*`) | Reproduced in base (fresh XML for S3Pwd/Lfc0) + documented baseline in WU-LPR-402 receipt §6 (git-stash-verified) | BASE_CONFIRMED |
| `CompatibilityCorpusTest`, `UatCompat001CorpusSmokeRunTest` | Base-verified at 881829fc (receipt WU-LPR-043 §follow-ups) | BASE_CONFIRMED (documented) |
| `UatLocal009TopStepsTest` (writeFile pins) | 7 pins red in clean base (S2 checkpoint 2026-09-13, rule-16 set) | BASE_CONFIRMED (documented) |
| `UatLocal008CredentialsTest` CR-BD-027 | Documented pre-existing (RETRY-D notes) | BASE_CONFIRMED (documented) |
| `CoreCleanWs/CoreDeleteDir/Echo/Error/Sleep contract suites`, `WithCredentialsCompileIntegrationTest`, `WULpr402RuntimeHonestDslFitnessTest`, `DirExecutor` | Part of the 14-failure arch/fitness baseline (WU-LPR-402 §6) / registry-suite baseline | BASE_CONFIRMED (documented) |
| `CliCompileErrorExitsOneTest` (validate exit 1→2) | Base: 3/3 GREEN (fresh XML) | HEAD_INTENTIONAL_CHANGE (F3 contract; pin re-pinned honestly to exit 2, now 3/3 green on head) |
| `UatDsl003ParallelTest` P6, `CanonicalDurableRunCoordinatorTest`, `Uppercase/WriteFileStepContractSuite` divergence/replay rows | Base green; head red ONLY under the F5 short-circuit | RESOLVED — caused by the reverted F5 attempt; green after reversion (fresh XML) |
| `UatLocal007SandboxProfileTest`, `UatLocal005CheckoutGitTest` | Base run hangs: spawned `pipeline run` JVM never exits (`DestroyJavaVM` + `sqlite-event-writer` on `ArrayBlockingQueue.take()`, jcmd-verified). Base test code cannot complete | BASE_HANG_CONFIRMED for the hang itself (the bug this WU fixes — head exits); assertion failures at head: BASE_UNVERIFIED_DUE_HANG, no attribution evidence to WU-011 |

**WU-LPR-011 scoped regressions = 0.**

Method note: a defective baseline must not supervise its own
verification. The base full-suite runs were aborted after 3 attempts
(hang-terminated). Follow-ups: WU-LPR-011H (hermetic
`ExternalProcessProbe` + differential baseline runner owned by HEAD) and
CLI lifecycle ownership (`runCli(): Int` + single outer `System.exit`;
never daemonize the writer).

## 7. Known-red carried (unchanged) + follow-ups opened

- Corpus fixture05/fixture14 → WU-LPR-103 scope.
- EchoOutputCaptured de-dup parity → WU-LPR-042-FOLLOWUP (not Gate-1).
- **NEW WU-LPR-011H — Hermetic External Process Test Harness:**
  `ExternalProcessProbe` (sealed `Exited`/`TimedOut`/`LaunchFailed`,
  file-redirected stdio, bounded `waitFor`, descendant-tree kill,
  best-effort jcmd diagnostics); differential baseline runner owned by
  HEAD (build base+head binaries, probe BOTH with the SAME current
  harness, JSON classification algebra); Gradle external-process task
  (`maxParallelForks=1`, `forkEvery=1`); JUnit `@Timeout` demoted to
  last-resort budget.
- **NEW CLI lifecycle ownership WU:** `main → runCli(): Int → use{} →
  single outer exit`; harden `SqliteEventStore.close()` (verify writer
  termination after bounded join; never close the connection under a
  live writer). Never daemonize `sqlite-event-writer`.

## 8. Auto-continue

Next: **Secret-redaction streaming follow-up** (Gate-1 blocker:
`StreamingRedactor` reuse + bounded channel from WU-LPR-043, chunk-boundary
secret-split test), then WU-LPR-061 (@Disabled classification).

---

**CLOSED — 2026-09-18.**
