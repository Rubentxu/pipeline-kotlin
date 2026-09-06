# Execution Model proposal disposition

**Status:** accepted disposition

## Decision

`docs/v2/` is the sole current authority for the Durable Kotlin Execution Model
(EM). The former `docs/pipeline-kotlin-execution-model-proposal/` package has
been absorbed and is retained only as a historical provenance record. It is
not a parallel specification set and cannot authorize implementation, change a
gate, or supersede `docs/v2`.

## Authoritative EM chain

1. [ADR-0065](../04-adrs/ADR-0065-durable-kotlin-execution-semantics.md) is
   accepted. ADR-0066, ADR-0067 and ADR-0068 remain proposed.
2. Normative EM specifications in `03-specifications/` define the intended
   behaviour.
3. [Execution-model migration](../05-roadmap/EXECUTION_MODEL_MIGRATION.md)
   and [implementation backlog](../05-roadmap/IMPLEMENTATION_BACKLOG.md)
   sequence work, exit criteria and gates.
4. UAT contracts in `07-uat/` and traceability records in `00-context/`
   provide acceptance and evidence linkage.

`DOCUMENT_AUTHORITY.md` resolves any conflict in this chain. The absorbed
package may be cited for provenance, never as a source of current requirements.

## Evidence and open gates

- SPIKE-016 has fresh expanded evidence of **24/24 PASS**.
- The full verify gate is **not green**.
- Approximately **85 failures** remain unclassified pending base-vs-head
  reconciliation; this blocks release or completion claims.
- EM-3 remains **PARTIAL**: typed `ShExecution` exists, while timeout grammar
  and the complete UAT-JEP matrix remain open.

## Traceability

This disposition is reflected in `DOCUMENT_AUTHORITY.md`, `CURRENT_STATE.md`,
`ROADMAP.md`, `IMPLEMENTATION_BACKLOG.md`, and the proposal package README.
Manifest regeneration is intentionally deferred to the verify-close owner.
