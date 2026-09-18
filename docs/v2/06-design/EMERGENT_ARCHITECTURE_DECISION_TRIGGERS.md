# Emergent Architecture — decision triggers

Status: PROPOSED

The roadmap intentionally leaves implementation choices open where evidence can decide later. These triggers prevent both premature complexity and endless “we may need it someday” abstractions.

## 1. Event query pushdown

**Default:** filter local event history in read-side Kotlin over the existing port.  
**Introduce SQL indexes/query pushdown when:** representative histories reach a measured p95 query latency that harms CLI/agent use (suggested investigation threshold: ~50 ms for expected local history sizes) or memory decoding becomes material.

Do not add a general query DSL; add typed query cases/indexes that real filters need.

## 2. Event batch size

**Default:** benchmark 32/64/128 and short 1/2/5 ms windows.  
**Choose:** the smallest latency/throughput tradeoff that meets budgets.  
**Do not expose:** batch size as stable public API until an operational use case needs tuning.

## 3. Queue implementation

Start with standard Kotlin/JDK primitives. Adopt a specialized MPSC library only if profiler evidence shows queue coordination is a meaningful share of runtime after DB/serialization costs are fixed.

## 4. Console storage representation

Start append-only per-operation transcript + byte offset. Add a side index/frame file only if channel-aware random access or tail latency cannot meet requirements. Do not invent a binary log protocol preemptively.

## 5. Remote event relay

Stay local/in-process through LPR. Start EVT-4 only when a real detached/remote consumer is required. Select NATS/Kafka/HTTP only under EVT-5 benchmark/operational criteria.

## 6. Native executable / jlink

Universal Java 21 ZIP first. Investigate jlink/native when measured JVM startup/footprint is a real UX or ephemeral-CI bottleneck. Keep JVM distribution as compatibility reference until native parity is proven.

## 7. Daemon/cache service

No background daemon for Gate-1. Add one only if repeated compile/startup cost remains a top dogfooding complaint after ordinary compiler caching and process optimization.

## 8. Plugin ecosystem breadth

After LPR-GATE-1, choose next plugin family from observed projects. Do not automatically implement LFC-2E E2..E10 in numeric order if usage says reporting/artifacts/toolchains/containers have different value.

## 9. `waitUntil` / RepeatUntil

Add `BodyExecutionPolicy.RepeatUntil` only when `waitUntil` is being promoted to supported functionality. The implementation must prove no special coordinator path and restart-safe durable recurrence.

## 10. `load`

Run a bounded design spike only when real local projects need dynamic source expansion. Decide explicitly whether `load` is a Step returning structural expansion or language-level program expansion; do not force it into StepHandler merely to make `LEGACY_PLUGIN_IDS` empty.

## 11. Coordinator split beyond two engines

BodyExecutionEngine + InvocationEngine are the intended 80/20 extractions. Introduce StageRunner/RunLifecycle components only when the coordinator still changes for unrelated reasons or tests show lifecycle cannot be isolated cleanly.

## 12. CloudEvents

Keep current characterization mapping. Do not make CloudEvents SDK a local runtime dependency until transport interoperability becomes an actual requirement.
