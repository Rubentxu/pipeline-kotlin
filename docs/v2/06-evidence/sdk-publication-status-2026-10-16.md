# SDK publication status — 2026-10-16

## Verdict

```text
SDK_PUBLISHED = BLOCKED_EXTERNAL
```

The SDK cannot be declared `PUBLISHED` because no remote Maven
destination is authorised. The local `build/sdk-repo` is a
build-time fixture, not a publishable repository, and recording
this fact honestly is the contract for H2 TODO 4 of
`docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md` BLOQUE 3.

## Why this is `BLOCKED_EXTERNAL`, not `PASS`

`SDK_PUBLISHED` requires that a consumer can resolve the SDK
coordinates from a **remote** Maven repository. Per
`docs/v2/05-roadmap/P2_MAVEN_PUBLICATION.md` §28–50 (and re-confirmed
2026-10-16), every published module declares the same destination:

```kotlin
// v2/<module>/build.gradle.kts
maven {
    name = "sdk"
    url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
}
```

That URL is `v2/build/sdk-repo`, a directory inside `build/`.
It is **not** Nexus, not GitHub Packages, not a Maven repo over
HTTP. It exists for offline verification of the publish chain,
and it is discarded by every `./gradlew clean`. The pieces
needed for a real remote publication are documented in
`P2_MAVEN_PUBLICATION.md` §54–60 and are all open:

| Piece | Status | Owner |
|---|---|---|
| Remote repo URL | not configured in any `build.gradle.kts` | persona (Nexus / GitHub Packages) |
| Publication credentials | not injected | persona |
| GPG signing (`signing` plugin) | absent | persona (key + policy) |

The PRODUCT-GATE side (full chain through certifier) is
already `BLOCKED_EXTERNAL` per ADR-0105 (no remote CI surface
in this repository). The SDK side is `BLOCKED_EXTERNAL` for the
same reason in a different shape: nothing outside this build
host can resolve the coordinates, so no remote consumer can
compile against them, so `SDK_PUBLISHED` cannot be set to
`PASS` without lying.

## What IS verifiable

The local `build/sdk-repo` is real and current, and the
publication chain is end-to-end exercisable from the build
host. As of 2026-10-16 (commit `efd569fe`):

- 5 published contract JARs at version 0.62.0:
  `pipeline-domain`, `pipeline-events`, `pipeline-output`,
  `pipeline-scripting-api`, `pipeline-runtime-api`.
- BOM POM at `pipeline-sdk-bom-0.62.0.pom` carrying **5**
  `<dependencyManagement>` entries (the four read-side
  contracts plus `pipeline-runtime-api`; the H2 TODO 3 fix
  shipped in `efd569fe`).
- All 8 release versions of these artifacts persisted locally
  (0.55.0 → 0.62.0) — 41 POMs and 41 JARs in
  `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`.
- `PublishedContractBoundaryFitnessTest` (5 published contract
  modules enforced) passes 6/0/0 in 0.272 s on a fresh tree.

A consumer that points Maven at `v2/build/sdk-repo` can resolve
the SDK today. That consumer is not a remote consumer; it is
this build host. `SDK_PUBLISHED` measures the former, not the
latter.

## Implications (per AGENTS.md §13)

`SDK_PUBLISHED=BLOCKED_EXTERNAL` does **not** block the CLI
release candidate. AGENTS.md §13 ("Fast lane, sin bloqueo por
certificación") and QUALITY_VALUE_ADDENDUM.md §206
("`CANDIDATE_PUBLISHED` ≠ `SDK_PUBLISHED` ≠ `CERTIFIED` ≠
`STABLE_PROMOTED`; los bloqueos externos no paralizan work
units independientes") make the separation explicit. The CLI
RC can ship; the SDK publication requires a decision the
repository cannot take alone (which Maven destination, which
credentials, which signing policy).

This status is therefore recorded as a fact, not a defect:
the chain is verifiable locally, the remote destination is
unauthorised, and the next change that flips this verdict must
configure a real remote destination and prove resolution from
a clean consumer — neither of which is in scope for this
commit.

## Cross-references

- `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md` line 177 (BLOQUE 3).
- `docs/v2/05-roadmap/P2_MAVEN_PUBLICATION.md` §28–60.
- `docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md` (PRODUCT-GATE
  `BLOCKED_EXTERNAL` is the same kind of verdict for the CI side).
- Commit `efd569fe` — H2 TODO 3, the BOM-5th-constraint
  change that pinned the published contract set in the BOM.
- `v2/pipeline-architecture-tests/.../PublishedContractBoundaryFitnessTest.kt`
  — fitness that pins the contract-set invariant; passes 6/0/0
  at this SHA.
