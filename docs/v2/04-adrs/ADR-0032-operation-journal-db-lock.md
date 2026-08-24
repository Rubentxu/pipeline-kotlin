# ADR-0032: OperationJournal database-level locking

- **Status:** Accepted for M3-R4.1
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.1 Implementation:** T-04 (E4-15)

## Context

The `OperationJournal` implementation used `synchronized(this)` for concurrency control, which fails under multi-instance construction patterns (UatDurable006). When multiple journal instances were constructed (even briefly), each would acquire its own monitor, defeating the intended locking semantics.

The symptom: concurrent append operations from different journal instances could interleave, causing race conditions in the SQLite database.

## Decision

Implement database-level locking using SQLite's `busy_timeout` and a `DbLock` wrapper:

### 1. Connection-level busy timeout

Set `busy_timeout=5000` on the SQLite connection factory, allowing SQLite to retry locks for up to 5 seconds before failing.

### 2. Application-level lock map

Create a `DbLock` utility that wraps `ConcurrentHashMap<String, ReentrantLock>` to provide named locks at the application level:

```kotlin
object DbLock {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun wrap(key: String, operation: () -> T): T {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return operation()
        } finally {
            lock.unlock()
        }
    }
}
```

### 3. Lock wrapping

Wrap `OperationJournal.append()` and `beginOperation()` with `DbLock.wrap("journal-$runId")` to serialize operations per-run while allowing cross-run concurrency.

## Alternatives Considered

1. **Per-journal-instance synchronized block** — rejected; fails under multi-instance construction (the original bug).

2. **Global static lock** — rejected; creates contention across unrelated runs.

3. **SQLite serializable mode** — rejected; significant performance penalty for write-heavy workloads.

4. **External distributed lock (Redis/database)** — rejected; adds external dependency; database-level lock with busy_timeout is sufficient.

## Consequences

- Concurrent appends to the same run are serialized via application-level lock
- Cross-run operations can proceed in parallel (no global contention)
- SQLite lock retries are bounded by busy_timeout
- UatDurable006 passes consistently

## Evidence and Provenance

- E4-15 criterion from ROADMAP.md §E4-15
- DEBT-2026-08-24-UAT006-RECONCILE-OUTPUT-NULL (F13 HIGH)
- SqliteConnectionFactory sets busy_timeout=5000
- DbLock.wrap guards OperationJournal.append() and beginOperation()
- DbLockContractTest validates concurrent safety
