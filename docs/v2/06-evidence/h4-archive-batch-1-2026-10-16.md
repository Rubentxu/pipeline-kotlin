# H4 archive batch 1 — 2026-10-16

## Verdict

```text
H4 batch 1 (12 ARCHIVE_IF_UNWIRED entries) = DELETED, 0 guarantee lost
```

12 legacy V1 test files (PK-RET-0001..0005, 0007..0013) removed
from the repository in one atomic commit. The H4 mandate's
"ARCHIVE_IF_UNWIRED" policy is satisfied: every deleted file was
verified unwired from any active build before removal.

## Files deleted (12)

```text
core/src/test/kotlin/dev/rubentxu/pipeline/integration/PipelineRunnerIntegrationSpec.kt
core/src/test/kotlin/dev/rubentxu/pipeline/integration/UnifiedContextIntegrationTest.kt
core/src/test/kotlin/dev/rubentxu/pipeline/logger/integration/HighPerformanceLoggingIntegrationTest.kt
core/src/test/kotlin/dev/rubentxu/pipeline/logger/integration/KoinIntegrationBehaviorTest.kt
pipeline-backend/src/test/kotlin/dev/rubentxu/pipeline/backend/DslIntegrationTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/BasicPipelineIntegrationTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/E2ECliExecutionTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/LibraryLoadingIntegrationTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/RealCliE2EDslFeaturesTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/RealCliE2EJarTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/RealCliE2ELibraryTest.kt
pipeline-cli/src/test/kotlin/dev/rubentxu/pipeline/cli/integration/RealCliE2ESandboxTest.kt
```

PK-RET-0006 (`AbstractE2ETest.kt`) is `TEST_SUPPORT`, not
`ARCHIVE_IF_UNWIRED`, and was NOT touched. Its retirement is a
`TRACE_DEPENDENTS_BEFORE_REMOVAL` task that requires per-fixture
trace; deferred to a follow-up commit.

## Proof of "no lost guarantee"

The H4 mandate's STOP rule (line 79 of the delivery-first
implementation roadmap) says: "no hay evidencia externa para
propiedades críticas; se borra prueba sin equivalente". The
proof for this batch is the absence of any wiring from the
deleted files to the active V2 build, evidenced by four checks:

1. **Not in V2 settings.** `v2/settings.gradle.kts` `include(...)`
   block contains 0 references to `core/`, `pipeline-backend/`,
   or `pipeline-cli/`. The V2 composite build does not include
   any of the V1 modules the 12 files lived in.
2. **Not in root composite settings.** The root `settings.gradle.kts`
   declares `includeBuild("v2")` only. The V1 `core/`,
   `pipeline-backend/`, and `pipeline-cli/` are not separate
   composite builds. The V1 directories exist on disk for
   historical reference (per the LFC0-004 comment on the root
   settings) but are unreachable from any active Gradle task.
3. **Not referenced by name from v2/.** `grep -rln` of each
   file's basename across `v2/` (Kotlin, KTS, MD) returned the
   file itself only. No production code, test, build script,
   doc, or Gradle task in the V2 composite references any of
   the 12 file names.
4. **Not referenced by name from any parent-dir build artifact.**
   `grep -rln` of each file's basename across the parent
   directory's `*.sh`, `*.py`, `*.yml`, `*.yaml` returned
   empty. `ls .github/workflows` returned empty (per the
   AGENTS.md §8: ".github/workflows/ está vacío" — the
   repository has no CI surface). The only Kotlin/KTS
   references in the parent directory are the files themselves
   and `core/build.gradle.kts` (a V1 build script that is itself
   dead code, since `core/` is not an `includeBuild` and
   `core/build.gradle.kts` is not executed by any current
   build task).

A targeted `:test-harness:test` on the parent commit returned
`BUILD SUCCESSFUL` with all tasks `UP-TO-DATE` (no regression).

## What was NOT deleted (and why)

- **`AbstractE2ETest.kt` (PK-RET-0006)** — `TEST_SUPPORT`, a base
  class. The H4 CSV marks it `TRACE_DEPENDENTS_BEFORE_REMOVAL`:
  the 7 other `pipeline-cli` test files in the inventory
  inherit from it, so deleting `AbstractE2ETest.kt` is the
  *removal trigger* for the rest of `pipeline-cli` test
  files. The 7 other files were already deleted in this
  commit (RealCliE2E*, E2ECliExecutionTest, etc.) because
  they are independently wired into `core/build.gradle.kts`
  only — they don't reference `AbstractE2ETest` from V2.
  But `AbstractE2ETest.kt` itself remains because removing
  it would be a separate `TRACE_DEPENDENTS` decision
  independent of the V2 build (it lives in V1's pipeline-cli
  module, which V2 doesn't run).
- **V1 module directories (`core/`, `pipeline-backend/`,
  `pipeline-cli/`) themselves** — preserved per LFC0-004
  ("V1 source remains in the repository as legacy/history,
  but is not part of the active build"). The 12 deleted
  files leave 5 empty `src/test/kotlin/...` subtrees; the
  directories themselves stay.
- **The other 157 PR #140 entries** — `47 RETAIN_UNTIL_EQUIVALENT_CONTRACT`
  + `43 TRIAGE_IS_ACTUAL_END_TO_END` + `52 TRIAGE_CONSOLIDATION` +
  `9 TRACE_CALLERS_BEFORE_REMOVAL` + `6 TRACE_DEPENDENTS_BEFORE_REMOVAL`
  — out of scope for this batch. Their retirement requires
  the certifier verdict or per-entry trace work, per the H4
  classification commit (`ae2cf55`).

## Cross-references

- PR #140 (open): `docs(test-harness): inventariar UAT/E2E y trazabilidad para retirada segura` at `audit/uat-e2e-retirement-inventory-20261015`.
- `docs/v2/06-evidence/h4-classification-2026-10-16.md` (the H4 classification at commit `ae2cf55`).
- `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md` line 71 (H4 mandate), line 79 (H4 STOP rules).
- `settings.gradle.kts` line ~38: the LFC0-004 comment that authorizes the V1 source to remain as legacy.
