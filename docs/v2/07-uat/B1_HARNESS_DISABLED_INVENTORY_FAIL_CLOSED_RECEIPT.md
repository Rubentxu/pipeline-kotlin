# B1 — @Disabled inventory: the generator now fails closed

- **Date:** 2026-10-15
- **HEAD at authorship:** `68ec67cd98b606bdc30e14d3e114595e1d8e0097`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `scripts/gen-disabled-inventory.py` + new
  `scripts/test_gen_disabled_inventory.py`. The generator had **no tests at all** until now.

Found by the parallel audit worker (`audit disabled inventory and PR classifier`) and
reproduced here against the real module before the fix.

---

## 1. Defects

| # | Defect | Consequence |
|---|---|---|
| G1 | `classify()` defaulted every unlisted entry to `HISTORICAL`, while the docstring and the generated footer claimed "the generator fails the run" | A gate-critical `@Disabled` was silently counted as archived evidence; the file documented a law it did not enforce |
| G2 | `extract_entries()` had no line numbers and `main()` never checked the scan produced anything | A wrong `TEST_ROOT` printed `@Disabled total = 0` and `Gate-1 ... not blocked`, exit 0 |
| G3 | `CLASSIFICATION` was consulted but never verified | A renamed test or edited reason made a declared `COMPATIBILITY` entry silently unobserved (expected 2 → observed 0) |
| G4 | `git rev-parse` return code was never checked | An inventory could be generated with an empty HEAD line, claiming provenance it has no |

## 2. Observed RED (original generator, hermetic fixtures, real module imported)

```text
Ran 7 tests in 2.178s
FAILED (failures=7)
  AssertionError: None != 2 : an unclassified entry must fail the run
  AssertionError: None != 2 : an unobserved declaration must fail the run
  ...
```

`main()` returned `None`, so no fail-closed path existed at all; the exit-code API is part
of the fix.

## 3. Closure

- `classify()` returns `None` for an undeclared entry whose reason does not match the
  archive pattern `^(Historical|Archived)\b`; the entry is reported as
  `unclassified @Disabled entry: <file>:<line> <target> <reason>`.
- Refusal (exit 2, **nothing written**) when: scan root missing or empty of Kotlin test
  sources, git cannot name the tree, an entry is unclassified, or a declared
  `CLASSIFICATION` entry is no longer observed.
- The generated Counts block gains `kotlin test files scanned = N`.
- Footer law text now states the real behavior (refuse and publish nothing).

## 4. Observed GREEN

```text
python3 scripts/test_gen_disabled_inventory.py
Ran 7 tests in 2.207s
OK
```

Real tree (current HEAD), the refusal is the correct observable:

```text
$ python3 scripts/gen-disabled-inventory.py
EXIT=2
REFUSED: 9 problem(s); no inventory written.
  - unclassified @Disabled entry: .../ConsoleFrameFiltersTest.kt:568  `StageStepKindResolver treats empty axes as no-op conjuncts`
  - unclassified @Disabled entry: .../CoreIsUnixRegistryPrimaryFitnessTest.kt:283  ...
  - unclassified @Disabled entry: .../CoreLegacyStepMetadataResolverTest.kt:23  ...
  - unclassified @Disabled entry: .../RuntimeApiVersionPrereleaseProbeTest.kt:33  ...
  - unclassified @Disabled entry: .../cli/WULpr010CliCharacterizationTest.kt:283  ...
  - unclassified @Disabled entry: .../FArchRP040DisabledTestClassificationTest.kt:13
  - unclassified @Disabled entry: .../Lfc2ConcreteBodyRoutingDebtFitnessTest.kt:162
  - unclassified @Disabled entry: .../SegmentOutputStoreTest.kt:693  (OUT-01)
  - unclassified @Disabled entry: .../SegmentOutputStoreTest.kt:754  (OUT-02)
```

`WU_LPR_061_DISABLED_INVENTORY.md` was NOT modified by the failed run (`git status` clean
for that path).

## 5. Required owner action (not taken here)

The 9 entries above need a `CLASSIFICATION` decision (category, reason, blocking). Deciding
them here would be a gating decision: `MANDATORY_SUPPORTED` opens Gate-1 and `HISTORICAL`
downgrades real debt (OUT-01/OUT-02 are declared HIGH). Until they are classified, the
inventory cannot be regenerated — which is the intended forcing function, and the committed
document was already stale (68 entries at HEAD `5138a32a` vs 75 observed entries now).

## 6. What this does NOT claim

- No category was assigned to the 9 entries; no gate state changed anywhere.
- Two audit findings in sibling tools (`classify-open-prs.py` hardcoded "All 25" tick,
  `gen-certification-ledger.py` hardcoded zero) are NOT addressed in this receipt.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; the generator's own documented
    classification law (WU-LPR-061) was the contract it violated.
Behaviour adopted:                  zero/unknown evidence fails the run; the archive
                                    regex is the only benign default.
Intentional deviations:             the generator now refuses on the current tree until
                                    the 9 entries are classified; publishing a doc while
                                    refusing would blur the verdict.
Security implications reviewed:     none; reads local sources, runs git rev-parse only.
Tests demonstrating the contract:    scripts/test_gen_disabled_inventory.py (7 methods,
                                    hermetic temp trees, real module imported).
```
