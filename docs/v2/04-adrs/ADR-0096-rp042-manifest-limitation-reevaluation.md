---
type: adr
id: ADR-0096
title: "WU-RP-042 re-evaluation of UAT-RP-005 inv 3 (MANIFEST.json): KNOWN_LIMITATION confirmed for the 0.39.0 release; release-notes disclosure mandated"
status: accepted
date: 2026-09-22
deciders: "Rubentxu (product owner); WU-RP-042 S2 re-evaluation under AUTO mode"
supersedes: null
superseded_by: null
related:
  - docs/v2/04-adrs/ADR-0095-rp010-manifest-known-limitation.md
  - docs/v2/05-roadmap/ROADMAP.md L50, L74 (WU-RP-042 charter)
  - docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md (UAT-RP-005 invariant 3)
  - docs/v2/07-uat/WU_RP_041_LEGAL_CLOSURE_AUDIT.md (residual R3)
---

# ADR-0096 — WU-RP-042 re-evaluation of MANIFEST.json (UAT-RP-005 inv 3)

## Context

ADR-0095 classified the missing `MANIFEST.json` in `core.publishHTML` archives as
`KNOWN_LIMITATION` with a deferred decision, and mandated:

> "WU-RP-042 (release gate): must re-evaluate this KNOWN_LIMITATION before
> declaring a release. If the format ADR has been written and approved by then,
> implement MANIFEST.json before the release; otherwise the limitation must
> appear in the release notes."

WU-RP-042 S2 performs that mandatory re-evaluation.

## Facts observed at re-evaluation (2026-09-22, HEAD 48cd4de3)

1. The MANIFEST format specification
   (`docs/v2/03-specifications/PUBLISH_HTML_MANIFEST_SCHEMA.md`, proposed by
   ADR-0095's follow-up) does **not exist**.
2. No consumer of `core.publishHTML` archives has expressed a durable-manifest
   requirement since ADR-0095. The event-based channel
   (`HtmlReportPublished`, per-entry sha256) remains the only consumer-facing
   integrity mechanism, and UAT-RP-005 invariants 1, 2 and 4 remain covered.
3. WU-RP-042's charter (ROADMAP L74) freezes the API/schema for the release
   candidate: introducing a new durable archive layout NOW would be exactly
   the unreviewed contract lock-in ADR-0095 rejected, and would change bytes
   of a certified Step after S1 evidence was captured.

## Decision

**Confirm KNOWN_LIMITATION for the 0.39.0 release. Do NOT implement
MANIFEST.json in WU-RP-042.**

Per ADR-0095's own rule (format ADR absent ⇒ limitation must appear in the
release notes), the 0.39.0 release notes MUST carry an explicit
`Known limitations` entry stating:

> `core.publishHTML` archives do not include a durable, externally-parseable
> `MANIFEST.json`. Archive integrity is observable via the `HtmlReportPublished`
> event (per-entry sha256) at run-time, or by computing sha256 over the archive
> contents. See ADR-0095 / ADR-0096.

## Consequences

- UAT-RP-005 remains `PARTIAL (KNOWN_LIMITATION inv 3)` in the UAT matrix for
  this release; RP-2/RP-0 legal closures are not invalidated (the residual was
  classified, not hidden).
- `core.publishHTML` Step bytes and contract remain frozen as certified.
- The deferred WU-RP-010 r2 remains the legitimate implementation path when
  the format spec + ADR are written. Implementation before then is forbidden
  by the contract-freeze rule.

## Authority

- Decider: orchestrator under AUTO mode, applying ADR-0095's deferred-decision
  rule mechanically (no new substantive policy created).
- ADR-0095's follow-up clause is the source of the release-notes mandate.
