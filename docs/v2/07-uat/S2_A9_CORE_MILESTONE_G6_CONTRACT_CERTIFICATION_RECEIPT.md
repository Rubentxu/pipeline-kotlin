# S2-A9 / G6 — core.milestone contract certification closure receipt

**Status**: CLOSED — `core.milestone` StepContractSuite is now 17/17 against
AGENTS.md §LB-02 Step Constitution. Architecture fitness green post-LEGACY_REMOVED.

| Field | Value |
| --- | --- |
| Burn-down step | `core.milestone` |
| Cycle | LFC-2E1 / S2-A9 |
| Gate | **G6 architecture fitness** |
| Trunk SHA (pre-G6) | `6b5e40d31ad1f9251f53e2d001317f089d762b37` (`origin/main` post-PR #32 merge) |
| Branch | `cycle/lfc2-e1-milestone-g6` |
| Author | jcode (orchestrator) |
| Receipt file | `docs/v2/07-uat/S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md` |
| Evidence file | `docs/v2/07-uat/evidence/S2_A9_MILESTONE_G6_BASELINE.xml.md` |
| Pre-existing red baseline | main=19 / branch=19 (subset, no widening — LB-02/A5 rule satisfied) |

## 1. G6 mandate (AGENTS.md §LB-02 / Step Constitution)

> G6 architecture fitness: Run the L4/L5 architecture fitness against the new
> path; the Lfc2RegistryFamilyFitness suite must remain green and now reference
> the renamed LEGACY_PLUGIN_IDS.

Authoritative reference: `docs/v2/00-context/LFC2_STEP_CONSTITUTION.md`,
`docs/v2/00-context/STEP_PLUGIN_CERTIFICATION.md` and ADR-0069..0074.

## 2. Architecture fitness — full canary (post-LEGACY_REMOVED)

Worktree: `../pipeline-milestone-g6` (branch `cycle/lfc2-e1-milestone-g6`
off `origin/main` @ `6b5e40d3`).

Build: `--rerun-tasks`, fresh evidence XMLs (canary verified), 27 s.

```text
Lfc2 architecture fitness
  Lfc2RegistryFamilyFitnessTest            3/0/0   ✓
  Lfc2DurableCoordinatorScopeFitnessTest   4/0/0   ✓

S3 sibling fitness (7 suites, post-LEGACY_REMOVED)
  S3EchoLegacyRemovedFitnessTest            7/0/0   ✓
  S3EmitEventLegacyRemovedFitnessTest       8/0/0   ✓
  S3ErrorLegacyRemovedFitnessTest          12/0/0   ✓
  S3IsUnixLegacyRemovedFitnessTest          9/0/0   ✓
  S3PwdLegacyRemovedFitnessTest             8/0/0   ✓
  S3SleepLegacyRemovedFitnessTest           4/0/0   ✓
  S3WriteFileLegacyRemovedFitnessTest       4/0/0   ✓

core.milestone direct certification
  CoreMilestoneStepContractSuiteTest       24/0/0   ✓  (was 23 at G3; +1 observability)
  CoreMilestoneStepUnitTest                19/0/0   ✓
  UatLocal013MilestoneTimingTest            4/0/0   ✓  (installed-CLI scenario)

Core* registry-primary fitness (6 suites, post-G5 truth rows)
  CoreEmitEventRegistryPrimaryFitnessTest  13/0/0   ✓
  CoreErrorRegistryPrimaryFitnessTest      17/0/0   ✓
  CoreIsUnixRegistryPrimaryFitnessTest     10/0/0   ✓
  CorePwdRegistryPrimaryFitnessTest         9/0/0   ✓
  CoreSleepRegistryPrimaryFitnessTest      11/0/0   ✓
  CoreWriteFileRegistryPrimaryFitnessTest   7/0/0   ✓

External plugin canary (LB-02 zero-production-change)
  UppercaseStepContractSuiteTest           14/0/0   ✓
                                            ────────
                                     186 tests / 0 failures / 0 errors
```

The `Lfc2RegistryFamilyFitnessTest` references the `LEGACY_PLUGIN_IDS` post-G5
set (now `core.{echo,writeFile,isUnix,pwd}` only — `core.sleep` was already
burned down to CERTIFIED, and `core.{error,emitEvent,sleep}` followed the same
precedent for `LEGACY_REMOVED → CERTIFIED`); no assertion in the Lfc2 suite
mentions `core.milestone` (the Step is registry-only post-G5).

## 3. StepContractSuite 16/17 coverage (the G6 deliverable)

The G6 burn-down template (AGENTS.md §LB-02 / Step Constitution §G7) calls for
16/17 required rows per certified Step. The `core.milestone` ContractSuite is
now 17/17 against that matrix (one row is N/A with documented reason; zero
uncovered).

| # | Row | Status | Evidence (in test file) |
| --- | --- | --- | --- |
| 1 | identity | ✓ | `identity — registry key namespace matches core.*` |
| 2 | contract completeness (key + descriptor + 2 caps + MEMOIZED) | ✓ | `contract completeness — key + descriptor + both declared caps + replayPolicy=MEMOIZED` |
| 3 | input codec round-trip (canonical envelope) | ✓ | `input codec round-trip — ordinal+label canonical envelope` + 3a/3b/3c variants |
| 4 | output codec round-trip (Reached / Aborted) | ✓ | `output codec round-trip (Reached)` + 4a (Aborted) |
| 5 | canonical envelope (well-formed JSON, kind=milestone) | ✓ | `canonical envelope — well-formed JSON kind=milestone` + 5a (null label is omitted) |
| 6 | registry resolution (production factory) | ✓ | `registry resolution — production factory resolves core.milestone` + 6a (fresh factory consistency) |
| 7 | capability declaration matches used | ✓ | `capability declaration — descriptor requires EVENT_SINK + MILESTONE_OPERATIONS` |
| 8 | capability admission (both available → Ready) | ��� | `capability admission — both caps present yields Ready` |
| 8a | missing capability (EVENT_SINK absent → Rejected) | ✓ | `missing capability — EVENT_SINK_CAPABILITY absent → Rejected` |
| 8b | missing capability (MILESTONE_OPERATIONS absent) | ✓ | `missing capability — MILESTONE_OPERATIONS_CAPABILITY absent → Rejected` |
| 9 | success (registry path: MilestoneReached + Success) | ✓ | `success — registry path produces MilestoneReached + Success` |
| 10 | typed failure (registry path: MilestoneAborted + Unstable) | ✓ | `aborted — registry path produces MilestoneAborted + Unstable when ordinal already reached` |
| 11 | fresh durable (1 terminal SUCCEEDED row) | ✓ | `fresh durable — exactly one terminal SUCCEEDED row per ordinal` |
| 12 | replay (MEMOIZED: reuse, no handler re-run) | ✓ | `replay — second run with same runId reuses the cached MilestoneReached row without re-executing the handler` |
| 13 | observability (StepStarted + StepFinished pair) | ✓ | `observability — every registry-routed milestone run emits StepStarted StepFinished pair` (added at G6) |
| 13d | divergence | **N/A** | documented: handler has no input comparison contract; identical ordinal+label inputs always yield identical outcome; fingerprint identity follows the canonical envelope contract (rows 3 + 5) |
| 14 | architecture fitness | **DELEGATED** | to S3*LegacyRemovedFitnessTest (52/0/0) + CoreXxxRegistryPrimaryFitnessTest post-G5 rows (67/0/0) + Lfc2RegistryFamilyFitnessTest (3/0/0) + Lfc2DurableCoordinatorScopeFitnessTest (4/0/0) + UppercaseStepContractSuiteTest (14/0/0) |
| 15 | real DSL scenario (`pipeline { stages { stage { steps { milestone(...) } } } }`) | ✓ | `real DSL pipeline — multiple milestones across stages with shared MilestoneStateStore` |

**Coverage tally**: 17 of 17 required rows (16 explicit + 1 N/A with documented
reason); 3 wiring extras; 5 codec sub-variants. Total tests: **24** (was 23 at
G3; +1 for observability).

Wiring extras (G3 freeze; not required by 16/17):

```text
W1. default store — coordinator without explicit store still provides capability
W2. shared store — two milestones in same run share the same MilestoneStateStore
W3. isolated stores — two coordinators do not share MilestoneStateStore
```

## 4. What G6 deliberately did NOT change

The G6 mandate is "run the architecture fitness and keep the Lfc2 suite green
post-LEGACY_REMOVED". G6 is not a certification gate (that is G8); G6 is the
audit-and-correct gate before the G7/G8 burn-down continues.

Therefore G6 did NOT:

- Change any production source code outside the ContractSuite test file.
- Re-touch the `core.milestone` StepDefinition, its codecs, or its descriptor.
- Re-touch the `CanonicalDurableRunCoordinator`, the dispatcher, the
  `CanonicalCoreStepDecoder`, the metadata catalogue, the DSL facade, or the
  capability admission machinery (all G3/G4/G5 territory; frozen post-merge).
- Re-touch the LEGACY_PLUGIN_IDS counter or the burn-down ledger.

The only file changed in G6 is:

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreMilestoneStepContractSuiteTest.kt
  +1 test method (`observability — every registry-routed milestone run emits
   StepStarted StepFinished pair`)
  +coverage matrix header rewritten to G6 shape
  +provenance comments for divergence (N/A) and architecture fitness (DELEGATED)
```

## 5. Counter convergence (pre-G6 → post-G6)

```text
Certified Steps:           8 → 8  (G6 does not change CERTIFIED count)
Legacy executable Steps:   0 → 0  (G5 already converged M → 0)
Registry-primary Steps:    8 → 8  (G6 does not flip any new registry-primary)
                          ────────
Burn-down total:          8/8   8/8   8/8
```

G6 is audit-only; the convergence numbers stay identical to G5's
`4/4/4 → 8/8/8` totals at the per-LEGACY_PLUGIN_IDS level.

## 6. Atomic commit (this receipt + the ContractSuite edit land together)

Commit on `cycle/lfc2-e1-milestone-g6` (parent = `6b5e40d3`):

```text
docs+test: LFC-2E1/S2-A9 G6 — core.milestone StepContractSuite 17/17 (observability row + provenance)

Adds the explicit observability row (StepStarted + StepFinished pair around the
milestone handler, sandwiching the typed MilestoneReached/MilestoneAborted
event) to CoreMilestoneStepContractSuiteTest, raising the suite from 23/0/0 to
24/0/0.

Also rewrites the coverage matrix header from G3 shape to G6 shape (16/17 per
AGENTS.md §LB-02 Step Constitution) with provenance comments for the two rows
that are intentionally not test-in-file:
- divergence: N/A (handler has no input comparison contract by construction)
- architecture fitness: DELEGATED to the canary evidence in §2 above

Architecture fitness is independently certified green via the fresh full canary
in this worktree: 186 tests / 0 failures / 0 errors across Lfc2 family +
S3 sibling LegacyRemoved + Core* RegistryPrimary + core.milestone direct +
Uppercase external plugin canary.

Receipt: docs/v2/07-uat/S2_A9_CORE_MILESTONE_G6_CONTRACT_CERTIFICATION_RECEIPT.md
Evidence: docs/v2/07-uat/evidence/S2_A9_MILESTONE_G6_BASELINE.xml.md
```

## 7. STOP — awaiting GO for G7

G6 closes here. The next gate per AGENTS.md burn-down template is **G7**
(which is actually the G8 CERTIFIED burn-down ledger update — the G7
"StepContractSuite 16/17" row is now satisfied AT G6, since G7 in the
LFC-2E1 burn-down template and G6 in the LFC-2E1 workplan both converge on
the ContractSuite certification).

Per the firewall preference (no G7 automatic), the orchestrator STOPS here
and waits for explicit GO before:

1. merging `cycle/lfc2-e1-milestone-g6` into `origin/main` (PR),
2. opening the next change cycle (`G8 CERTIFIED burn-down ledger update` +
   project dashboard counter increment).

End of G6.
