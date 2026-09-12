# S2-A8 / G3 — `core.waitUntil` Contract Suite Receipt (EVIDENCE CORRECTION)

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `2a247b18`
**This SHA**: `ee2616f9`
**Branch**: `cycle/lfc2-e1-wait-until`
**Date**: 2026-09-12
**Gate**: G3 — **PARTIAL CANDIDATE CONTRACT/READINESS** (NOT final certification)

## Evidence Validation Post-Rebase

- **Base revalidation SHA**: `ee2616f9`
- **Rebase from**: `f5e4003d` → `2a247b18`
- **Test execution**: WaitUntilStepContractSuiteTest (18 tests) + CoreWaitUntilDifferentialContractTest (8 tests)
- **XML SHA-256 (WaitUntilStepContractSuiteTest)**: `2e3689aa50e6e4346c9696d6be038d576a1ac26ebf66eb6ef3b54084b101168d`
- **XML SHA-256 (CoreWaitUntilDifferentialContractTest)**: `f03a4489921c289870e513ab3ca403a96a3a59d3291bd18444ca621857467ea2`

## Registry State Declaration

```
REGISTERED              = true   (Step registered in CoreStepRegistryFactory)
IMPLEMENTED_UNCERTIFIED = true   (stub body, BodyInvoker not integrated)
AUTHORITY_FLIP_READY    = false  (BLOCKER present)
BLOCKER                 = WAITUNTIL_BODY_INVOKER
```

## 1. Purpose

Correct G3 evidence to reflect that `core.waitUntil` is in `IMPLEMENTED_UNCERTIFIED` state, not `CERTIFIED` or `READY_FOR_AUTHORITY_FLIP`. The Step uses a stub body pattern; full polling loop evaluation requires `BodyInvoker` (ADR-0073) integration, which is mandatory before G4/G5.

## 2. Test Results

### 2.1 WaitUntilStepContractSuiteTest (18 tests)

| # | Test | Result |
|---|------|--------|
| 1 | identity — KEY and duplicate registration | PASS |
| 2 | contract completeness — key, descriptor, codec, EVENT_SINK, MEMOIZED | PASS |
| 3 | input codec round-trip | PASS |
| 4 | input codec rejection (foreign envelope) | PASS |
| 5 | output codec round-trip | PASS |
| 6 | output codec rejection (non-waitUntil kind) | PASS |
| 7 | canonical envelope (byte-identical to legacy dsl-v1) | PASS |
| 8 | production registry resolution | PASS |
| 9 | fresh factory consistency | PASS |
| 10 | capability declaration (EVENT_SINK only) | PASS |
| 11 | capability admission (available → Ready) | PASS |
| 12 | missing EVENT_SINK rejects fail-closed | PASS |
| 13 | success via canonical coordinator | PASS |
| 14 | fresh durable (1 terminal SUCCEEDED row) | PASS |
| 15 | observability (StepStarted + StepFinished pair) | PASS |
| 16 | WaitUntilPolled event (correct fields) | PASS |
| 17 | WaitUntilCompleted event (correct fields) | PASS |
| 18 | real registry path scenario | PASS |

**Total: 18 tests, 18 PASS, 0 FAIL**

### 2.2 CoreWaitUntilDifferentialContractTest (8 tests)

| # | Test | Result |
|---|------|--------|
| 1 | handler — WaitUntilPolled and WaitUntilCompleted events (stub pattern) | PASS |
| 2 | handler — WaitUntilPolled has correct attempt and duration fields | PASS |
| 3 | handler — WaitUntilCompleted has correct totalAttempts and totalDurationMs | PASS |
| 4 | handler — stub output has completed outcome with zero attempts-durations | PASS |
| 5 | output codec — WaitUntilOutput round-trips correctly | PASS |
| 6 | codec round-trip — encoded by registry, decodeable as legacy expects | PASS |
| 7 | input codec — default values produce expected envelope | PASS |
| 8 | input codec — WaitUntilInput produces canonical dsl-v1 envelope | PASS |

**Total: 8 tests, 8 PASS, 0 FAIL**

### 2.3 Test Suite Summary

| Suite | Tests | Pass | Fail |
|-------|-------|------|------|
| `WaitUntilStepContractSuiteTest` | 18 | 18 | 0 |
| `CoreWaitUntilDifferentialContractTest` | 8 | 8 | 0 |

**Grand Total: 26 tests, 26 PASS, 0 FAIL**

## 3. Pre-Existing Tests (Unchanged)

| Test | Result |
|------|--------|
| `UatLocal011WorkflowControlTest.SC-011-12` | PASS |
| `CompatibilityCorpusTest.fixture13` | PASS |

## 4. Counter State

```
LEGACY_PLUGIN_IDS       = 6   (UNCHANGED — behind LegacyCore)
metadata rows           = 6   (UNCHANGED)
dispatcher files        = 6   (UNCHANGED)
```

## 5. Block Step Status — STUB PATTERN

`waitUntil` is a Block Step with a condition body. The current implementation follows the **stub pattern**:
- Emits `WaitUntilPolled` with `conditionResult=true`
- Emits `WaitUntilCompleted` with `outcome="completed"`

**CRITICAL**: The actual polling loop with real condition evaluation requires `BodyInvoker` (ADR-0073) integration. This is a prerequisite for G4/G5. The stub does not validate the user's condition DSL.

## 6. G3 Classification: PARTIAL CANDIDATE

This gate validates:
- Contract completeness (key, descriptor, codecs, capabilities)
- Registry registration and resolution
- Capability admission (fail-closed)
- Stub body execution through canonical coordinator
- Durable journal correctness for stub path
- Observability events

This gate does NOT validate:
- Real condition body evaluation
- BodyInvoker integration
- Polling loop semantics
- Condition timeout handling

## 7. What G3 Does NOT Change

- `LEGACY_PLUGIN_IDS` — unchanged (flip is G4, blocked by WAITUNTIL_BODY_INVOKER)
- Legacy decoder — unchanged
- Legacy metadata — unchanged
- Legacy dispatcher — unchanged
- Body evaluation — unchanged (stub only)

## 8. Blockers for Authority Flip

| Blocker | Description | Required For |
|---------|-------------|--------------|
| `WAITUNTIL_BODY_INVOKER` | BodyInvoker (ADR-0073) integration for real condition evaluation | G4, G5, CERTIFIED |

Until `WAITUNTIL_BODY_INVOKER` is resolved:
- `AUTHORITY_FLIP_READY = false`
- Cannot proceed to G4 (remove from LEGACY_PLUGIN_IDS)
- Cannot proceed to G5 (remove legacy execution paths)
- Cannot proceed to CERTIFIED

---

**G3 evidence correction complete.** `core.waitUntil` is registered and functional through the stub pattern, but remains in `IMPLEMENTED_UNCERTIFIED` state. Authority flip blocked by `WAITUNTIL_BODY_INVOKER`.
