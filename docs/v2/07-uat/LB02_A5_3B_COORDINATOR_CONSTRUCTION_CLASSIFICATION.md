# LB-02 / A5.3b — classification of the 22 bare coordinator constructions

Source: `durable/CanonicalDurableRunCoordinatorTest.kt`. Each construction is a
direct `CanonicalDurableRunCoordinator(...)` that omits `stepRegistry` (defaults
to `null` → the legacy authority). `test@101` was already migrated to the
registry family in `dfc8dbbd`; the remaining 21 are classified below.

Category meanings:
- **G1** — production-like behaviour test (durable/replay/cursor/journal/
  lifecycle/events/recovery); migrate to the productive registry composition.
- **G2** — intentional no-registry / fail-closed test; keep without registry.
- **G3** — legacy-family characterization of a step that is STILL legacy.
- **G4** — obsolete architecture test (existed only because Echo/Sh was a
  canonical sealed command); transform or remove, never blindly migrate.
- **G5** — unrelated construction; do not modify.

## Classification table

| ctor | test | method | category | Steps exercised | expected family | action |
| --- | --- | --- | --- | --- | --- | --- |
| @84 | @62 | continues after a default catchError failure and returns unstable | G1 (Sh) | `sh` (inside catchError) | Registry | migrate; confirm red cause is registry-echo, not a separate defect |
| @128 | @101 | reconciles a completed running canonical shell without relaunching | G1 (Sh) | `sh` (shell recovery) | Registry | **done** (`dfc8dbbd`) |
| @173 | @147 | projects a stage timeout into canonical shell execution | G1 (Sh) | `sh` (timeout) | Registry | migrate |
| @285 | @253 | journals a supported block child with its body path | G1 | `core.dir` body `core.echo` | Registry | migrate (echo as simple step) |
| @326 | @300 | propagates a dir block working directory to its shell child | G1 (Sh) | `core.dir` body `sh` | Registry | migrate |
| @343 | @338 | fails closed when a resumed canonical node diverges from its journal | G1 | `echo` | Registry | migrate (echo as simple step) |
| @374 | @358 | records a failing canonical shell run as a typed script failure | G1 (Sh) | `sh` (failure) | Registry | migrate |
| @414 | @387 | journals and checkpoints a linear canonical echo run | G1 | `echo` | Registry | migrate (echo as simple step) |
| @475 | @439 | dispatch decodes each StepNode before delegating to the typed dispatcher | G4 | `echo` | n/a | transform/remove (describes legacy typed-dispatcher model) |
| @515 | @494 | dispatch returns Failure SCHEMA and journals FAILED when decoder throws | G3 | `sh` (legacy decoder) | Legacy | legacy-Sh decode characterization; remove at burn-down, do NOT migrate |
| @561 | @537 | dispatch returns SCHEMA for a fresh structurally-valid but typed-invalid payload | G3 | `echo` (legacy decode) | Legacy | legacy-echo decode; pre-existing Echo debt, leave |
| @589 | @582 | run emits StepStarted before dispatch | G1 | `echo` | Registry | migrate (echo as simple step) |
| @618 | @611 | run emits StepFinished after dispatch | G1 | `echo` | Registry | migrate (echo as simple step) |
| @660 | @640 | StepFinished count equals 1 per step on failure path | G1 (Sh) | `sh` (failure) | Registry | migrate |
| @692 | @685 | No step events for ReplayDecision SKIP | G1 | `echo` | Registry | migrate (echo as simple step) |
| @728 | @724 | ReplayDecision ABORT emits one failed lifecycle without dispatching | G1 | `echo` | Registry | migrate (echo as simple step) |
| @787 | @757 | Exactly-once discipline - 3 steps emits 3 StepStarted and 3 StepFinished | G1 | `echo` | Registry | migrate (echo as simple step) |
| @828 | @814 | milestone dispatches MilestoneReached for strictly increasing ordinals | G3 | `core.milestone` (still legacy) | Legacy | keep (step still legacy) |
| @858 | @844 | milestone out-of-order ordinal emits MilestoneAborted and continues as Unstable | G3 | `core.milestone` (still legacy) | Legacy | keep (step still legacy) |
| @912 | @885 | withCredentials acquires scope overlays env and always closes | G1 (Echo) | `withCredentials` body `echo` | Registry | Echo/credential debt, NOT Sh; leave pre-existing red |
| @939 | @932 | withCredentials unavailable fails closed and never dispatches body | G2/G5 | `withCredentials` (no body run) | n/a | keep (credential fail-closed) |
| @982 | @958 | withCredentials cleanup failure folds a successful body to failure | G1 (mixed) | `echo`/`sh`/`milestone`/`emit.event` in `withCredentials` | mixed | Echo/credential debt, NOT pure Sh; leave pre-existing red |

## Pure-Sh closure to migrate first (A5.3d order)

Only the G1 constructions whose exercised Step is `sh` (normal Sh execution, not
legacy-Sh decode):

1. `@173` timeout into shell (cancellation)
2. `@326` dir-block working directory to shell child
3. `@374` failing canonical shell → typed script failure
4. `@660` StepFinished count 1 per step on failure path
5. `@62` catchError continuation (Sh body)

`@128` (recovery) is already migrated. `@515` is legacy-Sh **decode**
characterization and is deliberately excluded from the normal-Sh closure: it
asserts the legacy decoder error path and will be removed at Sh burn-down.

## Explicit non-targets (do not chase in this Sh closure)

- G1 echo tests that use `echo` only as a "simple step" under a durable law are
  migrated to the **registry** Echo (productive composition), not to legacy.
- G3/G4 legacy decode and typed-dispatcher tests stay as pre-existing debt.
- G3 milestone and G2/G5 credential tests stay unchanged.
- `@885` / `@958` are Echo/credential red debt outside the Sh closure and stay
  registered as pre-existing, per the no-incidental-Echo-fix rule.

This document is classification-only. No source construction is changed here.
