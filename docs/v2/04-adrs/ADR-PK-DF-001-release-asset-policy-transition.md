---
type: adr
id: ADR-PK-DF-001
title: "Una autoridad sobre el conjunto de assets público; conciliación del publisher actual con la política §8"
status: draft
date: 2026-10-16
deciders: "product owner — pendiente de aceptación"
supersedes: null
superseded_by: null
related:
  - AGENTS.md §8
  - AGENTS.md §11
  - AGENTS.md §13
  - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
  - docs/v2/05-roadmap/P2_MAVEN_PUBLICATION.md
  - docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md
  - docs/v2/04-adrs/ADR-PK-QV-004-release-after-ephemeral-cleanup.md
  - docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md
  - PR #140
  - PR #142
---

# ADR-PK-DF-001 — Una autoridad sobre el conjunto de assets público

## Status

**DRAFT.** This ADR mirrors the proposal at
`docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md`
so it lives at the tracked ADR location and can be referenced by
existing ADRs (`ADR-0105`, `ADR-PK-QV-004`). The status is `draft`,
not `accepted`, per the proposal's own "Esta ADR es una propuesta,
no un mandato de cambiar automáticamente la regla aceptada §8".

The **conciliation** the H2 TODO 1 mandate asked for is in the
section "Conciliación del publisher con §8" below: the H2 work
on `origin/main` (`efd569fe`, `53ab91c9`, `5e4cea31`) implements
Option A (the "compatible-today" option). The **decision** between
Option A and Option B is the user's; this draft records the
conciliation and the conditions for Option B without prejudging
the decision.

## Context (from the proposal)

In `main@5de40c45` the publisher and AGENTS.md §8 disagreed:

- **AGENTS.md §8**: five assets (ZIP runtime, SBOM, SDK ZIP, BOM JSON, SHA256SUMS).
- **Publisher script** (legacy): five different assets (ZIP, SBOM, HANDOFF.md, git-head.txt, SHA256SUMS).

PR #142 proposed splitting the SDK into a separate Maven coordinate.
Both directions are valid as **distribution models**, but they
cannot govern simultaneously without an updated decision/contract.

## Options

- **A — compatible today**: keep exactly five assets per §8. The
  SDK ZIP and BOM JSON remain the contract surface for the SDK
  channel; the local `v2/build/sdk-repo` is a fixture, not a
  remote destination; `SDK_PUBLISHED=BLOCKED_EXTERNAL` is the
  honest verdict until a remote destination is authorized.
- **B — preferred after acceptance**: split APP (runtime ZIP +
  SBOM + SHA256SUMS) from SDK (5 JAR+POM+.module coordinates +
  BOM from a remote Maven destination). The SDK ZIP and BOM JSON
  are removed from the APP release only after the consumer
  dependencies (installer, SDKMAN, certifier, plugin authors) are
  verified to not require them.
- **C — discarded**: opportunistic publication of 3 or 5 assets
  depending on which task ran. Permits incomplete releases with
  deceptive hash validation.

## Decision (proposed)

Until the user accepts Option B, **Option A is the executable
law**: five assets per AGENTS.md §8; the publisher outputs the
five; the manifest records the five; the `SHA256SUMS` covers
the four distributable artifacts; `SDK_PUBLISHED=BLOCKED_EXTERNAL`
until a remote Maven destination is configured.

## Conciliación del publisher con §8 — H2 work on `origin/main`

Three commits implement Option A in the build and the evidence
chain:

| Commit | H2 TODO | What was reconciled |
|---|---|---|
| `efd569fe` (2026-10-16) | H2 TODO 3 | `:pipeline-sdk-bom` now pins 5 contract coordinates in its `api(...)` constraints. The published POM `pipeline-sdk-bom-0.62.0.pom` lists 5 `<dependencyManagement>` entries; the BOM is consistent with §8's five-asset contract. |
| `53ab91c9` (2026-10-16) | H2 TODO 4 | `SDK_PUBLISHED=BLOCKED_EXTERNAL` recorded at `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md`. Reason: no remote Maven URL, no credentials, no GPG. Local `v2/build/sdk-repo` is a fixture, not a destination. |
| `5e4cea31` (2026-10-16) | H2 TODO 5 | Two ephemeral UATs passed end-to-end against the local fixture: AAT (asset set composition, 5 manifest entries × on-disk files × byte SHA) and UAT (Maven consumer resolution, `pipeline-domain` and `pipeline-events` resolved at 0.62.0). Receipts persisted in the repo, ephemeral scripts deleted per the "Borrar tras verificar" rule. |

The three commits together prove the publisher outputs the
five §8 assets, the manifest is the single source of truth for
the asset set, and the SDK contract chain (BOM → 5 contracts) is
end-to-end correct on the build host. The next step is
publication of a new RC, gated by the user's decision between
Option A (continue) and Option B (split APP/SDK channels).

## Verification (re-runnable against `origin/main` `817a0e9`)

- BOM 5-constraint assertion: `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/pipeline-sdk-bom/0.62.0/pipeline-sdk-bom-0.62.0.pom`
  lists 5 `<dependencyManagement>` entries.
- Architecture fitness: `cd v2 && ./gradlew :pipeline-architecture-tests:test --tests PublishedContractBoundaryFitnessTest`
  → 6/0/0 (re-ran with `--rerun-tasks` on `817a0e9`, fresh XML).
- H2 TODO 5 ephemeral UAT receipts: `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md`.

## Conditions for Option B acceptance (from the proposal, restated for traceability)

1. **Decision recorded by the user.** This ADR changes from
   `draft` to `accepted` with date and exact scope.
2. **Real consumer installs the APP ZIP from a download** and
   verifies `version --json`, SHA, archive root, and `SHA256SUMS`.
3. **Remote Maven resolves the six coordinates** (5 libraries +
   BOM) with `.module` metadata, without `sdk-repo` or
   `includeBuild`, from a clean checkout.
4. **Certifier, installer, SDKMAN, and plugin authors confirm
   no dependency** on the SDK ZIP or BOM JSON in the APP release.
5. **Remote proof of missing/extra/duplicates** in the asset
   set, before the release is declared a success.
6. **Atomic update** of `AGENTS.md` §8 and the relevant specs
   to avoid contradictory norms.

Until all six hold, Option A remains the law.

## Consequences

- **Positive**: distinct APP and SDK products; faster releases;
  no residuals in the asset set; one authority on integrity and
  completeness. The H2 work proves the Option A chain is correct
  end-to-end on the build host, so the "compatible-today" path
  is not a stopgap — it is the production contract for the
  current ProductVersion.
- **Cost**: Option B requires (a) a remote Maven destination
  authorized by the user, (b) credentials, (c) GPG signing
  policy, (d) consumer migration off the SDK ZIP and BOM JSON
  inside the APP release. PR #142 must not be merged while
  AGENTS.md §8 still names the SDK ZIP and BOM JSON as APP
  assets.
- **Honest**: the BLOCKED_EXTERNAL verdict on the SDK is not a
  defect; it is the correct verdict given the missing remote
  destination. The H2 TODO 4 receipt records this honestly.

## References

- The proposal: `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md`
- AGENTS.md §8 (5-asset contract), §11 (release candidates), §13 (fast lane).
- ADR-0105 (PRODUCT-GATE=BLOCKED_EXTERNAL, the certifier side).
- ADR-PK-QV-004 (release-after-ephemeral-cleanup).
- `docs/v2/05-roadmap/P2_MAVEN_PUBLICATION.md` (the SDK publication plan).
- `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md` (H2 TODO 4 receipt).
- `docs/v2/06-evidence/h2-todo-5-receipts-2026-10-16.md` (H2 TODO 5 ephemeral UAT receipts).
- Commits: `efd569fe`, `53ab91c9`, `5e4cea31` on `origin/main`.
- `docs/v2/06-evidence/changelog-since-rc5-2026-10-16.md` (H0..H4 partial closure scope).
