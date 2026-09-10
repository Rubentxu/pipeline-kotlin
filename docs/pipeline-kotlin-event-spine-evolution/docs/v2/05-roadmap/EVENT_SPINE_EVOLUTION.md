# EVT — Event Spine, Verification and Policy evolution

Status: PROPOSED program  
Placement: after LFC-2/CTX-P foundation, before resuming the distributed/controller path that depends on event semantics.
Maps forward to existing M4 (Protocol/Gateway), M6 (Jenkins UI), M8 (Graph/Provenance) and M9 (policies).

## Sequencing principle

Do not build the final distributed platform first. Each slice must produce visible local value and leave a
compatible seam for the next deployment topology.

## EVT-0 — Grounding and contract freeze

**Goal:** inventory current event production, sequence assignment, storage, event types and all existing real UAT/example assertions.

Deliverables:
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

## EVT-3 — Event Harness POST_RUN + real examples

**Goal:** make real examples executable protocol specifications.

Deliverables:
- universal lifecycle grammar;
- typed constraints (`Exactly`, `Never`, `Before`, `Outcome` minimum);
- YAML/TOML contract codec;
- minimal counterexample reporting;
- examples 01..06 classified by expected outcome;
- new real examples for catchError, parallel, retry and timeout;
- `examples/run.sh` or dedicated acceptance runner distinguishes expected failures/timeouts.

UAT: see `docs/v2/07-uat/UAT_EVT_REAL_EXAMPLES.md`.

Exit:
- all supported examples execute via installDist;
- contracts validate actual produced histories;
- at least one deliberately mutated trace is rejected (anti-false-green canary).

## EVT-4 — Detached live relay

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
