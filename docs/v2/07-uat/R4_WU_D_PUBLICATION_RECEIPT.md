# R4 / WU-D — v0.61.0-rc2 publication: PRODUCT IDENTITY LAW satisfied end to end

- **Status:** CANDIDATE_PUBLISHED · 2026-10-15
- **Train:** 0.61.0-rc2 · **Source of truth:** this file + `evidence/0-61-rc2/SHA256SUMS` + `evidence/0-61-rc2/git-head.txt` + `evidence/0-61-rc2/HANDOFF-TO-CERTIFIER.md`
- **Base:** `4a6b161e` (post PR #136)
- **Tip:** `52b9c491` (merge of PR #137)
- **Provenance:** additive merge of PR #130 (WU-A/B), #133 (WU-B), #135 (WU-C), #136 (WU-C polish) into `main`, then PR #137 (this block) on top. No squash. ADR-0099 honoured.

## What this block did

It fixed the product-identity defect that the rc1 candidate carried, and it produced the
byte-level artefacts that a certifier can verify independently of this repo.

### The defect rc1 carried

The rc1 candidate was published with `v2/build.gradle.kts` set to `0.60.0` while the train was
advertised as `0.61.0-rc1`. The 5 surfaces of `PRODUCT IDENTITY LAW` (asset name, archive root,
JAR `Implementation-Version`, runtime `sdk-info`, distribution manifest) all carried `0.60.0`
because `rootProject.version` is the single source of truth. The defect was visible in
`git ls-tree v0.61.0-rc1 -- evidence/`: the ZIP and SBOM were named `pipelinek-0.60.0.{zip,sbom.json}`,
not `0.61.0.*`.

This is a *naming* defect with a *correctness* consequence: a consumer who trusts the
`version` of the installed binary and pairs it with the train name `0.61.0-rc1` reads `0.60.0`
back from `sdk-info`, and there is no way to detect the disagreement from outside the build
without reading `build.gradle.kts` directly.

### The fix

- `v2/build.gradle.kts` version bumped from `0.60.0` to `0.61.0`. Single line, documented
  in the file: the candidate identity is `0.61.0-rc2` (in the train name, the directory name
  and the git tag), the product identity is `0.61.0` (every other surface).
- The `distZip` and `installDist` builds were re-run with `-Pversion=0.61.0`; the JAR
  manifest, the launcher scripts, the archive root and the asset name all derive from
  `rootProject.version`, so the bump propagates through.
- `pipelinek-0.61.0.zip` (88.7 MB), `pipelinek-0.61.0.sbom.json` (CycloneDX 1.5, 48 components,
  19.7 KB), `SHA256SUMS` (3 entries), `git-head.txt` (41 B) and `HANDOFF-TO-CERTIFIER.md`
  are committed under `evidence/0-61-rc2/`. `SHA256SUMS.txt` is a copy retained for
  rc1-convention compatibility.

## Five surfaces, one identity

```text
surface                                  value       source
---------------------------------------  ----------  ---------------------------------------
build.gradle.kts version                  0.61.0      v2/build.gradle.kts:94
asset name                                0.61.0      pipelinek-0.61.0.zip
archive root                              0.61.0      install test: 'Installed binary reports: pipeline 0.61.0'
JAR Implementation-Version                0.61.0      unzip -p .../lib/pipeline-application-0.61.0.jar META-INF/MANIFEST.MF
runtime sdk-info version                  0.61.0      pipelinek sdk-info: "version":"0.61.0"
runtime sdk-info 6 coordinates            0.61.0      pipelinek sdk-info: coordinates[].version all 0.61.0
distribution manifest entries             0.61.0      evidence/0-61-rc2/SHA256SUMS (zip, sbom, git-head)
```

## Producer verification

| Check | Result |
|---|---|
| `:pipeline-step-sdk:runtime:detekt` | 0 issues |
| `:pipeline-application:detekt` | 0 issues |
| L4 incremental at base `4a6b161e` (post #136) | 262 / 3 skipped / 0 failures / 0 errors |
| `ConsoleMultiOpInstallCanaryTest` HF2 | 3 / 0 / 0 against the installed binary |
| `sha256sum -c evidence/0-61-rc2/SHA256SUMS` | 3 / 3 OK |
| `resolve-release-digest.sh` against the manifest | 3 / 3 OK (asset → SHA-256) |
| `install-pipelinek.sh install 0.61.0` against a loopback mirror | digest verified, `pipeline 0.61.0`, `sdk-info` 0.61.0 across 6 coordinates |
| `python3 scripts/release/sbom-cyclonedx.py` exit | clean (48 components written) |

## The producer half, end to end

```
v2/build.gradle.kts:0.61.0
  └── ./gradlew :pipeline-application:distZip -Pversion=0.61.0
        └── build/distributions/pipelinek-0.61.0.zip (88.7 MB)
              ├── python3 scripts/release/sbom-cyclonedx.py …  (CycloneDX 1.5, 48 components)
              ├── bash scripts/release/build-release-manifest.sh --out SHA256SUMS …
              ├── echo $GIT_COMMIT > git-head.txt
              └── cp SHA256SUMS SHA256SUMS.txt
                    └── sha256sum -c SHA256SUMS   (3/3 OK)
                          └── cp evidence/0-61-rc2/* $TMPDIR/release/v0.61.0/
                                └── install-pipelinek.sh install 0.61.0 against loopback
                                      └── pipelinek sdk-info
                                            └── "version":"0.61.0", 6 coordinates at 0.61.0
```

## What is BLOCKED here, recorded honestly

- **External certifier verdict.** The certifier runs against the published bytes, recomputes
  digests, re-measures the R4 scenarios and signs. This repo does not claim `STABLE`.
- **`v0.61.0-rc2` git tag** is created at the head of the merge (`52b9c491`) so the published
  bytes can be referenced by SHA-1 against the tag. The tag is a pointer, not a mutation.
- **GitHub Release publication and the harness delivery** are the harness's responsibility
  per `Frontera de responsabilidad` §5. This repo stops at `CANDIDATE_PUBLISHED`.

## Reference implementation consulted

- **Reference implementation consulted:** none applicable. This block's job is to satisfy
  the operator's named product-identity contract and produce the assets; no external
  reference was sought.
- **Behaviour adopted:** asset naming convention from the existing `v0.61.0-rc1` evidence
  (`pipelinek-{ProductVersion}.{zip,sbom.json}`); the directory carries the train name with
  the candidate suffix.
- **Intentional deviations:** none.
- **Security implications reviewed:** the SBOM is included so a consumer can verify the
  dependency inventory against the bytes; the digests in `SHA256SUMS` are SHA-256 over the
  exact published bytes; `sha256sum -c` and `resolve-release-digest.sh` both refused
  every other form. A tampered asset is detected by the consumer, not silently accepted.
- **Tests demonstrating the contract:** `ConsoleMultiOpInstallCanaryTest` HF2; the
  `install-pipelinek.sh` R1..R6 contract test suite is a separate hermetic suite at
  `scripts/test_install_pipelinek.py` and is the existing harness for the install contract.

## Remaining

- The certifier re-runs the R4 scenarios against the published bytes and signs.
- After `0.61.0-rc2` is accepted, the next additive slice of the R4 plan is the consolidation
  of the internal test harness in `v2/test-harness/` as a separate PR, per the operator's R4
  plan.