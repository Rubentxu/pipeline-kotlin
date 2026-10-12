# PK-RC-PLUGIN-001 — Eliminate plugin compatibility drift (P0)

## Problem

The four official plugins (http, scm-git, junit, utilities) embedded in
`v0.56.0-rc3` declare `apiRange=[0.47.0, 0.53.0)` while the host product version is
`0.56.0`. The exclusive upper bound excludes 0.56.0 — at runtime, every official
plugin would refuse its own host. The candidate is published but the runtime can
never start it.

The drift was inherited from a chain of per-train manual edits to the apiRange
literal. The Gradle `inputs.property("apiRange", "[0.47.0, 0.52.0)")` in each
plugin's build.gradle.kts is dead code: the actual manifest is emitted by Kotlin
`main()` calling `PluginManifestCodec.encode(HttpPluginDeclaration.manifest())`
which uses the Kotlin constant `PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 53, 0))`.
Two sources of truth, neither updated when product version was bumped.

The release gates (`BundledPluginCompatibility`, `CandidateAdmission`,
`candidateAdmission` Gradle task) exist but were never invoked against the rc3
ZIP. The release-receipt and handoff for v0.54.0-rc1 (and v0.56.0-rc1+rc2+rc3)
contain zero references to them.

## Authoritative rule (criterio de cierre)

> Subir la versión del producto en un futuro hito no exige editar las
> declaraciones oficiales de compatibilidad y ningún flujo normal de
> publicación puede aceptar un ZIP que sus propios plugins rechazan.

## Plan

### Block 1 — Reproduce + investigate bypass
- [x] Download `pipelinek-0.56.0.zip` rc3 + extract 4 plugin manifests
- [x] Confirm `apiRange=[0.47.0, 0.53.0)` excludes `0.56.0`
- [ ] Run `BundledPluginCompatibility.probeZip` + `evaluate(ProductVersion("0.56.0"))` against rc3 → must report Incompatible for all 4
- [ ] Trace the publish path: what script/task published rc3, did it invoke `candidateAdmission`?
- [ ] Document the bypass path in evidence/

### Block 2 — Single source of compatibility truth
- [ ] Create pure policy in `buildSrc`: `rootProject.version` + next-minor → exclusive upper bound
- [ ] Default lower bound: `SemVer(0, 47, 0)` (or current major floor)
- [ ] Lower bound overridable by `org.gradle.api.attributes.Attribute`-style
      extension only when prior cert exists
- [ ] Gradle task `generateCompatibilityResource` writes
      `META-INF/pipelinek/plugin-compatibility.json` from `project.version`
- [ ] Refactor `HttpPluginDeclaration`, `ScmGitPluginDeclaration`,
      `JUnitPluginDeclaration`, `UtilitiesPluginDeclaration` to load apiRange
      from the generated resource — kill the hardcoded `PipelineKApiRange(...)`
      constant
- [ ] Refactor the four `emit*Manifest` tasks: replace
      `inputs.property("apiRange", "[0.47.0, 0.52.0)")` with
      `inputs.file(compatibilityResource)`, add `dependsOn(generateCompatibilityResource)`
- [ ] External example plugins keep manual `apiRange` (no auto-broadcast)
- [ ] `processResources`, digest generation, and `jar` all depend on the
      resource, so version change regenerates everything
- [ ] Fail-closed: missing or invalid resource → build error, no fallback

### Block 3 — Tests + anti-regression gate
- [ ] `BuiltPluginManifestArtifactTest.gateAdmitsTheRealArtifact` →
      `RuntimeApiVersion.current()` instead of `SemVer(0, 47, 0)`
- [ ] Historical 0.47/0.51 tests: keep as separate `*AtHistoricalVersion*` tests
      (NOT as current-version tests)
- [ ] Negative-bound tests pull the lower/upper from the real manifest, not literals
- [ ] `BundledPluginCompatibility`: canonical `PluginManifestCodec` decode (no regex)
- [ ] Gate asserts presence of 4 mandatory plugins (http, scm-git, junit, utilities)
- [ ] Reject: missing, duplicate, malformed manifest, range excludes host
- [ ] Gradle TestKit test: build two consecutive product versions without editing
      plugin sources
- [ ] Cache tests: clean / incremental / --build-cache / version change /
      policy change
- [ ] Mutants: stale range, missing resource, stale manifest, incompatible
      declaration → must produce RED for the right reason
- [ ] `check` task runs the new gate
- [ ] `releaseGate` includes `candidateAdmission` over the final ZIP bytes
- [ ] Publish scripts refuse to advance without a releaseGate PASS receipt bound
      to the ZIP digest

### Block 4 — Release closure + recertification
- [ ] Build ZIP from clean final SHA
- [ ] Install ZIP outside build dir
- [ ] `pipelinek version` + 4-manifest inspection
- [ ] UAT installed: real Step of each plugin (http→local server, git→fixture
      repo, junit→XML report, utilities→read/write)
- [ ] Re-run the 13 original scenarios, distinguish admission/execution/result
- [ ] `cd v2 && ./gradlew check --rerun-tasks` + `releaseGate`
- [ ] `candidateAdmission` over final ZIP, preserve receipt bound to digest
- [ ] Merge to `origin/main`
- [ ] Tag `v0.56.0-rc4` and Prerelease
- [ ] Re-download ZIP, verify digest, version, manifests, UAT
- [ ] Mark `v0.56.0-rc3` as historical incompatible in evidence; do NOT
      rewrite rc3 tag or asset
- [ ] `PK_CONTRACT_HANDOFF` for rc4 → Fabric
- [ ] External certification + joint with Fabric

## Critical files (initial)

- `v2/buildSrc/` — add new policy module
- `v2/pipeline-step-sdk/{http,scm-git,junit,utilities}/build.gradle.kts`
- `v2/pipeline-step-sdk/{http,scm-git,junit,utilities}/src/main/kotlin/.../*PluginDeclaration.kt`
- `v2/pipeline-application/src/test/kotlin/.../BuiltPluginManifestArtifactTest.kt`
- `v2/pipeline-release/src/main/kotlin/.../BundledPluginCompatibility.kt`
- `v2/pipeline-release/src/main/kotlin/.../CandidateAdmission.kt`
- `v2/pipeline-release/build.gradle.kts`
- `v2/build.gradle.kts`

## Acceptance

A future product-version bump must regenerate all four manifests and JARs
without any human edit to plugin declarations. The release gate rejects
any candidate whose bundled plugins exclude the shipped host version.
