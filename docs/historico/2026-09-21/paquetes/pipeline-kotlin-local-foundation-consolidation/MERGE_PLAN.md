# Merge plan

This pack is designed to be merged into the existing `docs/v2` tree without silently declaring itself authoritative before review.

## Step 1 — Copy as proposed documents

Copy the complete `docs/v2` subtree into the repository. Keep `ADR-LFC-*` identifiers until each ADR is accepted; renumber to the repository's canonical ADR sequence only at acceptance time.

## Step 2 — Establish document authority

Accept `00-governance/CURRENT_STATE.md` and `00-governance/DOCUMENT_AUTHORITY.md` first. These define which existing documents remain normative, which are historical evidence, and which must be marked superseded/deferred.

## Step 3 — Apply LFC-0 before implementation

LFC-0 is documentation and repository truth. Do not start major refactors until:

1. V2 is declared the active product line.
2. protocol/controller work is explicitly deferred.
3. stale V1 root documentation and release paths are quarantined.
4. broken or contradictory roadmap references are corrected.
5. the debt register is accepted.

## Step 4 — Accept ADRs just-in-time

Do not approve all ADRs mechanically. Before each milestone, accept only the ADRs required for that milestone. If a spike disproves an ADR assumption, amend or supersede it before implementation.

## Step 5 — Require evidence receipts

Every milestone closes with a receipt under the repository's existing exit-receipt convention, containing:

- commit SHA;
- tests/UAT executed and exact commands;
- performance evidence where applicable;
- removed code paths;
- architecture fitness result;
- known residual debt;
- rollback note.

## Important

These documents intentionally propose deletion of dead or duplicative paths. Git history is the archive. Do not preserve active code solely because it may be useful in a future remote-controller phase.
