# ADR-EVO-008 — v2 identity is derived from actual semantic compiler inputs

**Status:** Proposed, refined 2026-10-06.

## Decision

Use the canonical scripting specification and one effective compiler profile/frozen dependency plan. v2 identity includes exact source/lowering digests, logical template/source identity, ordered content digests including transitives/façades, actual compiler/JDK/API/runtime fingerprints and all compilation-affecting options/imports/properties. Keep existing replay artifact identity coherent.

Use versioned canonical structured encoding with unambiguous boundaries. Paths/mtime are location facts, not artifact identity; genuinely semantic source locations remain explicit inputs. Sort unordered catalogue sets, never reorder effective compiler precedence merely for deterministic hashing.

## Consequences

Same-path mutation, option/JDK/ABI changes and order changes invalidate reuse. Install-root relocation remains portable with correct current source mapping. No manual version bump stands in for option fingerprints. v1 historical decoding stays versioned; no v1-to-v2 reinterpretation. Certify identity and measured value before conditional cache/session GO (ADR-EVO-011..014).

## Acceptance

Spec 09, UAT-050..053, AAT-014/015/023. No key, artifact or feature is marked implemented by this proposal.
