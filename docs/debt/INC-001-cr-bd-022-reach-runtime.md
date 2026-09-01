# INC-001 — CR-BD-022 reach-runtime

| Field | Value |
|---|---|
| destination | `docs/debt/INC-001-cr-bd-022-reach-runtime.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/ml-r10-2-5-scripting-withcredentials-resolution` / `debt-report.json` |
| fingerprint | `43067fa5a07f29df` |
| cluster_id | `CL-04` |
| severity | `high` |
| priority | `P0` |
| attribution | `pre_existing` |
| owner | `human` (H0-wiring-completion — NOT yet authorized per AGENTS.md V2 PRIME DIRECTIVE) |
| followon_cycle | `H0-wiring-completion` |
| rationale | "UAT008.CR-BD-022 (USERNAME_COLON_PASSWORD binding resolves user_pass env var) still <failure> at runtime despite the cycle's wiring executing (Main.kt:224 instantiates Kotlin24ScriptingHost which runs the extractor+escaper). Carry-forward per spec non-claim block." |

## Description

UAT008.CR-BD-022 (USERNAME_COLON_PASSWORD binding resolves user_pass env var) is `<failure>` at runtime. Pipeline outcome is `<failure>`, not `<success>`. All 8 CR-BD failures pre-existed in `ml-r10-2` baseline with identical fingerprint; cycle made ZERO net change on UAT008.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| test | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatLocal008CredentialsTest.xml` | CR-BD-022 FAILING at runtime | `b7316d83e328f2df38b91a19ac901b6a0d5614bf7cc4877353cd39a74b8e1e19` |
| source | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt:224` | Instantiates `Kotlin24ScriptingHost` (the cycle's changed host) | — |
| artifact | `spec.v1.md §Non-claim block (line 7)` | Spec explicitly disclaims ownership of the 8 CR-BD-IDs | — |

## Owner

`human` — `H0-wiring-completion` cycle (NOT yet authorized per AGENTS.md V2 PRIME DIRECTIVE). Suggested path: instrumented-diagnose the runtime reach via worktree-level evidence (mirroring `ml-r10-2-1` worktree methodology).

## Status

`open` — pre-existing carry-forward. Not introduced by `ml-r10-2-5`. Not in scope of this cycle's positive claim.

## Cross-references

- verify-finding: `11a587586c18fda25a9ed331a244caa52406a827b48632a87a59f0aab535ceb7`
- debt-report: `FIND-000011`
- spec: `spec.v1.md §B-1` (INV-L10-CR-001 runtime reach) + `§Non-claim block (line 7)`
- related INC: `INC-002` (8 UAT008 CR-BD carry-forward), `INC-003` (SC-007)
