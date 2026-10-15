# B1 — @Disabled inventory: it now describes the tree instead of refusing it

- **Date:** 2026-10-15
- **HEAD at authorship:** `840c7e19` (worktree `test-harness-brave-harbor-6920`)
- **Scope:** `scripts/gen-disabled-inventory.py`, its suite, and the regenerated
  `docs/v2/07-uat/WU_LPR_061_DISABLED_INVENTORY.md`.

---

## 1. The defect: the inventory invented an entry from documentation

`gen-disabled-inventory.py` extracted `@Disabled("...")` by scanning **raw source text**.
`FArchRP040DisabledTestClassificationTest.kt` documents the required shape in its own KDoc:

```text
 *  1. Every `@Disabled` annotation MUST carry a textual reason
 *     (the @Disabled("...") string argument). A bare @Disabled
```

The extractor matched that example, attributed it to the enclosing class and produced:

```text
@Disabled entry: .../FArchRP040DisabledTestClassificationTest.kt:13
                 class FArchRP040DisabledTestClassificationTest (whole class)  '...'
```

Consequences, both real:

- the generator refused the tree (`exit 2`, nothing written) with **nine** findings, one of
  them describing a test that is not disabled at all;
- `WU_LPR_061_DISABLED_INVENTORY.md` could not be regenerated at all — the committed
  document was generated 2026-09-18 at `5138a32a`.

A fail-closed gate that cannot tell code from documentation is a gate nobody can satisfy.

## 2. The fix: a code mask, not a text rewrite

`code_mask(src) -> [bool]` classifies every character as code or not:

```text
// line comment        -> False to end of line
/* block comment */    -> False (KDoc included)
"string" and """raw""" -> False for the literal body, escapes respected
'c' char literal       -> False
everything else        -> True
```

Extraction then requires the annotation's `@` to sit on a **code** character, and the target
search (`fun \`name\`` / `class Name` inside the following 600 characters) uses the same mask,
so a commented-out declaration cannot become the target either. Offsets and line numbers are
preserved: the mask classifies characters, it never rewrites the text, so the reported
`file:line` and the reason still come from the original bytes.

## 3. Evidence

Suite (`scripts/test_gen_disabled_inventory.py`, 12 tests, hermetic temp trees, real module
imported rather than reimplemented), five new rows in `CommentAndStringMentions`, each with
its mutation in the docstring:

```text
RED   (before the fix): Ran 12 tests ... FAILED (failures=4)
      test_kdoc_mention_is_not_an_entry
      test_line_comment_mention_is_not_an_entry
      test_string_literal_mention_is_not_an_entry
      test_real_annotation_next_to_a_mention_is_still_found
GREEN (after the fix) : Ran 12 tests in 3.488s ... OK
```

Real tree, before and after:

```text
before: REFUSED: 9 problem(s); no inventory written
after : exit 0; @Disabled total = 74
        MANDATORY_SUPPORTED = 0   COMPATIBILITY = 4   HISTORICAL = 66
        EXPERIMENTAL = 2          OBSOLETE = 2
        Gate-1 @Disabled state = not blocked by @Disabled inventory
```

## 4. The eight real entries, declared with evidence

Every one was classified from its own `@Disabled` reason plus the artifact that reason names.
A category states why the disable is tolerable; it is not a claim that the disabled test is
healthy.

| file | entry | category | basis |
| --- | --- | --- | --- |
| `ConsoleFrameFiltersTest.kt` | `StageStepKindResolver treats empty axes as no-op conjuncts` | OBSOLETE | RED pin of the `--kind` drop, now CLOSED; successor `ConsoleOperationSelectionTest` RC3-4/RC3-5 + `R4_WU_RC3_CONSOLE_SELECTION_RECEIPT.md` |
| `CoreLegacyStepMetadataResolverTest.kt` | `resolves sleep metadata by step key matching the decoded command` | OBSOLETE | subject physically deleted (`Pwd`/`CleanWs`/`Load` LEGACY_REMOVED); successor `CoreSleepG3DifferentialParityTest` over `CoreSleepStep.descriptor` |
| `CoreIsUnixRegistryPrimaryFitnessTest.kt` | `G4 flip — legacy source code is physically present` | HISTORICAL | S2-A5/G5 archived registry-primary evidence (G4 row 8 kept verbatim) |
| `WULpr010CliCharacterizationTest.kt` | `WONTFIX (WU-LPR-011 F5) — resume of a terminal run…` | HISTORICAL | F5 closed as WONTFIX; `run()` helper hangs; contract pinned by `CanonicalDurableRunCoordinatorTest` and `UatDsl003ParallelTest.P6` |
| `RuntimeApiVersionPrereleaseProbeTest.kt` | `a plain version parses` | EXPERIMENTAL | P3 witness: `0.48.0-rc1` illegal BY DESIGN; kept as a law-regression witness, non-gating |
| `Lfc2ConcreteBodyRoutingDebtFitnessTest.kt` | `class ViolationFixture` | EXPERIMENTAL | injection fixture pending synthetic-source refactor (H2 slice 3); non-gating while baseline debt is zero |
| `SegmentOutputStoreTest.kt` | `OUT-01 prune of a short runId…` | COMPATIBILITY | reproduced witness; fix needs the durable-format migration policy (`WIP4_OUT01_OUT02_REPRODUCED.md`) |
| `SegmentOutputStoreTest.kt` | `OUT-02 distinct runIds whose safe forms are equal collide…` | COMPATIBILITY | same deferred decision, same receipt |

## 5. What this receipt does not claim

- No `@Disabled` test was enabled, deleted or edited. `OBSOLETE` marks a deletion candidate
  and names its live successor; the deletion sweep is the owner's, not this commit's.
- `MANDATORY_SUPPORTED = 0` means "no disabled test is currently declared gate-critical",
  which is a statement about the declarations, not proof that Gate-1 is green.
