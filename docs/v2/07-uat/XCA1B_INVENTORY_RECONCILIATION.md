# XCA-1B — Inventory Reconciliation

**Base:** `0f74f992` (XCA-1A)
**Principle:** do not use the ledger to discover what the ledger should contain.

## Authorities (none may prove itself)

```text
DISCOVERY AUTHORITY   runtime registry / plugin contributors  (PluginStepId declarations)
CERTIFICATION AUTHORITY  docs/v2/status/step-certification.yaml
EXECUTION EVIDENCE    installed CLI + canonical journal       (XCA-2, not yet)
PRODUCT DOCUMENTATION examples/
```

## Q1 — Utilities: 17 vs 16 — RESOLVED

```text
E2 closure "17"                       17   (one receipt's parenthetical arithmetic)
UtilitiesJsonContributor.definitions() 16   (counted directly from source)
runtime declared utilities StepKeys    16   (PluginStepId declarations)
ledger utilities records               16
```

`definitions()` was read directly: 3 JSON + 2 YAML + 2 properties + 2 filesystem +
3 checksums + 2 archive + 2 tar = **16**. `example.uppercase` is **not** in it.

The `17` came from `E2_U7_5_EXAMPLES_CORPUS_BURNDOWN.md:151`
("16 utilities + 1 example.uppercase = 17"), which is correct as a *total plugin* count
and was then restated in `E2_U7_TAR_RECEIPT.md:51` as "`definitions()` returns 17
entries (… + 1 example.uppercase)". That restatement is a **documentation error**: it
folded a different plugin coordinate into the utilities contributor's size.

**Outcome: 17 / 16 / 16. No ledger record was missing.** The E2 receipt text is wrong,
not the ledger.

## Q2 — E3-A not in the central certification authority — CONFIRMED DEBT

```text
core.junit
  present in runtime registry       YES
  present in examples/testing       YES (junit-success, junit-failures, junit-then-publish)
  present in step-certification.yaml  NO   <- the debt

core.publishHTML
  present in runtime registry       YES
  present in examples/testing       YES (junit-then-publish)
  present in step-certification.yaml  NO   <- the debt
```

Product certified in E3-A was never incorporated into the central certification
authority. This is more serious than the 13 Utilities claims: those were **false
claims inside** the ledger, this is **certified product absent from** it. Any audit
trusting the ledger as the universe would have silently omitted two shipped Steps.

### Fixed in this slice

Both records added, with facts read from source rather than invented:

```text
core.junit        capability testing.filesystem.operations
                  registry   examples/testing-plugin/…/junit/JunitStepDefinition.kt
                  fixtures   junit-success, junit-failures, junit-then-publish

core.publishHTML  capability testing.publish.operations
                  registry   examples/testing-plugin/…/publish/PublishHtmlPlugin.kt
                  fixtures   junit-then-publish
```

Both verified `EXERCISED / A_IN_EXAMPLES` by the same XCA-0 law, not asserted.

## Full runtime↔ledger reconciliation

```text
runtime production keys MISSING from ledger   16  (before this slice)
ledger keys with NO production declaration      1  core.load (REJECTED, expected)
```

The 16 were classified, not bulk-inserted:

```text
core.junit, core.publishHTML          -> REAL product, recorded (this slice)
core.catchError, core.warnError,
core.dir, core.withEnv,
core.withCredentials, core.parallel,
core.retry, core.timeout              -> orchestration; need an ORCHESTRATION evidence mode
core.timestamps                       -> unclassified; needs a decision
core.${step.name}                     -> template artifact in source, not a real key
parallel-branch, retry-attempt,
wait-until-control, wait-until-poll   -> durable control nodes (RETRY-D / block machinery),
                                         not user-facing Steps
```

Inserting the orchestration keys would have required inventing a DSL surface for Steps
reached through `BodyInvoker`/`BranchInvoker`. That is the anti-pattern explicitly
rejected for `core.emit.event`: no false DSL to satisfy a KPI. They need the
`ORCHESTRATION` evidence adapter defined first.

## Updated state (31 CERTIFIED)

```text
CERTIFIED records                    31
  EXERCISED                          17
  NOT_EXERCISED                      13
  STRUCTURAL_SYNTH                    1
  SYMBOL_UNKNOWN                      0

A_IN_EXAMPLES                         6   readJSON writeJSON sha256
                                            example.uppercase core.junit core.publishHTML
B_PROMOTE_COMPATIBILITY              11   the core.* primitives
C_CREATE_OR_EXPAND                   13   the utilities families
D_REWRITE_CONTRACT                    1   core.emit.event
```

## Do not promote compatibility fixtures into evidence yet

Copying `v2/compatibility/16-sleep.pipeline.kts` to `examples/` is a **documentation**
move. It must not by itself create a `real_fixtures` claim:

```text
XCA-1D promotion != certification evidence
```

Only XCA-2 (installed CLI -> canonical execution -> journal -> expected StepKey
observed) turns a promoted file into evidence. This preserves the principle won at
`0f74f992`: the ledger never again runs ahead of reality.

## Remaining for XCA closure

```text
runtime StepKeys missing from ledger            0   (needs ORCHESTRATION mode; 16 -> 0)
ledger executable StepKeys missing runtime      0   (core.load classified)
false fixture claims                            0   DONE
CERTIFIED without valid evidence                0   (13 remaining, XCA-1C)
compatibility-only certified surfaces           0   (11 remaining, XCA-1D + XCA-2)
static-but-never-executed claims                0   (XCA-2)
missing fixture paths                           0   DONE
runner-unregistered examples                    0   (not yet measured)
duplicate manual certification authorities      0   (5 nested entries; isolated)
STRUCTURAL_SYNTH without rewrite evidence       0   (core.emit.event has a suite)
```
