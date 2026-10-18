# R-BUILD-08 — the materializer no longer admits a candidate it cannot digest

**Defect class:** a written guarantee that the code did not implement. Same family as R-BUILD-07,
one layer down: the build script could no longer pass a null sums path, but the consumer still had
a live branch that would have accepted one silently.
**Commit:** `b078bb63`.
**Fidelity:** BEHAVIOURAL, both directions. The claim is "this input is refused", so it is proved
by driving the real `materialize` against real ZIP bytes in both states, and by running the real
`candidateAdmission` task afterwards to confirm the producer still admits a legitimate candidate.

---

## 1. The defect

`CandidateMaterializer.materialize` built its manifest with:

```kotlin
sha256sums = when {
    covered.isNotEmpty() && sha256sums != null -> { writeSha256Sums(...); ref }
    covered.isEmpty() && sha256sums != null -> ref
    else -> null
},
```

and the comment immediately above it stated:

> A non-empty `covered` with a null `sha256sums` path is refused: SHA256SUMS is a contract
> artifact, not a courtesy.

That is the defect. The case named as refused **is** the `else` arm, and the `else` arm writes
`null`. There is no refusal anywhere else in the function: the candidate materialized, and the
manifest recorded `sha256sums = null` next to an asset list that nothing had digested.

Two things made this load-bearing rather than cosmetic:

1. **The build script invoked the promise as its own justification.** `build.gradle.kts:181-183`
   argued that its `covered` construction was acceptable because *"a strict invariant enforced at
   runtime, see CandidateMaterializer.materialize"*. The invariant it cited did not exist, so a
   reviewer reading the guard would see the protection already handled and would not re-check it.
2. **The honest detector's report was discarded.** `locateSbom` correctly returns `null` when no
   SBOM was produced, and `CandidateAdmissionTest` certifies exactly that. That `null` then travelled
   into `covered`, deleting an asset from the digest. The test certified the detector's accuracy
   while its consumer threw the accuracy away — zero coverage of the absent-asset branch in both
   directions.

## 2. The fix

The sums field is now decided **before** the manifest is constructed, so the refusal happens while
nothing has been written — which is what "on any rejection nothing is written" already promised
about the function's two other refusals.

```kotlin
val sumsRef: DistributionArtifactRef? = when {
    covered.isNotEmpty() -> {
        val target = sha256sums
            ?: return MaterializationResult.Refused("covered declares ... nowhere to write it ...")
        Files.createDirectories(target.parent)
        writeSha256Sums(target, covered)
        DistributionArtifactRef(target.fileName.toString(), sha256Of(target))
    }
    else -> sha256sums?.let { DistributionArtifactRef(it.fileName.toString(), sha256Of(it)) }
}
```

The fix **adds a branch rather than removing the nullable path**, because an empty `covered` is a
genuinely different thing and must stay accepted: no assets declared means nothing to digest, and a
pre-existing sums path is then a reference the manifest records without rewriting. That distinction
is exactly what the old `else` collapsed, which is why removing the `Path?` outright would have
been a contract change rather than a bug fix.

## 3. Verification — RED

```
cd v2 && ./gradlew :pipeline-release:test --tests '*CandidateMaterializerTest*'
```

`CandidateMaterializerTest$Refusing`: 4 tests, 1 failure, 0 errors. The failure message is the
defect, not a timeout and not a compile error:

```
org.opentest4j.AssertionFailedError: a non-empty `covered` is a claim that those assets MUST be
digested. With no path to write SHA256SUMS there is nowhere to keep that proof, ...
==> Unexpected type, expected: <...MaterializationResult.Refused>
                              but was: <...MaterializationResult.Materialized>
```

## 4. Verification — GREEN, and the canary that made it readable

Two measurement notes, both of which changed what I could conclude:

- **Gradle writes one XML per `@Nested` class.** The canary path I first guessed,
  `TEST-...CandidateMaterializerTest.xml`, does not exist; the real files are
  `...CandidateMaterializerTest$Materializing.xml` and `...CandidateMaterializerTest$Refusing.xml`.
  Guessing the filename is how a stale report gets read as this run's.
- **`:pipeline-release:test` dependsOn `candidateAdmission`** (`build.gradle.kts:40`), and that task
  refuses a dirty working tree. The first GREEN attempt therefore died in the guard, not in a test.
  A comment there claims the dependency exists so a developer *"running `:pipeline-release:test`
  directly observes the same staging the releaseGate does"* — which is true, and is also why a unit
  test of this class cannot run on uncommitted code.

Owning class, canary deleted first and its regeneration confirmed:

```text
CandidateMaterializerTest$Materializing  tests=4 failures=0 errors=0 skipped=0
CandidateMaterializerTest$Refusing       tests=4 failures=0 errors=0 skipped=0
TOTAL tests=8 failures=0 errors=0 skipped=0
```

`compileKotlin` reported `UP-TO-DATE` here. That is not a stale green in this case, and the run
proves it: the previous invocation had already compiled the edited production file and then failed
downstream in `candidateAdmission`. Had the stale class run instead, the new row would still have
failed with the RED message above. It passed, which is only possible against the new code.

## 5. Verification — the module, and the real bytes

`cd v2 && ./gradlew :pipeline-release:test` on a clean tree (committed), whole module, nothing
excluded. `candidateAdmission` **ran** rather than reporting `UP-TO-DATE`:

```text
classes=31  tests=120  failures=0  errors=0  skipped=7
```

The 7 skips are accounted for, not waved through:

| class | count | reason | verdict |
| --- | --- | --- | --- |
| `CandidateIdentityEndToEndTest` | 6 | `@Disabled("migrated-pending")` **at class level** (line 41) | the declared migration state the responsibility boundary requires |
| `DistributionIdentityProbeTest$AgainstRealCheckedOutArtifacts` | 1 | `Assumption failed: 0.43.0 artifact not present in this checkout` | environment-conditional, with an honest reason |

Neither covers this change. `CandidateIdentityEndToEndTest` is one of the six call sites, so the
`covered = emptyList()` path it would have exercised is still exercised by the other five.

The behavioural proof is the artifact the strict producer actually wrote:

```
$ cat pipeline-release/build/candidate/SHA256SUMS
0c2f6884...  pipelinek-0.62.0.zip
7f8e5dc3...  pipelinek-0.62.0.sbom.json
8a7b37b0...  pipelinek-0.62.0.sdk.zip
7324869b...  pipelinek-bom-0.62.0.json
```

```json
"sha256sums": { "name": "SHA256SUMS",
                "sha256": "1a4d514a380321310588f670c0d389e6c7ca769456c7bc8039042f48c253bd82" }
```

All four mandatory assets are covered, and the manifest records the digest of the sums file it wrote.
This is the byte-level counterpart to R-BUILD-07's producer guards: the two halves now agree, and
each half's absence is a refusal rather than a `null`.

## 6. Residual risk, stated rather than hidden

- **The manifest schema still allows `sha256sums = null`** — legitimately, for the empty-`covered`
  case. Unchanged, and now the only route to that value.
- **`scripts/release-pipelinek.sh:307-311` still reads the manifest tolerantly.** Measured
  divergence, **NOT_RUN**: no publish was attempted with the strict producer against the tolerant
  reader. This is the same item R-BUILD-07 carries, and it is the one thing that must be hardened
  before the strict and tolerant readings converge.
- **The producer is strict and the consumer is now strict, but nothing asserts the pair together** at
  the level of "a missing mandatory asset refuses". That composition is covered by running the real
  task, which is what §5 did, and it is deliberately not frozen as a fitness: it would cost a full
  distribution build per run to learn what §5 already measured.

## 7. Reference implementation consulted

- `MaterializationResult.Refused`, the two refusals already in this same function (`archiveRoot`
  unobservable, identity divergent). The new refusal follows their shape rather than introducing a
  result type, and it runs at the same point — before anything is written.
- `CandidateMaterializerTest.Refusing` — the class and the real-ZIP `buildZip` fixture were reused,
  not reinvented. A refusal tested against a mock archive would not exercise `archiveRootName`.

## 8. Verification report (delivery-first-testing)

```text
Changed:
- v2/pipeline-release/.../CandidateMaterializer.kt       — refusal replaces the `else -> null`
- v2/pipeline-release/.../CandidateMaterializerTest.kt   — one row in Refusing

Affected SUT:
- the candidate materialization boundary: what a manifest may claim about digested assets

Verification executed:
- RED, owning class      — Refused expected, Materialized observed (the defect, verbatim)
- GREEN, owning class    — 8/8, canary verified, XML read with ElementTree
- :pipeline-release:test — 31 classes / 120 tests / 0 failures / 0 errors / 7 skips, each skip
                            accounted for; candidateAdmission ran and wrote real candidate material

Evidence reused:
- none. Production code changed, so prior evidence for this class is void by rule 7.

Verification deliberately not executed:
- the full repository `check` — one function in one module; L4 on the owning module is the minimum
  sufficient level, and the module's test task transitively runs the real candidate admission.
- a publish against `scripts/release-pipelinek.sh` — external authority, and declared NOT_RUN.

Unknown impact:
- whether the tolerant publisher script accepts the strict producer's manifest (measured
  divergence, not exercised).

Result: PASS
Full verification required now: NO — a Step/RC gate is not what this block is.
```