# Specification — Distribution, Release and SDKMAN

Status: PROPOSED

## 1. One artifact authority

The release pipeline produces one canonical universal JVM distribution ZIP from the V2 Gradle `application` distribution. GitHub Releases, SDKMAN and future installers consume that same immutable artifact.

```text
source tag
   ↓
release gate
   ↓
distZip
   ↓
canonical ZIP + SHA-256 + SBOM
   ↓
GitHub Release
   ├── SDKMAN
   ├── future Homebrew/mise/asdf/Scoop
   └── direct download
```

No installer rebuilds the application independently.

## 2. Runtime baseline

First LPR targets Java 21 JVM distribution. Native image/jlink are future optimizations gated by measured startup/footprint needs.

Preferred CLI executable name: `pipelinek`. SDKMAN candidate name is intended to be `pipelinek`, subject to vendor onboarding/name acceptance.

## 3. Gradle distribution

Use the existing `application` plugin and make the distribution explicit:

- `applicationName = "pipelinek"` or equivalent;
- `distZip` is release artifact;
- `installDist` remains HF2/local-real-binary test artifact;
- archive timestamps/order/permissions configured for reproducibility where the repository's Gradle version does not already guarantee it;
- version comes from release/tag authority, not hard-coded `0.1.0-SNAPSHOT` in a production release.

## 4. Release contents

```text
pipelinek-<version>/
├── bin/pipelinek
├── bin/pipelinek.bat   # may exist from Gradle distribution; SDKMAN primary target remains Unix/WSL
├── lib/*.jar
├── LICENSE
└── NOTICE/README-release (optional)
```

Published alongside:

- `pipelinek-<version>.zip`;
- `SHA256SUMS` — the single digest authority for the whole release, resolved
  by basename (its entries carry the build-time path, e.g.
  `dist/candidates/<version>/pipelinek-<version>.zip`, not the download name);
- SBOM (CycloneDX/SPDX chosen by implementation);
- release notes;
- optional signature/provenance attestation after initial gate.

A per-asset `<asset>.sha256` sidecar is **not** published and must not be
consulted. That URL returns 404 on real releases; reading it makes any
digest check fail closed forever. Consumers resolve through `SHA256SUMS`
(via `scripts/release/resolve-release-digest.sh`) and are expected to
recompute the digest from the archive bytes before trusting it.

## 5. GitHub Actions release gate

Release workflow is separate from current quarantined V1 workflow. It runs against a tag/release candidate and must execute:

- compile/static/architecture fitness;
- focused unit/application tests;
- real installed distribution tests;
- LPR real-project suite;
- observability performance smoke;
- secret canaries;
- archive reproducibility check (build twice, compare digest in controlled lane);
- `distZip` + checksums + SBOM;
- GitHub Release upload.

Branch protection/required checks are configured separately from files in the repo.

## 6. SDKMAN publication

SDKMAN supports vendor publication through its Vendor API and official release automation. The workflow only publishes the already-created GitHub release ZIP, checksum included when supported.

Vendor credentials live only in GitHub secrets/environment protection. Production workflow pins third-party actions to reviewed immutable commits where practical.

Before onboarding is complete, GitHub Release ZIP is the public artifact authority and SDKMAN publishing remains a gated task, not a release blocker for internal dogfooding.

## 7. SDKMAN UAT

After publication:

```text
clean runner
  ↓
SDKMAN CI/noninteractive install
  ↓
sdk install pipelinek <version>
  ↓
pipelinek version
pipelinek doctor
pipelinek validate
pipelinek run real-project
```

The installed ZIP digest must match the GitHub Release artifact expected by the publish job.

## 7a. Known published-artifact defect — version laundering (observed 2026-09-30)

Two published releases ship bytes that claim a DIFFERENT version than the
asset name. Verified by downloading each real asset:

| Release | Asset | Archive root | JAR/binary version | SHA-256 |
| --- | --- | --- | --- | --- |
| `v0.40.0` | `pipelinek-0.40.0.zip` | `pipelinek-0.40.0-rc8` | `0.40.0-rc8` | see release manifest |
| `v0.43.0` | `pipelinek-0.43.0.zip` | `pipelinek-0.43.0-rc1` | `0.43.0-rc1` | `b81687acf82d04e814908eadfaaaa39520fe976e772c40084a0125bba2005483` |

`v0.43.0` and `v0.43.0-rc1` are **byte-identical** (same digest above): the
final release is the rc1 ZIP renamed, with no rebuild.

Consequences:

- a consumer that trusts the asset NAME installs rc content as a final release;
- `SHA256SUMS` cannot detect this on its own, because the manifest is derived
  from the same build as the (misnamed) asset;
- build-time admission is the control that prevents recurrence, which is why
  `CandidateAdmission` (TRAIN P0.3) refuses to materialize a candidate whose
  recorded identity diverges from the root version.

Status: the producer fast lane cannot retro-fix published bytes. Republishing
under a correct version is an operator decision (it creates a new tag and a
new release). Until then, `scripts/release/cheat-sheet-uat.sh` REPORTS the
mismatch instead of assuming the asset name is truthful, and it defaults to
`v0.43.0` (the newest release publishing both a ZIP and a `SHA256SUMS`)
while still flagging the laundering above. Set `CHEAT_UAT_VERSION` to target
a specific release.

## 8. Future distribution adapters

Allowed after LPR and only from canonical ZIP/version metadata:

- Homebrew formula/tap;
- mise/asdf plugin;
- Scoop/Windows-native path if product demand warrants it;
- container image for reproducible runners;
- jlink/native binary if benchmarks justify.

These do not become new build authorities.
