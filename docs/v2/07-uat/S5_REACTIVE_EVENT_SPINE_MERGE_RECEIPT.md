# S5 Reactive Event Spine — MERGE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine`
**Branch merged:** `s5-observation-vertical`
**Pre-merge `main`:** `569a088cc76f1a826c619577eefa1475404c3fb4`
**Trunk SHA post-merge:** `f769c134dc3fa34da7533755850a6871dbf2b9b5`
**Merge mode:** fast-forward (`--ff-only`), no merge commit
**Date:** 2026-10-06
**Author:** agent:main:autonomo, per the standing roadmap directive

---

## 1. Why this receipt exists, and why it is written after the merge

`release.complete` requires a `merge-receipt` that names the trunk SHA. A receipt
naming that SHA cannot be committed *before* the merge, because the SHA it would
name does not exist yet — committing it first means either predicting it or writing
a fiction. This receipt is therefore written **on `main`, after the fast-forward**,
where `f769c134` is an observed fact rather than an expectation.

That ordering is also why this file is not part of the merged range. It was
committed after `f769c134`, and it attests to that commit, not to itself.

## 2. Integration attestation

| Verification | Result |
|---|---|
| `origin/main` before merge | `569a088cc76f1a826c619577eefa1475404c3fb4` |
| `origin/main` after merge | `f769c134dc3fa34da7533755850a6871dbf2b9b5` |
| `git merge --ff-only` exit code | 0 |
| `git push origin main` exit code | 0 |
| Local `main` == remote `origin/main` after push | **YES** |
| Merge commit created | none — linear history preserved |
| Commits added to trunk | 16 |
| Working tree clean post-merge | YES (excluding the unrelated untracked `docs/pipelinek-v1-to-v2-evolution-bundle*`) |
| Branch behind `main` after merge | 0 |

The push line is `569a088c..f769c134  main -> main`.

## 3. What landed on trunk

| Category | Count |
|---|---:|
| Production source | 4 |
| Tests | 7 |
| Published API dump (real registered binary break) | 1 |
| Governance ledgers | 2 |
| Documentation | 7 |
| **Total files** | **21** |

Firewall files absent from the diff: `CanonicalDurableRunCoordinator.kt`,
`DslCompiledPipelineCompiler.kt`.

## 4. Evidence backing this merge

The merge was gated on the candidate that actually became trunk, not on the earlier
S5.4 receipt:

- **Gate:** `cd v2 && ./gradlew --no-daemon check --rerun-tasks` on `880a5287`.
- **Result:** `BUILD SUCCESSFUL in 33m 43s`, **329 tasks executed, 0 up-to-date**.
- **Tests:** 5078 · 0 failures · 0 errors · 140 skipped · 766 classes, counted
  bounded by the gate start instant (`2026-10-06T18:15:27Z`, epoch `1791310527`)
  over the complete `v2/**` tree, and only after the gate finished.
- **Log digest:** `5fef62fa91d88e137c6574e2020974ed8bf27778e2dc13e714242fccd0bd77c5`
- **Tree fingerprint** (sha256 over the sorted 21-file diff against `main`):
  `9f6962fb8a4863877de8fb795646018bba42f11546e48e042c2d489486269c0d`

`880a5287` is the direct parent of trunk, so the gated tree and the merged tree are
the same code. The single commit between them (`f769c134`) adds one markdown file
and touches no source, so it cannot invalidate the gate.

## 5. UAT basis

`S54ExternalVerticalRestartUatTest` (2 tests, green) forked
`build/install/pipelinek/bin/pipelinek` in two OS processes across a durable
cursor. `installDist` ran inside this gate (launcher mtime `18:16:36Z`, after the
`18:15:27Z` start), so the bytes under test belong to this SHA. Full table in the
release receipt §4.

## 6. What this merge does not claim

- It is **not** a version publication: no tag, no `distZip`, no SBOM, no GitHub
  release. The versioned release carrying this work is `0.48`, after S6.
- It does not turn `PRODUCT-GATE` green. That gate is `BLOCKED_EXTERNAL`; remote
  CI has not existed since `754ddda0`.
- It resolves none of debts D1..D6, all of which remain open with unchanged status.
  D2 (`CoreWaitUntilStep`) is scheduled for S7 specifically so it is not resolved
  during the S8 compatibility freeze.
- It does not certify S5.5 (deferred, ADR-0104) or S5.6 (no MCP surface, no agent
  consumer in this repository).

---

Reference implementation consulted: the branch's own release receipt §2..§5; no external harness.

Behaviour adopted: fast-forward integration preserving linear history, with the receipt attesting to an already-observed trunk SHA.

Intentional deviations: the receipt is committed after the merge rather than before it, which puts it outside the merged range. This is deliberate and is the only ordering in which it can name a real trunk SHA.

Security implications reviewed: no credential, network, persistence or process capability was widened by this merge.

Tests demonstrating the contract: the seven suites listed in release receipt §4, all green on `880a5287`, which is trunk's direct parent.
