# Test-harness catalogue (GENERATED — do not hand-edit)

Regenerate with `python3 scripts/gen-test-harness-catalogue.py`; `just fitness` fails when it drifts.

Schema: `pipelinek-test-harness-catalogue-1` · home: `v2/test-harness/`

## Roles (one authority each: this is what refuses a second engine)

| Role | Authority | Owns |
|---|---|---|
| ContractCatalogue | `scripts/gen-test-harness-catalogue.py` | the catalogue itself: which modules, gates, suites and roles exist |
| ImpactResolver | `scripts/plan-affected-tests.py` | file -> test plan (the only impact mapping) |
| SuitePolicy | `scripts/fitness-harness-coverage.py` | tool <-> suite registry: what must have a suite and what a suite needs |
| Dispatch | `justfile` | the gate ladder and the only place that spawns Gradle |
| Gate | `scripts/run-integration-gate.py` | typed PASS|FAIL|BLOCKED|STALE receipt for a gate run |
| EvidenceReader | `scripts/run-scoped-junit-report.py` | fresh-vs-carried-over JUnit adjudication (skips, zero tests, head) |
| BaselineMeter | `scripts/measure-harness-baseline.py` | reproducible wall/Gradle/profile samples and freshness of the XML |
| CandidateReceipt | `scripts/emit-candidate-receipt.py` | candidate identity: bytes, digests, product-identity law |
| VerdictConsumer | `scripts/consult-harness-verdict.py` | external harness verdict consumption under a committed publisher authority |

## Gates

| Level | Recipe | Budget (s) | Gradle | Graph via | Runs |
|---|---|---|---|---|---|
| L-DEV | `just dev` | 900 | 1 | direct | affected module tests + affected python suites, one incremental Gradle graph, UAT excluded |
| L-MERGE | `just integration` | 1800 | 0 | `run-integration-gate.py` | fitness + affected suites + lint/ABI + one Gradle graph, typed verdict |
| L-ROUND | `just gate` | 1270 | 1 | direct | full `check` with the UAT battery excluded |
| L-ROUND-ESCALATED | `just gate-escalate` | 1270 | 1 | direct | `check --rerun-tasks`: escalation after a killed run |
| HARNESS | `just harness` | 900 | 0 | none | every hermetic python suite under scripts/ |
| L-RC | `just rc` | 2400 | 1 | direct | releaseGate: distZip + SBOM + candidateAdmission + architecture/application/release suites, UAT excluded; declared budget pending the first complete run |

## Modules with production code

| Module | Impact (Gradle) | Fork policy | Justification |
|---|---|---|---|
| `v2/buildSrc` | `check` | default | — |
| `v2/pipeline-application` | `:pipeline-application:test` | forkEvery=1, maxParallelForks=1 | forkEvery = 1 is deliberate while the installed-CLI UAT wedge has no structural fix (PK-0-57-RC1, recorded in .agent/TESTING-STATE.md); the module's UAT classes are excluded from local gates via shard.excludes, so the fork cost is paid only by the harness |
| `v2/pipeline-artefacts-local` | `:pipeline-artefacts-local:test` | default | — |
| `v2/pipeline-binding-factory` | `:pipeline-binding-factory:test` | default | — |
| `v2/pipeline-credentials-api` | `:pipeline-credentials-api:test` | default | — |
| `v2/pipeline-credentials-executor` | `:pipeline-credentials-executor:test` | default | — |
| `v2/pipeline-credentials-local` | `:pipeline-credentials-local:test` | default | — |
| `v2/pipeline-credentials-multipart` | `:pipeline-credentials-multipart:test` | default | — |
| `v2/pipeline-domain` | `:pipeline-domain:test` | default | — |
| `v2/pipeline-event-harness` | `:pipeline-event-harness:test` | default | — |
| `v2/pipeline-events` | `:pipeline-architecture-tests:test`, `:pipeline-events:test` | default | — |
| `v2/pipeline-events-store` | `:pipeline-events-store:test` | default | — |
| `v2/pipeline-output` | `:pipeline-output:test` | default | — |
| `v2/pipeline-output-store` | `:pipeline-output-store:test` | default | — |
| `v2/pipeline-protocol` | `:pipeline-protocol:test` | default | — |
| `v2/pipeline-release` | `:pipeline-release:test` | default | — |
| `v2/pipeline-runtime` | `:pipeline-runtime:test` | default | — |
| `v2/pipeline-runtime-api` | `:pipeline-runtime-api:test` | default | — |
| `v2/pipeline-scripting-api` | `:pipeline-scripting-api:test` | default | — |
| `v2/pipeline-scripting-kotlin24` | `:pipeline-scripting-kotlin24:test` | default | — |
| `v2/pipeline-testkit` | `:pipeline-testkit:test` | default | — |
| `v2/pipeline-step-sdk/api` | `:pipeline-application:test`, `:pipeline-step-sdk:api:test` | default | — |
| `v2/pipeline-step-sdk/files` | `:pipeline-application:test`, `:pipeline-step-sdk:files:test` | default | — |
| `v2/pipeline-step-sdk/http` | `:pipeline-application:test`, `:pipeline-step-sdk:http:test` | default | — |
| `v2/pipeline-step-sdk/junit` | `:pipeline-application:test`, `:pipeline-step-sdk:junit:test` | default | — |
| `v2/pipeline-step-sdk/runtime` | `:pipeline-application:test`, `:pipeline-step-sdk:runtime:test` | default | — |
| `v2/pipeline-step-sdk/scm-git` | `:pipeline-application:test`, `:pipeline-step-sdk:scm-git:test` | default | — |
| `v2/pipeline-step-sdk/utilities` | `:pipeline-application:test`, `:pipeline-step-sdk:utilities:test` | default | — |
| `v2/pipeline-step-sdk/workflow-control` | `:pipeline-application:test`, `:pipeline-step-sdk:workflow-control:test` | default | — |

## Suites

- python suites: **22** (366 `def test_` bodies)
- Kotlin modules with `src/test`: **30**

## UAT frontier

- excludes: `*UatLocal*, *UatCompat*, *UatDsl*, *UatStep*, *UatEvt*, *UatDurable*, *Lpr011*, *PublishHtml*, *StashOps*`
- declared in: `justfile:uat_excludes`

## Policies (index, not a copy)

| Topic | Authority |
|---|---|
| test authoring, hang protocol, budgets | `AGENTS.md §V2 TESTING RULES` |
| gate ladder and what each level certifies | `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md §4` |
| step certification states | `docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md` |
| harness fidelity levels (HF0..HF6), proposed | `docs/v2/04-adrs/ADR-0072-layered-test-harness-fidelity.md` |
| candidate/release boundary with the external harness | `docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md §5.4` |
| responsibility migration catalogue (UAT exodus) | `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` |

## Receipts

- root: `docs/v2/07-uat/`
- candidate evidence: `docs/v2/07-uat/evidence/`
