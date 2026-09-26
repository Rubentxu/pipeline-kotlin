# RC2 Main Integration Receipt: v0.40.0-rc2

**Status:** `INTEGRATED_ON_MAIN`  
**Candidate tag:** `v0.40.0-rc2`  
**Candidate tag target:** `165b6f9ad2242ac5336e660e7516f88f1af90d96`  
**Integration commit:** `6c518b53aca2e0c9c2fd99c7c59a9e15d8acffca`  
**Remote:** `origin/main`  
**Date:** `2026-09-26`

## Decision applied

ADR-0099 makes `main` the source of truth for release candidates and releases.
The RC2 branch history was therefore integrated by fast-forward. The candidate
tag remains immutable and now resolves to a commit reachable from `main`.

The external release harness remains responsible for real-project
certification and stable-promotion eligibility. Its pending verdict does not
remove the candidate history from `main`.

## Integration evidence

```text
argv: git push origin HEAD:refs/heads/main
exit_code: 0
observed: acc90387..6c518b53  HEAD -> main
output_digest: 1c94caf1ad692dd7df45da582c33044af9ff0947d7e5dbb2da507c86e568995e
```

Post-push remote verification:

```text
argv: git fetch origin main;
      git rev-parse HEAD;
      git ls-remote origin refs/heads/main;
      git merge-base --is-ancestor 165b6f9ad2242ac5336e660e7516f88f1af90d96 origin/main
exit_code: 0
output_digest: 9f39bff6dfb37a08e7b07b38b8f4dd8a27a6126b086155a95e6a6f3883726a0e
observed: origin/main contains 6c518b53 and contains the immutable RC2 tag target
```

The complete RC2 commit history is preserved. No squash, rebase of published
history, force push, or tag mutation was used.

## Release state

| Gate | State | Evidence |
|---|---|---|
| Candidate version identity | PASS | `v2/build.gradle.kts`, tag and distribution identify `0.40.0-rc2` |
| Candidate artifact integrity | PASS | ZIP, SBOM, manifest and checksum receipt |
| GitHub prerelease publication | PASS | `RELEASE_PUBLICATION_v0_40_0_rc2.md` |
| Candidate history integrated on `main` | PASS | fast-forward from `acc90387` to `6c518b53` |
| External harness certification | NOT_RUN | owned by `pipelinek-release-harness` |
| RP-5 external product gate | NOT_RUN | owned by the external certification workflow |
| Stable promotion | BLOCKED_PENDING_HARNESS | no external verdict exists in this repository |

## Closure record

- **Reference implementation consulted:** ADR-0091, ADR-0099 and the RC2 publication receipt.
- **Behaviour adopted:** preserve all candidate commits on `main` before considering RC publication complete.
- **Intentional deviations:** stable promotion remains pending external certification; this receipt does not invent a harness verdict.
- **Security implications reviewed:** immutable tag and artifact digests remain unchanged; integration used a fast-forward push only.
- **Tests demonstrating the contract:** RC2 build, installed smoke, artifact checksum verification, admission gate and post-push remote ancestry verification.
