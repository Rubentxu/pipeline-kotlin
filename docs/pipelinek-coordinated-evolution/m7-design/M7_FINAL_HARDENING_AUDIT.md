# CRIC-M7 — FINAL HARDENING audit

**Status:** read-only audit, completed before any M7 code is written.
**Worktree:** `pk-cric-m7` (branch `audit/cric-m7-final-hardening`) at `257c5b8b` (`release/cric-m6-v0.53.0-rc1`).
**Anchor:** the Block 7 plan ("Endurecer los seams cross-repo antes del `PAIR_CERTIFIED` final, cerrar los `PARTIAL` remanentes, producir la candidata final certificable PK") and the normative text in `coordination/INTERFACE_CONTRACT.md` §1..§12.
**Precedent:** `m6-design/M6_CONTEXT_PRESSURE_AUDIT.md` — the same read-only survey shape, applied to the final block of CRIC guarantees.

This document is the audit. It is the input to the M7 design (if a design is
needed) and to the Fabric-side final certification; PK itself ships the
M7 candidate as a final-harden release.

## 0. Scope and naming

The Block 7 plan names one scenario and three properties. The scenario is
"the final hardening of the CRIC-1 chain before the `PAIR_CERTIFIED` final
verdict". The properties are:

```
I.A  close the PARTIAL I.6 row of the M6 audit (synthetic -> event-driven grouping)
I.B  harden the cross-repo seams (the seven non-features the M4 audit pinned,
     plus any new seams M6 introduced)
I.C  produce the M7 candidate (the final PK release in the CRIC-1 chain)
```

The audit answers, in order:

1. What `PARTIAL` / `UNVERIFIED` rows remain in the prior audits (§A).
2. What M6 seams need hardening (§B).
3. Whether the M7 block needs a new PK surface or is a
   compatibility-handoff / final-harden release (§C).
4. The M7 decision (§D).

## A. PARTIAL / UNVERIFIED rows in prior audits

Surveying the M1..M6 audits, the remaining PARTIAL row is exactly one
(M6 §D I.6 — synthetic grouping). All other rows are PASS-by-design +
tested:

```
M1: I.1..I.6  PASS (M1-D cross-JVM, SegmentOutputFollowerTest 18, EventFollowerAdapterTest 18)
M2: I.1..I.6  PASS (RuntimeIntrospection/Control/Recover adapter 8+8+8, decision 12)
M3: I.1..I.6  PASS (27 cases across output-store + runtime-side)
M4: I.1..I.6  PASS (no new surface; existing M1+M2+M3)
M5: I.1..I.6  PASS (no new surface; existing M3)
M6: I.1..I.5  PASS, I.6 PARTIAL — synthetic single-stage / single-step view
              (the published PipelineEventEnvelope strips the type-specific
              fields of the typed events; reading them requires a typed
              event-shape read port that the M6 first cut does not introduce).
```

So the M7 audit's first task is to close the M6 I.6 PARTIAL with a typed
event-shape read port. The port's name, signature, and ADT shapes are
the M7 design's job; the audit's job is to confirm the gap and the
authority to fix it.

## B. M6 seams that need hardening

The M6 release introduced two new additive methods on
`RuntimeIntrospectionPort`:

1. `runStructure(runId, options)` — bounded scan + resume cursor.
2. `inspectOperation(operationId, attempt)` — typed per-op terminality.

The M4 audit pinned seven non-features (observer enumeration, observer
lease, observer retention, etc.). The M6 release does NOT introduce any
of them, but the new surface exposes three new seams:

```
B.1  The bounded-scan seam: a reader that pages slowly must not block the
     writer. The M6 StructurePaginationTest pinna this (test 4), but the
     contract does not yet name a wall-time budget on runStructure the
     same way DEFAULT_INSPECT_TIMEOUT_MS names one on inspect.
B.2  The inspectOperation lookup seam: a caller that passes an unknown
     (operationId, attempt) gets a typed Refused(StorageError), but the
     error message currently contains the operationId and attempt
     verbatim. The contract test pinna the refused shape; the audit
     recommends a more deliberate refusal case (`UnknownOperation`) for
     cleanliness, but the M6 first cut ships the StorageError form.
B.3  The OperationStatus projection seam: the new method projects the
     journal's OperationStatus enum verbatim. The enum has 8 cases
     (SUCCEEDED, FAILED, FAILED_TIMEOUT, DIVERGENT, UNSTABLE, ABORTED,
     LOST, PENDING, RUNNING); the OperationSummary's status field is
     the same enum. A consumer that wants a `when` over the cases must
     handle all 8. The M6 design names this as deliberate (closed ADT
     rule), but a future M-block that adds cases to the enum would
     force a consumer recompile. The M6 contract test does not yet
     pin the 8-case set.
```

These three seams are NOT new M7 features; they are hardening of the
M6 surface so a consumer compiles against a deliberate, named set of
seams. The M7 work is small: a budget constant on `runStructure`, a
new `UnknownOperation(operationId, attempt)` refusal case, and a
contract test that pins the 8-case OperationStatus set.

## C. M7 direction: PK-CANDIDATE NEEDED (small)

The M7 audit's decision:

```
BLOCK 7 = PK-CANDIDATE NEEDED (small).
```

The M7 candidate is a final-harden release, not a feature release.
The candidate is `v0.54.0-rc1` (the next CRIC train after 0.53.0). The
scope is three concrete items, each hardening an existing M6 seam:

1. **`EventShapeReadPort`** (`:pipeline-events`, NEW published module
   coordinate) — typed read of the `StageStarted` / `StageFinished` /
   `StageSkipped` / `StepStarted` / `StepFinished` events. The port
   returns the typed events (not the projected envelopes), so the M6
   I.6 PARTIAL closes: the `runStructure` adapter can use this port
   to drive event-driven grouping. The port is `fun interface` (SAM)
   with one method `eventShapesForRun(runId): List<TypedEvent>`. The
   `TypedEvent` is a sealed interface with the six typed-event cases
   the M6 audit names.
2. **`runStructure` budget constant** — add `DEFAULT_STRUCTURE_TIMEOUT_MS`
   to `RuntimeIntrospectionPort` companion, mirroring
   `DEFAULT_INSPECT_TIMEOUT_MS`. The default is `10_000L` (10s; longer
   than inspect because paginated reads may legitimately take longer).
3. **`UnknownOperation(operationId, attempt)` refusal case** — a new
   case on `IntrospectionRefusal` (additive, no breaking change to
   the closed ADT). The `inspectOperation` adapter uses the new case
   when the journal has no row for `(opId, attempt)`; the existing
   `StorageError` form is deprecated for the no-row case.

Plus the M6 I.6 PARTIAL closure: the `runStructure` adapter is
updated to use the new `EventShapeReadPort` to drive event-driven
grouping. The synthetic-grouping test is replaced by an
event-driven-grouping test that pins the audit's PASS-by-design + tested
shape.

The M7 release is small in lines (≈400 additions, ≈50 deletions) but
significant in contract change: the published module set grows from
5 to 6 (the new `EventShapeReadPort` lives in a new published
coordinate, OR in the existing `:pipeline-events` module — see §D for
the design call).

The audit's pre-M7 design call: put the new port in the EXISTING
`:pipeline-events` module (not a new module). The M3 release added
`output.read.digested.v1` etc. to the existing `:pipeline-output` module
in the same shape; the M7 follows that precedent. The 5-coordinate
published surface becomes a 5-coordinate surface with a new typed port
on `:pipeline-events`.

## D. Decision

```
BLOCK 7 = PK-CANDIDATE NEEDED (small).
M7 candidate: v0.54.0-rc1.
M7 surface (additive, no breaking changes):
  - EventShapeReadPort on :pipeline-events (new fun interface)
  - DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L
  - IntrospectionRefusal.UnknownOperation(operationId, attempt) case
  - runStructure adapter: event-driven grouping (closes M6 I.6 PARTIAL)
M7 tests:
  - EventShapeReadPortAdapterTest (≥ 6 cases, real event store)
  - IntrospectionUnknownOperationRefusalTest (3 cases, contract test)
  - RuntimeIntrospectionPortStructureAdapterTest (extend the existing
    8 cases to pin event-driven grouping, replace the synthetic-grouping
    rows)
  - RuntimeIntrospectionPortOperationAdapterTest (extend the existing
    6 cases to assert the new UnknownOperation case)
  - CapabilityRegistrationTest (extend to pin the 11th capability
    runtime.eventshape.v1)
M7 contract:
  - INTERFACE_CONTRACT.md: §6 block + "Capacidades publicadas (CRIC-M7)"
    table; CONTRACT_SHA256.txt refresh
M7 release:
  - pipelinek-0.54.0.zip + 5 SDK coordinates at 0.54.0
  - PK_CONTRACT_HANDOFF CRIC-M7 to Fabric
  - release-receipt v0.54.0-rc1 with capabilities manifest
M7 negative-scope (preserved):
  - No Governor, no fairness scheduler, no query engine in PK
  - No new lease, no new scheduler, no new transport in PK
  - The seven non-features the M4 audit pinned remain non-features
M7 outcome:
  - Closes the M6 I.6 PARTIAL (the only remaining PARTIAL row)
  - Hardens the three M6 seams (B.1 budget, B.2 refusal case, B.3
    enum set pin)
  - Produces the final PK candidate for the CRIC-1 chain
  - After CERTIFIED + Fabric M7 release, the chain is ready for the
    final PAIR_CERTIFIED verdict.
```

## E. Cross-walk to UAT / AAT / FABRIC evidence

| UAT ID | Description | Test that pinna |
|---|---|---|
| UAT-PK-M7-001 | `EventShapeReadPort.eventShapesForRun` returns typed events for a run with stage + step boundaries | `EventShapeReadPortAdapterTest` |
| UAT-PK-M7-002 | `inspectOperation` on an unknown (opId, attempt) returns `Refused(UnknownOperation)` (NEW case) | `IntrospectionUnknownOperationRefusalTest` |
| UAT-PK-M7-003 | `runStructure` on a run with stage + step boundaries groups operations by their enclosing stage + step (event-driven, NOT synthetic) | `RuntimeIntrospectionPortStructureAdapterTest` (extended) |
| UAT-PK-M7-004 | `runStructure` honours `DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L` and returns a typed refusal on timeout | `RuntimeIntrospectionPortStructureAdapterTest` (extended) |

## F. Open issues for the orchestrator

None that block the audit. The M7 design consumes this audit and names
the API shape (the audit's §C names the high-level shape; the design
names the signatures, ADTs, and adapter composition).

1. The M7 design must confirm whether `EventShapeReadPort` lives in
   `:pipeline-events` (the audit's recommendation) or in a new
   `:pipeline-events-shape` module (a stricter separation). The audit
   recommends `:pipeline-events` for the same reason M3 added
   `output.read.digested.v1` etc. to `:pipeline-output`: the port is
   a typed read of an existing event-store's contents, and a new
   module would be a heavier change for a small surface.
2. The M7 design must confirm the wall-time budget for `runStructure`
   (the audit's recommendation is `10_000L`).
3. The M7 design must confirm the `UnknownOperation` case is added
   to the existing `IntrospectionRefusal` ADT (additive, no breaking
   change).
4. The M7 design must confirm the synthetic-grouping rows in the M6
   `RuntimeIntrospectionPortStructureAdapterTest` are replaced with
   event-driven rows; the test class name + structure stays the same.
5. The M7 design must name the `runtime.eventshape.v1` capability ID
   (the audit's recommendation) and confirm the existing
   `CapabilityRegistrationTest` is extended to pin the 11th capability.
6. The M7 design must confirm the new SDK coordinate is the existing
   `:pipeline-events` (no new published module) and that the existing
   consumer build's `pipeline-events` dependency pulls the new port
   automatically once the SDK is published at 0.54.0.

## G. Final hardening summary

The CRIC-1 chain is currently:

- M1 (`v0.49.0-rc1`): 2 capabilities PUBLICADA
- M2 (`v0.50.0-rc1`): +3 EXPERIMENTAL → 5 capabilities
- M3 (`v0.51.0-rc1`): +3 EXPERIMENTAL → 8 capabilities
- M4 (handoff only): no new surface
- M5 (handoff only): no new surface
- M6 (`v0.53.0-rc1`): +2 EXPERIMENTAL → 10 capabilities (I.6 PARTIAL)
- M7 (`v0.54.0-rc1`, this audit): +1 EXPERIMENTAL → 11 capabilities (I.6 closed)

After M7, the chain has 11 capabilities, 0 PARTIAL rows, and is ready
for the final `PAIR_CERTIFIED` with Fabric.

---

**Audit author:** PipelineK runtime author (authority for the
cross-repo contract).
**Audit date:** 2026-10-11.
**Audit consumed:** `M6_CONTEXT_PRESSURE_AUDIT.md` (audit `83e1c28d`,
1392 lines) and the prior M1..M5 audits.
**Status:** ready for the M7 design (which consumes this audit).
