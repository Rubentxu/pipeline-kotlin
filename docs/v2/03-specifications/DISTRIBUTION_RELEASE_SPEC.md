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
