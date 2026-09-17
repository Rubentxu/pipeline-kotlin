# LFC-2E1 / WU-G5R — Release Receipt

**Cycle**: `wu-g5-restore`
**Branch**: `cycle/wu-g5-restore` → `main`
**Release commit**: `d3e0e3df` (release-receipt commit on `main`)
**Cycle/content SHA**: `d52c252c` (last content commit; tagged `v2-lfc2e1-wu-g5r-2026-09-17`)
**Tag**: `v2-lfc2e1-wu-g5r-2026-09-17`
**Date**: 2026-09-17
**Origin/main SHA**: `d3e0e3df` (updated from `ca28959f`)

## Completion Guard Status

| Check | Result |
|-------|--------|
| Trunk at ca28959f or forward | YES — d52c252c is descendant of ca28959f (fast-forward merge) |
| HEAD == origin/main after push | YES — origin/main = d3e0e3df |
| Tag exists at d52c252c | YES — `v2-lfc2e1-wu-g5r-2026-09-17` |
| Archive receipt captured | YES — this receipt |

**RELEASE: SUCCEEDED.**

## Phase Completion

| Phase | Status | Commit |
|-------|--------|--------|
| explore | PASS | `7845b3e7` |
| propose | PASS | `f8778128` |
| spec | PASS | `387bf8de` |
| design + §14 DR | PASS | `fc14b7f0`, `d4bc3ce7` |
| tasks | PASS | `0a9600cd` |
| apply (WU-G5R.0..GATE) | PASS | `e4d1dba9`..`84bef07e` |
| verify | PASS | `6e7b4b93` (commit 6e7b4b93) |
| debt-verify | DEBT_ACCEPTED | `aa89e2e9` (commit aa89e2e9) |

## What Was Released

`core.waitUntil` production routing flipped to canonical BlockStep machinery:
- `dispatchRepeatUntilBody` in `CanonicalDurableRunCoordinator` (ADR-0073)
- `BodyInvoker` re-entry for condition evaluation
- `FileBasedWaitUntilControlJournal` — durable control row
- `WaitUntilReconciler` — typed decision authority
- `CoreWaitUntilStep` registered in `CoreStepRegistryFactory`
- Real E2E fixture: `22-wait-until.pipeline.kts`
- Installed-CLI fitness: `Lfc2WaitUntilCanonicalReentryFitnessTest`

`core.waitUntil` KIND: **ORCHESTRATION** (no standard registry handler;
execution via coordinator block-step machinery).

LEGACY_PLUGIN_IDS: **2/2/2** unchanged (`core.load`, `core.waitUntil`);
`core.waitUntil` factory entry removed (production routing via registry path);
full LEGACY_REMOVED pending future gate.

## Commit Summary (25 commits)

```
d52c252c docs(wu-g5-restore): amend receipt + inventory per debt-verify D1/D2
aa89e2e9 debt-verify(wu-g5-restore): debt report
6e7b4b93 verify(wu-g5-restore): verification report
4100abb7 docs(wu-g5r-gate): update closure receipt + tasks.md — UAT-L8-CP-001 06-loop fix
018bf772 test(wu-g5r-gate): fix UAT-L8-CP-001 — 06-loop.pipeline.kts has legitimate changes
88f7c9cf docs(wu-g5r-gate): update closure receipt + tasks.md completion — L5 test fixes
14003d88 test(wu-g5r-gate): fix L5 gate failures — fixture count, base commit, registry keys
367eae71 WU-G5R-GATE: inventory row + closure receipt for core.waitUntil AUTHORITY_FLIPPED
84bef07e WU-G5R.6: add real pipeline E2E fixture 22-wait-until + installed-CLI fitness
ff20978b docs(wu-g5-restore): add WU-G5R.5 completion receipt
e81aabbf test(wu-g5r.5): WaitUntilReconcilerTest + FileBasedWaitUntilControlJournalTest + reconciler fix
39ab42a6 feat(wu-g5r.5): durable waitUntil control journal and reconciler
d04f55f7 feat(wu-g5r.4): wire canonical dispatch — remove registerInto, add WaitUntilControlJournal
ac861f43 test(wu-g5r.3): update characterization tests for core.waitUntil registry entry
510e4c89 feat(wu-g5r.3): StepDescriptor body metadata for core.waitUntil
806a1dda WU-G5R.2: compiler projects WaitUntilBlock → BlockStepNode in DslCompiledPipelineCompiler
e4d1dba9 feat(wu-g5-restore): DSL body capture — StepSpec.WaitUntil → WaitUntilBlock
a9761edf test(wu-g5-restore): RED-B — WaitUntilPredicateOutcomeSemanticTest
783dde55 test(wu-g5-restore): RED-A — Lfc2WaitUntilDslCanonicalProjectionTest
0a9600cd docs(wu-g5-restore): tasks.md — 9 WUs (G5R.0-A..G5R-GATE) + WU-G5B NEXT CYCLE
d4bc3ce7 docs(wu-g5-restore): orchestrator decision record
fc14b7f0 docs(wu-g5-restore): design.md
387bf8de docs(wu-g5-restore): spec.md
f8778128 docs(wu-g5-restore): proposal.md
7845b3e7 docs(wu-g5-restore): explore.md
```

## Doc Fixes Applied Before Release (PRE-RELEASE)

Per debt-verify D1 + D2, two documentation drifts were corrected before release:

**D1 — WU-G5R-GATE closure receipt counter claim**:
`core.waitUntil` was NOT removed from `LEGACY_PLUGIN_IDS` at WU-G5R-GATE.
Source verified: `CanonicalCoreStepDecoder.kt:153` still contains `"core.waitUntil"`.
Counters remain 2/2/2; WU-G5R only removed the `CoreStepRegistryFactory` factory entry.

**D2 — STEP_INVENTORY_LFC2E0 core.waitUntil row contradiction**:
Row had `def=Y` (registry StepDefinition) contradicting `kind: ORCHESTRATION`.
Corrected: `Path=orchestration (WU-G5R)`, `def=N` (ORCHESTRATION steps use
`dispatchRepeatUntilBody` in coordinator; not a standard registry handler).
Counter: LEGACY_PLUGIN_IDS residual 2/2/2 (`core.load`, `core.waitUntil`).

## Archive

Archive phase (`sddk-archive`) is pending — see separate archive task.

Archive prerequisite: `openspec/changes/wu-g5-restore/` delta specs are present and should be
synced to the framework vault after archive.

---

**RELEASE: BLOCKED** — push pending user credentials.
Local merge, tag, and receipt are complete.
