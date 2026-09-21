# Tasks: event-spine-evolution

## EVT-0
- [ ] inventory every production event producer and sequence authority
- [ ] inventory current EventSink/EventStore consumers
- [ ] map existing UAT/example event assertions, explicitly including P4-EX `d0ccf4b5` 07–10 contracts and `EXPECTED_OUTCOME`/`EXPECTED_EXIT`
- [ ] freeze authority/non-goal matrix

## EVT-1
- [ ] RED tests for ResourceRef determinism/round-trip
- [ ] implement ResourceRef + builders for five initial kinds
- [ ] RED tests for PipelineEventEnvelope identity/causation
- [ ] envelope adapter around current DomainEvents
- [ ] prove no fingerprint/journal semantic change

## EVT-2
- [ ] characterize existing SQLite store behavior
- [ ] introduce publisher/history/tail ports minimally
- [ ] adapt SQLite behind ports; no rewrite unless characterized defect forces it
- [ ] add run query/filter CLI/API minimum
- [ ] add ObservationStatus/completeness semantics

## EVT-3
- [ ] protocol grammar ADTs and verifier
- [ ] YAML/TOML codec with fail-closed validation
- [ ] minimal counterexample renderer
- [ ] characterize current `examples/run.sh` 01..10 expected outcome/exit oracle
- [ ] encode existing 07 catchError, 08 parallel, 09 retry and 10 timeout assertions as typed constraints/sidecars
- [ ] differential gate: old `run.sh` assertion verdict == new harness verdict on the same histories
- [ ] only then delegate/remove duplicated shell assertions; keep `examples/run.sh` top-level gate GREEN
- [ ] add one mutation canary per core grammar family

## EVT-4
- [ ] detached relay process spike using current local backend/tail
- [ ] deterministic live-before-finish UAT
- [ ] subscriber crash isolation UAT
- [ ] reconnect-from-cursor UAT
- [ ] resource benchmark; optional cgroup Linux isolation proof

## EVT-5
- [ ] CloudEvents mapping contract tests
- [ ] HTTP CloudEvents transport spike
- [ ] NATS JetStream spike
- [ ] compare footprint/latency/replay/duplicates/operability
- [ ] ADR selects transport only if M4 needs justify it

## Closure
- [ ] architecture fitness
- [ ] real installDist examples acceptance
- [ ] full round gate + Rule-16 exact baseline
- [ ] closure receipt
- [ ] update AGENTS only for laws proven by the completed cycle
