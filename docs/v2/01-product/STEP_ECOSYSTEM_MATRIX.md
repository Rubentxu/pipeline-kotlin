# Step Ecosystem Matrix — local-first coverage and delivery

Status: PROPOSED baseline for LFC-2 ecosystem expansion.
Source inputs: `JENKINS_FAMILIARITY_CATALOG.md`, current v2 implementation, ADR-0070..0074 and the certified Step inventory.

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

| Family / Step | Delivery | Current evidence/state | Priority | Target note |
|---|---|---:|---:|---|
| `echo` | CORE | CERTIFIED | P0 | reference Step |
| `sh` | CORE | CERTIFIED | P0 | reference effectful Step |
| `error` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | migrate/certify on canonical seam |
| `sleep` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | durable timing semantics |
| `retry` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | block Step certification |
| `timeout` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | block Step + cancellation |
| `catchError` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | example 07 is behavioral oracle |
| `warnError` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | same block engine |
| `unstable` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | typed outcome semantics |
| `parallel` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | durable named bodies, partial order |
| `dir` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | immutable execution context |
| `withEnv` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | immutable env context |
| `withCredentials` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | all core Jenkins bindings already present; certify path |
| `pwd` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | real typed runtime value |
| `isUnix` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | real typed runtime value |
| `readFile` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | typed return required |
| `writeFile` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | workspace capability |
| `fileExists` | CORE | IMPLEMENTED_UNCERTIFIED | P0 | typed Boolean result |
| `deleteDir` | CORE | IMPLEMENTED_UNCERTIFIED | P1 | workspace cleanup |
| `cleanWs` | OFFICIAL_PLUGIN candidate | IMPLEMENTED_UNCERTIFIED | P1 | Jenkins-specific convenience; evaluate promotion evidence |
| `waitUntil` | CORE candidate | IMPLEMENTED_UNCERTIFIED | P1 | certify honest polling semantics |
| `timestamps` | OFFICIAL_PLUGIN | IMPLEMENTED_UNCERTIFIED | P1 | output decorator plugin |
| `ansiColor` | OFFICIAL_PLUGIN | IMPLEMENTED_UNCERTIFIED | P2 | output decorator plugin |
| `milestone` | OFFICIAL_PLUGIN | IMPLEMENTED_UNCERTIFIED | P2 | local durable coordination semantics |
| `node` | DEFERRED_REMOTE | local compatibility/no-op exists | P3 | no fake remote scheduling before M4/M5 |

## SCM

| Step / capability | Delivery | State | Priority | Target note |
|---|---|---:|---:|---|
| `git` | OFFICIAL_PLUGIN | IMPLEMENTED_UNCERTIFIED | P0 | certify existing implementation |
| `checkout` | OFFICIAL_PLUGIN | IMPLEMENTED_UNCERTIFIED | P0 | certify existing implementation |
| `readScmFile` | OFFICIAL_PLUGIN | NOT_STARTED | P1 | local SCM read capability |
| shallow/depth | OFFICIAL_PLUGIN | NOT_STARTED | P1 | advanced Git checkout |
| submodules | OFFICIAL_PLUGIN | NOT_STARTED | P1 | recursive/submodule options |
| LFS | OFFICIAL_PLUGIN | NOT_STARTED | P2 | Git LFS where git client supports it |
| sparse checkout | OFFICIAL_PLUGIN | NOT_STARTED | P2 | typed checkout extension |
| SVN/Mercurial | OFFICIAL_PLUGIN or external | DEFERRED | P3 | only on evidence/demand |

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

Target difficulty ladder after EVT-3:

1. existing core certification (`error/sleep/control/context/filesystem`);
2. utilities plugin — filesystem + typed deterministic values;
3. testing plugin — structured reports + events;
4. HTTP plugin — network + credentials + typed response;
5. SCM Git plugin — process + network + credentials + filesystem;
6. containers plugin — nested scopes/resources/registry credentials;
7. Artifactory or equivalent external reference — vendor API + artifacts + credentials + typed failures.

Each level must pass the same public plugin seam. A Step-specific core edit is a failed architecture gate, not a normal implementation technique.
