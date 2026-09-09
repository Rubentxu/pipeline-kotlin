# LB-02 G0 Baseline Evidence (2026-09-09)

**Status**: G0 PASS for LB-02. `0 regressions` introduced by the LB-02 doc-only slice
(`d7d9606f` Path A decision + `576c2a91` contract draft). HEAD at the time of the
canary is `576c2a91`.
**Scope**: only the doc-only changes between S3 cert (`d7eec872`) and the LB-02
contract draft commit `576c2a91`. No source/test/code edits. LB-02 base is the
S3-cert + AGENTS.md update commit `1eb06d0a` (i.e. just before LB-02 docs).

## LB-02 base worktree method

A fresh git worktree was created at `/tmp/base-lb02-1eb` pinned to
`1eb06d0a5428a2dc9dfd61b75085aa29570789c0`. The same Gradle invocation was
re-run against the base to demonstrate that the targeted sh-coupled failures
are pre-existing (introduced before the LB-02 slice).

## Targeted sh-coupled test set

Selected from the S2.5.7 evidence table (`docs/v2/07-uat/S2_5_7_GATE_EVIDENCE.md`)
the rows where the failure signature exercises `core.sh` directly:

| # | Test | Reason selected | Classification |
|---|------|-----------------|----------------|
| 1 | `UatLocal005CheckoutGitTest.SC-007` | real sh `git rev-parse` poll | pre-existing host flake (sandbox + bind-mount) |
| 2 | `UatLocal007SandboxProfileTest.SB-S-008` | parallel branches have isolated cwds (process launch path) | pre-existing (sandbox profile gap) |
| 3 | `UatLocal009TopStepsTest.CR-U9-008` | archiveArtifacts shape (sh hooks) | pre-existing (archiveArtifacts gap) |
| 4 | `UatLocal009TopStepsTest.CR-U9-011` | archiveArtifacts AntStyleGlob | pre-existing |
| 5 | `UatLocal009TopStepsTest.CR-U9-012` | cross-step writeFile + archiveArtifacts | pre-existing |

Five rows covers the spine-shape of LB-02 (fresh sh launch, parallel sh, sandbox
under sh, multi-Step sh composition). Wider tests such as the scripting-kotlin24
or `UatDsl005` rows are **out of scope for the LB-02 baseline**: those exercise
the DSL compiler / rewriter, not the durable sh execution path; they belong to a
later burn-down (e.g. when `core.archiveArtifacts`, `core.file.writeFile`, or
`core.checkoutGit` are migrated, not now).

## Negative evidence — fresh canary at HEAD `576c2a91`

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
    --tests 'dev.rubentxu.pipeline.v2.application.UatLocal005CheckoutGitTest.SC-007*' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatLocal007SandboxProfileTest.SB-S-008*' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.CR-U9-008*' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.CR-U9-011*' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.CR-U9-012*' \
    --rerun-tasks
```

| XML/Log | SHA-256 |
|---------|---------|
| `TEST-dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.xml` | `d213ad4ffc1394c65c5fb4f6c09956efc9122d49229d1663035f22ce9933bc10` |
| `TEST-dev.rubentxu.pipeline.v2.application.UatLocal007SandboxProfileTest.xml` | `c6a11e47dda5628d03e77af62c8e014e3bf51f276405e9a8a9e22287191fc956` |
| `TEST-dev.rubentxu.pipeline.v2.application.UatLocal005CheckoutGitTest.xml` | `aded79f5bde0a6503cee3ac6afea9031f807ec8107c16123c9f0cfb3204f289f` |
| full log `/tmp/lb02-g0-sh-tail-relog.log` | `40fa8b01c08fc588d7ceac0befacc1f316d43d6bbafcc085363d374e1067bda7` |

Run: `BUILD FAILED in 42s`, 5/5 failed, byte-identical assertion line numbers
(`UatLocal009TopStepsTest.kt:335`, `:419`, `:448`).

## Negative evidence — base worktree `/tmp/base-lb02-1eb` at `1eb06d0a`

Same Gradle invocation, re-run:

| XML/Log | SHA-256 |
|---------|---------|
| `TEST-dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.xml` | `f46be8e02498940d1b9dff6dc06f1ae95b474d0c0715b7c5c258d14679e6de1a` |
| `TEST-dev.rubentxu.pipeline.v2.application.UatLocal005CheckoutGitTest.xml` | `016eb157a2df76ece18c65eb910362e682254d37283e67ff4431426c743a4d1a` |
| full log `/tmp/base-lb02-1eb-sh.log` | `f4e56b6f864831e8cf74d0546e190ad5511405a5ab7804a49d7942091407fe67` |

Run: `BUILD FAILED in 42s`, 5/5 failed, byte-identical assertion line numbers.

**Conclusion**: failures are pre-existing on `1eb06d0a`. They survive an exact
worktree-method reproduction, so the LB-02 doc-only slice did NOT introduce them.

## Positive evidence — fresh canary at HEAD `576c2a91`

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
    --tests 'dev.rubentxu.pipeline.v2.application.EchoStepContractSuiteTest' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatDurable002DivergenceFailsClosedTest' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatDurable003ScriptBlockReplayTest'
```

| XML | SHA-256 | tests/failures/errors |
|-----|---------|-----------------------|
| `TEST-dev.rubentxu.pipeline.v2.application.EchoStepContractSuiteTest.xml` | `650f05759f82a1545ffe75c0e77905b0398e30920dad69fa7ed56fa883845ae9` | 17 / 0 / 0 |
| `TEST-dev.rubentxu.pipeline.v2.application.UatDurable002DivergenceFailsClosedTest.xml` | `cf9237deeac2d5b7874999d0046c1d1340fbd6734f94c4d97cab49644587debe` | 2 / 0 / 0 |
| `TEST-dev.rubentxu.pipeline.v2.application.UatDurable003ScriptBlockReplayTest.xml` | `5aabea218f1a14ea0ac6a3d9643fe9ad41d608faa475c93ec57231f744b7cb27` | 2 / 0 / 0 |

Run: `BUILD SUCCESSFUL in 3s`. The S3 contract suite (echo reference, 17 rows) is
unchanged; the canonical-path sh durable tests `UatDurable002` and `UatDurable003`
both pass — proving the durable sh execution machinery is intact at HEAD.

## Verdict

```text
LB-02 G0 = DONE ✅
regressions introduced by LB-02 doc-only slice = 0
canonical sh execution path intact at HEAD
5 pre-existing sh-coupled failures captured with SHA-256 for future burn-down
```

LB-02 is now cleared to start G1 (registry seam proof: `CoreShellStep` behind
the registry while legacy decode/dispatch remains intact).
