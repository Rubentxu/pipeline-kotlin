# WU-RP-040 R5.1 — Closure Receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Date:** 2026-09-27
**Base SHA (cycle open):** `ab5bec80`
**Implementation SHA (cycle close):** `4207748e`
**Branch:** `wu/rp-053r-red-fixtures`
**Status:** **CLOSED — three detekt smells closed; pre-existing flake D-002 reproduced and classified NOT_REGRESSION**

## Three smells closed

| ID | Module | File:line | Rule | Status before | Status after |
|---|---|---|---|---|---|
| C-1 | `:pipeline-application:detekt` | `CliParser.kt:64` | `CyclomaticComplexMethod` (27 > 25) | NEW | CLOSED |
| C-2 | `:pipeline-scripting-api:detekt` | `PipelineDslSteps.kt:15` | `MatchingDeclarationName` | NEW | CLOSED |
| C-3 | `:pipeline-step-sdk:api:detekt` | `PipelineJson.kt:100` | `TooManyFunctions` (12 > 11) | NEW | CLOSED |

## Commits

| SHA | Subject |
|---|---|
| `5c262bf9` | `docs(uat,adr-rp040-r51): exploration report — detekt burn-down of 3 regressed smells` |
| `6f04cf87` | `docs(uat,adr-rp040-r51): specification — contracts for the 3 detekt fixes` |
| `5905e6f6` | `docs(uat,adr-rp040-r51): design — D-1 CliParser refactor, D-2 rename, D-3 suppress` |
| `ae622059` | `docs(uat,adr-rp040-r51): implementation plan — 4 tasks with verification` |
| `1c436312` | `chore(pipeline-step-sdk:api): suppress TooManyFunctions on JsonAccessors with documented rationale` (Task 1 / D-3) |
| `b8f9ec01` | `refactor(pipeline-scripting-api): rename PipelineDslSteps.kt to StepSpec.kt; update fitness path` (Task 2 / D-2) |
| `4207748e` | `refactor(application): reduce CliParser.parse complexity below 25 via per-option helper` (Task 3 / D-1) |

## Verification

### Per-task

- **Task 1 (D-3)** — `cd v2 && timeout 600 ./gradlew :pipeline-step-sdk:api:detekt --no-daemon --rerun-tasks --console=plain`
  Exit 0. detekt checkstyle XML at `v2/pipeline-step-sdk/api/build/reports/detekt/detekt.xml` SHA-256
  `f491e274f0d208f81c3edbc7c1097c30db590a3822fcf579468ac814d9ccc62b` (empty `<checkstyle/>` element).
  Tests at `:pipeline-step-sdk:api:test` exit 0; two XML canaries
  `c11e877113950b0e5ce76c555891410d5292cd1e8956ce93f083518f9074ab08` and
  `2f373c5e54be31642625ab472aa559c45e9aaa37c6142cdb9cf08d5fc09d2fa8`.

- **Task 2 (D-2)** — `cd v2 && timeout 600 ./gradlew :pipeline-scripting-api:detekt :pipeline-architecture-tests:test --no-daemon --rerun-tasks --console=plain`
  Exit 0, BUILD SUCCESSFUL in 3m 22s. `pipeline-scripting-api` detekt XML SHA-256
  `f491e274f0d208f81c3edbc7c1097c30db590a3822fcf579468ac814d9ccc62b` (empty).
  `FArchLfc1LegacyDslRemovedTest.xml` SHA-256
  `bc72227fe7a5f358b73ded7968bd338b5169359fbb489bc268c92bb2c880f015` (test green).
  No remaining references to `PipelineDslSteps` after `git mv`.

- **Task 3 (D-1)** — `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.MainCliParsingTest' :pipeline-application:detekt --no-daemon --rerun-tasks --console=plain`
  Exit 0, BUILD SUCCESSFUL in 1m 31s. `MainCliParsingTest.xml` SHA-256
  `fc0cf3ff8a9b4d81653dd34a008de363d443ad3ba90ba256a21d235b27d64a2f` with
  `tests="10" skipped="0" failures="0" errors="0"`. `pipeline-application` detekt XML SHA-256
  `f491e274f0d208f81c3edbc7c1097c30db590a3822fcf579468ac814d9ccc62b` (empty;
  CliParser parse no longer reported as CyclomaticComplexMethod).

### Aggregate (Task 4)

- **L4 round gate** — `cd v2 && timeout 1200 ./gradlew check --no-daemon --rerun-tasks --console=plain`
  Run finished in 1m 38s; **FAILED** at `:pipeline-credentials-api:test` with
  `Rp022ThroughputProbe.redactor throughput floor()` reporting `13,7 MB/s`
  against a 20 MB/s floor (`throughput below floor: 3659 ms for 50MiB`).
- **Failing XML canary** —
  `v2/pipeline-credentials-api/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.credentials.api.Rp022ThroughputProbe.xml`
  with `tests="1" skipped="0" failures="1" errors="0"` timestamped
  `2026-09-27T07:23:44.315Z`, wall time 13.304 s.

## Rp022ThroughputProbe — NOT_REGRESSION (pre-existing flake D-002)

The single round-gate failure is unrelated to this slice. Evidence:

1. `git log --oneline ab5bec80..HEAD -- v2/pipeline-credentials-api/src/test`
   returns no commits — my slice did not touch `Rp022ThroughputProbe.kt`,
   the throughput floor, or the redactor source path.
2. Running the same probe in isolation:
   `cd v2 && timeout 120 ./gradlew :pipeline-credentials-api:test --tests 'dev.rubentxu.pipeline.v2.credentials.api.Rp022ThroughputProbe' --no-daemon --rerun-tasks --console=plain`
   produced `tests="1" failures="0" errors="0"`, wall time 9.543 s
   (`v2/pipeline-credentials-api/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.credentials.api.Rp022ThroughputProbe.xml`
   timestamp `2026-09-27T07:25:17.551Z`).
3. The failure matches the operator's prior D-002 diagnosis (see
   `.agent/SESSION_POINTER.md` reconciliation 2026-09-25T16:06Z and the
   pre-existing 2026-09-24T08:43Z round-gate partial): the probe passes in
   isolation at ~22 MB/s and fails under concurrent test JVM contention at
   ~13-17 MB/s. Root cause is CPU contention between the parallel test
   JVMs, not a regression in the redactor or the throughput floor.
4. The flake is classified `KNOWN_FLAKE pre-existing D-002` in the
   `.agent/TECH_DEBT_BACKLOG.md` and the operator already weighed the four
   options (warmup 1→3 / threshold 20→15 / retry / accept+disclose). The
   slice does not re-open that decision.

Decision: this slice is **CLOSED** on its three contracts. The aggregate
round gate remains amber (1 pre-existing flake). D-002 follow-up is
eligible for a future WU once the operator's preference is restated.

## Out-of-scope items reaffirmed

- A/B/D mutation categories from `RP040_R8_MUTATION_SURVIVOR_TRIAGE.md` remain
  classified as accepted / UAT-covered / low-value. Not touched.
- R3.4 Dependabot remains `BLOQUEADO_EXTERNO`. Not touched.
- R5 `coverage-all` CI job was wired in commit `21b89514`. Local
  `koverXmlReport` runs were not re-executed in this slice because the
  three contracts above are detekt-gated, not kover-gated. A separate WU
  to capture fresh `koverXmlReport` evidence against the new HEAD remains
  open (and the operator archived that as `coverage-all` is
  `NOT_RUN_in_CI` since R5).
- No public API change to `CliParser`, `PipelineJson`, `JsonAccessors`, or
  the `StepSpec` hierarchy. `git diff ab5bec80..HEAD --stat` over the
  touched modules shows the changes are local: `CliParser.kt`
  +84/-56 (refactor), `PipelineDslSteps.kt` → `StepSpec.kt` rename,
  `FArchLfc1LegacyDslRemovedTest.kt` -1/+1 (path), `PipelineJson.kt`
  +12/-0 (annotation + comment).

## Reference implementation consulted

None applicable. The three detekt findings are static-analysis thresholds
defined in `v2/config/detekt/detekt.yml`; their closures follow in-repo
precedent (`DslCompiledPipelineCompiler.encodePayload` already carries
`@Suppress("CyclomaticComplexMethod")` for closed IR dispatch).

## Lessons captured

1. **`@Suppress` is a first-class debt primitive when scoped to a single
   declaration with a justifying comment.** Splitting `JsonAccessors` would
   have been more invasive without reducing the surface detekt measures.
   The pattern is already established by `encodePayload`; reusing it keeps
   the gate from weakening globally.
2. **Mechanical refactors preserve historical coupling.** Renaming
   `PipelineDslSteps.kt` → `StepSpec.kt` was blocked by a single hardcoded
   path in `FArchLfc1LegacyDslRemovedTest.kt`. A grep for the old name
   across `v2/` before the `git mv` caught the only consumer; this is the
   pattern to follow for future renames.
3. **Detekt gating must run in the round gate every time, even on
   "documentation-only" commits.** R5 was archived as closed without
   `cd v2 && ./gradlew check` strict-mode evidence on the rc6/rc7 chain
   that introduced/moved the affected files. The gap here was the
   inability to call R5 "verified green" until a fresh run surfaced the
   three smells. Future rounds should run the L4 gate once before
   archiving close, not just on the original receipt's SHA.

## Next step

If the operator wants D-002 closed alongside the cycle (the warmup 1→3 fix
applied at `Rp022ThroughputProbe.kt:41`), a one-line patch + a fresh
isolated green run is sufficient. Otherwise R5.1 closes here and the
operator's original D-002 evaluation (option A: warmup 1→3 / option B:
threshold 20→15 / option C: retry / option D: accept+disclose) stands.
