---
type: adr
id: ADR-0089
title: "One universal JVM distribution is the release artifact authority"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0082
  - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
---

# ADR-0089 — Distribution artifact authority and SDKMAN

## Context

V2 already uses Gradle's `application` plugin and real tests use `installDist`, but the repository does not have an active V2 release workflow. The near-term goal includes SDKMAN plus future distribution channels.

## Decision

`distZip` from the V2 application distribution becomes the single binary artifact authority for the first LPR releases.

Pipeline:

```text
tag -> release gates -> distZip -> checksum/SBOM -> GitHub Release -> SDKMAN
```

SDKMAN consumes the same immutable GitHub Release ZIP; it does not rebuild it. Future Homebrew/mise/asdf/Scoop/container adapters also consume the same version/artifact metadata.

First supported runtime is Java 21. Native/jlink distributions require measured justification and must not delay LPR-GATE-1.

Preferred executable/candidate name is `pipelinek`, subject to SDKMAN vendor onboarding.

## Security/operations

- vendor credentials in protected CI secrets;
- publication only after GitHub Release artifact is available;
- release actions pinned/reviewed;
- checksum verification in post-publish UAT.
