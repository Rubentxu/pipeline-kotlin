# XCA-YAML — make the certification authority parseable

**Base:** `95188c44` (XCA-GOVERNANCE-CLOSE, left intact)
**Scope:** syntax repair only. No certification decision changes. No schema
migration (`candidate_fixtures` / `real_fixtures` is deliberately NOT touched).

## RED (before any change)

```text
yaml.safe_load(step-certification.yaml)
  -> ScannerError: mapping values are not allowed here, line 1119
```

Root cause: the `invariants:` section mixed two shapes, and three checks carried a
deeper-indented `notes:` under a **scalar** value, which YAML forbids:

```yaml
  certified_must_have_g8_or_g7_receipt: PASS
    notes: "12 CERTIFIED core Steps x G8 receipt, ..."
```

Four invalid sites across three checks (`certified_must_have_g8_or_g7_receipt`,
`no_uncertified_steps_with_real_fixture`,
`mandatory_disabled_acceptance_tests_must_be_zero`).

## Repair — one canonical check shape

Every check is now an object. Not flattened into `..._notes` sibling keys, because
that destroys the semantic relation and blocks a natural typed model:

```kotlin
data class CertificationCheck(val status: CheckStatus, val notes: String? = null)
```

```yaml
  certified_must_have_g8_or_g7_receipt:
    status: PASS
    notes: "12 CERTIFIED core Steps x G8 receipt, ..."
```

The three list-valued checks (`legacy_dispatcher_files_in_main` etc.) became objects too,
since the reported defect was exactly the scalar-vs-object inhomogeneity:

```yaml
  legacy_dispatcher_files_in_main:
    status: PASS
    entries: []
```

9 checks, all objects, all with `status`.

## GREEN

```text
ledger_standard_yaml_parse      PASS
ledger_typed_schema_parse       PASS   (every record has step_key/certification_state/real_fixtures;
                                        real_fixtures is a list; every check is an object with status)
top-level keys   version generated_by generated_at cycle freeze e2_prep counters steps invariants
steps            34      type list
states          CERTIFIED 31, STOPPED_G7 2, REJECTED 1
invariants       9
```

## Semantic equivalence proof

An independent regex fact-extractor ran before and after, comparing every step record
field-by-field (`step_key`, `certification_state`, `real_fixtures`, `capability`,
`registry_file`, `contract_suite*`, receipts, `delivery`, `execution`, `freeze_status`,
`namespace_classification`, `legacy_state`):

```text
records before/after                     34 -> 34
step-record semantic diffs               0
```

Plus reader cross-validation: the regex reader and the new YAML reader produce identical
counters (34 records / 31 CERTIFIED / 28 EXERCISED / 2 NOT_EXERCISED / 1 STRUCTURAL_SYNTH,
classes 17/11/2/1).

## A data-loss bug caught in my own repair

The first attempt produced `notes: ''` for the two block-scalar checks: the kid-matching
pattern `^    \S` did not capture the 6-space block-scalar **content** lines, so the text
was dropped. Reverted (`git checkout`) and redone with `^    ` (4+ spaces). Notes are now
276 / 138 / 344 characters, preserved verbatim.

The same attempt also reported two phantom "semantic diffs" on `utilities.tarExtract` —
an artifact of the comparison reader absorbing the `invariants:` children into the last
step record. The reader now truncates at any top-level key.

## Regex removed as an authority

`parse_ledger_yaml()` reads the ledger with `yaml.safe_load`. Regex is retained only for
source-architecture scans, never for discovering records, states, `real_fixtures` or
checks. The law now matches reality:

```text
structured certification facts -> parsed structurally
source-architecture constraints -> source scan
```

## Guard falsified, not asserted

Replacing one check's `status:` with `notstatus:` yields:

```text
exit: 1
AssertionError: ledger_typed_schema_parse: check not an object with status:
                legacy_residual_ids_must_be_zero
```

Restored: `exit: 0`.

## Consequence for XCA-3

XCA-3 MUST NOT implement its own ledger parser. It consumes a typed model over valid YAML
via a standard library, and FAILS if it cannot interpret the authority that way. Both
`ledger_standard_yaml_parse` and `ledger_typed_schema_parse` become permanent gates,
because "it is valid YAML" and "it satisfies our schema" are different properties.
