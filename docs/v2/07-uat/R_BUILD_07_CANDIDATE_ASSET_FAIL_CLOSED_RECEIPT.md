# R-BUILD-07 — `candidateAdmission` can no longer observe its own dependencies optionally

**Defect class:** silent-absence sentinel (fail-open). Same shape as the `locateSbom` null whose
consumer discarded it.
**Commits:** `13590e76` (the fix), this receipt + `CandidateAssetFailClosedFitnessTest` (the guard).
**Base:** `13590e766ac936e537ec9fde10c42f0833689836`.
**Fidelity:** SOURCE-LEVEL for the guard; the fix itself was verified BEHAVIOURALLY by running the
real `candidateAdmission` task, because it is on the candidate path and a module test would prove
nothing about the bytes it admits.

---

## 1. The defect

`v2/pipeline-release/build.gradle.kts`, task `candidateAdmission`, declared:

```kotlin
dependsOn(":pipeline-application:cyclonedxBom")
dependsOn("sdkZip")
```

and then observed what those two produced like this:

```kotlin
val sbom = listOf(...).firstOrNull { it.isFile }
val sdkZip = dist.resolve("pipelinek-$version.sdk.zip").takeIf { it.isFile }
val bomJson = dist.resolve("pipelinek-bom-$version.json").takeIf { it.isFile }
```

Three routes to the same absence:

| asset | how an absence was expressed | reached argv as |
| --- | --- | --- |
| SBOM | `null` | `"-"` |
| SDK ZIP | `null` | `"-"` |
| BOM JSON | `null` | `"-"` |

`CandidateAdmissionMain.kt:45-62` maps `"-"` back to `null`, so the handoff recorded a candidate
carrying none of the three, and the task exited 0.

### Why nothing downstream noticed

`covered` shrank with them. `CandidateMaterializer.materialize`'s only completeness invariant is
`covered.isNotEmpty()` — and `covered` was never empty, because the ZIP is checked. So a
`SHA256SUMS` covering the runtime ZIP alone satisfied every existing invariant, and the file it
wrote was still internally consistent and still verifiable. The digest certified a smaller profile
as if it were the whole one, which is the precise reason it could not reveal the damage.

§8 of the DISTRIBUTION-FIRST RELEASE POLICY fixes the asset set at exactly five members and §5
makes an incomplete one `PUBLICATION_REFUSED`. Neither happened.

## 2. The fix

The ZIP already had its `doFirst` guard; the three others now have one each. SBOM keeps its own
guard because it is copied out of a `dependsOn` task's output directory; the SDK ZIP and the BOM
manifest share `requiredAsset(`, because they come from the same `sdkZip` dependency.

```kotlin
fun requiredAsset(asset: String, file: File, producedBy: String): File {
    if (!file.isFile) {
        throw GradleException(
            "DIST-PRODUCT: $producedBy is a declared dependency of this task but " +
                "produced no $file. Refusing to admit a candidate without $asset; ..."
        )
    }
    return file
}
```

Consequences that matter more than the checks themselves:

- each binding is now a non-null `File`, so `?: "-"` is unrepresentable rather than merely absent;
- `covered` is `listOf(...)`, not `listOfNotNull(...)` — the second door for the same null is shut;
- the guard runs in `doFirst`, before the `JavaExec`, so a refusal is attributable to this check
  alone: nothing else in that task can refuse at that point.

**Denominator, to avoid opening a second authority.** Measured by enumeration over `v2/` (not by
guessing filenames): no type, value class or catalogue expressing "a complete asset set" exists —
`assetSet|candidateAsset|requiredAsset|fiveAsset|ASSET_SET` return nothing. So the fix lives at
the guard, not in the model. `DistributionManifest.kt:61-75` keeps `sdk` and `bom` optional in the
published schema; changing that is a contract change (ADR), not a bug fix, and is deliberately not
done here.

## 3. Verification — the fix, both directions

### 3a. Baseline: inert on the happy path

The checks must not fire when the dependencies deliver. Run against a clean tree with the checks
in place:

```
cd v2 && ./gradlew :pipeline-release:candidateAdmission
```

`PASSED`. This also incidentally exercised the provenance guard: an earlier attempt at this
baseline failed with

```
[release] candidate admission REFUSED — the working tree carries uncommitted changes to
tracked files (1 modified, 0 staged)
```

which was this receipt's own uncommitted edit, refused by a pre-existing guard. That refusal is the
guard working: it proves the three new checks passed and execution reached the application.

### 3b. Mutation: the check fires, with an attributable diagnostic

A single missing mandatory asset, with the dependency deliberately declared and run:

```
cd v2 && ./gradlew :pipeline-release:candidateAdmission
...
> DIST-PRODUCT: :pipeline-release:sdkZip is a declared dependency of this task but produced no
  <...>/pipelinek-0.45.0-SNAPSHOT.sdk.zip. Refusing to admit a candidate without
  pipelinek-0.45.0-SNAPSHOT.sdk.zip; §8 of the DISTRIBUTION-FIRST RELEASE POLICY fixes the asset
  set exactly, and a missing member is a defect to fix, not a variant to ship around.
```

`BUILD FAILED`. The message names the dependency, the missing asset, and the full path.

## 4. Verification — the guard itself, mutation-tested in BOTH directions

`CandidateAssetFailClosedFitnessTest` is the permanent protection: the mutation in §3b proves the
check fires today, and nothing but a row prevents a future one-line revert to
`takeIf { it.isFile }`.

Four traps this repository has receipts for, and how each is closed:

1. **A banned-pattern row alone is vacuous** — it passes if the whole task is deleted, or rewritten
   to assert nothing. So the FIRST row is positive: each asset must be bound to the guard that
   actually refuses it, by adjacency.
2. **A scanner blind to its own subject** — this block's own KDoc describes the defect and
   therefore NAMES `takeIf { it.isFile }` and `?: "-"` verbatim, three times between them. A scan
   reading comments would report the defect present and become unfalsifiable. So whole comment
   lines are dropped first — by `trimStart().startsWith("//")`, NOT by truncating at the first
   `//`, which would also cut inside a string literal containing a URL.
3. **A subject that vanishes leaves the guard green** — `candidateAdmissionBlock()` FAILS if the
   registration cannot be located or if the block has no column-zero closing brace.
4. **Half a property** — the SBOM is guarded by `if (!bomSource.isFile)` and the other two by
   `requiredAsset(`. Pretending one shape fits all three would assert a shape nobody wrote.

### Measured

All four rows below run the real class; the XML timestamp is the evidence that the task executed.

| # | Mutation | Row 1 (bound to guard) | Row 2 (no sentinel) |
| --- | --- | --- | --- |
| baseline | none | GREEN | GREEN |
| **M1b** | full original defect re-introduced in a **compiling** form: `sdkZip` → `takeIf { it.isFile }`, `covered` → `listOfNotNull(...)`, argv → `sdkZip?.absolutePath ?: "-"` | **RED** | **RED** |
| **M2** | asset NAME kept in the block, removed from the `requiredAsset(` argument | **RED** | GREEN |
| **M3** | task registration renamed, so the row cannot find its subject | **RED** | **RED** |

M2 is the one that proves the design is not decorative: the literal `pipelinek-$version.sdk.zip`
is still present in the file (inside `dist.resolve(...)`), so a name-only scan stays green, and only
the adjacency assertion flips. M2 killing row 1 and leaving row 2 green is the 1:1 attribution
HARNESS FIDELITY LAW §5 asks for.

### A mutation that survived for the wrong reason, recorded because it nearly read as a finding

The first attempt at M1 degraded only the `sdkZip` binding. It produced **GREEN on a mutation** —
which is the outcome most likely to be misread as "the guard does not work".

It did not survive; **it never ran**. `sdkZip.absolutePath` on a `File?` made the build script fail
to compile, so `:pipeline-architecture-tests:test` never executed, and the XML I read was the one
left by the previous run. Proof: the XML timestamp was byte-identical to the baseline's
(`14:22:52.665Z`) and the task name did not appear in the log at all.

Two rules, both already written in `AGENTS.md`, paid for here:

- a failing compile is not a RED (HARNESS FIDELITY LAW §6) — and symmetrically, a mutation that
  breaks the build proves nothing about the row;
- after a build that failed, distrust the previous XML (rule 27) and re-run with a verified canary.

M1b is the same defect re-applied so that it compiles, which is the only form in which it could
ever have reached production.

## 5. Round gate

`cd v2 && timeout 600 ./gradlew :pipeline-architecture-tests:test` — `BUILD SUCCESSFUL in 5m 35s`,
0 compile errors.

Result truth read from the JUnit XML with `xml.etree.ElementTree`, not from the exit code:

```text
classes=117  tests=572  failures=0  errors=0  skipped=0
XML regenerated 2026-10-18 16:29:50 +0200 (this invocation)
CandidateAssetFailClosedFitnessTest  tests=2 failures=0 errors=0 skipped=0
  - each section 8 asset is bound to the guard that refuses it when absent   PASS  0.218s
  - the admission task carries no absence sentinel into argv                 PASS  0.154s
```

`skipped=0` is stated because a `SKIP` reported by the runner as `BUILD SUCCESSFUL` is a green that
is not evidence. Both new rows are present as executed test cases, which is also the canary: the
class did not merely compile, it ran.

## 6. Residual risk, stated rather than hidden

- **The manifest schema still allows `sdk = null` / `bom = null`.** The guard makes that state
  unreachable from this task; it does not make it unrepresentable in the model. Closing it is a
  published-contract change and needs an ADR.
- **`scripts/release-pipelinek.sh:307-311` reads the manifest tolerantly** ("if present in
  manifest"), so it and the now-strict producer disagree about the contract until the reader is
  hardened in the same change. Measured, not run: no publish was attempted with the strict
  producer against the tolerant reader.
- **The guard is textual.** It proves the binding and the absence of the sentinel shapes. It does
  not prove the task refuses at runtime — §3b does that, and it is the reason that row exists
  separately.

## 7. Reference implementation consulted

- `SingleVersionAuthorityFitnessTest.kt` — the existing idiom for a fitness that scans a
  `build.gradle.kts`: `buildRoot` resolved by walking up for a sibling module directory, one rule
  per row, exceptions named inside the rule they except. Matched rather than reinvented.
- Denominator, not guesswork: 31 test files mention `build.gradle.kts`; the closest existing one was
  read rather than a filename guessed.

## 8. Verification report (delivery-first-testing)

```text
Changed:
- v2/pipeline-release/build.gradle.kts — three fail-closed guards in candidateAdmission
- v2/pipeline-architecture-tests/.../CandidateAssetFailClosedFitnessTest.kt — new, 2 rows
- docs/v2/07-uat/R_BUILD_07_CANDIDATE_ASSET_FAIL_CLOSED_RECEIPT.md — this file

Affected SUT:
- the candidate-admission boundary; the SBOM/SDK/BOM bindings and the argv the handoff is built from

Verification executed:
- :pipeline-release:candidateAdmission baseline      — inert on the happy path (PASSED)
- :pipeline-release:candidateAdmission mutation     — fires, message names dependency+asset+path
- CandidateAssetFailClosedFitnessTest baseline + M1b + M2 + M3 — every claim has a killing mutation
- :pipeline-architecture-tests:test (L4)             — see §5

Evidence reused:
- nothing. Every claim here is text-scoped to a file that changed, so prior evidence is invalid.

Verification deliberately not executed:
- the full repository `check` — the change is one task in one module plus one test class; L4 on the
  owning module is the minimum sufficient level.
- the harness certifier and the release publish — external authority, and the risk in §6 is
  explicitly NOT_RUN rather than assumed away.

Unknown impact:
- whether `scripts/release-pipelinek.sh` still tolerates the strict producer's output (measured
  divergence, not exercised).
```