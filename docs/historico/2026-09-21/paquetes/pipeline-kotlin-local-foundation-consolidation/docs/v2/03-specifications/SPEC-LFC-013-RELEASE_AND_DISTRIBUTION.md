# SPEC-LFC-013 — Release and distribution

**Status:** proposed

## Primary artifact

Ship platform-specific **Jlink distributions** containing the required Java runtime. Users installing through supported package managers should not need to provision a compatible JDK just to run `pipeline`.

Initial target matrix:

- Linux x86_64;
- Linux arm64;
- macOS x86_64 while supported by upstream dependencies;
- macOS arm64;
- Windows x86_64 when the runtime/CLI behavior reaches platform parity.

## Release orchestrator

Use JReleaser from the Gradle build/release workflow for:

- checksums;
- signing/provenance integration;
- GitHub Release publication;
- Homebrew package generation/publication;
- SDKMAN publication;
- asdf package generation;
- additional packagers later without rewriting release logic.

## mise

Prefer publishing artifacts in a form consumable by mise's Aqua backend / registry or GitHub release backend rather than maintaining a bespoke mise plugin unless a demonstrated missing feature requires one.

## Supply chain

Every release must include:

- SHA-256 checksums;
- SBOM (SPDX or CycloneDX);
- provenance/attestation where release infrastructure supports it;
- signatures for release metadata/artifacts according to the accepted signing ADR;
- immutable tagged version.
