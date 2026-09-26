---
type: adr
id: ADR-0099
title: "Main is the source of truth for release candidates and releases"
status: accepted
date: 2026-09-26
deciders: "Rubentxu (product owner)"
supersedes:
  - ADR-0091
superseded_by: null
related:
  - ADR-0082
  - AGENTS.md
  - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
---

# ADR-0099: Main authority for release candidates and releases

## Context

The repository previously described release candidates as branch-only handoffs
to the external harness, with later promotion to `main`. That model conflicts
with the product operating model: `main` is the source of truth, every
evolution branch starts from `main`, and release candidates and stable releases
must be represented by the complete history already present on `main`.

The external `pipelinek-release-harness` remains a separate certification
system. Its result controls certification and stable-promotion eligibility. It
does not own the Git history of this repository and it does not replace `main`
as the product source of truth.

## Decision

1. `main` is the authoritative integration and release line.
2. Every evolution branch MUST be created from the current `main`.
3. A release candidate MUST be integrated into `main` before its GitHub
   prerelease is considered complete. Integration preserves the complete
   commit history. Squashing, rebasing published candidate history, and force
   pushing are forbidden.
4. The immutable candidate tag MUST resolve to a commit reachable from `main`.
   The ZIP, SBOM, manifest and checksum files published for that tag remain the
   exact bytes built and verified for the candidate.
5. Stable promotion remains gated by the external harness and the applicable
   product-readiness gates. A harness failure blocks stable promotion, but does
   not remove an already integrated release candidate from `main`.
6. This repository owns candidate construction, local verification, Git
   integration, tag/release publication and the corresponding receipts. The
   external harness owns its own real-project certification evidence and
   verdicts.

## Consequences

- A candidate branch is an integration vehicle, not a permanent release
  authority.
- Candidate publication is not complete while its tagged commit is absent from
  `main`.
- Historical receipts remain immutable evidence of the policy and state that
  existed at their recorded SHA. A later receipt or erratum records policy
  changes and corrective integration; historical facts are not rewritten.
- The SDDK release workflow must be treated as stack-agnostic. A Rust/Cargo
  precondition that does not describe this Kotlin/Gradle repository is an
  external tooling limitation and must not create a false manifest in this
  project.
