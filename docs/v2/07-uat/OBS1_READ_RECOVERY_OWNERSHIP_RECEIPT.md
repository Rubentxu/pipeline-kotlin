# OBS-1 — Frontera segura entre lectura y recuperación

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `3567bbc6` |
| **Status** | **BLOCK COMPLETE — seven UAT green, one fitness law, six mutations measured** |
| **Harness level** | HF3 — real OS processes, real store, genuine `console` verb |
| **Decision** | `docs/v2/04-adrs/ADR-OBS-002-read-recovery-ownership.md` |
| **Closes** | the defect reproduced in `OBSG_READER_RECOVERY_INTERFERENCE_RECEIPT.md` |
| **Roadmap** | `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`, block OBS-1 |

## The gate

> **STOP:** any reader may release, truncate or modify a reservation belonging to an active writer.

Closed by three mechanisms, each with its own failure mode and its own mutation:

1. **Ownership by kernel lock.** `cur.own` is held by `FileLock` from `reserve()` to `commit()`. The
   kernel drops it the instant the owning process dies, so "held" means *alive*, with no clock and no
   staleness policy to invent a threshold for. `recover()` reconciles a stream only when it can take
   that lock.
2. **A split opening.** `storeForReading` constructs the store with `recoveryPermitted = false`;
   `storeForWriting` recovers. `storeFor` is retained and delegates to the writing opening, because
   retention neither reads nor writes and changing what it may do is a separate decision.
3. **A closed seam.** `ObsPcReadRecoverySeamFitnessTest` pins that the store has exactly one
   construction site and that only the write side and retention may name a recovering opening.

The three are independent, which the mutation table below demonstrates: no single mutation kills all
seven rows.

## Row status

| UAT | Row | Status |
|---|---|---|
| OBS-PC-101 | `OBSG-1` (`ObsGReaderRecoveryInterferenceUatTest`) | GREEN — a live writer's bytes survive a reader |
| OBS-PC-102 | `ObsPcReadRecoveryOwnershipUatTest` | GREEN — one dead run, one live run, one control root |
| OBS-PC-103 | `RECOVER-2` (`ObsGReaderRecoveryInterferenceUatTest`) | GREEN — SIGKILL, recovery authorised from another JVM |
| OBS-PC-104 | `ObsPcReadRecoveryOwnershipUatTest` | GREEN — second recovery releases nothing and says so |
| OBS-PC-105 | `ObsPcReadRecoveryOwnershipUatTest` | GREEN — cursor survives recovery without re-serving |
| OBS-PC-106 | `ObsPcReadRecoveryOwnershipUatTest` | GREEN — an unreconciled plane is not an empty page |
| OBS-PC-107 | `ObsPcReadRecoveryOwnershipUatTest` | GREEN — a dead reader costs the run nothing |

Fitness, three rows: `ObsPcReadRecoverySeamFitnessTest` — one construction site; reading cannot
recover; only the write side and retention name a recovering opening.

## What was actually executed

```
cd v2 && GRADLE_OPTS="-Djava.io.tmpdir=/var/home/rubentxu/.cache/gradle-tmp" \
  ./gradlew :pipeline-application:test --rerun-tasks --console=plain \
  --tests '*ObsPcReadRecoveryOwnershipUatTest*' \
  --tests '*ObsPcReadRecoverySeamFitnessTest*' \
  --tests '*ObsGReaderRecoveryInterferenceUatTest*'
```

Read from the JUnit XML, not from the build's exit line:

```
TEST-...ObsPcReadRecoveryOwnershipUatTest: tests="5" skipped="0" failures="0" errors="0"
TEST-...ObsPcReadRecoverySeamFitnessTest:    tests="3" skipped="0" failures="0" errors="0"
TEST-...ObsGReaderRecoveryInterferenceUatTest: tests="2" skipped="0" failures="0" errors="0"
total: tests=10 failures=0 errors=0 skipped=0
```

`--rerun-tasks` throughout. A run that was merely `UP-TO-DATE` was not accepted as evidence, and two
compile failures during this block were read as harness failures rather than as results.

## Mutations — measured, not argued

Every mutation applied through `~/obsE4diag/mutate.sh`, which verifies the file hash before and after,
refuses to report a RED when the mutated tree did not compile (`^e:`), and restores from a copy rather
than from `git checkout` — the work on this branch is not all committed, and a restore that reached for
git would have deleted it silently.

| Mutation | Change | Rows turned RED |
|---|---|---|
| M-OWN-1 | `tryWithStreamOwnership` reconciles when the lock is held | OBS-PC-**102** |
| M-OWN-2b | `reconcile` truncates to zero instead of to `committed` (the OBS-G defect) | OBS-PC-**102, 104, 105, 106** |
| M-OWN-3 | `read` sets `next` to the start of the stream | OBS-PC-**102, 105, 106, 107** |
| M-OWN-4 | drop `recoveryPermitted = false` from `storeForReading` | fitness row 2 only |
| M-OWN-5 | a second `SegmentOutputStore(` outside the provider | fitness row 1 only |
| M-OWN-6 | `MainConsoleCli` uses `storeForWriting` | fitness row 3 only |

M-OWN-4, 5 and 6 are 1:1 with their rows, which is what a fitness scan should look like when it has
never been given a reason to be lenient.

### Two attributions this block got wrong before measuring

Recorded rather than quietly rewritten, because the corrections are the useful part:

- M-OWN-1 was documented as killing OBS-PC-102 **and** OBS-PC-106. It kills 102 only. OBS-PC-106 kills
  its writer before recovering, so the kernel has already released the lock and the mutation has
  nothing to act on. The claim was about a row that never exercises the guard.
- The first M-OWN-2 was `truncateTo(file, onDisk)` — which truncates to the file's *current* length and
  therefore changes nothing on disk. It still killed three rows through `bytesReleased` alone. A
  mutation can be load-bearing for the report while saying nothing whatsoever about the bytes, which
  is why M-OWN-2b was written to actually truncate.

## Fidelity

HF3. Each row that needs concurrency forks real OS processes. The writer drives the genuine store API
in its own JVM through `ObsGInterferenceProducer`; the reader is a forked JVM running the genuine
`MainConsoleCli.main` through `ObsGConsoleObserver`. Barriers are files the writer creates after
`write` and before `commit`, so "parked in the window" is an event and not a sleep. Every wait has a
deadline and an assertion on its premise. `@TempDir`, no pipes, no wall-clock assertions, no ambient
cwd or env.

Not HF2: `MainConsoleCli` is driven directly rather than through `installDist`. What is certified here
is the library's read/recovery seam, not the installed image.

## Four harness defects this block paid for

Each one nearly produced a false result, and in three cases the first run was a *false pass*:

1. **Zero-filled byte expectations.** OBS-PC-102 and 107 compared the committed output against
   `ByteArray(8192)` — 8192 zeros. The producer writes `'A'` for the acknowledged phase and `'B'` for
   the parked one, so the comparison failed for a reason unrelated to the property under test. The
   expectation is now built by `acknowledgedContent(committed, parked)`.
2. **A tautological liveness assertion.** OBS-PC-107 asserted `waitFor(...) || isAlive`, which is true
   whether the reader ran, crashed on a bad classpath, or was never a working program — so the row would
   have passed with no reader having read anything. It now requires the observer's exit marker in its
   log, which is evidence the real read path ran to completion.
3. **The fitness scan fired on correct code, twice.** Row 1 was written with the guard inverted, so it
   reported the provider's two legitimate constructions; row 3's alternation included `Reading`, so it
   reported the three legitimate readers. A fitness that is red on correct code is a broken fitness, and
   the fix was to make the assertion discriminate, not to widen it.
4. **The fitness scan did not fire on the defect it was written for.** Row 3's regex was
   `storeFor(Write|)`, and the method is `storeForWriting`. It matched neither opening. **M-OWN-6 is what
   caught this** — the mutation that was expected to fail did not. A scan that has never been pointed at
   the real shape of the call it forbids is a scan that has never been tested.

## Honest limits

- The seam law proves that a recovering opening is not *named* outside the write side. It does not prove
  a store handed over as a parameter cannot recover; that path is covered by the constructor's
  `recoveryPermitted` default and the `check(recoveryPermitted)` inside `recover()`, which are behavioural.
- `storeFor` still exists and still recovers, because retention is neither reading nor writing. Changing
  what retention may do was not decided here.
- OBS-PC-105 compares the *commit record* extent. A store whose segment had been destroyed but whose
  commit record survived would still satisfy it; the readable-bytes side of that failure is what
  OBS-PC-106 covers.

## Open, not closed by this block

- **Flake P2, attributed but unresolved.** `ObsBJvmDeathOutputRecoveryUatTest` failed 1 of 4 runs and
  passed 3 of 3 on repetition. Backlog `bl-bl-01M4BM43QY000388Q8AE9EN740`. It cannot be claimed that
  this change did not alter its probability without comparing against the base commit.
- `UatDsl003ParallelTest P6` and `UatRunConcurrencyCharacterisationTest` remain unassigned.

## NOT_RUN

- **STEP-CERT** and **PRODUCT-GATE** on this SHA — not run, therefore not passed. No remote CI exists in
  this repository, so the substitute gate is `cd v2 && ./gradlew check --rerun-tasks` over the exact SHA
  plus an installed-distribution UAT, neither of which this block performed.
- HF2 reproduction of the interference through `installDist`.
- Installed-binary verification of any of the seven rows.

## Verdict

**GO for the OBS-1 STOP condition.** No reader can release, truncate or modify a reservation belonging
to an active writer, demonstrated across real processes, and the seam that made it possible is now
closed by a fitness law whose three rows are each killed by their own mutation.

This closes OBS-1 only. OBS-2 through OBS-7 remain open, and the PRODUCER lifecycle guarantee — what
survives the death of the JVM that owns the pipes — is explicitly undecided.