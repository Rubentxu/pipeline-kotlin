# EXTERNAL_PLUGIN_INFRA_BASELINE (LB-02)

Frozen: 2026-09-09, immediately after EP-F2.6 generic production-path proof.
Baseline commit: `587f9ab5` (`feat(LB-02): EP-F2 RegistryStepSpec sealed leaf +
generic compiler lowering`). Docs commit: `38fdbcfb` (proof test).
Immutable reference for the external-plugin zero-production-change proof.

## Why frozen here (order per user decision)

```text
F1 StepDefinitionContributor SPI        ✅
F2 RegistryStepSpec + generic lowering  ✅
F2.5 production reachability audit      ✅ (cd9d9e7b)
F2.6 generic proof (neutral fixture)    ✅ (38fdbcfb)
    ↓
EXTERNAL_PLUGIN_INFRA_BASELINE          ← HERE
    ↓
example.uppercase build/contribution
    ↓
diff vs this manifest must show ZERO production semantic change
```

Freezing BEFORE the concrete plugin is what makes
"adding example.uppercase → production semantic changes = 0" honestly demonstrable.

## F2 final gate evidence (all green at freeze)

| Gate | Evidence |
|---|---|
| RegistryStepSpec sealed leaf | `587f9ab5` (PipelineDsl.kt) |
| generic registryStep DSL | same |
| StepKey-agnostic lowering | DslCompiledPipelineCompiler.kt `RegistryStepSpec` branch |
| StructuralRegistry family | EP_F26 test `family` assertion |
| production coordinator path | Main → runCanonicalPipeline → CanonicalDurableRunCoordinator (fail-closed, exit 2 otherwise) |
| PipelineRun involvement | 0 (guards fail-closed; path not production-reachable, EP-F2.5 audit) |
| typed decode during compile | 0 (payload verbatim, EP_F26 assertion) |
| handler execution at compile | 0 |
| concrete external StepKey knowledge | 0 (word-boundary source fitness) |
| core.echo CERTIFIED | ✅ (prior) |
| core.sh CERTIFIED | ✅ (LB-02 A4, prior) |
| architecture gates | green at last full run |
| installed distribution core scenarios | 01-basic, 02-environment, 03-stages, 04-sh, 05-scripted-if, 06-loop → all `SUCCESS` post-F2 |

Test evidence: `EP_F26_GenericProductionPathProofTest` 4/0/0
(XML sha256 prefix `e88b41243907e17e`).

## Production surface manifest (sha256 at freeze)

| Surface | File | SHA-256 |
|---|---|---|
| coordinator | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` | `80fee66da75bbc357c1e15d77f6dca97174ffe074a823266fb9ccf4b1459ff42` |
| compiler | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` | `552ab49a6c11978b4037a0c9d4b0fba09641e2a3983450fa715c1e0c79c17a98` |
| DSL (StepSpec forms + registryStep) | `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt` | `c827f255b6ac014dc9c8ea65e05dca190982a7834fd318eb1099481c150c9524` |
| flattener | `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/api/BlockStepFlattener.kt` | `1e570840f50c6f22c7e7e48397e2208f4d28ea0027d44c4fe3d03bc2617c860b` |
| registry + codec contracts | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepRegistry.kt` | `cb1e1992b2dba0ca1755490340d546c33f054671dad34fa5b7d5c6f99cb2ac4e` |
| contribution SPI | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepDefinitionContributor.kt` | `40320d1a51ac86b6397e621764adc6d784c1e44392b79eb029948673f4d0993a` |
| registry composition (core) | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` | `b386cf19c8def3f21b5a464801289e993be1ee15ded606319f45771f2218042a` |
| legacy runner (debt, untouched) | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt` | `cb3757370d6d8a76158488bde86d2f393b918869a104f919a9c48dd0204ad678` |
| compiled pipeline IR | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/CompiledPipeline.kt` | `f7299342ab66481ff300353fb038efb1336ef2706c05890daca32904faaf6b49` |
| pipeline validator | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/CompiledPipelineValidator.kt` | `44fc72037306a80587769d5ded097362f9404398ed5e5c3532f5f3c6626a895e` |
| execution planner | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/CompiledExecutionPlanner.kt` | `2a8e941080ee6fa349e6e9ab9dec897b5e9f2eaf3b89aa67a7f00712984fe25d` |
| dispatcher | `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ConcurrentStepDispatcher.kt` | `ee7653e99aee9636b37708f144d65296709df369744bf67a47c95cf08473d5cd` |
| composition root | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt` | `ff467074842e8515b49358f8c537ccfa4d99d98a396ae1fb0e0f924157c7aaf2` |
| legacy decoder | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt` | `e29fcb545f8f2d797624837d6a6f464d0bab3905961d13a846c12e68919993b9` |
| core metadata | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt` | `67dc9f5168d7ff50a7bfcccdb2c90f075a8813ed7a116660fda8fe598900777e` |
| structural family | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/StructuralStepFamily.kt` | `093bb309c9e080a7aff4268ba2b375ab2c512f45ddec7bd9d36e8923a77a4eee` |

Also tracked implicitly by git: full tree at `587f9ab5` (plus docs at `38fdbcfb`).

## Zero-production-change proof protocol (after plugin certification)

Re-hash the manifest table. Expected:

```text
coordinator semantic changes     = 0
durable protocol changes         = 0
compiler plugin-specific cases   = 0
core metadata rows added         = 0
legacy catalogue entries added   = 0
dispatcher cases added           = 0
StepKey-specific routing added   = 0
```

Allowed new artifacts: external plugin sources, plugin JAR, ServiceLoader
descriptor, external DSL extension, UAT/evidence docs, this file's post-proof
section. Any manifest hash change requires explicit justification in the proof
doc (the only anticipated legitimate one: NONE — if any hash changes, the proof
fails until explained or reverted).

## Known debt registered (out of scope here)

```text
StepSpec direct-execution debt (EP-F2.5 doc):
  production reachable       = 0
  remaining code consumers   = 4 (PipelineRun.kt whens + legacy guards)
  legacy/test consumer       = 1 (StepExecutors.executeBranch)
  PipelineOrchestrator       = constructed, never invoked (LF-0205); preferred
                               resolution: progressive deletion, not re-pointing
```
