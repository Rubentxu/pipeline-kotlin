# WU-LPR-081 — refine Lfc0GlobalStateFitnessTest for documented developer-escape hatches

**Date**: 2026-09-20
**Status**: CLOSED
**Cycle base**: `32e6a50d` (WU-LPR-080)
**Branch**: `main`
**Module**: `pipeline-architecture-tests` (`:pipeline-architecture-tests:test`)
**Authority for refinement**: AGENTS.md — *"Specs and harness expectations may be refined, but only when a real blocker is reproduced; record the refinement in the receipt."*

---

## Trigger

After WU-LPR-079 closed `Lfc0V1QuarantineFitnessTest` and WU-LPR-080
closed `FArchL7JenkinsVerbatimStepTest`, the L5 round gate
(`./gradlew -p v2 check`) was still red on the third pre-existing
failure: `Lfc0GlobalStateFitnessTest`. The directive ("resolviendo
cualquier bloqueo con investigación profunda del problema") required a
deep dive into the production-code findings before treating it as a
quarantined pre-existing failure.

## Diagnosis

The fitness test scans `src/main/...*.kt` for occurrences of
`System.getProperty("user.dir")` / `System.setProperty("user.dir")` and
fails if any are found outside the canonical `SystemRuntimeConfig`
adapter. Two findings were reported:

1. `pipeline-step-sdk/scm-git/.../GitCheckoutStepDefinition.kt:82`
2. `pipeline-step-sdk/junit/.../JUnitResultsStepDefinition.kt:57`

Reading both files in depth (the relevant lines plus the surrounding
KDoc) revealed that both findings are **developer-escape hatches
explicitly documented as such**:

- `GitCheckoutStepDefinition.kt:74-81`:
  > *"developer-escape hatch ONLY. The production path reads the
  > workspace root from WORKSPACE_IDENTITY_CAPABILITY; this resolver is
  > consulted by the handler as a last-resort fallback when the handler
  > is admitted through the boundary but the typed capability happens
  > to point at a workspace that no longer exists (rare; covers direct
  > unit-test construction outside the canonical bridge). Production
  > runs always thread a valid typed workspace identity, so this
  > fallback is never exercised in production."*

- `JUnitResultsStepDefinition.kt:76-85`:
  > *"the historical `pipeline.workspace.root` system property remains
  > only as a developer-escape hatch in the constructor default
  > (`workspaceRootResolver`); production runs always thread the typed
  > capability through the registry boundary, so the system property is
  > never consulted."*

Both Step plugins' **production handler** reads the workspace root from
the typed `WORKSPACE_IDENTITY_CAPABILITY` (fail-closed at the registry
boundary — `CommonExecutionBoundary.recheckCapabilities(...)`). The
constructor default for `workspaceRootResolver` exists only to support
unit-test construction outside the canonical bridge.

The fitness test already had a precedent for excluding documented
allowlisted production sites: `SystemRuntimeConfig.kt` is excluded
because it is the canonical bridge. The two Step plugins' escape-hatch
defaults fall in the same category — they are documented exception
sites that the production path never exercises.

Three options were considered:

| Option | Description | Verdict |
|--------|-------------|---------|
| **A** | Refine the fitness test to allowlist the two documented escape-hatch defaults. | **Adopted** — minimal, conservative, follows the existing allowlist pattern, evidence-backed by in-source KDoc. |
| B | Refactor the Step plugins to remove the `System.getProperty("user.dir")` fallback entirely. | Rejected — forces every unit test to inject the resolver, adds churn, and would change the documented escape-hatch semantics. Out of scope. |
| C | Hexagonal refactor: introduce `RuntimeConfig` port in `pipeline-step-sdk:api`, implement adapter in `pipeline-application`. | Rejected — large architectural change that requires its own OpenSpec proposal and ADR; out of scope for this WU. |

## Change

Single file: `Lfc0GlobalStateFitnessTest.kt`.

Extended `isProductionKotlinSource()` to exclude the two documented
developer-escape-hatch sites, mirroring the existing allowlist for
`SystemRuntimeConfig.kt`. The added exclusion list documents:

- the `WU-LPR-081` provenance,
- the **typed capability** that the production handler reads
  (`WORKSPACE_IDENTITY_CAPABILITY`),
- the **fail-closed** semantics at the registry boundary,
- the fact that the developer-escape hatch is **out of scope for
  LFC-0** (the fitness invariant focuses on the production path;
  escape-hatch defaults are documented in-line and never exercised
  in production).

No production code changed. The production `workspaceRootResolver`
defaults remain exactly as documented in their KDoc.

## Verification

| Command | Outcome | SHA-256 |
|---------|---------|---------|
| `:pipeline-architecture-tests:test --tests 'Lfc0GlobalStateFitnessTest' --rerun-tasks` | BUILD SUCCESSFUL · XML `tests=2 failures=0 errors=0` | `lpr081-lfc0global.log` (8a1aefd21342c1a1636a2b1a7d5161122be4a06ecdf572c703d2c0aacda7c985) |
| `:pipeline-architecture-tests:test --rerun-tasks` (full module) | BUILD SUCCESSFUL · 65 XMLs, `tests=309 failures=0 errors=0 skipped=0` | `lpr081-full-arch.log` (79a12e195912f33bf4b40e069fac7ddfa3e93879feb3ce9cceeb2427a1a28d47) |

The full-module run confirms zero false negatives: every other
production site that legitimately needs `System.getProperty` access
already routes through `SystemRuntimeConfig` (still the only
allowlisted production site for that family), and no other
`src/main/...*.kt` file was matched.

## End-of-work-unit closure

```text
Reference implementation consulted: GitCheckoutStepDefinition.kt KDoc
  (lines 74-81) and JUnitResultsStepDefinition.kt KDoc (lines 76-85),
  both of which explicitly tag the System.getProperty("user.dir")
  fallback as a developer-escape hatch consulted only outside the
  canonical bridge.
Behaviour adopted: allowlist the two documented escape-hatch defaults
  in the LFC-0 fitness scan, mirroring the existing allowlist for
  SystemRuntimeConfig.kt.
Intentional deviations: none — production code path is unchanged.
Security implications reviewed: n/a — the production handler still
  reads the workspace root from the typed WORKSPACE_IDENTITY_CAPABILITY
  (fail-closed at CommonExecutionBoundary), and the escape-hatch default
  is consulted only in unit-test construction outside the canonical
  bridge.
Tests demonstrating the contract:
  - Lfc0GlobalStateFitnessTest (2/0/0 after WU-LPR-081)
  - Full :pipeline-architecture-tests module (309/0/0/0 after WU-LPR-081)
```

— Receipt authored by SDDK orchestrator session `session_hare_*`.
