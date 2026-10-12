# H1.6 — Handoff to independent certifier

## Candidate

- **Tag (planned):** `v0.56.0-rc6`
- **Source commit (HEAD):** see `git-head.txt` in this directory
- **Branch:** `pk-release-0-56-consolidation`
- **Train:** 0.56.0

## Artefacts in this directory

| File | SHA-256 |
|------|---------|
| `SHA256SUMS` | (this file) |
| `../v2/pipeline-application/build/distributions/pipelinek-0.56.0.zip` | (see SHA256SUMS) |
| `../v2/pipeline-application/build/distributions/pipelinek-0.56.0.sbom.json` | (see SHA256SUMS) |
| `../v2/pipeline-application/build/reports/bom.json` | (see SHA256SUMS) |
| `../v2/pipeline-sdk-bom/build/publications/sdk/pom-default.xml` | (see SHA256SUMS) |
| `../v2/pipeline-sdk-bom/build/publications/sdk/module.json` | (see SHA256SUMS) |
| `../v2/pipeline-application/build/install/pipelinek/lib/pipeline-application-0.56.0.jar` | (see SHA256SUMS) |

ZIP carries 7 bundled plugins: 4 official (pipeline-plugin-http, pipeline.junit, pipeline.scm-git, pipeline.utilities) + 3 external examples (block, directive, uppercase) with apiRange=[0.56.0, 0.57.0) derived from the resolved SDK at build time.

## Build evidence

- `./v2/gradlew :pipeline-release:distZip` BUILD SUCCESSFUL, ZIP=88.5MB
- `./v2/gradlew :pipeline-release:candidateAdmission` PASSED (clean tree, commit-recorded provenance)
- `DistributionMandatoryPluginsTest`: 10/10 PASSED against the real distribution
  - 7-plugin union (4 official + 3 external)
  - host admission per plugin
  - codec decode per manifest
  - ≥1 contribution (steps/directives/events) per plugin
  - host version parsed from application JAR
  - 4-plugin subset present
  - no duplicate pluginIds
  - `LaunchReceipt` schema token `pipelinek.launch/1` in application JAR
  - SBOM CycloneDX well-formed with host application coordinate/purl
  - DistributionMandatoryPluginsMutationTest (R0.1) also green (4 mutation tests)

## What the certifier must do

1. **Verify the tag exists** at the SHA in `git-head.txt` and points at the
   same commit observed here.
2. **Re-compute SHA-256** on every artefact in `SHA256SUMS` and confirm
   they match.
3. **Re-run the gate** on a clean checkout at the same SHA:
   ```
   cd v2 && ./gradlew check --rerun-tasks
   ```
   The brief requires `executed`, `passed`, `failed`, `errors`, `skipped`
   counts as evidence; report all five.
4. **Run the 13 certification scenarios** (R0.5) from the catalogue under
   `docs/v2/05-certification/`. The candidate is NOT-CERTIFIED until those
   13 outcomes are produced.
5. **Sign the verdict** in `evidence/certification-ledger/<tag>/`.

## What the certifier must NOT do

- Do not trust the local SHA-256 sum without recomputing it on the
  downloaded bytes.
- Do not declare CERTIFIED based on the producer-side gate alone; the
  gate only certifies the BUILD, not the runtime behaviour on the
  consumer side.
- Do not modify the ZIP, the SBOM, the BOM, or the SDK POMs after
  download; any divergence is the same defect as a re-built ZIP that
  does not match the receipt.

## Authority

- **Producer gate:** `releaseGate` (this commit's `f7aab65e..dfaa9918`).
  PASSED locally, with seven-plugin surface, LaunchReceipt contract
  check, and SBOM CycloneDX well-formed check.
- **Certifier verdict:** NOT YET. Handoff above is the request.
