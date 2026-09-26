# C5 BCV CHECK WIRING - RELEASE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c5-bcv-check-wiring`
**Candidate:** `pipelinek 0.40.0-rc4`
**Release tag:** `v0.40.0-rc4`
**Candidate commit:** `119974cee002158c1f7dec000d238f82ff7e0123`
**Trunk at publication:** `origin/main = 119974cee002158c1f7dec000d238f82ff7e0123`
**Date:** `2026-09-26T21:38Z`
**Path:** A-lite

## Scope

This candidate publishes C5 Phase 2: the existing Binary Compatibility Validator
API baselines for `pipeline-domain`, `pipeline-events`, `pipeline-step-sdk:api`,
and `pipeline-credentials-api` are now wired into each module's `check` task.
The allowlist remains explicit and no non-BCV module receives an `apiCheck`
dependency.

## Release material identity

| Artifact | Size | SHA-256 |
|---|---:|---|
| `pipelinek-0.40.0-rc4.zip` | 92,082,053 | `b5ed5ce5a5d44c7e654ad1e7ba880df68b16df418118224582443425f01c4156` |
| `pipelinek-0.40.0-rc4.sbom.json` | 17,557 | `1ae3583e27ef993ccda5e57469d34b09429f73cf488a00e49cdece4292b80d6b` |
| `SHA256SUMS` | 188 | `0866a9ae9783630612ad08c9e96399fafb3edcc74204781d82025a3fd8cccd06` |
| `release-manifest.json` | 2,949 | `25600a3c40bf8ca570701b68b0183b0287d5b98695cdcd6ad9389af422ea15c6` |

The ZIP passed `unzip -t` and `sha256sum --strict -c SHA256SUMS`. The same
four downloaded GitHub assets were byte-exact against the local candidate.

## Verification evidence

| Check | Command / evidence | Result |
|---|---|---|
| BCV API checks | `timeout 600 bash -c 'cd v2 && ./gradlew :pipeline-domain:apiCheck :pipeline-events:apiCheck :pipeline-step-sdk:api:apiCheck :pipeline-credentials-api:apiCheck --console=plain'` | PASS, log SHA `fe96659103d51a777f5ef8e62de5b7ff67b37897a6f20dad91ba046ea9a61b3b` |
| BCV check wiring | Four-module `check --dry-run` | PASS, log SHA `d2ebc40cd23f011a0c9773fbdd310f33c156b5bcd8fdd5c5c18ee11d2a90dfb8` |
| Non-BCV exclusion | `:pipeline-scripting-api:check --dry-run` | PASS, no `apiCheck`, log SHA `afcbe39da5317b4f46a287f256d9071d8124e1e841b82f97e33203b9b6746b4b` |
| Admission | `timeout 30 python3 scripts/admission-check.py` | PASS, 6/6, output SHA `3a02a32ef843ec513733391d1fb236bbdba4c76a75db9cf2f6622d6d9e2f82c4` |
| Distribution build | Gradle distribution, Detekt, application Detekt | PASS, log SHA `eda26c762c60fc38daa6af9a914b386f2a8b3d19bcf4f3ec16a5b7a7657ce11e` |
| Installed CLI smoke | RC4 `version` and `doctor` | PASS, log SHA `bf93ddfa1df34b0843639663f9b9c9b7e3b18b377032cff88dc9b60b52795a15` |
| Installed functional smoke | `pipelinek run --db ... v2/compatibility/01-basic.pipeline.kts` | PASS, exit 0, log SHA `42d1b2ea1e59d3f947428bf8163f5f31fa0de0f3de20a233e100aee232d593dc` |
| GitHub asset identity | `gh release download` + `cmp` + checksum verification | PASS |

`./gradlew check` was **NOT_RUN** for this bounded release. The external
`pipelinek-release-harness` was **NOT_RUN** by this repository, as required by
the responsibility boundary.

## Publication and trunk integration

- `git push origin HEAD:main`: exit `0`, fast-forward from
  `7b8c68e0a68ee9ab66eaf3c34a32d98fbf3c3b9e` to
  `119974cee002158c1f7dec000d238f82ff7e0123`.
- `v0.40.0-rc4` was created as an annotated tag on the candidate commit and
  pushed to origin.
- GitHub prerelease: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc4>
- Release status: published, non-draft, prerelease.

## SDDK gate receipts

- `gate-tests-pass-ef733dba519f6085-1`
- `gate-policy-compliant-ef733dba519f6085-1`
- `gate-debt-severity-assigned-ef733dba519f6085-1`
- `gate-debt-priority-assigned-ef733dba519f6085-1`
- `gate-no-pending-effects-1b511bd0b11eea0a-1`
- `gate-release-uat-approved-1b511bd0b11eea0a-1`

## Reference implementation and security review

Reference implementation consulted: none applicable for Gradle BCV task wiring.

Behaviour adopted: existing API baselines are checked by the owning module's
`check` task for the four explicit BCV modules.

Intentional deviations: other modules remain outside this phase and the full
repository `check` is deferred to the repository gate.

Security implications reviewed: no runtime, credential, process, network, or
serialization behaviour changed; build task wiring only.

Tests demonstrating the contract: C5 verification evidence above and the
existing API baseline files under `v2/*/api/api.api`.

*End of release receipt.*
