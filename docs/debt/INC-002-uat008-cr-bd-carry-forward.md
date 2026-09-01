# INC-002 — 8 UAT008 CR-BD failures unchanged from ml-r10-2 baseline

| Field | Value |
|---|---|
| destination | `docs/debt/INC-002-uat008-cr-bd-carry-forward.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/ml-r10-2-5-scripting-withcredentials-resolution` / `debt-report.json` |
| fingerprint | `dfa323341cabf4e0` |
| cluster_id | `CL-04` |
| severity | `high` |
| priority | `P0` |
| attribution | `pre_existing` |
| owner | `human` (multi-cycle carry-forward: ml-r10-3 / ml-r10-4 / H0-wiring-completion) |
| followon_cycle | `ml-r10-3-audit-ordering` (CR-BD-026) + `ml-r10-4-nested-restoration` (CR-BD-032) + `H0-wiring-completion` (CR-BD-018/019/020/021/027 + F-WRAPPER-SH) |
| rationale | "8 CR-BD failures (018, 019, 020, 021, 022, 026, 027, 032) UNCHANGED from ml-r10-2 baseline with identical fingerprint. Cycle made ZERO net change on UAT008." |

## Description

The 8 CR-BD failures at UAT008 (CR-BD-018, 019, 020, 021, 022, 026, 027, 032) are unchanged from `ml-r10-2` baseline. Cycle did not modify `WithCredentialsExecutor.kt`, `LocalCredentialProvider`, `LocalSecretStore`, or any H0-path code. Per non-claim block, these failures are OWNED by follow-on cycles.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| test | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatLocal008CredentialsTest.xml` | tests=27, skipped=1, failures=8 — 8 CR-BD FAILURES UNCHANGED | `b7316d83e328f2df38b91a19ac901b6a0d5614bf7cc4877353cd39a74b8e1e19` |
| command | git diff stat for H0-path modules | EMPTY (no H0-path code modified) | — |
| artifact | `spec.v1.md §Non-claim block (line 7)` | Spec explicitly disclaims ownership | — |

## CR-BD Ownership Map

| CR-BD | Owner | Cycle | Authorized? |
|---|---|---|---|
| CR-BD-018 | H0-wiring-completion | TBD | NOT yet authorized |
| CR-BD-019 | H0-wiring-completion | TBD | NOT yet authorized |
| CR-BD-020 | H0-wiring-completion | TBD | NOT yet authorized |
| CR-BD-021 | H0-wiring-completion | TBD | NOT yet authorized |
| CR-BD-022 | H0-wiring-completion | TBD | NOT yet authorized (also tracked as `INC-001`) |
| CR-BD-026 | ml-r10-3-audit-ordering | P2 | NOT yet authorized |
| CR-BD-027 | H0-wiring-completion | TBD | NOT yet authorized |
| CR-BD-032 | ml-r10-4-nested-restoration | P1 | NOT yet authorized |
| F-WRAPPER-SH | H0-wiring-completion | TBD | NOT yet authorized |

## Owner

`human` — multi-cycle carry-forward. Each CR-BD has its own owner follow-on cycle per spec non-goals §13..§15.

## Status

`open` — pre-existing carry-forward. Net effect of `ml-r10-2-5` on UAT008: ZERO.

## Cross-references

- verify-finding: `dcdb737f01abd5c97f2771819f5f6d14bbf908101c4960e61aa3ad261693db3f`
- debt-report: `FIND-000012`
- spec: `spec.v1.md §Non-claim block (line 7)` + `§Non-goals §13..§15`
- related INC: `INC-001` (CR-BD-022), `INC-003` (SC-007)
