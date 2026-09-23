# WU-RP-045 — UAT-RP-018 sandbox 'os' + LOCAL cert (slice receipt)

**CI:** 35839625273 at f1ea0cf7 — **SUCCESS** (10/10 jobs: domain-unit, architecture-fitness, application-shard × 4, sbom, secret-scan, compile, dogfood).
**Type:** test-only slice (production code untouched). Pattern mirrors WU-RP-044.
**Files touched (1):**
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal007SandboxProfileTest.kt` (+133 lines, two new tests).
**Local-only memory (not committed):**
- `.agent/SESSION_POINTER.md` and `.agent/WORK_JOURNAL.md` (per `.gitignore`).

## Scope decision (recorded explicitly)

The session report set the hard NO_GO: **do NOT start OS-level sandbox framework; no `EffectiveRunPlan`, no `JobDefinition`, no parser YAML, no new public API surface.** This WU certifies only the **verifiable limits of the LOCAL profile as it exists today** and **pins the fail-closed rejection of profile `os` from the CLI**. RP-7+ owns the actual OS-level framework. This WU closes the RP-5 exposure gap: the LOCAL sandbox story is now reproducible and the unsupported profile is explicitly failed closed at L3 with a diagnostic that names its prerequisites (ADR-0016 M5, M9).

## Tests added

### `UAT-L7-TC-003` — CLI rejects sandbox-profile os with ADR-0016 M5 M9 fail-closed message

Spawns the real `pipelinek` CLI with `--sandbox-profile os` and asserts:

- exit completes within 30 s (fail-closed must be cheap),
- the captured error mentions `ADR-0016`, `M5`, `M9`, and the rejected profile `os`,
- the resulting exit is not a hang.

This is the E2E CLI pin for the previously-observed `SandboxProfileUnsupportedException("sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. Accepted: {none, local}.")`. UAT-RP-018 required exactly this kind of locked-in evidence so the limitation cannot drift.

### `UAT-L7-TC-004` — profile LOCAL capabilities contract: cwd / env / kill / parallel

Single-method oracle for the **bundled LOCAL surface** that this version advertises. It writes the canonical contract to a tempfile via three `sh` calls inside one `stage("cap") { … }` (DSL pattern from SB-S-005: `sh(...)` lives directly in the stage, not wrapped in `steps { … }`):

| Capability                     | Oracle in this test                         | Other coverage            |
|--------------------------------|---------------------------------------------|---------------------------|
| HOME not mutated by LOCAL      | `printenv HOME > outFile` → `/home/runner`  | SB-S-003                  |
| JAVA_HOME preserved            | `printenv JAVA_HOME >> outFile` → `/opt/jdk`| SB-S-009                  |
| cwd is per-stage workspace     | `pwd >> outFile` → `/…/ctrl/workspace/cap-0`| SB-S-001                  |
| LD_PRELOAD scrubbed            | (covered separately)                        | SB-S-004                  |
| PATH rogue drop                | (covered separately)                        | SB-S-005                  |
| parallel branches isolated cwd | (covered separately)                        | SB-S-008                  |
| write outside workspace report | (covered separately)                        | SB-S-002                  |
| kill mid-step classification   | (covered separately)                        | SB-S-007                  |
| resume with profile change     | (covered separately)                        | SB-S-010                  |
| class-level `@Timeout`         | (covered separately)                        | UAT-L7-TC-001             |
| afterEach kills orphans        | (covered separately)                        | UAT-L7-TC-002             |

The tempfile-redirect pattern keeps the test output-isolated from the JSON event stream (same approach SB-S-001..010 use), which is why the failure log earlier in the session was a "missing RunFinished" rather than a wrong-content assertion — the script compiled and executed, the assertion was looking at the wrong surface.

## Verification ladder executed

```text
L1  TC-003 + TC-004 + the 12 SB-S-* + UAT-L7-TC-001 + UAT-L7-TC-002:
       14/14 PASS in 88.7s, 0 failures, 0 errors, 0 skipped.
       XML timestamp 2026-09-23T08:29:09.684Z.
L2  Neighbors (no production code touched):
       Lpr011SecretRedactionTranscriptUatTest       6/6
       Lpr011r2SecretRedactionAtRestUatTest         11/11
       UatLocal011WorkflowControlTest               12/12 PASS, 1 SKIP (historic burn-down)
       WULpr011ResumeLifecycleUatTest                1/1
       TranscriptStreamingEmissionTest (RP-044)       4/4
L3  :pipeline-step-sdk:runtime:test --rerun-tasks:
       SandboxProfileTest        11/11
       RunnerTrustProfileTest     3/3
L4  :pipeline-application:test --rerun-tasks:
       1737 tests, 0 failures, 115 skipped (0 obligatory UAT-RP-001..024
                                              among the skipped — all historic
                                              burn-down snapshots from earlier
                                              certifications; audit recorded
                                              in WORK_JOURNAL)
       duration 15m 47s, 213 test classes.
L5  ./gradlew check (incremental):
       BUILD SUCCESSFUL in 13s, all tasks UP-TO-DATE except the fresh
       installDist that the L4a run produced. No-op gate proves the L4
       evidence still matches the working tree.
```

## Skipped-test audit (115, recorded honestly)

```text
0 of the 115 skipped @Ignored tests map to UAT-RP-001..024 obligatory rows.
All 115 are historic characterization snapshots preserved for burn-down
traceability:
- CorePwdRegistryPrimaryFitnessTest (StepConstitution burn-down)
- CoreSleepCoordinatorCharacterizationTest
- … and 113 others, of which 3 are quarantines inside UAT-local tests:
    * UatLocal011WorkflowControlTest:479
    * WULpr010CliCharacterizationTest:267
    * UatLocal008CredentialsTest:1150
None are regressions introduced by WU-RP-045.
```

## Reference implementation notes

The existing `UatLocal007SandboxProfileTest` already had twelve tests covering the LOCAL profile cap-by-cap with tempfile-redirected oracles (SB-S-001..010) plus two charter tests (UAT-L7-TC-001, UAT-L7-TC-002). What it was missing was:

1. **An E2E CLI-level pin** for the fail-closed rejection of `os`. The production code already does reject `os` correctly, but the rejection was only documented in design notes and only visible via the SDK's `SandboxProfileConfig` unit tests. TC-003 promotes the contract to a CLI integration assertion so future refactors cannot regress the failure mode without breaking a UAT.
2. **A single-method capabilities contract oracle.** The LOCAL story was tested cap-by-cap across ten methods with a single-purpose pipeline per call. TC-004 bundles the contract so the certification has one place that names what LOCAL actually delivers today.

Both additions are output-isolated (tempfile redirect) so they don't depend on the JSON event-stream parser evolving in WU-RP-044+.

## Identifier-collision note (RP-5 inbox, deferred)

The session report flagged that `WU-RP-045`, `ADR-0096`, `ADR-0097`, `ADR-0098` overlap with the in-flight `docs/pipeline-kotlin-config-overlay-package/` work. This WU does NOT touch the overlay package — it only adds two tests to an existing UAT class. Any renumbering decision is left to whoever integrates the overlay package; the test IDs `UAT-L7-TC-003` and `UAT-L7-TC-004` are stable identifiers inside `UatLocal007SandboxProfileTest` and do not collide with anything in flight.

## Honest product claim (mirrors WU-RP-044)

```text
LOCAL profile, as of f1ea0cf7:
    cwd set to per-stage workspace, HOME preserved, JAVA_HOME preserved,
    LD_PRELOAD scrubbed, PATH rogue entries dropped, parallel branches
    have isolated cwds, kills mid-step preserve LOST status, resume with
    profile change re-attaches. Bundled limit: process-level isolation
    only (no syscall filter, no user namespace, no cgroup, no seccomp).

profile 'os':
    Rejected at L3 with SandboxProfileUnsupportedException citing
    ADR-0016 M5/M9. Status today: still not implemented (no surprise,
    this is RP-7+ scope).
```

## Follow-up recommendations (NOT executed by this WU)

The session report listed them and they remain open:

1. **WU-RP-046 (RP-5 close-out gate):** UAT-MATRIX row update, CERTIFICATIONS update,
   WU-RP-044 / WU-RP-045 sum. Required to formally close RP-5; not part of this slice.
2. **RP-7+ OS-level sandbox:** requires ADR-0096/97/98 (in flight in the overlay
   package), `JobDefinition`, parser YAML, `EffectiveRunPlan` — all explicitly
   excluded by the operator.
3. **Renumbering when integrating the overlay package:** do not reuse
   `WU-RP-045` or `ADR-0096/97/98`; pick new IDs that don't collide.
4. **CLI exit-code-0-on-typed-exception defect (separate WU):** observed while
   inspecting the `os` rejection, not in scope of this slice.
