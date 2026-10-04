# M1-P4 — conformance of the Output Plane

**Date:** 2026-10-04 · **Branch:** `m1/output-plane` · **Follows:** `M1_P1`, `M1_P2`, `M1_P3`

---

## 1. What is proven, and what is refused

```text
PROVEN:     a process that dies loses nothing it acknowledged.
NOT PROVEN: durability across power loss.
```

The store issues writes without `fsync`. Bytes already in a file descriptor survive **process death**
in the page cache; they do not survive a machine losing power. The brief's fault model is the one
that was measured, and the refusal lives in `ADR-M1` §D4.1, in
`OutputNotEstablished.POWER_LOSS_DURABILITY`, and in the store's own KDoc so it cannot erode by
silence.

## 2. No invented thresholds

The memory assertion checks a **shape**: the producer was never read with more than one window in
flight. "Peak RSS below 40 MB" would be a measurement of this machine under this load, and a
threshold derived from one sample becomes a law nobody can re-derive. The only number asserted is
the one the brief fixes: **more than 1 GiB**.

## 3. Coverage against the brief

| brief item | where | how |
|---|---|---|
| >1 GiB soak | `soak - more than 1 GiB…` | 1025 MiB, SHA-256 equality end to end |
| bounded-memory shape | `the writer never materialises the whole producer` | one window in flight, as a shape |
| restart mid-stream | `a restart mid-stream resumes at the committed offset…` | a reader holding the pre-crash cursor |
| partial tail | `a commit count ahead of the readable bytes…` | see §4 — this one found a defect |
| slow reader | `a slow reader that stops and resumes…` | real writer thread, 64-byte pages, stalls |
| no missing/duplicate committed bytes | `a deterministic stream of pseudo-random bytes…` | digest equality, not a length check |
| streaming redaction before persistence | `M1_P2` §5 + `Lpr011r2SecretRedactionAtRestUatTest` | redacted on the way **in** |
| returnStdout separate | `M1-P2` `returnStdout stays an exact typed value…` | transcript holds stderr, not the value |

The soak is `@Tag("performance")` and runs through the module's new `performanceTest` task, which is
excluded from the standard gate — the same split `:pipeline-credentials-api` uses for its redactor
probe. A 1 GiB test inside `check` would make every commit pay for it, and would then get skipped or
deleted under contention, which is worse than not having it.

**Not run in this session.** The soak has been written and compiles; it has not been executed. Every
number in this receipt that carries a count was measured on a standard-gate run, which excludes it.

## 4. Three defects this slice found, all real

**A commit record ahead of its payload was silently accepted.** `reconcile` handled "disk ahead of
commit" and not the reverse, so a truncated payload left the committed extent pointing at bytes that
do not exist — and a read at such an offset returned an **empty page that read as a legitimate
end-of-stream**. The first fix I wrote clamped the claim to the readable end, and that fix was worse
than the defect: the bytes between the last real commit and the readable end were never
acknowledged, so clamping published them (I2). Clamping *back* is not decidable from the segment
alone, because the record is exactly what was lost.

So the store now **counts the gap, reports it, and does not repair it**, and the reads that would
need the missing bytes fail loudly. Deciding what a corrupt commit record *means* — lose
acknowledged bytes, or expose unacknowledged ones — is a product decision, and the store declines to
make it silently. Recorded as `OutputNotEstablished.CORRUPT_COMMIT_RECORD`.

**`OutputReadPort.readRange` was not total.** The dangling commit was thrown as an `IOException`,
which is a hole in a closed ADT: a caller handling every refusal could still be thrown out of a
function whose signature promises it will not. It is now `OutputRefusal.DanglingCommit` — and the
compiler immediately caught the consumer that had to learn about it, which is what a closed ADT is
for.

**`OutputRecoveryReport` made idempotence uncheckable.** It carried only *work done this pass*, so
two identical recoveries produced different reports and the only way to assert idempotence was to
compare two things that are supposed to differ. It now carries `committedBytes` (stable) alongside
`bytesReleased` / `reservationsReleased` (correctly zero on a second pass), and a second recovery is
asserted to release nothing.

## 5. What this slice has NOT done

- **No real process kill.** Every crash here is the *reproduced durable residue* of a crash — an
  outstanding `cur.res` and uncommitted bytes, recovered through a fresh store instance. That
  reproduces what recovery reads, and it is what makes these runnable on every change. A real
  `halt()` matrix is a different artefact and has not been run.
- **No P4 mutation harness.** The P1 and P2 harnesses are mutation-proven; this file's guards are
  not yet, and `m4-mutation-prove.py` does not exist. Given the count of defects found here by
  *writing* the tests rather than by mutating them, that harness is the obvious next thing.
- The **corrupt commit record** case is detected and reported, not repaired (§4).
