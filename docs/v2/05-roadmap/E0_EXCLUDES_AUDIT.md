# E0-01 Excludes Audit — baseline analysis (2026-08-22)

## Status

M0 exit criterion is **NOT met**:

- `./gradlew help` ✅ passes (module graph + build script evaluation)
- `./gradlew check` ❌ **fails** (54 source excludes in `core/build.gradle.kts`)
- `KSP` ✅ enabled (KSP 2.3.11 + Kotlin 2.4.10 — official recipe)
- `Shadow fat-jar` ❌ disabled (incompatible with Gradle 8.14.5)

## Audit of `core/build.gradle.kts` excludes

The excludes were added in a "TDD Phase 1" to suppress non-compiling files
while keeping `DslManager.kt` as the only minimal implementation. After the
catalogue refactor (commit `7d0b2a6`) the source tree was rebuilt — most of
the excluded files **no longer exist on disk**.

### Breakdown of 51 excludes (compileKotlin + compileTestKotlin)

| Pattern                                       | Files on disk | Status |
|-----------------------------------------------|--------------:|--------|
| `**/dsl/*.kt` (16 specific files)             |             0 | All MISSING |
| `**/dsl/{engines,examples,interfaces,validation}/**` (4 dirs) | 0 | EMPTY |
| `**/{execution,compilation,compiler}/**` (3 dirs) | 0 | EMPTY |
| `**/{security,plugins,library,events,jenkins}/**` (5 dirs) | 0 | EMPTY |
| `**/steps/**`                                 |             0 | EMPTY |
| `**/steps/{builtin/BuiltInSteps.kt,security/StepSecurityManager.kt}` | 0 | MISSING |
| `**/model/{pipeline,job,scm}/**` (3 dirs)     |             0 | EMPTY |
| `**/model/{GitTool,Workspace}.kt`             |             0 | MISSING |
| `**/{Default,Minimal}PipelineContext.kt`, `IPipelineContext.kt`, `PipelineContextFactory.kt` | 0 | MISSING |
| `**/context/{Pipeline,StepExecution}Context.kt` | 0 | MISSING |
| `**/context/unified/**`                       |             0 | EMPTY |
| `**/context/managers/WorkingDirectoryManager.kt` | 0 | MISSING |
| `**/context/modules/{ManagersModule,PipelineCoreModules,UnifiedCoreModules}.kt` | 0 | MISSING |
| `**/dsl/MinimalDslManager.kt`                 |             0 | MISSING — comment notes "Remove this since it's copied to DslManager.kt" |
| `**/integration/UnifiedContextIntegrationTest.kt` | 0 | MISSING |
| `**/RealManagersTest.kt`                      |             0 | MISSING — comment notes "References deleted KoinParameterManager" |

**Net result**: 49 of 51 excludes target paths that no longer exist. They are
**stale references from the pre-refactor V1 codebase** and have no effect on
the current build.

### What does compile today

By exclusion, only the following `core/src/main/kotlin/**/*.kt` files compile:

- `dev/rubentxu/pipeline/dsl/DslManager.kt` (the comment says "minimal implementation")
- `dev/rubentxu/pipeline/error/**/*.kt` (not excluded; verify on next step)
- `dev/rubentxu/pipeline/logger/**/*.kt` (not excluded; verify on next step)
- `dev/rubentxu/pipeline/validation/**/*.kt` (not excluded; verify on next step)
- `dev/rubentxu/pipeline/runner/**/*.kt` (not excluded; verify on next step)
- `pipeline/kotlin/**/*.kt` (not excluded; verify on next step)

115 `.kt` files exist in `core/src/main/kotlin`; only a small subset compiles
in the current `compileKotlin` task.

## Recommended classification (E0-05 mapping)

| Decision | What it means | Targets |
|----------|---------------|---------|
| **RETIRE** | Delete the exclude entirely (file no longer exists) | All 49 stale excludes |
| **KEEP** | Leave as-is — paths still exist or are intentional | `**/disabled/**` (V2 convention) |
| **ADAPT** | Re-enable with code fixes required for Kotlin 2.4.10 | None identified yet |
| **REWRITE** | Replace before re-enabling | None identified yet |

## Re-enabling pass — required to satisfy M0 exit

Steps:

1. **RETIRE all 49 stale excludes** in a single commit
2. **Validate**: `./gradlew :core:compileKotlin` must compile every existing
   file in `core/src/main/kotlin`. This will surface API drift between
   Kotlin 2.2 (when V1 was last compiled) and Kotlin 2.4.10.
3. **Fix surfaced errors** — likely candidates:
   - JVM target mismatch (some subprojects default to JDK 24 bytecode while
     others require JDK 21; see E0-04 below)
   - Type inference changes between Kotlin versions
   - Removed/deprecated stdlib APIs
4. **KEEP `**/disabled/**`** — this is a project convention for files that
   are intentionally left out of compilation; documented in V2 design.

### Sub-blocker surfaced during this audit

Removing the excludes exposes a JVM target inconsistency:

```
> Could not resolve project :pipeline-steps-system:plugin-annotations.
  - Variant 'apiElements' declares ... compatible with Java 24
  - and the consumer needed a component, compatible with Java 21
```

This is independent of E0-01. Tracked under **E0-04** (dependency fitness).

## References

- `docs/v2/05-roadmap/ROADMAP.md` — M0 baseline definition
- `docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md` — E0-01 through E0-07
- `core/build.gradle.kts` lines 64–156 (excludes currently in place)
- Commit `7d0b2a6` — initial catalog baseline that enables this audit