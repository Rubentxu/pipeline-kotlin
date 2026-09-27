# WU-RP-040 R5.1 — Release Receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-r51-detekt-burndown`
**Date:** 2026-09-27
**Branch:** `wu/rp-053r-red-fixtures`
**Status:** `RELEASED_DOCUMENTATION_ONLY`

This slice did not change Kotlin production bytes that warrant a new ZIP,
SBOM, manifest, or tag. The three production commits (`1c436312`,
`b8f9ec01`, `4207748e`) are local refactors of code that already shipped
in `v0.40.0-rc5` (tagged at `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4`,
ZIP SHA-256 `69a2421cb5e785e8fa454391df992c530dffeb0dd04468389deefcd8e5222df2`)
and `v0.40.0-rc7` (HEAD `ab5bec80`, no ZIP published at this writing).

The current published RC remains immutable. No new artifact is created;
no release tag is moved.

## Evidence

- Closure receipt: `docs/v2/07-uat/WU_RP_040_R5_1_CLOSURE_RECEIPT.md`
- Verification report: `docs/v2/07-uat/WU_RP_040_R5_1_VERIFICATION_REPORT.md`
- Spec/design/plan/exploration: 4 docs under `docs/v2/07-uat/WU_RP_040_R5_1_*.md`
- 3 detekt contracts closed (C-1, C-2, C-3): fresh XMLs (`f491e274…`,
  `bc72227f…`, `fc0cf3ff…`) and JUnit canary (`tests=10, failures=0,
  errors=0, skipped=0` for `MainCliParsingTest`).
- 1 pre-existing flake (D-002, `Rp022ThroughputProbe`): NOT_REGRESSION,
  isolated re-run green.
- Main integration: NOT_RUN — pending operator review.
- External harness: NOT_RUN — slice produced no ZIP.
- Stable release: NOT_CLAIMED.

Reference implementation consulted: none applicable.

Behaviour adopted: close the three detekt smells left behind by
post-R5 commits without weakening the global detekt gate, document each
closure contract, and report the pre-existing flake honestly.

Intentional deviations: no ZIP, no SBOM, no tag. The slice is internal
tooling.

Security implications reviewed: no executable, dependency, credential,
process, network, or serialization surface changed. `JsonAccessors` keeps
its public surface unchanged; the `@Suppress` is a detekt-pragma, not an
invisible behavior change. `CliParser.parse` is still a pure function
returning a typed `CliParseResult` ADT; the refactor only moves per-option
logic into `applyOption`.
