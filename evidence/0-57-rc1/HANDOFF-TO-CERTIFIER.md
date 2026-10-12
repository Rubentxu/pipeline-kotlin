# Handoff to certifier — v0.57.0-rc1 candidate

## Producer (this repo) vs certifier (you)

The producer's responsibility ends at `candidateAdmission` PASSED and a
complete set of artefacts catalogued under SHA256. **Nothing in this
document is a verdict.** The certifier independently re-computes SHAs,
re-runs the gate, and re-measures the 13 R0.5 scenarios before signing
anything.

## Source of truth

| Item | Value |
|---|---|
| Train | 0.57.0-rc1 |
| Work-item (local) | pk-0-57-rc1 |
| Commit (HEAD) | `50a32acfd6fcf0474ca33c2f57fec563d383c0e0` (see `git-head.txt`) |
| Branch | `pk-release-0-57-consolidation` |
| Base | `main` HEAD `05ce6236` (post-merge v0.56.0-rc5) |
| Tree state at candidate build | clean (verified by candidateAdmission) |

## Artefacts in this directory

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.57.0.zip` | 88.5 MB | `0a29d5231be03cfb6b086c6bbd2820b7849a15b5e40fb3e36f2458adb1f40d1c` |
| `pipelinek-0.57.0.sbom.json` | 1.3 MB | `b5afecbaf47f65a3bd3a9d1de23287126c071bbc8e18a03fbab2326c20022c66` |
| `pipeline-sdk-bom-0.57.0.pom` | — | `54228c13c7aeb58ca17d6143563a4c045eb5401b84e64f728f1ae3c8b10affc7` |
| `pipeline-domain-0.57.0.pom` | — | `232ebec04314e454da26acdd20fec168e86f1f0f1353fbaac8dcd87bac7b038d` |
| `sdk-domain-0.57.0.module` | — | `10c91cb218c1e67dddaa9d0c4e55b4ba64f810bc119b341f4731c9a296a007c0` |
| `pipeline-application-0.57.0.jar` | — | `45e174147e48173f540dee9e7bffc51230d29091c85cf7ea7f56f0cc6f76143b` |
| `git-head.txt` | — | `4b036e44b3a02039c50bae191a9631cef62ec1498a9e2072922f17d811d91bc9` |

`SHA256SUMS` aggregates all of the above.

## What the gate verified (producer-side, this commit)

1. **H1.4 7-plugin gate** — `DistributionMandatoryPluginsTest` 3/3
   green against `pipelinek-0.57.0.zip`:
   - exactly the four official plugins (http, scm-git, junit, utilities)
     AND the three external examples (block, directive, uppercase)
   - all seven admit the running host `0.57.0`
   - the v0.56.0-rc3 reproducer is still refused (apiRange=`[0.47.0, 0.53.0)`)
2. **`candidateAdmission`** — provenance `VERIFIED` (clean tree at the
   commit named in `distribution-manifest.json`), manifest emitted.
3. **D2 ProcessOutputDecoder** — 18/18 green on
   `:pipeline-application:test --tests ProcessOutputDecoderTest`,
   including the 8-test matrix from ADR-PK1-OUTPUT-FRAMING for the
   opt-in `pipelinek.output/2` protocol.
4. **D2 CliContractTestKit** — 15/15 green.
5. **Bump `0.56.0 → 0.57.0`** — `version = "0.57.0"` is the SOLE
   authority; all four `PluginCompatibilityPolicyTest` cases re-anchored
   to the 0.57.0 host (apiRange upper exclusive is now `0.58.0`).
6. **External examples recompiled with `-PsdkVersion=0.57.0`** — every
   example's `*release.properties` now declares
   `compatibility.min=0.57.0` and `compatibility.untilExclusive=0.58.0`,
   the union of the seven plugin ranges covers the 0.57 host.

## What the certifier must do

1. **Re-compute SHAs** against the artefacts in this directory. Every
   line in `SHA256SUMS` must match what you compute locally; any
   mismatch is a hard fail.
2. **Re-run the gate** from a clean checkout at `git-head.txt`:
   ```bash
   cd v2
   PIPELINEK_DIST_ZIP=$(pwd)/pipeline-application/build/distributions/pipelinek-0.57.0.zip \
   PK_RC_PLUGIN_001_RC3_ZIP=/path/to/rc3.zip \
   ./gradlew --no-daemon --no-parallel -p . \
     :pipeline-release:candidateAdmission \
     :pipeline-release:test --tests "dev.rubentxu.pipeline.v2.release.DistributionMandatoryPluginsTest" \
     :pipeline-application:test --tests "dev.rubentxu.pipeline.v2.application.support.*" \
     --rerun-tasks
   ```
   `candidateAdmission` must PASS. `DistributionMandatoryPluginsTest`
   must be 3/3. The `support.*` classes must be 33/33 (18 decoder + 15
   testkit).
3. **Re-measure the 13 R0.5 scenarios** defined in
   `docs/v2/07-uat/R0_5_CERTIFICATION_SCENARIOS.md`. These are
   producer-blind: the producer has not run them and the certifier
   verdict is the only authority for them.
4. **Inspect the seven plugin manifests** inside
   `pipelinek-0.57.0/lib/*.jar` — each `META-INF/pipelinek/plugin-manifest.json`
   must declare the same apiRange its `*release.properties` declares.
5. **Sign the verdict** with the certifier's own identity and write it
   to `evidence/0-57-rc1/CERTIFIER-VERDICT.md` with the same artefact
   SHAs above plus the verdict.

## What the certifier must NOT do

1. **Do not modify the artefacts in this directory.** The candidate is
   immutable. If a SHA mismatches, fail the verdict and report; do not
   rebuild locally and substitute your build for the producer's.
2. **Do not merge to main.** Tagging `v0.57.0-rc1` and merging to main
   are release-engineering actions that follow the certifier's signed
   verdict. They are not the certifier's job.
3. **Do not promote to GA.** `0.57.0-rc1` is a release candidate, not a
   GA. A separate cycle owns the GA decision; that cycle's brief will
   arrive on its own schedule.

## Authority boundary

The producer's PASSED is the producer's claim that the gate ran and
the bytes compiled. **It is not a claim of correctness.** The
certifier's verdict is the only authority for promotion.
