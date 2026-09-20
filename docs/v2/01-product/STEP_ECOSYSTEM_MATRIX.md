# Step Ecosystem Matrix — local-first coverage and delivery

Status: **DEPURATED 2026-09-20** (cycle `lfc2-step-ecosystem-depuration-2026-09-20`).
Source of truth: [`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`](../../v2/07-uat/STEP_INVENTORY_LFC2E0.md) — machine-derived from production code on 2026-09-20.

This matrix is the binding plan for the local-first Step ecosystem. The
preceding version (2026-09-11) proposed ~25 universal Steps plus several
families ("testing/reports", "toolchains", "containers",
"HTTP/SSH/notifications", "external reference") without discriminating
**real utility** and **genericity**. The user directive on 2026-09-20 was
to:

1. **Genericidad**: primitivas universales only (filesystem, network,
   process, time, value) — never wrapper for a specific tool.
2. **Utilidad**: used in non-Jenkins pipelines (GitHub Actions, GitLab CI,
   Buildkite, Concourse). Jenkins-only = compat-only or REJECTED.
3. **Tool-specific = plugin externo / REJECTED**: no Maven/npm/pip/
   dotnet/Go/docker as core primitives.
4. **Certificación obligatoria G0..G8**: per `STEP_PLUGIN_CERTIFICATION.md`
   and ADR-0074, every Step must reach `CERTIFIED` (not
   `IMPLEMENTED_UNCERTIFIED`, not `LEGACY_IMPLEMENTED_UNCERTIFIED`,
   never `DONE/PASS`).

The depurated list below is binding. Future cycles implement only the
Steps proposed here.

## Certification snapshot (LFC-2E0+1+2, 2026-09-20)

```text
Production Step keys total: 18
  Registry (open-world Step seam):  17   (CoreStepRegistryFactory)
  Legacy (Canonical*NodeDispatcher): 0    (LEGACY_PLUGIN_IDS = {})
  External plugin (ServiceLoader):   1    (example.uppercase)

CERTIFIED (full G0..G8 + real installDist + receipt): 8
  core.echo              (S3 burn-down)
  core.sh                (LB-02 S6 burn-down)
  core.error             (S2-A1 burn-down)
  core.sleep             (S2-A2 burn-down)
  core.writeFile         (S2-A3 burn-down)
  core.isUnix            (S2-A5 burn-down)
  core.deleteDir         (S2-A7 burn-down)
  core.milestone         (S2-A9 burn-down)
  example.uppercase      (EP-5 burn-down, external)

G6 only (contract suite green; pending G8 installDist): 4
  core.emit.event        (S2-A4/G6)
  core.pwd               (S2-A6/G6)
  core.cleanWs           (S2-A10/G6)
  core.archiveArtifacts  (S2-B10/G6)

CERTIFIED but missing formal contract suite row: 1
  core.writeFile         (G8 done; add StepContractSuiteTest)

REGISTRY_PRIMARY, contract pending, installDist pending: 2
  core.waitUntil         (G5 done; needs G6+G8)
  core.pwdTmp            (no G6 yet; needs G6+G8)

External: 1
  example.uppercase      (CERTIFIED)
```

Total **CERTIFIED or pending** = 18 production Step keys. The legacy path
is empty (`LEGACY_PLUGIN_IDS = {}`); no Step is routed through
`Canonical*NodeDispatcher` in production.

## Binding Plan — Depurated list

The list below is binding. A future cycle that adds a Step **not on the
list** must justify it in an ADR first. A Step **on the list** must be
brought to `CERTIFIED` (G8 + fitness + contract suite + real installDist)
before the cycle closes.

### Tier A — CORE pending G8 (close immediately, 6-7 WUs)

| # | Step key | Current state | Required action | WU estimate |
|---|----------|---------------|-----------------|-------------|
| 1 | `core.emit.event` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 2 | `core.pwd` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 3 | `core.cleanWs` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 4 | `core.archiveArtifacts` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 5 | `core.waitUntil` | registry + G5 (no contract) | G6 contract + G8 installDist | 1-2 WUs |
| 6 | `core.pwdTmp` | registry, no G6/G8 | G6 contract + G8 installDist | 1-2 WUs |
| 7 | `core.writeFile` (formal contract test) | registry + G8 | add `StepContractSuiteTest` | 1 WU |

### Tier B — CORE next gate (genéricos universales, 7 Steps)

| # | Step key | Genericidad | WU estimate |
|---|----------|-------------|-------------|
| 8 | `junit.results` (full burn-down to CERTIFIED) | universal XML | 1-2 WUs |
| 9 | `publishHTML` | universal HTML | 1 WU |
| 10 | `stash` | universal mem-between-stages | 1 WU |
| 11 | `unstash` | universal (pair of stash) | 1 WU |
| 12 | `lock` | universal resource lock | 1-2 WUs |
| 13 | `input` | manual approval | 1-2 WUs |
| 14 | `httpRequest` | universal HTTP | 1-2 WUs |

### Tier C — CORE optional (low priority, on demand)

| # | Step key | Reason optional |
|---|----------|-----------------|
| 15 | `readTOML` / `writeTOML` | formato moderno (Pyproject/Cargo); demand-driven |
| 16 | `tar` / `untar` | paridad con zip; demand-driven |

### Tier D — REJECTED (NEVER in core; vendor/external/derivable)

The following Steps are **explicitly rejected** from core. Reasons are
recorded here so future cycles do not re-debate them. Implementation, if
ever demanded, goes through (a) `sh "..."` for derivable cases, or
(b) a third-party plugin external to the core SDK.

| Step | Reason for rejection |
|------|----------------------|
| `tool`, `withMaven`, Maven helpers | tool-specific (Maven); users can `sh "mvn ..."` |
| Gradle toolchain/wrapper helpers | tool-specific |
| NodeJS / npm / pnpm / yarn | tool-specific; users can `sh "npm ..."` |
| Python / pip / Poetry | tool-specific; users can `sh "pip ..."` |
| .NET | tool-specific |
| Go | tool-specific |
| Config File Provider | Jenkins admin-only idiom; no portable use |
| `docker.build/push/pull/inside/withRun` | tool-specific (Docker); plugin external |
| `kubernetes.podTemplate/container` | tool-specific + REMOTE-only (M5) |
| `slackSend` | vendor-specific (Slack); plugin external |
| `emailext` | vendor-specific (Jenkins extension); plugin external |
| `mail` (simple) | low value + covered by `emailext` plugin if needed |
| `sshagent` | Jenkins-idiom with credential helper; derivable with `withCredentials` + `sh` |
| SSH command/get/put/remove | derivable with `withCredentials` + `sh`; plugin external if demanded |
| `copyArtifacts` | Jenkins cross-job idiom; not portable; not local-first |
| `build(job:)` | controller/REMOTE-only (M4/M5) |
| `waitForBuild` | controller/REMOTE-only |
| `properties` | controller/job mutation; REMOTE-only |
| `timestamps` | output decorator, cosmetic; plugin external `timestamper` |
| `ansiColor` | output decorator, cosmetic; plugin external |
| `git` (advanced flags: depth, submodules, LFS, sparse) | CLI-git flags; derivable with `git --depth` in `sh` |
| `readScmFile` | derivable with `readFile` |
| SVN / Mercurial | niche SCM; DEFER until demanded |
| `md5` / `sha1` | legacy/weak hash; security risk; sha256 already exists |
| `readManifest` | Java/Maven-only format; niche |
| `compareVersions` | not generic; niche |
| `checksum verify variants` | niche; sha256 + sh covers it |
| `touch` / `prependToFile` / `tee` | derivable with `sh` + `writeFile` |
| Coverage (JaCoCo/Cobertura/LCOV/OpenCover) | tool-specific formats; plugin external |
| `recordIssues` / static-analysis reports | tool-specific; plugin external |
| Maven POM read/write | tool-specific; plugin external |

### Tier E — EXTERNAL (responsibility of third parties / vendors)

The core SDK is enough. The team will NOT implement these; they are
the responsibility of the respective vendor / community.

| Family | Vendor responsible |
|--------|--------------------|
| Artifactory / Xray | JFrog |
| SonarQube | SonarSource |
| Vault credential bindings | HashiCorp |
| AWS, Azure, GCP | respective cloud providers |

## Already-CERTIFIED inventory (full G0..G8 + receipt)

For each, the file:line evidence is in
[`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`](../../v2/07-uat/STEP_INVENTORY_LFC2E0.md)
and the per-Step receipt in `docs/v2/07-uat/S2_*_G8_*_*.md`.

| Step | Receipt | Notes |
|------|---------|-------|
| `core.echo` | `S3_ECHO_BURNDOWN_CERTIFICATION.md` | reference atomic Step (registry; `CoreEchoStep`) |
| `core.sh` | `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` | reference effectful Step (registry; `CoreShellStep`) |
| `core.error` | `S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md` | typed failure |
| `core.sleep` | `S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `core.writeFile` | `S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md` | formal `StepContractSuiteTest` pending |
| `core.isUnix` | `S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `core.deleteDir` | `S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md` | |
| `core.milestone` | `S2_A9_CORE_MILESTONE_G8_FINAL_CERTIFICATION_RECEIPT.md` | |
| `example.uppercase` | `LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` | external plugin reference |

## Universal core and already-present families (state per row)

> **LFC-2E0+1+2 inventory note (2026-09-20):** every `core.*` key is
> registered through `CoreStepRegistryFactory`. The
> `LEGACY_PLUGIN_IDS` set is **empty** since WU-LPR-301 (2026-09-18).
> `StructuralFamilyResolver.classify` routes every `core.*` key through
> `StructuralStepFamily.Registry`. The legacy
> `Canonical*NodeDispatcher` files exist for documentation/parity tests
> only; they are unreachable in production.
>
> Block / orchestration DSL (`retry`, `timeout`, `catchError`, `warnError`,
> `unstable`, `parallel`, `dir`, `withEnv`, `withCredentials`) are NOT
> Step keys — they are orchestration that re-enters the engine through
> `BodyInvoker.invoke` / `BranchInvoker.invokeAll` (ADR-0073).

| Family / Step | Delivery | State | Priority | Target note |
|---|---|---|---:|---|
| `echo` | CORE | **CERTIFIED** | — | done |
| `sh` | CORE | **CERTIFIED** | — | done |
| `error` | CORE | **CERTIFIED** | — | done |
| `sleep` | CORE | **CERTIFIED** | — | done |
| `writeFile` | CORE | **CERTIFIED** | — | done; formal contract test pending |
| `isUnix` | CORE | **CERTIFIED** | — | done |
| `deleteDir` | CORE | **CERTIFIED** | — | done |
| `cleanWs` | CORE | G6 only | Tier A | G8 installDist |
| `milestone` | CORE | **CERTIFIED** | — | done |
| `waitUntil` | CORE | G5 only | Tier A | G6 + G8 |
| `archiveArtifacts` | CORE | G6 only | Tier A | G8 installDist |
| `pwd` | CORE | G6 only | Tier A | G8 installDist |
| `pwdTmp` | CORE | no G6 | Tier A | G6 + G8 |
| `emit.event` | CORE | G6 only | Tier A | G8 installDist |
| `load` | (UNSUPPORTED) | DEFERRED | — | DSL `load(path)` is fail-closed at compile-time (per WU-LPR-301 / G5) |
| `retry` | (block step) | IMPLEMENTED | — | block Step via `BodyInvoker.invoke`; not a `StepDefinition` |
| `timeout` | (block step) | IMPLEMENTED | — | block Step + cancellation; not a `StepDefinition` |
| `catchError` | (block step) | IMPLEMENTED | — | example 07 is behavioral oracle; not a `StepDefinition` |
| `warnError` | (block step) | IMPLEMENTED | — | same block engine; not a `StepDefinition` |
| `unstable` | (block step) | IMPLEMENTED | — | typed outcome semantics; not a `StepDefinition` |
| `parallel` | (block step) | IMPLEMENTED | — | durable named bodies, partial order; ADR-0073 |
| `dir` | (block step) | IMPLEMENTED | — | immutable execution context; CTX-P |
| `withEnv` | (block step) | IMPLEMENTED | — | immutable env context; CTX-P |
| `withCredentials` | (block step) | IMPLEMENTED | — | credential bindings compiled; not a `StepDefinition` |
| `timestamps` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | — | output decorator; plugin external |
| `ansiColor` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | — | output decorator; plugin external |
| `node` | DEFERRED_REMOTE | DEFERRED | — | no fake remote scheduling before M4/M5 |

## SCM

| Step | Delivery | State | Note |
|---|---|---|---|
| `git` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D for advanced flags) | basic checkout covered by `scm-git.checkout`; advanced flags derivable with `sh` |
| `checkout` (`scm-git.checkout`) | OFFICIAL_PLUGIN | **CERTIFIED** | in `pipeline-step-sdk/scm-git` |
| `readScmFile` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | derivable with `readFile` |
| shallow/depth | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | CLI-git flag in `sh` |
| submodules | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | CLI-git flag in `sh` |
| LFS | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | CLI-git flag in `sh` |
| sparse checkout | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | CLI-git flag in `sh` |
| SVN / Mercurial | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | niche; demand-driven only |

## Testing, reports and quality

| Step | Delivery | State | Note |
|---|---|---|---|
| `junit.results` | OFFICIAL_PLUGIN | Tier B | full burn-down to CERTIFIED |
| `publishHTML` | OFFICIAL_PLUGIN candidate | Tier B | generic HTML publish |
| `coverage` (JaCoCo/Cobertura/LCOV/OpenCover) | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | tool-specific formats |
| `recordIssues` / static-analysis reports | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | tool-specific |

## Pipeline utility / structured data

| Step | Delivery | State | Note |
|---|---|---|---|
| `findFiles` | OFFICIAL_PLUGIN | **CERTIFIED** | in `pipeline-step-sdk/utilities` |
| `readJSON` / `writeJSON` | OFFICIAL_PLUGIN | **CERTIFIED** | in `pipeline-step-sdk/utilities` |
| `readYaml` / `writeYaml` | OFFICIAL_PLUGIN | **CERTIFIED** | in `pipeline-step-sdk/utilities` |
| `readTOML` / `writeTOML` | OFFICIAL_PLUGIN candidate | Tier C (optional) | on demand (Pyproject/Cargo) |
| `readProperties` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | Java/Maven-specific |
| `readManifest` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | Java-only niche |
| `zip` / `unzip` | OFFICIAL_PLUGIN | **CERTIFIED** | with zip-slip defense |
| `tar` / `untar` | OFFICIAL_PLUGIN candidate | Tier C (optional) | paridad con zip |
| `sha256` | OFFICIAL_PLUGIN | **CERTIFIED** | |
| `md5` / `sha1` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | legacy/weak hash |
| `touch` / `prependToFile` / `tee` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | derivable with `sh` |
| `checksum verify variants` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | sha256 + sh covers it |
| Maven POM read/write | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | tool-specific |
| `compareVersions` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | not generic |

## Artifacts and data movement

| Step | Delivery | State | Note |
|---|---|---|---|
| `archiveArtifacts` | OFFICIAL_PLUGIN candidate | G6 only | G8 installDist pending |
| `stash` | OFFICIAL_PLUGIN candidate | Tier B | full burn-down |
| `unstash` | OFFICIAL_PLUGIN candidate | Tier B | pair of stash |
| `copyArtifacts` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | cross-job Jenkins idiom |

## Toolchains and build ecosystems (all REJECTED-core, Tier D)

| Step | Reason |
|---|---|
| `tool` | tool-specific |
| Maven / `withMaven` | tool-specific |
| Gradle toolchain/wrapper helpers | tool-specific |
| NodeJS / npm / pnpm / yarn | tool-specific |
| Python / pip / Poetry | tool-specific |
| .NET | tool-specific |
| Go | tool-specific |
| Config File Provider | Jenkins admin-only |

## Network, SSH and notifications

| Step | Delivery | State | Note |
|---|---|---|---|
| `httpRequest` | OFFICIAL_PLUGIN candidate | Tier B | universal HTTP |
| `sshagent` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | derivable with `withCredentials` + `sh` |
| SSH command/get/put/remove | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | derivable with `withCredentials` + `sh` |
| `mail` (simple) | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | low value; `emailext` plugin if needed |
| `emailext` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | vendor-specific (Jenkins extension) |
| `slackSend` | OFFICIAL_PLUGIN candidate | REJECTED-core (Tier D) | vendor-specific (Slack) |

## Coordination and interaction

| Step | Delivery | State | Note |
|---|---|---|---|
| `lock` | OFFICIAL_PLUGIN candidate | Tier B | universal resource lock |
| `input` | OFFICIAL_PLUGIN candidate | Tier B | local CLI/manual approval first |
| `build(job:)` | DEFERRED_REMOTE | DEFERRED | Jenkins/controller job semantics |
| `waitForBuild` | DEFERRED_REMOTE | DEFERRED | same |
| `properties` | DEFERRED_REMOTE | DEFERRED | controller/job mutation |

## Local containers (all REJECTED-core, Tier D)

| Step | Reason |
|---|---|
| image pull / build / push | tool-specific (Docker/Podman) |
| `inside` equivalent | tool-specific |
| `withRun` equivalent | tool-specific |
| registry scope | tool-specific |
| Kubernetes `podTemplate` | REMOTE-only (M5) |
| Kubernetes `container` on worker pod | REMOTE-only (M5) |

## External reference plugins (Tier E)

The core SDK is enough. Vendor plugins are not implemented by core.

| Family | Vendor responsible |
|---|---|
| Artifactory/Xray | JFrog |
| SonarQube | SonarSource |
| Vault | HashiCorp |
| AWS | Amazon |
| Azure | Microsoft |
| GCP | Google |

## Rejected Jenkins-internal compatibility (binding)

Per `STEP_ECOSYSTEM_POLICY.md R12` and ADR-0074:

| Jenkins surface | Reason |
|---|---|
| `step($class:...)` | bridge to Jenkins Java extension model |
| `wrap($class:...)` generic bridge | same |
| `getContext` | use typed capabilities/context parameters |
| `withContext` | use typed capabilities/context parameters |

## Family certification progression (binding plan)

```text
LFC-2E1-S2  Tier A close (6-7 WUs, already in registry)
LFC-2E2     Tier B batch 1 (junit.results, stash/unstash, publishHTML)
LFC-2E3     Tier B batch 2 (lock, input, httpRequest)
LFC-2E4+    Tier C on demand
```

A new family beyond Tier C requires an ADR-level justification. The
Tier D and Tier E lists are closed: no core change without ADR.

## Strict Validation Set (per Step, applied to every candidate)

Every Step on the depurated list, before it can be moved to `CERTIFIED`,
must pass all of the following in a single commit-and-verify round.
Details: [`openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/proposal.md`](../../../openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/proposal.md).

### 1. ADR / constitutional gates (mechanical)

- [ ] `when(stepName/stepKey)` is **absent** in
      `CanonicalDurableRunCoordinator` and `CanonicalNodeDispatcher`
      (fitness `Lfc2ConcreteBodyRoutingDebtFitnessTest`).
- [ ] No privileged core execution path bypassing the registry seam
      (fitness `Lfc2RegistryFamilyFitnessTest`).
- [ ] `StepSpec.RegistryStepSpec` is the only generic structural
      representation used (no concrete `StepSpec` subtype).
- [ ] Plugin handler declares capabilities == capabilities used
      (fitness `Lfc2DurableCoordinatorScopeFitnessTest`).
- [ ] No global cwd/env mutation (fitness `Lfc0GlobalStateFitnessTest`).

### 2. Step contract gates (STEP_PLUGIN_CERTIFICATION.md R2..R4)

The 19 dimensions C01..C19 must all be green. See `proposal.md` for the
list.

### 3. Fitness test suite (architecture-tests module)

Each candidate Step must, at minimum, leave these green:

- `Lfc2RegistryFamilyFitnessTest`
- `Lfc2DurableCoordinatorScopeFitnessTest`
- `Lfc2ConcreteBodyRoutingDebtFitnessTest`
- `Lfc2BodyExecutionPolicyFitnessTest`
- `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`
- `Lfc2B11ExternalScopedRoutingDefenseFitnessTest`
- `S3<Name>LegacyRemovedFitnessTest`
- `LegacyResidualConvergenceFitnessTest`

### 4. StepContractSuite (one per Step, 16/17 dimensions minimum)

Already-canonical template at `EchoStepContractSuiteTest` /
`ShStepContractSuiteTest`. Required rows listed in `proposal.md`.

### 5. installDist + real installed binary (G8 evidence)

```text
installDist : ./gradlew -p v2 :pipeline-application:installDist
binary      : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
scenario    : real fixture in v2/compatibility/NN-<step>.pipeline.kts
db/control  : SAME for fresh and replay runs
sha256      : of the binary + artifact fingerprint in the receipt
```

### 6. Receipt (per WU)

A new file under `docs/v2/07-uat/S2_<name>_G<0..8>_*.md` (or
`LFC2E<XY>_<name>_G<gate>_*.md` for new families) with: argv, exit code,
XML counters, SHA-256, real fixture run output, and final
`Status: CERTIFIED` (never `DONE/PASS`/`IMPLEMENTED_UNCERTIFIED`).

## Provider / Release / Policy-surface columns (LFC-2E2 precondition)

Per the prior matrix (unchanged), new plugin families born in or after
LFC-2E2 SHALL add the following dimensions per row: Provider,
Plugin ResourceRef, Plugin Families, Trust Metadata, Capabilities,
Policy Surface, Release Identity. These are metadata, not runtime
admission verdicts.

## Expansion precondition summary (binding)

```text
Tier A       burn down 6-7 already-registry Steps to G8 (LFC-2E1-S2)
LFC-2E2      Tier B batch 1 (junit.results, stash/unstash, publishHTML)
LFC-2E3      Tier B batch 2 (lock, input, httpRequest)
LFC-2E4+     Tier C on demand (TOML, tar)
Tier D       REJECTED (binding; ADR-level only)
Tier E       EXTERNAL (vendor responsibility)
```
