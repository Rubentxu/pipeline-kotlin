# PR-014 audit main integration receipt

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-pr014-replay-debt-reconciliation`
**Integration status:** `PASS`
**Branch:** `wu/rp-053r-red-fixtures`
**Target:** `main`
**Date:** 2026-09-26

## Fast-forward evidence

- Main before receipt integration: `a5c63fdd7df874961dd16435df764b5830692429`
- Receipt integration mode: fast-forward push, no force/reset
- Final main SHA and remote equality: captured by the closing verification command

The integration commit contains only the PR-014 release and merge receipts. No production source or release bytes changed.

## Existing release preservation

The published RC remains `v0.40.0-rc5`, tagged at `04ceff8d7ff7743ff83c1a0a64cd000c67e000e4`, with ZIP SHA-256 `69a2421cb5e785e8fa454391df992c530dffeb0dd04468389deefcd8e5222df2`. This documentation commit does not mutate or retag that artifact.

External release harness: NOT_RUN because it is a separate project. Stable release: NOT_CLAIMED.
