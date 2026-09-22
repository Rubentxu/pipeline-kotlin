# WU-RP-031 E2 Receipt — DurableInvocationResolver extraction

Base: `d011b4be` (E1). Date: 2026-09-22.

## What

Second extraction slice from `CanonicalDurableRunCoordinator` (1943 lines post-E1):
7 reconciliation/recovery methods moved verbatim (`rejectSchema`,
`reconcileInvocation`, `deterministicGate`, `replayResolution`,
`recoverRunningShell`, `completedShellOutcome`, `lostShellOutcome`) into new
`DurableInvocationResolver.kt` (internal, same package `application.durable`).
Deps narrowed to: `DivergenceDetector` (domain interface), `EffectReplayPolicy`,
`Clock`, `OperationJournal`, `Path?` controlDirRoot. No event sink, no
dispatcher, no coordinator reference. Coordinator holds a private body property
(wired from existing ctor collaborators); public constructor signature
unchanged (zero call-site churn). `REATTACH_TIMEOUT_MS = 60_000L` preserved
from coordinator companion.

## Behavior-equivalence notes

- Methods converted `private` → `internal`, bodies byte-identical.
- `recoverRunningShell` uses the domain `DivergenceDetector` interface type
  (widened only at the collaborator seam; coordinator still injects
  `StrictFingerprintDivergenceDetector`).
- An accidental drag of trailing kdoc fragments (INC-007 / B13 dispatch docs)
  into the new file was detected and removed before validation.

## Verification (this SHA, fresh runs)

| Check | Result |
| --- | --- |
| `:pipeline-application:compileKotlin` | BUILD SUCCESSFUL |
| durable package tests (`--tests '*durable*'`) | 296/296 GREEN |
| arch/fitness suites (`*Arch*`, `*Fitness*`) | 183/183 GREEN |
| kill/resume/UAT-local (`*Kill*`,`*Resume*`,`*UatLocal0*`) | 148/148 GREEN |

## Validation ladder

L0 compile → L2 durable package → L3 arch/fitness + kill/resume UAT smoke.
Full `check` deferred to the round gate / next integration point per
incremental testing policy.

## Next

E3 (TypedInputDecode extraction) → E4 (StepExecutor), then WU-RP-032.
