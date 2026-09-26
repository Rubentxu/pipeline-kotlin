# PR-004 Receipt: Open PR Classification
**Generated at (UTC):** 2026-09-26T10:15:56Z
**Repository:** `Rubentxu/pipeline-kotlin`
**Source of truth:** `gh pr list --state open` (machine-verifiable).

---

## Summary

- **Total OPEN PRs classified:** 25
- **KEEP:** 1
- **SUPERSEDE:** 10
- **DEPENDENCY:** 14

---

## Classification Table

| # | PR | Title | Head | Author | Category | Rationale |
|---|---|---|---|---|---|---|
| 54 | #54 | docs: plan intelligent testing CLI + external Step with Git, YAML and UAT | `docs/testing-intelligent-external-plugin-20260921` | Rubentxu | **SUPERSEDE** | Content already in active docs (AGENTS.md, EXECUTION_PLAN.md, etc.) |
| 55 | #55 | docs(agents): shorter test loop for small changes, full gates at integration/rel | `docs/agents-short-loop-testing-20260921` | Rubentxu | **SUPERSEDE** | Content already in active docs (AGENTS.md, EXECUTION_PLAN.md, etc.) |
| 56 | #56 | docs(roadmap): integrar release multicanal con dogfooding tras RP-5 | `docs/rp6-multichannel-release-dogfood` | Rubentxu | **SUPERSEDE** | Content already in active docs (AGENTS.md, EXECUTION_PLAN.md, etc.) |
| 58 | #58 | chore(ci-deps): bump actions/checkout from 4.4.0 to 7.0.1 | `dependabot/github_actions/actions/checkout-7.0.1` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 59 | #59 | chore(ci-deps): bump actions/upload-artifact from 4.6.2 to 7.0.1 | `dependabot/github_actions/actions/upload-artifact-7.0.1` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 60 | #60 | chore(ci-deps): bump actions/setup-java from 4.9.1 to 6.0.1 | `dependabot/github_actions/actions/setup-java-6.0.1` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 61 | #61 | chore(ci-deps): bump actions/cache from 4.3.0 to 6.1.0 | `dependabot/github_actions/actions/cache-6.1.0` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 62 | #62 | chore(deps): bump the kotlin-toolchain group in /v2 with 7 updates | `dependabot/gradle/v2/kotlin-toolchain-6e626356c5` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 63 | #63 | chore(deps): bump the test-dependencies group in /v2 with 2 updates | `dependabot/gradle/v2/test-dependencies-3d56f79c2d` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 64 | #64 | chore(deps): bump com.google.devtools.ksp from 2.3.11 to 2.3.12 in /v2 | `dependabot/gradle/v2/com.google.devtools.ksp-2.3.12` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 65 | #65 | chore(deps): bump org.yaml:snakeyaml from 2.3 to 2.7 in /v2 | `dependabot/gradle/v2/org.yaml-snakeyaml-2.7` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 66 | #66 | chore(deps): bump protobuf from 3.25.5 to 4.36.2 in /v2 | `dependabot/gradle/v2/protobuf-4.36.2` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 67 | #67 | chore(deps): bump bouncycastle from 1.80.2 to 1.86 in /v2 | `dependabot/gradle/v2/bouncycastle-1.86` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 68 | #68 | chore(deps): bump org.springframework:spring-core from 6.2.4 to 7.0.9 in /v2 | `dependabot/gradle/v2/org.springframework-spring-core-7.0.9` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 69 | #69 | chore(deps): bump org.bouncycastle:bcpkix-jdk18on from 1.80 to 1.86 in /v2 | `dependabot/gradle/v2/org.bouncycastle-bcpkix-jdk18on-1.86` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 70 | #70 | chore(deps): bump com.google.protobuf from 0.9.4 to 0.10.0 in /v2 | `dependabot/gradle/v2/com.google.protobuf-0.10.0` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 71 | #71 | chore(deps): bump org.xerial:sqlite-jdbc from 3.46.1.3 to 3.53.4.0 in /v2 | `dependabot/gradle/v2/org.xerial-sqlite-jdbc-3.53.4.0` | app/dependabot | **DEPENDENCY** | Batched into PR-005 (Dependabot reconciliation) |
| 90 | #90 | fix(workspace): split authorizedWorkspaceRoot and effective cwd (WU-RP-053) | `wu/rp-053-promote-operator-wip` | Rubentxu | **SUPERSEDE** | WU-RP-053 work landed in main via WU-RP-053R (commits 567196c9+) |
| 91 | #91 | perf(wu-rp-043): integration clean (rebased on main 0a62cb82) | `wu/rp-043-rebase-on-0a62cb82` | Rubentxu | **SUPERSEDE** | Rebase work re-included in later main merge |
| 92 | #92 | docs(uat): WU-RP-053 first vertical cut closure receipt | `wu/rp-053-first-cut-closure-receipt` | Rubentxu | **SUPERSEDE** | WU-RP-053 work landed in main via WU-RP-053R (commits 567196c9+) |
| 93 | #93 | ci(actions): drop pull_request trigger on LPR-0 CI | `wu/disable-actions-pr-trigger-rp-harness` | Rubentxu | **KEEP** | Operative CI config: drop pull_request trigger on LPR-0 CI |
| 94 | #94 | docs(agents): operative meridian M1..M8 for release candidates | `docs/agents-rc-meridian` | Rubentxu | **SUPERSEDE** | Content already in active docs (AGENTS.md, EXECUTION_PLAN.md, etc.) |
| 95 | #95 | fix(workspace): route deleteDir through effective cwd | `wu/rp-053-cut4-deletedir-cwd` | Rubentxu | **SUPERSEDE** | WU-RP-053 work landed in main via WU-RP-053R (commits 567196c9+) |
| 96 | #96 | fix(workspace): scope stash and unstash to effective cwd | `wu/rp-053-cut5-stash-cwd` | Rubentxu | **SUPERSEDE** | WU-RP-053 work landed in main via WU-RP-053R (commits 567196c9+) |
| 97 | #97 | test+docs(workspace): WU-RP-053 coherence contract characterization + HAR-007 ga | `wu/rp-053-coherence-characterization` | Rubentxu | **SUPERSEDE** | WU-RP-053 work landed in main via WU-RP-053R (commits 567196c9+) |

---

## Acceptance Criteria

- [x] C1: All 25 OPEN PRs enumerated (machine-verifiable via `gh pr list --state open`).
- [x] C2: Each PR classified into one of KEEP/STACK/SUPERSEDE/CLOSE/DEPENDENCY.
- [x] C3: Rationale recorded for each classification.
- [x] C4: Receipt generated with SHA-stamped provenance.

---

## Decisions and Discoveries

- **Decision:** Dependabot PRs (#58-#71) batched under DEPENDENCY (PR-005 will handle).
- **Decision:** WU-RP-053 PRs (#90, #92, #95, #96, #97, #91) all SUPERSEDE — work landed via WU-RP-053R closure.
- **Decision:** PR #93 (CI trigger drop) KEEP — operative CI config aligned with PR-006 (admission check).
- **Decision:** Docs-only PRs (#54, #55, #56, #94) SUPERSEDE — content already in active documents.
- **Discovery:** Several PRs have stale CI runs (CANCELLED conclusion) because LPR-0 CI was reconfigured post-creation. Closing them does not lose work.

---

## Follow-up Actions

- **Operator decision required:** close SUPERSEDE/CLOSE PRs individually via `gh pr close <N> --delete-branch --comment '...'` after reviewing the rationale above.
- **PR-005:** Group Dependabot updates (DEPENDENCY bucket), run affected tests + SCA, integrate as a single batch.
- **PR-006:** Reuse KEEP bucket to design the admission check.
