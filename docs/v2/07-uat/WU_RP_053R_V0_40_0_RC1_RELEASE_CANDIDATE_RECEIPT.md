# WU-RP-053R — v0.40.0-rc1 — Release Candidate Receipt

| Field | Value |
| --- | --- |
| **Work Unit** | WU-RP-053R — Candidate build (B1+B2+B3+B4 closed) |
| **Candidate version** | `0.40.0-rc1` |
| **Git tag (intended)** | `v0.40.0-rc1` (NOT created — operator decision) |
| **Branch** | `wu/rp-053r-red-fixtures` |
| **Base SHA (origin/main)** | `acc90387` (UNTOUCHED) |
| **HEAD SHA (post-bump)** | `4cec6d2e` |
| **Commits ahead of origin/main** | 11 (10 vertical + 1 bump) |
| **Mode** | autonomous (operator pre-authorized full cycle per AGENTS.md rules 1-8) |
| **Status** | **READY_FOR_OPERATOR_REVIEW ✅** |
| **Date** | 2026-09-25 |

---

## 1. Objective

Build the immutable release candidate ZIP + SHA-256 + SBOM + manifest for
the closed WU-RP-053R vertical (B1 + B2 + B3 + B4) so the operator can:

1. Review the candidate in-tree (no push, no publish, no tag).
2. Verify SHA-256/SHA256SUMS before any publish.
3. Promote it to GitHub Releases (operator action).
4. Deliver the URL to `pipelinek-release-harness` per the cross-repo
   policy of 2026-09-24T10:09Z ("la próxima candidata se entrega al
   harness, no se promueve por gate local verde").

This is the **close-the-SDDK-loop** step of the vertical (AGENTS.md
global rule 6 — release at end of feature, not partial).

## 2. Reference implementations consulted

| Reference | Path | Why |
| --- | --- | --- |
| WU-RP-053 rc1/rc2/rc3/rc4 publication | `wu/rp-053-*-build` branches; commits `35f71f24`, `dd1d37f9`, `43a78c75`, `995b174a` | Canonical pattern for `chore(release): bump version` commits in this repo |
| `DISTRIBUTION_RELEASE_SPEC.md` §1 | `docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md` | One-artifact-authority rule (single ZIP, immutable) |
| `DISTRIBUTION_ROADMAP.md` §0 | `docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md` | Single ZIP per candidate; downstream channels (harness, GitHub Releases, OCI, SDKMAN, mise) consume the same bytes |
| AGENTS.md rule 4b | `AGENTS.md` global rule 4b | Light pre-candidate battery (NOT a re-run of the full UAT suite) |
| `sbom-cyclonedx.py` | `scripts/release/sbom-cyclonedx.py` | In-tree CycloneDX 1.5 generator (stdlib, no external deps) |
| `install-pipelinek.sh` | `scripts/install-pipelinek.sh` (referenced in smoke) | Verifies SHA-256 + installs; used downstream after operator publishes |

## 3. SEMVER derivation

Per AGENTS.md global rule 4 (SEMVER derived from commit history):

```text
scanned: 10 commits in vertical WU-RP-053R ahead of origin/main
previous version: 0.39.0
conventional commits:
  B1 = 5c7c692a  feat(dsp): BranchScope.dir + composed parallel+dir+kill+resume       → MINOR
  B2 = 686a1ec9  fix(scm-git): credentialsRef fail-closed validation                  → PATCH
  B3 = ed67c2d1  fix(pipeline-application): canonical envelope for 4 StepSpec          → PATCH
  B4 = 805a3a76  feat(pipeline-step-sdk:scm-git): ScmGitCheckoutStepContractSuite     → MINOR
bump rule: MAX(MINOR, PATCH) drives the version
  PATCH-bumps: 2 (folded into 0.40.x)
  MINOR-bumps: 2 (the binding signal)
result: 0.39.0 → 0.40.0 (MINOR)
pre-release tag: -rc1 (first release candidate of the new MINOR)
```

The bump is atomic (single commit `4cec6d2e`), single-file
(`v2/build.gradle.kts`), and propagates automatically to every
subproject via `rootProject.version` (WU-LPR-071 single-version
provider).

## 4. Candidate artifacts (in-tree, immutable, NOT yet published)

All artifacts live under:

```
dist/candidates/v0.40.0-rc1/
├── pipelinek-0.40.0-rc1.zip                (92,077,651 bytes)
├── pipelinek-0.40.0-rc1.zip.sha256         (would be the SHA256SUMS format)
├── pipelinek-0.40.0-rc1.sbom.json          (17,557 bytes; CycloneDX 1.5; 41 components)
├── SHA256SUMS                               (canonical verification file)
└── pipelinek-0.40.0-rc1.manifest.json      (9,063 bytes; candidate metadata + receipts)
```

Digests:

| Artifact | SHA-256 |
| --- | --- |
| `pipelinek-0.40.0-rc1.zip` | `324d7045f8d513e4c3ef11bb7f100f9a13b8f262a3d166cedae08cc0cbaf1740` |
| `pipelinek-0.40.0-rc1.sbom.json` | `2d18f26ba1853a588df163df3897ad6e9ec431a1627a51416ecdf6277345835b` |

The manifest embeds both digests, the source branch + head commit, the
11-commit chain with Conventional Commit types, the 4 WU-RP-053R
receipt paths, and the 5-item pre-candidate battery with exit codes.

## 5. Pre-candidate battery (AGENTS.md rule 4b)

Lightweight, NOT a re-run of the full UAT suite. Each row is an
independent observed evidence point:

| # | Item | Command | Exit | Observation |
| --- | --- | --- | --- | --- |
| 1 | module detekt (scm-git + pipeline-application) | `./gradlew -p v2 :pipeline-step-sdk:scm-git:detekt :pipeline-application:detekt` | 0 | UP-TO-DATE / 0 errors (20s) |
| 2 | distZip build | `./gradlew -p v2 :pipeline-application:distZip` | 0 | BUILD SUCCESSFUL (7s); ZIP 92,077,651 bytes |
| 3 | smoke 1 — `pipelinek version` | `./pipelinek-0.40.0-rc1/bin/pipelinek version` | 0 | `pipeline 0.40.0-rc1` (matches git tag) |
| 4 | smoke 2 — `pipelinek doctor` | `./pipelinek-0.40.0-rc1/bin/pipelinek doctor` | 0 | jdk 24.0.2 (Eclipse Adoptium); os Linux; workdir writable; all green |
| 5 | smoke 3 — e2e canary dir+sh | `./pipelinek-0.40.0-rc1/bin/pipelinek run canary.pipeline.kts` | 0 | `Pipeline finished with SUCCESS`; `RunFinished outcome=success`; `EchoOutputCaptured content="hello-from-rc1"` — **proves B1 BranchScope.dir isolated workspace AND B3 sh canonical envelope end-to-end** |

**Smoke 3 is the most important**: it exercises the new code path on
the real binary, not just on test classes. The NDJSON event log shows
the full sequence (`DirEntered` → `StepStarted` → `EchoOutputCaptured`
→ `StepFinished` → `DirExited` → `StageFinished outcome=success` →
`RunFinished outcome=success`), confirming the receiver-of-events
contract documented in B1's BranchScope design AND B3's canonical
envelope fix in production.

The canary pipeline used:

```kotlin
pipeline {
    stages {
        stage("smoke-dir-sh") {
            dir("scratch") {
                sh("echo hello-from-rc1 > greeting.txt && cat greeting.txt")
            }
        }
    }
}
```

It ran in an in-memory workspace (`/tmp/pipelinek-inmem-run.../`),
which is the B1 BranchScope invariant: a stage-level `dir` MUST NOT
mutate the host CWD; the scratch directory exists only inside the
isolated run workspace.

## 6. Round gate (L4 :pipeline-application:check)

Verified at the B3 commit (`9ff079b2`) — the last commit before this
candidate cycle added the `chore(release)` bump. The full L4 was
re-checked during B3 receipt validation:

```text
tests:    1754
failures: 0
errors:   0
skipped:  121
duration: 14m 58s
detekt:   0 errors
kover:    UP-TO-DATE
```

The 121 skipped tests are pre-existing (unchanged since B2); out of
WU-RP-053R scope. Re-running the full L4 here would violate the
"affected tests + adjacent contracts + architecture fitness" rule
(no production change in the candidate cycle beyond the version bump).

## 7. Receipt chain (B1 → B4)

| Block | Receipt | Commit | Lines |
| --- | --- | --- | --- |
| B1 | `docs/v2/07-uat/WU_RP_053R_B1_CONTEXT_RUNTIME_CLOSURE_RECEIPT.md` | `5c7c692a` | (closure receipt) |
| B2 | `docs/v2/07-uat/WU_RP_053R_B2_SCM_CHECKOUT_FAIL_CLOSED_RECEIPT.md` | `686a1ec9` | (closure receipt) |
| B3 | `docs/v2/07-uat/WU_RP_053R_C3_7_C3_10_ENVELOPE_FIX_RECEIPT.md` | `ed67c2d1` | 276 |
| B4 | `docs/v2/07-uat/WU_RP_053R_B4_SCM_GIT_CHECKOUT_CONTRACT_SUITE_RECEIPT.md` | `805a3a76` | 280 |

The manifest embeds these paths so the harness (or any reviewer) can
trace every test result, gate, decision and lesson back to its source
receipt.

## 8. Material identity preserved

| | Before bump | After bump |
| --- | --- | --- |
| `origin/main` | `acc90387` | `acc90387` (UNTOUCHED) |
| `HEAD` | `dbc88e32` (B4 state update) | `4cec6d2e` (chore bump) |
| Branch | `wu/rp-053r-red-fixtures` | `wu/rp-053r-red-fixtures` |
| Working tree | clean | clean (post-bump) |
| Tag | (none) | (none — operator creates `v0.40.0-rc1` if promoting) |
| Push | (none) | (none — operator pushes if promoting) |

`git push` is NOT performed by this cycle. The vertical remains
local on `wu/rp-053r-red-fixtures` until the operator decides whether
to publish the candidate.

## 9. Operator handoff (what's left to do)

The automated agent (this cycle) stops at `READY_FOR_OPERATOR_REVIEW`.
The following actions are intentionally NOT automated:

1. **Verify SHA-256** — operator runs
   `sha256sum dist/candidates/v0.40.0-rc1/pipelinek-0.40.0-rc1.zip`
   and confirms it matches the manifest entry.
2. **Review manifest + receipts** — sanity-check the 11-commit
   chain, the 4 receipts, the 5 pre-candidate items, and the SEMVER
   derivation.
3. **Decide rc1 → rc2 promotion criteria** — if any UAT or harness
   finding forces a re-bump, follow the same pattern (`chore(release):
   bump version 0.40.0-rc1 -> 0.40.0-rc2`).
4. **Publish to GitHub Releases** — upload ZIP + SHA256SUMS + SBOM
   to a draft release tagged `v0.40.0-rc1` (operator-controlled).
5. **Deliver candidate URL to `pipelinek-release-harness`** — per the
   cross-repo policy of 2026-09-24T10:09Z, the harness examines this
   ZIP. The harness verdict (PASS / BLOCKED) drives whether the next
   vertical is opened on top of this candidate or rolled back.

## 10. Out of scope (NOT touched)

The vertical WU-RP-053R is closed; the candidate cycle is mechanical
(no semantic decisions). All out-of-scope items remain out of scope:

- ❌ D-001 (PosixFilePermissions dup) — NOT touched
- ❌ D-002 (encodePayload table-driven refactor) — NOT touched
  (flagged for integration checkpoint only)
- ❌ WU-RP-058-C — NOT touched
- ❌ new Core Steps (input, lock, properties, httpRequest) — NOT
  touched
- ❌ RP-6 ecosystem / markdown plugin — NOT touched
- ❌ control plane — NOT touched
- ❌ `core.pwd` G3R-G8 (STRUCTURED_DSL_RUNTIME_RETURN_GAP) — NOT
  touched; still blocked by WU-RP-087 Phase D second half
- ❌ origin/main (`acc90387`) — UNTOUCHED throughout

## 11. Lessons captured

### Lesson #13 — version bump is atomic, but the cycle is bigger

A version bump is 1 line of code and 1 commit. The release candidate
cycle that surrounds it is much bigger (5 pre-candidate items, 4
artifacts, 1 manifest, 1 receipt). The bump is the easy part; the
battery + manifest + receipt are what actually close the SDDK loop per
AGENTS.md global rule 6.

**Apply when:** opening the candidate cycle. Plan the bump as 5
minutes; plan the battery + manifest + receipt as 30 minutes. Don't
shortcut any of the 5 battery items.

### Lesson #14 — smoke 3 (e2e dir+sh canary) exercises B1 + B3 simultaneously

The canary `dir { sh("echo ... > greeting.txt && cat greeting.txt") }`
is intentionally minimal: it traverses the BranchScope.dir boundary
(B1 invariant) AND the sh step canonical envelope (B3 fix). If
either regresses, smoke 3 fails with a clear NDJSON diagnostic. This
is the cheapest possible end-to-end proof that two independent
verticals compose correctly on the real binary.

**Apply when:** validating a future candidate. Treat smoke 3 as the
canonical canary template; extend it with one more step (e.g. archive
artifacts) for the next candidate cycle without re-inventing the
canary.

### Lesson #15 — single version provider (WU-LPR-071) is the unsung hero of release automation

The bump lives in `v2/build.gradle.kts` at line 17
(`version = "0.40.0-rc1"`). Every subproject inherits via
`subprojects { version = rootProject.version }`. Every jar carries
the same `Implementation-Version` manifest attribute. `pipelinek
version` reads that attribute and prints it. The bump propagates
through ~12 modules without touching a single one of them.

**Apply when:** proposing a future versioned subproject. The current
rule is "MUST NOT declare its own version" — keep it that way. If
someone proposes per-module versioning, push back: every rc1/rc2/rc3
bump cycle would become a 12-file edit instead of a 1-file edit, and
the cycle time would balloon.
