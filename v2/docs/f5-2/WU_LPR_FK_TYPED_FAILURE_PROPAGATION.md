# WU-LPR-FK — Typed failure propagation (F5.2 follow-up B)

## Status

CLOSED_GREEN — 2026-09-19.

## Closure summary

The transformation site is `RegistryExecutionBoundary.coexecute`. The
boundary was **NOT** modified: it correctly projects any kind declared
on a `TypedStepOutput` carrier verbatim, and classifies an unhandled
`Exception` as `FailureKind.ENGINE` (the only correct place ENGINE is
produced today).

The real defect was on the plugin side: `JUnitResultsStepDefinition.handler`
threw `PluginStepException(USER)` instead of returning a `TypedStepOutput`
carrier with `outcome = StepOutcome.Failure(kind=USER)`. The migration
mirrors `CoreShellOutput` (`core.sh`) — a `data class JUnitResultsOutput(summary, outcome) : TypedStepOutput`
plus a `JUnitResultsOutputCodec` that roundtrips `{ outcome, summary }`
on the wire.

Lock-in tests:
- `RegistryExecutionBoundaryFailureKindTest` — 7 rows; unexpected
  exception → ENGINE preserved; TypedStepOutput with USER/SCRIPT/TIMEOUT/INFRASTRUCTURE
  → preserved verbatim.
- `F5_2_JUnitStepContractTest` — 7 negative paths migrated from
  `assertThrows(PluginStepException)` to `assertEquals(USER, outcome.failure.kind)`;
  one new codec-roundtrip row preserves a typed failure through the
  envelope.

Receipt: `v2/docs/f5-2/FK_CLOSURE_RECEIPT.md`.
Characterisation evidence: `v2/docs/f5-2/FK_CHARACTERISATION.md`.

## Background

`F5.2` observed that `RegistryExecutionBoundary`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt`)
converts every `PluginStepException(kind=USER)` raised by a plugin
handler into a `StepOutcome.Failure(failureKind=ENGINE)`. The
user-facing message is preserved verbatim; only the `FailureKind`
discipline is lost.

The four negative scenarios of F5.2 all surface this:

| Case | Handler intent (`PluginStepException.kind`) | Observed on event envelope (`failureKind`) |
| --- | --- | --- |
| `neg1_missing` | USER (`report file not found`) | ENGINE |
| `neg2_malformed` | USER (`malformed XML`) | ENGINE |
| `neg3_failing_strict` | USER (`1 failed test(s)`) | ENGINE |
| `neg4_failing_lenient` | (success path — no failure) | success |

The same pattern is observable for `core.sh` (LB-02 certified
reference effectful step): when the shell exits with a non-zero code
the handler raises a USER failure and the envelope emits ENGINE. This
is **pre-existing** behaviour, not a F5.2 regression.

## Why it matters

`FailureKind` is observed by:

- **Retry semantics** — the durable engine treats USER differently
  from ENGINE (USER is replayable; ENGINE is not).
- **Audit consumers** — they label USER failures as "expected",
  ENGINE failures as "infrastructure".
- **The event envelope** — auditors and dashboards pivot on the kind.

A plugin author who declares a USER failure today has no way to make
that classification survive the boundary. The pipeline's audit story
becomes "everything is ENGINE", which dilutes the signal.

## Goal

Preserve `FailureKind.USER` (and any future typed kind the plugin
declares via `PluginStepException.failure.kind`) when it survives the
boundary, and reserve `FailureKind.ENGINE` for genuine engine or
adapter faults.

The fix MUST be generic: no JUnit-specific exception path, no
`if (stepKey == "junit.results")` branch in the boundary.

## Non-goals (deliberate)

- Do NOT change the existing ENGINE classification for non-typed
  exceptions (e.g. an unexpected `NullPointerException` raised by a
  handler MUST stay ENGINE — the boundary has to keep classifying
  unhandled runtime exceptions as adapter faults).
- Do NOT introduce new `FailureKind` values in this WU; the
  classification set today is `USER / SCRIPT / ENGINE / INFRASTRUCTURE`,
  and today's only declared USER is from the plugin handler.
- Do NOT change retry semantics. This WU delivers correct
  classification; retry policy is a separate ADR.

## Required evidence before implementation

A. Characterisation suite (mandatory before any code change):

- For each plugin handler in `v2/pipeline-step-sdk/` and any handler in
  `v2/pipeline-application/` that raises `PluginStepException`:
  - Capture the input that triggers the failure.
  - Run the handler in isolation (no coordinator), observe the typed
    `PluginStepException.failure.kind`.
  - Run the same input through the canonical durable run path, observe
    the event envelope's `failureKind` and `StepOutcome.Failure.kind`.
  - Diff; classify as "preserved" / "lost" / "transformed".

B. Audit (mandatory before any code change):

- Every call site of `FailureKind.ENGINE` in
  `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/`.
- The matrix at the boundary: `kind_in × exception_type → kind_out`.
- The taxonomy of "real" ENGINE faults today (vs. transformed USER
  ones).

C. Lock-in suite (mandatory before any code change):

- A new test class `RegistryExecutionBoundaryFailureKindTest` that
  runs a typed USER failure through the boundary and asserts the
  envelope's `failureKind == USER` (after the migration; today it
  asserts `ENGINE` as the baseline).
- The same suite runs a `NullPointerException` from a handler and
  asserts `failureKind == ENGINE` (unchanged).

## Acceptance criteria

- The characterisation matrix is published under
  `docs/v2/07-uat/WU_LPR_FK_CHARACTERISATION.md`.
- The lock-in suite PASSes against today's code (baseline = USER → ENGINE).
- After the migration, the same suite PASSes against the new code
  (USER → USER preserved; NPE → ENGINE unchanged).
- No JUnit-specific code path. The change is a typed-field pass-through
  on the existing `PluginStepException → StepOutcome.Failure`
  conversion in `RegistryExecutionBoundary` (or wherever the
  transformation lives; the audit may relocate it).
- All existing tests (55 in F5.* today, plus the F5.2 E2E, plus the
  boundary lock-in suite) remain green.
- `core.sh` ENTRY-POINT tests (LB-02 / A4.2) remain green.

## Out of scope

- New `FailureKind` values.
- Retry-policy changes.
- A general "make all errors typed" rewrite of every handler.
- Changes to the audit envelope shape beyond the `failureKind` field.

## Estimated size

Small-medium. The audit + characterisation are small; the lock-in
suite is the largest non-mechanical piece. The actual code change is
expected to be 5-15 lines once the audit identifies the correct
transformation site.

## Trigger to close

When the lock-in suite PASSes and the audit is published, and at least
two plugin handlers (e.g. `core.sh` and `junit.results`) preserve their
declared `FailureKind` end-to-end through the boundary, this WU is
ready to be marked DONE.

If the audit reveals that the boundary's transformation is intentional
(e.g. because of a documented "we always treat plugin failures as
infrastructure-level" contract), this WU is cancelled and the audit's
finding is filed as the closing reason. The cancellation rationale
must cite the audit file.
