# LFC-2E0+1+2 — Step Inventory (machine-derived, 2026-09-20)

Status: **REGENERATED 2026-09-20** (cycle `lfc2-step-ecosystem-depuration-2026-09-20`).
This file is the **source of truth** for the LFC-2E program. `STEP_ECOSYSTEM_MATRIX.md`
is the planning hypothesis and is corrected against this table.

## Sources (authoritative)

```text
LEGACY_PLUGIN_IDS                              v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt
CoreStepRegistryFactory                        v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
Core<Name>Step (StepDefinition)                v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Core<Name>Step.kt
Canonical*NodeDispatcher (legacy, unreachable) v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/Canonical*NodeDispatcher.kt
PipelineDsl.kt (DSL façades)                   v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt
StepDefinitionContributor (ServiceLoader)      examples/example-uppercase-plugin/src/main/resources/META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
Real examples                                  v2/compatibility/*.pipeline.kts
Event Harness contracts                        examples/contracts/*.events.yaml
Certification receipts                         docs/v2/07-uat/S2_*_G8_*_*.md, S2_*_G6_*_*.md, S2_*_G7_*_*.md
StepContractSuite tests                        v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Core<Name>StepContractSuiteTest.kt
Fitness tests                                  v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/
```

## Counts (machine-derived 2026-09-20)

```text
Production Step keys total:                  18
  Registry (open-world Step seam):           17   (CoreStepRegistryFactory)
  Legacy (Canonical*NodeDispatcher):         0    (LEGACY_PLUGIN_IDS = {})
  External plugin (ServiceLoader):           1    (example.uppercase)

CERTIFIED (G8 + real installDist + receipt):  11
  core.echo              (S3 burn-down)
  core.sh                (LB-02 S6 burn-down)
  core.error             (S2-A1 burn-down)
  core.sleep             (S2-A2 burn-down)
  core.writeFile         (S2-A3 burn-down)
  core.isUnix            (S2-A5 burn-down)
  core.deleteDir         (S2-A7 burn-down)
  core.milestone         (S2-A9 burn-down)
  core.cleanWs           (S2-A10 burn-down)
  core.archiveArtifacts  (S2-B10 burn-down)
  core.emit.event        (S2-A4 burn-down)
  example.uppercase      (EP-5 burn-down, external)

G6/G7 BLOCKED by horizontal blocker:          1
  core.pwd               (G7 BLOCKED — STRUCTURED_DSL_RUNTIME_RETURN_GAP;
                          root cause affects the whole runtime-returning family
                          pwd/readFile/fileExists; needs LFC-2R2 scope)

REGISTRY_PRIMARY, contract pending, installDist pending:  1
  core.pwdTmp            (no G6/G8 yet; depends on LFC-2R2 like pwd)

CERTIFIED but missing formal contract suite:    0
  (corrected 2026-09-20 WU-LPR-084; WriteFileStepContractSuiteTest
   exists with 21/0/0 — see S2_A3_CORE_WRITEFILE_G6_CONTRACT_CERTIFICATION_RECEIPT.md)

Step SDK plugins (open-world, registry seam):
  scm-git.checkout       (registry)
  junit.results          (registry; full burn-down to CERTIFIED pending)

Core utilities plugin (registry, in pipeline-step-sdk/utilities):
  zip, unzip, findFiles, readJson, writeJson, readYaml, writeYaml, sha256

Total CERTIFIED or pending: 18 production keys + 3 SDK families
```

## Inventory table

Columns:
- **Delivery** = CORE / OFFICIAL_PLUGIN / EXTERNAL_REFERENCE / DEFERRED_REMOTE / REJECTED
- **Path** = `registry` / `legacy` / `external`
- **DSL?** = typed façade declared in `PipelineDsl.kt` (Y/N)
- **Def?** = `StepDefinition` exists (Y/N)
- **Can?** = canonical execution path through `CanonicalDurableRunCoordinator` (Y/N)
- **Legacy?** = legacy executable path still present (Y/N — per ADR-0074 MUST be N for CERTIFIED)
- **TI/TO** = typed input / typed output (Y/N)
- **Cap?** = capability declared in `StepContract.requiredCapabilities` (Y/N)
- **Replay?** = `ReplayPolicy` set in `StepDescriptor` (Y/N)
- **Ex?** = real `.pipeline.kts` example (name; `—` if none)
- **EH?** = Event Harness YAML contract (name; `—` if none)
- **State** = `CERTIFIED` / `IMPLEMENTED_UNCERTIFIED` / `DESIGNED` / `NOT_STARTED` / `DEFERRED` / `REJECTED` / `BLOCKED_<reason>`

| Step key | Delivery | Path | State | Receipt | Notes |
|---|---|---|---|---|---|
| `core.echo` | CORE | registry | **CERTIFIED** | `S3_ECHO_BURNDOWN_CERTIFICATION.md` | reference atomic Step |
| `core.sh` | CORE | registry | **CERTIFIED** | `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` | reference effectful Step |
| `core.error` | CORE | registry | **CERTIFIED** | `S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md` | typed failure |
| `core.sleep` | CORE | registry | **CERTIFIED** | `S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `core.writeFile` | CORE | registry | **CERTIFIED** | `S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md` + `S2_A3_CORE_WRITEFILE_G6_CONTRACT_CERTIFICATION_RECEIPT.md` | 18/18 strict validation rows green (21 contract tests); G6 closed 2026-09-20 WU-LPR-084 |
| `core.isUnix` | CORE | registry | **CERTIFIED** | `S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `core.deleteDir` | CORE | registry | **CERTIFIED** | `S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md` | |
| `core.milestone` | CORE | registry | **CERTIFIED** | `S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md` | |
| `core.cleanWs` | CORE | registry | **CERTIFIED** | `S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md` | |
| `core.archiveArtifacts` | CORE | registry | **CERTIFIED** | `S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md` | |
| `core.emit.event` | CORE | registry | **CERTIFIED** | `S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `core.pwd` | CORE | registry | **BLOCKED (STRUCTURED_DSL_RUNTIME_RETURN_GAP)** | `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` + [ADR-0093](../04-adrs/ADR-0093-structured-dsl-runtime-return.md) | G7 2/4 pass; LFC-2R2 design spike ACCEPTED on main (WU-LPR-086); implementation deferred to WU-LPR-087 |
| `core.waitUntil` | CORE | registry | **CERTIFIED** | `S2_A8_CORE_WAITUNTIL_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` + `S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md` | G6+G8 closed 2026-09-20 WU-LPR-085 (18 contract tests + 9 unit tests green; installed-CLI canary fresh EXIT 0 + replay EXIT 0; ReplayPolicy.MEMOIZED confirmed) |
| `core.pwdTmp` | CORE | registry | no G6/G8 yet | (none) | depends on LFC-2R2 like pwd; will follow WU-LPR-087 |
| `core.artifact.query` | CORE | registry | **REGISTERED + contract** | `CoreArtifactQueryStepContractTest.kt` | E1.1 bridge; G6/G8 pending |
| `scm-git.checkout` | OFFICIAL_PLUGIN | registry (SDK plugin) | **REGISTERED + contract** | `F5_1_ScmGitStepContractTest.kt` | in `pipeline-step-sdk/scm-git` |
| `junit.results` | OFFICIAL_PLUGIN | registry (SDK plugin) | **REGISTERED + contract** | `F5_2_JUnitStepContractTest.kt` | full burn-down to CERTIFIED pending |
| `core.utilities.zip` / `unzip` | OFFICIAL_PLUGIN | registry | **CERTIFIED** | (utilities receipts) | with zip-slip defense |
| `core.utilities.findFiles` | OFFICIAL_PLUGIN | registry | **CERTIFIED** | (utilities receipts) | |
| `core.utilities.readJson` / `writeJson` | OFFICIAL_PLUGIN | registry | **CERTIFIED** | (utilities receipts) | |
| `core.utilities.readYaml` / `writeYaml` | OFFICIAL_PLUGIN | registry | **CERTIFIED** | (utilities receipts) | |
| `core.utilities.sha256` | OFFICIAL_PLUGIN | registry | **CERTIFIED** | (utilities receipts) | |
| `example.uppercase` | EXTERNAL_REFERENCE | external (ServiceLoader) | **CERTIFIED** | `LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` | reference external plugin |
| `core.load` | — | — | DEFERRED (UNSUPPORTED) | (decision WU-LPR-301 / G5) | DSL `load(path)` is fail-closed at compile-time |

**Total production keys: 18** + 1 external = 19 keys; 11 + 1 = **12 CERTIFIED**.

## State invariants

- `LEGACY_PLUGIN_IDS = {}` (empty since WU-LPR-301, 2026-09-18).
- `CoreStepRegistryFactory` registers 17 CoreStepDefinitions.
- `StructuralFamilyResolver.classify` routes every `core.*` key through
  `StructuralStepFamily.Registry`.
- Legacy `Canonical*NodeDispatcher` files exist for parity tests only;
  unreachable in production.

## Known blockers (must not be hidden)

```text
STRUCTURED_DSL_RUNTIME_RETURN_GAP  (S2-A6 / G7 STOP_BLOCKED)

  Symptom: A Step executed by the runtime cannot return a typed value into
  the Kotlin frame that built a PipelineSpec.

  Affects: pwd, pwd(tmp), readFile, fileExists, plus any future
  runtime-returning Step family.

  Status: blocker for G7/G8 of the entire family; not pwd-specific.

  Proposed scope: LFC-2R2 — Structured Runtime-Returning Steps
  (initial consumers: pwd(), pwd(tmp=true), readFile(), fileExists();
   architectural references: isUnix/sh(returnStdout) generator-level seams).

  Design: ADR-0093 — Structured DSL Runtime Return (suspend structured DSL,
  ACCEPTED on main per WU-LPR-086, 2026-09-20; renumbered from ADR-0082 on
  branch cycle/lfc2-e1-r2-runtime-return). Implementation slice: WU-LPR-087.

  Source: docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md
  Spike source: docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
```

```text
core.writeFile formal contract test missing

  Symptom: G8 done; StepContractSuiteTest not authored.

  Affects: only core.writeFile. 1 WU to author.

  Source: this inventory (machine-derived).
```

## Re-anchoring to the depurated plan

The depurated list in `STEP_ECOSYSTEM_MATRIX.md` and
`STEP_REGISTRY_PLAN.md` (both 2026-09-20) is binding. This inventory's
counts feed the per-Step G0..G8 plan and the validation set per Step
(see `openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/`).
