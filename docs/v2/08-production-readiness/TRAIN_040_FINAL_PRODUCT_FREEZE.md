# TRAIN-040-FINAL - PipelineK 0.40.0 Product Freeze

**Decision date:** 2026-09-27  
**Decision authority:** operator product-cut decision  
**Cycle:** `p-733fb505b5a6bd2d/train-040-final`  
**Inventory SHA:** `6f76999c83c478f17f1e13de6ed2342181f9c773`  
**Declared version:** `0.40.0-rc8`

## Decision

PipelineK 0.40.0 is frozen by product utility and ownership boundary, not by
implementing every Step registered in the repository.

The release product is:

> A local-first durable pipeline runner with a typed Kotlin DSL, shell
> execution, filesystem and workspace primitives, credentials, structured
> control flow, artifacts, reports, Git checkout through an official plugin,
> durable replay, and an open Step plugin SDK.

This decision does **not** move any existing Step between core and plugin for
0.40.0. Existing implementation locations and public compatibility surfaces
are preserved. The classification governs the release surface and all new
work after the freeze.

## Product categories

### Core public stable surface

These are the universal local execution primitives and the product's primary
public story:

| Family | Surface |
|---|---|
| Execution | `echo`, `sh`, `error`, `sleep` |
| Filesystem | `writeFile`, `readFile`, `fileExists`, `deleteDir`, `cleanWs` |
| Context | `pwd`, `isUnix` |
| Artifacts | `archiveArtifacts`, `stash`, `unstash`, `publishHTML` |
| Control | `waitUntil`, `milestone` |
| Scopes | `dir {}`, `withEnv {}`, `withCredentials {}` |
| Structural control | `retry {}`, `timeout {}`, `parallel {}` |

A surface listed here is a **release target**. It becomes part of the stable
claim only after the exact candidate SHA has the required contract and
installed-distribution evidence. Historical receipts do not satisfy that
requirement.

### Core infrastructure, not the primary API story

These keys may remain available for composition and runtime infrastructure:

- `core.emit.event`
- `core.pwd.tmp`
- `core.artifact.query`

They remain subject to compatibility and fail-closed rules. They are not
Quickstart or headline product features.

### Official plugins

Official plugins are maintained by PipelineK but remain decoupled from the
engine's universal core:

- `scm-git.checkout`
- `junit.results`
- utilities: `core-utils.findFiles`, `core-utils.readJson`,
  `core-utils.writeJson`, `core-utils.readYaml`, `core-utils.writeYaml`,
  `core-utils.zip`, `core-utils.unzip`, `core-utils.sha256`

Official plugins use the same Step constitution as core:

```text
StepContract
  -> typed codecs
  -> declared capabilities
  -> registry admission
  -> durable/replay semantics
  -> StepContractSuite
  -> installed-distribution evidence
  -> SHA-bound certification
```

The difference is ownership and coupling, not quality.

### External reference plugin

`example.uppercase` remains an SDK certification fixture. It proves:

```text
external JAR
  -> ServiceLoader
  -> StepDefinitionContributor
  -> registry
  -> capability admission
  -> DSL
  -> durable execution
  -> zero core semantic changes
```

It is not a headline PipelineK product feature.

### Compatibility and optional surfaces

Existing `timestamps` and `ansiColor` compatibility surfaces may remain where
already implemented. They are presentation decorators, not new core design
work. Future evolution belongs in an optional or external projection/plugin.

No removal is part of the 0.40.0 freeze.

### Deferred after stable

The following are not 0.40.0 release blockers unless an already existing
contract or security defect makes them necessary:

- `lock`
- `input`
- `httpRequest`
- `readTOML` / `writeTOML`
- `tar` / `untar`
- markdown plugin
- additional report plugins
- toolchains
- containers
- cloud integrations

The first functional expansion block after stable is `lock`, `input`, and
`httpRequest` under a new product train.

### Rejected from core

The following remain outside core:

- Maven, Gradle, npm, yarn, pnpm, Poetry, pip, Cargo, Go, .NET
- Docker, Podman, Kubernetes worker/container semantics
- SonarQube, Artifactory/Xray, Vault, AWS, Azure, GCP, Slack
- Jenkins controller/admin idioms such as `step($class:...)`, `wrap`,
  `getContext`, `withContext`, `build(job:)`, `waitForBuild`, and `properties`
- advanced Git flags that are derivable with `sh`
- weak hashes and niche format/tool integrations

Derivable tool operations use `sh` and existing universal primitives. A new
Step requires a typed value or durable semantic value that justifies the
additional surface.

## Feature freeze law

Once the candidate identity is created:

```text
FEATURE FREEZE 0.40.0

No new Steps.
No new DSL features.
No new functional plugins.

Allowed:
- bug fixes
- security fixes
- correctness fixes
- certification gaps
- documentation contract drift
- packaging and installation defects
- compatibility regressions
- release and harness blockers
```

Any byte-changing fix produces a new candidate identity. No candidate may
inherit certification from a different source SHA or distribution ZIP.

## Current exact-SHA inventory

The machine-generated inventory was regenerated at the inventory SHA above:

```text
Production Step keys: 31
Core registry keys:   20
SDK plugin keys:      10
External plugins:      1
CERTIFIED_AT_SHA:     18
REGISTERED:            2
BLOCKED:               1
Legacy authority rows: 0
```

These numbers are evidence about the repository at the inventory SHA. They do
not automatically certify the proposed product surface. In particular:

- historical certification receipts must be revalidated for the candidate;
- the inventory generator currently under-recognizes the existing
  `scm-git.checkout` certification receipts and requires reconciliation;
- `junit.results` and the utilities require exact candidate certification
  evidence before being advertised as certified official plugins;
- `pwd` requires explicit resolution of its current runtime-return contract
  state before the stable claim is closed.

## Final release sequence

```text
F0  exact inventory and product freeze decision
F1  certify accepted core surface at the exact candidate SHA
F2  certify official plugins at the exact candidate SHA
F3  run the complete local-core/product UAT profile
F4  freeze an immutable release candidate and manifest
F5  hand the exact ZIP and digest to the external harness
F6  fix only actual harness or release blockers; each byte change creates RC+1
F7  stable promotion by the harness using the exact verified ZIP bytes
```

The repository owns implementation, local quality, candidate packaging, and
SHA/digest traceability. The external harness owns installed real-project
certification and stable promotion.

## Exit criteria

TRAIN-040-FINAL may close only when:

1. the frozen inventory and product boundary are committed and SHA-bound;
2. every advertised core surface has current contract and installed evidence;
3. every advertised official plugin has current contract, discovery,
   capability, replay, and installed evidence;
4. the external reference plugin remains certified or is explicitly blocked
   with evidence;
5. the local product UAT matrix is green for the exact candidate SHA;
6. the candidate ZIP, manifest, and SHA-256 are immutable and reproducible;
7. the exact bytes are delivered to the external harness;
8. stable is not claimed until the harness returns its structured verdict.

No `PASS` is inferred from a present test, an old receipt, a tag, or a log
from another SHA.
