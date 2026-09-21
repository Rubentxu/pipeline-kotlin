# Document authority and anti-split-brain rules

## Authority order

When documents disagree, use this order:

1. Accepted ADRs.
2. Normative specifications under `03-specifications/`.
3. `CURRENT_STATE.md` and `TARGET_ARCHITECTURE.md`.
4. Current milestone roadmap/gates.
5. UAT contracts.
6. Product guides.
7. Spikes and research notes.
8. Historical exit receipts.

A spike can recommend; it cannot authorize architecture by itself. An exit receipt proves what happened; it does not redefine the current architecture.

## Required status metadata

Every long-lived ADR/specification that may be superseded SHOULD include one of:

- `proposed`
- `accepted`
- `superseded-by: <id>`
- `deferred`
- `rejected`

## Documents to consolidate in the existing repository

During LFC-0:

- rewrite the root README around the V2 local-first product;
- mark remote-first vision/protocol documents as long-term/deferred where they conflict with current scope;
- consolidate roadmap references into one current roadmap;
- keep old milestone receipts immutable as evidence;
- retain the Jenkins familiarity catalogue, but make its implementation status generated/verified where possible;
- replace duplicated architecture descriptions with links to the canonical documents.

## Rule

No new architecture document may introduce a second definition of a canonical type, execution path, module boundary, capability or compatibility level. It must link to the normative source.
