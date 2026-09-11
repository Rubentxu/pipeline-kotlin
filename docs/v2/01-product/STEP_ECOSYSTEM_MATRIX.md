# Step Ecosystem Matrix — local-first coverage and delivery

Status: PROPOSED baseline for LFC-2 ecosystem expansion.
Source inputs: `JENKINS_FAMILIARITY_CATALOG.md`, current v2 implementation, ADR-0070..0074 and the certified Step inventory.

**Certification snapshot (LFC-2E0, 2026-09-11):**

```text
CERTIFIED:                              3
  core.echo                             (registry; CoreEchoStep)
  core.sh                               (registry; CoreShellStep)
  example.uppercase                     (external plugin; ServiceLoader)

LEGACY_IMPLEMENTED_UNCERTIFIED:        12
  core.{error, sleep, file.writeFile, emit.event, milestone,
        deleteDir, cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts}
  routed through Canonical*NodeDispatcher; no StepDefinition; no contract suite

NOT_STARTED (production):               n/a (no StepDefinition beyond the 3 above)
NOT_STARTED (planning rows):            ~25 (git family, testing, HTTP, lock, Docker, etc.)

Block / orchestration DSL (NOT Steps):  14 (retry, timeout, catchError, warnError,
                                            unstable, parallel, dir, withEnv,
                                            withCredentials, readFile, fileExists,
                                            timestamps, ansiColor, node)
```

**This matrix is the planning hypothesis. The authoritative source is
[`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`](../../v2/07-uat/STEP_INVENTORY_LFC2E0.md) — machine-derived from the production code on 2026-09-11.**
Every row below is cross-checked against the inventory; corrections are noted inline as **(corrected LFC-2E0)**.

This matrix is a planning/certification view, not an assertion that every historically implemented DSL façade is currently certified.
A Step is complete only when `CERTIFIED` by the common suite.

## Legend

Delivery:
- `CORE`
- `OFFICIAL_PLUGIN`
- `EXTERNAL_REFERENCE`
- `DEFERRED_REMOTE`
- `REJECTED_JENKINS_INTERNAL`

State:
- `CERTIFIED`
- `IMPLEMENTED_UNCERTIFIED`
- `LEGACY_IMPLEMENTED_UNCERTIFIED` — exists as legacy executable path through `Canonical*NodeDispatcher`; needs G0..G8 burn-down to registry seam (AGENTS.md Step Constitution)
- `DESIGNED`
- `NOT_STARTED`
- `DEFERRED`
- `REJECTED`

Priority:
- `P0` — close/certify before broad ecosystem expansion;
- `P1` — high-value local-first coverage;
- `P2` — useful breadth after the main local pipeline path is strong;
- `P3` — niche/vendor breadth.

## Universal core and already-present families

> **LFC-2E0 inventory note (2026-09-11):** only `core.echo` and `core.sh` are
> registered through `CoreStepRegistryFactory` and routed through the open-world Step
> registry seam. The remaining `core.*` keys are routed through legacy
> `Canonical*NodeDispatcher` files (see `LEGACY_PLUGIN_IDS` in
> `CanonicalCoreStepDecoder.kt`). They have a typed DSL façade but lack a
> `StepDefinition<I,O>` form, a contract suite, capability declaration, replay policy,
> and certification receipt.
>
> Block / orchestration DSL (`retry`, `timeout`, `catchError`, `warnError`, `unstable`,
> `parallel`, `dir`, `withEnv`, `withCredentials`, `readFile`, `fileExists`, `timestamps`,
> `ansiColor`, `node`) are NOT Step keys — they are orchestration that re-enters the
> engine through `BodyInvoker.invoke` / `BranchInvoker.invokeAll` (ADR-0073).
>
> Source of truth: `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`.

| Family / Step | Delivery | Current evidence/state | Priority | Target note |
|---|---|---:|---:|---|
| `echo` | CORE | CERTIFIED | P0 | reference Step (registry; `CoreEchoStep`) |
| `sh` | CORE | CERTIFIED | P0 | reference effectful Step (registry; `CoreShellStep`) |
| `error` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P0 | **legacy**; needs G0..G8 burn-down |
| `sleep` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P0 | **legacy**; needs G0..G8 burn-down |
| `writeFile` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P0 | **legacy** (`core.file.writeFile`); needs burn-down |
| `milestone` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P2 | **legacy** (`core.milestone`); needs burn-down |
| `deleteDir` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P1 | **legacy** (`core.deleteDir`); needs burn-down |
| `cleanWs` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P1 | **legacy** (`core.cleanWs`); needs burn-down |
| `load` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P2 | **legacy** (`core.load`); needs burn-down |
| `pwd` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P0 | **legacy** (`core.pwd`); needs burn-down |
| `isUnix` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P0 | **legacy** (`core.isUnix`); needs burn-down |
| `waitUntil` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P1 | **legacy** (`core.waitUntil`); needs burn-down |
| `archiveArtifacts` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P2 | **legacy** (`core.archiveArtifacts`); needs burn-down |
| `emitEvent` | CORE | LEGACY_IMPLEMENTED_UNCERTIFIED | P2 | **legacy** (`core.emit.event`); needs burn-down |
| `retry` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | block Step via `BodyInvoker.invoke`; not a `StepDefinition` |
| `timeout` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | block Step + cancellation; not a `StepDefinition` |
| `catchError` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | example 07 is behavioral oracle; not a `StepDefinition` |
| `warnError` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | same block engine; not a `StepDefinition` |
| `unstable` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | typed outcome semantics; not a `StepDefinition` |
| `parallel` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | durable named bodies, partial order; ADR-0073 |
| `dir` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | immutable execution context; CTX-P |
| `withEnv` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | immutable env context; CTX-P |
| `withCredentials` | (block step) | IMPLEMENTED_UNCERTIFIED | P0 | credential bindings compiled; not a `StepDefinition` |
| `readFile` | (block step) | NOT_STARTED | P0 | not in DSL or production code; needs design + burn-down |
| `fileExists` | (block step) | NOT_STARTED | P0 | not in DSL or production code; needs design + burn-down |
| `timestamps` | OFFICIAL_PLUGIN candidate | NOT_STARTED | P1 | output decorator; needs design |
| `ansiColor` | OFFICIAL_PLUGIN candidate | NOT_STARTED | P2 | output decorator; needs design |
| `node` | DEFERRED_REMOTE | local compatibility/no-op exists | P3 | no fake remote scheduling before M4/M5 |

## SCM

> **LFC-2E0 inventory note (2026-09-11):** the `git`, `checkout`, `scmGit` DSL
> façades exist in `PipelineDsl.kt` (L1050, L1066, L1094), but no
> `StepDefinition` exists in `pipeline-step-sdk/scm-git`, no entry in
> `CoreStepRegistryFactory`, no entry in `LEGACY_PLUGIN_IDS`. The SDK has the
> executor (`scm-git/GitCheckoutExecutor`), but it is wired through the legacy
> dispatcher path, NOT through the registry seam. Per ADR-0074 a Step cannot be
> CERTIFIED while it remains legacy-executable and outside the registry.

| Step / capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| `git` | OFFICIAL_PLUGIN candidate | NOT_STARTED (no `StepDefinition`) | P0 | needs design + G0..G8 burn-down |
| `checkout` | OFFICIAL_PLUGIN candidate | NOT_STARTED (no `StepDefinition`) | P0 | needs design + G0..G8 burn-down |
| `readScmFile` | OFFICIAL_PLUGIN candidate | NOT_STARTED | P1 | local SCM read capability |
| shallow/depth | OFFICIAL_PLUGIN candidate | NOT_STARTED | P1 | advanced Git checkout |
| submodules | OFFICIAL_PLUGIN candidate | NOT_STARTED | P1 | recursive/submodule options |
| LFS | OFFICIAL_PLUGIN candidate | NOT_STARTED | P2 | Git LFS where git client supports it |
| sparse checkout | OFFICIAL_PLUGIN candidate | NOT_STARTED | P2 | typed checkout extension |
| SVN/Mercurial | OFFICIAL_PLUGIN candidate or external | DEFERRED | P3 | only on evidence/demand |

## Testing, reports and quality

| Step / capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| `junit` | OFFICIAL_PLUGIN | NOT_STARTED / specs exist | P0 | typed `TestReport`; real XML UAT |
| `publishHTML` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local report artifact |
| coverage | OFFICIAL_PLUGIN | NOT_STARTED | P1 | JaCoCo/Cobertura/LCOV/OpenCover via typed adapters |
| `recordIssues` / static-analysis reports | OFFICIAL_PLUGIN | NOT_STARTED | P2 | typed issue model, not analyzer-specific core cases |

## Pipeline utility / structured data

| Step / capability | Delivery | State | Priority |
|---|---|---:|---:|
| `readJSON` / `writeJSON` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `readYaml` / `writeYaml` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `readTOML` / `writeTOML` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `readProperties` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `readManifest` | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `findFiles` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `touch` / `prependToFile` / `tee` | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `md5` / `sha1` / `sha256` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| checksum verify variants | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `zip` / `unzip` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `tar` / `untar` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| Maven POM read/write | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `compareVersions` | OFFICIAL_PLUGIN | NOT_STARTED | P2 |

## Artifacts and data movement

| Step / capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| `archiveArtifacts` | OFFICIAL_PLUGIN candidate | IMPLEMENTED_UNCERTIFIED | P0 | complete semantics + certify; keep core only if generic seam cannot express it |
| `stash` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local durable stash store |
| `unstash` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | pair with stash |
| `copyArtifacts` | OFFICIAL_PLUGIN | NOT_STARTED | P2 | use RunRef/ResourceRef rather than Jenkins controller coupling |

## Toolchains and build ecosystems

| Capability / compatibility surface | Delivery | State | Priority |
|---|---|---:|---:|
| `tool` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| Maven / `withMaven` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| Gradle toolchain/wrapper helpers | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| NodeJS / npm / pnpm / yarn | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| Python / pip / Poetry | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| .NET | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| Go | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| Config File Provider-like capability | OFFICIAL_PLUGIN | NOT_STARTED | P1 |

## Network, SSH and notifications

| Step / capability | Delivery | State | Priority |
|---|---|---:|---:|
| `httpRequest` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| `sshagent` | OFFICIAL_PLUGIN | NOT_STARTED | P1 |
| SSH command/get/put/remove | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `mail` / `emailext` | OFFICIAL_PLUGIN | NOT_STARTED | P2 |
| `slackSend` | OFFICIAL_PLUGIN or EXTERNAL_REFERENCE | NOT_STARTED | P2 |

## Coordination and interaction

| Step / capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| `lock` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | durable local resource lock manager |
| `input` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local CLI/manual approval first; remote UI later |
| `build(job:)` | DEFERRED_REMOTE | DEFERRED | P3 | Jenkins/controller job semantics out of local-first scope |
| `waitForBuild` | DEFERRED_REMOTE | DEFERRED | P3 | same |
| `properties` | DEFERRED_REMOTE | DEFERRED | P3 | controller/job mutation |

## Local containers

| Capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| image pull | OFFICIAL_PLUGIN | NOT_STARTED | P1 | provider-neutral container runtime |
| image build | OFFICIAL_PLUGIN | NOT_STARTED | P1 | Docker/Podman adapter |
| image push | OFFICIAL_PLUGIN | NOT_STARTED | P1 | registry credentials capability |
| `inside` equivalent | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local process scope inside container |
| `withRun` equivalent | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local side service lifecycle |
| registry scope | OFFICIAL_PLUGIN | NOT_STARTED | P1 | typed registry credentials |
| Kubernetes `podTemplate` | DEFERRED_REMOTE | DEFERRED | P3 | M5 worker provisioning |
| Kubernetes `container` on worker pod | DEFERRED_REMOTE | DEFERRED | P3 | M5 |

## External reference plugins

| Family | Delivery | Priority | Minimum reference surface |
|---|---|---:|---|
| Artifactory/Xray | EXTERNAL_REFERENCE | P1 | upload, download, build-info, promote, scan |
| SonarQube | EXTERNAL_REFERENCE | P1 | scanner environment, analysis invocation/quality result |
| Vault | EXTERNAL_REFERENCE | P2 | contributed credential bindings / secret fetch |
| AWS | EXTERNAL_REFERENCE | P2 | representative auth + artifact/deploy use case |
| Azure | EXTERNAL_REFERENCE | P3 | representative provider flow |
| GCP | EXTERNAL_REFERENCE | P3 | representative provider flow |

## Rejected Jenkins-internal compatibility

| Jenkins surface | Delivery | Reason |
|---|---|---|
| `step($class:...)` | REJECTED_JENKINS_INTERNAL | bridge to Jenkins Java extension model |
| `wrap($class:...)` generic bridge | REJECTED_JENKINS_INTERNAL | same |
| `getContext` | REJECTED_JENKINS_INTERNAL | use typed capabilities/context parameters |
| `withContext` | REJECTED_JENKINS_INTERNAL | use typed capabilities/context parameters |

## Family certification progression

Target difficulty ladder after EVT-3, anchored to the LFC-2E0 inventory
(`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`):

```text
E0  certify existing families        (12 legacy core keys → registry seam; LFC-2E0 inventory)
E1  universal core freeze            (no more new core Steps without registry + capability + contract suite)
E2  utilities plugin                 (filesystem + typed deterministic values; OFFICIAL_PLUGIN)
E3  testing/reports plugin           (junit, publishHTML, coverage; OFFICIAL_PLUGIN)
E4  artifacts/stash                  (cleanWs promoted, archiveArtifacts review)
E5  toolchains/config                (typed JDK/Toolchain matrix; OFFICIAL_PLUGIN)
E6  HTTP/SSH/notifications           (network + credentials + typed response)
E7  lock/input                       (fileLock, input step; core or plugin)
E8  Docker/Podman                    (process wrapper + image registry credentials)
E9  advanced Git                     (LFS, sparse, submodules; OFFICIAL_PLUGIN candidate)
E10 complex external reference       (Artifactory / SonarQube / Vault; EXTERNAL_REFERENCE)
```

Each level must pass the same public plugin seam. A Step-specific core edit
(`when(stepName)`, `CanonicalDurableRunCoordinator` step-specific logic,
privileged core path) is a failed architecture gate, not a normal implementation
technique — per AGENTS.md user law #7.
