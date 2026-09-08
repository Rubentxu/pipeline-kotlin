# Reconstitution matrix — package proposal → canonical authority

Source package (frozen reference, NOT authority):
`docs/pipeline-kotlin-lfc2-step-testkit-evolution/` at baseline `5af901c7`.
Decision codes: **MERGE** (fold into existing canonical authority as content/refinement),
**AMEND** (extend an existing canonical doc), **NEW** (no canonical authority covers it),
**DISCARD** (rejected / duplicate / superseded).

Rule (MERGE_ORDER §7 spirit): never create a second source of truth; keep one owner per concept;
re-map IDs on collision; canonical `docs/v2` + openspec remain the only authority.

## ADRs (pack `ADR-LFC-018..023`)

| Package proposal | Existing canonical authority | Decision | Destination / next action | Backlog / UAT / gate |
|---|---|---|---|---|
| ADR-LFC-018 closed execution structure + open Step registry | ADR-0069 (closed `ALL_PLUGIN_IDS` registry); ADR-0064; STEP_PLUGIN_SDK | **NEW** (mechanism not covered: registry is currently closed) | NEW canonical `ADR-0070` (consolidated with 019). AMEND ADR-0069 cross-ref + mechanism clause (fail-closed becomes registry-driven admission). | Epic E8 (LFC-3) + E-EM; gate = external plugin runs with no core edit. |
| ADR-LFC-019 core & external share one execution path | ADR-0069 intent; ADR-0054; STEP_PLUGIN_SDK | **NEW** (no canonical ADR states the single-path guarantee) | Fold into `ADR-0070`. | Same gate as 018. |
| ADR-LFC-020 examples are executable specifications | `examples/`, `v2/compatibility` corpus, UAT_SCENARIOS | **NEW** (governance/spec method not adopted canonically) | NEW canonical `ADR-0071` + NEW spec `EXECUTABLE_SCENARIO_CORPUS`; layout migration of `examples/` is progressive. | Corpus scenarios = first-class UAT/gate inputs. |
| ADR-LFC-021 layered Test Harness fidelity | ADR-0048 (sandbox local), ADR-0053, openspec/specs/pipeline-test-rule (in-process) | **NEW** (taxonomy not canonically named; collision must be resolved) | NEW canonical `ADR-0072` defining HF0..HF6; AMEND pipeline-test-rule spec to tag HF1; AMEND ADR-0048/0053 cross-ref. | Test Strategy / pipeline-testkit gates. |
| ADR-LFC-022 BodyInvoker / BranchInvoker for block Steps | ADR-0054 (block-step nesting, proposed); BLOCK_STEP_EXECUTION.md spec | **NEW** (no canonical BodyInvoker/BranchInvoker concept) | NEW canonical `ADR-0073`; AMEND BLOCK_STEP_EXECUTION.md; **steer E-EM-11 D1/D2** away from `dispatchRetryBlock`/`dispatchTimeoutBlock` (criterion 15). | E-EM-11; workflow-control milestone. |
| ADR-LFC-023 a Step is done only when CERTIFIED | MILESTONES Definition of Done; ADR-0069 | **NEW** (formal state model not canonically adopted) | NEW canonical `ADR-0074`; AMEND MILESTONES DoD + ADR-0069 cross-ref. | Certification gates in UAT_CATALOG / TEST_MATRIX. |

## SPECs (pack `SPEC-LFC-016..021`)

| Package proposal | Existing canonical authority | Decision | Destination / next action |
|---|---|---|---|
| SPEC-016 STEP_CONSTITUTION | STEP_PLUGIN_SDK.md (4-representation model); GRAPH_MODEL StepDefinition | **AMEND + NEW**: fold the constitution into a new canonical `STEP_CONSTITUTION.md` spec, and AMEND STEP_PLUGIN_SDK to reference it. | NEW spec `docs/v2/03-specifications/STEP_CONSTITUTION.md`. |
| SPEC-017 EXECUTABLE_SCENARIO_CORPUS | `v2/compatibility/`, UAT_SCENARIOS, TEST_STRATEGY | **NEW** spec (executable scenario layout + inventories + negative corpus). | NEW spec `EXECUTABLE_SCENARIO_CORPUS.md` + layout migration plan. |
| SPEC-018 PIPELINE_TEST_HARNESS | openspec/specs/pipeline-test-rule (HF1 only) | **AMEND + NEW**: tag pipeline-test-rule as HF1; add canonical harness-fidelity spec HF0..HF6. | NEW spec `PIPELINE_TEST_HARNESS.md`; AMEND `openspec/specs/pipeline-test-rule/spec.md`. |
| SPEC-019 STEP_PLUGIN_CERTIFICATION | none (no common core+plugin suite exists) | **NEW** spec (C01..C19 matrix + StepContractSuite/PluginContractSuite). | NEW spec `STEP_PLUGIN_CERTIFICATION.md`. |
| SPEC-020 GENERIC_BODY_EXECUTION | BLOCK_STEP_EXECUTION.md; ADR-0054; E-EM-11 | **AMEND** (no new spec needed; fold into BLOCK_STEP_EXECUTION + ADR-0073). | AMEND `BLOCK_STEP_EXECUTION.md`. |
| SPEC-021 TEST_SANDBOX_PROFILES | ADR-0048, ADR-0053 | **AMEND + NEW** profiles doc; map HF4/HF5 to ADR-0048/0053. | NEW spec `TEST_SANDBOX_PROFILES.md` + ADR-0072. |

## Deltas / narrative / integration

| Package artifact | Existing canonical authority | Decision | Destination |
|---|---|---|---|
| ROADMAP_DELTA / LFC2_STEP_TESTKIT_EVOLUTION (slice order) | `docs/v2/05-roadmap/` (LFC2_HONEST_DSL_CLOSURE, LOCAL_FOUNDATION_CONSOLIDATION, MILESTONES) | **MERGE** (content only, IDs re-mapped) | Roadmap LFC-2 gate additions + IMPLEMENTATION_BACKLOG "LFC-2 recovery slices" section. |
| IMPLEMENTATION_BACKLOG_DELTA | IMPLEMENTATION_BACKLOG.md (E-EM epic, LFC-2 slices) | **MERGE** (re-map to existing epic/item IDs; no parallel epic) | Backlog rows under Epic E8 (plugin seam) + LFC-2 slices. |
| UAT_CATALOG / TEST_MATRIX / UAT_RUNBOOK deltas | canonical UAT set (`07-uat/*`, `06-quality/TEST_STRATEGY.md`) | **MERGE** (content), IDs/harness-levels re-mapped to HF0..HF6 | HF-tagged UAT tiers + gates. |
| TRACEABILITY_MATRIX delta | canonical traceability | **MERGE** | Map each reconstituted item → HF level + certification gate. |
| AGENTS_STEP_PLUGIN_CONSTITUTION | root `AGENTS.md` (§STEP SEMANTICS + V2 rules) | **AMEND** (append operative rules; ADR/spec stay architectural authority) | `AGENTS.md` new Step-constitution/fitness rules (apply phase). |
| EXAMPLES_LAYOUT_MIGRATION / FILES_TO_UPDATE | `examples/`, compatibility, fixtures | **MERGE** into EXECUTABLE_SCENARIO_CORPUS spec + migration plan | Progressive layout; ScenarioRunner drives all four inventories. |
| reference/reference-plugin-contract.md | STEP_PLUGIN_SDK | **MERGE** (reference model for the external-plugin fixture) | External reference plugin fixture under E8 slice. |
| reference/scenario-example.yaml | none | **NEW** (specimen of the scenario.yaml shape) | EXECUTABLE_SCENARIO_CORPUS spec example. |

## Taxonomies (criterion 3)

- Pack `T0..T6` harness-fidelity levels → **HF0..HF6** (Harness Fidelity). See `design.md` table.
- Canonical LFC-2 `T0..T4` items (lfc-2-honest-dsl-closure) are **not renamed**.

## Discards / explicitly NOT adopted as-is

- No literal `ADR-LFC-0xx` / `SPEC-LFC-0xx` files become canonical; they are input only.
- No pack-specific UAT/test-harness taxonomy `T0..T6` (collision); replaced by HF.
- No separate "step constitution" roadmap in the pack that duplicates canonical LFC roadmap; the
  slice order is merged as content into the canonical LFC-2 gate and backlog.
- No parallel AGENTS/spec authority: AGENTS.md remains operative translation; ADR/spec are the
  architectural authority.
