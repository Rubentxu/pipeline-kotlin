# WU-LPR-078 — DIAG: 3 pre-existing architecture fitness failures (NOT a regression)

**Date**: 2026-09-20
**Status**: CLOSED (DIAGNOSED, NOT A REGRESSION)
**Cycle base**: `adab94d1` (WU-LPR-074) — pre-LPR-075 cycle base per AGENTS.md rule 16
**Branch**: `main`
**Module**: `pipeline-architecture-tests` (`:pipeline-architecture-tests:test`)

---

## Trigger

After publishing LPR-076 (`UatCompat001CorpusSmokeRunTest`) and LPR-077
(`UatLocal005CorpusUntouchedTest > CP-002`) and committing them to `main`,
a wider module sweep surfaced 3 failures in the architecture fitness
suite (`pipeline-architecture-tests`):

```
Lfc0GlobalStateFitnessTest > production code does not access the controller user directory property() FAILED
Lfc0V1QuarantineFitnessTest > root README names pipeline-kotlin and references the LFC roadmap() FAILED
FArchL7JenkinsVerbatimStepTest > all_step_shapes_match_jenkins_catalog() FAILED
```

The LPR cycle had only touched test files (`*Test.kt`) and receipts, so
the first hypothesis was "are these introduced by LPR-074/075/076/077?"
which AGENTS.md rule 16 forces to be answered with a worktree comparison
against the cycle base SHA.

## Diagnosis

Reproduced all three against a fresh worktree of `adab94d1` (cycle
base, pre-LPR-075). Log: `$JCODE_SCRATCH_DIR/lpr078-base-arch.log`,
SHA-256 `a1edb0086521763528369b773c6780668073f1eded795f918536792a988673d7`.

| Test | Base (`adab94d1`) | HEAD (`88fdda29`) | Verdict |
|------|---------------------|---------------------|---------|
| `Lfc0GlobalStateFitnessTest` | FAILED (1/2) | FAILED (1/2) | **pre-existing** |
| `Lfc0V1QuarantineFitnessTest` | FAILED (1/5) | FAILED (1/5) | **pre-existing** |
| `FArchL7JenkinsVerbatimStepTest` | FAILED (1/2) | FAILED (1/2) | **pre-existing** |

`git diff --stat adab94d1 HEAD -- v2/pipeline-architecture-tests/` is
**empty** — zero files in that module changed during the cycle. The
files referenced by the failures
(`pipeline-step-sdk/scm-git/.../GitCheckoutStepDefinition.kt`,
`pipeline-step-sdk/junit/.../JUnitResultsStepDefinition.kt`, the root
README, and `StepSpec$ArchiveArtifacts` constructor) were **not** touched
by any LPR-074/075/076/077 commit.

### Root causes (each one is its own pre-existing work)

1. **`Lfc0GlobalStateFitnessTest`** — two production Step plugins
   (`GitCheckoutStepDefinition.kt:82` and `JUnitResultsStepDefinition.kt:57`)
   fall back to `System.getProperty("user.dir")` when the
   `pipeline.workspace.root` property is unset. The LFC-0 global state
   fitness forbids reading the controller's user directory from
   production code. **Tracked by the LFC2-E1 / CTX-P / WorkspaceResolver
   work** (already merged as `WorkspaceResolverTest` in
   `:pipeline-application:durable`). The Step plugins have not yet been
   migrated to consume `WorkspaceResolver` directly.

2. **`Lfc0V1QuarantineFitnessTest`** — the root README does not yet
   contain a `LOCAL_FOUNDATION_CONSOLIDATION.md` link or the literal
   `LFC/Local Foundation Consolidation` token. **Documentation gate;
   unrelated to test changes.**

3. **`FArchL7JenkinsVerbatimStepTest`** — the
   `dev.rubentxu.pipeline.v2.dsl.StepSpec$ArchiveArtifacts` class is
   expected to have a constructor `(String, Boolean, String, Boolean)`
   for `(artifacts, allowEmptyArchive, excludes, fingerprint)`. The
   current Step is implemented behind the registry path
   (`CoreArchiveArtifactsStep`) and the `StepSpec` data class has been
   retired under the open-world Step constitution (ADR-0070..0074).
   **Tracked by the in-flight `cycle/lfc2-e1-archive-artifacts-g8`
   branch** (worktree `pipeline-archive-g8`), which migrates
   `archiveArtifacts` through the same G0..G8 burn-down used for
   `core.echo`/`core.sh`.

## Decision

Per AGENTS.md "V2 DEVELOPMENT PRIME DIRECTIVE" §3 — *No V2 dependency
on legacy; classify + quarantine*. These three failures are
**pre-existing and quarantined** to the LFC-2 E1 / archive-artifacts
cycle, NOT to the LPR-074/075/076/077 corpus cycle. The LPR cycle is
**not blocked** by them — its own scope (corpus smoke runner +
inventory lock-step) is green and confirmed by the bundle runs
reported in WU-LPR-076 and WU-LPR-077.

The wider `./gradlew -p v2 check` L5 round gate remains red because of
these three failures. They will be closed when:

- `cycle/lfc2-e1-archive-artifacts-g8` lands in `main` (closes
  `FArchL7JenkinsVerbatimStepTest`).
- A future WU migrates the two Step plugins to `WorkspaceResolver`
  (closes `Lfc0GlobalStateFitnessTest`).
- A future doc-only WU updates the root README with the LFC link
  (closes `Lfc0V1QuarantineFitnessTest`).

## Verification

```text
git worktree add /tmp/lpr-base-arch adab94d1   # detached
./gradlew -p /tmp/lpr-base-arch/v2 :pipeline-architecture-tests:test \
  --tests 'dev.rubentxu.pipeline.v2.architecture.Lfc0GlobalStateFitnessTest' \
  --tests 'dev.rubentxu.pipeline.v2.architecture.Lfc0V1QuarantineFitnessTest' \
  --tests 'dev.rubentxu.pipeline.v2.architecture.FArchL7JenkinsVerbatimStepTest' \
  --rerun-tasks
# Result: BUILD FAILED, 3 tests FAILED, identical to HEAD
# SHA-256 of evidence log: a1edb0086521763528369b773c6780668073f1eded795f918536792a988673d7
git worktree remove --force /tmp/lpr-base-arch
```

## End-of-work-unit closure

```text
Reference implementation consulted: n/a — diagnostic receipt.
Behaviour adopted: cycle base worktree comparison (AGENTS.md rule 16).
Intentional deviations: none — we record the verdict and let the
  in-flight cycles close the architecture gaps.
Security implications reviewed: n/a.
Tests demonstrating the verdict:
  - Lfc0GlobalStateFitnessTest (same failure before/after LPR cycle)
  - Lfc0V1QuarantineFitnessTest (same failure before/after LPR cycle)
  - FArchL7JenkinsVerbatimStepTest (same failure before/after LPR cycle)
```

— Receipt authored by SDDK orchestrator session `session_hare_*`.
