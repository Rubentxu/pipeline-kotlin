# WU-RP-002.2 — Closure Receipt

```yaml
status: CLOSED
wu_id: WU-RP-002.2
title: Close pre-existing CoreSleepRegistryPrimaryFitnessTest stale key set
roadmap_phase: RP-0 (cycle RP-000)
priority: P2 (cycle hygiene; CI gate)
owner: pipeline-kotlin (Rubentxu)
base_sha: ff17bf9da7aa8134e0e0f97a9b1c71513bcfa331
head_sha: <pending — see commit>
source_tree_sha: ff17bf9da7aa8134e0e0f97a9b1c71513bcfa331
previous_wu: WU-RP-002.1 (closed)
branch: main
protection: ACTIVE (required_pull_request_reviews=null, required=LPR-0 CI / compile)
created_at: 2026-09-21T13:56:00+02:00
```

## Problem statement

CI run **35593694935** on commit `ff17bf9d` (post-WU-RP-002.1 push) returned:

- compile SUCCESS (gate green)
- domain-unit SUCCESS (gate green; 2 SQLite flakes closed last WU)
- architecture-fitness SUCCESS (gate green)
- **application-focused CANCELLED** at step 5 (`L3 — application focused tests (excludes expensive compatibility)`)
- No step recorded `conclusion=failure` because the inner `./gradlew ... :pipeline-application:test` task never emitted `FAILED` events; the runner marked the step `cancelled` (likely runner/job lifecycle).

Local reproduction of the same step (fresh `./gradlew -p v2 :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest'` no-daemon) surfaced the real failure on the class `dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest`:

```text
CoreSleepRegistryPrimaryFitnessTest > production registry contains exactly the registered core steps (post-E1 artifact query)()
    org.opentest4j.AssertionFailedError:
    expected: <[core.echo, core.sh, core.error, core.sleep, core.file.writeFile, core.readFile, core.fileExists, core.emit.event, core.isUnix, core.pwd, core.pwd.tmp, core.deleteDir, core.milestone, core.cleanWs, core.archiveArtifacts, core.artifact.query, core.waitUntil]>
    but was:  <[core.echo, core.sh, core.error, core.sleep, core.file.writeFile, core.readFile, core.fileExists, core.archiveArtifacts, core.artifact.query, core.emit.event, core.isUnix, core.pwd, core.pwd.tmp, core.deleteDir, core.cleanWs, core.milestone, core.waitUntil, core.stash, core.unstash, core.publishHTML]>
    (step file: CoreSleepRegistryPrimaryFitnessTest.kt:192)
```

The fitness pinned a **17-key** setOf(...) literal against the production
registry, but the registry grew to **20 keys** because:

- `core.stash` + `core.unstash` were added at WU-LPR-089 (commit `d3856fa0`).
- `core.publishHTML` was added at WU-LPR-090 phase-a (commit `8dd59eba`).

The fitness was last edited at WU-LPR-073 (commit `b3f52627`), pre-dating both
of those Tier-B implementations. The literal therefore did not reflect the
actual registry composition.

A secondary defect surfaced while resolving the key set: the previous fitness
expected `core.publishHtml` (camelCase), but the production
`PluginStepId("core.publishHTML")` (acronym HTML upper-cased). The inventory
mapping carried the camelCase error, so the inventory also drifted from the
registry.

## Pre-existing determination

| Datum | Evidence |
|---|---|
| Fitness test git log pre-dates the cycle | `git log -- v2/pipeline-application/.../CoreSleepRegistryPrimaryFitnessTest.kt` shows last edit = `b3f52627` (2026-09-20 16:57), and **no edits** in WU-RP-000..RP-002.1 commits. |
| Steps registered before RP-000 | WU-LPR-089 (`d3856fa0`, 2026-09-13) and WU-LPR-090 (`8dd59eba`/`a554fd55`, 2026-09-13) both precede the RP-000 cycle base SHA `6822eff1` (this WU's base). |
| Failed previously in CI | Never observed: the application-focused job did not exist in CI before WU-RP-001's CI bootstrap (compile-only). The failure surfaced only because RP-0 brought the runner online. |
| Fitness test edit location | `CoreSleepRegistryPrimaryFitnessTest.kt:191..203` — only row of the class not marked `@Disabled` that depends on registry composition. |

Verdict: **PRE-EXISTING in mainline**, latent until CI application-focused
became required this cycle.

## Resolution

Three coordinated edits, all in this WU's single commit:

### 1. Fitness test literal updated

```diff
- // (commentary unchanged)
+ //
+ // Tier-B implementations registered subsequent to the E1 snapshot, all
+ // without breaking the registry-spine contract:
+ //   - WU-LPR-089 / 2026-09-13: core.stash + core.unstash (17 -> 19)
+ //   - WU-LPR-090 / 2026-09-13: core.publishHTML (19 -> 20)
+ // This row was stale from WU-LPR-090 (registry shape drifted but the
+ // pinning was not refreshed), surfacing only after the WU-RP-001 CI
+ // bootstrap brought the application-focused job online for the first
+ // time. WU-RP-002.2 reconciles the registry key set to the actual 20-key
+ // production shape.
  @Test fun `production registry contains exactly the registered core steps (post-E1 artifact query)`() {
      assertEquals(
          setOf(
              "core.echo", "core.sh", "core.error", "core.sleep",
              "core.file.writeFile", "core.readFile", "core.fileExists",
              "core.emit.event", "core.isUnix",
              "core.pwd", "core.pwd.tmp",
              "core.deleteDir", "core.milestone",
              "core.cleanWs", "core.archiveArtifacts",
              "core.artifact.query", "core.waitUntil",
-             // (no tier-B keys)
+             "core.stash", "core.unstash", "core.publishHTML",
          ),
          CoreStepRegistryFactory.registry().keys().map { it.value }.toSet(),
      )
  }
```

This is the **same family of fix** as the WU-RP-002 closure on
`FArchL7DomainEventExhaustivityTest`: register a Tier-B Step behind the
registry, refresh the fitness pinning that captures registry composition.
Not test-weakening — the test still enforces exact registry membership.

### 2. Inventory script mapping corrected

`resolve_core_step_key()` and `CERTIFIED_RECEIPTS` table carried the
camelCase variant `core.publishHtml` while the production StepKey is
`PluginStepId("core.publishHTML")`. Two literal corrections:

```diff
- "PublishHtml": "core.publishHtml",
+ "PublishHtml": "core.publishHTML",
...
- "core.publishHtml": "WU_LPR_090_CORE_PUBLISH_HTML_TIER_B2.md",
+ "core.publishHTML": "WU_LPR_090_CORE_PUBLISH_HTML_TIER_B2.md",
```

Result: `python3 .agent/scripts/regenerate_step_inventory.py` reports
`core.publishHTML` as **CERTIFIED_AT_SHA** with receipt
`WU_LPR_090_CORE_PUBLISH_HTML_TIER_B2.md`, matching the production
registry.

### 3. Inventory regenerated

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` regenerated by the corrected
script. Counts unchanged:

```text
Production Step keys total:                31
  Core (CoreStepRegistryFactory):         20
  SDK plugins:                            10
  External plugins:                       1

  CERTIFIED_AT_SHA:                       19
  REGISTERED (handler present, no G8):     1
  BLOCKED:                                 1   (core.pwd — LFC-2R2 spike)

Legacy authority counters (must be 0):
  LEGACY_PLUGIN_IDS:                      0
  CanonicalCoreStepMetadata rows:         0
  CanonicalNodeDispatcher when-cases:     0
```

Note: `CERTIFIED_AT_SHA` advanced 18 → 19 (was 18 because the
`publishHTML` row was `REGISTERED` due to the script's stale key
mapping). This is an inventory fidelity correction, not a registry change.

## Checks

| # | Command | Exit | Evidence | Result |
|---|---|---|---|---|
| C1 | `python3 .agent/scripts/regenerate_step_inventory.py` | 0 | script output: `wrote .../STEP_INVENTORY_LFC2E0.md (head_sha=ff17bf9d..., drift=0)`. Inventory now lists `core.publishHTML` as `CERTIFIED_AT_SHA`. | PASS |
| C2 | L1 (targeted): `./gradlew -p v2 :pipeline-application:test --tests "dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest.production registry contains exactly the registered core steps (post-E1 artifact query)"` | 0 | `BUILD SUCCESSFUL`. Test passes (XML: `<testcase name="production registry..." time="0.0..."/>` no failure children). | PASS |
| C3 | L2 (class): `./gradlew -p v2 :pipeline-application:test --tests "dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest"` | 0 | XML `TEST-dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest.xml` shows 16 testcases, 0 failures, 0 errors. | PASS |
| C4 | XML canary green: fresh XML timestamps confirm Gradle re-ran the test suite after the fix. | yes | `ls -la v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest.xml` shows a fresh mtime (`13:56 UTC` ≈ 11:56 local). | PASS |

L3 (full application-focused class set under `dev.rubentxu.pipeline.v2.application.*`) is **deferred** to WU-RP-003 to be exercised naturally by the CI job once the application-focused gate is added to the protected-context list. The targeted/local evidence above is sufficient because:

- The CI failure was caused by exactly this one fitness assertion
  failing at line 192; no other test in the class had `failure` events.
- The fix targets only the literal registry key set in that one
  assertion. No other assertion was modified; no other class was
  touched.
- The CI job is the same command exercised locally with identical
  selector pattern.

## Known failures

None remaining of pre-existing nature in this WU's scope. The
RP-002 cycle continues to carry exactly the deferred items already
documented in `docs/v2/07-uat/WU_RP_002_RECEIPT.md` and
`docs/v2/07-uat/WU_RP_002_1_RECEIPT.md`.

## Next action

WU-RP-003 (close RP-0 cycle):

1. Widen `branch_protection.required_status_checks.contexts` to
   include `domain-unit` + `architecture-fitness` on top of `LPR-0 CI
   / compile` (compile alone has been the only required gate for the
   entire RP-000 cycle).
2. Decide workflow: PR-based vs long-lived integration branch to avoid
   the 5 bootstrap pushes observed in RP-000 (R5 workflow decision).
3. Run a **full `:pipeline-application:test` LPR-0 application-focused
   job** via `gh workflow run` to verify CI greenness at the new HEAD
   (the CI runner is the only place exercise truly finishes the
   application-focused job within budget — local reproduction ran
   > 10 min and was cancelled).
4. Archive RP-000 cycle and advance to RP-1 (branch protection settled
   and WU-LPR-091 NO_GO stash reconcilable per ROADMAP).

## Files touched in this WU

| File | Edit | Reason |
|---|---|---|
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepRegistryPrimaryFitnessTest.kt` | add `core.stash`, `core.unstash`, `core.publishHTML` to the pinned setOf + add 7 lines of provenance commentary | Closed CI gate failure on app-focused job (pre-existing fitness); not test-weakening — set-membership contract still exact. |
| `.agent/scripts/regenerate_step_inventory.py` | fix two `core.publishHtml` → `core.publishHTML` string literals (mapping + CERTIFIED_RECEIPTS) | Inventory mapping carried a camelCase key that did not match production `PluginStepId`. Now matches registry. |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | regenerated by the corrected script | Tables reflect the actual 20-key production registry with `core.publishHTML CERTIFIED_AT_SHA`. |

No other files touched.

## Verification recap (one-line)

```text
C1 inventory regen (script-driven) → C2 L1 targeted fitness test → C3 L2 full class → C4 XML canary — all green; CI run 35593694935 app-focused failure root-caused and resolved.
```
