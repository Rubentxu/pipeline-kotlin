# S2-A2 / G8 — Final Certification Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A2 (`core.sleep`)
> Gate: **G8 — Final Certification (real installed CLI)**
> Branch HEAD: `759b13c3` (G6 close; G8 produces no new code)
> Date: 2026-09-11T13:59Z
>
> G8 closes S2-A2 and flips `CERTIFIED = true`.

## 1. Purpose

G8 is the **real distribution evidence** for `core.sleep`. G5/G6 certified the
removal and the contract seams; G8 certifies that the public DSL → script
compiler → canonical IR → production registry → durable coordinator → installed
CLI distribution path delivers exactly the MEMOIZED semantics G6 pinned.

No production code or abstractions were produced at G8. Evidence:

```text
installDist   : ./gradlew -p v2 :pipeline-application:installDist → BUILD SUCCESSFUL (UP-TO-DATE)
binary        : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
scenario      : v2/compatibility/16-sleep.pipeline.kts  (sleep(1); echo("woke"))
db/control    : /tmp/sleep-g8/{db,ctl} — SAME for fresh and replay
```

## 2. Fresh execution (real CLI)

```text
command : pipeline-application run --db /tmp/sleep-g8/db --control-root /tmp/sleep-g8/ctl 16-sleep.pipeline.kts
exit    : 0
events  : CompilationStarted → CompilationFinished → RunStarted → StageStarted
          → StepStarted(sleep-step/sleep-0, sleep) @13:58:39.580
          → StepFinished(sleep-step/sleep-0)             @13:58:40.586   (~1.0s wall)
          → StepStarted(sleep-step/echo-0, echo)
          → StepFinished(sleep-step/echo-0)
          → StageFinished(success) → RunFinished(outcome="success")
```

`sleep` executed by the registry path (real ~1s suspension), `echo("woke")` ran after.

## 3. Replay execution (same `--db` / `--control-root`)

```text
exit : 0
step event sets IDENTICAL to fresh (same eventId/sequence/timestamps):
  MEMOIZED journal reuse — the sleep handler did NOT re-execute.
  RunFinished(outcome="success")
```

## 4. Negative control (fail-closed on the installed distribution)

```text
command : sleep(-1) through the real CLI (fresh db/control)
exit    : 1
events  : no StepStarted; RunFinished(outcome="failure")
          rejected before any effect (CoreSleepInput invariant / fail-closed decode)
```

## 5. Gate ledger close

```text
REGISTERED         = true   (G1)
REGISTRY_PRIMARY   = true   (G3/G4)
LEGACY_UNREACHABLE = true   (G4)
LEGACY_REMOVED     = true   (G5, 8535c0bf)
CONTRACT_SUITE     = true   (G6, 759b13c3 — 21/0/0/0)
CERTIFIED          = true   ← G8 (this receipt)
```

Counters at close: `LEGACY_PLUGIN_IDS = 10`, metadata `= 10`, dispatchers `= 10`.

## 6. Side effects

None. G8 ran the existing installed binary (rebuilt UP-TO-DATE at HEAD
`759b13c3`), observed behavior, and recorded evidence.
