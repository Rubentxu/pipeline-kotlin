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
| Commit (HEAD) | `a95956b2...` (see `git-head.txt`) |
| Branch | `pk-release-0-57-consolidation` |
| Base | `main` HEAD `05ce6236` (post-merge v0.56.0-rc5) |
| Tree state at candidate build | clean (verified by candidateAdmission) |
| Audit response | The audit of 12c3ac17 (R1.1, R1.2, R1.3, R1.4) is closed in this commit |

## Artefacts in this directory

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.57.0.zip` | 88.5 MB | `0a29d5231be03cfb6b086c6bbd2820b7849a15b5e40fb3e36f2458adb1f40d1c` |
| `pipelinek-0.57.0.sbom.json` | 1.3 MB | `478f65981ee4167c5bee77ebb4560e62d5861dbffc5ed4f2ef6d65bd39c39131` |
| `pipeline-sdk-bom-0.57.0.pom` | — | `54228c13c7aeb58ca17d6143563a4c045eb5401b84e64f728f1ae3c8b10affc7` |
| `pipeline-domain-0.57.0.pom` | — | `232ebec04314e454da26acdd20fec168e86f1f0f1353fbaac8dcd87bac7b038d` |
| `sdk-domain-0.57.0.module` | — | `10c91cb218c1e67dddaa9d0c4e55b4ba64f810bc119b341f4731c9a296a007c0` |
| `pipeline-application-0.57.0.jar` | — | `45e174147e48173f540dee9e7bffc51230d29091c85cf7ea7f56f0cc6f76143b` |
| `git-head.txt` | — | `b0cbe591e5329bddbf2f2a1703b454a247a1648b10d6d53ce59f0967a9175b42` |
| `HANDOFF-TO-CERTIFIER.md` | — | `d4e1102e5eba9595d31a88602c2fd4479cc44c42e00bd503c4b17b3488239212` |

`SHA256SUMS` aggregates all of the above.

## Audit response (R1.1, R1.2, R1.3, R1.4 closed in this commit)

The audit of commit `12c3ac17` flagged four defects; all four are
closed in this bundle:

- **R1.1 (gate wiring race, P0)**: `PIPELINEK_DIST_ZIP` is now
  attached to the `:pipeline-release:test` task's OWN configuration
  with a Provider that points at the distZip output. The previous
  wiring set the env var in `releaseGate.doFirst`, which arrived too
  late (the test task had already forked). Verified by running
  `:pipeline-release:test` with `PIPELINEK_DIST_ZIP` unset in the
  parent env — the test graph now picks up the configuration
  before the JVM forks, and `candidateAdmission` runs.
- **R1.2 (catalog vs compatibility, P0)**: `DistributionMandatoryPluginsTest`
  now detects duplicate `pluginId` (a structural check, not a set
  equality). Two JARs shipping the same `pluginId` is a hard RED;
  the test names the offending identity.
- **R1.3 (no-op `assertNotNull(boolean)`, P0)**: the rc3 reproducer
  had `assertNotNull(refused.render().contains(...), msg)`, which
  checks the boxed reference (always non-null) and discards the
  boolean. The assertion was a no-op. Replaced with `assertTrue`.
  The rc3 reproducer still passes against the real rc3 ZIP, with
  the boolean now actually exercised.
- **R1.4 (host version literal, P0)**: the host identity
  (`0.56.0`, re-anchored to `0.57.0` in T2b) was a literal in the
  test. The fix derives the version from the ZIP filename via a
  regex, so a drift between the declared version and the source
  is now detectable by the test itself.

**Audit points still open** (with honest status, not closed):

- **H1.5 (full `./gradlew check`)** — deferred, blocked by host
  load (12-15, above the < 5 limit documented in `BLOCK_E_RUN_21`).
  Operator authorised running the critical suite only.
- **R3 (WURp023 and UatS2R0 retry)** — same load constraint blocks
  repeatable measurement.
- **R4 (merge to `main` before tag)** — depends on R3 and on
  operator-authorised merge.
- **R5 (independent certification)** — certifier has not run the
  13 R0.5 scenarios. This handoff is the input to that cycle.
- **P1 — example plugins as part of the mandatory catalog**: the
  audit noted that 4 official + 3 examples are different
  categories. The 0.57 train bundles all seven, but the
  "mandatory" boundary could be tightened in a future ADR: the
  four official plugins are the product's contract; the three
  examples are independent consumers that close the D1 loop.
  Documented here so the certifier can decide whether the
  seven-plugin gate is the right gate for 0.57 or only the
  four-official gate is.
- **P1 — semantically ambiguous `LaunchReceipt` in non-durable
  mode**: the audit's observation is real (the in-memory path
  emits `Launched` without a journal). The fix is a separate ADR
  that explicitly differentiates durable and non-durable
  `Launched`. Out of scope for this bundle.

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
