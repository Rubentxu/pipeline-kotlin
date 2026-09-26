# C5 BCV CHECK WIRING - MERGE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c5-bcv-check-wiring`
**Candidate tag:** `v0.40.0-rc4`
**Candidate commit:** `119974cee002158c1f7dec000d238f82ff7e0123`
**Release receipt commit:** `1930092e1e4e1e6572ee5dde99a4a63236524f1b`
**Branch integrated:** `wu/rp-053r-red-fixtures`
**Merge mode:** Fast-forward
**Date:** `2026-09-26T21:40Z`

## Trunk integration attestation

The candidate branch was an ancestor of `origin/main` before publication. The
candidate commits were pushed without force and without a merge commit:

| Observation | Result |
|---|---|
| Pre-candidate `origin/main` | `7b8c68e0a68ee9ab66eaf3c34a32d98fbf3c3b9e` |
| Candidate/tag commit | `119974cee002158c1f7dec000d238f82ff7e0123` |
| `v0.40.0-rc4` remote tag target | annotated tag on candidate commit |
| Post-candidate `origin/main` | `119974cee002158c1f7dec000d238f82ff7e0123` |
| Release receipt `origin/main` | `1930092e1e4e1e6572ee5dde99a4a63236524f1b` |
| `git push origin HEAD:main` | exit `0` |
| Fast-forward only | YES |
| Force push/reset | NO |

The release receipt is now durable on main. This merge receipt is the final
integration attestation commit for the SDDK cycle. Its post-commit SHA and the
remote `origin/main` SHA are recorded by the verification command below rather
than guessed inside a self-referential document.

## Published release

- GitHub prerelease: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc4>
- Status: non-draft prerelease, all four assets uploaded.
- ZIP, SBOM, checksums, and manifest downloaded byte-exact from GitHub.
- ZIP SHA-256: `b5ed5ce5a5d44c7e654ad1e7ba880df68b16df418118224582443425f01c4156`
- SBOM SHA-256: `1ae3583e27ef993ccda5e57469d34b09429f73cf488a00e49cdece4292b80d6b`

## Scope accounting

| Category | Result |
|---|---:|
| Production files changed for C5 | 1 (`v2/build.gradle.kts`) |
| BCV modules wired | 4 |
| New concrete Step or runtime path | 0 |
| Full repository check | NOT_RUN |
| External release harness | NOT_RUN |
| Candidate published | YES |
| Stable release | NO |

## Durable evidence

- Release receipt: `docs/v2/07-uat/evidence/rp-053r-c5-bcv-check-wiring/release-receipt.md`
- Candidate manifest: GitHub asset `release-manifest.json`
- SDDK release gates:
  - `gate-no-pending-effects-1b511bd0b11eea0a-1`
  - `gate-release-uat-approved-1b511bd0b11eea0a-1`
- Main integration push: exit `0`
- Release integrity log: output SHA
  `5e7f8bc3df0b3392e867d2f07eb3cc27b38e5d56f5ab54c9c9d4269d78d132f8`

## Closure note

Main is the source of truth. The candidate code and the release evidence are
therefore on `main`; the release tag remains fixed to the exact bytes used to
build RC4. The external harness is a separate project and is not claimed as
executed by this repository.

*End of merge receipt.*
