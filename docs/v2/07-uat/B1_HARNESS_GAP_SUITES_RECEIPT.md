# B1 — Gap suites: ecosystem matrix, step inventory, SBOM

- **Date:** 2026-10-15
- **HEAD at authorship:** `9be060f8` (worktree)
- **Scope:** three new suites; one generator guard added; one misnamed helper renamed.
  The fitness `OPEN_GAP` registry drops from four entries to one.

---

## 1. What was closed

| Tool | Suite | Tests |
|---|---|---|
| `scripts/gen-ecosystem-matrix.py` | `scripts/test_gen_ecosystem_matrix.py` | 6 |
| `scripts/regenerate-step-inventory.py` | `scripts/test_regenerate_step_inventory.py` | 12 |
| `scripts/release/sbom-cyclonedx.py` | `scripts/release/test_sbom_cyclonedx.py` | 10 |

Remaining declared gap: `release/sdkman-uat/mirror.py` (`OPEN_GAP`, networked
server spike; its digest contract is already covered by
`release/test_sdkman_digest.py`).

## 2. Contracts pinned (selection)

- **Ecosystem matrix:** CORE and orchestration totals are never summed; a
  missing ledger is rendered as `None`, not `0`; an external plugin needs both a
  `PluginStepId` key and a `StepDefinition` class; the three FAIL-LOUD paths
  (missing step source, missing admission row, missing key constant) abort
  without writing a file.
- **Step inventory:** legacy parsers strip comments and dedup; classification
  precedence is BLOCKED → DEFERRED → CERTIFIED_AT_SHA → REGISTERED; drift
  reports one error per legacy class; render lists each key with its class.
- **SBOM:** only libraries and executables become components (`META-INF`,
  README excluded); every hash equals an independently computed SHA-256; the
  component list is byte-stable across builds while serial/timestamp are volatile.

## 3. Defects found by writing these suites

1. **Empty CORE inventory was not drift.** `main_check` reported nothing when
   the inventory had zero Core keys — a factory refactor that escaped the regex
   would have rendered a green empty inventory. Added as check #0
   (`DRIFT: CORE step inventory is empty`). Real tree after the change:
   `DRIFT_COUNT=0` (exit 0), measured.
2. **`uuid4hex()` was misnamed**: the name promised hex, the value was the
   dashed UUID string the CycloneDX `serialNumber` needs. Renamed to
   `new_serial_uuid()`; emitted bytes unchanged, and the suite now pins the
   canonical dashed format.

## 4. Evidence

```text
$ just harness
…
scripts/test_gen_ecosystem_matrix.py          Ran 6 tests in 0.022s  OK
scripts/test_regenerate_step_inventory.py     Ran 12 tests in 0.012s  OK
scripts/release/test_sbom_cyclonedx.py        Ran 10 tests in 0.023s  OK
harness: 19 suite(s), fail=0                       (exit 0, 2m48s)

$ just fitness
harness coverage: 18 tool(s), 19 suite(s)
  [OPEN_GAP] release/sdkman-uat/mirror.py: … (single remaining declared gap)
OK: every tool has a suite, every suite has a subject and tests, every entry point is declared
```

## 5. What this does NOT claim

- These suites pin the tools' current contracts at the granularity described;
  they are not a proof that the generated matrices/inventories are *correct*
  about the product, only that their parsing, classification, counting and
  refusal rules behave as declared.
- The real generators were not regenerated into the repository by this work
  (`WU_F4_ECOSYSTEM_MATRIX.md`, the step inventory and the SBOM are unchanged).
- `mirror.py` remains without its own suite, by declaration.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; the tools' own FAIL-LOUD
    contracts and helper semantics were read and pinned.
Behaviour adopted:                  hermetic fixtures per tool; failure paths are
                                    asserted, not assumed.
Intentional deviations:             the step-inventory suite covers helpers and
                                    drift logic rather than the repository-wide walk,
                                    which is exercised by `--check` on the real tree.
Security implications reviewed:     none; local files and in-memory ZIPs.
Tests demonstrating the contract:    the three suites above (28 tests total).
```
