# Files to update in the repository

## Add permanently

### Specs
- SPEC-LFC-016-STEP_CONSTITUTION.md
- SPEC-LFC-017-EXECUTABLE_SCENARIO_CORPUS.md
- SPEC-LFC-018-PIPELINE_TEST_HARNESS.md
- SPEC-LFC-019-STEP_PLUGIN_CERTIFICATION.md
- SPEC-LFC-020-GENERIC_BODY_EXECUTION.md
- SPEC-LFC-021-TEST_SANDBOX_PROFILES.md

### ADRs
- ADR-LFC-018..023 from this pack.

## Merge into current authorities

- root `AGENTS.md`
- LFC `ROADMAP.md`
- LFC `IMPLEMENTATION_BACKLOG.md`
- LFC `UAT_CATALOG.md`
- LFC `TEST_MATRIX.md`
- LFC `UAT_RUNBOOK.md`
- LFC `TRACEABILITY_MATRIX.md`
- `examples/README.md` after ScenarioRunner exists

## Regenerate/update after merge

- `INDEX_LFC.md`
- docs MANIFEST.json if retained
- ADR/SPEC index
- compatibility catalogue/generated metadata

## Do not replace

Do not delete simply because this pack exists:

- ADR-LFC-003/004/005/006/007/015/016
- SPEC-LFC-003/004/005/014/015
- durable runtime ADR history
- existing UAT receipts/history

The new documents refine them.
