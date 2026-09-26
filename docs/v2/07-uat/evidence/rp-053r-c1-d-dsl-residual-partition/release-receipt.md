# C1-D RC5 release receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c1-d-dsl-residual-partition`
**Candidate:** `pipelinek 0.40.0-rc5`
**Release tag:** `v0.40.0-rc5`
**Candidate/build commit:** `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4`
**Main at candidate publication:** `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4`
**Base main:** `9e26cc65902fdb0b4e381cc36e4f6ca4acd1762f`
**Date:** 2026-09-26
**Status:** PUBLISHED PRERELEASE, READY FOR HARNESS INTAKE

## Scope

This candidate publishes the C1-D residual StepSpec source partition. The complete typed `StepSpec` hierarchy moved from `PipelineDsl.kt` to `PipelineDslSteps.kt` without changing package names, nested names, public signatures, compiler input, or runtime behavior. The remaining StageScope/validation/lowering partition stays explicitly open.

The product version authority is Gradle: `v2/build.gradle.kts:rootProject.version = "0.40.0-rc5"`. No Cargo manifest is involved in this Kotlin/Gradle project. Generic SDDK Rust/Cargo planner rules are outside this repository's release responsibility.

## Immutable material

Local candidate directory: `dist/candidates/v0.40.0-rc5/`

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.40.0-rc5.zip` | 92,082,060 | `69a2421cb5e785e8fa454391df992c530dffeb0dd04468389deefcd8e5222df2` |
| `pipelinek-0.40.0-rc5.sbom.json` | 17,557 | `d4f73c779b1cb054ea1dabec0f7973ffc8bd62ec5dacc9bab92077025b50c487` |
| `SHA256SUMS` | 188 | `e5daa326847c73ec04f1cee5a92ea800efcbe6b9567c1eeb11a02c2b345f64ea` |
| `release-manifest.json` | 2,840 | `see local manifest; uploaded byte-exact` |

The ZIP passed `unzip -t -q`. `sha256sum --strict -c SHA256SUMS` passed for ZIP and SBOM. The SBOM is CycloneDX 1.5 with 41 components.

## Verification evidence

| Check | Command/evidence | Result |
|---|---|---|
| Distribution build | `timeout 900 ./gradlew :pipeline-application:distZip :pipeline-step-sdk:scm-git:detekt :pipeline-application:detekt --console=plain` | PASS, exit 0, log SHA `18f653b132b7d4b755b8157d3245062083a8d9e6267bf46fef2fa2dde1d3daf2` |
| Candidate admission | `python3 scripts/admission-check.py` at build commit | PASS, 6/6, log SHA `97ad001c54119c88b93be3e61e4f2e4df5d771c1adc75cf138603f786e65d353` |
| Package integrity | ZIP test + strict checksum | PASS, combined log SHA `20da4ec98db8e31677b3505e6710ea11fd22082ba163b345e58b17d80b6eeb38` |
| Installed CLI smoke | `pipelinek version` | PASS, `pipeline 0.40.0-rc5` |
| Installed CLI doctor | `pipelinek doctor` | PASS, JDK 24.0.2, Linux, writable workdir |
| Installed functional smoke | `pipelinek run --db ... v2/compatibility/01-basic.pipeline.kts` | PASS, exit 0, `Pipeline finished with SUCCESS` |
| Installed smoke combined evidence | version + doctor + functional run | PASS, log SHA `5d533e3bba34b9c34406523c114b4f6675b78492b53d2b3a22676aa8cb5f5c99` |
| GitHub identity | `gh release download v0.40.0-rc5`, `cmp` all four assets, strict checksum | PASS, download log SHA `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

## Publication

- `git push origin HEAD:main`: PASS, fast-forward `9e26cc65` → `04ceff8d`.
- Annotated tag `v0.40.0-rc5`: PASS, target `04ceff8d`.
- GitHub prerelease: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc5>
- Assets uploaded: ZIP, SBOM, `SHA256SUMS`, and `release-manifest.json`.
- Downloaded GitHub assets were byte-identical to the local candidate.

## Gates and boundaries

- `gate-no-pending-effects-8d9d130eb9c9b444-1`: PASS.
- `gate-release-uat-approved-8d9d130eb9c9b444-1`: PASS.
- Full repository `check`: NOT_RUN for this bounded release.
- External `pipelinek-release-harness`: NOT_RUN here. It is a separate project and owns external corpus certification.
- Stable release: NO. This is a prerelease candidate.

Reference implementation consulted: none applicable for this source-organization release.

Behaviour adopted: preserve the typed DSL contract while isolating StepSpec declarations and publish the resulting bytes as RC5.

Intentional deviations: no StageScope, validation, or lowering extraction; no external harness execution in this repository.

Security implications reviewed: no runtime, credential, process, network, persistence, or serialization behavior changed. Artifact integrity was verified by strict checksums and byte comparison after download.

Tests demonstrating the contract: C1-D receipt, C1-D verification report, focused DSL/host/architecture tests, distribution build, installed smoke, and GitHub byte identity evidence above.

*End of release receipt.*
