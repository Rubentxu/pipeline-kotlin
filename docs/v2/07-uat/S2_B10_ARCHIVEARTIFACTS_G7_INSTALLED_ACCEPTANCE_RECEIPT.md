# S2-B10 / G7 — core.archiveArtifacts Installed Acceptance Receipt

> **Lane A — sole global writer of authority for S2-B10 `core.archiveArtifacts`.**
> Gate: **G7 — installed-distribution acceptance (real CLI, registry-only spine)**
> Base: `2271fb1e` (= the G6 commit as landed on trunk; `origin/main` at verification)
> Date: 2026-09-13T15:33–15:35Z (local +02:00)
> Distribution jar: `pipeline-application-0.1.0-SNAPSHOT.jar`
> sha256 `814d8d801551bf69260ab3aa958badd65427545ce5d930f88be326e05c19678e`
> Build: `timeout 1500 ./v2/gradlew -p v2 :pipeline-application:installDist` →
> `BUILD SUCCESSFUL in 3s / 38 actionable tasks: 21 executed, 17 from cache`
> (Gradle content-hash up-to-date check is the freshness oracle per V2 rule 2.)
> Pinned toolchain: `JAVA_HOME=…/asdf/installs/java/temurin-24.0.2+12`
> **Verdict: 5 / 5 PASS — `INSTALLED_ACCEPTANCE = true`**
> `CERTIFIED` **NOT** claimed (ADR-0074; G8 remains, which is the certification gate).

---

## 1. Scope and why this gate is meaningful here

G7 exercises the **real installed distribution** (CLI binary, shipped jars), not an
in-process coordinator. After G5 (`LEGACY_REMOVED`) there is no legacy fallback and no
compat shim left, so a green G7 is acceptance of the **registry path alone**.

`core.archiveArtifacts` does **not** consume a synchronous typed value in the DSL
continuation: the step's observable output is the durable `ArtifactArchived` /
`ArtifactArchiveFailed` event plus real filesystem effects in the artefacts retention
directory. The `STRUCTURED_DSL_RUNTIME_RETURN_GAP` blocker therefore does **not** apply.
This matches the `core.cleanWs` G7 precedent
(`S2_A10_CORE_CLEANWS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md`) and the `core.deleteDir` G7
precedent; the blocker is the R2 lane's concern for `pwd`, not this step's.

G7 was asked to prove two things explicitly:

1. the **non-empty registry path** archives a **byte-identical** payload, and
2. the **empty-match path fails closed** rather than silently succeeding.

Both are proven below, and a third scenario is added that turns (2) from an assertion
into a *discriminated* result (§5).

---

## 2. Evidence setup

```text
harness    : docs/v2/07-uat/evidence/s2-b10-g7/run-g7.sh          sha256 c026979f…
             docs/v2/07-uat/evidence/s2-b10-g7/assemble-evidence.py sha256 5cfceaa6…
             docs/v2/07-uat/evidence/s2-b10-g7/verify-receipt.py    sha256 41a9610b…
scenarios  : docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-01-…kts      sha256 99e91c15…
             docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-02-…kts      sha256 3a42b770…
             docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-03-…kts      sha256 093d86f2…
             docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-04-…kts      sha256 1bef409b…
evidence   : docs/v2/07-uat/evidence/s2-b10-g7/g7-installed-acceptance.json
             sha256 232adf4a…  (assembled from raw; never hand-written)
binary     : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
workspace  : /tmp/ar-g7-ws   (all mutable state; the repo is not mutated by a run)
raw manifest: g7-raw-sha256.txt — 43 entries, `sha256sum -c` verified 43/43 OK
```

The scenarios are **executable scenarios** in the ADR-0071 sense: the `.pipeline.kts`
archived here is the file the harness actually ran, in this directory, unmodified. They
are deliberately **not** added to `v2/compatibility/` so the corpus inventory and
`CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` are not perturbed.

```bash
pipeline-application run --db <ws>/<tag>.db --control-root <ws>/ctrl-<tag> <scenario>.pipeline.kts
# every invocation wrapped in `timeout 600` (V2 rule 4, inner-loop budget)
```

| Scenario | db / control-root | runId | exit |
| --- | --- | --- | --- |
| AR-G7-01 | `ar-g7-01.db` / `ctrl-ar-g7-01` | `f3291167-8b57-4a82-abea-8b6322d66139` | 0 |
| AR-G7-02 | `ar-g7-02.db` / `ctrl-ar-g7-02` | `ff71f9de-fa79-48d0-a0e8-fc88aa3e2254` | 1 |
| AR-G7-03 | `ar-g7-03.db` / `ctrl-ar-g7-03` | `6335c9de-af39-47fa-836f-9805a6ef5d50` | 0 |
| AR-G7-04 | `ar-g7-04.db` / `ctrl-ar-g7-04` | `9a1fcba4-a1d4-4a41-8811-a8a894884bf6` | 0 |

Result truth is the emitted events **plus the on-disk effects**, never the console
wording. `exit.txt` files are hashed in the manifest: `9a271f2a…` is `0\n` (runs 01, 03,
04) and `4355a46b…` is `1\n` (run 02).

---

## 3. Results

| # | Scenario | Criterion | Verdict |
| --- | --- | --- | --- |
| 1 | AR-G7-01 | non-empty match → archived payload byte-identical to the workspace source, verified independently of the engine | **PASS** |
| 2 | AR-G7-02 | empty match + `allowEmptyArchive=false` → typed SCRIPT failure, nothing archived, run fails | **PASS** |
| 3 | AR-G7-03 | **discriminator**: same glob/workspace, `allowEmptyArchive=true` → success with zero entries | **PASS** |
| 4 | AR-G7-04 | frozen delta D2 → `excludes` are applied on the real registry path | **PASS** |
| 5 | absence probe | deleted legacy dispatcher absent from source **and** from all 37 shipped jars, with a positive control | **PASS** |

---

## 4. AR-G7-01 — non-empty match, byte-identical payload (PASS)

```kotlin
pipeline {
    stages {
        stage("ar-g7-01") {
            sh("mkdir -p build/libs && seq 1 2000 > build/libs/artifact.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
```

Expected source payload is computed **outside the engine**, so the assertion cannot be
self-referential:

```text
seq 1 2000 | sha256sum  ->  6251e5743b6fd6a7d606130bdf7c15077ce85ebd3a0fdee284d15a46df199e38
```

| Capture | sha256 | source of truth |
| --- | --- | --- |
| expected (independent) | `6251e574…199e38` | `seq 1 2000 \| sha256sum`, outside the run |
| `ArtifactArchived` event payload | `6251e574…199e38` | stdout JSONL event, run `f3291167-…` |
| archived file on disk | `6251e574…199e38` | `ctrl-ar-g7-01/artefacts/<runId>/ar-g7-01/build/libs/artifact.jar` |

```text
event_sha256 == expected_sha256 == disk_sha256   (byte_identical = true)
size = 8893     relPath = build/libs/artifact.jar     RunFinished.outcome = success
```

The archive step ran on the registry path — `stepName=ar-g7-01/archiveartifacts-0`,
`stepType=archiveArtifacts`, and no legacy dispatcher exists to fall back to.

---

## 5. AR-G7-02 / AR-G7-03 — the empty-match failure is *policy*, not a glob defect (PASS)

This is the strongest row of the gate. The two scenarios differ in **exactly one
character-class of input**: the `allowEmptyArchive` flag. Same stage name pattern, same
`sh` precondition, same workspace contents, same absence of any `*.jar`.

**AR-G7-02** (`allowEmptyArchive = false`) — fails closed:

```json
{"kind":"ArtifactArchiveFailed",
 "reason":"No files matched glob pattern 'build/libs/*.jar' and allowEmptyArchive is false"}
{"kind":"StepFailed","stepName":"ar-g7-02/archiveartifacts-0","stepType":"archiveArtifacts",
 "failureKind":"SCRIPT","message":"archiveArtifacts: no files matched 'build/libs/*.jar'"}
{"kind":"RunFinished","outcome":"failure"}
```

```text
exit_code = 1
artefacts on disk = 0           (raw/ar-g7-02-archived-files.sha256 is the empty-file
                                 digest e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855)
workspace retained: workspace/ar-g7-02-0/build/libs/README.txt   (retainOnFailure)
```

Note the failure is the **typed domain failure**, not an engine crash: `failureKind=SCRIPT`
and the step's configured message, per the REPLAY POLICY test law (distinguish a typed
Step failure from an infrastructure failure).

**AR-G7-03** (`allowEmptyArchive = true`) — succeeds with an empty archive:

```json
{"kind":"ArtifactArchived","files":[]}
{"kind":"RunFinished","outcome":"success"}
```

```text
exit_code = 0      archived count = 0      RunFinished.outcome = success
```

```text
discriminator = same glob, same workspace
              + allowEmptyArchive=false -> exit 1, typed SCRIPT failure, 0 artefacts
              + allowEmptyArchive=true  -> exit 0, success,                0 artefacts
=> the empty-match failure is policy-driven, NOT a path/glob defect
```

This matters because it is exactly the historical defect shape. At G2 the **legacy**
authority anchored the glob against absolute paths and therefore *never* matched, so
fixture 10 failed end-to-end with `No files matched glob pattern 'build/libs/*.jar'`
(`evidence/s2-b10-g2/fixture10-legacy-glob-defect.json`). AR-G7-02 emits an
**identical-looking** `ArtifactArchiveFailed` + `StepFailed`. Only the discriminator
distinguishes "the policy fired correctly" from "the glob engine cannot match anything".
Without AR-G7-03, row 2 would be a false-green risk of precisely the kind V2 rule 21 and
the E-EM-11 precedent warn about.

---

## 6. AR-G7-04 — frozen delta D2 on the real path (PASS)

```kotlin
sh("mkdir -p build/libs && echo keep > build/libs/keep.jar && echo skip > build/libs/skip.jar")
archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false, excludes = "**/skip.jar")
```

```text
archived relPaths = ["build/libs/keep.jar"]           (skip.jar excluded)
keep.jar sha256   = f660a7996deacfbc7560e4240054a8ad82eb02fe25a95064257e07084bcacb85  (size 5)
exit_code = 0     RunFinished.outcome = success
```

The legacy dispatcher **silently ignored** `excludes` (frozen at G2 as delta D2). The
certified candidate applies them after the Jenkins default excludes. This row is the
installed-path confirmation that D2 holds where it is observable, complementing the
in-process `AntStyleGlobTest` authorities named in the G6 matrix.

---

## 7. Absence probe — the legacy authority is gone from the shipped distribution (PASS)

```text
source (src/main): 0 files matching CanonicalArchiveArtifacts*
jars_scanned              = 37
legacy_jar_hits           = 0
registry_control_jars     = 1     (CoreArchiveArtifactsStep present)
```

The positive control is what makes the negative meaningful: the same probe that finds no
legacy dispatcher **does** find the registry step class inside a shipped jar, so
`legacy_jar_hits = 0` is the absence of that class, not the absence of the probe's
scanning ability. This independently re-confirms G5's `LEGACY_REMOVED` claim against the
built artefact rather than against the source tree.

---

## 8. Read-only re-verification of the legacy residual triple

Measured directly at `2271fb1e` (independent of G5's receipt):

| Authority | live value at `2271fb1e` | count |
| --- | --- | --- |
| `LEGACY_PLUGIN_IDS` | `{core.load, core.waitUntil}` | 2 |
| `CanonicalCoreStepMetadata` legacy `core.*` rows | `core.load`, `core.waitUntil` | 2 |
| `Canonical*NodeDispatcher.kt` (excl. central `CanonicalNodeDispatcher.kt`) | `CanonicalLoadNodeDispatcher.kt`, `CanonicalWaitUntilNodeDispatcher.kt` | 2 |

```text
live ids / metadata rows / dispatcher files = 2 / 2 / 2   (converged)
```

G7 changes nothing here: it is a read-only confirmation that the triple did not drift
between the G5 receipt and trunk.

**Counter-clarification (recorded to prevent a recurring misreading).** The `N/N/N`
triple used by the per-gate certification chain is *ids / metadata rows / dispatcher
files* — the **legacy residual triple**. It is a different counter from the AGENTS.md
project-dashboard counters (`Certified Steps` / `Legacy executable Steps` /
`Registry-primary Steps`), which the G5 receipt deliberately leaves as `?` because a
single lane cannot author the dashboard. `2/2/2` is therefore the *correct and final*
value of the residual triple both before and after G8; it is not evidence that
`core.archiveArtifacts` failed to converge.

---

## 9. Freshness: what was and was not re-run

| Evidence | Status at G7 | Reason |
| --- | --- | --- |
| `CoreArchiveArtifactsStepContractSuiteTest` 27 / 0 / 0 | fresh at `2271fb1e` (G6 canary, 26 classes / 318 / 0 / 0) | the test file and all production under test are byte-identical to that run; V2 rule 20 forbids re-running green evidence whose inputs did not change |
| `installDist` | fresh at `2271fb1e` | rebuilt in the G7 worktree; jar sha256 recorded |
| 4 installed scenarios | fresh at `2271fb1e` | this gate |

G7 changes **no production code and no test code**, so no test ownership moved and no
validation-ladder escalation is warranted (V2 rule 17, docs-only + executable
scenarios).

---

## 10. Files changed by this gate

```text
 A docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-01-nonempty-byte-identical.pipeline.kts
 A docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-02-empty-match-fail.pipeline.kts
 A docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-03-empty-match-allow.pipeline.kts
 A docs/v2/07-uat/evidence/s2-b10-g7/ar-g7-04-excludes.pipeline.kts
 A docs/v2/07-uat/evidence/s2-b10-g7/run-g7.sh
 A docs/v2/07-uat/evidence/s2-b10-g7/assemble-evidence.py
 A docs/v2/07-uat/evidence/s2-b10-g7/g7-installed-acceptance.json
 A docs/v2/07-uat/evidence/s2-b10-g7/g7-raw-sha256.txt
 A docs/v2/07-uat/evidence/s2-b10-g7/raw/**            (21 raw outputs)
 A docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md  (this file)
```

Production `v2/**/src/main/**`: **0**. Test `v2/**/src/test/**`: **0**.

---

## 11. Certification status

```text
core.archiveArtifacts: IMPLEMENTED_UNCERTIFIED
```

| Gate | State |
| --- | --- |
| G4 REGISTRY_PRIMARY (`435f5f8b`) | merged |
| G5 LEGACY_REMOVED (`00416000`) | merged |
| G6 contract certification 17/17 (`2271fb1e`) | merged |
| **G7 installed acceptance** | **this receipt** |
| G8 CERTIFIED | **open** |

`CERTIFIED` is **not** recorded here. Per ADR-0074 a Step is `CERTIFIED` only at the final
gate, and no receipt in this chain may record `DONE`/`PASS` for an uncertified Step.

---

## 12. Reproduction

```bash
export JAVA_HOME=…/asdf/installs/java/temurin-24.0.2+12
cd docs/v2/07-uat/evidence/s2-b10-g7
timeout 1500 ./../../../../v2/gradlew -p v2 :pipeline-application:installDist
timeout 900 ./run-g7.sh              # 4 real CLI runs, fresh dbs + control roots
python3 assemble-evidence.py         # exits 0 iff every verdict is PASS
python3 verify-receipt.py            # exits 0 iff every sha256 cited in the receipt
                                     #   resolves to a real, current file hash
sha256sum -c g7-raw-sha256.txt       # 43/43 OK
```

`run-g7.sh` deletes and recreates `/tmp/ar-g7-ws` and re-derives every number from the raw
outputs; `assemble-evidence.py` exits non-zero if any scenario's verdict is not `PASS`, so
the gate cannot pass by transcription error.

`verify-receipt.py` closes the remaining transcription hole. During this gate the receipt
twice carried a hash that did not describe the file it named: once stale (the assembler was
edited after its hash was copied in) and once a placeholder that was never computed.
Presence-checking a hash string would have passed both. The verifier instead resolves every
cited digest against the filesystem and the archived raw manifests, and its negative control
was exercised: re-introducing the stale hash of `assemble-evidence.py` from the first
draft makes it exit 1 with that citation named.
Hash citations are now mechanical, not editorial.
