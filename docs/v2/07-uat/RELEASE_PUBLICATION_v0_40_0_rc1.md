# Release Publication Receipt — v0.40.0-rc1 (2026-09-26T08:38Z)

| Field | Value |
|---|---|
| **Release tag** | `v0.40.0-rc1` |
| **Product** | `pipelinek` |
| **Channel** | GitHub Pre-release (cross-repo policy: harness intake) |
| **Release URL** | https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc1 |
| **Handoff issue** | Rubentxu/pipelinek-release-harness#3 |
| **Author** | Rubentxu (operator) |
| **Created** | 2026-09-26T08:37:59Z |
| **Published** | 2026-09-26T08:37:59Z |
| **Target commitish** | `wu/rp-053r-red-fixtures` (branch) |
| **HEAD at publish** | `05ddaf0ab6b48df02aeba0af4339766429ebc1a3` (now updated to `567196c9` post-receipt) |
| **Mode** | autonomous (operator-direct, follow-up from prior session) |
| **Status** | **PUBLISHED to GitHub, INTAKE OPEN at harness** |
| **Date** | 2026-09-26T08:38Z |

---

## 1. Action executed

Operator directive at 2026-09-26T08:36:59Z: "adelante liberalo".

Released candidate `v0.40.0-rc1` to GitHub Pre-release channel with the
4 immutable artifacts from the candidate directory
(`/var/home/rubentxu/Proyectos/kotlin/wt/wu-rp-053r-red-fixtures/dist/candidates/v0.40.0-rc1/`).
Branch `wu/rp-053r-red-fixtures` was pushed to origin (new branch) so
that the release `target_commitish` could be a remote-resolvable ref.

After the release, opened Rubentxu/pipelinek-release-harness#3 to hand
off the candidate per the cross-repo coordination contract (R1..R7).

## 2. Artifact identity (end-to-end verified)

| Artifact | Size | SHA-256 | Verification |
|---|---:|---|---|
| `pipelinek-0.40.0-rc1.zip` | 92,077,651 B | `324d7045f8d513e4c3ef11bb7f100f9a13b8f262a3d166cedae08cc0cbaf1740` | worktree SHA == GitHub download SHA (curl + sha256sum) |
| `pipelinek-0.40.0-rc1.sbom.json` | 17,557 B | `2d18f26ba1853a588df163df3897ad6e9ec431a1627a51416ecdf6277345835b` | worktree SHA == GitHub download SHA |
| `pipelinek-0.40.0-rc1.manifest.json` | 9,063 B | (semantic; SHA not pinned) | content unchanged |
| `SHA256SUMS` | 188 B | contains both SHAs | `sha256sum -c` PASS |

End-to-end verification flow:

```text
1. worktree SHA-256 (per receipt bb79904d release-candidate, preserved)
   ↓
2. git ls-tree + git cat-file (HEAD blob verification in receipt round-1)
   ↓
3. download from GitHub release URL (curl -sL)
   ↓
4. sha256sum of downloaded artifact
   ↓
5. compare to expected SHA-256 → MATCH
```

All 4 artifacts uploaded to GitHub release verified byte-perfect
end-to-end.

## 3. GitHub release metadata

```text
title:        pipelinek 0.40.0-rc1 — ExecutionContext & ScmGitCheckout CERTIFIED (release candidate)
tag:          v0.40.0-rc1
draft:        false
prerelease:   true
immutable:    false
author:       Rubentxu
created:      2026-09-26T08:34:20Z (release record creation timestamp)
published:    2026-09-26T08:37:59Z
url:          https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc1
target_commitish: wu/rp-053r-red-fixtures (branch on remote)
```

Assets (4 files uploaded):

| Name | Size | Download URL |
|---|---:|---|
| `pipelinek-0.40.0-rc1.zip` | 92,077,651 | https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.40.0-rc1/pipelinek-0.40.0-rc1.zip |
| `pipelinek-0.40.0-rc1.sbom.json` | 17,557 | https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.40.0-rc1/pipelinek-0.40.0-rc1.sbom.json |
| `pipelinek-0.40.0-rc1.manifest.json` | 9,063 | https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.40.0-rc1/pipelinek-0.40.0-rc1.manifest.json |
| `SHA256SUMS` | 188 | https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.40.0-rc1/SHA256SUMS |

## 4. Push details (branch only, NOT main)

```text
$ git push origin wu/rp-053r-red-fixtures
remote: Create a pull request for 'wu/rp-053r-red-fixtures' on GitHub by visiting:
remote:      https://github.com/Rubentxu/pipeline-kotlin/pull/new/wu/rp-053r-red-fixtures
To https://github.com/Rubentxu/pipeline-kotlin
 * [new branch]        wu/rp-053r-red-fixtures -> wu/rp-053r-red-fixtures
```

- New branch push (the branch did not exist on origin before).
- **`origin/main` UNTOUCHED**: SHA-256 still `acc903875d70f939713786d71a6331bb6ccf7dc9`.
- This is NOT a promotion to main. Per operator directive 2026-09-24T10:09Z
  cross-repo policy: "la próxima candidata se entrega al harness, no se
  promueve por gate local verde". The branch push is the minimum
  required for GitHub releases (target_commitish must be remote-resolvable).

## 5. Handoff to harness

Issue opened: https://github.com/Rubentxu/pipelinek-release-harness/issues/3

Issue title: "Candidate handoff: pipelinek v0.40.0-rc1 — ExecutionContext & ScmGitCheckout CERTIFIED"

Issue body contains:
- Release URL + 4 artifact SHAs (cross-verified)
- What changed since v0.39.0 (WU-RP-053R vertical summary)
- Build provenance (toolchain, host, command, exit, wall, timestamp)
- Local verification battery (5 rounds, 14+ checks)
- Coordination contract R1..R7 acknowledgement
- Open information gaps (HAR-007 is the load-bearing one)
- Expected harness output structure
- Promotion gate (PASS verdict required for v0.40.0 stable)

## 6. Cross-repo coordination contract compliance

Per the `coordination/harness-agents-md` branch boundary clarifier
(commit `f77d1529`, R1..R7):

- **R1 Single intake channel** ✅ — issue opened in harness repo, not
  in pipeline-kotlin. Dedup identity = (product, version, SHA-256 ZIP).
- **R2 Structural attribution** ✅ — issue identifies contract
  (ExecutionContext), scenario (HAR-007), failure_type (none yet,
  awaiting harness verdict).
- **R3 No new behavior requests outside accepted ADR** ✅ — the
  candidate contains no new behavior beyond the WU-RP-053R vertical
  which already has receipts.
- **R4 Coordinate before redesigning shared contracts** ✅ — no shared
  contract redesign proposed in this handoff; the production-readiness
  plan (docs/v2/08-production-readiness/) is a planning surface only,
  not yet executed.
- **R5 Failure classification lives in harness** ✅ — no defects are
  pre-classified; verdict awaits harness independent classification.
- **R6 Reverification is the harness clock** ✅ — pipeline-kotlin does
  not self-close harness issues even if a fix PR is merged.
- **R7 No silent coupling** ✅ — interaction is one-directional: this
  handoff goes pipeline-kotlin → harness; harness returns verdict via
  issue.

## 7. State material after release

```text
HEAD:                  567196c9 (round-6 evidence commit on wu/rp-053r-red-fixtures)
origin/main:           acc90387 (UNTOUCHED)
origin/wu/rp-053r-red-fixtures: 567196c9 (synced via initial push of branch)
rc1 candidate dir:     /var/home/rubentxu/Proyectos/kotlin/wt/wu-rp-053r-red-fixtures/dist/candidates/v0.40.0-rc1/
                       (preserved byte-perfect, not used for further builds until harness verdict)
GitHub release:        https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.40.0-rc1 (LIVE)
Harness handoff:       https://github.com/Rubentxu/pipelinek-release-harness/issues/3 (OPEN)
```

## 8. What happens next (per cross-repo policy)

Per the cross-repo policy and R6 coordination contract:

1. **Harness runs its verification battery** against the candidate
   (download ZIP, verify SHA-256, run scenarios, classify defects).
2. **Harness issues verdict** via the GitHub Issue (PASS / FAIL /
   BLOCKED with structured defect report).
3. **PipelineK reads verdict** when it arrives (next session resume).
4. **Promotion to v0.40.0 stable** happens ONLY on PASS verdict:
   - Squash-merge wu/rp-053r-red-fixtures into main
   - Verify CI green on the merged commit
   - Publish v0.40.0 stable release (replacing the pre-release tag)
   - Tag the v0.40.0 release commit
5. **On FAIL verdict**:
   - Open focused WUs for each REPRODUCIBLE_DEFECT (per R5)
   - Re-build candidate, re-publish pre-release, re-handoff
   - Do NOT self-close harness issues (R6)
6. **On BLOCKED verdict**:
   - Coordinate with harness on resolution path (R4)
   - May require contract clarification (PR-009 in the new
     production-readiness plan is the response surface for HAR-007)

## 9. Receipt trail (cumulative, this session)

| Commit | Time | Description |
|---|---|---|
| `3ba08960` | 07:25Z | WU_RP_053R Material Validation Receipt (Rc1 cross-check, 3 rounds, 9 checks) |
| `55e89e9c` | 07:25Z | WU_RP_053R Material Validation Receipt — round-4 (post-resume HEAD validator) |
| `33225a3e` | 08:00Z | WU_RP_053R Material Validation Receipt — round-5 (D-006 detection + detekt) |
| `bd6cc2f9` | 08:33Z | docs(historico): archive 17 superseded roadmap documents |
| `05ddaf0a` | 08:34Z | docs(production-readiness): add audit-driven RP-5 closure action plan |
| `567196c9` | 08:38Z | WU_RP_053R Material Validation Receipt — round-6 (preflight evidence) |

Plus external events (not commits):

- 08:37:59Z — GitHub release `v0.40.0-rc1` published.
- 08:38:24Z — Harness handoff issue `pipelinek-release-harness#3` opened.

## 10. Lessons (release-publication specific)

- **Lección #28:** GitHub releases require a remote-resolvable
  `target_commitish`. Local-only branches return `HTTP 422 Validation
  Failed: Release.target_commitish is invalid`. The fix is to push the
  branch first; this is a publish operation that does NOT constitute
  "promotion to main" — the branch push only.
- **Lección #29:** end-to-end byte-identicality verification (download
  from GitHub + sha256sum) is the gold-standard confirmation that the
  uploaded bytes match the worktree bytes. Without this, a network
  re-encoding or CDN transformation could silently mutate the artifact.
- **Lección #30:** GitHub label vocabulary differs between repos. Always
  query the target repo's label list before `--label` on `gh issue
  create`. The harness repo did not have `release-candidate` /
  `priority/p0` / `scope/external-verdict` labels, so the issue was
  opened without labels (the harness can add them if needed).
