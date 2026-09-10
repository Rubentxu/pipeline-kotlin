# Step Ecosystem Policy — core pequeño, ecosistema amplio

Status: PROPOSED / ready for LFC-2 apply after EVT-3.
Authority: complements `STEP_CONSTITUTION.md`, `STEP_PLUGIN_SDK.md`, ADR-0070..0074 and `STEP_PLUGIN_CERTIFICATION.md`.

## Purpose

Maximize the useful local-first Step ecosystem without turning `pipeline-kotlin` core into a monolith.
The engine keeps a small universal core while first-party and vendor/domain capabilities are delivered through
the same external plugin seam already proven by `example.uppercase`.

The product goal is **maximum coverage of real pipeline use cases**, not literal reproduction of every Jenkins
implementation detail.

## R1 — Delivery classes

Every Step/family SHALL be classified as exactly one of:

- `CORE` — universal pipeline semantics that would still exist if Jenkins/vendors disappeared.
- `OFFICIAL_PLUGIN` — first-party plugin maintained with pipeline-kotlin but loaded through the external plugin path.
- `EXTERNAL_REFERENCE` — vendor/domain plugin used both for value and to exercise the public SDK under realistic complexity.
- `DEFERRED_REMOTE` — semantics fundamentally depend on controller/remote worker provisioning and stay out of the local-first freeze.
- `REJECTED_JENKINS_INTERNAL` — Jenkins-internal bridge/API that should not be copied because pipeline-kotlin has a cleaner primitive.

No popularity metric alone can promote a Step into `CORE`.

## R2 — Core admission rule

A Step may enter core only when all are true:

1. the concept is universal to pipeline execution rather than tied to a vendor/tool;
2. its semantics cannot be expressed cleanly through the public Step/Plugin SDK without exposing an engine-internal primitive;
3. the promotion has concrete evidence from at least two consumers/families or one unavoidable execution invariant;
4. promotion does not create a privileged execution path.

Default decision for a new non-universal Step is `OFFICIAL_PLUGIN`.

## R3 — Core target surface

Initial target core families:

- process/basic: `echo`, `sh`, `error`, `sleep`;
- control: `retry`, `timeout`, `catchError`, `warnError`, `unstable`, `parallel`;
- workspace/context: `dir`, `pwd`, `fileExists`, `readFile`, `writeFile`, `deleteDir`, `withEnv`, `withCredentials`;
- engine structural/directive semantics where they are not plugin operations.

`archiveArtifacts` is not automatically core; artifact semantics may move to an official artifacts plugin if the public
SDK supports the required durable/local behavior without special casing.

## R4 — Official plugin families

First-party plugins are external from the engine architecture even when shipped in the default distribution.
Candidate families:

- `pipeline-plugin-scm-git`: `git`, `checkout`, `readScmFile`, advanced Git checkout options;
- `pipeline-plugin-testing`: `junit`, report/test result model;
- `pipeline-plugin-utilities`: JSON/YAML/TOML/properties, hashes, findFiles, zip/tar utilities;
- `pipeline-plugin-artifacts`: archive/stash/unstash/copy local artifacts;
- `pipeline-plugin-http`: typed `httpRequest`;
- `pipeline-plugin-ssh`: ssh-agent/SSH operations where justified;
- `pipeline-plugin-toolchains`: Maven/Gradle/Node/Python/.NET/Go toolchain wrappers;
- `pipeline-plugin-notifications`: mail/email-ext/Slack-compatible surfaces;
- `pipeline-plugin-locks`: local durable `lock` resource coordination;
- `pipeline-plugin-input`: local durable/manual `input` interaction;
- `pipeline-plugin-containers`: Docker/Podman local container build/run/registry operations.

Package names are working names, not frozen API names. Domain boundaries matter more than Jenkins plugin packaging.

## R5 — External reference plugins

Vendor-specific surfaces SHALL remain external reference plugins unless evidence proves a universal abstraction.
Priority reference families:

- Artifactory/Xray: upload, download, build-info, promote, scan;
- SonarQube: scanner environment + quality gate interaction;
- GitHub/GitLab provider operations where generic SCM/HTTP is insufficient;
- AWS/Azure/GCP integrations;
- Vault/secret-provider contributed bindings.

These plugins are valuable SDK stress tests: network + credentials + artifacts + structured results + vendor failure taxonomy.

## R6 — Deferred remote surfaces

Do not pull remote/controller semantics into the local-first expansion:

- Kubernetes `podTemplate` / dynamic worker provisioning;
- remote `node(label)` scheduling;
- Jenkins `build(job:)` / `waitForBuild` controller job semantics;
- Jenkins `properties` job mutation;
- controller-only UI/status APIs.

A useful local analogue may be implemented only if it has independent local value and does not fake remote semantics.

## R7 — Jenkins internals explicitly rejected

Do not reproduce Jenkins implementation bridges whose purpose is to expose its Java extension model:

- `step($class: ...)`;
- `wrap($class: ...)` as a generic Jenkins bridge;
- `getContext` / `withContext` internal APIs.

Use typed `StepDefinition`, capabilities, context parameters and plugin registration instead.

## R8 — Same execution and certification law

Core, official and external plugins MUST share:

`typed façade -> canonical invocation -> StepRegistry -> typed codec/handler -> capability admission -> durable engine -> typed result/events`

There is no official-plugin shortcut.

Every Step remains:

`DESIGNED -> IMPLEMENTED_UNCERTIFIED -> CERTIFIED`

and certification uses the common Step/Plugin Contract Suite plus a real executable scenario.

After EVT-3, each certified family SHALL also own an Event Harness contract for its user-visible lifecycle/outcome where observable.

## R9 — SDK pressure-test law

A non-universal family MUST be attempted through the public plugin seam first.

If implementing `junit`, utilities, HTTP, Git, containers or a vendor reference plugin requires a change in
`CanonicalDurableRunCoordinator`, a central per-Step dispatcher case, or another privileged core execution branch:

1. stop the family slice;
2. document the concrete missing SDK capability;
3. improve the generic seam with a minimal, capability-oriented change;
4. prove the improvement with the plugin and at least one regression/reference plugin;
5. never add a plugin-name-specific exception to core.

Plugin development is therefore continuous architecture testing of the SDK.

## R10 — Native Kotlin surfaces may improve on Jenkins

Jenkins-compatible façades MAY coexist with better typed native APIs provided both compile to the same Step contract.
Examples:

- Jenkins-compatible `readJSON(...)` plus typed `StructuredData/JsonValue` result;
- `git(...)` / `checkout(...)` plus a native `scm.checkout(...)` façade;
- Jenkins-style Docker compatibility plus a provider-neutral `container.*` API.

Compatibility is a surface concern; durable semantics and plugin wire contracts remain authoritative.

## R11 — Local-first ecosystem exit

Before EVT-4/M4 remote work becomes the product priority, target:

- core universal families certified;
- high-value official plugin families usable in real local pipelines;
- at least one non-trivial external reference plugin certified with zero core Step-specific edits;
- real examples covering checkout -> build -> test -> artifacts plus representative network/container/toolchain flows;
- Event Harness contracts for certified user-visible behavior;
- no remote/controller semantic dependency in the local runtime.

The numeric Step count is informative, not an exit criterion. Coverage of real use cases and certification evidence are the gate.
