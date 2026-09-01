# INC-003 — UAT005 SC-007 test-environment git-identity config missing

| Field | Value |
|---|---|
| destination | `docs/debt/INC-003-uat005-sc-007-test-env.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/ml-r10-2-5-scripting-withcredentials-resolution` / `debt-report.json` |
| fingerprint | `dfa323341cabf4e1` |
| cluster_id | `CL-04` |
| severity | `low` |
| priority | `P3` |
| attribution | `pre_existing` |
| owner | `human` (test-infra environment config) |
| followon_cycle | `test-infra` (env config) |
| rationale | "UatLocal005CheckoutGitTest SC-007 FAIL: java.lang.IllegalStateException: 'git command failed: git -C /tmp/junit-XXX/work commit -m New commit, exit=1, err=git-wrapper: repo NO CLASIFICADO, no firmo commits (fail-closed)'. Root cause: test environment git-identity config missing." |

## Description

UAT005 SC-007 (poll detects changed SHA and emits `GitPollChanged`) FAIL. Pre-existing infrastructure configuration gap, NOT cycle-introduced. Cycle did not modify git/scm modules.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| test | `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatLocal005CheckoutGitTest.xml` | SC-007 FAIL: git-wrapper fail-closed on missing git-identity | `9171ab898722a21854c0d4e2f76aa11cf760e0f6f2e2c5832e0563ded166c637` |

## Owner

`human` — test-infra environment configuration. Two fix paths:

1. Set `RANDOM_GIT_COMMITTER_DISABLED=1` in env (allow random committer emails)
2. Register git-identity at `/home/rubentxu/.config/git-identity/hosts.conf`

## Status

`open` — pre-existing carry-forward. Test-environment config gap, not blocking verify.

## Cross-references

- verify-finding: `d816de37d69e6117af93311f5377e09d6baa3f78c11f8e1bc9c2616238e8ddb6`
- debt-report: `FIND-000013`
- related INC: `INC-001` (CR-BD-022), `INC-002` (UAT008 CR-BD carry-forward)
