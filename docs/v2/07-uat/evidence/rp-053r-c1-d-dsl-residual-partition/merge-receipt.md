# C1-D RC5 main integration receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c1-d-dsl-residual-partition`
**Candidate tag:** `v0.40.0-rc5`
**Candidate commit:** `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4`
**Branch integrated:** `wu/rp-053r-red-fixtures`
**Integration mode:** fast-forward to `main`
**Date:** 2026-09-26

## Trunk integration attestation

The candidate was pushed without force or reset:

| Observation | Result |
|---|---|
| Pre-candidate `origin/main` | `9e26cc65902fdb0b4e381cc36e4f6ca4acd1762f` |
| Candidate/tag commit | `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4` |
| `v0.40.0-rc5` remote tag target | `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4` |
| `git push origin HEAD:main` | exit 0 |
| Force push/reset | NO |

The release and merge receipts are being committed after the candidate tag. Their final post-commit SHA and remote `origin/main` SHA will be captured by the closing verification command rather than guessed inside this self-referential document.

## Published material

- GitHub prerelease: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc5>
- Status: non-draft prerelease.
- ZIP SHA-256: `69a2421cb5e785e8fa454391df992c530dffeb0dd04468389deefcd8e5222df2`
- SBOM SHA-256: `d4f73c779b1cb054ea1dabec0f7973ffc8bd62ec5dacc9bab92077025b50c487`
- `SHA256SUMS` SHA-256: `e5daa326847c73ec04f1cee5a92ea800efcbe6b9567c1eeb11a02c2b345f64ea`
- Assets downloaded from GitHub and compared byte-for-byte: PASS.

## Scope accounting

| Category | Result |
|---|---:|
| Production source partition | `PipelineDsl.kt` + `PipelineDslSteps.kt` |
| Architecture fitness adaptation | 1 source-set path assumption corrected |
| Full repository check | NOT_RUN |
| External release harness | NOT_RUN |
| Candidate published | YES |
| Stable release | NO |

## Durable evidence

- C1-D implementation receipt: `docs/v2/06-quality/C1_D_STEP_SPEC_PARTITION_RECEIPT_2026_09_26.md`
- C1-D verification report: `docs/v2/06-quality/C1_D_VERIFICATION_REPORT_2026_09_26.md`
- Candidate manifest: `dist/candidates/v0.40.0-rc5/release-manifest.json` local ignored material and GitHub asset.
- Release receipt: `release-receipt.md` in this directory.
- SDDK gates: `gate-no-pending-effects-8d9d130eb9c9b444-1` and `gate-release-uat-approved-8d9d130eb9c9b444-1`.

## Closure note

`main` is the source of truth. The candidate code, release evidence, and integration receipt are committed there. The tag remains fixed to the exact bytes used to build RC5. The external harness remains a separate project and is not claimed as executed by this repository.

*End of merge receipt.*
