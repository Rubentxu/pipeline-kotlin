# S2-A2 / G3 — Approved Differential Parity + Migration Readiness

> Base: `e5cc3f25`  
> Gate: G3, pre-flip only

## Classification closure

| Dimension | Legacy observation | Registry candidate observation | Classification | Evidence |
|---|---|---|---|---|
| identity / input envelope | `core.sleep`, `dsl-v1` | same, bytes for 0/1/MAX | PARITY | differential test |
| zero / positive completion | success | success / suspendable | PARITY | G0 + candidate tests |
| metadata | READ_ONLY, MEMOIZED, None | same | PARITY | differential test |
| location / capabilities | controller, semantic none | controller, emptySet | PARITY | differential test |
| succeeded replay | no second execution | MEMOIZED skip | PARITY | G0 + policy test |
| negative | late untyped failure | early decode rejection | APPROVED CONTRACT DELTA | differential test |
| Long.MAX_VALUE | overflow defect | cancellable duration | APPROVED FIX | differential test |
| cancellation / parent timeout | non-cooperative / ineffective | structured propagation | APPROVED FIX | differential test |
| stale RUNNING | no timer semantics | None reruns full input | CURRENT_SCOPE DECISION | G2 recovery test |

```text
PARITY rows                    = 5
APPROVED_FIX rows              = 2
APPROVED_CONTRACT_DELTA rows   = 1
CURRENT_SCOPE_DECISION rows    = 1
UNKNOWN_DIFFERENTIALS          = 0
```

## Readiness

`CoreSleepMigrationReadinessFitnessTest` proves the factory registration, legacy-membership-wins routing, physical legacy decoder/dispatcher/metadata presence, generic boundary isolation, and exact 11/11/11 counters.

`CoreSleepLegacyRegistryDifferentialParityTest` has 5 passing rows. The focused G3 command passed with 8/8 tests. No production routing, catalogue, decoder, metadata, or dispatcher edit occurred.

## Exit state

```text
REGISTERED=true; REGISTRY_PRIMARY=false; LEGACY_UNREACHABLE=false
LEGACY_REMOVED=false; CERTIFIED=false
StructuralFamily(core.sleep)=LegacyCore
counters=11/11/11
```

**STOP.** The next gate requires separate GO for the authority flip.
