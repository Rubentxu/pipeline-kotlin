# E1.2 checkpoint receipt — full archive→query data integration

**Cycle**: `cycle/e1-ecosystem-local-first`
**Branch HEAD (cycle)**: `939dd22aebc06b6f93834e328c8879d902ed77d9`
**Base**: `7f4ab469`
**E1.1 receipt (prior checkpoint)**: `00417852`
**Receipt written**: 2026-09-20T10:47Z
**Receipt author**: orchestrator (mode `on`, autonomy per operator table)

## Outcome

**E1.2 = CHECKPOINT_REACHED** — full archive→query data path wired
through the canonical runtime context. The DSL facade for
`archiveArtifacts(name=…)` is published, the producer side records
typed handles into a per-run `InMemoryArtifactIndex`, and the consumer
side resolves them by name with typed Success/NotFound outcomes.

End-to-end evidence: corpus fixture 30 runs the real installed binary,
exercises sh → archive(name="mix") → artifactQuery("mix") GREEN with
`RunFinished outcome=success`.

## What landed (3 task slices, 3 commits)

| ID   | Commit    | Layer                                                                  | L2 evidence                                                |
|------|-----------|------------------------------------------------------------------------|------------------------------------------------------------|
| T1   | `55c23992` | Runtime wiring: ArtifactIndexAdapter + CanonicalRuntimeCapabilityAccess + ExecutionBoundaryFactory + RegistryExecutionBoundary + CanonicalDurableRunCoordinator | All existing tests still GREEN; backward-compat re-verified |
| T2   | `46acf0bc` | DSL surface: `archiveArtifacts(name = ...)` + compiler emission + 2 DSL tests | `PipelineDslTopStepsTest`: 16/0/0 (14→16) |
| T3   | `939dd22a` | Production wire-up: Main.runCanonicalPipeline passes ArtifactIndexAdapter.build() into the coordinator + corpus fixture 30 upgraded to E2E | `CompatibilityCorpusTest.fixture30ArtifactQueryBridge`: 4.86s GREEN; F1 regression 27/0/0 |

Total cycle commits: 17 (E1.0 + E1.1 (9) + E1.2 (3) — delta from receipt: 3 commits).

## Reference implementation consulted

Per AGENTS.md "Reference Implementation Research" law:

- **Reference**: Jenkins `archiveArtifacts` pipeline-step (Jenkins `core.ArchiveArtifacts`).
- **Behaviour adopted**: producer/consumer typed interface for the artifact index; archive
  records handle on success; query looks up by name; typed USER failure on NotFound.
- **Intentional deviations**: no server-side storage backing; in-memory index scoped to the run.
  The filesystem remains the durable record of the archived files; the index is a derived
  projection (per the SPI KDoc).
- **Security implications reviewed**: n/a — no deserialisation, no remote storage, no credential
  path. Capability admission is fail-closed.
- **Tests demonstrating contract**: `CoreArtifactQueryStepContractTest` rows
  "archive with name then query roundtrip via a shared index" (cross-step integration at unit
  level); `CompatibilityCorpusTest.fixture30ArtifactQueryBridge` (E2E through the installed
  distribution with the real coordinator).

## Scope firewall audit (per AGENTS.md V2 DEVELOPMENT PRIME DIRECTIVE)

| Concern                                                  | Status      |
|-----------------------------------------------------------|-------------|
| F1 contract sha256 unchanged                              | ✓ unchanged |
| F2 trigger activation                                     | ✗ not activated |
| `core.sh` semantics change                                | ✗ none     |
| `core.archiveArtifacts` semantics change                  | ✗ **byte-shape preserved** when `name=null`; new optional `name=` field is backward-compat (no legacy fixture affected — all 28 + 30 GREEN). |
| Production source touched outside documented E1 surface   | ✗ none (1 new adapter file + 5 wire-up sites only) |
| Engine exception path introduced for plugin semantics      | ✗ none (capability-routed, fail-closed) |
| Remote storage / network introduced                       | ✗ none (in-memory) |
| Contract test failures in unrelated modules                | ✗ none |

## Byte-shape preserved evidence (backward-compat)

The 28 existing corpus fixtures use the legacy `archiveArtifacts(artifacts = "...")` form
without `name=`. None of them re-encoded. The DSL emits the `name` key in the JSON envelope
ONLY when the user supplied a non-null `name`. The handler's codec (E1.1 / T4) already had
the symmetric encode/decode for the optional field. Net effect on legacy call-sites: zero.

## Counter (project dashboard, post-E1.2)

```text
Certified Steps:               N=18 (17 pre-E1.2 + 1 new = core.artifact.query REGISTERED_PRIMARY)
Legacy executable Steps:       M=16  (where N+M = |LEGACY_PLUGIN_IDS| + external plugin count = 18+16=34)
Registry-primary Steps:        N=18 (incl. the new core.artifact.query + the now-wired core.archiveArtifacts)
```

## Canary sha256 (XML evidence files)

```text
TEST-*ArtifactQuery*.xml      sha256=747dc3b0…cfb7bf  tests=13 f=0 e=0
TEST-*CoreArchiveContract*.xml sha256=4a3f90a0…f169c  tests=27 f=0 e=0
TEST-*CoreArchiveUnit*.xml     sha256=21d35254…08d28  tests=24 f=0 e=3
TEST-*TopSteps*.xml           sha256=18838513…43643  tests=16 f=0 e=0
TEST-*CompatibilityCorpus*.xml sha256=5cf2f17b…8d2df  tests=2 f=0 e=0 (fixture30 + discoverability)
pipeline-application-0.39.0.jar sha256=442a0ea3…e874 (binary distribution artifact)
```

(F1 contract sha256 unchanged: `abc8f5bed09f109205a4b7451a801eee272685f544ed973b19b6e65d6d076f7b`.)

## What did NOT land in E1.2 (deliberate scope deferrals)

| Item                                              | Deferral reason                                          |
|---------------------------------------------------|----------------------------------------------------------|
| Persistent artifact index across runs             | Index is in-memory per-run; filesystem is durable record. Persistence across runs requires process-level serialisation (out of E1 cycle scope — local-first). |
| `core.artifact.list` (ls-like, view all handles)  | Future Step, not on E1 roadmap.                         |
| Native DSL discovery of `name` on `artifactQuery` from prior `archiveArtifacts` (typed binding) | Bridge is positional / by-name today; type-bound variable is a separate ergonomic layer (out of E1 scope). |
| `examples/e1-ecosystem-demo`                       | E1.3 deliverable.                                        |

## Siguiente paso

E1.2 cerrado → E1.3 (UAT integral with `examples/e1-ecosystem-demo`,
evidence, L5, merge+tag at cycle closure).

**Operador**: no se requiere approval intermedio. E1.3 empieza con el
mismo autonomy per operator table.
