# B4 — Local candidate built, admitted and verified (real bytes)

- **Date:** 2026-10-15
- **Authoring HEAD:** `e79fbff772561561520f5a866a2e3c7d258ca78a` (clean tree)
- **Worktree:** `worktree/test-harness-brave-harbor-6920`
- **Companion:** `B4_CANDIDATE_RECEIPT_RECEIPT.md` (the tool; that receipt recorded
  "no candidate exists in this worktree", which this document supersedes with a real run).

---

## 1. What was executed

```text
$ cd v2 && ./gradlew :pipeline-application:distZip :pipeline-application:cyclonedxBom
BUILD SUCCESSFUL in 31s          (20 executed, 70 up-to-date)
  -> pipeline-application/build/distributions/pipelinek-0.61.0.zip (92 985 677 bytes)
  -> pipeline-application/build/reports/bom.json (1 386 466 bytes)

$ cd v2 && ./gradlew :pipeline-release:candidateAdmission -Pcandidate.tag=v0.61.0-rc-local
BUILD SUCCESSFUL in 10s          (exit 0; "The candidate is immutable.")
  -> pipeline-release/build/candidate/{distribution-manifest.json,candidate-handoff.json}

$ python3 scripts/emit-candidate-receipt.py \
      --zip …/pipelinek-0.61.0.zip \
      --manifest …/distribution-manifest.json \
      --handoff  …/candidate-handoff.json \
      --sbom     …/pipelinek-0.61.0.sbom.json \
      --expect-sha "$(git rev-parse HEAD)"
CANDIDATE RECEIPT  verdict=PASS           (exit 0, 16/16 checks PASS)
```

## 2. Measured identity (from `candidate-receipt.json`)

| Fact | Value |
|---|---|
| ZIP sha256 | `1b1a2c01715c92c5125e727f311c63b77f2fd6d29c05ecef99178660766860b7` |
| ZIP size | 92 985 677 bytes |
| archive root | `pipelinek-0.61.0` (handoff = manifest = archive) |
| embedded `Implementation-Version` | `0.61.0` (from `pipeline-application-0.61.0.jar`) |
| product identity law | `product_version == release_train == manifest.version == embedded` = `0.61.0` (exact tokens) |
| `source_commit` | `e79fbff772561561520f5a866a2e3c7d258ca78a` = observed HEAD = `--expect-sha` |
| `candidate_id` | `sha256:1b1a2c01…60b7` (matches the measured ZIP) |
| SBOM digest | `50d02584…af707`, declared identically by handoff and manifest |
| distribution manifest digest | `cbd3090c…01b42`, matches the declared value |

Committed evidence bundle: `docs/v2/07-uat/evidence/candidate-e79fbff7/`
(receipt, handoff, manifest, SBOM, `zip.sha256`, README). The 92 MB ZIP is not
committed; its digest is bound.

## 3. What this does NOT claim

- **Not a release.** No tag, no GitHub Release, no handoff to the external
  `pipelinek-release-harness`; that delivery is an operator action and the harness
  owns certification. This candidate's bytes are local to the worktree.
- `candidate.tag=v0.61.0-rc-local` is a build label, not a published ref.
- The full RC matrix (application/architecture/release suites, installed UATs,
  durability, compatibility, SBOM consumers) was not run here; the round-gate
  measurement in this session is recorded separately.
- The runtime identity surface (`pipelinek version` from the extracted binary)
  was not executed; the embedded JAR manifest is what was verified.

## End-of-work-unit closure

```text
Reference implementation consulted: DistributionManifest / CandidateHandoff /
    CandidateAdmission (in-repo; the admission task produced the documents this
    receipt verifies).
Behaviour adopted:                  build -> admit -> measure -> bind; verdicts come
                                    from measured bytes.
Intentional deviations:             evidence bundle keeps small documents and the ZIP
                                    digest rather than committing 92 MB.
Security implications reviewed:     local-only; no network; digests computed locally.
Tests demonstrating the contract:    scripts/test_emit_candidate_receipt.py (13 methods)
                                    + the real run above (16/16 checks PASS).
```
