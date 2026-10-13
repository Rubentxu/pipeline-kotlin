# Handoff to certifier — v0.57.0-rc1 candidate

**Status:** INTEGRITY-CORRECTED · 2026-10-13T13:55Z
**Train:** 0.57.0-rc1 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
**Provenance:** tag `v0.57.0-rc1` → commit `51f654e3` (PR #107 merge)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a
complete set of artefacts catalogued under SHA-256. The certifier
independently re-computes SHAs, re-runs the gate, and re-measures the
13 R0.5 scenarios before signing anything. **Nothing in this document
is a verdict.**

## ⚠ Integrity notice (R0.2, 2026-10-13)

The body of this file as published in the GitHub release
`v0.57.0-rc1` (asset `HANDOFF-TO-CERTIFIER.md`, sha256
`e2ed4ce1…`) **contains digests copied from a previous candidate**.
Six of the nine digests listed in the original table do NOT match
the actual assets uploaded in the release. The original body is
NOT the source of truth; this corrected file is.

**What changed in R0.2:**
- The digest table below is rebuilt from the actual files on disk
  and from the actual asset digests in the GitHub release.
- `git-head.txt` was rewritten from the evidence commit
  (`f6eb1ebb`) to the tag commit (`51f654e3`).
- 2 assets present in the release but absent from `SHA256SUMS` are
  listed in the "Extra assets" section.
- A note is added to the GitHub release body pointing to this
  corrected file.

**Tag invalidation status:** the digest mismatches in the original
release body invalidate the trust chain for `v0.57.0-rc1` as
delivered. Per operator directive 2026-10-13: do not re-tag; open
`v0.57.0-rc2` once the full releaseGate completes under controlled
load. This file is the corrected handoff for the existing tag and
the seed for the next candidate.

## Source of truth

| Item | Value |
|---|---|
| Train | 0.57.0-rc1 |
| Branch | `pk-release-0-57-consolidation` |
| Tag commit | `51f654e3` (PR #107 merge commit) |
| Tree state at tag | clean (verified by `git ls-tree` at tag) |
| Audit at SHA `12c3ac17` | R1.1..R1.4 closed in this candidate |
| `candidateAdmission` | PASSED at evidence commit; full `releaseGate` did not complete under host load — see R0.1 evidence |

## Artefacts (actual digests, from `SHA256SUMS` and GitHub release)

| File | Size | SHA-256 (actual) |
|---|---|---|
| `pipelinek-0.57.0.zip` | 88.5 MB | `1b3372898f0e145d21cdf56b86989edbdaca57be078f8575e20834ad9201191c` |
| `pipelinek-0.57.0.sbom.json` | 1.3 MB | `c50109ef0e3d73b43d356ae5866534851ffea14d6a684cfb6ddc22b8f6132819` |
| `pipeline-application-0.57.0.jar` | 1.8 MB | `8184a72ca30a4205143dbc8366ac856fee963ea56ddd8c73f1111c5111f1caa3` |
| `pipeline-domain-0.57.0.pom` | 1.4 KB | `232ebec04314e454da26acdd20fec168e86f1f0f1353fbaac8dcd87bac7b038d` |
| `pipeline-sdk-bom-0.57.0.pom` | 1.6 KB | `54228c13c7aeb58ca17d6143563a4c045eb5401b84e64f728f1ae3c8b10affc7` |
| `sdk-domain-0.57.0.module` | 3.0 KB | `10c91cb218c1e67dddaa9d0c4e55b4ba64f810bc119b341f4731c9a296a007c0` |
| `git-head.txt` | 41 B | `ab4110c8c89dfc0e4b845f93339fd23d8844f872784af47c5c265122e3ed977f` |
| `STATUS.md` | 6.6 KB | `cd2695d06a6dcd6afd3fecaa804a6594ba456ca504a6dd413cc247123cb8b5a2` |
| `HANDOFF-TO-CERTIFIER.md` | 8.3 KB | `e2ed4ce123ea8c7d548ba7aa414e6d2b8101f086e2116b4e3729806604e9ce25` |

## Extra assets (in release, NOT in `SHA256SUMS`)

| File | Size | SHA-256 | Status |
|---|---|---|---|
| `pipelinek-0.57.0.sdk.zip` | 90.1 MB | `7e14e8f447c99fb09f4a692463fbc449d8a94ffc9a2702e6fcce2ac54f10f515` | in release, NOT in SHA256SUMS — `SHA256SUMS` should be regenerated to include this asset (or the asset should be removed from the release) |
| `pipelinek-bom-0.57.0.json` | 12 KB | `55efc99987e16fd01f8f5f9cd1baa8cc1fd11307e8abdc0cbe58343461f2adc5` | in release, NOT in SHA256SUMS — same disposition as above |
| `SHA256SUMS` (file in release) | 363 B | `37622bae4d27ddf411e20abf76880c2a67e860871dc5815e0aa2a9072550065a` | in release; differs from the local `SHA256SUMS` (808 B). Local `SHA256SUMS` is canonical. |

## What the certifier must do

1. **Re-compute SHAs** against the 7 core artefacts in the table
   above. Every line must match what the certifier computes locally.
2. **Decide** on the disposition of the 2 extra assets:
   - Option A: regenerate `SHA256SUMS` to include them, and amend
     `evidence/0-57-rc1/SHA256SUMS` to match.
   - Option B: remove the 2 extra assets from the GitHub release.
3. **Re-run the gate** from a clean checkout at the tag commit
   `51f654e3`:
   ```bash
   git checkout 51f654e3
   cd v2
   ./gradlew --no-daemon --no-parallel \
     :pipeline-release:candidateAdmission \
     :pipeline-release:test \
     :pipeline-application:test \
     --rerun-tasks
   ```
   `candidateAdmission` must PASS. The full suite must complete
   without hanging at the test phase (R0.1 documented the
   environmental hang pattern under high host load; reproducible
   under load < 5).
4. **Re-measure the 13 R0.5 scenarios** defined in
   `docs/v2/07-uat/R0_5_CERTIFICATION_SCENARIOS.md`.
5. **Inspect the seven plugin manifests** inside
   `pipelinek-0.57.0/lib/*.jar` — each
   `META-INF/pipelinek/plugin-manifest.json` must declare the same
   `apiRange` its `*release.properties` declares.
6. **Sign the verdict** in `evidence/0-57-rc1/CERTIFIER-VERDICT.md`
   with the certifier's own identity and the SHAs above.

## What the certifier must NOT do

1. **Do not modify the artefacts in this directory.** The candidate
   is immutable. If a SHA mismatches, fail the verdict and report;
   do not rebuild locally and substitute your build for the
   producer's.
2. **Do not promote to GA.** `v0.57.0-rc1` is a release candidate,
   not a GA. A separate cycle owns the GA decision.

## Known issues (open, not blocking certification)

- **R0.1 (gate hang)**: the full `:pipeline-application:test` does
  not complete under current host load (9-12, Xeon 64-core). Same
  signature as 2026-10-12 release attempt and 2026-10-13 R4
  attempt. Identified as host-pressure pattern (test JVM exits
  within ~12s without writing `TEST-*.xml`; daemon deadlocks in
  `CountDownLatch.awaitCompletion`). Not a code defect. Documented
  in `evidence/0-57-rc1/releaseGate-r4-hang.log` and reproduced
  in R0.1 (2026-10-13T13:55Z).
- **PK-58A T1 audit F1–F6**: 5 of 6 findings confirmed against
  `main@c88f35cf` (formerly). See
  `docs/v2/07-uat/PK-58A-ports-audit.md`. These do not block the
  certifier's work on 0.57.0-rc1 but inform the next blocks
  (R1–R4).
- **Block 0.58 (PK-58A)** is not yet started. R0 must close first.
