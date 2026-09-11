# EVT — Event Spine, Verification and Policy evolution

Status: ACTIVE program — EVT-0 CLOSED, EVT-1 CLOSED (cycle evt-1-resource-ref-envelope), EVT-2 CLOSED @ 1b950074, **EVT-3 CLOSED @ df22ff01** (cycle evt-3-event-harness, branch docs/evt-3-event-harness). EVT-4 PENDING (deferred by local-first priority per LFC-2E program); EVT-5 PENDING (deferred, no transport selected).
Placement: after LFC-2/CTX-P foundation, before resuming the distributed/controller path that depends on event semantics.
Maps forward to existing M4 (Protocol/Gateway), M6 (Jenkins UI), M8 (Graph/Provenance) and M9 (policies).

## Sequencing principle

Do not build the final distributed platform first. Each slice must produce visible local value and leave a
compatible seam for the next deployment topology.

## EVT-0 — Grounding and contract freeze

**Goal:** inventory current event production, sequence assignment, storage, event types and all existing real UAT/example assertions, using **d0ccf4b5 / CTX-P4-EX as the immutable behavioral baseline**.

Deliverables:
- CTX-P4-EX baseline receipt/evidence mapped into EVT (`10/10` real examples already GREEN);
- event producer/reachability inventory;
- baseline event catalogue + producers;
- authority statement: Journal != Events;
- ADR-0077..0079 review/acceptance or revisions;
- transport/storage assumptions explicitly classified.

Gate:
- no implementation changes required;
- every proposed port maps to a current call site/use case;
- no speculative generic observer SPI.

## EVT-1 — ResourceRef + PipelineEventEnvelope

**Goal:** freeze identity/envelope semantics without changing execution behavior.

Deliverables:
- ResourceRef typed model and deterministic builders for Pipeline/Run/Stage/Step/Operation;
- EventRef (`source + EventId`);
- PipelineEventEnvelope;
- codecs + versioning policy;
- CloudEvents mapping characterization tests (without mandatory SDK dependency);
- architecture fitness: no ad-hoc resource string construction in event path.

UAT:
- same logical run/stage/step/op produces stable ResourceRef across fresh reconstruction;
- parallel completion order does not change ResourceRefs;
- no execution fingerprint/journal diff from adding identity projection.

## EVT-2 — Event history ports + local durable adapter

**Goal:** keep local historical execution traces while removing SQLite from the architectural contract.

Deliverables:
- EventPublisher/EventHistory/EventTail ports (exact interface shape may evolve);
- adapt existing SQLite implementation behind those ports rather than replacing it;
- per-run cursor/tail semantics;
- ObservationStatus for incomplete/degraded traces;
- query API/CLI minimal enough to inspect a run without parsing console logs.

UAT:
- real installDist pipeline leaves queryable ordered history;
- restart process and re-read same run history;
- history can be filtered by event type and ResourceRef;
- no stdout/stderr chunks stored as DomainEvents.

## EVT-3 — Event Harness POST_RUN + P4-EX migration

**Status: CLOSED @ `df22ff01`** (cycle `evt-3-event-harness`, branch `docs/evt-3-event-harness`)  
**Closure receipt:** `docs/v2/07-uat/EVT_3_CLOSURE_RECEIPT.md`

Implemented facts (not plans): new module
`v2/pipeline-event-harness` (model/verify/codec); typed contract ADT
(`Exactly`/`Never`/`Before`/`TerminalOutcome`, closed `FieldMatch` selectors, no
`Map<String,Any>`); pure deterministic verifier with bounded counterexamples;
YAML v1 codec fail-closed; CLI `pipeline events verify`; HF0 9/9 + real-history
parity/mutation 10/10; FArch020 isolation fitness 4/4; differential parity 07-10
PASSED on real executions (legacy assertions intact, none removed) — 2 consecutive
runs of `examples/run.sh` GREEN (10/10 + 4/4 parity) on 2026-09-11.
**BASELINE DEBT INC-EVT3-1**: durable rerun re-appends a lifecycle skeleton with
restarted sequences (pre-existing append behavior; characterized, unchanged).

**Next downstream:** LFC-2E (local-first Step ecosystem expansion) — Step families
E0..E10 sequenced after EVT-3; LFC-2E0 = certify existing families.

**Goal:** generalize the **already executable** examples into reusable protocol specifications without weakening the d0ccf4b5 gate.

Deliverables:
- universal lifecycle grammar;
- typed constraints (`Exactly`, `Never`, `Before`, `Outcome` minimum);
- YAML/TOML contract codec;
- minimal counterexample reporting;
- import/characterize the existing `examples/run.sh` `EXPECTED_OUTCOME` / `EXPECTED_EXIT` matrix;
- preserve examples 01..10 exactly as real CLI acceptance fixtures;
- migrate the existing 07–10 event assertions into typed scenario contracts (sidecar YAML/TOML may become their declarative representation);
- differential parity gate: existing shell verifier verdict == Event Harness verdict before deleting any old assertion;
- `examples/run.sh` remains GREEN and may delegate to the harness only after parity is demonstrated.

UAT: see `docs/v2/07-uat/UAT_EVT_REAL_EXAMPLES.md`.

Exit:
- baseline `examples/run.sh` remains 10/10 GREEN through installDist;
- contracts validate actual produced histories;
- at least one deliberately mutated trace is rejected (anti-false-green canary).

## EVT-4 — Detached live relay

**Status: PENDING — DEFERRED-BY-LOCAL-FIRST-PRIORITY**  
EVT-4 is NOT the next priority after EVT-3. Per the LFC-2E program (`docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md`), EVT-4 must yield to LFC-2E0..E10 (certify existing Step families, then implement the local-first Step ecosystem). EVT-4 will be reopened after the local-first feature freeze (M4 controller/remote work).

**Goal:** prove live consumers can observe a running pipeline without becoming part of its failure/cancellation/resource domain.

Deliverables:
- live tail/cursor adapter;
- separate relay process proof;
- bounded resource profile;
- consumer health/lag metrics;
- reconnect-from-cursor semantics;
- no controller/Jenkins dependency.

Real UAT:
1. long-enough real example starts Stage A;
2. detached process observes StageStarted **before** RunFinished;
3. kill detached process while run continues;
4. pipeline still finishes with its expected outcome;
5. restart subscriber from last cursor;
6. remaining events arrive without semantic lifecycle duplication.

Resource isolation gate:
- no observer coroutine under coordinator scope;
- no observer network call on execution event append path;
- optional cgroup test on Linux: observer CPU/memory pressure must not cancel execution.

## EVT-5 — CloudEvents adapter + transport spike

**Status: PENDING — DEFERRED** (no transport selected; transport choice gated on M4 controller/remote needs).  
**Goal:** prove interoperability, not select a fashionable broker.

Compare with measured UAT/benchmarks:
- HTTP CloudEvents direct adapter;
- NATS JetStream candidate (live + replay + durable consumer);
- one alternative only if it materially changes trade-offs (e.g. Kafka for controller-scale target).

Measure:
- local publish latency overhead;
- relay throughput/lag;
- reconnect/replay correctness;
- duplicate delivery/dedup semantics;
- operational footprint;
- failure isolation.

Decision gate: select a transport only when M4/controller requirements need one. Local EventLog remains valid even if no broker is selected.

## EVT-6 — M4/M6 handoff

No new controller implementation here. Produce mapping artifacts:
- PipelineEventEnvelope ↔ E5 protocol/events mapping;
- ResourceRef ↔ controller/Jenkins identity mapping;
- event reducer requirements for M6 `event→FlowNode`;
- reconnect semantics consumed by M4 E5-04/E5-05;
- output stream kept separate from event stream.

## POL-0 — Cedar authorization model spike (future)

Depends on EVT-1..3.

- define Cedar schema for PipelineRun, Credential, Environment, Artifact and capability-oriented actions;
- validate example policies;
- no production enforcement;
- prove ResourceRef mapping into Cedar entity IDs.

## POL-1 — Shadow audit

Depends on EVT-4 only for LIVE mode; can start POST_RUN using EVT-3 history.

- pipeline-local `observe(cedarAudit())` for advisory/additional policy;
- historical and live shadow evaluation;
- PolicyAssessment output separate from DomainEvents;
- audit crash/failure never changes PipelineOutcome.

## POL-2 — Historical simulation and policy diff

- same historical request corpus evaluated by policy pack N and N+1;
- report Allow→Deny restrictions and Deny→Allow privilege expansions;
- false-positive review before enforcement.

## POL-3 — High-value guardrail catalogue

Only policies backed by real risk/UAT:
- production credential use;
- privileged capabilities;
- production deployment;
- artifact promotion/provenance.

## POL-4 — Enforcement admission (future / M9-aligned)

Only after shadow evidence:

```text
Prepared protected intent -> PolicyAdmission -> Allow/Deny -> effect
```

This is the sole policy path allowed to intentionally block execution. It is not implemented as an event observer.

## Deferred/Rejected list

Deferred until evidence:
- generic Observation/Companion plugin SPI;
- NATS/Kafka mandatory dependency;
- Jenkins plugin/controller implementation;
- policy marketplace;
- OPA/Rego second engine;
- controller commands over DomainEvents;
- artifact/provenance graph expansion beyond what EVT/POL tests require.

Rejected now:
- event JSON embedded in logs;
- synchronous remote publish as execution dependency;
- live Event Harness by default;
- unbounded in-process observer queues;
- console output as DomainEvents.
