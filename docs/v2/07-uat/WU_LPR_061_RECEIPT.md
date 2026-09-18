# WU-LPR-061 Receipt — @Disabled Classification

Date: 2026-09-18 · HEAD at generation: `5138a32a` (R2)

## 1. Method

Fresh inventory over HEAD (NOT the historical 67 figure). Deterministic
generator: `scripts/gen-disabled-inventory.py` →
`docs/v2/07-uat/WU_LPR_061_DISABLED_INVENTORY.md` (the only count authority;
regenerable; hand-edited numbers are forbidden).

The generator enforces a fail-loud law: any new `@Disabled` not present in its
CLASSIFICATION table defaults to HISTORICAL — a new MANDATORY_SUPPORTED or
COMPATIBILITY entry must be classified explicitly there, so a silent
misclassification that could mask a Gate-1 blocker is visible in review of the
generator diff itself.

## 2. Result

```text
@Disabled total                 = 68   (raw annotation count reconciles: 68)
MANDATORY_SUPPORTED             = 0    ← Gate-1 property: mandatory-disabled = 0
COMPATIBILITY                   = 2
HISTORICAL                      = 66
EXPERIMENTAL                    = 0
OBSOLETE                        = 0
```

### COMPATIBILITY (deferred to WU-LPR-103, not auto-fixed)

| id | class | target | reason | blocks Gate-1 |
|---|---|---|---|---|
| 061-C1 | UatLocal008CredentialsTest | CR-BD-034 mismatched credential kind throws | `CredentialsId` not on the `.pipeline.kts` script classpath (DSL classpath capability gap) | no |
| 061-C2 | UatLocal011WorkflowControlTest | SC-011-11 load executes script content | INC-024: coordinator does not yet inject loaded pipeline; `load` is explicitly non-blocking for local-core-v1 (product doc §3) | no |

### HISTORICAL (66)

All are archived burn-down snapshots of the legacy→registry migration
(S2-A1..B10, WU-LPR-301): "Historical Gx snapshot ... superseded by ...
preserved verbatim for traceability", plus four class-level
`Archived G3/G4 evidence` suites (CoreEmitEvent/CoreError
MigrationReadinessFitnessTest, CoreErrorStepG2RegistryAdmissionTest,
CoreSleepCoordinatorCharacterizationTest, CoreSleepG3DifferentialParityTest).
Each one names its live superseding test (RegistryPrimaryFitnessTest /
LegacyRemovedFitnessTest variants). None participates in any active gate
(JUnit never runs them). They stay as closed-cycle evidence per WU-LPR-061
law; deletion of the verbatim-snapshot set is deferred to the legacy-narrative
retirement (each entry already documents its own deletion trigger).

## 3. Gate-1 property (pinned)

```text
Gate-1 cannot be GREEN while mandatory-disabled > 0
current mandatory-disabled = 0
```

Enforced by the generated counts section; no @Disabled test was ever counted
as GREEN evidence in any LPR receipt.

## 4. Forever-fitness pin (typed-value boundary)

New test in `Lpr011r2SecretRedactionAtRestUatTest` (11th): the typed
`capturedStdout` value is EXACT by contract, but no OBSERVABLE projection of
the shell execution (`EchoOutputCaptured` events, `console.log`) may carry
that value raw. Pins the channel separation at the event boundary for good —
critical before self-hosting `pipeline.kts` runs GitHub/SDKMAN credentials.
Suite: 11/11 green.

## 5. Verification

- Raw `@Disabled(` annotation count over `v2/**` reconciles exactly with the
  generator total (68 = 68).
- At-rest UAT now 11/11 (fresh XML, `failures="0" errors="0"`).
- No test re-enabled, weakened, or deleted in this WU. Inventory + generator
  + one added fitness test only.
