# Changelog since `v0.62.0-rc5` — 2026-10-16

## Scope

`v0.62.0-rc5` was the last published release candidate (tag at
`24fdbdb8`; per `ROADMAP.md`, the same commit was `a66fe88c`
when packaged). Since then, `origin/main` has accumulated 14
commits:

```text
002ccc9c test(h4): H4 batch 2 — archive 7 TRACE_CALLERS_BEFORE_REMOVAL scripts (PK-RET-0014..0019, 0022)
b3fc8f74 test(h4): H4 batch 1 — archive 12 ARCHIVE_IF_UNWIRED entries (PK-RET-0001..0005, 0007..0013)
ae2cf555 docs(h4): H4 classification of PR #140 inventory — 0 retired, 169/169 MIGRATION_PENDING
b377442f feat(console): H3 — usage line advertises every per-frame option the parser accepts
5e4cea31 docs(sdk): H2 TODO 5 receipts — both ephemeral UAT families PASS (2026-10-16)
53ab91c9 docs(sdk): H2 TODO 4 — register SDK_PUBLISHED=BLOCKED_EXTERNAL (2026-10-16)
efd569fe feat(sdk): H2 TODO 3 — BOM pin pipeline-runtime-api as 5th contract
f66abfe2 feat(lanes): H1 TODO 4 — planner governance pinned to the catalogue
82adaebd feat(application): H1 TODO 3 — installed lane actually runs tagged tests
f9b6c292 feat(plan): H1 TODO 2 — sidecar evidence (FQCN+Gradle path+SHA+HF)
0e7a377c feat(plan): H1 TODO 1 — ChangedInput ADT classifying by semantic effect
3020f2e9 feat(cycle): H0 TODO 4 — JsonOracleLedger + CLI flags + UAT fixture
09ccd020 feat(cycle): H0 TODO 2 — typed PromotionEvidence carrier (P0-D close)
28f1b9b1 feat(cycle): ExitVerdict ADT — typed projection from CycleState to exit code
```

`rootProject.version` is still `0.62.0` (the `-rc5` lives in the
tag and `CandidateHandoff.candidateSequence`, not in
`ProductVersion`, per AGENTS.md §11 TARGET-VERSION CANDIDATE LAW).
The next RC is the user's call: bump to `0.62.0` (same ProductVersion,
higher candidate sequence) or to `0.63.0` (new ProductVersion).
This document records the candidate scope regardless.

## What changed in the codebase (consumer-visible)

| Commit | Hito | What changed |
|---|---|---|
| `efd569fe` | H2 TODO 3 | `:pipeline-sdk-bom` now pins 5 contract coordinates in its `api(...)` constraints (was 4). The published POM `pipeline-sdk-bom-0.62.0.pom` lists 5 `<dependencyManagement>` entries including the new `pipeline-runtime-api` constraint. |
| `53ab91c9` | H2 TODO 4 | `SDK_PUBLISHED=BLOCKED_EXTERNAL` recorded in `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md`. Reason: no remote Maven destination authorized; only the local `v2/build/sdk-repo` fixture. |
| `5e4cea31` | H2 TODO 5 | Two ephemeral UATs passed end-to-end against the local fixture. Receipts (AAT asset set composition, UAT Maven consumer resolution) persisted in `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md`; the ephemeral scripts were deleted from `/tmp/pk-h2-uat-{001,002}` per the "Borrar tras verificar" rule. |
| `b377442f` | H3 partial | `pipelinek console` (no args) now prints a multi-line usage that lists `--frames`, `--frame-limit`, `--after-ordinal`, `--follow`, `--stage`, `--step`, `--kind` in addition to the byte-budgeted options. The parser already accepted these flags; the help text now reflects reality. |
| `b3fc8f7` | H4 batch 1 | 12 ARCHIVE_IF_UNWIRED V1 test files removed (PK-RET-0001..0005, 0007..0013). Trace: 0 active V2 callers; V1 directories (`core/`, `pipeline-backend/`, `pipeline-cli/`) are not in the V2 composite build. |
| `002ccc9` | H4 batch 2 | 7 TRACE_CALLERS_BEFORE_REMOVAL scripts removed (PK-RET-0014..0019, 0022). Trace: 0 active V2 callers (only doc references; SDKMAN scripts retired because AGENTS.md §8 retired the `.github/workflows/` SDKMAN workflow on 2026-09-30). |

The H0/H1 commits (`28f1b9b1` … `f66abfe2`) shipped earlier in
the session; they are part of the H0/H1 closure that preceded
`v0.62.0-rc5` and are listed for completeness.

## What did NOT change in the codebase

- `rootProject.version` stays at `0.62.0` (no version bump this
  document; the bump is a separate decision).
- No CLI binary, no SDK artifact, no SBOM, no SHA256SUMS file
  was touched.
- No release was published. Per AGENTS.md §13 ("Fast lane, sin
  bloqueo por certificación"), the RC can be published with
  `SDK_PUBLISHED=BLOCKED_EXTERNAL`; this document does not
  publish one.
- No ADR was authored or accepted. ADR-PK-DF-001 (the H2 TODO 1
  reconciliation) remains a proposal, not an accepted ADR.
- No legacy UAT was retired beyond the 19 in H4 batches 1+2. The
  remaining 150 entries in the PR #140 inventory stay
  `MIGRATION_PENDING` until the `pipelinek-release-harness`
  produces the 5-axis verdict per ADR-PK-QV-003.
- No `core/`, `pipeline-backend/`, or `pipeline-cli/` directory
  was deleted. LFC0-004 ("V1 source remains in the repository
  as legacy/history, but is not part of the active build")
  keeps them; the 12 deletions in H4 batch 1 only removed the
  test source files inside them, not the modules themselves.

## Verification (re-runnable, against `origin/main`)

The receipt documents captured at each commit are the canonical
proof. Quick re-runs against `origin/main` `002ccc9`:

- BOM 5-constraint assertion: `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/pipeline-sdk-bom/0.62.0/pipeline-sdk-bom-0.62.0.pom`
  lists 5 `<dependencyManagement>` entries.
- Architecture fitness: `cd v2 && ./gradlew :pipeline-architecture-tests:test --tests PublishedContractBoundaryFitnessTest`
  → 6/0/0.
- H3 usage line: `cd v2 && ./gradlew :pipeline-application:test --tests MainConsoleCliPerFrameTest --tests CliParserUnificationTest`
  → 15/0/0 + 20/0/0.
- H4 batch 1 receipts: `docs/v2/06-evidence/h4-archive-batch-1-2026-10-16.md` (12 files, 4 checks each).
- H4 batch 2 receipts: `docs/v2/06-evidence/h4-archive-batch-2-2026-10-16.md` (7 files, 6 checks each).

## What the next session does to publish a new RC

1. **Decide the version**: bump `rootProject.version` to `0.62.0`
   for a `v0.62.0-rc6` (same ProductVersion, higher candidate
   sequence), or to `0.63.0` for a `v0.63.0-rc1`. The bump is
   `v2/build.gradle.kts:105 version = "0.62.0"`.
2. **Build the distribution**: `./gradlew :pipeline-release:releaseGate -Pcandidate.tag=vX.Y.Z-rcN -Pcandidate.sequence=N`
   produces the 5-asset ZIP, SBOM, SDK ZIP, BOM JSON, and
   SHA256SUMS at `v2/pipeline-application/build/distributions/`.
3. **Tag and publish**: `git tag -a vX.Y.Z-rcN` and `gh release create vX.Y.Z-rcN --prerelease --title "..." --notes-file docs/v2/06-evidence/0-62-rc{N}/HANDOFF.md <5 assets>`.
4. **Hand to the certifier**: the candidate bytes go to
   `pipelinek-release-harness` via its `pipelinek-*-handoff`
   schema (`docs/v2/06-evidence/sdk-publication-status-2026-10-16.md`
   cites the contract). The harness returns
   `CERTIFIED` (promotable) or `BLOCKED_EXTERNAL` (with a
   defect list). Either is honest; the next step depends on
   the verdict.

## Cross-references

- `AGENTS.md` §8 (DISTRIBUTION-FIRST RELEASE POLICY), §11 (Release candidates), §13 (Fast lane).
- `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md` line 173–200 (H2 BLOQUE 3).
- `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md` line 46 (H2 mandate) and line 71 (H4 mandate).
- `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md` (H2 TODO 4 receipt).
- `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md` (H2 TODO 5 ephemeral UAT receipts).
- `docs/v2/06-evidence/h4-classification-2026-10-16.md` and `h4-archive-batch-{1,2}-2026-10-16.md` (H4 receipts).
