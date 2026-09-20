# E1.ecosystem-local-first — Tasks

Cycle: `cycle/e1-ecosystem-local-first`
Companion to: `proposal.md`, `spec.md`, `design.md`

Four checkpoints, single cycle. Each checkpoint produces one or
more commits and an inline receipt. The cycle closes with a
single closure receipt.

Conventions per AGENTS.md:

- One commit per task with `--rerun-tasks` evidence.
- L0 compile → L1 individual test → L2 class-level → L4 module
  suite only as justified.
- L5 (`./gradlew -p v2 check`) only at cycle end (rule 23 + scope
  firewall; we DO touch production under `src/main`, so an L5 at
  cycle end is justified to ensure no regression).

---

## Checkpoint E1.0 — Inventory + scope + UAT definition

**Goal.** Freeze the scope, the UAT plan, and the inventory delta
before any code changes.

| ID | Task | Outcome |
|---|---|---|
| E1.0.T1 | Write `docs/v2/07-uat/E1_ECOSYSTEM_LOCAL_FIRST_UAT_PLAN.md` defining the S1..S11 UAT scenarios as YAML for the UAT dashboard. | UAT plan published. |
| E1.0.T2 | Write `docs/v2/07-uat/E1_INVENTORY.md` capturing the pre-cycle state of `core.*` steps and the gap that E1 closes. Inventory sha256-anchored. | Inventory published; gap declared. |
| E1.0.T3 | Decision receipt `E1.0_RECEIPT.md` enumerating: scope freeze, UAT plan, inventory, the four checkpoints, and the cycle's closure criteria. | E1.0 closed. |

Commit: `E1.0 — inventory + UAT plan + decision receipt (no code changes yet)`

---

## Checkpoint E1.1 — `core.junit` plugin

**Goal.** Implement `core.junit` via the public SDK with codec,
contract, capability, handler, fixtures, and contract suite.

| ID | Task | Outcome |
|---|---|---|
| E1.1.T1 | Define the typed `JUnitReport` + `JunitTotals` + `FailingCase` + `JunitSuiteSummary` ADTs in `pipeline-domain`. | ADTs added; compile green. |
| E1.1.T2 | Implement the Ant/Maven JUnit XML parser (no external lib; in-house, fail-closed on unknown elements). | Parser unit-tested. |
| E1.1.T3 | Define `JUNIT_REPORT_CAPABILITY` SPI + in-app adapter. | Capability registered. |
| E1.1.T4 | Define `JunitInputCodec` + `JunitReportCodec` (typed envelope round-trip). | Codecs unit-tested. |
| E1.1.T5 | Implement `CoreJunitStep.registerInto` + handler with typed result algebra; declare contract + descriptor. | Step registered. |
| E1.1.T6 | Add `JUnitReadStarted/Suite/Completed/Failed` events. | Events emitted. |
| E1.1.T7 | Author `CoreJunitStepContractSuiteTest` (registry-resolved, capability admission, all four failure modes + happy path, replay). | Contract suite green. |
| E1.1.T8 | Add `core.junit` DSL extension in `pipeline-scripting-kotlin24` lowering only to `registryStep`. | DSL façade. |
| E1.1.T9 | Add a corpus fixture `27-junit-read.pipeline.kts` (happy) + `28-junit-missing.pipeline.kts` (negative) + `29-junit-malformed.pipeline.kts` (negative). | Corpus fixtures; `CompatibilityCorpusTest` green. |
| E1.1.T10 | Checkpoint receipt `E1.1_RECEIPT.md` listing contracts, sha256s, registry state. | E1.1 closed. |

Commit sequence: T1..T10 (each its own commit, each with
`--rerun-tasks` evidence). Cycle state stays green throughout.

---

## Checkpoint E1.2 — `core.artifact.query` + index bridge

**Goal.** Bridge `core.archiveArtifacts` to a derived query path so
the pipeline can ask "where is the JAR?" by name. No remote
backend, no new protocol.

| ID | Task | Outcome |
|---|---|---|
| E1.2.T1 | Define `ArtifactHandle` + `ArtifactIndexCapability` SPI. | SPI in domain. |
| E1.2.T2 | Implement the in-app index adapter (file-backed JSON at `~/.pipelinek/artifacts-index.json` or similar). | Adapter unit-tested. |
| E1.2.T3 | Hook the adapter into `CoreArchiveArtifactsStep` so each successful archive writes a record. | Bridge code; archive step contract test still green. |
| E1.2.T4 | Define `ArtifactQueryInputCodec` + `ArtifactHandleCodec`. | Codecs unit-tested. |
| E1.2.T5 | Implement `CoreArtifactQueryStep.registerInto` + handler; declare contract + descriptor. | Step registered. |
| E1.2.T6 | Add `ArtifactQueried`/`ArtifactQueryFailed` events. | Events emitted. |
| E1.2.T7 | Author `CoreArtifactQueryStepContractSuiteTest` (happy + NOT_FOUND + capability missing + replay). | Contract suite green. |
| E1.2.T8 | Add `core.artifactQuery` DSL extension. | DSL façade. |
| E1.2.T9 | Add corpus fixtures `30-artifact-query-happy.pipeline.kts` + `31-artifact-query-missing.pipeline.kts`. | Corpus fixtures. |
| E1.2.T10 | Checkpoint receipt `E1.2_RECEIPT.md` listing the bridge's invariants (filesystem remains durable record; index is derived). | E1.2 closed. |

Commit sequence: T1..T10.

---

## Checkpoint E1.3 — UAT integral + cycle closure

**Goal.** End-to-end demonstration from a clean install with all
four failure modes + resume interaction captured.

| ID | Task | Outcome |
|---|---|---|
| E1.3.T1 | Author `examples/e1-ecosystem-demo/` (a small Gradle project producing `build/libs/demo.jar` and `build/reports/tests/test/TEST-*.xml`). | Demo fixture. |
| E1.3.T2 | Author `examples/e1-ecosystem-demo/demo.pipeline.kts` exercising the full chain. | Pipeline script. |
| E1.3.T3 | Run the demo from a freshly-built distribution; capture stdout/stderr + sha256s in `examples/e1-ecosystem-demo/EVIDENCE.md`. | Evidence captured. |
| E1.3.T4 | Author failure-mode fixtures: `demo-broken.pipeline.kts` (compile fail), `demo-failing-tests.pipeline.kts` (test fail), `demo-no-report.pipeline.kts` (missing JUnit). Capture each in `EVIDENCE.md`. | Failure-mode evidence captured. |
| E1.3.T5 | Author resume fixture: kill mid-pipeline, rerun with same `--db` + `--control-root`, capture the replay behaviour (cached JUnitReport, no second parse). | Resume evidence captured. |
| E1.3.T6 | Update `docs/v2/05-roadmap/LOCAL_PRODUCTION_READY_ROADMAP.md` to mark E1 as done and E2..E10 as deferred (per `ROADMAP_MIGRATION_LPR`). | Roadmap pointer updated. |
| E1.3.T7 | Author `docs/v2/07-uat/E1_ECOSYSTEM_LOCAL_FIRST_CLOSURE_RECEIPT.md` with: cycle commits, sha256 anchors, inventory delta, F1/F2 status unchanged, release tag. | Closure receipt. |
| E1.3.T8 | Final L5 (`./gradlew -p v2 check`) just to lock no regression. | L5 green. |
| E1.3.T9 | Tag `e1-ecosystem-local-first` on the cycle's tip. | Tag created. |

Commit: T1..T9 (each its own commit with `--rerun-tasks`
evidence where applicable; L5 evidence captured in T8 commit).

---

## Cycle exit gate

Cycle closes green when:

- All four checkpoints closed (each with its own receipt).
- Cycle closure receipt at `E1_ECOSYSTEM_LOCAL_FIRST_CLOSURE_RECEIPT.md` with full sha256 table.
- F1 contract unchanged (`SH_VAR_SCOPE_CONTRACT.md` sha256-stable); F2 status unchanged.
- L5 final run green.
- Tag `e1-ecosystem-local-first` created.

## Forbidden (per AGENTS.md + operator table)

- Modify F1 contract or open F2 without trigger.
- Touch the engine or coordinator to add a `core.junit`-specific exception.
- Introduce remote storage or new protocols.
- Modify `core.sh` or `core.archiveArtifacts` semantics.
- Alter historical receipts / releases / tagged SHAs.
- Continue past E1.3 if the integral goal becomes infeasible inside the limits.
