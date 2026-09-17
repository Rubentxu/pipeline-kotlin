# XCA-1A — Correct the ledger before using the ledger

**Base:** `dd03a19d` (XCA-0)
**Principle:** correct the ledger before using the ledger to classify work.

## What changed

`docs/v2/status/step-certification.yaml` — 42 lines, 15 insertions / 27 deletions.

```
removed false claims : 13
added real claims    :  1
```

The 13 removed claims are the Utilities surfaces XCA-0 proved the claimed fixture
does not invoke (`readYaml`, `writeYaml`, `readProperties`, `writeProperties`,
`findFiles`, `touch`, `md5`, `sha1`, `sha512`, `zip`, `unzip`, `tarCreate`,
`tarExtract`). Each became `real_fixtures: []`.

They were **not** replaced with a provisional path and **not** replaced with a
"coverage pending" comment. A comment would have kept `CERTIFIED` cosmetically green
while the product-evidence gap stayed invisible. The gap is now visible instead.

The 1 added claim: `example.uppercase` now cites
`examples/example-uppercase-plugin/scripts/uppercase-demo.pipeline.kts`.

## `example.uppercase` — checked, not assumed

The script was read before any file was created:

```kotlin
import example.uppercase.uppercase
pipeline { stages { stage("External") { uppercase("hello") } } }
```

It genuinely invokes `uppercase`, so this is `A_IN_EXAMPLES` and **no new file was
created**. Location was not treated as evidence; the invocation was.

## Honest state after XCA-1A

```text
CERTIFIED records                    29
  EXERCISED                          15
  NOT_EXERCISED                      13
  STRUCTURAL_SYNTH                    1

A_IN_EXAMPLES                        4   readJSON writeJSON sha256 example.uppercase
B_PROMOTE_COMPATIBILITY             11   the core.* primitives
C_CREATE_OR_EXPAND                  13   the utilities families
D_REWRITE_CONTRACT                   1   core.emit.event
LEDGER-MAPPING FALSE CLAIMS          0
```

## Temporary honest RED

```text
CERTIFIED without product evidence : 13
```

This is intended and must not be papered over. Technical certification of those Steps
(implementation, contract suites, receipts) is unchanged and preserved; what is now
visible is the **product-evidence** gap. It recovers only through real evidence.

## Governance debt registered (isolated, not mixed into XCA-1)

The ledger contains **5 nested 6-space `- step_key:` entries** (under `families:`)
carrying `delivery`/`capability`/`effect`/`replay_policy` but no `real_fixtures`. They
are a **second manual certification authority**. XCA-0 guard G3 stops the auditor
merging them into the 32 canonical records, but G3 does not stop them **diverging**.

Two editable sources of certification truth is a governance defect, not a parsing
detail. Resolution is one of:

```text
A. delete the second manual listing
B. declare it DERIVED and generate it from the 32 canonical records
```

Neither is done here. Target fitness counter for XCA-3:

```text
duplicate_manual_certification_authorities = 0
```

## Planned shape for XCA-1C (not done yet)

Do not create one fixture per StepKey — `17 StepKeys -> 17 pipeline.kts` is noise.
Group into coherent product scenarios, each covering several related operations:

```text
examples/utilities/
  01-json-roundtrip.pipeline.kts     (existing; readJSON writeJSON sha256)
  02-yaml-properties.pipeline.kts    readYaml writeYaml readProperties writeProperties
  03-filesystem.pipeline.kts         findFiles touch
  04-checksums.pipeline.kts          md5 sha1 sha512
  05-zip.pipeline.kts                zip unzip
  06-tar.pipeline.kts                tarCreate tarExtract
```

Cardinality is secondary. The law is per-surface:

```text
surface -> real_fixtures -> symbol present -> installed CLI actually executes it
```

## XCA-1B note on promotion

The 11 `B_PROMOTE_COMPATIBILITY` surfaces are statically `EXERCISED` in
`v2/compatibility/`. They must **not** be promoted mechanically on that basis alone:
static symbol presence is a precondition, not proof of execution. Promotion should
follow XCA-2 evidence.

## XCA-2 preparation (explicitly not claimed here)

Nothing in this slice claims execution. A fixture can contain a DSL invocation that
never runs (guarded, skipped, rewritten). XCA-2 must:

```text
installed CLI -> execute fixture -> inspect canonical journal -> executed StepKey set
then cross: ledger.real_fixtures x journal.executedStepKeys
```

Evidence modes:

```text
REGISTRY_STEP     -> executed StepKey from the canonical journal
ORCHESTRATION     -> structural/event execution evidence
STRUCTURAL_SYNTH  -> rewrite-contract evidence
```

## XCA-1D note

Where a `v2/compatibility/` fixture is the only valid evidence, prefer `examples/` as
the canonical product source and have compatibility reuse it, rather than maintaining
two `.pipeline.kts` that will diverge.

## Tooling fix in this slice

`xca0_coverage_audit.py` had `Counter` imported inside `main()`, shadowing it as a
local and crashing the counter block. Moved to module scope. Also added an assertion
that verdicts partition the records, after a derived `n - ex - unk` silently absorbed
the structural class and misreported the debt.
