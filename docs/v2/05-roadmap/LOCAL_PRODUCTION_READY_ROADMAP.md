# Local Production Ready Roadmap (LPR)

Status: PROPOSED — intended to become active P0 sequencing authority after ADR-0082 acceptance.  
Baseline: `main` @ `9f0b1e28b68a7eb03a0d6005dac0d203c25bcdde`.

## Strategy

LPR optimizes for **real local CI/CD use**, not feature-count completion. Every milestone is a vertical slice ending in observable product value and evidence. Architectural refactors are prioritized when they reduce product risk, not because an ideal decomposition diagram is incomplete.

Each milestone has gates:

1. Architecture/fitness;
2. Functional/UAT;
3. Performance where applicable;
4. Compatibility;
5. Real installed-distribution evidence;
6. no pre-existing failure is silently reclassified as success.

## Priority chain

```text
LPR-0 Architecture Truth + CI + benchmark baseline
  ↓
LPR-1 Product Profile + CLI contract
  ↓
LPR-2 Body/Execution hardening
  ↓
LPR-3 Honest DSL + runtime values
  ↓
LPR-4 High-performance observation foundation
  ↓
LPR-5 Observation UX + agentic inspection
  ↓
LPR-6 local-core certification + real projects
  ↓
LPR-7 reproducible distribution + GitHub Release
  ↓
LPR-8 SDKMAN publication + clean-install certification
  ↓
LPR-9 dogfooding + hardening
  ↓
LPR-GATE-1 LOCAL_PRODUCTION_READY
  ↓
resume LFC-2E expansion by evidence
```

---

## LPR-0 — Architecture Truth, CI and performance baseline

### Goal

Start from one authoritative trunk, obtain a green/reproducible baseline and measure hot paths before changing them.

### Work

- accept roadmap disposition + ADR-0082..0091 proposal set;
- mark historical/convergent branches reference-only per ADR-0091;
- make PR/main CI non-empty and required (repository protection is operational configuration);
- define focused per-PR lane vs expensive HF/release lane;
- capture exact current certification/legacy inventory without trusting stale docs;
- benchmark current `SqliteEventStore.append`, event read, shell output drain, `installDist` startup/run;
- characterize sequence restart behavior (`INC-EVT3-1` style issue) and current event/output duplication;
- record baseline JVM/OS/hardware/argv/digests.

### Exit

- CI compile + domain/application + architecture + compatibility corpus is green or has explicit known-baseline ledger;
- no empty required workflow;
- performance baseline reproducible;
- roadmap authority unambiguous;
- no historical branch scheduled for blind integration.

### UAT

`UAT-LPR-000`: checkout clean `main`, run documented baseline commands twice, compare classification and benchmark variance.

---

## LPR-1 — Product Profile and CLI contract

### Goal

Define exactly what first production-ready release promises.

### Work

- freeze intended `local-core-v1` capability list;
- classify every public DSL surface SUPPORTED / EXPERIMENTAL / REJECTED;
- define CLI names `run`, `validate`, `doctor`, `version`, `events`, `inspect`, existing credentials surface;
- define stable exit codes 0/1/2;
- default `pipeline.kts` discovery;
- define project-local state convention `.pipelinek/` without promising all internal paths as public API;
- define `pipelinek doctor` checks and JSON form;
- establish version metadata (app version, revision, JVM, schema/envelope versions).

### Exit

No user-facing command/DSL in Gate-1 has ambiguous support status.

### UAT

- missing/invalid script exits 2 before effects;
- failing pipeline exits 1;
- successful pipeline exits 0;
- `doctor --format json` is parseable and secret-free.

---

## LPR-2 — Body and Execution Hardening

### Goal

Remove the biggest structural risk before feature expansion: central body/control semantics in the coordinator.

### Work units

#### LPR-E1 — Generic bodies contract

- normalize body carrier around durable `BodyRef`: `None / Single / Named` semantics;
- no external body plugin subtype in core IR;
- parity tests from compiler to canonical execution.

#### LPR-E2 — BodyExecutionEngine first slice

- extract policy resolution/interpretation for `Sequential` + `Scoped`;
- preserve exact events/outcomes/journal;
- coordinator delegates.

#### LPR-E3 — Retry/timeout

- route durable retry/timeout through BodyExecutionEngine/BodyInvoker;
- no concrete StepKey switch;
- kill/resume and divergence gates.

#### LPR-E4 — Parallel

- composable named bodies via BranchInvoker;
- no stage-terminal special case;
- deterministic branch identity and durable reconciliation;
- high-output parallel stress included because output plane will depend on it.

#### LPR-E5 — InvocationEngine extraction

- extract durable invocation envelope (metadata/fingerprint/replay/reconcile/capability/common boundary);
- keep run/stage lifecycle in coordinator;
- avoid class explosion.

### Exit

- coordinator fitness finds zero concrete Step routing for migrated supported families;
- supported body Steps run through generic policies;
- external body plugin proof is green or scheduled before Gate-1 if plugin-body extensibility is part of stable SDK claim;
- all migration is behavior-characterized before deletion.

### Deferred

`RepeatUntil`/waitUntil is not a prerequisite unless waitUntil is promoted to SUPPORTED. If promoted, it must enter as a policy + generic body consumer, never a coordinator special case.

---

## LPR-3 — Honest DSL and runtime values

### Goal

Make the supported DSL trustworthy before users start storing it in real repositories.

### Work units

#### LPR-D1 — DSL marker / receiver boundaries

- add pipeline DSL marker;
- split large file incrementally by ownership where it reduces coupled edits;
- no semantic change in this WU.

#### LPR-D2 — Declarative/runtime separation

- declarative builders only build IR;
- remove construction-time runtime bridges from stable supported contract;
- formalize ScriptRuntimeScope/invoker.

#### LPR-D3 — `pwd` / `isUnix`

- real typed runtime values inside runtime context;
- no fallback placeholders;
- restart/replay behavior characterized if used in durable control flow.

#### LPR-D4 — unsupported control surfaces

- `when/post/load/waitUntil` each either has real semantics or fails/labels experimental before Gate-1;
- no silent unconditional body execution / no-op.

### Exit

`NoFakeRuntimeValueFitness` + real `.pipeline.kts` corpus green for supported profile.

---

## LPR-4 — High-performance Observation Foundation

### Goal

Build the stream architecture before building rich rendering.

### LPR-O0 — Benchmark and invariants

- ratify performance budgets from baseline;
- create slow-consumer/high-output fixtures;
- freeze Event/Console/Value/Journal separation.

### LPR-O1 — Batched Event Writer

- persistent writer connection;
- prepared statement;
- bounded ingress;
- batch transactions;
- sequence recovers from durable max on reopen;
- terminal flush barrier;
- no connection/autocommit per event.

### LPR-O2 — Console transcript hot path

- remove per-chunk `runBlocking` bridge if possible without breaking cancellation;
- streaming redaction;
- buffered append-only transcript;
- O(chunk/buffer) memory;
- renderer absent from pump path.

### LPR-O3 — Output duplication burn-down

- stop high-volume `sh` output duplication into `EchoOutputCaptured(content)` after compatibility proof;
- semantic echo event remains bounded;
- events point to operation/transcript metadata when needed.

### LPR-O4 — Read-side cursors

- EventCursor remains sequence based;
- introduce/standardize ConsoleCursor byte offset;
- gap recovery tests;
- local `--follow` built on wakeup + read-after, no EVT-4 remote process required.

### Exit

Performance UAT green; child process unaffected by intentionally slow renderer within budget; 1 GiB soak shows bounded heap.

---

## LPR-5 — Observation UX and agentic inspection

### Goal

Turn the efficient substrate into a product surface.

### Work

- `ObservationView`: normal/events/full/console/quiet;
- `ObservationFormat`: text/jsonl/json;
- `-v` => full;
- clean machine stdout contract;
- typed filters: stage, step, kind, outcome, channel, contains, limit;
- cursor/follow/tail/context;
- bounded `--fields` projection;
- `inspect RUN --failed --context N --log-tail N`;
- normal mode only tails console when failure requires context;
- no arbitrary query language.

### Exit

- all views produce identical execution outcome/events/journal/fingerprint;
- agent can diagnose a deliberately failing real build without ingesting whole transcript;
- secrets absent from all rendered forms;
- slow JSONL pipe cannot slow child process.

---

## LPR-6 — local-core certification and real projects

### Goal

Prove the product on software, not only synthetic Steps.

### Work

- generate authoritative certification ledger from code/tests/receipts rather than manually duplicated Markdown;
- certify required `local-core-v1` families;
- real Gradle wrapper project;
- real Maven wrapper project;
- real Node/npm lockfile project;
- success + compile/test failure + artifact path + credential-safe case;
- run all from `installDist`, not test JVM;
- `pipelinek validate` and `run` use same DSL/compiler contract;
- representative resume/replay test for durable effect.

### Exit

All mandatory product rows are CERTIFIED or removed from supported profile. No disabled/quarantined mandatory test counted green.

---

## LPR-7 — Reproducible distribution and GitHub Release

### Goal

Produce an installable artifact independent from source checkout.

### Work

- configure app/distribution name `pipelinek`;
- version derived from release authority;
- `distZip` canonical;
- explicit reproducible archive settings when needed by actual Gradle version;
- checksums;
- SBOM;
- new V2 release workflow (keep V1 quarantined workflow separate or retire with evidence);
- tag/release candidate gate;
- rebuild same source twice and compare ZIP digest in controlled lane;
- GitHub Release smoke install from downloaded ZIP.

### Exit

A GitHub Release artifact is the exact ZIP tested by release certification.

---

## LPR-8 — SDKMAN

### Goal

Install/update `pipelinek` as a normal developer tool.

### Work

- SDKMAN vendor onboarding;
- candidate naming confirmation;
- protected vendor credentials;
- publication job triggered only after GitHub Release published;
- official SDKMAN release action/API integration, pinned/reviewed;
- checksum;
- clean SDKMAN CI-mode install UAT;
- `version`, `doctor`, `validate`, real project run after installation.

### Exit

A new user can install a specific release via SDKMAN and produce the same certified behavior as direct ZIP.

### Note

SDKMAN onboarding is external. If approval timing lags, internal/local dogfooding may start from the canonical GitHub Release ZIP; LPR-GATE-1 public SDKMAN claim waits for post-publish UAT.

---

## LPR-9 — Dogfooding and hardening

### Goal

Use pipeline-kotlin as CI/CD definition in actual repositories and let evidence reprioritize work.

### Required evidence

- multiple distinct real repositories;
- repeated daily/local runs;
- at least one release/package workflow;
- observed startup cost, cache behavior, log volumes, failure diagnostics;
- defect taxonomy by frequency/severity;
- migration stability across at least one upgrade.

No arbitrary “number of features” closes this milestone. Human review decides when defects are routine rather than architectural.

---

# LPR-GATE-1 — LOCAL_PRODUCTION_READY

All true simultaneously:

```text
CI required checks                    GREEN
single canonical execution spine      GREEN
supported BodyExecution policies      GREEN
coordinator Step-specific routing      ZERO for supported path
fake runtime returns                   ZERO in supported DSL
local-core certification               GREEN
real Gradle project                    GREEN
real Maven project                     GREEN
real Node project                      GREEN
event stream performance               GREEN
slow consumer isolation                GREEN
secret redaction                       GREEN
CLI view parity                        GREEN
reproducible distZip                   GREEN
GitHub Release clean install           GREEN
SDKMAN post-publish UAT                GREEN (for SDKMAN-ready claim)
compatibility fixtures                 GREEN
mandatory @Disabled/quarantine         ZERO counted as success
```

After this gate, LFC-2E resumes selectively. Priority is chosen from dogfooding evidence: reporting/artifacts/toolchains/containers/advanced SCM etc., not automatically E2→E10 if real usage points elsewhere.
