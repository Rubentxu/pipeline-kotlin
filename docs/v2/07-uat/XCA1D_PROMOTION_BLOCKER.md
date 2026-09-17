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
