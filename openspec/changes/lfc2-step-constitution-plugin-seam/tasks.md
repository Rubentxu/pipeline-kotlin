# Tasks: lfc2-step-constitution-plugin-seam (LFC-2 reconstitution)

## Phase A — Reconciliation + design (THIS change; no production edits)

- [x] Reconstitution matrix (RECONCILIATION_MATRIX.md).
- [x] ADR disposition (ADR_DISPOSITION.md).
- [x] Proposal + design (this change).
- [ ] Human/openspec review of the reconstituted LFC-2 gate and the NEW/AMEND ADR list.

Phase-A exit: the change is accepted; only then does the apply phase touch canonical authority
(docs/v2 ADRs/specs/roadmap/AGENTS) and production.

## Phase B — Apply (ordered; each slice ends at its gate; LFC-2 stays OPEN until GATE)

Slices follow `design.md` order. Certification states apply to every Step touched: a Step reaches
`CERTIFIED` only through the common suite; `QUARANTINED` if a certification dimension cannot be
proven; never `DONE/PASS` while uncertified.

| # | Slice | Deliverable / gate | Canonical authority to land | HF |
|---|---|---|---|---|
| B0 | Land reconstitution ADRs + specs | ADR-0070..0074 accepted; spec files added; AGENTS rules appended | docs/v2/04-adrs, 03-specifications, AGENTS.md | — |
| B1 | Step Constitution | sealed `ExecutionNode` + open `StepRegistry` + `StepDefinition`/`StepContract`; fail-closed admission; no per-Step switch | ADR-0070; STEP_CONSTITUTION spec | HF0 |
| B2 | ScenarioRunner | `.pipeline.kts` executable by ScenarioRunner; inventories converge | ADR-0071; EXECUTABLE_SCENARIO_CORPUS spec | HF1 |
| B3 | PipelineExtension (HF1) | pipeline-test-rule tagged HF1 + `StepContractSuite` | ADR-0072; PIPELINE_TEST_HARNESS spec | HF1 |
| B4 | generic Step seam | canonical Invoke → Registry → typed adapter → handler; capability admission + context bridge | ADR-0070 | HF0/HF1 |
| B5 | migrate `echo` + `sh` | both run via the seam; concrete dispatcher cases deleted | ADR-0070 | HF1/HF2 |
| B6 | RealPipelineExtension | HF2 forked real distribution | ADR-0072 | HF2 |
| B7 | external reference plugin proof | independent plugin JAR runs with **no core edit** (extensibility gate) | ADR-0070/0071 | HF2 |
| B8 | Step certification | `StepContractSuite`/`PluginContractSuite`; Step reaches CERTIFIED | ADR-0074; STEP_PLUGIN_CERTIFICATION spec | HF0..HF2 |
| B9 | strict DSL | DslMarker/scopes/smart constructors; fake-return closure; source fidelity | ADR-0070/0074; DSL_SPEC | HF1 |
| B10 | BodyInvoker/BranchInvoker | block Steps re-enter engine; no `dispatch*Block` collection | ADR-0073; BLOCK_STEP_EXECUTION AMEND | HF1/HF3 |
| B11 | dir / withEnv / timestamps | context-only blocks via BodyInvoker | ADR-0073 | HF1 |
| B12 | retry / timeout | real semantics over BodyInvoker (steer E-EM-11 D1/D2 revision) | E-EM-11 + ADR-0073 | HF3 |
| B13 | composable parallel | Named Bodies + BranchInvoker; reuses durable branch machinery | E-EM-11 D3 (composable) + ADR-0073 | HF3 |
| B14 | durable scripted runtime values | real `pwd`/`isUnix`; scripted runtime boundary; no fake returns | ADR-0070/0074 | HF1/HF3 |
| B15 | typed when/post | conditions/post on typed outcomes | ADR-0074 | HF1 |
| B16 | formal `@KotlinScript` + source fidelity | formal scripting, source mapping, final corpus | ADR-0071 | HF1/HF2 |
| **B17** | **LFC-2 GATE** | `lfc-2-honest-dsl-closure` green (no disabled/quarantined obligation counted as done) AND extensibility proof CERTIFIED | LFC2_HONEST_DSL_CLOSURE + E-EM-11 reopen | all |

## Ordering constraints

- Do NOT migrate a large Step catalog before B7 (external plugin proof): extensibility must be
  demonstrated on the minimum slice (`echo`+`sh`) before it is trusted.
- `parallel` is last among control-flow (B13) and is composable, not stage-terminal.
- Do not start a block Step before B10 (BodyInvoker/BranchInvoker) exists.

## Verification discipline (AGENTS V2 rules)

- Per-slice: targeted runs only; `--tests` mandatory at L1/L2; full `check` once per apply/verify
  round as final gate; record argv/exit/output digest; never weaken/ignore a test to pass a gate.
- Every UAT/integration class declares `@Timeout`; teardown kills whole process groups.
- Do not claim a Step `DONE`/`PASS` from a partially wired façade (criterion 13/18).
- No full-grammar or `parallel` UAT re-opens green until E-EM-11 (B12/B13) provides real semantics.
