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

## Phase C — Local-first Step ecosystem expansion (after B17 + EVT-3)

This phase is the product-priority continuation defined by:

- `docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`;
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md`;
- `docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md`.

EVT-4/M4 remote/controller work is deliberately deferred until the local-first feature freeze.

### C0 — Existing family certification (P0)

- [ ] certify primitives `error`, `sleep`;
- [ ] certify control `retry`, `timeout`, `catchError`, `warnError`, `unstable`, `parallel`;
- [ ] certify context/workspace `dir`, `withEnv`, `withCredentials`, `pwd`, `isUnix`, `readFile`, `writeFile`, `fileExists`, cleanup;
- [ ] classify/certify `archiveArtifacts`, `timestamps`, `ansiColor`, `milestone`, `waitUntil`, `load`;
- [ ] certify existing Git/checkout through plugin delivery policy;
- [ ] remove/forbid every alternate legacy executable path for certified Steps.

Gate: exact certified inventory + real examples + EVT-3 Event Harness contracts, no disabled mandatory UAT counted as green.

### C1 — Universal core freeze

- [ ] resolve every P0 `CORE` candidate in the ecosystem matrix;
- [ ] document evidence for promotion/retention in core;
- [ ] default every non-universal new family to `OFFICIAL_PLUGIN`;
- [ ] add fitness preventing plugin-name-specific cases in coordinator/central dispatcher.

Gate: core surface intentionally bounded; future growth requires core-admission evidence.

### C2 — Utilities official plugin

- [ ] JSON/YAML/TOML/properties structured-data steps;
- [ ] findFiles/file utilities;
- [ ] checksums;
- [ ] zip/tar archive utilities;
- [ ] typed native results + Jenkins-compatible façades where useful.

Gate: filesystem + typed-value plugin works with zero Step-specific core edits.

### C3 — Testing/reporting official plugins

- [ ] `junit` typed report;
- [ ] local HTML/report publication;
- [ ] coverage adapters;
- [ ] typed issue/static-analysis reports if evidence justifies them.

Gate: structured result/events/artifacts plugin works with zero Step-specific core edits.

### C4 — Artifacts/stash official plugin

- [ ] complete/certify `archiveArtifacts` in its final delivery location;
- [ ] `stash` / `unstash`;
- [ ] local `copyArtifacts` based on RunId/ResourceRef.

Gate: durable artifact data movement and replay contracts green.

### C5 — Toolchain/config official plugins

- [ ] Maven/Java;
- [ ] Gradle;
- [ ] Node/npm/pnpm/yarn;
- [ ] Python/pip/Poetry;
- [ ] .NET/Go as P2;
- [ ] scoped configuration provider.

Gate: at least Java+Maven/Gradle and Node or Python real repo builds pass through plugin APIs.

### C6 — Network/SSH/notification plugins

- [ ] typed `httpRequest`;
- [ ] SSH agent/selected SSH operations;
- [ ] notification compatibility surfaces where useful.

Gate: network + credentials + typed failure + secret-safe output demonstrated without core exceptions.

### C7 — Local coordination/interaction plugins

- [ ] local durable `lock`;
- [ ] local durable/manual `input`.

Gate: restart/recovery behavior characterized; no controller UI dependency.

### C8 — Local containers official plugin

- [ ] provider-neutral container runtime capability;
- [ ] Docker/Podman pull/build/push;
- [ ] `inside`-like nested scope;
- [ ] side-service/withRun lifecycle;
- [ ] registry credentials.

Gate: real local container pipeline works; no Kubernetes worker semantics added.

### C9 — Advanced Git official plugin

- [ ] `readScmFile`;
- [ ] shallow/depth;
- [ ] submodules;
- [ ] refspec/prune/tags;
- [ ] sparse/LFS where justified.

Gate: real advanced checkout fixtures, credential safety and replay/idempotency green.

### C10 — Complex external reference plugin

- [ ] choose Artifactory/Xray or an equivalently demanding vendor integration;
- [ ] implement a bounded real surface (e.g. upload/download/build-info/promote/scan);
- [ ] package/register as a true external plugin artifact;
- [ ] pass the same PluginContractSuite and real distribution UAT;
- [ ] verify zero Step-specific production core edits.

Gate: SDK proven beyond toy `example.uppercase` against network + credentials + artifacts + vendor failures.

### Phase-C exit — local-first feature freeze

- [ ] all P0 matrix rows resolved/certified/rejected with evidence;
- [ ] representative P1 plugin families run end-to-end locally;
- [ ] checkout → build → test → report → artifact real pipeline green;
- [ ] representative HTTP/network and container flow green;
- [ ] complex external reference plugin CERTIFIED;
- [ ] Event Harness contracts own reusable acceptance assertions;
- [ ] no hidden controller/remote-worker dependency;
- [ ] only after this gate may EVT-4 become the next product-priority evolution.
