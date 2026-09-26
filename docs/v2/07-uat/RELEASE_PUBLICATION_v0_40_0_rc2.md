# Release Publication Receipt: v0.40.0-rc2

**Status:** `PUBLISHED_PRERELEASE_AND_INTEGRATED_ON_MAIN`  
**Release URL:** <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc2>  
**Tag:** `v0.40.0-rc2`  
**Tag target commit:** `165b6f9ad2242ac5336e660e7516f88f1af90d96`  
**Published at:** `2026-09-26T20:28:44Z`  
**Branch uploaded:** `wu/rp-053r-red-fixtures`  
**Main integration commit:** `6c518b53aca2e0c9c2fd99c7c59a9e15d8acffca`  
**Stable release:** no  
**Harness verdict:** pending external intake

## Publication scope

The RC2 candidate was published as a GitHub prerelease. The four assets are the
same bytes produced and verified locally. Following ADR-0099, the complete
candidate history was then fast-forwarded into `main` at
`6c518b53aca2d4d7f25c5a2b2b45de8ec3c890a3`, without squash or history rewrite.
This publication does not claim RP-5 or harness certification.

The GitHub release metadata reports `targetCommitish=main`, which is platform
metadata for the release record. The immutable tag resolves to the candidate
source commit above. `origin/main` remained
`acc903875d70f939713786d71a6331bb6ccf7dc9` before integration and
`6c518b53aca2e0c9c2fd99c7c59a9e15d8acffca` after integration.

## Assets

| Asset | Size | SHA-256 | GitHub state |
|---|---:|---|---|
| `pipelinek-0.40.0-rc2.zip` | 92,082,095 | `45f79479ded7d476e160829a8f2f4582c0a261c2d784f0b7f2642b87dd1dd3d5` | uploaded |
| `pipelinek-0.40.0-rc2.sbom.json` | 17,557 | `0de67693521514cab9c64fe6cb458e20728357ce7ae812c7efa1ca2c246963ac` | uploaded |
| `release-manifest.json` | 3,881 | `51445347ef87b1775de421334406af90a3e106f771e65b78c0da70b93d62423f` | uploaded |
| `SHA256SUMS` | 244 | `13da690761ce4380a7a76ea23ec40d4763309c9b9c6c7c02373cde11910fc40c` | uploaded |

## Independent download verification

```text
argv: gh release view v0.40.0-rc2 --repo Rubentxu/pipeline-kotlin --json ...;
      git rev-parse v0.40.0-rc2^{};
      sha256sum downloaded-assets;
      cmp four assets
exit_code: 0
output_digest: e2fe37ac44aedb2ead393c3cf14bedcca3c4ab531cc9778f5c1ba639dc4f3dbe
```

The downloaded ZIP, SBOM, manifest and `SHA256SUMS` each matched the local
candidate with `cmp`. The release is not draft and is marked prerelease.

## Remaining gates

- External harness intake and certification: `NOT_RUN` here by repository boundary.
- RP-5 product gate: `NOT_RUN`.
- Stable promotion: `BLOCKED_PENDING_HARNESS`.
- Merge to `main`: `PASS` via fast-forward, preserved in `RC2_MAIN_INTEGRATION_RECEIPT_2026_09_26.md`.
- SDDK cycle archive: pending release approval and required release/merge receipts.

## Closure record

- **Reference implementation consulted:** RC1 publication receipt and the repository distribution roadmap.
- **Behaviour adopted:** publish one immutable ZIP and its matching SBOM, manifest and checksum file as a prerelease.
- **Intentional deviations:** no stable release before external certification; the candidate is integrated on `main` before stable promotion as required by ADR-0099.
- **Security implications reviewed:** assets were hash-verified after download; no credentials or mutable source references are part of the candidate material.
- **Tests demonstrating the contract:** local RC2 candidate receipt plus the byte-perfect GitHub download verification above.
