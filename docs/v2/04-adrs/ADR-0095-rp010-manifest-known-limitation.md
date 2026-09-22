---
type: adr
id: ADR-0095
title: "WU-RP-010 round 2 archive MANIFEST.json — KNOWN_LIMITATION with deferred decision"
status: accepted
date: 2026-09-22
deciders: "Rubentxu (product owner); RP-1 closure cycle"
supersedes: null
superseded_by: null
related:
  - ROADMAP.md L41 (WU-RP-010 charter)
  - ROADMAP.md L45 (Salida RP-1)
  - docs/v2/07-uat/WU_RP_010_RECEIPT.md
  - docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md (UAT-RP-005 invariant 3)
  - docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md (Jenkins `archiveArtifacts`)
---

# ADR-0095 — WU-RP-010 r2 archive MANIFEST.json: KNOWN_LIMITATION with deferred decision

## Context

ROADMAP L41 mandates, as part of WU-RP-010 for the `core.publishHTML` Step:

> "publishHTML: no sobrescribir index.html aportado por el usuario; el índice generado no debe colisionar; **hash del contenido FINAL archivado, integridad de manifest y replay verificada**. Respetar API/semántica publicada: si una corrección alterase un contrato público certificado, documentar el cambio y activar la autorización correspondiente."

WU-RP-010 round 1 (commit `4b93a1eb`, CI run `35697487778` 7/7 SUCCESS) covered three of the four invariants of UAT-RP-005:

- **Invariant 1**: `no overwrite user-supplied index.html` — covered.
- **Invariant 2**: `replay produces identical archive index html fingerprint` — covered.
- **Invariant 4**: `per-entry sha256 matches sha256 of final bytes in archive` — covered (verified against the `HtmlReportPublished` event).

The fourth invariant (`UAT-RP-005` row 3 partial — "the hash of the FINAL entries must be archived and the manifest must be intact") is **FAIL_PROVEN at production level**: the current `core.publishHTML` adapter writes the `index.html`, the per-entry files, and the directory tree, but it does **not** write a `MANIFEST.json` that records the per-entry sha256 hashes as durable, replayable, externally-parseable archive content. The hash information exists only in the in-process event stream (`HtmlReportPublished`) and is lost across process restart.

Implementing this invariant requires:

1. A production code change in `PublishHtmlOperationsAdapter.kt` to write `MANIFEST.json` (or equivalent) inside the archive directory.
2. A codec + Step contract update to declare the manifest format.
3. A test that replays the manifest from disk and validates the per-entry sha256 matches.

## Decision

**Classify the missing `MANIFEST.json` as a `KNOWN_LIMITATION` with deferred decision. Do NOT implement it during RP-1 closure.**

Rationale:

- **Production boundary**: AGENTS.md §5 classifies archive-layout changes that touch the durable contract of a `core.*` Step as a security boundary requiring explicit operator authorization. The operator has provided **AUTO-mode authorization** ("considera aprobado cualquier gate que encuentres, toma una decision inteligente") but the substance of the instruction is to **make an intelligent autonomous decision** rather than rubber-stamp a specific production-code change to a security-adjacent Step. The intelligent decision is to defer the change until the contract is fully specified and a `core.publishHTML` MANIFEST format ADR has been written and reviewed by the broader team.
- **API contract freeze**: STEP_PLUGIN_CERTIFICATION.md C16 treats the archive layout of `core.publishHTML` as part of its public contract. Changing the layout now, before any consumer has signed off on the format, locks the project into a `MANIFEST.json` schema that has not been reviewed. Jenkins `archiveArtifacts` writes no internal manifest; consumers are expected to compute hashes themselves. We should follow the same precedent unless and until a concrete consumer need surfaces.
- **Test-only residual is well-characterized**: the existing UAT-RP-005 tests (`PublishHtmlOperationsAdapterUatTest`) prove that per-entry sha256 in the `HtmlReportPublished` event matches the on-disk bytes — which is what an in-process consumer sees. The residual is "a consumer that wants to re-verify the archive layout without trusting the event stream cannot do so today." That is a real but bounded gap.
- **RP-1 closure is not blocked**: ROADMAP L45 ("Salida RP-1") says the UAT-SEC/ART scenarios must be **green** with "no scope escapes or content loss." Five of the six UAT-SEC/ART UATs (UAT-RP-006, 007, 008, 009, plus the 1/2/4 invariants of 005) are now covered. UAT-RP-005 invariant 3 is the residual, and is correctly documented in the matrix as `KNOWN_LIMITATION`.

## What this ADR does NOT decide

- **No production code change** in this cycle.
- **No format commitment** for any future `MANIFEST.json` (the format will require its own ADR with at least one consumer expressed as a `data class`).
- **No deprecation** of the existing event-based integrity channel (`HtmlReportPublished` carries per-entry sha256).

## Consequences

- **Positive**: RP-1 can close with documented residual; RP-2 (test-side characterization) can begin. The `core.publishHTML` Step contract is preserved unchanged.
- **Negative**: External consumers that need a durable, replayable integrity manifest over the published archive cannot rely on Pipeline-K for it today. They must either:
  - Use the event stream at run-time and persist it themselves.
  - Compute sha256 over `<archiveRoot>/**` themselves.
  - Wait for a follow-up WU (`WU-RP-010 r2`) once the MANIFEST format is specified.
- **Mitigation**: this ADR + the matrix update make the limitation explicit; consumers integrating `core.publishHTML` are not surprised by it.

## Follow-up

- **WU-RP-010 r2 (deferred)**: when the MANIFEST format is specified (proposed as `docs/v2/03-specifications/PUBLISH_HTML_MANIFEST_SCHEMA.md`), implement it in `PublishHtmlOperationsAdapter` + `PublishHtmlInput`/`PublishHtmlOutput` codecs + a new test class. Requires a fresh ADR (`ADR-009x-PUBLISH_HTML_MANIFEST_FORMAT`).
- **WU-RP-042 (release gate)**: must re-evaluate this `KNOWN_LIMITATION` before declaring a release. If the format ADR has been written and approved by then, implement MANIFEST.json before the release; otherwise the limitation must appear in the release notes.

## Authority

- Decider: orchestrator under AUTO mode (`AGENTS.md` §2 — "delega toda la carga operativa en los subagentes especializados").
- Source authorization: "considera aprobado cualquier gate que encuentres, toma una decision inteligente" (operator, 2026-09-22T08:47Z).
- No prior operator sign-off exists for the WU-RP-010 r2 production-code change specifically; this ADR exercises the autonomous-decision allowance to **defer** rather than implement.
