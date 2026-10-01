# Candidate 0.45.0 — producer certification and handoff

```yaml
id: WU-RP-034-CANDIDATE
status: PASS
release_train: 0.45.0
candidate_sequence: 1
candidate_id: sha256:bd166619f2cdd5b2e083102977b9cacedaaab6a4be06b5d00765711abfab1176
product_version: 0.45.0
source_commit: a277d67acba771c2f2999eb2bb0f2b7ae9c34a22
producer_gate_sha: e01323d835222d946f20ae3fda8644af9de96588
artifact: pipelinek-0.45.0.zip   (92,363,248 bytes)
```

## Provenance boundary

| commit | role |
|---|---|
| `e01323d8` | last SHA on which `./gradlew check` ran green: **4058 tests, 0 failures, 0 errors, 130 skipped, 21m 32s** |
| `21c3aaf0` | docs only — scope divergence reconciliation receipt |
| `a277d67a` | carries forward a stale agent projection file that the provenance law correctly refused to build over |

Neither post-gate commit touches a single byte of the distribution. That is
demonstrated, not asserted: the ZIP digest was captured **before** `21c3aaf0`
and `a277d67a` and is byte-identical to the one produced after them.

## Producer gate

`./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=1`

```text
candidate admission PASSED
  candidate_id: sha256:bd166619f2cdd5b2e083102977b9cacedaaab6a4be06b5d00765711abfab1176
  provenance: source provenance VERIFIED: clean tree at a277d67acba771c2f2999eb2bb0f2b7ae9c34a22
```

The admission **refused** the first attempt, correctly: the tree carried an
uncommitted change to a tracked file, and a manifest naming a commit that does
not contain the compiled content would have been a false provenance record.
That refusal is the law working.

## Version coherence — all five surfaces

| surface | observed | how |
|---|---|---|
| PRODUCT_VERSION | `0.45.0` | declared in `v2/build.gradle.kts`, sole authority |
| ASSET | `0.45.0` | `pipelinek-0.45.0.zip` — producer probe |
| ARCHIVE_ROOT | `pipelinek-0.45.0` | producer probe |
| IMPLEMENTATION_VERSION | `0.45.0` | JAR manifest, producer probe |
| RUNTIME_VERSION | `pipeline 0.45.0` | `pipelinek version` on the extracted candidate |
| MANIFEST_VERSION | `0.45.0` | `Implementation-Version` in the extracted application JAR |

The producer probe reports `identity INCOMPLETE` for `RUNTIME_VERSION` and
`MANIFEST_VERSION`. **That is by design, not a defect**: `probeZip` is a
ZIP-only probe covering three surfaces, and the two remaining ones require the
binary to be installed and executed — which is exactly the harness's job. Both
were verified directly here and both report `0.45.0`.

Recorded honestly: the producer gate is blind on two of five surfaces by
construction, so those are corroborated manually here and remain the harness's
independent check.

## Reproducibility

Two independent builds of `pipelinek-0.45.0.zip` — one before the two
post-gate commits, one after deleting the ZIP and rebuilding — produced the
identical digest `bd166619…`. Reproducible.

## Installed UAT re-run on the exact candidate bytes

The four earlier scenarios were re-run against the **extracted candidate ZIP**,
not against a rebuilt `installDist`, so no result from a different digest is
counted.

| id | scenario | result |
|---|---|---|
| UAT-CAND-01 | real Gradle `classes`, local-first default | exit 0, `build/classes/java/main/demo/App.class` produced |
| UAT-CAND-02 | real Maven `compile`, local-first default | exit 0, `target/classes/demo/App.class` produced |
| UAT-CAND-03 | real `npm run build`, local-first default | exit 0, `dist/bundle.js` produced with expected content |
| UAT-CAND-04 | self-hosting + workspace/cwd semantics | exit 0, see below |

Semantic proof from UAT-CAND-04, observed on the candidate binary:

```text
A_INVOCATION_DIR_WINS          nested run attaches the invocation directory
B_SCRIPT_DIR_IS_NOT_WORKSPACE  the script's own directory does not define it
C_WORKSPACE_OVERRIDES           --workspace nested moves the effects
dir("nested") pwd -> /tmp/cand-uat/s/nested
after the block     -> /tmp/cand-uat/s      (workspace root restored)
```

This is the law WU-RP-034 exists to enforce, now observed end-to-end through
the certified bytes.

## Handoff delivered

```text
pipelinek-release-harness/inputs/dogfood/0.45.0/
  pipelinek-0.45.0.zip          (sha256 verified after copy)
  candidate-handoff.json
  distribution-manifest.json
  candidate.sha256
```

`sbom` is `null` in the handoff. No SBOM was produced by this producer path;
that is recorded rather than substituted with a placeholder.

## Not done — and why

- **No `git tag v0.45.0`, no GitHub Release, no stable promotion.** SDDK policy
  declares `git.tag` and `git.release` as `human_gate` under `system-law`, and
  per the release model the harness must first certify these exact bytes.
- **No use of `sddk release plan` or `sddk release dist`.** `plan/apply` abort
  on a `Cargo.toml` version-lockstep read that this Kotlin/Gradle repository
  does not have; `dist` packaged the `sddk` binary itself (version 2.4.2)
  rather than anything from this project. No `Cargo.toml`, `Cargo.lock` or shim
  was created to satisfy it.
- **The harness has not yet certified.** The candidate is delivered and waiting;
  `main` is free to continue with independent work per the fast-lane candidate
  law.

## SDDK deficiencies found, recorded not worked around

1. **The governance gate cannot represent a completed queue.** With every
   roadmap item terminal, `plan roadmap next` returns `SpineComplete` with a
   non-zero exit; `git sddk-align` treats that as fatal and exits 4 before
   writing the report it must produce, and `sddk_current_work_item` has nothing
   to derive — so `sddk-align --ack` answers `Run 'git sddk-align' first`
   forever. **The commit path locks exactly when the team finishes its work.**
   Resolved here by declaring the next cycle, which is the flow SDDK models;
   the underlying hook limitation is not repaired, because the hooks are shared
   infrastructure outside this repository and changing them for one project
   would be the wrong blast radius.
2. **The reactive graph does not project planning work items**, so
   `sddk graph why` cannot trace one even though `plan work-item show` resolves
   it fine.
3. **The release planner is not multistack**: it assumes a Cargo workspace, so
   it cannot plan or package a Gradle project at all.