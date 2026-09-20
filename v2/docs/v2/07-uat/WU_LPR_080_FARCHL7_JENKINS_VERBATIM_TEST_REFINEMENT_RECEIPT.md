# WU-LPR-080 — refine FArchL7JenkinsVerbatimStepTest to match Jenkins catalog + E1.1 extension

**Date**: 2026-09-20
**Status**: CLOSED
**Cycle base**: `26b425fc` (WU-LPR-079)
**Branch**: `main`
**Module**: `pipeline-architecture-tests` (`:pipeline-architecture-tests:test`)
**Authority for refinement**: AGENTS.md — *"Specs and harness expectations may be refined, but only when a real blocker is reproduced; record the refinement in the receipt."*

---

## Trigger

After WU-LPR-078 quarantined 3 pre-existing architecture fitness
failures, the L5 round gate (`./gradlew -p v2 check`) remained red on
two of them. `Lfc0V1QuarantineFitnessTest` closed in WU-LPR-079 with a
one-line README link. `FArchL7JenkinsVerbatimStepTest` was the second
pre-existing failure and the directive ("resolviendo cualquier bloqueo
con investigación profunda del problema") required me to investigate
before quarantining.

## Diagnosis

`FArchL7JenkinsVerbatimStepTest > all_step_shapes_match_jenkins_catalog`
asserted a constructor-parameter shape for 5 `StepSpec` data classes.
Reading the test alongside the Jenkins familiarity catalog and the
production data classes revealed a **stale expectations contract** —
not a regression in the production code:

| Step | Jenkins verbatim (catalog §1.1) | Production `StepSpec.*` | Old test expectation | Verdict |
|------|----------------------------------|--------------------------|------------------------|---------|
| `WriteFile` | `(file, text, encoding)` | `(file, text, encoding)` (PipelineDsl.kt:365) | `(name, file, text, encoding)` | **test stale** |
| `ReadFile` | `(file, encoding)` | `(file, encoding)` (PipelineDsl.kt:386) | `(file, encoding)` | ok |
| `FileExists` | `(file)` | `(file)` (PipelineDsl.kt:405) | `(file)` | ok |
| `WithEnv` | `(overrides: List<String>)` | `(overrides, steps)` (PipelineDsl.kt:425) | `(overrides, steps)` | ok |
| `ArchiveArtifacts` | `(artifacts, allowEmptyArchive, caseSensitive, defaultExcludes, excludes, fingerprint, followSymlinks, onlyIfSuccessful)` | `(artifacts, allowEmptyArchive, excludes, fingerprint, artifactName)` (PipelineDsl.kt:472) | `(artifacts, allowEmptyArchive, excludes, fingerprint)` | **test stale** (E1.1 added `artifactName`) |

The original test KDoc (lines 12-17) described the *correct* Jenkins
verbatim shape, but the data table and the in-test
`writeFile_shape_matches_jenkins_catalog` contradicted it. Two
inconsistencies inside the same file:

1. **`WriteFile`**: the test required a `name: String` constructor
   parameter that does not exist in Jenkins (`writeFile(file, text,
   encoding)` per `JENKINS_FAMILIARITY_CATALOG.md §1.1` line 35) and
   does not exist in the production `StepSpec.WriteFile` data class.
   The Jenkins step carries an implicit `name` (the call site), not a
   constructor parameter.

2. **`ArchiveArtifacts`**: the test was a snapshot taken before E1.1
   / T7 added `artifactName: String? = null` to the data class to
   carry the per-run artifact identifier for the
   `core.archiveArtifacts` → `core.artifact.query` bridge. The
   addition is strictly typed, optional, additive, and does not
   change the Jenkins verbatim semantics of the first 4 parameters.

Production code is correct against the Jenkins verbatim contract
(ADR-0023 / ADR-0052 / JENKINS_FAMILIARITY_CATALOG.md §1.1). The fitness
test must be refined to match.

## Change

Single file: `FArchL7JenkinsVerbatimStepTest.kt`.

- Expanded the KDoc to quote the Jenkins verbatim signatures and to
  document the E1.1 extension for `ArchiveArtifacts` (`artifactName`
  added by LFC-2 E1 / T7 / core.artifact.query). WU-LPR-080 is named
  in the refinement trail.
- Updated the `WriteFile` `StepShape` so its parameter-count is 3
  (`file, text, encoding`) and its field set is `{file, text, encoding}`,
  matching both Jenkins and `StepSpec.WriteFile`.
- Updated the `ArchiveArtifacts` `StepShape` so its parameter list
  includes the E1.1 `artifactName: String?` and its field set is
  `{artifacts, allowEmptyArchive, excludes, fingerprint, artifactName}`.
  The JVM type descriptor for `String?` is `Ljava/lang/String;` (boxed
  reference type).
- Updated the in-test KDoc of `writeFile_shape_matches_jenkins_catalog`
  to document the previous-shape mismatch and link to the Jenkins
  catalog + production code references.

The test logic itself (constructor-count filter, JVM descriptor
comparison, declared-fields comparison) is unchanged. Only the
**expected shape** changed, to align with the real contract.

## Verification

| Command | Outcome | SHA-256 |
|---------|---------|---------|
| `:pipeline-architecture-tests:test --tests 'FArchL7JenkinsVerbatimStepTest' --rerun-tasks` | BUILD SUCCESSFUL · XML `tests=2 failures=0 errors=0` | `lpr080-jenkins-verbatim.log` (2db43f2206aa259f10be86ff3e2da6dd0cb6d92a51ed1ea0db743de75167cbf0) |
| `:pipeline-architecture-tests:test --tests 'Lfc0GlobalStateFitnessTest' --rerun-tasks` | BUILD FAILED · XML `tests=2 failures=1 errors=0` (unchanged) | `lpr080-lfc0global.log` |

The other pre-existing failure (`Lfc0GlobalStateFitnessTest`)
reproduces identically and remains quarantined to the CTX-P /
hexagonal `WorkspaceRootProvider` port follow-up.

## End-of-work-unit closure

```text
Reference implementation consulted: JENKINS_FAMILIARITY_CATALOG.md §1.1
  (ADR-0023 / ADR-0052) and the production StepSpec data classes in
  PipelineDsl.kt (lines 365, 386, 405, 425, 472).
Behaviour adopted: refined the fitness test's expected shape to match
  the Jenkins verbatim contract and the E1.1 extension.
Intentional deviations: none — the production code was already correct;
  the test was the artifact that had drifted from the contract.
Security implications reviewed: n/a — architecture fitness only.
Tests demonstrating the contract:
  - FArchL7JenkinsVerbatimStepTest (2/0/0 after WU-LPR-080)
  - Lfc0GlobalStateFitnessTest (still pre-existing, unchanged)
```

— Receipt authored by SDDK orchestrator session `session_hare_*`.
