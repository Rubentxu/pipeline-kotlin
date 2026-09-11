# LFC-2E — Local-first Step Ecosystem Expansion

Status: PROPOSED / implementation-ready after EVT-3 closes.
Depends on: LFC-2 Step Constitution/extensibility proof, CTX-P/P4-EX, EVT-0..EVT-3.
Defers: EVT-4+, M4 distributed/controller work, M5 Kubernetes workers, M6 Jenkins adapter, production policy enforcement.

## Strategic decision

After EVT-3 closes, pipeline-kotlin prioritizes **maximum useful local-first Step coverage** before resuming live relay/controller/remote worker work.

The sequence becomes:

```text
EVT-0 ✅
EVT-1 ✅
EVT-2 ✅
EVT-3 Event Harness
      ↓
══════════════════════════════════════════
 LFC-2E LOCAL-FIRST STEP ECOSYSTEM
══════════════════════════════════════════
      ↓
LFC-2E0 certify existing families
LFC-2E1 complete universal core
LFC-2E2 utilities official plugin
LFC-2E3 testing/reports official plugins
LFC-2E4 artifacts/stash official plugin
LFC-2E5 toolchains/config official plugins
LFC-2E6 HTTP/SSH/notifications official plugins
LFC-2E7 lock/input local coordination plugins
LFC-2E8 Docker/Podman local containers plugin
LFC-2E9 advanced SCM official plugin
LFC-2E10 external reference plugins
      ↓
LOCAL-FIRST FEATURE FREEZE
      ↓
EVT-4 detached live relay
      ↓
M4 controller/remote protocol
      ↓
M5 remote/Kubernetes workers
      ↓
M6 Jenkins adapter
```

EVT-4 is not cancelled; it is intentionally sequenced after the local-first product surface is broad and certified.

## Product outcome

A developer should be able to run representative real pipelines locally with no external controller:

```text
checkout
 -> configure toolchain
 -> build
 -> test
 -> reports/coverage
 -> artifacts/stash
 -> HTTP/SSH/integration calls
 -> optional local containers/services
 -> local lock/manual input
```

with durable semantics, typed results, structured history and Event Harness acceptance contracts.

## Delivery policy

See `../03-specifications/STEP_ECOSYSTEM_POLICY.md`.

Every family is classified as:

- `CORE`;
- `OFFICIAL_PLUGIN`;
- `EXTERNAL_REFERENCE`;
- `DEFERRED_REMOTE`;
- `REJECTED_JENKINS_INTERNAL`.

Default for non-universal functionality is `OFFICIAL_PLUGIN`, not core.

## Common Definition of Done

A family is not closed because its DSL compiles. Every Step that is claimed complete must be `CERTIFIED`.

Required chain:

```text
canonical/user-facing signature
        ↓
typed Kotlin façade
        ↓
StepDefinition<I,O>
        ↓
input/output codecs
        ↓
declared capabilities + effects
        ↓
canonical invocation path
        ↓
durable/replay semantics
        ↓
typed result/failure
        ↓
domain events where observable
        ↓
real .pipeline.kts scenario
        ↓
Event Harness contract
        ↓
Step/Plugin Contract Suite
        ↓
CERTIFIED
```

For `OFFICIAL_PLUGIN` and `EXTERNAL_REFERENCE`, the family must install/register/run with **zero Step-specific production core edits**.

## LFC-2E0 — Certify the catalog already present

Priority: P0.

Goal: turn historical implementations into evidence-backed certified Steps before multiplying the catalog.

Batches:

### E0-A primitives
- `error`;
- `sleep`.

### E0-B control
- `retry`;
- `timeout`;
- `catchError`;
- `warnError`;
- `unstable`;
- `parallel`.

### E0-C execution context
- `dir`;
- `withEnv`;
- `withCredentials`.

### E0-D workspace/files
- `writeFile`;
- `readFile`;
- `fileExists`;
- `deleteDir`;
- classify/certify `cleanWs`;
- classify/complete `archiveArtifacts`.

### E0-E runtime utilities/decorators
- `pwd`;
- `isUnix`;
- `waitUntil`;
- `load` if it remains an honest supported surface;
- `milestone`;
- `timestamps`;
- `ansiColor`.

### E0-F credentials + SCM
- `withCredentials` all supported core bindings;
- `git`;
- `checkout`.

Exit:
- exact certified inventory generated;
- all certified Steps have no legacy executable path;
- every claimed runtime value is real and typed;
- real examples + EVT-3 harness contracts green;
- no mandatory UAT is disabled/quarantined and reported as PASS.

## LFC-2E1 — Universal core completion

Priority: P0/P1.

Goal: stop core growth after universally useful execution/workspace/control primitives are complete.

Candidates:
- complete cross-platform process story only where universal (`sh`; native generic process API may exist underneath);
- `stash/unstash` are **not automatically core**; prefer artifacts plugin unless evidence forces promotion;
- evaluate `waitUntil` and workspace cleanup against core-admission rule;
- finish block Step semantics through BodyInvoker/BranchInvoker, never one-off dispatcher branches.

Exit:
- `STEP_ECOSYSTEM_MATRIX.md` has no unresolved P0 core row;
- core admission decisions recorded with evidence.

## LFC-2E2 — Official utilities plugin

Priority: P1.

Target surface:
- JSON: `readJSON`, `writeJSON`;
- YAML: `readYaml`, `writeYaml`;
- TOML: `readTOML`, `writeTOML`;
- properties/manifest;
- `findFiles`, `touch`, `prependToFile`, `tee` where useful;
- hashes/checksums;
- `zip/unzip`, `tar/untar`;
- version/POM helpers where justified.

Design direction:
- Jenkins-compatible façades may exist;
- native Kotlin results should be typed rather than raw `Object`/`Map<String,Any>`;
- deterministic/file-only operations should have strong replay/idempotency contracts.

SDK pressure-test: filesystem + typed values with no coordinator edits.

## LFC-2E3 — Testing, reports and quality plugins

Priority: P1.

First slice:
- `junit` with typed `TestReport`;
- `publishHTML` or generic local report publication.

Second slice:
- coverage adapters (JaCoCo/Cobertura/LCOV/OpenCover etc.);
- issue/static-analysis report model (`recordIssues`-like surface).

Avoid one core Step per analyzer format. Use typed parser/adapters inside the testing/quality plugin family.

SDK pressure-test: filesystem + structured results + events + artifact/report linkage.

## LFC-2E4 — Artifacts and stash

Priority: P1.

Target:
- complete/certify `archiveArtifacts`;
- `stash` / `unstash`;
- local `copyArtifacts` using RunId/ResourceRef rather than Jenkins-controller coupling.

Do not invent a generic ArtifactStore abstraction until at least two real operations prove shared lifecycle requirements.

SDK pressure-test: durable data movement + history + replay semantics.

## LFC-2E5 — Toolchains and config

Priority: P1/P2.

Official plugin families/capabilities:
- Java/JDK;
- Maven / withMaven-compatible façade;
- Gradle wrapper/toolchain helpers;
- NodeJS + npm/pnpm/yarn;
- Python + pip/Poetry;
- .NET;
- Go;
- Config File Provider-like scoped configuration.

Prefer typed provider-neutral native APIs with compatibility façades where valuable.

## LFC-2E6 — HTTP, SSH and notifications

Priority: P1/P2.

Target:
- typed `httpRequest`;
- `sshagent` and selected SSH operations;
- `mail`/`emailext` compatibility where valuable;
- Slack notification plugin or external reference depending on coupling.

Required capabilities should be explicit (`NETWORK`, `CREDENTIAL_USE`, filesystem as needed).

SDK pressure-test: network + credentials + typed response/failure + secret-safe output.

## LFC-2E7 — Local coordination and interaction

Priority: P1.

Target:
- durable local `lock` with resource/label semantics that have independent local value;
- local durable/manual `input`, initially via CLI interaction or an explicit local response mechanism.

Remote Jenkins/controller UI semantics remain deferred.

These slices intentionally test whether a future distributed implementation can preserve a stable local domain contract.

## LFC-2E8 — Docker/Podman local containers

Priority: P1.

Deliver as an official plugin through a provider-neutral runtime capability.

Target use cases:
- image pull/build/push;
- run/withRun side service;
- `inside`-like nested execution scope;
- registry credentials;
- Docker and Podman adapters where supported.

Do not import Kubernetes worker provisioning into this slice.

SDK pressure-test: process + credentials + nested scopes + lifecycle resources.

## LFC-2E9 — Advanced SCM

Priority: P1/P2.

Extend the official Git plugin:
- `readScmFile`;
- depth/shallow;
- submodules;
- refspec/prune/tags;
- sparse checkout;
- LFS where available;
- relative target dir and clean policies.

SVN/Mercurial remain evidence-driven, not automatic compatibility goals.

## LFC-2E10 — External reference plugins

Priority: P1/P3 depending on family.

Minimum serious reference plugin after `example.uppercase`:

### Artifactory/Xray candidate
- upload;
- download;
- publish build-info;
- promote;
- scan.

Additional candidates:
- SonarQube;
- Vault;
- one cloud provider integration.

These are not core and should preferably live in separate plugin artifacts/repositories once the packaging workflow is mature.

Exit:
- at least one complex external reference plugin CERTIFIED;
- no plugin-name-specific core changes;
- common PluginContractSuite covers installation, codecs, capabilities, replay, errors and real execution.

## Explicit defer/reject list

Until local-first feature freeze, do not prioritize:
- EVT-4+ live relay/transport;
- M4 remote controller protocol beyond already-closed work;
- M5 Kubernetes dynamic workers;
- M6 Jenkins adapter;
- Kubernetes `podTemplate` / remote container selection;
- Jenkins controller `build(job:)`, `waitForBuild`, `properties` semantics;
- generic Jenkins `step($class:)`, `wrap($class:)`, `getContext`, `withContext` bridges;
- Cedar enforcement.

These are tracked, not discarded.

## Local-first feature freeze exit

The freeze is evidence-based rather than a raw Step count. Target expectations:

- all P0 rows in `STEP_ECOSYSTEM_MATRIX.md` resolved/certified or explicitly rejected with evidence;
- representative P1 official plugin families usable end-to-end;
- checkout -> build -> test -> report -> artifact pipeline demonstrated locally;
- representative network and container flows demonstrated locally;
- plugin installation/removal does not change core execution semantics;
- Event Harness owns reusable acceptance assertions for certified families;
- external complex reference plugin proves SDK extensibility;
- no hidden dependency on controller/remote worker semantics.

A useful planning range is ~70–100 operations across 12–15 families, but **coverage + certification**, not the number, decides exit.


## Cedar / policy readiness — frozen before LFC-2E2 expansion

Cedar enforcement is listed above (line 345) as deferred, not discarded. To avoid
runtime data reconstruction when the binding eventually opens, the data shape that the
binding will consume is frozen NOW by:

- `docs/v2/02-architecture/PLUGIN_IDENTITY_MODEL.md` — ResourceRef, PluginReleaseRef,
  StepProviderMetadata, multi-family classification, four-level admission model.
- `docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md` — R12 (provider identity),
  R13 (policy readiness gate), R14 (Cedar binding, deferred).
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` — Provider / Plugin ResourceRef /
  Plugin Families / Trust Metadata / Capabilities / Policy Surface / Release Identity
  columns.

### LFC-2E2 precondition (gate C1..C10)

Before any new plugin family (utilities, junit, HTTP, Git, containers, Artifactory)
enters production in LFC-2E2, the policy readiness gate MUST be green:

- C1  ResourceRef(PLUGIN) declared per plugin
- C2  PluginReleaseRef(version, digest) declared per release
- C3  registry exposes `providerOf(STEP_KEY)` in O(1)
- C4  families are a `Set` (multi-family allowed)
- C5  manifest capabilities match StepContract.requiredCapabilities (cross-checked)
- C6  no Cedar runtime dependency in production classpath
- C7  identical admission for OFFICIAL_PLUGIN and EXTERNAL_REFERENCE (modulo duplicate-key)
- C8  PipelineEventEnvelope for Step execution carries ResourceRef(STEP_DEFINITION)
- C9  dedicated fitness suite (Lfc2PolicyReadinessFitnessTest) green
- C10 S2-burned-down core Steps register without provider metadata; their existing
     contract suites continue to pass

The gate is **shape-only**. Cedar is NOT wired up at this point. No policy evaluation
engine is introduced; the runtime behavior of StructuralFamilyResolver,
RegistryExecutionBoundary, and RegistryStepInvoker is unchanged. The gate ensures the
data the future engine will consume already exists.

### Forward plan

```text
S2-A..S2-G        burn down LEGACY_PLUGIN_IDS (12 → 0)
LFC-2E2-prep      adopt StepRegistration(definition, provider) shape + readiness fitness
LFC-2E2           new plugin families born with provider/release/family/policy-surface
LFC-2E3 (future)  Cedar runtime binding; consumes the frozen shape; no runtime
                  data reconstruction needed because C1..C10 were green before LFC-2E2
```
