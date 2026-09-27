# C10 — Detekt ratchet repair: stale baseline keys after the D-026 `StageScope` extraction

**Status:** complete (module-scoped evidence; full-repository gate recorded below)
**Repository:** `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-restored`
**Baseline commit:** `3204c19f` (pre-change HEAD for this work item)
**Module:** `pipeline-scripting-api` (renamed) + `pipeline-architecture-tests` (guard path)
**Product impact:** none. No runtime behavior, public DSL contract, Step, registry
routing, durability policy or plugin classification changed.

---

## 1. Problem

`v2/gradlew check` failed at `:pipeline-scripting-api:detekt` with three findings
in `dsl/PipelineDslStageScope.kt`:

| # | Rule | Reported | Source |
|---|------|----------|--------|
| 1 | `TooManyFunctions` | `StageScope` has 53 functions (max 25) | OBSERVED |
| 2 | `CyclomaticComplexMethod` | `StageScope.retry` complexity 35 (max 25) | OBSERVED |
| 3 | `MatchingDeclarationName` | file `PipelineDslStageScope` vs declaration `StageScope` | OBSERVED |

All three originate in a file this work item did **not** touch. Verified by scope:

```
git log --oneline a1441573..HEAD -- v2/.../PipelineDslStageScope.kt   -> (empty)
git diff  a1441573..HEAD --stat -- v2/pipeline-scripting-api/        -> (empty)
```

OBSERVED: the C8/C9 commits changed nothing in this module. The failure is
pre-existing baseline fallout, not a C8/C9 regression.

## 2. Root cause

Commit `4f8a05f1` ("refactor(pipeline-scripting-api): D-026 (audit H1.a) —
extract StageScope from PipelineDsl.kt") moved `StageScope` out of
`PipelineDsl.kt` into a new file `PipelineDslStageScope.kt` and **did not
regenerate the detekt baseline**.

detekt keys baseline suppressions on `<file>:<declaration>`. The two entries
still pointed at the pre-move file:

```
<ID>CyclomaticComplexMethod:PipelineDsl.kt:StageScope$fun retry</ID>   <-- stale
<ID>TooManyFunctions:PipelineDsl.kt:StageScope</ID>                    <-- stale
```

After the move, neither key matches, so findings 1 and 2 surfaced. Finding 3 is
new and never was suppressed: the project deliberately keeps
`MatchingDeclarationName` active — `v2/config/detekt/detekt.yml` explicitly
disables `FunctionNaming`, `MagicNumber`, `ReturnCount` and `ThrowsCount` with
written rationale, but leaves the rest of `naming` at detekt defaults.

## 3. Fix

Three coordinated changes. `StageScope` is the single top-level declaration of
the file (1 of 1), `StageScope.kt` was free, and the class is package-local, so
the rename is source-compatible in Kotlin.

1. **Rename** `dsl/PipelineDslStageScope.kt` -> `dsl/StageScope.kt` (`git mv`),
   satisfying `MatchingDeclarationName` by construction rather than by waiver.
2. **Repoint** the two baseline entries from `PipelineDsl.kt:` to `StageScope.kt:`.
   Same rules, same declarations, same thresholds — only the file key changes.
   No entry was added, removed, or relaxed.
3. **Update** the one hardcoded source path in
   `WULpr402RuntimeHonestDslFitnessTest` (line 64) to the new filename.
4. **Add `StageScope.kt` to the certified structural-seam allowlist** in
   `FArchLeg1ExecutionAuthorityTest` (line 46). This file was **missed** on the
   first pass and was caught by the full `check` run, not by a targeted search.
   See section 4.4.

The rename is not confined to one guard: two independent architecture tests key
their contracts on production filenames. A third (`TRAIN_040_F1...`) names the old
file only as a historical narrative and is left verbatim.

Deliberately **not** done: no detekt threshold changed, no new suppression
added, no rule disabled, no assertion weakened.

`TRAIN_040_F1_CORE_CERTIFICATION_RECEIPT.md` still names the old file. It is a
historical record of what `6cb6377d` corrected at that time, so it is left
verbatim; this receipt supersedes it for current-state purposes.

## 4. Evidence

### 4.1 Green with no caching

```
$ ./gradlew :pipeline-scripting-api:detekt --rerun-tasks
> Task :pipeline-scripting-api:detekt
BUILD SUCCESSFUL in 2s
$ grep -c "<error" pipeline-scripting-api/build/reports/detekt/detekt.xml
0
```

OBSERVED: 0 findings from a forced re-execution. Not a cached green.

### 4.2 Falsification — the corrected keys are load-bearing

Reverting only the two baseline keys to the stale `PipelineDsl.kt:` form
reproduces the original failure exactly:

```
e: .../dsl/StageScope.kt:12:7  Class 'StageScope' with '53' functions detected.
                             The maximum allowed functions per class is set to '25'
                             [TooManyFunctions]
e: .../dsl/StageScope.kt:247:9 The function retry appears to be too complex based on
                             Cyclomatic Complexity (complexity: 35).
                             The maximum allowed complexity for methods is set to '25'
                             [CyclomaticComplexMethod]
BUILD FAILED in 2s
```

OBSERVED: the two entries suppress exactly the two pre-existing D-026 findings
and nothing more. The baseline was then restored and re-verified green.

### 4.3 Dependent guard still green

```
$ ./gradlew :pipeline-scripting-api:detekt :pipeline-architecture-tests:test \
      --tests "*WULpr402RuntimeHonestDslFitnessTest*"
BUILD SUCCESSFUL in 11s
```

OBSERVED: the fitness guard reads the renamed file successfully, so it still
inspects the authoritative `StageScope` source.

### 4.4 Regression found by the full gate, then fixed

The first full `./gradlew check --rerun-tasks` run reported:

```
> Task :pipeline-architecture-tests:test FAILED
319 tests completed, 1 failed

FArchLeg1ExecutionAuthorityTest >
  StepSpec in production is structural IR only - no runtime command interpretation()
  AssertionFailedError: StageScope.kt interprets StepSpec at runtime but is not
  a certified structural seam ==> expected: <true> but was: <false>
```

**This defect was introduced by the rename in this work item.** An initial
`grep -rln "PipelineDslStageScope"` did not surface this test because it matches
on the *path suffix* `PipelineDsl.kt` rather than the literal filename. The
`structuralSeamFiles` allowlist had to gain `StageScope.kt` alongside
`PipelineDsl.kt`, because the certified `StepSpec` interpretation moved with
the class in `4f8a05f1` while the allowlist stayed behind.

Recorded rather than hidden: the targeted verification in 4.3 was insufficient
on its own, and only the full gate surfaced the true change surface.

### 4.5 Corrupted log handling (process note)

The same run's stdout captured an interleaved `BUILD SUCCESSFUL in 1s` from a
*concurrent* build in an unrelated worktree (`wt/recover/pk`), which contradicted
the FAILED task in the same file. The authoritative signal was the final task
line, not the BUILD line. The gate result is therefore taken from per-task
outcomes and test-result XML, never from a `BUILD` line of a multi-build log.

## 5. Known decisions

- **Fix over baseline** for `MatchingDeclarationName`. The project keeps that
  rule active on purpose; baselining it would silently contradict stated house
  intent and re-hide a future real mismatch.
- **Rename over suppression** for findings 1 and 2, because they are
  pre-existing D-026 debt whose suppression already existed and had only gone
  stale. Re-pointing preserves the original decision rather than re-litigating it.
- Finding 3's file rename also reduces the chance of recurrence: the class can
  no longer be moved without detekt noticing the name relationship.

## 6. Scope classification

- This repair is **inside** the blocker "full `check` gate is unestablished" and
  is required before any roadmap work can claim a green baseline.
- It is **not** a C8/C9 change. It is separate, pre-existing debt surfaced by
  the gate, and is committed separately from the C8/C9 receipt.

## 7. Outstanding

- Structural debt left intentionally untouched: `StageScope` still has 53
  methods and `retry` still has complexity 35, both suppressed by the baseline.
  This repair makes them *visible and correctly tracked*, not *smaller*.
- The 15 unpublished local commits destroyed with the original working tree
  remain unrecoverable.

## 8. Final gate result (added after C12 and C13)

Once C10, C11, C12 and C13 were all in place, the repository-wide gate was run
clean, as a single build on a quiet machine:

```
$ ./gradlew check --rerun-tasks
BUILD SUCCESSFUL in 18m 16s
```

Authoritative figures, aggregated from every module's JUnit XML (not from log
text, which was contaminated by a concurrent build in another worktree during
earlier attempts):

```
TOTAL tests=3194   failures+errors=0
detekt findings across all modules: 0
```

Named suites in that run:

| Suite | tests | failures | errors |
|---|---|---|---|
| `UatCompat001CorpusSmokeRunTest` | 2 | 0 | 0 |
| `CompatibilityCorpusTest` | 30 | 0 | 0 |
| `FArchLeg1ExecutionAuthorityTest` | 4 | 0 | 0 |
| `WULpr402RuntimeHonestDslFitnessTest` | 7 | 0 | 0 |
| `Rp022ThroughputProbe` | 1 | 0 | 0 |
| `WorkspaceCleanupTest` | 20 | 0 | 0 |
| `WorkspaceResolverTest` | 15 | 0 | 0 |

**The 25-module repository-wide gate is now ESTABLISHED.** It was
UNESTABLISHED when this work item began.

