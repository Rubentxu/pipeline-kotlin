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

### The soak, executed

```text
:performanceTest   1 test, 0 failures, 15.46 s
1025 MiB written in 64 KiB windows, read back in 1 MiB pages
SHA-256 of the concatenation == SHA-256 of what the reader saw
```

Fifteen seconds for a gigabyte in and out is plausible on a warm page cache, and it is the only
number here that would have been suspicious without the XML: `BUILD SUCCESSFUL` on a
`:pipeline-output:test` run with zero XML is exactly the failure this project has already hit twice
(this module's missing `useJUnitPlatform`, and a receipt-guard input that was never tracked). The
result was read out of `test-results/performanceTest/`, not out of the exit code.

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

## 4b. A test that could hang is worse than a test that fails

`m4-mutation-prove.py`'s Q7 — make every page hand back a continuation cursor, so `next` is never
`null` — did not fail a test. It **hung the process**: the read loops were `while (cursor != null)`,
and a store that never ends a page never sets it to null. The run sat there for ten minutes with no
XML being written, which is indistinguishable from a slow machine and is not a property a CI gate
can act on.

Two fixes, both about failing loudly instead of stalling:

- `readAll` and the slow-reader loop now carry a **page cap that is asserted**, so a store that
  never terminates reports `the reader never reached the end of the stream after N pages`.
- `MainConsoleCli.readWholeStream` — which is *production* code, not a test — carries a `check`
  with the same shape. A consumer asking for a whole transcript against a broken store would
  otherwise hang forever rather than get an error.

The harness itself now runs each mutation under a 300 s timeout rather than 2400 s, so a hang is
reported as a kill instead of stalling the run.

## 4c. A mutation that cannot fail is a mutation that does not exist

Q2 asked `commit()` to record the **segment size** instead of the reservation **position** — the
exact substitution that turns strategy D into strategy C, and the one RCE's `ADR-0002` spent a
spike ruling out. The suite stayed **green**.

That is not a weak test. In this store the two values **coincide by construction**: every
reservation truncates any stale tail before it writes, so at commit time the segment holds exactly
the committed bytes. They could only diverge if a reservation failed to truncate — which is Q3's
subject, and Q3 is killed.

So Q2 is **retired with its reason recorded** rather than left in the harness looking like coverage.
A mutation that cannot fail is a guard that does not exist, and keeping it in the list would make
the harness's "6 of 6" read stronger than it is. The property is real; this store satisfies it
structurally, and that is the honest way to say so.

## 5. What this slice has NOT done

- **No real process kill.** Every crash here is the *reproduced durable residue* of a crash — an
  outstanding `cur.res` and uncommitted bytes, recovered through a fresh store instance. That
  reproduces what recovery reads, and it is what makes these runnable on every change. A real
  `halt()` matrix is a different artefact and has not been run.
- **P4's mutation harness: 6 of 8 killed, and the two that are not are named.**
  `m4-mutation-prove.py` exists and is re-runnable. It caught the hang in §4b on its first run.

  ```text
  killed  Q1  appendFrom writes but never commits
  killed  Q3  recovery stops releasing the outstanding reservation
  killed  Q4  the dangling-commit count is not reported
  killed  Q6  a page is no longer bounded by maxBytes
  killed  Q7  the last page always hands back a continuation cursor
  killed  Q9  the writer buffers more than one window
  OPEN    Q5  a byte range silently clamps to what is readable
  OPEN    Q8  recovery is no longer idempotent in the stable field
  ```

  **Q5 is a real hole and is not papered over.** The mutation applies and the suite stays green.
  The likely cause is that the range-agreement test only asks for ranges *inside* the committed
  extent, so it may never reach the clamping branch at all — but that was not established, and an
  unestablished cause is not a cause. Until it is resolved, that row is a gap in the harness and the
  harness says so in its own source.

  **Q8 was a harness bug, not a store property**: its pattern and replacement were written swapped,
  so it silently never applied, and the second attempt did not compile because an `Int` was summed
  into a `Long` expression. Both are fixed; the corrected mutation has **not** been through a
  verification pass, so Q8 is listed as open rather than claimed.
- The **corrupt commit record** case is detected and reported, not repaired (§4).
