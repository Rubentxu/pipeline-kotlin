# B4 — Candidate receipt: material identity and product identity, fail-closed

- **Date:** 2026-10-15
- **HEAD at authorship:** `d34718d6` (worktree)
- **Scope:** new `scripts/emit-candidate-receipt.py` + suite, `just candidate-receipt`
  recipe. Operator approved gate work at discretion. No existing release task changed.

---

## 1. What it does

Given a built candidate (`ZIP` + `distribution-manifest.json` + `candidate-handoff.json`,
optionally `--sbom`), it measures and verifies before emitting an immutable receipt:

| Check | Rule |
|---|---|
| M1 | the ZIP exists; its SHA-256 and size are **measured** |
| M2 | `handoff.artifact` and `manifest.asset` describe those bytes (sha256 + size) |
| M3 | `archive_root` agrees across handoff, manifest and the ZIP's single top folder |
| M4 | `implementation_version` agrees across handoff, manifest and the **nested application JAR's** `Implementation-Version` |
| M5 | product identity law as **exact tokens**: `product_version == release_train == manifest.version == embedded version` |
| M6 | `source_commit` is 40-hex, equals the observed `git HEAD`, and equals `--expect-sha` when given |
| M7 | `candidate_id == sha256:<zip digest>` |
| M8 | declared digests of the manifest file and (optionally) the SBOM match the files |

Verdict `PASS | FAIL | BLOCKED`; the receipt JSON carries the measured values and
every check with its detail. Blocked runs print no receipt body.

## 2. Evidence

```text
python3 scripts/test_emit_candidate_receipt.py
Ran 13 tests in 4.309s
OK
```

The suite builds a real ZIP with a real nested application JAR and a real git
repo, and pins, among others:

- a handoff declaring a different digest → `FAIL` (a rewritten handoff cannot
  certify other bytes);
- `product_version=0.61.0-rc2` against `0.61.0` → `FAIL` on the exact-token law
  (the v0.43.0 identity incident);
- embedded JAR version `0.60.0` → the "version present" check passes but the
  consistency check `FAIL`s;
- missing `Implementation-Version` → `FAIL`;
- observed HEAD mismatch and `--expect-sha` mismatch → `FAIL`;
- a touched manifest after the handoff was written → `FAIL`;
- missing/unreadable documents → `BLOCKED` (exit 2, no receipt body);
- an archive without a single top folder → `zip_archive_root == None` (fail
  closed instead of inventing a root).

## 3. What this does NOT claim

- It does not build the candidate, run the RC matrix, or publish anything; it
  verifies the documents the build already produced. `releaseGate` and
  `candidateAdmission` are untouched.
- The runtime identity surface (`pipelinek version` from the extracted binary)
  is not executed here; the embedded JAR manifest covers the in-archive surface.
  Running the installed binary remains the harness's job.
- No candidate was available in this worktree, so no real candidate receipt was
  emitted; the evidence is the hermetic suite (documented honestly).
- SBOM verification is digest-only; the SBOM's contents are covered by the SBOM
  tool's own suite.

## End-of-work-unit closure

```text
Reference implementation consulted: DistributionManifest/CandidateHandoff (in-repo
    schema authority) plus AGENTS.md rules 10-12 (target-version candidate law,
    product identity law, immutable distribution manifest).
Behaviour adopted:                  measure, never trust; exact-token comparison;
                                    BLOCKED when inputs are missing.
Intentional deviations:             the tool is a standalone verifier rather than a
                                    Gradle task, so it can also be run by the external
                                    harness against delivered bytes.
Security implications reviewed:     reads local files; computes digests; no network.
Tests demonstrating the contract:    scripts/test_emit_candidate_receipt.py (13 methods).
```
