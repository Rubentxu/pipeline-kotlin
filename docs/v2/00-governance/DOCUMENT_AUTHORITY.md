# Document authority — Local Foundation Consolidation

**Status:** accepted  
**Effective:** 2026-09-03  
**Authority:** ADR-0064

## Active product line

The active product line is V2 local-first CI/CD. Its consolidation programme is
defined by [LFC roadmap](../05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md) and
the current `docs/v2/` tree.

For the Durable Kotlin Execution Model (EM), the authoritative line is also
exclusively `docs/v2/`: ADR-0065 is accepted; ADR-0066, ADR-0067 and ADR-0068
remain proposed. The package under
`docs/pipeline-kotlin-execution-model-proposal/` is absorbed historical
provenance only. It records how the EM line entered V2; it cannot authorize
implementation or override a document in `docs/v2/`.

Until LFC-0 through LFC-6 close, remote controllers, network protocols, Jenkins
runtime integration, and broad new step families are deferred. A change is in
scope only when it closes a consolidation gate or is required by an accepted
UAT. V2 must not depend on `:pipeline-steps-system:compiler-plugin`.

## Authority order

When material conflicts, apply this order:

1. Accepted ADRs.
2. Accepted normative specifications.
3. Current state and target architecture.
4. Current roadmap, implementation backlog, and milestone gates.
5. UAT contracts.
6. Product guides.
7. Spikes and research.
8. Historical exit receipts.

For EM specifically, apply this chain after the accepted ADR: normative
specifications, then `EXECUTION_MODEL_MIGRATION.md` and the implementation
backlog, then UAT contracts and traceability. Current-state notes and receipts
report evidence; they do not upgrade an incomplete phase to complete.

Older V2 roadmaps, M0–M8/ML planning documents, and their exit receipts remain
valuable evidence. They do not authorize work that conflicts with ADR-0064 or
the LFC programme.

## Integration rules

- `ADR-LFC-*` identifiers remain proposal identifiers until their individual
  decisions are accepted and assigned the next repository ADR number.
- Every implementation slice must trace to milestone, backlog item, exit
  criterion, gate, and UAT before production code changes.
- A replacement is incomplete while its predecessor remains active, unless an
  accepted ADR gives the predecessor a bounded quarantine period.
- EM work must trace from ADR-0065 through its normative specifications and
  migration/backlog item to an exit criterion, gate, UAT and traceability
  record. The proposal package is not an additional authority tier.
- The LFC-0 gate currently cannot close: its roadmap cites `UAT-GOV-001..004`,
  while the catalogue defines only `UAT-GOV-001` and `UAT-GOV-002`. Define the
  missing contracts before claiming the gate is green.
