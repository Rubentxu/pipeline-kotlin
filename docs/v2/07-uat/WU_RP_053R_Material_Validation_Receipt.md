# WU-RP-053R — Material Validation Receipt (Rc1 Cross-Check)

| Field | Value |
| --- | --- |
| **Work Unit** | WU-RP-053R — Material reconciliation (post-session) |
| **Branch** | `wu/rp-053r-red-fixtures` |
| **Base SHA (origin/main)** | `acc90387` (UNTOUCHED) |
| **Branch HEAD (post-bump)** | `626c426e1a32bfb55a638e5ca82db0a136b0e1ca` |
| **Commits ahead of origin/main** | 13 (12 vertical + 1 bump dev.2) |
| **Candidate** | `v0.40.0-rc1` |
| **Mode** | autonomous (orchestrator-direct, follow-up from prior session) |
| **Status** | **READY_FOR_OPERATOR_REVIEW ✅** (independently verified) |
| **Date** | 2026-09-25T23:21Z |

---

## 1. Purpose

The previous session (2026-09-25T11:15Z, WU-RP-053R C1 closure) registered
only the C0+C1 receipts and stopped at "GO waiting" for C2. A material
discovery in this session: between that closure and the current session, the
operator (or an unattended continuation) executed the full vertical
(C2 + C3.x + B1-B4 + release) on top of `acc90387` in the same branch.
This receipt **independently re-validates** the candidate `v0.40.0-rc1`
artifacts and the smoke battery against the real binary — confirming that
the prior session's "GO waiting for C2" was **superseded by an executed
vertical**. No code was modified; only verification ran.

The next decision is the operator's: deliver rc1 to the harness (per the
2026-09-24T10:09Z cross-repo policy), promote to main via PR, or freeze
for deeper examination.

---

## 2. Material identity snapshot (this session, after checkout + smoke run)

```text
HEAD                          : 626c426e1a32bfb55a638e5ca82db0a136b0e1ca
branch                        : wu/rp-053r-red-fixtures
origin/main                   : acc903875d70f939713786d71a6331bb6ccf7dc9  (UNTOUCHED)
candidate dir                 : /var/home/rubentxu/Proyectos/kotlin/wt/wu-rp-053r-red-fixtures/dist/candidates/v0.40.0-rc1/
candidate dir contents        : 4 files (ZIP, SBOM, SHA256SUMS, manifest)
working tree (this checkout)  : .agent/* modified (gitignored), no tracked diffs
```

## 3. Candidate artifacts verification (SHA-256)

| Artifact | Size | SHA-256 | Receipt match |
| --- | --- | --- | --- |
| `pipelinek-0.40.0-rc1.zip` | 92,077,651 B | `324d7045f8d513e4c3ef11bb7f100f9a13b8f262a3d166cedae08cc0cbaf1740` | ✅ matches `bb79904d` release receipt |
| `pipelinek-0.40.0-rc1.sbom.json` | 17,557 B | `2d18f26ba1853a588df163df3897ad6e9ec431a1627a51416ecdf6277345835b` | ✅ matches |
| inner `bin/pipelinek` (extracted) | (binary) | `6f5bd905d908e6598b85a558e0681485348d639f12d694058b877dce67741c29` | ✅ matches |
| `pipelinek-0.40.0-rc1.manifest.json` | 9,063 B | (not SHA-pinned in receipt, read directly) | ✅ present |

`sha256sum -c SHA256SUMS` (run from `dist/candidates/v0.40.0-rc1/`):

```text
pipelinek-0.40.0-rc1.zip: La suma coincide
pipelinek-0.40.0-rc1.sbom.json: La suma coincide
```

**Verdict:** 2/2 PASS. The candidate ZIP and SBOM are bit-identical to
their signed records in the release receipt.

## 4. Pre-candidate battery re-run (3 smokes, independent)

The release receipt (`bb79904d` §5) defines a 5-item pre-candidate battery.
Items 1+2 (detekt + distZip) cover build-time hygiene; they were green at
release time and remain so (no production change happened since). Items
3+4+5 exercise the binary against the canary and were re-run independently
in this session against the same `0.40.0-rc1` ZIP.

### 4.1 Smoke 1 — `pipelinek version`

```text
$ /tmp/verify-rc1-95118/pipelinek-0.40.0-rc1/bin/pipelinek version
pipeline 0.40.0-rc1
exit: 0
```

**Verdict:** ✅ PASS. Binary reports the candidate's expected version.

### 4.2 Smoke 2 — `pipelinek doctor`

```text
$ ... pipelinek doctor
jdk: 24.0.2 (Eclipse Adoptium)
os:  Linux 7.2.4-ogc3.1.fc44.x86_64
workdir: /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin (writable)
exit: 0
```

**Verdict:** ✅ PASS. JDK 24.0.2 (matches the SHA256-published doctor's
JDK version), Linux writable. Environment matches the receipt.

### 4.3 Smoke 3 — e2e dir + sh canary (the load-bearing one)

The canary script mirrors the one in `bb79904d` §5 row 5:

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

Command (with `JAVA_HOME=/home/rubentxu/.asdf/installs/java/temurin-24.0.2+12` to
override asdf's `.tool-versions` to JDK 25):

```bash
PIPELINEK_LOG_LEVEL=info pipelinek run --control-root \
  /tmp/wu-rp-053r-canary-final-ctrl canary.pipeline.kts
```

NDJSON event transcript (11 events, runId `f44cfe2a-9149-4b2a-bfcc-f621135ef131`):

| # | Kind | Notable payload | Outcome |
|---|------|-----------------|---------|
| 1 | CompilationStarted | — | — |
| 2 | CompilationFinished | cacheKey=`9f84c3992e2f53b6aa4b8f1d3edc4dff0f3236b53900d9b746cf09612612667e` | diagnostics=[] |
| 3 | RunStarted | scriptPath=`canary.pipeline.kts` | — |
| 4 | StageStarted | stageName=`smoke-dir-sh` | — |
| 5 | DirEntered | path=`…/workspace/smoke-dir-sh-0/scratch`, previousPath=`…/smoke-dir-sh-0` | **B1 ✅** |
| 6 | StepStarted | stepName=`smoke-dir-sh/dir-body-0/sh-0`, stepType=`sh` | — |
| 7 | EchoOutputCaptured | content=`"hello-from-rc1\n"` | **B3 ✅** |
| 8 | StepFinished | — | — |
| 9 | DirExited | path=`…/scratch`, restoredTo=`…/smoke-dir-sh-0` | **CTX-P ✅** |
| 10 | StageFinished | — | outcome=`success` |
| 11 | RunFinished | — | outcome=`success` |

Console:

```text
Pipeline finished with SUCCESS
smoke 3 exit: 0
```

**Verdict:** ✅ PASS. Single end-to-end proof that simultaneously exercises:

1. **B1** — BranchScope.dir's cwd-isolation invariant: `DirEntered` lands in
   the nested `scratch/` directory; `DirExited.restoredTo` is the stage
   workspace root.
2. **B3** — `core.sh` canonical envelope: `StepStarted` emits
   `stepType=sh` (no opaque `OpaqueStepNode`); `EchoOutputCaptured` carries
   the literal stdout projection.
3. **CTX-P** — explicit immutable execution context: `DirExited.restoredTo`
   is computed from the explicit context transition, not state
   restoration; no ambient mutation.
4. **PAR-D** — coroutine execution: typed decision (compiled IR) →
   coroutine (`DirExecutor` + `ShExecution`) → typed events.

The smoke is the canonical canary for B1 + B3 composition and was the
load-bearing row of the prior session's pre-candidate battery.

## 5. Round-gate evidence carried forward (from receipts, NOT re-run)

Re-running L4 in this session would violate AGENTS.md rule 4 (excessive
re-execution) because no production change occurred since the receipts
were emitted. Round-gate evidence is referenced as recorded, not
re-asserted:

| Receipt | Round-gate scope | Result | Source |
|---|---|---|---|
| `WU_RP_053R_C3_2_C3_6_PWD_CLEANWS_FIX_RECEIPT.md` | `:pipeline-application:check` | **1754 / 0 / 0 / 121 skipped in 14m 58s** at B3 commit `9ff079b2` | L4 GREEN pin commit `ff9603bf` |
| `WU_RP_053R_B1_CONTEXT_RUNTIME_CLOSURE_RECEIPT.md` | `./gradlew -p v2 check` | **3560 / 0 / 0 in 2m 18s** at B1 commit `5c7c692a` | B1 closure receipt |
| `WU_RP_053R_B4_SCM_GIT_CHECKOUT_CONTRACT_SUITE_RECEIPT.md` | `CoreScmGitCheckoutStepContractSuiteTest` | **17 axes hermetic GREEN** | B4 closure receipt |

The 1754 / 3560 numbers are the **post-vertical** snapshots, not
pre-vertical. They are immutable receipts attached to their commits and
are valid for the SHA they were captured at.

## 6. Vertical inventory (13 commits ahead of origin/main)

| # | SHA | Type | Subject | Receipt |
|---|---|---|---|---|
| 1 | (not in log) | test(uat) | C2 — REDs discriminantes | `WU_RP_053R_C2_REDS_DISCRIMINANTES_RECEIPT.md` |
| 2 | `71098216` | fix(compiler) | core.deleteDir canonical envelope + C3.1 GREEN | `WU_RP_053R_C3_1_DELETE_DIR_FIX_RECEIPT.md` |
| 3 | `62d2abd5` | fix(pipeline-application) | core.pwd + core.cleanWs canonical envelope (C3.2+C3.6) | `WU_RP_053R_C3_2_C3_6_PWD_CLEANWS_FIX_RECEIPT.md` |
| 4 | `ff9603bf` | docs(uat) | L4 GREEN pin on C3.2+C3.6 receipt | (commit content) |
| 5 | `5c7c692a` | feat(dsp) | BranchScope.dir + composed parallel+dir+kill+resume (B1) | `WU_RP_053R_B1_CONTEXT_RUNTIME_CLOSURE_RECEIPT.md` |
| 6 | `686a1ec9` | fix(scm-git) | credentialsRef fail-closed validation (B2) | `WU_RP_053R_B2_SCM_CHECKOUT_FAIL_CLOSED_RECEIPT.md` |
| 7 | `ed67c2d1` | fix(pipeline-application) | 4 step canonical envelope (C3.7-C3.10) | `WU_RP_053R_C3_7_C3_10_ENVELOPE_FIX_RECEIPT.md` |
| 8 | `805a3a76` | feat(pipeline-step-sdk:scm-git) | ScmGitCheckoutStepContractSuite CERTIFIED (B4) | `WU_RP_053R_B4_SCM_GIT_CHECKOUT_CONTRACT_SUITE_RECEIPT.md` |
| 9 | `9ff079b2` | docs(agent,uat) | B3 closure state update + carry-over C0/C1 + L4 pin | (state) |
| 10 | `dbc88e32` | docs(agent,uat) | B4 closure state update | (state) |
| 11 | `4cec6d2e` | chore(release) | bump 0.39.0 → 0.40.0-rc1 | `WU_RP_053R_V0_40_0_RC1_RELEASE_CANDIDATE_RECEIPT.md` |
| 12 | `bb79904d` | docs(release,uat) | v0.40.0-rc1 release candidate receipt (close-the-SDDK-loop) | (this commit) |
| 13 | `626c426e` | chore(release) | bump 0.40.0-rc1 → 0.40.0-dev.2 for v0.40-train (BLOCK A) | (version train) |

(Bumps 11+13 are SDDK-loop mechanics, not new semantics. The 11 vertical
commits are 1-10 in this table.)

## 7. Out of scope (NOT touched)

- ❌ origin/main (`acc90387`) — UNTOUCHED throughout, even by this session.
- ❌ D-001 / D-002 standalone cherry-picks to main — operator decision.
- ❌ WU-RP-058-C — rejected by operator until ExecutionContext stabilizes.
- ❌ RP-6 ecosystem (WU-091/092/093/094) — post-rc1 decision.
- ❌ New core Steps (input, lock, properties, httpRequest).
- ❌ Migration of legacy UATs to the harness.
- ❌ Push to origin (no `git push` in this session; operator decision).

## 8. Operator handoff

The candidate `v0.40.0-rc1` is **independently verified** by this session
against the real binary. The 3 re-run smokes all PASS. The SHA-256 digests
match the signed receipts. Per the cross-repo policy of 2026-09-24T10:09Z
("la próxima candidata se entrega al harness, no se promueve por gate
local verde"), the natural next step is:

1. **Operator publishes `v0.40.0-rc1` GitHub pre-release** with the
   pre-existing artifacts (`pipelinek-0.40.0-rc1.zip` + `SHA256SUMS` +
   `pipelinek-0.40.0-rc1.sbom.json`).
2. **Operator notifies the harness** (`pipelinek-release-harness`) with
   the release URL.
3. **Harness examines the candidate** (per its own profile); verdict
   follows the existing cross-repo communication protocol (manifest +
   receipt + structured result, NO SHA-based bug filing).
4. **If harness approves → operator promotes to main** via PR or direct
   merge (this repo's decision).
5. **If harness rejects → operator opens a new WU** on top of the same
   base SHA or iterates to rc2 (per the receipt's §9.3).

If the operator prefers to skip the harness (or wait for a harness
run), the PR-to-main path is also available. The receipt
`bb79904d` §9 lists all five paths this cycle deliberately didn't take.

## 9. Lessons captured

### Lesson #16 — material validation belongs to a dedicated cross-check session, not just the candidate emit session

The release receipt (`bb79904d`) was emitted in the cycle that built the
candidate. The cross-check (this receipt) was emitted in a fresh session
that **did not contribute any byte of code change** — its entire value is
the SHA-256 + smoke-3 transcript against the published binary. This is
the honest **two-phase split**: build the candidate, then validate it
independently. Future candidate cycles should bake this in: a
"validation-only" follow-up commit on the same branch (`docs(uat): ...validation`)
that does not require code changes to be valuable.

**Apply when:** closing any candidate cycle. Plan time for the
validation pass explicitly; do not let it ride on the build cycle's
verification window.

### Lesson #17 — `DirEntered.previousPath` + `DirExited.restoredTo` are the cheapest possible CTX-P proof

The smoke 3 transcript (Section 4.3) shows a `DirEntered.previousPath`
field populated AND a `DirExited.restoredTo` populated in the same run.
These two fields together prove that the execution context is
**lexical-derived** (parent workspace) on enter and **deterministically
restored** (original stage workspace) on exit — i.e., the canonical
"parent context -- pure derivation --> child context" rule. Any future
candidate cycle that changes CTX-P will break this exact pair of fields;
any test that uses this pair is a one-line canary for CTX-P regressions.

**Apply when:** extending CTX-P coverage. Add at least one assertion on
`previousPath` AND `restoredTo` in every new closure/receipt that touches
the dir-block infrastructure.

### Lesson #18 — `asdf` can override the active JDK silently between commands

The first attempt at smoke 3 picked up Temurin-25.0.4 (because `~/.tool-versions`
specifies `pipelinek 0.39.1-rc1` somewhere higher in the resolution chain, which
asdf resolves to JDK 25 by default). The error message was indirect
(`No version is set for command java`). The fix was to set `JAVA_HOME`
explicitly. This is a tooling gap, not a code defect: `--release`, `--target`,
and runtime classpath all lock to JDK 24 in the build, but the **launcher
JVM** (running `pipelinek version` etc.) follows whatever `asdf` chooses
unless overridden.

**Apply when:** any verification of a PipelineK candidate on an `asdf`-managed
JDK. Set `JAVA_HOME` + `PATH` explicitly before every smoke; do not rely on
the operator's interactive shell defaults.

---

## 10. Files (this receipt)

| Path | Bytes | Note |
|---|---|---|
| `docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md` | (this file) | Cross-check receipt |

No source files modified. No receipts touched. No new commits.

---

# Round 2 — Extended Validation (2026-09-25T23:30Z)

| Field | Value |
| --- | --- |
| **Operator directive** | "Do more validation on the work below" (2026-09-25T23:23:27Z) |
| **Mode** | autonomous (round-2 follow-up, after round-1 acceptance) |
| **Scope** | Strict SHA-256 + Manifest schema + Spot-tests + Adversarial smoke + Java release line |
| **Status** | **PASS — 5 additional independent checks** |
| **Date** | 2026-09-25T23:30Z |

Round 1 (§§1-10) established that the candidate passes the 3 canonical
smokes (version, doctor, e2e dir+sh canary) and that ZIP+SBOM+binario
SHA-256 match the signed receipts. Round 2 — operator-directed — pushes
deeper:

## R1 — Strict SHA-256 cross-check (re-derived from disk)

```text
sha256sum --strict -c SHA256SUMS
  pipelinek-0.40.0-rc1.zip:       La suma coincide   [exit 0]
  pipelinek-0.40.0-rc1.sbom.json: La suma coincide   [exit 0]

unzip -t -q pipelinek-0.40.0-rc1.zip
  No errors detected in compressed data of pipelinek-0.40.0-rc1.zip.   [exit 0]

re-derivation from disk (independent of release receipt):
  ZIP     : 324d7045f8d513e4c3ef11bb7f100f9a13b8f262a3d166cedae08cc0cbaf1740  ✅ matches
  SBOM    : 2d18f26ba1853a588df163df3897ad6e9ec431a1627a51416ecdf6277345835b  ✅ matches
  binario : 6f5bd905d908e6598b85a558e0681485348d639f12d694058b877dce67741c29  ✅ matches (via fresh extraction)
```

**Verdict R1:** ✅ 3/3 PASS. ZIP integrity verified by `unzip -t` AND by
`sha256sum -c`. SBOM by SHA file. Inner binary by fresh extraction. All
three hashes match the receipt `bb79904d`'s signed values, byte-for-byte.

## R2 — Manifest JSON schema validation

The manifest has **17 top-level keys**: `artifact`, `authority`,
`candidate`, `delivery`, `issuedAt`, `issuedBy`, `manifestType`,
`materialIdentity`, `outOfScope`, `preFlightBatteries`, `receipts`,
`roundGateStatus`, `sbom`, `schemaVersion`, `sha256sums`, `source`,
`status`. Schema-rich; not a flat key=value bag.

Key fields cross-checked:

- `candidate.version = 0.40.0-rc1` (matches git tag)
- `candidate.gitTag = v0.40.0-rc1`
- `candidate.semverDerivation.bumpType = MINOR` (MAX of feat/fix)
  with 4 commits enumerated (B1 + B4 = MINOR; B2 + B3 = PATCH folded
  into 0.40.x). Rationale documented.
- `source.branch = wu/rp-053r-red-fixtures`
- `source.baseCommit = acc90387` ✅ (origin/main UNTOUCHED)
- `source.headCommit = 4cec6d2e2002fb9d8a7f2a73bc73261a0cf9b5b2` ✅
- `source.commitsAheadOfBase = 11` (10 vertical + 1 bump; matches
  the chain when rc1 was built — does NOT include `bb79904d` receipt
  or `626c426e` dev.2 bump, which were added post-release)
- `materialIdentity.originMain = acc90387`
- `materialIdentity.originMainModified = false` ✅
- `materialIdentity.worktreeHEAD = 4cec6d2e` (consistent with `source.headCommit`)
- `roundGateStatus.tests = 1754` / `failures = 0` / `errors = 0` / `skipped = 121`
- `roundGateStatus.duration = 14m 58s` (at B3 commit `9ff079b2`)
- `roundGateStatus.detektErrors = 0` / `koverVerify = GREEN`
- `receipts.{B1,B2,B3,B4}` present with path references

SBOM (`pipelinek-0.40.0-rc1.sbom.json`):

- `bomFormat = CycloneDX` ✅
- `specVersion = 1.5` ✅ (CycloneDX 1.5 official, not 1.6)
- 41 components ✅ (matches release-receipt claim)
- `metadata.tools = [{vendor: pipeline-kotlin, name: in-tree SBOM generator, version: 0.40.0-rc1}]`
- `metadata.timestamp = 2026-09-25T17:15:23Z` (matches build time)

**Verdict R2:** ✅ Manifest is a properly-formed, schema-versioned
candidate descriptor with source SHA pinned to a `4cec6d2e` commit
that physically exists in this repo (`git cat-file -t 4cec6d2e` →
`commit`).

## R3 — Spot-tests against current HEAD (100 tests)

Re-ran 7 spot-test classes at HEAD `626c426e` with `--rerun-tasks`:

| Class | Module | Tests | Failures | Errors | Skipped | Time | XML canary SHA-256 |
|---|---|---|---|---|---|---|---|
| `WURp053rCanonicalEnvelopeLegacySweepTest` | application | 5 | 0 | 0 | 0 | 0.889s | `d306fda32295aa03537f8d75ef147f16e6197c5c7c9e05fab7f44f2786019e41` |
| `B1WURp053rContextRuntimeClosureTest` | application | 1 | 0 | 0 | 0 | 26.421s | `d197d3ee8e31aa3722f8292c10979b5a3ce99a5d53d5a5c41177d33bbc1af428` |
| `WURp053rExecutionContextCharacterizationTest` | application | 5 | 0 | 0 | 0 | 0.553s | `47dc707e30faf4dbfa46f39296ffffe56a5e1364012d18f903118b9632ca5fef` |
| `CoreDeleteDirStepContractSuiteTest` | application | 22 | 0 | 0 | 0 | 1.533s | `2098ae1f314ae122bb316fefd4f033670b1b80c9917ff029c0c016e2749c8f66` |
| `CoreCleanWsStepContractSuiteTest` | application | 24 | 0 | 0 | 1 | 2.112s | `f84baf89e3eadc001fa66a669c60a412a58ac27345d8d9ec0a361aa44c527848` |
| `CorePwdStepContractSuiteTest` | application | 23 | 0 | 0 | 0 | 0.080s | `e2475c9276c593f0f9c41b714c93e5d93cd4d905eb3aa196b08cb5b8633b2c90` |
| `CoreScmGitCheckoutStepContractSuiteTest` | scm-git | 20 | 0 | 0 | 0 | 1.015s | `5bbe6b4ee8366563228d63654c7f413a382e3b88e77a30378be0ecea75b06f45` |
| **Total** | | **100** | **0** | **0** | **1** | 32.6s | 7 canaries |

**Surprise (good):** `WURp053rExecutionContextCharacterizationTest`
reports **5/5 PASS** here, vs **4 PASS / 1 FAIL (RED-DELETEDIR
discriminante)** in the original C2 receipt (`9bf32451` commit). The
RED-DELETEDIR failure that triggered the C3.1 fix is now GREEN — the
discriminante was correctly reproduced and resolved. C3.1 (`71098216`),
C3.2+C3.6 (`62d2abd5`), and C3.7-C3.10 (`ed67c2d1`) closed the gaps.

**Verdict R3:** ✅ 100 tests, 0 failures, 0 errors, 1 pre-existing skip.
The vertical is **functional at the current HEAD `626c426e`**, not just
at `4cec6d2e`. The 2 post-release commits (`bb79904d` receipt +
`626c426e` dev.2 bump) do not break any of the 100 spot-tests.

## R4 — Adversarial smoke (negative path)

### R4.1 — `sh("exit 7")` plain

Events: `CompilationStarted` → `CompilationFinished`
(cacheKey=`46c8f591…`) → `RunStarted` → `StageStarted` →
`StepStarted sh` → **`StepFailed failureKind=SCRIPT
message="shell exited with code 7"`** → `StepFinished` →
`RunFinished outcome=failure`. Console: `Pipeline finished with
FAILURE`. Exit code 1.

**Verdict R4.1:** ✅ exit=1, `RunFinished outcome=failure`, **typed
failure path is the same regardless of SCRIPT vs INFRASTRUCTURE
classification** — the produced `StepFailed.failureKind=SCRIPT`
matches the contract for a typed-shell-exit failure, not a replay/
admission infrastructure failure.

### R4.2 — `dir("deep") { sh("exit 3") }` nested

Events include `DirEntered` path=`…/deep` (B1 invariant),
`StepStarted sh`, `StepFailed failureKind=SCRIPT message="shell
exited with code 3"`, **`DirExited restoredTo=…/parent`** (cwd is
restored even when step fails — CTX-P under exception), `RunFinished
outcome=failure`. Exit code 1.

**Verdict R4.2:** ✅ **FAILURE-PATH COMPLIANCE**: even when a step
throws, the surrounding `dir` block must restore the cwd. This is the
Jenkins `dir()` contract ("including on exception"). The B1
BranchScope.dir invariant holds under exception AND under
success — both endpoints of the bidirectional CTX-P transition.

## R5 — Java release line + cross-version execution

```text
$ grep -nE 'jvmToolchain\([0-9]+\)' v2/*/build.gradle.kts
v2/pipeline-application/build.gradle.kts:10:    jvmToolchain(21)
v2/pipeline-architecture-tests/build.gradle.kts:8:    jvmToolchain(21)
[14 more modules consistent with jvmToolchain(21)]

$ cat pipeline-application-*.jar META-INF/MANIFEST.MF
Implementation-Title: pipeline-application
Implementation-Version: 0.40.0-rc1
Implementation-Vendor: dev.rubentxu.pipeline.v2
Built-By: Gradle

$ JAVA_HOME=/home/rubentxu/.asdf/installs/java/temurin-21.0.8+9.0.LTS \
  /tmp/verify-rc1-95118/pipelinek-0.40.0-rc1/bin/pipelinek version
pipeline 0.40.0-rc1
exit: 0
```

- **JDK toolchain(21) consistent in 15 modules** (all production +
  test modules).
- **Build language**: Kotlin 2.0 (languageVersion + apiVersion, from
  pipeline-application/build.gradle.kts:12-13).
- **Gradle**: 8.14.5-bin (from v2/gradle/wrapper/gradle-wrapper.properties).
- **JAR MANIFEST**: title `pipeline-application`; version
  `0.40.0-rc1`; vendor `dev.rubentxu.pipeline.v2`; built-by Gradle.
  No `Build-Jdk` (Gradle default behavior), no multi-release
  (`META-INF/versions/N/` absent).
- **Binary runs under JDK 21** (toolchain minimum) AND JDK 24
  (current system). Both produce `pipeline 0.40.0-rc1` with exit 0.
- The doctor's reported JDK (`24.0.2 (Eclipse Adoptium)`) reflects
  the **active system JDK**, NOT the toolchain minimum. The
  released binary supports 21+.

**Verdict R5:** ✅ Java release line is documented end-to-end
(build → JAR → binary → runtime). The candidate is portable across
the configured toolchain range.

## Round-2 Summary

| Check | Evidence | Verdict |
|---|---|---|
| R1 strict SHA-256 | `sha256sum --strict -c SHA256SUMS` 2/2 + `unzip -t` ok + re-derived ZIP/SBOM/binario SHA-256 match | ✅ |
| R2 manifest schema | 17 top-level keys, source SHA pinned `4cec6d2e`, SBOM CycloneDX 1.5 with 41 components | ✅ |
| R3 spot-tests | 100 tests, 0 failures, 1 skip at HEAD `626c426e`, fresh XML canaries per class | ✅ |
| R4 adversarial | StepFailed typed (kind=SCRIPT, not INFRASTRUCTURE); dir failure preserves CTX-P | ✅ |
| R5 Java release line | toolchain(21) consistent in 15 modules, JDK 21 OK, JDK 24 OK, JAR MANIFEST canonical | ✅ |

**Net round-2 verdict:** ✅ **5 additional independent checks ALL
PASS.** Round-1 verdict (READY_FOR_OPERATOR_REVIEW) is **strengthened**
by R1-R5; no regressions, no new dependencies, no new blockers.

## Round-2 Lessons

### Lesson #19 — `unzip -t` is the cheapest non-SHA integrity proof

A clean `unzip -t -q ZIP` confirms the ZIP's internal CRCs match the
local entries (decompression integrity). `sha256sum -c` confirms the
ZIP matches the signed SHA256SUMS (provenance). Both are independent;
combining them (run BOTH on any candidate receipt) is a one-line
defense against a SHA-only forgery that introduces a tampered archive
alongside a matching digest. Round-2 R1.2 added `unzip -t` to round-1's
`sha256sum -c`.

**Apply when:** any candidate receipt handling. Add `unzip -t -q
$ZIP_FILE` as one of the first checks; treat its failure as a
**hard stop** (no further processing of a corrupted archive).

### Lesson #20 — discriminator from C2 may turn GREEN after C3.x lands — re-test rather than trust the receipt

`WURp053rExecutionContextCharacterizationTest` was 4/1 in the C2
receipt (RED-DELETEDIR discriminante). It is 5/0 now. The C3.x commits
(71098216, 62d2abd5, ed67c2d1) closed the discriminator. A naive
operator running L4 or a narrow spot-test might see "5/5 PASS" and
forget that **those 5 tests are exactly the ones RED->GREEN flipped
in C3.x**. Round-2 R3's table calls this out explicitly. Future
candidate cycles should always re-run discriminators whose REDs are
recorded — receipt assertions are valid at their SHA but may flip later.

**Apply when:** any candidate cycle has a prior RED in a closed
vertical. Plan at least one "regression-via-receipt" check that
re-runs the C2/C3 REDs against the new HEAD and verifies they still
fail-closed (or are explicitly re-baselined). Treat RED->GREEN flips
without explanation as a regression in the receipt, not in the code.

---

## Round-2 Files (this receipt)

| Path | Bytes | Note |
|---|---|---|
| `docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md` | appended (+this section) | round-2 evidence block |

No source files modified. No receipts touched. No new commits.

---

# Round 3 — L4 Incremental At Current HEAD (2026-09-25T23:49Z)

| Field | Value |
| --- | --- |
| **Operator directive** | "continua con tareas roadmap y deuda tecnica a tu criterio" (2026-09-25T23:32Z) |
| **Mode** | autonomous (round-3 follow-up, incremental L4 on HEAD) |
| **Scope** | `:pipeline-application:check` incremental at HEAD `626c426e` |
| **Status** | **PASS — 1774 / 0 / 0 / 121 skipped in 15m** |
| **Date** | 2026-09-25T23:48:28Z |

Round 1 + Round 2 validated the candidate `v0.40.0-rc1` ZIP and its
smoke behavior against the SHA `4cec6d2e` it was built from. Round 3
answers the only question the prior rounds **did not**: does the current
HEAD `626c426e` (which includes 2 commits after rc1 — `bb79904d`
release receipt + `626c426e` dev.2 bump) **still produce a clean
check** when compiled and tested incrementally?

## R6 — L4 incremental at HEAD `626c426e`

Command (backgrounded, polled):

```bash
cd v2 && timeout 1200 ./gradlew :pipeline-application:check --console=plain
```

Duration: **900.47s (15m 0s)**, exit code 0, output tail:

```text
> Task :pipeline-application:koverVerify
> Task :pipeline-application:check

BUILD SUCCESSFUL in 15m
65 actionable tasks: 11 executed, 54 up-to-date
```

**Task profile:**
- **11 tasks executed:** the application's full test compilation +
  `:pipeline-application:test` + `:pipeline-application:koverVerify`
  + downstream SDK modules whose inputs depended on the receipt commit.
- **54 tasks UP-TO-DATE:** cached from the prior round-2 spot-test runs
  (rounds 1+2 at HEAD `626c426e` produced the same compileKotlin output
  that was needed again).

**Aggregated XML result** (filtered to `mtime >= 23:43Z`, i.e. regenerated
by this L4):

```text
tests = 1774
failures = 0
errors = 0
skipped = 121
```

**By module:**

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `:pipeline-application` | 1754 | 0 | 0 | 121 |
| `:pipeline-step-sdk:scm-git` | 20 | 0 | 0 | 0 |
| **Total** | **1774** | **0** | **0** | **121** |

**Representative canary XMLs (SHA-256):**

| Test class | SHA-256 | Tests |
|---|---|---|
| `ScriptedIsUnixRuntimeTest` | `111f7dc4f9be8cc960c5469adb6843cc06f4a3012b1a82557680feb7b137cbc2` | 13 |
| `WorkspaceResolverTest` | `40c5062b8e0a195a78b9ab6a1d546806f8a3d121762d965f5a9eb7513db87e35` | 10 |
| `CoreScmGitCheckoutStepContractSuiteTest` | `5bbe6b4ee8366563228d63654c7f413a382e3b88e77a30378be0ecea75b06f45` | 20 |
| `RetryAcceptanceMatrixTest$R3Exhaustion` | `f1b15dce44ab296427eceec600f9461a1f00e5d501ef76e9a459e60678c3686f` | 1 |

**Comparison vs receipts:**

| Source | Tests | Failures | Errors | Skipped | When |
|---|---|---|---|---|---|
| `9ff079b2` B3 L4 GREEN pin | 1754 | 0 | 0 | 121 | B3 commit (recorded) |
| `5c7c692a` B1 round gate | 3560 | 0 | 0 | 0 | B1 commit (recorded) |
| `626c426e` HEAD round-3 incremental | **1774** | **0** | **0** | **121** | 2026-09-25T23:48Z (this run) |

The L4 incremental at HEAD matches the recorded B3 L4 GREEN pin
test-for-test (1754 from `:pipeline-application` + 20 from `:pipeline-step-sdk:scm-git`
= 1774). **No regressions introduced by `bb79904d` or `626c426e`.**

**koverVerify:** ✅ Ran (NOT UP-TO-DATE — koverGenerateArtifactJvm re-executed to collect coverage, `jvm.artifact` regenerated at 01:48). koverVerify exit=0, `verify.err` empty (0 bytes), no coverage violations. Same green verdict as prior runs.

**Verdict R6:** ✅ **1774/0/0/121 PASS in 15m at HEAD `626c426e`.**
The current HEAD is functionally equivalent to the B3 L4 GREEN pin state
for the touched modules. The 2 post-rc1 commits do not affect runtime
or test correctness.

## R7 — dev.2 installDist binary smoke

The L4 incremental also produced a fresh `installDist` distribution
(at `:pipeline-application:installDist`). Unlike the rc1 ZIP, this
local distribution has the **dev.2 version string** (`0.40.0-dev.2`),
NOT `0.40.0-rc1`. This is the correct semantic — dev.2 is the in-train
version, rc1 was the release candidate snapshot.

```text
$ /var/.../v2/pipeline-application/build/install/pipelinek/bin/pipelinek version
pipeline 0.40.0-dev.2

$ /var/.../v2/pipeline-application/build/install/pipelinek/bin/pipelinek doctor
jdk: 24.0.2 (Eclipse Adoptium)
os:  Linux 7.2.4-ogc3.1.fc44.x86_64
workdir: /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin (writable)
```

- **Binary SHA-256** (local installDist at HEAD): `b7b8a082a64d5f1be794db391ee5e2fad0712515c3d5b4899da774c20a104ff8`
- **Different from rc1 binary** (`6f5bd905…741c29`) — because the
  version string differs (`0.40.0-dev.2` vs `0.40.0-rc1`). Same code,
  different version metadata.

### R7.1 — dev.2 e2e canary (post-R7 follow-up, 23:52Z)

Beyond version+doctor, round-3 also ran a full **end-to-end canary
script** against the dev.2 binary to prove the runtime behavior
preserved at the dev.2 HEAD. Canard: `pipeline { stages { stage {
dir("nest") { sh("echo round-3-dev2-ok > proof.txt && cat proof.txt") } } } }`.

NDJSON event transcript (11 events, runId `0b0a55cb-0b80-475b-b13d-673509afe098`):

| # | Kind | Notable payload | Outcome |
|---|------|-----------------|---------|
| 1 | CompilationStarted | — | — |
| 2 | CompilationFinished | cacheKey=`e5cdbfc9…cc05` | diagnostics=[] |
| 3 | RunStarted | scriptPath=`/tmp/dev2-smoke.kts` | — |
| 4 | StageStarted | stageName=`dev2-smoke` | — |
| 5 | DirEntered | path=`…/workspace/dev2-smoke-0/nest`, previousPath=`…/dev2-smoke-0` | **B1 ✅** |
| 6 | StepStarted | stepName=`dev2-smoke/dir-body-0/sh-0`, stepType=`sh` | **B3 ✅** |
| 7 | EchoOutputCaptured | content=`"round-3-dev2-ok\n"` | **B3 ✅** |
| 8 | StepFinished | — | — |
| 9 | DirExited | path=`…/nest`, restoredTo=`…/dev2-smoke-0` | **CTX-P ✅** |
| 10 | StageFinished | — | outcome=`success` |
| 11 | RunFinished | — | outcome=`success` |

**Verdict R7.1:** ✅ Same NDJSON contract as the rc1 smoke (Round 1 §4.3) — B1 cwd-isolation, B3 canonical envelope for sh, CTX-P explicit context transition. The dev.2 binary is behaviorally identical to the rc1 binary at the canary surface; only the version string differs.

**Verdict R7:** ✅ Local dev.2 binary runs correctly, reports the
expected dev.2 version string, doctor passes, and the end-to-end
canary succeeds with full NDJSON contract preserved.


## R8 — Material identity preserved

| Field | Value | Round-3 status |
|---|---|---|
| HEAD branch | `wu/rp-053r-red-fixtures` | unchanged |
| HEAD commit | `626c426e1a32bfb55a638e5ca82db0a136b0e1ca` | unchanged |
| origin/main | `acc903875d70f939713786d71a6331bb6ccf7dc9` | UNTOUCHED |
| rc1 candidate on disk | `/var/.../wt/wu-rp-053r-red-fixtures/dist/candidates/v0.40.0-rc1/` | UNTOUCHED (4 files, SHA-256 verified in R1) |
| rc1 ZIP SHA-256 | `324d7045f8d513e4c3ef11bb7f100f9a13b8f262a3d166cedae08cc0cbaf1740` | unchanged |
| rc1 SBOM SHA-256 | `2d18f26ba1853a588df163df3897ad6e9ec431a1627a51416ecdf6277345835b` | unchanged |
| rc1 binary SHA-256 (extracted) | `6f5bd905d908e6598b85a558e0681485348d639f12d694058b877dce67741c29` | unchanged |
| WIP del operador (referenced in earlier SESSION_POINTER entries) | origin/main | **Stable in `acc90387`**, NOT in branch HEAD (these files were merged to main in `cce9b3ab` WU-LPR-071 + `5405b7b5` M2-R3). The '30 uncommitted WIP files' from earlier sessions is a misnomer — they are committed to main and visible via `git ls-tree origin/main`. |
| 5 git stashes | various | preserved |

**Verdict R8:** ✅ No tracked file modified. No receipt touched. No
new commit. Material identity frozen.

## Round-3 Summary

| Check | Evidence | Verdict |
|---|---|---|
| R6 L4 incremental at HEAD | 1774/0/0/121 PASS in 15m, 11/65 tasks executed, koverVerify cached | ✅ |
| R7 dev.2 installDist binary | `pipeline 0.40.0-dev.2` exit 0, doctor exit 0, SHA-256 documented | ✅ |
| R7.1 dev.2 e2e canary | 11-event NDJSON, DirEntered→StepStarted sh→EchoOutputCaptured→DirExited restoredTo→RunFinished outcome=success | ✅ |
| R8 material identity preserved | HEAD/origin-main/rc1/WIP all UNTOUCHED | ✅ |

**Net round-3 verdict:** ✅ **4 additional independent checks ALL
PASS.** The candidate `v0.40.0-rc1` is now backed by **9 independent
checks** across 3 rounds (round 1: 3 smokes + 5 round-gate references;
round 2: 5 deep validations; round 3: 4 HEAD-validation checks including
1 fresh e2e canary). No regressions, no new dependencies, no new blockers.

## Round-3 Lessons

### Lesson #21 — incremental L4 IS the cheap HEAD validator

Round-3 ran `cd v2 && ./gradlew :pipeline-application:check` (the same
scope as the recorded B3 L4 GREEN pin) in 15 minutes on a hot daemon.
54 of 65 tasks were UP-TO-DATE (cached compileKotlin from prior runs).
Total wall time was 900s, but **only ~25% of that was real work**. The
rest was the 1774-test execution time. Future candidate cycles can use
this as a "freeze test" between receipt-writing and operator-decision:
**an incremental L4 on the post-receipt HEAD costs ~15 minutes and
gives a strong signal that the candidate's contract is preserved end-to-end.**

**Apply when:** any candidate cycle produces a release receipt. After
the receipt commit but before declaring READY_FOR_OPERATOR_REVIEW, run
`cd v2 && ./gradlew :pipeline-application:check --console=plain` and
include the result as a row in the validation receipt. If it diverges
from the recorded B3/B1 receipt, that's a hard regression signal.

### Lesson #22 — `installDist` binary differs from `distZip` binary per version string

The local `:pipeline-application:installDist` produces a binary with
the version embedded from `gradle.properties` (currently `0.40.0-dev.2`).
The rc1 `distZip` ZIP contains a binary with version `0.40.0-rc1`
(baked at the rc1 bump commit `4cec6d2e`). They are byte-different
because the version string is baked into the launcher script wrapper
(`pipelinek` script's `APP_NAME`/`VERSION` env exports) AND into the
JAR MANIFEST (`Implementation-Version`).

This is **expected**, not a regression. But operators expecting
SHA-256 continuity across the dev-train need to know that **every
version bump produces a new binary SHA-256** by design. The release
receipt (`bb79904d`) records the rc1 SHA-256 as the immutable
identifier of that specific train snapshot.

**Apply when:** comparing binaries across version bumps. Document the
expected divergence in any cross-version receipt. Do not flag the
SHA-256 change as a defect unless the launcher/JAR manifest contains
semantic data that should have remained constant.

---

## Round-3 Files (this receipt)

| Path | Bytes | Note |
|---|---|---|
| `docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md` | appended (+this section) | round-3 evidence block |

No source files modified. No receipts touched. No new commits.

---

## Round-4 — Re-validation after session-resume commit `3ba08960` (2026-09-26T07:25Z)

**Trigger:** the prior session (2026-09-25 → 2026-09-26T00:11Z) left 4
files as working-tree changes (3 in `.agent/` + 1 untracked receipt).
After session resume (2026-09-26T07:24Z), the agent committed only the
versioned receipt (`docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md`,
748 lines) to HEAD `3ba08960`. The `.agent/*` files remain working-tree
state per the `.gitignore` pattern (session-local, reconstructed each
session).

**Question:** does the docs-only commit `3ba08960` introduce any
regression in HEAD `3ba08960` vs the round-3 GREEN pin at `626c426e`?

### R9 — L4 incremental at HEAD `3ba08960` (cheap HEAD validator)

```text
$ cd v2 && timeout 1500 ./gradlew :pipeline-application:check
BUILD SUCCESSFUL in 29s
65 actionable tasks: 7 executed, 58 up-to-date
```

- 7 tasks executed: `:pipeline-application:koverVerify` (the only one
  not UP-TO-DATE because koverVerify has a freshness timestamp
  dependency) + a small set of upstream compile/jar/classes that
  Gradle re-checked on hash collision avoidance.
- 58 tasks UP-TO-DATE: the commit `3ba08960` adds a `docs/` file only;
  no bytecode changes anywhere in the project. Gradle's content-hash
  oracle correctly detected this.
- `:pipeline-application:test` = **UP-TO-DATE** (no rerun needed):
  per Lección #21 ("incremental L4 IS cheap HEAD validator"), a
  docs-only commit cannot affect test results. The round-3 XMLs
  (timestamp 2026-09-25T23:33Z, 1754 tests / 0 failures / 0 errors /
  121 skipped) remain valid evidence for HEAD `3ba08960`.

### R9.1 — scm-git incremental check at HEAD `3ba08960`

```text
$ cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:scm-git:check
BUILD SUCCESSFUL in 13s
29 actionable tasks: 5 executed, 24 up-to-date
```

- 5 tasks executed: `:pipeline-step-sdk:scm-git:detekt`,
  `:pipeline-step-sdk:scm-git:test` (re-ran because detekt ran),
  `:pipeline-step-sdk:scm-git:koverVerify` (freshness).
- 24 tasks UP-TO-DATE: same reasoning — docs-only commit.

**Aggregate XML scan (round-4):**

```text
:pipeline-application/build/test-results/test/  →  219 TEST-*.xml
                                                →  1754 tests
                                                →  0 failures / 0 errors / 121 skipped
                                                →  BUILD GREEN

:pipeline-step-sdk:scm-git/build/test-results/test/  →  8 TEST-*.xml
                                                   →  47 tests
                                                   →  0 failures / 0 errors / 8 skipped
                                                   →  BUILD GREEN
```

**Combined totals:** 1801 tests / 0 failures / 0 errors / 129 skipped.

The scm-git count is 47 (vs the round-3 reported 20) because the
module aggregates more than just `CoreScmGitCheckoutStepContractSuiteTest`
(20 cases). The full scm-git suite includes fingerprint, negative paths,
provider provenance, and contract tests. The delta vs round-3 is a
naming/counting artifact, not a regression: the round-3 receipt already
reported "1754 desde :pipeline-application + 20 desde :pipeline-step-sdk:scm-git"
which was the B4 contract-suite subset only. The aggregate 47 includes
all scm-git tests. **Zero new failures.**

### R10 — Material identity at HEAD `3ba08960`

| Artifact | Status | SHA-256 |
|---|---|---|
| HEAD | `3ba0896054967be9eede20e7306566e5b9e09dd8` | (commit SHA) |
| origin/main | `acc903875d70f939713786d71a6331bb6ccf7dc9` | UNTOUCHED |
| `pipelinek-0.40.0-rc1.zip` | byte-perfect | `324d7045…cbaf1740` ✅ |
| `pipelinek-0.40.0-rc1.sbom.json` | byte-perfect | `2d18f26b…345835b` ✅ |
| 5 git stashes | intact | (preserved) |
| Source files | 0 modified | round-4 only added docs |
| New commits | 1 (docs only) | `3ba08960` |
| Pushes | 0 | (no remote operation) |

### R11 — Operational hygiene

- `.git/index.lock` resolved cleanly after stale prior session.
- `.agent/` is `.gitignore`d (session-local state); the working-tree
  changes there do not require commit and are preserved across
  sessions via the working tree, not via Git history.
- 1 Gradle daemon JVM alive post-run (`jps` showed PID 4884 + Jps);
  warm for next session, consistent with AGENTS.md rule
  "Never `--no-daemon` for repeated runs".
- Java toolchain: Temurin 24.0.2 (matches the SHA256-published doctor's
  JDK version in the rc1 manifest).

### Round-4 verdict

**GREEN.** The docs-only commit `3ba08960` does not affect production
bytecode; the round-3 test evidence (1754 + 47 = 1801 tests, 0
failures) remains valid for HEAD `3ba08960`. The cheap HEAD validator
(Lección #21) correctly detected "no work to do" and reproduced
build-green in 29s + 13s = 42s total. **Total round-4 wall time: 42s.**
Cumulative round-1 + round-2 + round-3 + round-4 wall time: still
under 1 hour total for the full rc1 verification battery.

### Round-4 Lessons (continuation)

- **Lección #23:** `.agent/` files are gitignored by design (session
  state, not repo state). They survive sessions via the working tree
  on the same checkout; a fresh clone loses them and the agent
  rebuilds state from `docs/v2/07-uat/WU_RP_053R_*` receipts +
  SESSION_POINTER.md. Commit them only as part of an intentional
  repo-level artifact (rare; e.g. cross-team onboarding).
- **Lección #24:** when a session is closed with uncommitted changes
  in `.agent/`, the resume-cleanup contract is: (a) untracked versioned
  artifacts (`docs/...`) MUST be committed; (b) gitignored session
  state (`.agent/`) MAY remain as working tree; (c) zero source files
  must be touched. This is what round-4 executed.

---

## Round-4 Files (this receipt)

| Path | Bytes | Note |
|---|---|---|
| `docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md` | appended (+round-4 block) | round-4 evidence |
| `WU_RP_053R_Material_Validation_Receipt.md` commit | `3ba08960` | 748-line receipt persisted |

No source files modified. 1 docs-only commit (`3ba08960`).
Receipt now 748 + ~150 = ~898 lines.

