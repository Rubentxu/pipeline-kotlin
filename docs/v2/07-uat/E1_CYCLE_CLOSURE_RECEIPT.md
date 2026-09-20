# E1 cycle closure receipt — `e1-ecosystem-local-first`

**Cycle**: `cycle/e1-ecosystem-local-first`
**Base**: `7f4ab469`
**Tag**: `e1-ecosystem-local-first` (CHECKPOINT auditado, NO semver)
**Merge commit (HEAD on main)**: `f66492ac`
**Tag SHA on origin**: `d71e06f86c3f15d97427259c8601e816af21f954`
**Author**: orchestrator (mode `on`, autonomy per operator instruction)

## Outcome

**E1 = CLOSED_GREEN** — 22 commits, 4 checkpoints (E1.0, E1.1, E1.2, E1.3)
all GREEN at the L2 / L4 evidence level. Merged to `main`. Tag
`e1-ecosystem-local-first` published on `origin`.

Each checkpoint has its own receipt file:

| Checkpoint | Receipt                                              | State    |
|------------|------------------------------------------------------|----------|
| E1.0       | `docs/v2/07-uat/E1.0_RECEIPT.md` (pre-cycle)         | Closed   |
| E1.1       | `docs/v2/07-uat/E1_1_CHECKPOINT_RECEIPT.md`           | Checked  |
| E1.2       | `docs/v2/07-uat/E1_2_CHECKPOINT_RECEIPT.md`           | Checked  |
| E1.3       | `examples/e1-ecosystem-demo/README.md` (3 fixtures)  | Closed   |

## Cycle deliverables

| Layer                                                | Status |
|------------------------------------------------------|--------|
| Domain ADTs (`ArtifactHandle`, `ArtifactIndexCapability` SPI) | DONE |
| Domain parser (`JunitParser` stdlib XML)             | DONE (15 unit tests GREEN, 1 worked RED documented) |
| Production adapter `InMemoryArtifactIndex`           | DONE |
| `core.artifact.query` Step (registry-primary)        | DONE (CERTIFIED at G8-equivalent: contract 13/0/0, compatibility 30 GREEN, demos GREEN) |
| DSL façade `artifactQuery(name)`                     | DONE (sealed hierarchy 30 variants; byte-shape canonical) |
| DSL façade `archiveArtifacts(name=…)`                | DONE (backward-compat: no `name` field when null) |
| Runtime wiring (ArtifactIndexAdapter → capability bridge → coordinator → Main) | DONE |
| Corpus fixture 30 (`30-artifact-query-bridge.pipeline.kts`) | DONE (E2E GREEN) |
| Examples demo (`examples/e1-ecosystem-demo`)         | DONE (3 fixtures: archive-with-name, query-fresh-run, full-bridge) |
| Backward-compat (F1 archive contract + DslCompiledPipelineCompiler emission) | PRESERVED |

## Scope firewall audit (final)

| Concern                                                       | Status |
|---------------------------------------------------------------|--------|
| F1 contract sha256 unchanged                                   | ✓ unchanged (abc8f5bed09f1092…ef7b) |
| F2 trigger activation                                         | ✗ not activated |
| `core.sh` semantics change                                    | ✗ none |
| `core.archiveArtifacts` semantics change                      | ✗ none (only optional `name` field added, byte-shape preserved for legacy callers) |
| Production source touched outside documented E1 surface        | ✗ none (1 new adapter file + 5 wire-up sites only) |
| Engine exception path introduced for plugin semantics          | ✗ none (capability-routed, fail-closed) |
| Remote storage / network introduced                           | ✗ none (in-memory index) |
| Contract test failures in unrelated modules                    | ✗ none (only sealed-hierarchy count 29→30 + BlockStepFlattener terminal leaf +1, all intentional) |

## Counter (project dashboard, post-E1)

```text
Certified Steps:               N=18 (17 pre-cycle + 1 new = core.artifact.query)
Legacy executable Steps:       M=16  (N+M = 18+16 = |LEGACY_PLUGIN_IDS| + external plugin count = 34)
Registry-primary Steps:        N=18 (incl. the new core.artifact.query + the now-wired core.archiveArtifacts)
```

## Evidence canary sha256 (post-merge)

XML artifacts for the canonical Step family (refreshed just before merge):
```text
CompatibilityCorpusTest                : 2 / 0 / 0
CoreArchiveArtifactsStepContractSuite  : 27 / 0 / 0  (F1 contract preserved)
CoreArchiveArtifactsStepUnit           : 24 / 0 / 3  (F1 unit, pre-existing skips)
CoreArtifactQueryStepContract          : 13 / 0 / 0  (E1.1 / T6)
```

DSL surface:
```text
PipelineDslTopStepsTest                : 16 / 0 / 0  (14 → 16 with E1.2 / T2)
PipelineDslSealedHierarchyTest         : 1  / 0 / 0  (29 → 30 variants)
```

End-to-end demos (`examples/e1-ecosystem-demo/01..03.pipeline.kts`):
```text
01 archive-with-name         : Pipeline finished with SUCCESS
                                ArtifactArchived event emits 2 typed files
                                with sha256 + size + relPath
02 query-by-name (fresh run) : Pipeline finished with FAILURE
                                failureKind=USER message=NotFound (typed)
03 full-bridge (one run)     : Pipeline finished with SUCCESS
                                EchoOutputCaptured content=E1_DEMO_BRIDGE_OK
```

## Reference implementation consulted

Per AGENTS.md "Reference Implementation Research" law:

- **Reference**: Jenkins `archiveArtifacts` pipeline-step (catalog §1.1 line 45).
- **Behaviour adopted**: producer/consumer typed interface for the artifact index;
  archive records handle on success; query looks up by name; typed USER failure on NotFound;
  separate `consoleTranscript` stream vs typed `capturedStdout`.
- **Intentional deviations**: no server-side storage backing; in-memory index scoped to
  the run. The filesystem remains the durable record of the archived files; the index
  is a derived projection (per the SPI KDoc).
- **Security implications reviewed**: n/a — no deserialisation, no remote storage, no
  credential path. Capability admission is fail-closed at all bridges.
- **Tests demonstrating contract**: `CoreArtifactQueryStepContractTest` rows
  (identity, completeness, descriptor, codec, envelope, registry, capability admission,
  success, typed failure, fresh durable, replay, divergence, observability, missing
  capability, architecture fitness, real DSL); `CompatibilityCorpusTest.fixture30`;
  `examples/e1-ecosystem-demo/*` (real-binary runs).

## Siguiente paso

Continue with the LPR roadmap (LPR-0 → LPR-9 → LPR-GATE-1) under the
`modo auto con completion-guard y resolución profunda de bloqueos` per
operator authorization. Next cycle intake will pick up at LPR-0
(Architecture Truth + CI + benchmark baseline).

**Operador**: no se requiere approval intermedio. La autonomía
configurada cubre hasta cierre del roadmap completo.
