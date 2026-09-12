# S2-A8 / G2 — `core.waitUntil` Differential Contract Freeze Receipt

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `f5e4003d`
**This SHA**: `HEAD` (6caaaf2e + G2 changes)
**Branch**: `cycle/lfc2-e1-wait-until`
**Date**: 2026-09-12
**Gate**: G2 — STOP

## 1. Purpose

Drive the registry candidate (`CoreWaitUntilStep`) through the canonical execution path and verify:
- Input codec produces the canonical dsl-v1 envelope
- Handler emits `WaitUntilPolled` and `WaitUntilCompleted` events
- Output codec produces the expected envelope
- Semantic parity with legacy stub pattern

## 2. Changes

### 2.1 New Files

| File | Purpose |
|------|---------|
| `v2/pipeline-application/src/test/kotlin/.../CoreWaitUntilDifferentialContractTest.kt` | Differential contract tests |

## 3. G2 Differential Tests

| Test | Result |
|------|--------|
| `input codec — WaitUntilInput produces canonical dsl-v1 envelope with correct defaults` | PASS |
| `input codec — default values produce expected envelope` | PASS |
| `handler — emits WaitUntilPolled and WaitUntilCompleted events with stub pattern` | PASS |
| `output codec — WaitUntilOutput round-trips correctly` | PASS |
| `handler — stub output has completed outcome with zero attempts-durations` | PASS |
| `codec round-trip — encoded by registry, decodeable as legacy would expect` | PASS |
| `handler — WaitUntilPolled has correct attempt and duration fields` | PASS |
| `handler — WaitUntilCompleted has correct totalAttempts and totalDurationMs fields` | PASS |

**Total: 8 tests, 8 PASS, 0 FAIL**

## 4. Compatibility Corpus Verification

| Test | Result |
|------|--------|
| `CompatibilityCorpusTest.fixture13` (waitUntil) | PASS |

## 5. Block Step Note

`waitUntil` is a Block Step with a condition body. The registry candidate follows the stub pattern:
- Emits `WaitUntilPolled` with `conditionResult=true`
- Emits `WaitUntilCompleted` with `outcome="completed"`

The actual polling loop with condition evaluation requires BodyInvoker (ADR-0073).

## 6. Counter State

```
LEGACY_PLUGIN_IDS       = 6   (UNCHANGED)
metadata rows           = 6   (UNCHANGED)
dispatcher files        = 6   (UNCHANGED)
```

## 7. What G2 Did NOT Change

- `LEGACY_PLUGIN_IDS` — unchanged
- Legacy decoder — unchanged
- Legacy metadata — unchanged
- Legacy dispatcher — unchanged

## 8. Next Steps (G3)

1. Implement `WaitUntilStepContractSuiteTest` following the `CorePwdStepContractSuiteTest` pattern
2. Verify the full contract matrix

---

**STOP**: G2 complete. Awaiting user GO for G3.
