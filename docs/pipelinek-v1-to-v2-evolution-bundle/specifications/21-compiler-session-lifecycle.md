# Specification — Optional compiler session lifecycle

**Status:** Optional measured slice. **Owner:** existing compiler adapter/worker lifecycle. **Decision:** ADR-EVO-013.

## 1. Value and boundary — GR-013

Gradle amortizes JVM startup and keeps classes warm. PipelineK may reuse compatible compiler sessions inside an existing long-lived owner when measured benefit justifies it. Keep standalone CLI behaviour. No controller, provisioning, daemon protocol or second execution engine is added.

Memory cache in a one-shot CLI cannot survive the next process. Cross-command acceleration requires the conditional persistent slice or an existing long-lived owner; never claim it from in-process caching alone.

## 2. Compatibility partition

Partition sessions/scopes by effective compiler profile, exported DSL/API identities, approved dependency plan and ABI. Incompatible versions cannot share compiler internals or Kotlin type identities. Reused ClassLoaders do not isolate static singletons. Use fresh receivers/contexts and characterize supported state; bypass loaded-class reuse if isolation fails.

## 3. Lifecycle — GR-014

Model opening/ready/draining/closed/failed states through the existing owner. Reject work when draining/closed with typed diagnostics. Profile switches create a new compatible scope and retire old scopes after active leases.

Initially one bounded compiler queue per session; Kotlin internals are not assumed reentrant. Bound pending requests/retained artifacts and distinguish queue timeout, compiler deadline and pipeline Step timeout.

Release temporary files, borrowers, source maps and event/context references on every terminal path. Dispose owned environments/loaders at the right scope boundary. Do not dispose global Kotlin application state used by another session. Characterize global environment ownership before concurrency; serialize or use the canonical isolated compiler fallback through a real implemented port if separation is impossible.

## 4. Cancellation and failure

Cancel queued work without evaluating source. In-flight cancellation is promised only as actually provided by the backend; isolated fallback must terminate its owned process tree. Cooperative coroutine/thread cancellation does not prove compiler CPU work stopped.

No reuse of incomplete/failing artifacts. Follow subscriber rules in spec 20. Restarting compiler resources never resets pipeline history or invents a completed operation.

## 5. Honest budgets

Enforce entry/byte counts, queue lengths and owned deadlines where implemented. Observe RSS/native memory/file handles separately. Heap configuration is not a per-pipeline process quota. Approve benefit/retention budgets after measuring a defined corpus, cancellation and profile transitions; no universal invented threshold.

## 6. Acceptance

UAT-080/081/082 and AAT-025/027: context freshness, queue cancellation, lease-safe eviction, profile/resource retirement. One-shot installed CLI parity remains blocking. NO-GO retains current/isolated path and makes no delivered-daemon claim.
