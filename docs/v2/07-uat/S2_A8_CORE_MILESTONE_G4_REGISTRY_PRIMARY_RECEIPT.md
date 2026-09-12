# S2-A8 / G4 — core.milestone REGISTRY_PRIMARY Receipt

> Gate: **G4 — REGISTRY_PRIMARY (authority flip, legacy unreachable, physically present)**
> Base: `main @ 51cd021f` — counters 5 / 5 / 5
> Branch: `cycle/lfc2-e1-milestone-g4`
> Flip commit: `87d998ff` (single coherent commit: state flip + sibling test updates)
> Date: 2026-09-12T23:05–23:15Z
> Result: **counters 5/5/5 → 4/5/5** (generic gate law: G4 = N→N-1 in ids ONLY)
> Preflight: `docs/v2/07-uat/S2_A8_CORE_MILESTONE_G4_G5_PREFLIGHT_RECEIPT.md` (READY_FOR_G4 = true)

## Scope firewall (exactly what changed)

```text
1. CanonicalCoreStepDecoder.kt     LEGACY_PLUGIN_IDS -= "core.milestone"
                                   (+ S2-A8/G4 provenance comment, A1/A5 pattern)
2. LegacyResidualSnapshot.kt       registryPrimaryPendingRemoval = "core.milestone"
                                   (counters 4/5/5 computed — never hand-edited);
                                   burn-down header updated (milestone G4 = current)
3. Transitional-window test policy (restored at G5):
   assertConverged -> assertCurrentState at 9 sites across
   S3{EmitEvent,Error,IsUnix,Pwd,Sleep,WriteFile}LegacyRemovedFitnessTest
   (S3Error carries 4 sites; Echo already called assertCurrentState only)
4. Stale G1-era invariant updated to G4 truth:
   CanonicalCoreStepCommandRegistryTest expected ids set -= core.milestone
   (provenance comment, no alias/stub)
5. NEW CoreMilestoneRegistryPrimaryFitnessTest — dedicated 8-row G4 fitness
   (per preflight §6 and the S2-A6/A7 precedent)
6. This receipt
```

PROHIBIDO and NOT touched (G5 territory): `CanonicalCoreStepMetadata` rows,
`CanonicalMilestoneNodeDispatcher.kt` (physically present, UNREACHABLE), the
`Milestone` command subtype, the decoder `Milestone` decode branch,
`CoreStepRegistryFactory`, `CoreMilestoneStep.kt`, any other Step's entries.

## State after G4

```text
LEGACY_PLUGIN_IDS = 4   (cleanWs, load, waitUntil, archiveArtifacts)
metadata rows     = 5
dispatcher files  = 5

core.milestone:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = false   (G5)
  CERTIFIED          = false   (G8)
```

## Routing authority (not path-shape)

Routing authority is **ids-absence + StructuralFamilyResolver=Registry**
(`CoreMilestoneRegistryPrimaryFitnessTest` rows 2–4):

- `"core.milestone" !in LEGACY_PLUGIN_IDS`, `LEGACY_PLUGIN_IDS.size == 4`
- `StructuralFamilyResolver.classify(core.milestone, CoreStepRegistryFactory.registry()) == Registry`
- `RegistryStepMetadataResolver.composite` resolves via the registry descriptor;
  with an EMPTY registry it fails closed (`EngineInvariantViolation`) — the legacy
  metadata row is NOT consulted as a production fallback.
- The typed `MilestoneReached` event is emitted through the capability-routed
  registry handler; the legacy dispatcher file remains on disk for G5 physical
  removal — presence without reachability is exactly the G4 contract.

## Validation (fresh XML, all timestamps 2026-09-12T23:13–23:14Z, HEAD `87d998ff`)

All runs: `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/gradlew -p v2`,
`timeout 600` per invocation, JUnit XML as result truth (canary: fresh XMLs
regenerated this session; sha256 over final XML bytes).

| Suite | Result | XML sha256 (first 16) |
|---|---|---|
| `CoreMilestoneRegistryPrimaryFitnessTest` (NEW, 8 rows) | 8/0/0 | `686a916b3e50d58f` |
| `CoreMilestoneStepContractSuiteTest` (regression canary) | 23/0/0 | `49190ed67c73b465` |
| `CoreMilestoneStepUnitTest` | 19/0/0 | `275871fca396a301` |
| `UatLocal013MilestoneTimingTest` | 4/0/0 | `cbdba66e3186e390` |
| `CanonicalCoreStepCommandRegistryTest` | 7/0/0 | `53250bf2bf19f85e` |
| S3 LegacyRemovedFitness (7 suites, transitional-aware) | 52/0/0 | per-suite below |
| — S3Echo | 7/0/0 | `5d050ef40287fcfe` |
| — S3EmitEvent | 8/0/0 | `40804b2762e7f9dc` |
| — S3Error | 12/0/0 | `035803e7ca74b846` |
| — S3IsUnix | 9/0/0 | `183a9a1412b1631e` |
| — S3Pwd | 8/0/0 | `890ebf2fce00a343` |
| — S3Sleep | 4/0/0 | `ffe731a4622220b0` |
| — S3WriteFile | 4/0/0 | `83cdaefe39af176b` |
| `Lfc2RegistryFamilyFitnessTest` | 3/0/0 | `cff9ad7d9d03a284` |

### Pre-existing red baseline (not touched, not re-baselined)

`CanonicalDurableRunCoordinatorTest` full class: **12/26 failures at base
`51cd021f` AND identical 12/26 at HEAD `87d998ff`** (fresh worktree-method
base-vs-head evidence, same session). Zero of the 12 failures involve the
milestone rows. The two coordinator milestone rows
(`milestone dispatches MilestoneReached...`, `milestone out-of-order ordinal...`)
were run in isolation at base and head: **2/0/0 green at both**.

## Installed-CLI authority proof (legacy unreachable)

```text
command : pipeline-application run --db ~/.jcode/scratch/ms-g4/ms-g4.db MS-G4.pipeline.kts
script  : stage("ms-g4-pre") { echo("before-milestone"); milestone(ordinal=1, label="g4-flip") }
          stage("ms-g4-post") { echo("after-milestone") }
exit    : 0 — Pipeline finished with SUCCESS
events  : StepStarted stepType="milestone" stepName="ms-g4-pre/milestone-0"
          MilestoneReached {ordinal=1, label="g4-flip"}
log sha256 = eb9ff42df191537b3184f6ec3d0e59c8d9c1d8f6cb7185ff6064ce5fd535685c
```

## Counter verification (machine-read from source at HEAD)

```text
CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS = {core.cleanWs, core.load,
  core.waitUntil, core.archiveArtifacts}                       -> 4
CanonicalCoreStepMetadata residual rows                        -> 5
durable/Canonical*NodeDispatcher.kt (facade excluded)          -> 5
LegacyResidualSnapshot.expected() = 4 / 5 / 5 (assertCurrentState green)
```

## STOP

No G5 in this slice. Next authorized gate: **core.milestone G5 — LEGACY_REMOVED
(4/5/5 → 4/4/4)**: delete `Milestone` command subtype, decoder branch, metadata
row, `CanonicalMilestoneNodeDispatcher.kt`; restore `assertConverged` in the 6
sibling suites.
