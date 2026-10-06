# ADR-EVO-013 — Reuse compiler sessions under an existing owned lifecycle

**Status:** Proposed, optional measured slice.

## Decision

Reuse compatible compiler sessions in an existing long-lived worker/host if measurements justify it. Keep standalone CLI. Partition by actual profile/dependency identity; use fresh run contexts, bounded queues/retention, cancellation and resource retirement after active leases finish.

No new daemon product, controller, remote protocol or second pipeline scheduler. Characterize Kotlin global environment/thread safety before concurrency. The canonical isolated fallback must be actually implemented and compatible before it is relied on.

## Rationale

Gradle's daemon demonstrates potential startup/JIT/class reuse benefit, not safe sharing of all compiler instances or script singletons. Unowned reuse can retain secrets, event sinks, static state or file handles.

## Acceptance

Spec 21; UAT-080..082, AAT-025/027 and approved benchmark/resource evidence. NO-GO preserves the current route without a warm-worker claim.
