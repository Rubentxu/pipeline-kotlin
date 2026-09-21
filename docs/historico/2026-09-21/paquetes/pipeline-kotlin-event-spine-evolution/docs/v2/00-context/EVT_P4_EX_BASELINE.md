# EVT baseline — CTX-P4-EX real executable examples

Status: **BASELINE / already closed before EVT**  
Authoritative starting commit: `d0ccf4b572cd80a5ff48ac55af58b265603bf566` (`d0ccf4b5`)  
Parent: `a9df918df19084362190c58cd5e45cf7b48ade50`

## Why this document exists

EVT starts **after** the CTX-P4-EX gate. The following capabilities are evidence already present on trunk; they are not tasks to re-implement. The Event Harness evolution must preserve them and progressively move their ad-hoc shell assertions into reusable protocol/contract machinery without weakening any assertion.

## Closed P4-EX gate

`examples/run.sh` executes the real `:pipeline-application:installDist` binary and exits 0 when all expected outcomes/contracts hold. Baseline: **10/10 examples GREEN** (~70 s in the closure run).

| Example | Baseline contract at d0ccf4b5 | Status entering EVT |
|---|---|---|
| 01 hello | real CLI success | CLOSED |
| 02 multi-stage | real CLI success, stage ordering visible | CLOSED |
| 03 shell | real OS process success | CLOSED |
| 04 Kotlin control-flow | real scripting path success | CLOSED |
| 05 failing-step | expected non-zero / typed SCRIPT failure | CLOSED |
| 06 durable | runs twice with same `--db`; current durable semantics documented honestly | CLOSED |
| 07 catch-error | exactly two `CatchErrorTriggered`, inner FAILURE → outer UNSTABLE; post-catch echo | CLOSED |
| 08 parallel | second run same `--db`: terminal aggregate reuse, zero new branch/step lifecycle | CLOSED |
| 09 retry | `RetryAttemptFinished` failed → succeeded; deterministic marker fixture | CLOSED |
| 10 timeout | `TimeoutScheduled` + aborted shell; terminal FAILURE | CLOSED |

## Known findings carried into EVT

These are **constraints/debt**, not EVT regressions:

1. CLI flags are positional: durable flags must precede the script; trailing flags are currently silently ignored. Strict rejection is a separate CLI item.
2. Durable default is `ReusePriorRun`; `--rerun`/`--resume` are explicit modes. Do not rewrite this semantic while building EVT.
3. CLI durable reruns can re-emit prior journal events with original timestamps; P4-EX scopes "new event" assertions using `occurredAt > previous max`. This is known merged-stream debt (`INC-021d`). EVT should ultimately replace timestamp workarounds with first-class event/run/cursor identity, but must characterize before changing behavior.
4. Timeout example terminal outcome is FAILURE; UNSTABLE belongs to catchError semantics.

## Migration law for EVT-3

> Existing P4-EX assertions are the RED/GREEN behavioral oracle. The reusable Event Harness may replace their implementation mechanism only after proving semantic parity against the same real examples.

Required migration sequence:

1. characterize each existing `run.sh` assertion;
2. encode the equivalent typed protocol/scenario constraint;
3. run old and new verifiers over the same captured event history;
4. require equal verdicts for the 10 valid examples plus deliberate invalid mutations;
5. remove an ad-hoc shell assertion only after the new verifier owns that law.

No example is renamed/replaced merely to fit the harness design.
