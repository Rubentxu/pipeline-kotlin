# XCA-GOV — governance corrections before XCA-1C.2

**Base:** `bf8cf0fd`

## 1. Duplicate manual certification authority — REMOVED

The 5 nested 6-space `step_key` entries under `families:` are deleted (26 lines). They were
a **stale partial duplicate**: 5 entries out of 16 utilities surfaces, restating
`delivery`/`capability`/`effect`/`replay_policy` that the canonical records already carry.

That listing caused three distinct classes of defect before removal:

```text
1. G3 parsing        naive readers merged it into the canonical records
2. XCA-1A update     an anchor matching it wrote a fixture into core.load's record
3. divergence risk   two editable sources of certification truth
```

`nested 6-space entries (G3) : 0` — the auditor now reports none.

If the by-family view is wanted, it MUST be generated from the canonical records, never
edited by hand.

## 2. Counter partition — display defect fixed

The counter printed a **hardcoded four-name list**, so any class outside that list would be
omitted from the breakdown while the `sum == n` assert still passed. It now enumerates every
key present and asserts **both** axes:

```text
CERTIFIED records                    : 31
  EXERCISED                          : 28
  NOT_EXERCISED                      :  2
  STRUCTURAL_SYNTH                   :  1
  (verdict total)                    : 31
  A_IN_EXAMPLES                      : 17
  B_PROMOTE_COMPATIBILITY            : 11
  C_CREATE_OR_EXPAND                 :  2
  D_REWRITE_CONTRACT                 :  1
  (class total)                      : 31
```

### Correction on the reported 27 vs 31

The `31 != 30` I reported was **my transcription error**, not a partition break: I read a
`tail -14` that had cut the `EXERCISED` line and carried `27` forward from an earlier run.
The partition assert was never violated. The underlying display hazard was real and is fixed.

## 3. NEW FINDING — the certification authority is not valid YAML

```text
yaml.safe_load(step-certification.yaml)
  at HEAD      -> ScannerError, line 1145
  after edit   -> ScannerError, line 1119   (same cause, shifted 26 lines)
```

Cause: a scalar check followed by a deeper-indented mapping key:

```yaml
  certified_must_have_g8_or_g7_receipt: PASS
    notes: "12 CERTIFIED core Steps x G8 receipt, ..."
```

`notes:` is indented under a scalar value, which YAML forbids.

**Consequence.** No standard YAML parser can read the certification authority. This is why
XCA-0's requirement of a *typed reader* was unsatisfiable and every reader so far — mine
included — fell back to regex. That fallback was forced by the data, not chosen out of
laziness, and the regex readers are exactly what produced the earlier false greens.

**This is now a blocking governance item for XCA-3.** A permanent Kotlin fitness that must
parse the ledger cannot be written against an unparseable file, and "the ledger is the
certification authority" is a weak claim while no parser can load it.

Not fixed here: repairing the file requires deciding whether `notes` belongs to a check
map or should be flattened, and that touches the counter section's intent. Raised rather
than silently rewritten.

## Still open

```text
XCA-1C.2   String overloads for writeYaml/writeProperties (decision: option B)
XCA-1D     promote canonical compatibility fixtures into examples/ (no auto-evidence)
XCA-2      installed CLI + journal execution proof
XCA-3      permanent Kotlin fitness + closure receipt
```
