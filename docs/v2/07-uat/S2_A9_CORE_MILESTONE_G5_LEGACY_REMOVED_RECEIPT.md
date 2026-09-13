# S2-A9 / G5 CLOSURE RECEIPT — core.milestone LEGACY_REMOVED

> Gate: **G5 — physical removal of all legacy forms (counters 4/5/5 → 4/4/4 converged)**
> Branch: `cycle/lfc2-e1-milestone-g5` (LOCAL — not pushed, not merged)
> Worktree: `../pipeline-milestone-g5`
> Base: `main @ 0a370f43` (= origin/main, clean)
> Date: 2026-09-13T07:33Z (work); atomic writer commit 2026-09-13T08:07Z
> Atomic writer commit: **5dd67637** (single atomic destructive commit; Lane A exclusive)

## Atomic removal set (exactly what was deleted)

```text
CanonicalCoreStepDecoder.kt
  - CanonicalCoreStepCommand.Milestone subtype (data class)            DELETED
  - MILESTONE_PLUGIN_ID constant                                        DELETED
  - MILESTONE_PLUGIN_ID decoder branch (Milestone -> {…})               DELETED
  + provenance comments (LEGACY_REMOVED, S2-A9 / G5, 2026-09-13)        ADDED

CanonicalCoreStepMetadata.kt
  - "core.milestone" metadata row                                        DELETED
  + provenance comment (LEGACY_REMOVED, S2-A9 / G5, 2026-09-13)         ADDED

durable/CanonicalMilestoneNodeDispatcher.kt                             DELETED (file)
durable/CanonicalNodeDispatcher.kt
  - milestoneDispatcher field                                            DELETED
  - Milestone when-branch                                                DELETED
  - milestoneContext() helper                                            DELETED
  + provenance comments (LEGACY_REMOVED, S2-A9 / G5, 2026-09-13)        ADDED

DslCompiledPipelineCompiler.kt
  - StepSpec.Milestone -> OpaqueStepNode branch                          DELETED
  - milestonePayload(ordinal, label) helper                              DELETED
  + provenance comments (LEGACY_REMOVED, S2-A9 / G5, 2026-09-13)        ADDED

PipelineDsl.kt (scripting-api)
  - StepSpec.Milestone(ordinal, label) producer                          REPLACED
  + StepSpec.RegistryStepSpec direct-construction (open-world path)    ADDED
  + canonical envelope encoder (matches CoreMilestoneStep codec)        ADDED
```

## DSL lowering migration

The DSL `milestone(ordinal, label)` previously produced `StepSpec.Milestone`
sealed subtype, which the compiler routed to a dedicated branch producing an
`OpaqueStepNode` with `milestonePayload()` JSON. After G5:

```text
milestone(ordinal = 1, label = "post-error")
  ↓
StepSpec.RegistryStepSpec(
  stepKey = PluginStepId("core.milestone"),
  schemaVersion = "dsl-v1",
  encodedInput = EncodedStepValue("{\"kind\":\"milestone\",\"ordinal\":1,\"label\":\"post-error\"}"),
)
  ↓
compiler (no dedicated branch)
  ↓
OpaqueStepNode(pluginStepId=core.milestone, payload=encodedInput)
  ↓
runtime registry handler: CoreMilestoneStep.definition (CoreMilestoneStep)
```

The canonical envelope is byte-equivalent to the previous `milestonePayload()`
output and to `CoreMilestoneStep.inputCodec.encode(MilestoneInput(...))`, so
the durable fingerprint is preserved across the G5 flip.

## Snapshot transition

```text
LegacyResidualSnapshot:
  physicalResidual -= "core.milestone"                (5 keys -> 4 keys)
  registryPrimaryPendingRemoval = null                (still null at G5)
  header counter                = 4 / 4 / 4 (converged)

7 sibling S3 suites: assertCurrentState -> assertConverged RESTORED
  (Echo, EmitEvent, Error, IsUnix, Pwd, Sleep, WriteFile)
```

## Counters (post-G5)

| Counter | Pre-G5 (G4 window) | Post-G5 |
|---|---|---|
| LEGACY_PLUGIN_IDS | 4 (cleanWs, load, waitUntil, archiveArtifacts) | **4** (unchanged — milestone already absent from G4) |
| metadata rows | 5 | **4** (milestone removed) |
| dispatcher files | 5 | **4** (CanonicalMilestoneNodeDispatcher.kt removed) |
| **Convergence** | 4/5/5 | **4/4/4** ✓ |

## Tests updated to G5 truth (no aliases, no stubs, no weakened assertions)

```text
CanonicalCoreStepCommandRegistryTest:
  `Milestone has correct pluginId and defaultMetadata` removed (LEGACY_REMOVED).
  Provenance comment (S2-A9 / G5) added in its place.

CanonicalDurableRunCoordinatorTest:
  - milestone dispatches MilestoneReached test: added stepRegistry = CoreStepRegistryFactory.registry()
    to the coordinator constructor (registry-aware routing is now required since the legacy
    decoder/metadata authority for core.milestone was physically removed in this slice).
  - milestone out-of-order ordinal test: same migration applied.
  - continues after a default catchError failure test (line 84): same migration applied
    (transitively affected: it uses echoStep, and core.echo is registry-primary).

CoreSleep/EmitEvent/Error/IsUnix/Pwd/WriteFile RegistryPrimaryFitnessTest:
  - Each historical "post-S2-A6/G4 — 6 residual legacy keys remain" test marked @Disabled
    with provenance comment (superseded by S2-A9/G5 4-key snapshot).
  - Each gained a new "post-S2-A9/G5 — 4 residual legacy keys remain" test asserting the
    G5 truth (LEGACY_PLUGIN_IDS = {cleanWs, load, waitUntil, archiveArtifacts}; size = 4).
  - SleepRegistryPrimaryFitnessTest gained a new production-registry-keys test including
    core.waitUntil, core.deleteDir, core.milestone (12 total), per the current
    CoreStepRegistryFactory registration.
```

## State after G5

```text
LEGACY_PLUGIN_IDS = 4
metadata rows     = 4
dispatcher files  = 4

core.milestone:
  REGISTERED         = true   (CoreMilestoneStep.definition)
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = true   (this slice)
  CERTIFIED          = false  (G6 contract suite + G7/G8 pending)
```

## Lane C verification (fresh, post-G5, XML canary)

| Suite | Result |
|---|---|
| CoreMilestoneStepContractSuiteTest | 23/0/0 |
| CoreMilestoneStepUnitTest | 19/0/0 |
| UatLocal013MilestoneTimingTest | 4/0/0 |
| S3EchoLegacyRemovedFitnessTest | 7/0/0 |
| S3EmitEventLegacyRemovedFitnessTest | 8/0/0 |
| S3ErrorLegacyRemovedFitnessTest | 12/0/0 |
| S3IsUnixLegacyRemovedFitnessTest | 9/0/0 |
| S3PwdLegacyRemovedFitnessTest | 8/0/0 |
| S3SleepLegacyRemovedFitnessTest | 4/0/0 |
| S3WriteFileLegacyRemovedFitnessTest | 4/0/0 |
| **S3 total** | **52/0/0** |
| Lfc2RegistryFamilyFitnessTest | 3/0/0 |
| Lfc2DurableCoordinatorScopeFitnessTest | 4/0/0 |
| UppercaseStepContractSuiteTest (LB-02 zero-production-change canary) | 14/0/0 |
| CoreEmitEventRegistryPrimaryFitnessTest | 13/0/0 |
| CoreErrorRegistryPrimaryFitnessTest | 17/0/0 |
| CoreIsUnixRegistryPrimaryFitnessTest | 10/0/0 |
| CorePwdRegistryPrimaryFitnessTest | 9/0/0 |
| CoreSleepRegistryPrimaryFitnessTest | 11/0/0 |
| CoreWriteFileRegistryPrimaryFitnessTest | 7/0/0 |

### Pre-existing red baseline (rule LB-02 / A5: NOT widened)

Focused comparison scope (`--tests '*Milestone*' --tests '*RegistryPrimary*'
--tests '*UatLocal*' --tests 'CanonicalDurableRunCoordinatorTest' --tests
'UppercaseStepContractSuiteTest' --tests 'CoreLegacyStepMetadataResolverTest'`):

| Scope slice | main @ 0a370f43 | branch (G5 milestone) |
|---|---|---|
| CanonicalDurableRunCoordinatorTest | 26/11/0 | 26/10/0 (one catchError test now green via stepRegistry migration) |
| CoreLegacyStepMetadataResolverTest | 4/1/1 | 4/1/1 (identical — core.sleep was already pre-existing red) |
| *RegistryPrimaryFitness (6) | 8 reds (one per historical `post-S2-A6/G4` snapshot) | 0 reds (all 8 historical snapshots `@Disabled` with provenance + G5 truth tests added) |
| Other focused scope (UatLocal/Milestone/Uppercase) | 8 reds (pre-existing: CR-U9-*, UAT-L8-CP-001, SB-S-010, ordinary-steps recovery etc.) | 8 reds (**same set**, no widening) |
| **Focused scope total** | **27 reds** | **19 reds** (subset, no widening; 8 cleanup + 1 catchError improvement) |

Set equality invariant: **branch red set ⊆ main red set** (no widening).
The 8-tests delta comes from historical `post-S2-A6/G4` snapshots (`@Disabled`
with provenance) plus one catchError test that became green as a side effect
of migrating to registry-aware coordinator construction (the LB-02 / A5
required path when the no-registry coordinator can no longer resolve the
migrated Step).

This comparison covers the **focused scope** above, not the full
`:pipeline-application:test` suite (full run ~480s wall, deferred to L5 per
validation ladder rule 17). The authoritative G5 evidence is the **100% green
S3 sibling fitness suite (52/0/0) + milestone contract suite (23/0/0) +
milestone unit suite (19/0/0)**, which directly exercise the G5 destructive
removal.

## Lane B static audit

Pre-audit inventory @ `0a370f43` matched the removal set exactly:
- `CanonicalCoreStepCommand.Milestone` subtype (line ~132)
- `MILESTONE_PLUGIN_ID` constant (line ~212)
- `MILESTONE_PLUGIN_ID` decoder branch (line ~229)
- `CanonicalCoreStepMetadata["core.milestone"]` row (line ~21)
- `CanonicalMilestoneNodeDispatcher.kt` file
- 3 `CanonicalNodeDispatcher` seams (field, when-branch, milestoneContext())
- `DslCompiledPipelineCompiler.milestonePayload()` and the `StepSpec.Milestone ->` branch
- DSL `milestone()` producer: `StepSpec.Milestone(...)` direct-construction

Post-audit on the G5 branch: zero surviving code references — only textual
provenance comments remain. No aliases or stubs were created to keep historical
tests green; the affected `CanonicalDurableRunCoordinatorTest` cases were
**migrated to registry-aware coordinator construction** (the AGENTS.md §LB-02 / A5
required path when the no-registry coordinator construction can no longer resolve
the migrated Step). Sibling `RegistryPrimaryFitnessTest` historical snapshots were
@Disabled with provenance comments + new G5 truth tests added, following the
established precedent (`bd9f6ee7` deleteDir G5 commit).

## Installed-CLI proof (registry path post-removal)

```text
script    : v2/compatibility/21-milestone.pipeline.kts
           (real .pipeline.kts, milestone(1,"post-error") + echo + milestone(2,"post-build") + echo)
exit      : 0
pipeline  : RunFinished{outcome="success"}
events    : MilestoneReached{ordinal=1,label="post-error"}
            MilestoneReached{ordinal=2,label="post-build"}
            StepStarted/StepFinished stepType="milestone" stepName="milestones/registrystep-N"
```

Source digest sha256 (post-G5):
- `CanonicalCoreStepDecoder.kt`     = b25213f156e2998c (first 16 hex)
- `DslCompiledPipelineCompiler.kt`  = f4d19b681c377687 (first 16 hex)
- `CanonicalCoreStepMetadata.kt`    = fc13dc6c34b60f3e (first 16 hex)
- `CanonicalNodeDispatcher.kt`      = 2c09eb5991c174bb (first 16 hex)
- SQLite journal `/tmp/dd-g5-ms/m.db` = a3c718b86621b7f7 (first 16 hex)

The legacy `CanonicalMilestoneNodeDispatcher.kt` file no longer exists on disk
(verified via `ls .../durable/` — zero `Canonical*Milestone*` matches).

## STOP

No G6 in this slice. The G6 contract suite is the next authorised gate
(see `S2_A9_CORE_MILESTONE_G3_RECEIPT.md` for the contract suite shape — it is
**already green** at 23/0/0 in this branch and can be reused for G6 closure
proofs). Next authorised gates:

```text
G6 contract suite (carry-forward from G3) -> G7 installed acceptance -> G8 CERTIFIED
```