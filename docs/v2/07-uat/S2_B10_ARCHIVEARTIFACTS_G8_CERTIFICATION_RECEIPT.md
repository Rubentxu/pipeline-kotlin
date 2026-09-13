# S2-B10 / G8 — core.archiveArtifacts Final Certification Receipt

> Cycle: `cycle/lfc2-e1-archive-artifacts-g8`
> Slice: S2-B10 (`core.archiveArtifacts`)
> Gate: **G8 — Final Certification (read-only re-verification of all prior gates)**
> code-under-test: `e7ea54ba` (= `origin/main` after the G7 evidence PR #44 landed)
> evidence/receipt commit: this commit on `cycle/lfc2-e1-archive-artifacts-g8`
> Date: 2026-09-13T15:49–15:56Z (local +02:00)
> Production code changes: **0** (ledger-only scope; `git status` clean on the gate base)
>
> G8 closes S2-B10 and **proposes `CERTIFIED = true`** for `core.archiveArtifacts`
> (ADR-0074). This receipt re-verifies every prior gate against current main with
> fresh evidence; the installed-CLI acceptance burden was already discharged at G7
> (`S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md`, 5/5 PASS — no
> scenarios re-run at G8).
>
> Two findings are recorded here rather than smoothed over: the G7 binary citation is
> **not re-verifiable in principle** (§4), and the `:pipeline-application` test
> sources do **not** compile from a clean checkout (§5). Neither invalidates G7's
> evidence; both constrain what a future gate may claim.

## 1. Per-gate verification table (re-verification of CLOSED gates)

G4..G7 are closed facts recorded in their merged receipts. G8 does not re-litigate
them; it re-verifies, on the current main, that the recorded evidence is intact and
that the properties they assert still hold.

| Gate | Closed at (authority) | Re-verification on `e7ea54ba` (2026-09-13) | Verdict |
|---|---|---|---|
| G4 REGISTRY_PRIMARY | `S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md` `d1afb7a22a42…` (ids counter 3 → 2; state 2/3/3) | receipt present and unmodified; `LegacyResidualConvergenceFitnessTest` fresh 3/0/0 confirms the id is gone while the metadata row and dispatcher file still existed at that gate | **CONFIRMED** |
| G5 LEGACY_REMOVED | `S2_B10_ARCHIVEARTIFACTS_G5_LEGACY_REMOVED_RECEIPT.md` `697db0f22a3a…` (counters 2/3/3 → **2/2/2**; `CanonicalArchiveArtifactsNodeDispatcher.kt` deleted) | receipt present; the dispatcher file is **physically absent** (`durable/CanonicalArchiveArtifactsNodeDispatcher.kt` does not exist); the `CanonicalCoreStepMetadata["core.archiveArtifacts"]` row is absent; `"core.archiveArtifacts" !in LEGACY_PLUGIN_IDS`; live residual re-derived as **2/2/2** (§6) | **CONFIRMED** |
| G6 CONTRACT_SUITE | `S2_B10_ARCHIVEARTIFACTS_G6_CONTRACT_CERTIFICATION_RECEIPT.md` `b795f5e0d20f…` (17/17 coverage matrix, 27 tests) | `CoreArchiveArtifactsStepContractSuiteTest` re-run fresh, canary-verified (`cleanTest` deleted the prior XML): **27/0/0**, XML sha256 `3374a09ce519e696…` | **CONFIRMED** |
| G7 INSTALLED_ACCEPTANCE | `S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` `c722bc3e70b2…` (5/5 PASS, `INSTALLED_ACCEPTANCE = true`) | receipt present; **13 of its 14 hash citations resolve** against the tree right now; the 14th is a non-reproducible build output and is classified and proven as such in §4. Scenarios NOT re-run (no relevant input changed; rule 5/7). | **CONFIRMED with one classified exception** |
| Counters 2/2/2 | G5 closure authority (`LegacyResidualSnapshot`) | re-derived from live source by `verify-g8-receipt.py`: ids `{core.load, core.waitUntil}` / 2 metadata rows / 2 per-Step dispatcher files | **CONFIRMED** |
| S3 + Lfc2 fitness green | per-gate receipts | S3 ×7 suites: 7+8+12+9+8+4+4 = **52/0/0** + `Lfc2RegistryFamilyFitnessTest` 3/0/0 + `Lfc2DurableCoordinatorScopeFitnessTest` 4/0/0 + `LegacyResidualConvergenceFitnessTest` 3/0/0, all fresh | **CONFIRMED** |

## 2. Fresh JUnit XML evidence (canary discipline)

Every XML below was regenerated 2026-09-13T15:50:37Z (UTC) via
`./v2/gradlew -p v2 --no-build-cache :<module>:cleanTest :<module>:test --tests '<class>' --rerun-tasks`
(`cleanTest` deletes the prior XMLs, so regeneration *is* the freshness canary,
rule 25). Counts and per-XML sha256 were extracted by `assemble-g8-evidence.py`
into `g8-canary.json` — derived, never transcribed. The 12 XMLs themselves are
**committed** under `raw/xml/`, so their digests resolve as ordinary files rather
than as recollections. Result truth is the XML, not the console and not the Gradle
exit code.

### `:pipeline-application`

```text
CoreArchiveArtifactsStepContractSuiteTest   tests=27 skipped=0 failures=0 errors=0
  xml sha256 = 3374a09ce519e6964aec6770d480e8f6a87b8e4f4691838443bf6a2e7897b075
```

### `:pipeline-architecture-tests`

```text
LegacyResidualConvergenceFitnessTest   tests=3  f=0 e=0  d8b4569064e11634…
Lfc2RegistryFamilyFitnessTest          tests=3  f=0 e=0  1ac4719fb364bb51…
Lfc2DurableCoordinatorScopeFitnessTest tests=4  f=0 e=0  91f644ef894a815d…
S3EchoLegacyRemovedFitnessTest         tests=7  f=0 e=0  229037231d9f0330…
S3EmitEventLegacyRemovedFitnessTest    tests=8  f=0 e=0  188faf955da12ff4…
S3ErrorLegacyRemovedFitnessTest        tests=12 f=0 e=0  7f5d4a1303281db4…
S3IsUnixLegacyRemovedFitnessTest       tests=9  f=0 e=0  173ecea437de32f8…
S3PwdLegacyRemovedFitnessTest          tests=8  f=0 e=0  43e2e121322c4e15…
S3SleepLegacyRemovedFitnessTest        tests=4  f=0 e=0  1013266fe379bd5a…
S3WriteFileLegacyRemovedFitnessTest    tests=4  f=0 e=0  7945074511d05352…

S3 legacy-removed totals: 52/0/0
```

`Lfc0GlobalStateFitnessTest` — **pre-existing red, isolated, NOT counted**:

```text
Lfc0GlobalStateFitnessTest             tests=2  f=1 e=0  1dd870c5b6627f4d…
failure: production code does not access the controller user directory property()
```

Baseline honesty: this gate ran in a worktree at `e7ea54ba` with **zero tracked
modifications** (`git status --short` empty), so this run *is* the baseline
reproduction rather than a comparison against a remembered one. The same single
failure is recorded as pre-existing in the cleanWs G8 receipt
(`S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md`) and in the deleteDir and
milestone G8 receipts. It is unrelated to `core.archiveArtifacts`.

Source-of-truth hashes at verification time (resolved hands-free by
`verify-g8-receipt.py`):

```text
CoreArchiveArtifactsStep.kt             sha256 = 6372e6117d45…
ArchiveArtifactsOperations.kt           sha256 = 5de35661c482…
CoreArchiveArtifactsStepContractSuiteTest.kt sha256 = cc6b0c21d7ae…
G7 receipt                              sha256 = c722bc3e70b2…
STEP_INVENTORY_LFC2E0.md (post-G8 flip) sha256 = 442a7a27657c…
```

## 3. Why G8 re-runs no installed scenarios

Per ADR-0074 the certification proof of an effectful/recoverable Step is the
conjunction: registry-only authority (G4/G5) ∧ typed contract suite (G6) ∧ real
installed-distribution acceptance (G7).

G7 already executed the installed CLI: real archive creation, byte-identical payload
(the non-empty digest matching an externally computed `seq 1 2000 | sha256sum`), the
empty-match failure path with `allowEmptyArchive=false` versus its discriminator with
`true`, the `excludes` delta, and physical legacy absence across 37 installed jars.
No relevant input has changed since that green run, so per rule 5/7 its evidence
remains fresh and re-running the scenarios would buy no new information. G8 therefore
re-verifies evidence **integrity** (hashes, counts, live residual) rather than
re-executing scenarios.

## 4. Finding — the G7 binary citation is not re-verifiable in principle

The G7 receipt cites the distribution jar at
`v2/pipeline-application/build/install/.../pipeline-application-0.1.0-SNAPSHOT.jar`
by sha256 `814d8d801551bf…`.

Running G7's own verifier (`verify-receipt.py`) at G8 reports **13 of 14 citations
resolving and exactly this one failing** (`raw/g7-verifier-at-g8.txt`). The cause is
not staleness but class:

1. The artifact is a **build output**. `build/` is gitignored, so it does not exist
   in a fresh worktree at all. Its absence is expected, not a gate failure.
2. It is **not reproducible even from the identical source tree**. Rebuilding the
   distribution at the identical commit `e7ea54ba` on a clean tree produced
   `3eab7703a19e896…`, which differs from the cited `814d8d80…`. Both hashes are
   recorded (`raw/g7-cited-jar-sha256.txt`, `raw/rebuild-jar-sha256.txt`) and the
   verifier only grants the "non-reproducible" class when the two **differ**, so the
   class is a demonstrated property rather than an excuse.

Consequence: no future gate can ever confirm that citation by hashing. It should not
be read as a defect in G7's *evidence* — the proof of G7 is the raw run outputs
(exit codes, stdout, archived-file digest manifests, `events.jsonl`), and the jar hash
served only to bind those runs to a particular built binary. But a hash that cannot
be re-derived is weaker provenance than it appears, and the `dist` jar is not the only
instance: the example-plugin jar built during this very gate has the same property
(same byte size, different sha256 across two builds).

**Proposed discipline (for the next gate that touches receipts, not applied here):**
a receipt citing a build output should cite `(commit SHA + exact build command +
output-path)` as the binding, and record the byte hash explicitly as a *run-time
record* that is not expected to re-derive. Naming the class is the fix; inventing a
re-check for it is not.

## 5. Finding — `:pipeline-application` test sources do not compile from a clean checkout

The G8 canary initially failed with `:pipeline-application:compileTestKotlin FAILED`:
`UppercaseStepContractSuiteTest.kt` could not resolve `UppercaseStepDefinition`,
`UppercaseCodec`, `UppercaseInput`, `UppercaseOutput`, `UppercaseOutputCodec`.

Cause, in `v2/pipeline-application/build.gradle.kts`:

```kotlin
testImplementation(files(rootDir.resolve(
    "../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
```

The module's test compilation depends on a **pre-built jar of an independent build**.
`examples/example-uppercase-plugin` has its own `settings.gradle.kts`, is not included
in the `v2` build (`v2/settings.gradle.kts` has no `includeBuild`), and **no recipe,
script or README documents the prerequisite**. A new worktree therefore cannot compile
the test sources, and neither can a clean clone: `./gradlew -p v2 check` fails before
running a single test until someone manually builds the plugin jar.

This has been masked because every worktree used so far inherited the jar from an
earlier manual build (`pipeline-kotlin` still carries the Sep 9 artifact), so the
defect is invisible on the long-lived worktree and appears on every new one.

The G8 canary was unblocked the documented-by-absence way
(`./v2/gradlew -p examples/example-uppercase-plugin jar`, exit 0, 12 s), and that is
recorded rather than hidden. This receipt does **not** fix the build wiring: it is a
build change, and lane A is scoped to ledger/status. The finding is reported for its
own lane.

**Related latent risk:** `examples/example-uppercase-plugin/libs/*.jar` **are**
committed (`pipeline-domain-0.1.0-SNAPSHOT.jar`, `pipeline-scripting-api-0.1.0-SNAPSHOT.jar`).
The plugin therefore compiles against pinned SDK binaries that can silently drift from
the live `v2` source they are supposed to represent. Any SDK contract change that the
plugin is not rebuilt against would go unnoticed until runtime.

## 6. Gate ledger close

```text
core.archiveArtifacts:
  REGISTERED           = true   (G1, CoreArchiveArtifactsStep registry candidate)
  REGISTRY_PRIMARY     = true   (G4, S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md, 2/3/3)
  LEGACY_REMOVED       = true   (G5, S2_B10_ARCHIVEARTIFACTS_G5_LEGACY_REMOVED_RECEIPT.md, 2/2/2)
  CONTRACT_SUITE       = true   (G6, CoreArchiveArtifactsStepContractSuiteTest 27/0/0; matrix 17/17)
  INSTALLED_ACCEPTANCE = true   (G7, S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md, 5/5 PASS)
  CERTIFIED            = true   ← PROPOSED by this G8 receipt
```

Live residual re-derived at G8 (`raw/residual-live.txt`):

```text
ids             = {core.load, core.waitUntil}                     (2)
metadata rows   = {core.load, core.waitUntil}                     (2)
dispatcher files= {CanonicalLoadNodeDispatcher.kt,
                   CanonicalWaitUntilNodeDispatcher.kt}           (2)   [generic
                   CanonicalNodeDispatcher.kt excluded, not per-Step]
RESIDUAL TRIPLE = 2/2/2   ← unchanged by this slice; G8 mutates no authority
```

## 7. Burn-down counters at close

Certified core Steps on main (each entry backed by a MERGED G8/final certification
receipt; this slice adds ONLY `core.archiveArtifacts`):

```text
CERTIFIED on main (before this proposal): 10
  core.echo, core.sh, core.error, core.sleep, core.file.writeFile,
  core.emit.event, core.isUnix, core.deleteDir, core.milestone, core.cleanWs
PROPOSED by this receipt:                 core.archiveArtifacts  → 11 total
  + example.uppercase  (external reference plugin, counted separately
                        from the core LEGACY_PLUGIN_IDS keys)
```

```text
Certified core Steps on main BEFORE this slice:  10
Added by THIS receipt (proposal):                core.archiveArtifacts → 11
Legacy executable Steps (M):                      2   (residual 2/2/2 = {load, waitUntil})
Registry-primary Steps:                           11 CERTIFIED + core.pwd (registry-routed but
                                                      IMPLEMENTED_UNCERTIFIED pending LFC-2R2)
```

Explicitly NOT in the certified set, state preserved and not touched by this slice:

```text
core.pwd       : G7 STOP/BLOCKED — STRUCTURED_DSL_RUNTIME_RETURN_GAP (lane E / LFC-2R2).
core.waitUntil : S2-A8, G3 evidence-corrected to IMPLEMENTED_UNCERTIFIED (BodyInvoker, lane C).
core.load      : SPIKE-018 analysis; canonical path is a silent no-op stub (lane D).
```

Note (precedent S2-A7/G8 `dc96b026`): the E0 inventory header counters
(`STEP_ECOSYSTEM_MATRIX.md` "CERTIFIED: 3"; `STEP_INVENTORY_LFC2E0.md` machine-derived
`Registry: 3 / Legacy: 11`) are historical and left as-is. This slice updates only
the `core.archiveArtifacts` table row and adds its closure block.

## 8. Evidence integrity mechanics

```text
verify-g8-receipt.py     resolves every sha256 this receipt cites, asserts the
                         manifest and test-count claims, and re-derives the residual
                         triple from live source. Build outputs are accepted only in
                         two explicitly named classes (recorded XML digest; proven
                         non-reproducible build output).
assemble-g8-evidence.py  derives g8-canary.json from the JUnit XMLs.
g8-raw-sha256.txt        19 entries, `sha256sum -c` verified 19/19 OK
                         7/7 OK.
```

### Negative controls (the verifier is not a no-op)

A checker that cannot fail proves nothing, so five mutations were injected one at a
time and each was required to fail the verifier. All five did, and the receipt was
restored byte-identical afterwards (`exit=0`):

```text
injected mutation                                     expected   observed
  stale hash (contract-suite XML digest, last hex)     FAIL       FAIL (exit 1)
  contract-suite test count, decremented by one        FAIL       FAIL (exit 1)
  residual triple's dispatcher digit, 2 changed to 3   FAIL       FAIL (exit 1)
  delete raw/rebuild-jar-sha256.txt (class unproven)   FAIL       FAIL (exit 1)
  manifest entry count, understated by one             FAIL       FAIL (exit 1)
```

The mutations are described rather than quoted: writing the rejected values verbatim
into this table would make the verifier reject *this receipt*, since every `a/b/c`
triple it sees must belong to the evidence. The check is not given an exemption for
documentation, because such an exemption is exactly where a contradiction would hide.

Controls 2 and 3 initially **passed**, which exposed a real hole in this verifier: it
asserted that the expected number appeared *somewhere*, and a mutation survives that
whenever the same number is also stated elsewhere — which it is, in the per-gate table
and in the ledger. The check now asserts consistency instead: every `a/b/c` triple
anywhere in the receipt must be a value the evidence permits. That immediately
surfaced `2/3/3`, the historical G4 counter state, which is now declared explicitly in
`HISTORICAL_COUNTER_STATES` rather than quietly tolerated.

Reproduce from a checkout at `e7ea54ba`:

```bash
# prerequisite that nothing documents (finding §5)
./v2/gradlew -p examples/example-uppercase-plugin jar
./v2/gradlew -p v2 --no-build-cache :pipeline-application:cleanTest :pipeline-application:test \
    --tests '*CoreArchiveArtifactsStepContractSuiteTest*' --rerun-tasks
./v2/gradlew -p v2 --no-build-cache :pipeline-architecture-tests:cleanTest :pipeline-architecture-tests:test \
    --tests '*LegacyRemovedFitnessTest*' --tests '*LegacyResidualConvergenceFitnessTest*' \
    --tests '*Lfc2*FitnessTest*' --rerun-tasks
cd docs/v2/07-uat/evidence/s2-b10-g8
python3 assemble-g8-evidence.py      # exits 0 iff every expected class is as expected
python3 verify-g8-receipt.py         # exits 0 iff every citation and claim holds
sha256sum -c g8-raw-sha256.txt       # 7/7 OK
```

## 9. Side effects

None on production code. Deliverables: this receipt, the G8 evidence directory,
the `g8-canary.json` derived record, and the `core.archiveArtifacts` ledger flip in
`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`.

Legacy counters frozen at **2 / 2 / 2**. Trunk: `cycle/lfc2-e1-archive-artifacts-g8`
opens a ledger-only PR against main at `e7ea54ba`.

## 10. LFC-2E1 counter party

| counter | pre-S2-B10 | post-G4 | post-G5 | post-G6 | post-G7 | post-G8 (this PR) |
| --- | --- | --- | --- | --- | --- | --- |
| `LEGACY_PLUGIN_IDS.size` (ids) | 3 | 2 | 2 | 2 | 2 | 2 |
| `CanonicalCoreStepMetadata.pluginIds.size` | 3 | 3 | 2 | 2 | 2 | 2 |
| Dispatcher-file count (physical, per-Step) | 3 | 3 | 2 | 2 | 2 | 2 |
| CERTIFIED core Steps | 9 | 9 | 9 | 9 | 9 | **11** |
| Legacy executable Steps (M; convergence) | 3 | 3 | 2 | 2 | 2 | 2 |
| Registry-primary Steps | 10 | 11 | 11 | 11 | 11 | 11 |

(CERTIFIED moves 9 → 11 because `core.cleanWs` (S2-A10/G8) also landed between the
S2-B10 baseline and this receipt; this slice contributes exactly one of those two.)
This slice does not change `M`. Per the AGENTS.md counters panel, `N + M = total`;
convergence is `M → 0` when the `core.load` and `core.waitUntil` lanes close.
