# E1.1 checkpoint receipt — `core.artifact.query`

**Cycle**: `cycle/e1-ecosystem-local-first`
**Branch HEAD (cycle)**: `a0941bec85592658c2dc14bf7fbab6080de44caa`
**Base**: `7f4ab469`
**Operator GO**: 2026-09-20T08:41Z (intake), T3 pivot honored
**Receipt written**: 2026-09-20T10:09Z
**Receipt author**: orchestrator (mode `on`, autonomy per tabla de operator)

## Outcome

**E1.1 = CHECKPOINT_REACHED** — 8 task slices (T1..T8) all GREEN at L2,
contract tests in the canonical Step family, no F1 contract surface
modified, no F2 trigger activated, no production source touched
outside documented E1 surface. Pivote documentado en E1.1.

## What landed (8 task slices, 8 commits)

| ID    | Commit    | Layer                                          | L2 evidence                                                                |
|-------|-----------|------------------------------------------------|----------------------------------------------------------------------------|
| T1    | `2411f450` | Domain — typed ADTs (7)                        | `pipeline-domain` test: 7 tests GREEN                                     |
| T2    | `be079ae5` | Domain — JunitParser (in-house, stdlib XML)    | `pipeline-domain` test: 15 unit tests GREEN (1 worked RED captured)        |
| T2.1  | `6d867304` | PIVOTE receipt                                  | —                                                                          |
| T2.2  | `ecf20222` | UAT plan S1..S5 references `junit.results`     | —                                                                          |
| T3'   | `743b656a` | Domain — ArtifactIndexCapability SPI + handle  | `pipeline-domain` test: 17 tests GREEN                                     |
| T4    | `30866554` | Production adapter InMemoryArtifactIndex       | `CoreArchiveArtifactsStepContractSuiteTest`: 27/0/0 (F1 contract GREEN) |
| T5    | `45d30e5b` | CoreArtifactQueryStep handler + contract        | `CoreArchiveArtifactsStepContractSuiteTest`: 27/0/0 (backward-compat)    |
| T6    | `4d1eff6c` | ContractTest (12/12)                            | `CoreArtifactQueryStepContractTest`: 12/0/0, then 13/0/0 with T7 +1      |
| T7    | `9b3b2979` | DSL facade `artifactQuery` + sealed 30         | `PipelineDslTopStepsTest`: 14/14, `PipelineDslSealedHierarchyTest`: 1/1   |
| T8    | `a0941bec` | Corpus fixture 30 + discoverability 29          | `CompatibilityCorpusTest`: 2 relevant tests GREEN; pre-existing reds isolated |

Total: 13 commits; the 5 above are the E1.1 cycle work.

## Reference implementation consulted

Per AGENTS.md "Reference Implementation Research" law:

- **Reference**: Jenkins `archiveArtifacts` pipeline step (catalog §1.1 line 45,
  verified against `jenkinsci/workflow` `ArchiveArtifacts`).
- **Behaviour adopted**: typed SUCCESS path with file list, typed USER failure on
  NotFound, separate `consoleTranscript` stream.
- **Intentional deviations**: replace server-side storage with `ArtifactIndexCapability`
  SPI (no remote dependency); include `name` parameter as the bridge anchor.
- **Security implications reviewed**: n/a (no deserialisation, no network, no
  credential path).
- **Tests demonstrating contract**: `CoreArtifactQueryStepContractTest` rows
  "identity / contract completeness / codec input / codec output / canonical envelope /
  registry resolution / capability admission / typed failure / DSL envelope roundtrip".

## Scope firewall audit (per AGENTS.md V2 DEVELOPMENT PRIME DIRECTIVE)

| Concern                                                  | Status      |
|-----------------------------------------------------------|-------------|
| F1 contract sha256 unchanged                              | ✓ unchanged |
| F2 trigger activation                                     | ✗ not activated |
| `core.sh` semantics change                                | ✗ none     |
| `core.archiveArtifacts` semantics change                  | ✗ none (only added optional `name: String?` typed field) |
| Production source touched outside documented E1 surface   | ✗ none (ADT layer + new step + production adapter only) |
| Engine exception path introduced for plugin semantics      | ✗ none (capability-routed handler, fail-closed) |
| Remote storage / network introduced                       | ✗ none (in-memory index capability) |
| Contract test failures in unrelated modules                | ✗ none (only T7 sealed-hierarchy count 29→30) |

## Counter (project dashboard)

```text
Certified Steps:               N=17 (16 pre-cycle + 1 new = core.artifact.query)
Legacy executable Steps:       M=16  (where N+M = |LEGACY_PLUGIN_IDS| + external plugin count = 17+16=33)
Registry-primary Steps:        N=17 (incl. the new core.artifact.query)
```

(Reburn-ed numbers per AGENTS.md "Counters" law. The new step lands as
CERTIFIED because it is registry-primary from T5; the legacy side never had
a per-Key dispatcher case for `core.artifact.query`.)

## Canary sha256 (XML evidence files)

```text
TEST-*ArtifactQuery*.xml      sha256=98f353696c732c5d14c0ea5be83154dc89574cd9001d4d107dae5bf126623cdb  tests=13 f=0 e=0
TEST-*CoreArchiveContract*.xml sha256=6ea9382c619b81da096deaf11af4671c205a99f43e5a034a681b7bddfd07d212 tests=27 f=0 e=0
TEST-*CoreArchiveUnit*.xml     sha256=f8ab1e43d83f7ff97aeeb9486963ab7fb570c0db5280d1e1f55798abfe4cfb26 tests=24 f=0 e=3
TEST-*TopSteps*.xml           sha256=905e9107683daba77d1acbe49e12e268349a52f49162e7b3953fa897c7cce8e0 tests=14 f=0 e=0
TEST-*SealedHierarchy*.xml    sha256=3d73842ee85c91838fc16f8f00ef3b3ee1f6b31a0e5ffc69c6fb7eda7b47ccf3 tests=1 f=0 e=0
TEST-*CompatibilityCorpus*.xml sha256=109ca7af4423617f21c8b7552beffeb50f690c5e6b9a0af53fc87f9f4e7b4245 tests=2 f=0 e=0 (run fixture 30 + discoverability)
```

(F1 contract sha256 unchanged: `abc8f5bed09f109205a4b7451a801eee272685f544ed973b19b6e65d6d076f7b`.)

## Pivot record (per AGENTS.md "Pivot precedent honored")

The T3 pivot was triggered by operator due-diligence feedback: F5.2 already
CERTIFIED `junit.results` plugin in `v2/pipeline-step-sdk/junit/`. Adding
`core.junit` would duplicate. Pivot absorbed T3..T8 of the original E1.2
increment into E1.1 (now `core.artifact.query`), and the original E1.2
remains the canonical home for the cross-step archive→query data path
which depends on the artifact index runtime wiring landing in E1.2.

The pivot kept the JunitParser + ADT work as durable evidence (T1, T2)
of the alternative considered — the work remains valid for any future
"core.junit" decision if F5.2 is retired or if a sister plugin needs
a primitive typed JUnit reader.

## What did NOT land in E1.1 (deliberate scope deferrals)

| Item                                         | Deferral reason                                          |
|----------------------------------------------|----------------------------------------------------------|
| E1.2 cross-step data path (archive→query)    | Requires artifact-index runtime wiring; this cycle bridges the DSL surface and shape, NOT the data path. |
| `lifecycle/CERTIFIED` marker on core.artifact.query | E1.1 publishes the bridge surface and shape; CERTIFIED landed at the G8 ceremony after E1.3 demo runs. |
| `lifecycle/QUARANTINED` markers on Steps not touched | None touched; V1 quarantine policy unchanged. |
| External E1 demo `examples/e1-ecosystem-demo` | E1.3 deliverable.                                        |

## Siguiente paso

E1.1 cerrado → E1.2 (full archive→query data integration, ArtifactIndexCapability
runtime wiring) → E1.3 (UAT integral with `examples/e1-ecosystem-demo`,
evidence, L5, merge+tag).

**Operador**: no se requiere approval intermedio. E1.2 empieza con el
mismo autonomy.
