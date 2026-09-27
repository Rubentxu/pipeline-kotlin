# Specification — Distribution, Release and SDKMAN

Status: PROPOSED

## 1. One artifact authority

The release pipeline produces one canonical universal JVM distribution ZIP from the V2 Gradle `application` distribution. GitHub Releases, SDKMAN and future installers consume that same immutable artifact.

```text
source tag
   ↓
release gate
   ↓
distZip
   ↓
canonical ZIP + SHA-256 + SBOM
   ↓
GitHub Release
   ├── SDKMAN
   ├── future Homebrew/mise/asdf/Scoop
   └── direct download
```

No installer rebuilds the application independently.

## 2. Runtime baseline

First LPR targets Java 21 JVM distribution. Native image/jlink are future optimizations gated by measured startup/footprint needs.

Preferred CLI executable name: `pipelinek`. SDKMAN candidate name is intended to be `pipelinek`, subject to vendor onboarding/name acceptance.

## 3. Gradle distribution

Use the existing `application` plugin and make the distribution explicit:

- `applicationName = "pipelinek"` or equivalent;
- `distZip` is release artifact;
- `installDist` remains HF2/local-real-binary test artifact;
- archive timestamps/order/permissions configured for reproducibility where the repository's Gradle version does not already guarantee it;
- version comes from release/tag authority, not hard-coded `0.1.0-SNAPSHOT` in a production release.

## 4. Release contents

```text
pipelinek-<version>/
├── bin/pipelinek
├── bin/pipelinek.bat   # may exist from Gradle distribution; SDKMAN primary target remains Unix/WSL
├── lib/*.jar
├── LICENSE
└── NOTICE/README-release (optional)
```

Published alongside:

- `pipelinek-<version>.zip`;
- `.sha256`;
- SBOM (CycloneDX/SPDX chosen by implementation);
- release notes;
- optional signature/provenance attestation after initial gate.

## 5. GitHub Actions release gate

Release workflow is separate from current quarantined V1 workflow. It runs against a tag/release candidate and must execute:

- compile/static/architecture fitness;
- focused unit/application tests;
- real installed distribution tests;
- LPR real-project suite;
- observability performance smoke;
- secret canaries;
- archive reproducibility check (build twice, compare digest in controlled lane);
- `distZip` + checksums + SBOM;
- GitHub Release upload.

Branch protection/required checks are configured separately from files in the repo.

## 6. SDKMAN publication

SDKMAN supports vendor publication through its Vendor API and official release automation. The workflow only publishes the already-created GitHub release ZIP, checksum included when supported.

Vendor credentials live only in GitHub secrets/environment protection. Production workflow pins third-party actions to reviewed immutable commits where practical.

Before onboarding is complete, GitHub Release ZIP is the public artifact authority and SDKMAN publishing remains a gated task, not a release blocker for internal dogfooding.

## 7. SDKMAN UAT

After publication:

```text
clean runner
  ↓
SDKMAN CI/noninteractive install
  ↓
sdk install pipelinek <version>
  ↓
pipelinek version
pipelinek doctor
pipelinek validate
pipelinek run real-project
```

The installed ZIP digest must match the GitHub Release artifact expected by the publish job.

## 8. Future distribution adapters

Allowed after LPR and only from canonical ZIP/version metadata:

- Homebrew formula/tap;
- mise/asdf plugin;
- Scoop/Windows-native path if product demand warrants it;
- container image for reproducible runners;
- jlink/native binary if benchmarks justify.

These do not become new build authorities.

## 9. Proposed multichannel distribution addendum (RP-6, post RP-5)

The existing canonical GitHub Release ZIP and SHA-256 are the **only** release bytes. A stable, previously certified, installed `pipelinek` runner orchestrates the tag candidate's CI/release behavior; it is not the same binary as the target distribution under test. GitHub Actions bootstraps and verifies the runner independently of the Kotlin DSL and gates release publication independently of `pipeline.kts`. The target artifact is smoke-tested as a separate executable. No consumer reconstructs the ZIP.

- **GitHub Releases:** tag `vX.Y.Z` is an evaluation trigger, not release authorization. Verify protected/ref SHA, full test/certification tuple, reproducible ZIP/digest/SBOM, then publish. Never change a published tag or rebuild in another channel.
- **mise:** direct GitHub Releases backend with an explicitly matched ZIP asset, Java 21 prerequisite and clean-install UAT. A central mise alias/registry registration is optional and is not implied by GitHub publishing.
- **asdf:** independent plugin repository (installable by URL) that lists versions and verifies/downloads/installs the canonical release ZIP. Central plugin-index acceptance is optional and separate from installer functionality.
- **SDKMAN:** use existing Vendor API scripts after onboarding and fixes; publish → clean official-catalog install UAT → default promotion. Missing vendor credentials/candidate produces BLOCKED_EXTERNAL, not a false PASS and not a block on other channels.

The driver/orchestrator may live in a separate `ci/release.pipeline.kts` and `ci/distribution.pipeline.kts`, both behavior-only Kotlin scripts compatible with the pinned runner. External shell/CLI adapters may handle installer protocols. No new core Step, no YAML stages or Steps, no remote worker/controller work. Secrets are available only to an authorized publish environment after the external gate, never to untrusted PR code. Verify public artifact provenance and channel status outside the runner. See `../05-roadmap/MULTICHANNEL_RELEASE_DOGFOOD_PLAN.md` and `../07-uat/UAT_RELEASE_CHANNELS.md`.
