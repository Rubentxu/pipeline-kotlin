# XCA-0 — Audit Correctness (reconnaissance)

**Status:** reconnaissance complete; permanent fitness is XCA-3.
**Tool:** `v2/tools/xca0_coverage_audit.py` (transitional, NOT the gate).
**Base:** `dbbf3ff5`

## Why this slice exists

`EXAMPLES-COVERAGE-AUDIT` opened on a premise that proved wrong (there are 15
`examples/**/*.pipeline.kts`, not >=25), and the first three attempts to answer it
produced **confident false greens**. XCA-0 therefore exists to protect the auditor
before the auditor is used to justify any file change.

## The three false greens, and the guard each produced

| # | False green | Root cause | Guard |
|---|---|---|---|
| 1 | "30 CERTIFIED, 30 violations" | YAML **schema comment block parsed as records** | G1: comment lines skipped (91) |
| 2 | "`real_fixture` is never populated" | Field is `real_fixtures` (**plural**) | G2: singular never yields records; asserted 0 |
| 3 | 16 utilities surfaces reported "ok (examples/)" | Checked the **path exists**, never that the claim was **true** | G4: EXERCISED only if the fixture imports/calls the surface symbol |

Additionally G3: the ledger contains **5 nested 6-space `- step_key:` entries** carrying
no `real_fixtures` — a duplicate-authority listing that a naive parser merges into
records. The tool parses exactly the 2-space top-level records (asserted == 32) and does
not merge them.

## Law enforced (documentary -> executable)

The audit does NOT assert:

```text
CERTIFIED -> some examples/ path exists
```

It asserts:

```text
CERTIFIED surface -> a CLAIMED fixture actually INVOKES its DSL symbol
```

Symbol resolution is **source-derived**, never fixture-frequency-derived: the symbol must
be declared by a `fun` in `PipelineDsl.kt` or a plugin DSL facade. Receiver extension
functions (`fun StepScope.readJSON(`) are handled; the first regex missed them and
produced a false negative on `utilities.readJSON`, caught by hand-verifying a case known
to be true.

## Findings at `dbbf3ff5`

```text
CERTIFIED records                                 29
  EXERCISED (fixture genuinely invokes it)        14
  NOT_EXERCISED (claimed, never invoked)          14
  STRUCTURAL_SYNTHETIC (no DSL surface)            1   core.emit.event
```

Cross-cut by fixture location:

```text
exercised via examples/                            3   readJSON, writeJSON, sha256
exercised only via v2/compatibility/              11   the core.* primitives
no fixture claimed at all                          1   example.uppercase
```

### The Utilities debt (hand-verified, decisive)

`examples/utilities/01-json-roundtrip.pipeline.kts` calls exactly:
`stage`, `readJSON`, `writeJSON`, `sha256`.

But **16** surfaces claim it in `real_fixtures`. Therefore **13 ledger mappings are
provably false** (`readYaml`, `writeYaml`, `readProperties`, `writeProperties`,
`findFiles`, `touch`, `md5`, `sha1`, `sha512`, `zip`, `unzip`, `tarCreate`,
`tarExtract`).

The ledger is therefore not merely incomplete: it asserts coverage that does not exist.
A ledger crediting `readYaml` to a file that never calls `readYaml` cannot be used as
certification evidence until corrected.

### `core.emit.event` is NOT a missing example

`grep -rn "fun emitEvent"` finds no DSL declaration. `core.emit.event` appears in
`PipelineDsl.kt` only inside error-message strings stating that `catchError`,
`warnError` and `unstable` are **pre-compiler-rewritten** into `core.emit.event` +
`core.sh`. It is a structural/synthetic Step. Its evidence is the rewrite contract
(`EmitEventCatchErrorRegistryUatTest.kt`), not a fixture calling an invented symbol.
Creating such a fixture would have been the "fake runtime value" anti-pattern.

## Classification for XCA-1

```text
A_REMAP_EXISTING          14   surface IS exercised; ledger mapping needs correcting
B_PROMOTE_COMPATIBILITY   ~11  core.* exercised ONLY from v2/compatibility/
C_CREATE_NEW              14   no genuine fixture exists (13 utilities + example.uppercase)
D_REWRITE_CONTRACT         1   core.emit.event - structural evidence
```

## Explicitly not done

- This is a **static** claim. XCA-2 must prove the same by running each fixture through
  the **installed CLI** and reading executed step keys from the journal
  (invocation/event/journal identity) — the stronger, non-inferable evidence.
- The tool is Python and transitional. XCA-3 replaces it with a typed Kotlin fitness
  plus negative fixtures.
- **No fixture file has been created, promoted or edited yet.**
