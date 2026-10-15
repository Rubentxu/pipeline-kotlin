# B1 — Candidate handoff gate: partial truth cannot pass R-C3

- **Date:** 2026-10-15
- **HEAD at authorship:** `52b9c4915c8a2403750c62deed4abf5afe2f8323`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/check-candidate-handoff.py` + `scripts/test_check_candidate_handoff.py`.
  Stricter only: every previously-refused handoff stays refused, and no legitimate
  schema-conformant handoff newly fails.

Third receipt of the same session's B1 sweep (see
`B1_HARNESS_EVIDENCE_RELIABILITY_RECEIPT.md` and
`B1_HARNESS_ADMISSION_FAIL_CLOSED_RECEIPT.md`). Same failure class again: a document that
looks complete while attesting less than it claims.

---

## 1. Defects (each reproduced before the fix)

| # | Defect | Consequence |
|---|---|---|
| B1 | R-C3 iterated sections with `if not isinstance(section, dict): continue` | A handoff with **no** `distribution_manifest` section was accepted, although `CandidateHandoff.distributionManifest` is non-null in the schema |
| B2 | `built_at is None → continue` | A manifest section present but carrying no `built_at` (truncated/edited JSON) was accepted silently |

Schema authority, read directly:

- `CandidateHandoff.distributionManifest: CandidateFileRef` — **required** (non-null).
- `CandidateHandoff.sbom: CandidateFileRef?` — **optional** by design; the materializer
  emits it only when an SBOM was produced.
- `CandidateFileRef.builtAt: String` — non-null.

The fix follows the schema asymmetry instead of treating both sections alike.

## 2. Observed RED (before the fix, real CLI)

```text
FAIL: test_missing_distribution_manifest_is_refused
  AssertionError: 0 != 1 : a handoff without distribution_manifest must be refused

FAIL: test_distribution_manifest_without_built_at_is_refused
  AssertionError: 0 != 1 : a manifest without built_at must be refused
```

A third new test, `test_absent_optional_sbom_is_accepted`, passed before and after: it
pins that the fix must NOT make the optional SBOM mandatory (mutation: demand it
unconditionally, refusing a lawful handoff).

## 3. Closure

- `distribution_manifest` absent or non-object → `R-C3` refusal.
- Any present section without a non-empty string `built_at` → `R-C3` refusal.
- `sbom` absent (`None`/missing) remains lawful; present-but-malformed is refused.
- Wrong `built_at` still refuses with the previous wording.

## 4. Observed GREEN (after the fix)

```text
python3 scripts/test_check_candidate_handoff.py
Ran 13 tests in 1.278s
OK
```

## 5. What this does NOT claim

- No real `candidate-handoff.json` exists in this worktree (it is build output), so this
  receipt cites the Kotlin schema and the hermetic CLI tests, not a live promotion run.
- R-C2/R-C4 semantics are unchanged. No gate is relaxed anywhere.

## End-of-work-unit closure

```text
Reference implementation consulted: CandidateHandoff.kt / CandidateFileRef (in-repo
    schema authority) for which sections are required versus optional.
Behaviour adopted:                  section presence follows the schema; an attestation-less
                                    required section is partial truth and is refused.
Intentional deviations:             optionality is implemented per-section rather than by
                                    dropping the check, so the schema asymmetry is explicit.
Security implications reviewed:     none; JSON and local files only, digests compared in
                                    existing R-C4, no network.
Tests demonstrating the contract:    scripts/test_check_candidate_handoff.py
                                    (13 methods, hermetic temp repos + real CLI subprocess).
```
