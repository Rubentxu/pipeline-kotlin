# Change: lfc2-step-ecosystem-expansion

## Why

`STEP_ECOSYSTEM_MATRIX.md` is the hypothesis for the local-first Step ecosystem.
It cannot be evidence: it was authored before EVT-3 closed and before the EVT/LFC-2
priority chain was explicit. LFC-2E0 is the machine-derived inventory that
discovers the **actual** state of every Step — registry vs legacy, real example,
typed input/output, capabilities, replay policy, contract suite, Event Harness
coverage — and uses that to correct the matrix.

The goal is to close the gap between "what the matrix claims exists" and
"what the code actually ships", then sequence E1..E10 against real evidence.

## Outcomes

- one machine-derived inventory table covering every production Step key
  (LEGACY_PLUGIN_IDS ∪ registry keys ∪ external plugin keys);
- for each Step: delivery, state, dsl present?, StepDefinition present?,
  canonical execution path?, legacy executable path?, typed input?,
  typed output?, capabilities declared?, replay policy?, real example?,
  Event Harness contract?, certification state?, sources (file:line);
- corrected `STEP_ECOSYSTEM_MATRIX.md` reflecting the real inventory;
- cycle ledger incremented.

## Non-goals

- implementing new Steps (LFC-2E1+);
- changing core/registry/canonical path;
- touching legacy dispatch;
- modifying AGENTS.md / ADRs.

## Success

- inventory table is machine-derived (every row cites code or test file:line);
- `STEP_ECOSYSTEM_MATRIX.md` is corrected and re-anchored to the inventory;
- no production code change;
- archive succeeds with `archive-manifest` referencing this cycle.
