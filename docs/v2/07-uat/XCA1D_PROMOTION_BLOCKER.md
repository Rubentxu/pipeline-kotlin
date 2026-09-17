# XCA-1D — promotion blocked by ADR-backed frozen-corpus invariants

**Base:** `97c54030`
**Outcome:** no file moved. A conflict surfaced that is not mechanical rewiring.

## What XCA-1D was going to do

Make `examples/` the single canonical source for the 11
`B_PROMOTE_COMPATIBILITY` surfaces by MOVING their `v2/compatibility/` fixtures to
`examples/core/` and pointing the compatibility corpus at the new location.

## What blocks it

`v2/compatibility/` is not merely a directory of fixtures. It is a **frozen,
byte-pinned and count-pinned corpus with ADR backing** (ADR-0050 §Compatibility), and
three invariants in `UatLocal005CorpusUntouchedTest` depend on the files living exactly
there:

```text
INV-CR-7   the original 6 files (01-06) must be BYTE-IDENTICAL to a base commit,
           verified by comparing SHA-256 against `git cat-file` at a base SHA, and
           must EXIST at v2/compatibility/<name>

CP-002     v2/compatibility/ must contain EXACTLY 21 .pipeline.kts files
```

Moving `01-basic.pipeline.kts` (or any of the 21) out of the directory breaks both:
`Files.exists(compatibilityDir.resolve(...))` fails for INV-CR-7, and the file count
drops below 21 for CP-002.

Directory-walk discovery that would also need rewiring:

```text
CompatibilityCorpusTest.kt               locates v2/compatibility/ by walk
UatCompat001CorpusSmokeRunTest.kt        locates v2/compatibility/ by walk
CliCompileErrorExitsOneTest.kt           locates v2/compatibility/ by walk
UatLocal005CorpusUntouchedTest.kt        asserts existence + byte-identity + count
Lfc2E0GlobalClosureFitnessTest.kt        refers to the "maintained canonical corpus"
```

So this is not "change the discovery glob". It is **weakening a frozen-corpus guarantee
that an ADR established on purpose**, plus a 5-file rewiring, plus a full corpus run to
prove the executed set is unchanged. That is an ADR-level change, not a promotion.

## Options, with a recommendation

```text
A. MOVE + relax INV-CR-7 and CP-002
   Cost: weakens an ADR-backed frozen-corpus invariant. The byte-identity check exists
   to stop the corpus being edited silently, which is a guarantee we want to keep.

B. COPY instead of move
   Rejected: produces two editable copies, which is exactly the divergence the user
   forbade. Two authorities again.

C. Keep compatibility/ physically, ledger points there
   Contradicts "examples/ is canonical", but touches nothing frozen.

D. Reclassify rather than relocate
   Treat v2/compatibility/ fixtures as VALID certification evidence and stop treating
   their location as the defect.
```

**Recommendation: D, with A postponed to an explicit ADR decision.**

Rationale: the 11 `B_PROMOTE_COMPATIBILITY` surfaces are exercised by fixtures that the
compatibility suite **actually executes**. That is arguably STRONGER evidence than the
new `examples/` fixtures, which so far are statically verified only and have never been
run. The defect XCA-0 measured was "claimed but not invoked"; these 11 are invoked. Their
real problem is **labelling**, not location: the ledger calls them `real_fixtures` while
`examples/` is described as canonical product documentation, so a reader cannot tell
"product example" from "regression corpus".

That is an evidence-taxonomy question, and XCA-2 is already scheduled to restructure
exactly that (`candidate_fixtures` / `real_fixtures`, structured evidence with
expectation + status). Resolving it there avoids weakening ADR-0050 for no gain.

The correct end state is then:

```text
examples/            product documentation   (browsable, runnable, canonical for humans)
v2/compatibility/    frozen regression corpus (byte-pinned, ADR-0050)
certification evidence  MAY be drawn from either, and records WHICH
```

That separates the four layers the user named — catalog, certification, documentation,
execution evidence — without collapsing documentation and evidence into one directory.

## State

```text
CERTIFIED                          31
  executable (static candidate)    30
  STRUCTURAL_SYNTH                  1
  NOT_EXERCISED                     0
canonical examples/                19
compatibility-only                 11   <- unchanged; now understood, not moved
runtime evidence                   NOT YET ASSERTED (XCA-2)
```

No file moved. No ledger change. Nothing weakened.

---

# DECISION — option D accepted (XCA-1D closed as an architectural decision)

```text
RELOCATION        REJECTED
RECLASSIFICATION  ACCEPTED

Reason: moving fixtures would violate ADR-0050 (INV-CR-7, CP-002).
Evidence location and product documentation are independent concepts.
```

XCA-1D is NOT a failed slice. It discovered that its own premise was wrong: the rule
`CERTIFIED => fixture under examples/` was too strong, because it conflated two things
that were introduced later and have different purposes:

```text
examples/          executable PRODUCT DOCUMENTATION
v2/compatibility/  FROZEN REGRESSION CORPUS (ADR-0050)
```

## Certification law CORRECTED

Rejected:

```text
CERTIFIED -> must live under examples/
```

Adopted:

```text
CERTIFIED executable surface
  -> has execution evidence
  -> evidence PROVENANCE is explicit
```

Evidence source kinds:

```text
PRODUCT_EXAMPLE       examples/**/*.pipeline.kts
REGRESSION_CORPUS     v2/compatibility/**/*.pipeline.kts
STRUCTURAL_CONTRACT   compiler/rewrite/UAT evidence
PLUGIN_ACCEPTANCE     plugin-installed acceptance, if needed later
```

`core.emit.event` remains `STRUCTURAL_CONTRACT`, with no invented public pipeline.

## Classification renamed to provenance-neutral

`B_PROMOTE_COMPATIBILITY` encoded a decision now rejected, so it is renamed:

```text
A_PRODUCT_EXAMPLE      19
B_REGRESSION_CORPUS    11
C_CREATE_OR_EXPAND      0
D_REWRITE_CONTRACT      1
```

## `real_fixtures` is now known to be insufficient — retired in XCA-2

A bare path cannot express: documentation vs regression corpus; which StepKey was
expected; whether the symbol merely appears; whether it actually executed; how it was
verified; what was observed. XCA-2 replaces it with structured evidence keeping three
dimensions separate:

```yaml
evidence:
  - source:       { kind: REGRESSION_CORPUS, path: v2/compatibility/16-sleep.pipeline.kts }
    expectation:  { step_key: core.sleep }
    verification: { mode: CANONICAL_JOURNAL, status: PENDING }
```

Migration MUST preserve every existing claim as `STATIC_CANDIDATE`. **Nothing becomes
`EXECUTED` merely by migrating the schema.**

## The 11 regression-corpus surfaces are NOT yet runtime-proven

Using the new criterion:

```text
installed CLI -> execute fixture -> canonical journal -> expected StepKey observed
```

the compatibility suite does execute those fixtures, which is considerably stronger than
the source scan behind the new Utilities fixtures. But the criterion has not yet been
applied to them, so they enter XCA-2 as strong *candidates*, not automatic `EXECUTED`.
Whether XCA-2 confirms all 11 unchanged is to be demonstrated, not assumed.

## `examples/` becomes a documentation metric, not a certification debt

```text
Certification coverage:      runtime evidence / certified executable surfaces
Product documentation:       public surfaces demonstrated through examples/
```

These need not both reach 30/30. Product pipelines may demonstrate several surfaces
coherently; `examples/` must not be filled with redundant demos to hit a percentage.
If `sleep`/`isUnix`/`cleanWs` later need visible documentation, that is a
PRODUCT-DOC-COVERAGE task, not a certification repair.

## ADR-0050 protection to carry into XCA-3

```text
compatibility_corpus_expected_count = 21
compatibility_original_pins_intact  = PASS
```

Not to duplicate `UatLocal005CorpusUntouchedTest`, but so certification knows it consumes
a source whose nature is `FROZEN_REGRESSION_CORPUS` (governance: ADR-0050), and not an
arbitrary directory.

## State

```text
CERTIFIED                              31
  30 executable static candidates
     19 PRODUCT_EXAMPLE
     11 REGRESSION_CORPUS
   1 STRUCTURAL_SYNTH  (D_REWRITE_CONTRACT)
NOT_EXERCISED                           0
runtime evidence                        NOT YET ASSERTED (XCA-2)
```
