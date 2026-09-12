# S2-A8 / G3 — `core.waitUntil` Contract Suite Receipt

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `f5e4003d`
**This SHA**: `HEAD` (58a10fe6 + G3 changes)
**Branch**: `cycle/lfc2-e1-wait-until`
**Date**: 2026-09-12
**Gate**: G3 — **READY_FOR_AUTHORITY_FLIP**

## 1. Purpose

Implement `WaitUntilStepContractSuiteTest` following the `CorePwdStepContractSuiteTest` pattern to certify `core.waitUntil` end-to-end across the registry-driven, open-world Step seam.

## 2. Changes

### 2.1 New Files

| File | Purpose |
|------|---------|
| `v2/pipeline-application/src/test/kotlin/.../WaitUntilStepContractSuiteTest.kt` | Contract suite tests |

## 3. Contract Suite Coverage Matrix

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

## 4. Test Suite Summary

| Suite | Tests | Pass | Fail |
|-------|-------|------|------|
| `WaitUntilStepContractSuiteTest` | 18 | 18 | 0 |
| `CoreWaitUntilStepUnitTest` | 9 | 9 | 0 |
| `CoreWaitUntilDifferentialContractTest` | 8 | 8 | 0 |
| `CanonicalWaitUntilNodeDispatcherTest` | 2 | 2 | 0 |

**Grand Total: 37 tests, 37 PASS, 0 FAIL**

## 5. Pre-Existing Tests (Unchanged)

| Test | Result |
|------|--------|
| `UatLocal011WorkflowControlTest.SC-011-12` | PASS |
| `CompatibilityCorpusTest.fixture13` | PASS |

## 6. Counter State

```
LEGACY_PLUGIN_IDS       = 6   (UNCHANGED)
metadata rows           = 6   (UNCHANGED)
dispatcher files        = 6   (UNCHANGED)
```

## 7. Block Step Note

`waitUntil` is a Block Step with a condition body. The registry candidate follows the stub pattern:
- Emits `WaitUntilPolled` with `conditionResult=true`
- Emits `WaitUntilCompleted` with `outcome="completed"`

The actual polling loop with condition evaluation requires BodyInvoker (ADR-0073).

## 8. What G3 Does NOT Change

- `LEGACY_PLUGIN_IDS` — unchanged (flip is G4)
- Legacy decoder — unchanged
- Legacy metadata — unchanged
- Legacy dispatcher — unchanged

## 9. Authority Flip Readiness

The registry candidate is now certified through the contract suite. The `core.waitUntil` Step is **READY_FOR_AUTHORITY_FLIP**.

Next gate (G4): Single edit to remove `"core.waitUntil"` from `LEGACY_PLUGIN_IDS`.

---

**STOP**: G3 complete. `core.waitUntil` is READY_FOR_AUTHORITY_FLIP.
