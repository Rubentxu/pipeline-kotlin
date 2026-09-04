# JReleaser implementation plan

## Why

JReleaser already models CLI distributions, Jlink artifacts, Git releases, checksums and packagers including SDKMAN, Homebrew and asdf. Centralizing release metadata avoids separate handcrafted release logic for each installer.

## Gradle flow

```text
build/test/UAT
  -> assemble platform Jlink images
  -> archive
  -> SBOM
  -> JReleaser config validation
  -> checksum/sign
  -> GitHub release
  -> prepare/package/publish package-manager metadata
  -> SDKMAN stable/default action as appropriate
```

## Artifact naming

```text
pipeline-<version>-linux-x64.zip
pipeline-<version>-linux-arm64.zip
pipeline-<version>-macos-x64.zip
pipeline-<version>-macos-arm64.zip
pipeline-<version>-windows-x64.zip
pipeline-<version>-checksums.txt
pipeline-<version>-sbom.spdx.json
```

## Release reproducibility

- tag determines version;
- clean checkout required;
- no mutable `latest` URL used as checksum source;
- generated Homebrew/asdf metadata points to immutable tagged artifacts;
- release workflow pins action/plugin major versions according to project supply-chain policy.

## SDKMAN detail

JReleaser's SDKMAN packager supports zip artifacts and platform values. SDKMAN itself supports multi-platform candidate publication and checksums. Candidate onboarding/credentials are an external prerequisite.
