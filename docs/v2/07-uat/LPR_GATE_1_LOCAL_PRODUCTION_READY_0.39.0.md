# LPR-GATE-1 — pipelinek 0.39.0 — Local Production Ready (CLOSED)

**Authority**: WU-LPR-071 (release workflow + workspace fix + version bump + GitHub Release publication)

**Date**: 2026-09-19

**Status**: **CLOSED — Local Production Ready**

---

## 1. Goal

Take the `pipelinek` distribution through the LPR (Local Production Ready) gate:

- The repo's own `/pipeline.kts` runs as a real pipeline against the canonical release artifact.
- A reproducible, audited ZIP is published as `v0.39.0` on GitHub Releases.
- The trunk (`main`) carries a single-version provider and a fail-closed `pipelinek version`.
- The legacy `0.1.0-SNAPSHOT` fallback is gone.
- The `--workspace` regression that broke every real-project fixture is fixed.

## 2. What landed

Five commits on `main`, all pushed to `origin`:

| SHA | Description |
|---|---|
| `cce9b3ab` | `fix(release-prep): close round-gate defects (SB-S-008, SB-S-010, CR-BD-027, CR-U9, WULpr402)` |
| `bbe916fd` | `docs(lpr): release prep handoff + receipt` |
| `88b26b81` | `feat(release): release workflow (root pipeline.kts + single-version provider)` |
| `fb2ed28d` | `feat(release): RC v0.36.0 — in-tree SBOM generator + release artifacts ignored` |
| `951b3cb5` | `fix(release): --workspace . was nesting under project root` (post-fix ZIP) |
| `68ba01ab` | `chore(release): bump to 0.39.0 — Local Production Ready` (cosmetic trunk state) |

Round-gate defect closure (commit `cce9b3ab` + `bbe916fd`) covered:

- **SB-S-008** / **SB-S-010**: sandbox profile regressions (`UatLocal007SandboxProfileTest`)
- **CR-BD-027**: credential redactor bug (`UatLocal008CredentialsTest`)
- **CR-U9**: `FileWritten` event shape (`UatCompat001*`)
- **WULpr010** (CLI): `AppBinSupport.discover()` instead of stale literal `pipeline-application` path
- Plus 7 fitness drift corrections.

## 3. Canonical artifact

```
pipelinek-0.39.0.zip
sha256 385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8
size   91,416,100 bytes
build  reproducible (A == B)
```

- **Certify commit**: `951b3cb5695ecc46c877776e330266e4bd44aa9e`
- **Tag**: `v0.39.0` (annotated, tag-object `be3b1b42ee72c9b60f08f7d6ac5abe176caad61b`)
- **Target commit of the tag**: `951b3cb5` (certified)
- **Trunk HEAD after release**: `68ba01ab97feb9debb194e1ebbcd8c86991fb250`
  - Cosmetic bump only — ZIP bytes unchanged. `v2/build.gradle.kts` root authority + `/pipeline.kts` PKG_VERSION both bumped to `0.39.0` so future `distZip` invocations stay aligned with the published version.

## 4. Certify (fresh unzip, `--workspace .`)

Run against the canonical ZIP, downloaded fresh from the build:

| Check | Outcome |
|---|---|
| `pipelinek version` | `pipeline 0.39.0`, exit 0 |
| `pipelinek doctor` | exit 0 (jdk 24.0.2, workdir writable) |
| Gradle smoke (real `gradlew`) | `outcome=success`, `GRADLE-DEMO-OK`, jar produced |
| Maven smoke (real `mvn`) | `outcome=success`, `MAVEN-DEMO-OK`, jar produced |
| Node smoke (real `node:test`) | `outcome=success`, `NODE-DEMO-OK`, tests 2/2 |
| Gradle FAIL path | `outcome=failure`, `failureKind=SCRIPT`, `shell exited with code 1` |
| 0 SNAPSHOT strings in ZIP | 0 matches |
| Reproducible build | A == B |

## 5. Post-publish verify (download from GitHub Release, SHA verify, smoke)

Downloaded the published ZIP from `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.39.0/pipelinek-0.39.0.zip`:

- **Size**: 91,416,100 bytes — match
- **SHA-256**: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` — match (certified == published)
- `pipelinek version` → `pipeline 0.39.0` ✓
- `pipelinek doctor` → exit 0 ✓
- Gradle smoke: `outcome=success`, `BUILD SUCCESSFUL`, `GRADLE-DEMO-OK` ✓
- Node smoke: `outcome=success`, `NODE-DEMO-OK`, `tests 2` ✓
- Gradle FAIL: `outcome=failure`, `failureKind=SCRIPT`, `code 1` ✓

## 6. GitHub Release

- **URL**: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0
- **isDraft**: false
- **isPrerelease**: false
- **publishedAt**: 2026-09-19T09:28:28Z
- **targetCommitish**: `951b3cb5695ecc46c877776e330266e4bd44aa9e`
- **Assets (4)**:
  - `pipelinek-0.39.0.zip` (91,416,100 bytes, sha256 `385b140c…cbb8`)
  - `pipelinek-0.39.0.zip.sha256`
  - `pipelinek-0.39.0.sbom.json` (CycloneDX 1.5, 39 components, sha256 `d8e6549c…dcf6`)
  - `release-manifest.json` (sha256 `6acb34d5…af8d`)

## 7. Gate criteria

- [x] Canonical artifact reproducible (A == B)
- [x] SHA-256 match across local build, certified, downloaded from GitHub
- [x] `pipelinek version` reports the certified version (no SNAPSHOT fallback)
- [x] 4 real-fixture smokes PASS (Gradle success, Maven success, Node success, Gradle fail)
- [x] All round-gate defects closed (SB-S-008, SB-S-010, CR-BD-027, CR-U9, WULpr010)
- [x] Single-version provider in `v2/build.gradle.kts` (root authority)
- [x] Fail-closed `Main.version` (exits 3 if manifest has no `Implementation-Version`)
- [x] Legacy `0.1.0-SNAPSHOT` fallback removed
- [x] `--workspace` CLI regression fixed
- [x] GitHub Release PUBLIC, target = certified commit
- [x] Tag `v0.39.0` annotated and pushed
- [x] Trunk `main` clean (16 untracked witness bundle preserved)

## 8. Outstanding

The release is closed on the GitHub Release channel. The SDKMAN channel has three sequential steps; the first is script-ready, the next two are blocked on vendor credentials and a clean runner.

- [ ] **SDKMAN publish** (`pipelinek` candidate on https://vendors.sdkman.io/release) — blocked on `SDKMAN_CONSUMER_KEY` + `SDKMAN_CONSUMER_TOKEN` (vendor account `Rubentxu`). Publish script committed at `e9494f7d` (`scripts/release/sdkman-publish.sh`): bash-validated, fail-closed without credentials, real SDKMAN endpoint verified (403 on dummy creds). Idempotent; does NOT promote to default.
- [ ] **SDKMAN install UAT** (`sdk install pipelinek 0.39.0` on a clean runner) — gated by publish. Script committed at `22021085` (`scripts/release/sdkman-install-uat.sh`): bash-validated, fail-closed without `sdk` CLI (exit 1). Validates `pipelinek version` / `doctor` / `validate` / `run` end-to-end on the SDKMAN-installed binary. **Mandatory gate: a failed UAT is a hard STOP, no promotion to default.**
- [ ] **SDKMAN promote to default** (PUT `https://vendors.sdkman.io/default`) — gated by UAT. Only after the UAT passes is `0.39.0` made the default candidate version. A one-line curl; not yet scripted because the UAT is the load-bearing check.
- [ ] Self-hosting via root `/pipeline.kts` from `v0.39.0` onwards — bootstrap exception retired.

## 9. Receipts and artifacts

- `docs/v2/07-uat/WU_LPR_071_RELEASE_PREP_RECEIPT.md` — closure of round-gate defects + workspace fix
- `docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md` — certification ledger digest referenced by the manifest
- `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md` — this receipt
- `scripts/release/sdkman-publish.sh` — SDKMAN vendor publish script (e9494f7d)
- `scripts/release/sdkman-install-uat.sh` — SDKMAN install UAT (publish → UAT → default gate) (22021085)
- `scripts/release/sbom-cyclonedx.py` — CycloneDX 1.5 SBOM generator (stdlib-only)
- GitHub Release assets: `pipelinek-0.39.0.zip`, `.sbom.json`, `.zip.sha256`, `release-manifest.json`

## 10. Provenance summary

```
git rev-parse HEAD
  e9494f7d5ea586251824acd9902bb5080f953423  (SDKMAN publish script added)

git rev-parse v0.39.0
  be3b1b42ee72c9b60f08f7d6ac5abe176caad61b (annotated tag object)

git rev-parse v0.39.0^{commit}
  951b3cb5695ecc46c877776e330266e4bd44aa9e (certified commit)

pipelinek-0.39.0.zip SHA-256
  385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8

GitHub Release URL
  https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0

Commits on main this release
  cce9b3ab  fix(release-prep): close round-gate defects
  bbe916fd  docs(lpr): release prep handoff + receipt
  88b26b81  feat(release): release workflow
  fb2ed28d  feat(release): RC SBOM generator + release artifacts ignored
  951b3cb5  fix(release): --workspace . was nesting under project root  ← CERTIFIED
  68ba01ab  chore(release): bump to 0.39.0 (cosmetic; ZIP bytes unchanged)
  320199fb  docs(lpr): LPR-GATE-1 closure receipt
  e9494f7d  feat(release): SDKMAN publish script (fail-closed)
  22021085  docs(lpr): receipt refresh — record SDKMAN publish script (e9494f7d)
  <next>    feat(release): SDKMAN install UAT script (publish → UAT → default gate)

---

**Signed off**: WU-LPR-071 (2026-09-19). Local Production Ready (LPR-GATE-1) **CLOSED** for `pipelinek 0.39.0` on the GitHub Release channel. SDKMAN channel: publish script (e9494f7d) and UAT script (next commit) ready; SDKMAN publish + UAT + default promotion pending vendor credentials and clean runner.
