# S2-A9 / G4+G5 PREFLIGHT — `core.milestone` Readiness Receipt

> Cycle: `cycle/lfc2-e1-milestone-g4prep`
> Slice: S2-A9 (`core.milestone`) — G4/G5 preflight ONLY (no authority change)
> Baseline verified: main `53b8fca0` (Merge PR #29, deleteDir G6-prep)
> Worktree branch: `cycle/lfc2-e1-milestone-g4prep` (fresh from main, zero code deltas)
> Date: 2026-09-12
> Scope firewall: this receipt CHANGES NO production code. LEGACY_PLUGIN_IDS,
> LegacyResidualSnapshot (5/5/5), legacy metadata rows, CanonicalCoreStepDecoder
> branches, and every dispatcher are untouched.

## Verdict

```text
READY_FOR_G4 = true
```

No technical blocker remains. All G1..G3 readiness work for `core.milestone`
was already merged to main via PR #26 (`cac9b587` is an ancestor of main
`53b8fca0`; `git merge-base main origin/cycle/lfc2-e1-milestone = cac9b587`,
and `git diff --name-only main origin/cycle/lfc2-e1-milestone` shows the only
post-cac9b587 divergence is the deleteDir G4/G5 slice, already superseded by
main's own deleteDir G5/G6 lineage).

## 1. State table (milestone per gate)

| Gate | State | Evidence | Location |
| --- | --- | --- | --- |
| G0 baseline | CLOSED | `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G0_BASELINE_RECEIPT.md` | main |
| G1 registry seam | CLOSED | `CoreMilestoneStep.registerInto(this)` in `CoreStepRegistryFactory.kt` (L122-140); candidate only, legacy-membership-wins keeps LegacyCore authoritative | main |
| G2 corpus migration | CLOSED | `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G2_RECEIPT.md` (differential freeze) | main |
| G3 contract certification | CLOSED | `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G3_RECEIPT.md` §6 freeze block | main |
| Current status | IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=true, CERTIFIED=false (correct per ADR-0074; only G8 sets CERTIFIED) | G3 receipt | main |
| G4 REGISTRY_PRIMARY | NOT STARTED — this preflight clears it | this receipt | — |
| G5 LEGACY_REMOVED | NOT STARTED | — | — |
| G8 CERTIFIED | NOT STARTED | — | — |

## 2. Fresh verification (this session, main `53b8fca0`, worktree `cycle/lfc2-e1-milestone-g4prep`)

All runs used the main repo's wrapper (`/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/gradlew`)
because the worktree lacks `gradlew`. Zero-fabrication: SHA-256 of each fresh XML cited below.

| Check | Command (argv) | Result | XML SHA-256 (first 16) |
| --- | --- | --- | --- |
| Milestone contract suite | `gradlew -p v2 :pipeline-application:test --tests CoreMilestoneStepContractSuiteTest --tests CoreMilestoneStepUnitTest` | exit 0, BUILD SUCCESSFUL 3s | — |
| — ContractSuiteTest XML | 23 tests, 0 failures, 0 errors, 0 skipped, ts 2026-09-12T21:42:07Z | GREEN | `8f463899e96c6ded` |
| — StepUnitTest XML | 19 tests, 0/0/0, ts 2026-09-12T21:42:08Z | GREEN | `bd34f0280eb3e7c6` |
| Counter fitness (5/5/5 oracle) | `gradlew -p v2 :pipeline-architecture-tests:test --tests 'S3*LegacyRemovedFitnessTest' --tests '*LegacyResidual*'` | exit 0; 7 suites: Echo 7, Pwd 8, WriteFile 4, Sleep 4, Error 12, EmitEvent 8, IsUnix 9 — all 0/0, ts 2026-09-12T21:42:30Z | fresh XMLs under `v2/pipeline-architecture-tests/build/test-results/test/` |

Environment note (not a code defect): the first `:pipeline-application:test`
attempt failed at `compileTestKotlin` because the worktree lacked
`examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar`
(referenced by `pipeline-application/build.gradle.kts:44`). Built locally
(`gradlew -p examples/example-uppercase-plugin jar`, exit 0); the jar is an
untracked build artifact. Any fresh G4 worktree must repeat this one-liner.

### Live counter state (machine-read from source at 53b8fca0)

- `CanonicalCoreStepDecoder.kt` L108-112 `LEGACY_PLUGIN_IDS` = `{core.milestone, core.cleanWs, core.load, core.waitUntil, core.archiveArtifacts}` → **5**
- `CanonicalCoreStepMetadata.kt` L21-29 residual rows = same 5 keys → **5**
- `durable/Canonical*NodeDispatcher.kt` (facade excluded) = ArchiveArtifacts, CleanWs, Load, Milestone, WaitUntil → **5**
- `LegacyResidualSnapshot`: `physicalResidual` = the same 5 keys; `registryPrimaryPendingRemoval = null` (converged point) → `assertConverged` green.

Current = **5 / 5 / 5 confirmed**.

## 3. Registry candidate readiness on main

- **Registration**: `CoreMilestoneStep.registerInto(this)` (candidate-only; `StructuralFamilyResolver` legacy-membership-wins keeps LegacyCore canonical until the G4 flip).
- **Capabilities**: `EVENT_SINK_CAPABILITY` + `MILESTONE_OPERATIONS_CAPABILITY`, declared in `StepContract.requiredCapabilities`; fail-closed admission proven (suite row 14, missing-capability rejection).
- **Codecs**: input codec encodes the WHOLE payload `{"kind":"milestone","ordinal":N,"label":...}` byte-identical to legacy `DslCompiledPipelineCompiler.milestonePayload`; ordinal > 0 validated fail-closed at decode.
- **Typed events**: `MilestoneReached` / `MilestoneAborted` emitted ONLY through `EVENT_SINK_CAPABILITY` (no direct sink access; LB-02/G3-A4.2 discipline).
- **Descriptor metadata**: `Effect.READ_ONLY`, `ReplayPolicy.MEMOIZED` — asserted equal to legacy metadata in suite row (contract completeness).

## 4. In-memory state-store semantics (task 2)

- `MilestoneStateStore` (`MilestoneOperations.kt:97`): `@Volatile` ordinal + `@Synchronized` `peek()/advance()`. Total `advance` returns sealed `MilestoneAdvanceResult.Reached|Aborted` (no exceptions as control flow).
- **Scope**: coordinator/run-level (`CanonicalDurableRunCoordinator` creates one store per instance, L370-376; wired into `ExecutionBoundaryFactory`/`RegistryExecutionBoundary` → `CanonicalRuntimeCapabilityAccess`). The former classloader-singleton `var` was REMOVED at G3 (state-seam fix). No global ambient state (CTX-P compliant).
- **Replay consistency**: MEMOIZED semantics proven by contract-suite row 13: same `runId` re-run reuses the SUCCEEDED op journal row and does NOT re-emit `MilestoneReached` (handler not re-run). Fresh run emits exactly one row (`OperationStatus.SUCCEEDED`).
- **Known limitation (accepted, legacy parity)**: durability decision `A_LEGACY_PARITY` (G3 receipt §6 freeze block): state is in-memory run-scoped; restart rehydration is NOT guaranteed — identical to the legacy `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal` instance field. Recorded as future debt (`S2_A9_MILESTONE_DURABILITY_SPIKE.md`, Option B). NOT a G4/G5 blocker.

## 5. Counter math (task 3)

Per `LegacyResidualSnapshot` header and `assertCurrentState/assertConverged` machinery:

```text
current (53b8fca0)          = 5 / 5 / 5   (milestone, cleanWs, load, waitUntil, archiveArtifacts)
G4 flip (milestone)         = 4 / 5 / 5   (ids only: LEGACY_PLUGIN_IDS -= core.milestone;
                                           registryPrimaryPendingRemoval = "core.milestone";
                                           metadata rows and dispatcher files untouched)
G5 removal (milestone)      = 4 / 4 / 4   (physicalResidual -= core.milestone;
                                           delete Milestone subtype + decoder branch +
                                           metadata row + CanonicalMilestoneNodeDispatcher.kt)
```

## 6. G4 flip invariants (what the flip commit MUST and MUST NOT touch)

The G4 commit must change EXACTLY:

1. `CanonicalCoreStepDecoder.kt`: remove `"core.milestone",` from `LEGACY_PLUGIN_IDS` (one line, plus a provenance comment following the A1/A5 pattern).
2. `LegacyResidualSnapshot.kt`: `registryPrimaryPendingRemoval = "core.milestone"` (one line; counters become 4/5/5 automatically — do NOT hand-edit counters).

MUST NOT touch (G4): `CanonicalCoreStepMetadata` rows, `CanonicalMilestoneNodeDispatcher.kt`, the `Milestone` command subtype, the decoder `Milestone` decode branch, `CoreStepRegistryFactory`, `CoreMilestoneStep.kt`, any other Step's entries. Those are G5 (physical removal) territory; their removal is enforced by `LEGACY_UNREACHABLE` fitness first.

Post-flip fitness required for the G4 receipt: dedicated 8-row G4 fitness (per S2-A5/A6 precedent: classify()==Registry for `core.milestone` on every production wiring, legacy dispatcher unreachable) + `S3*LegacyRemovedFitnessTest` all green (they consume `assertCurrentState`, which is transitional-aware) + ContractSuite 23/23 + Coordinator milestone rows.

## 7. Unmerged-but-ready work in `../pipeline-milestone` (task 4)

```text
merge-base(main, origin/cycle/lfc2-e1-milestone) = cac9b587 → ALREADY MERGED (PR #26)
```

`git diff --name-only main origin/cycle/lfc2-e1-milestone` (production, non-docs):
`CanonicalCoreStepDecoder.kt`, `CanonicalCoreStepMetadata.kt`,
`CanonicalDeleteDirNodeDispatcher.kt`, `CanonicalNodeDispatcher.kt`,
`CanonicalCoreStepCommandRegistryTest.kt`, `CoreDeleteDirStepContractSuiteTest.kt`,
`CoreDeleteDirStepUnitTest.kt`, `LegacyResidualSnapshot.kt` — this is the
**deleteDir G4/G5 slice on the milestone branch's tip**, which is STALE: main
already converged deleteDir via its own G5 (`physicalResidual` comment
"S2-A7 / G5 (2026-09-12)") and G6-prep (PR #29).

**Nothing needs rebasing for milestone readiness.** The branch tip's deleteDir
diff must simply NOT be merged (it predates main's deleteDir closure and would
fight the snapshot). Milestone G1..G3 content is fully in main.

## 8. Risks

| Risk | Mitigation | Blocker? |
| --- | --- | --- |
| Worktrees lack the uppercase plugin JAR → first test run fails at compileTestKotlin | One-liner local `jar` build (documented §2); or future main-side fix committing the jar path convention | No |
| Milestone state not restart-durable (Option A legacy parity) | Explicit G3 freeze-block decision; replay-safe within a run (MEMOIZED); Option B spike recorded as debt | No |
| Snapshot hand-edit temptation during G4 | Snapshot machinery computes 4/5/5 from `registryPrimaryPendingRemoval`; fitness enforces | No |
| Stale deleteDir diff on origin/cycle/lfc2-e1-milestone | Do-not-merge note (§7) | No |
| Serial merge queue drift (counters may be ≠5 at actual G4 time) | G4 flip commit must re-derive counters from live source; gate law is generic N→(N-1)/N/N | No |

## 9. Counters

```text
Certified Steps:         unchanged by this receipt (readiness only)
Legacy executable Steps: 5 (milestone, cleanWs, load, waitUntil, archiveArtifacts)
Registry-primary:        unchanged
```

---
**PREFLIGHT CLOSED — READY_FOR_G4 = true. `core.milestone` = IMPLEMENTED_UNCERTIFIED, AUTHORITY_FLIP_READY=true, CERTIFIED=false (G8 only).**
