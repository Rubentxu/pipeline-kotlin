# W5 — Skip classification and run-scoped gate provenance

- **Date:** 2026-10-09
- **HEAD at authorship:** `5c62327f9d454b87a9060c56d6b36e6c5bfa404a`
- **Branch:** `s6-plugin-sdk`
- **Cycle:** `p-733fb505b5a6bd2d/train-1-rp2-characterization` (OPEN)
- **WorkItem:** `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146`

This receipt records two things that were previously assertions without
evidence: what the skipped tests actually are, and how a green gate number
becomes attributable to the exact tree that produced it.

---

## 1. Skip classification

### Measured

```text
@Disabled("reason") across v2/**/src/test          67
  62  archived evidence (Historical/Archived/superseded snapshot)
   2  fixture needing synthetic sources
   1  declared product limitation (DSL classpath: CredentialsId)
   1  intentional contract (0.48.0-rc1 illegal BY DESIGN)
   1  declared WONTFIX (run() helper hangs)
```

Fresh skips in the W5 gate (`:pipeline-architecture-tests:test`
`:pipeline-step-sdk:runtime:test --rerun-tasks`): **10**, all from
`Lfc2ConcreteBodyRoutingDebtFitnessTest$ViolationFixture`, disabled at
line 162 with `"Coordinator is clean post-H2; fixture needs synthetic
sources"`.

### What the 10 fresh skips are NOT

A same-named `ViolationFixture` exists in `FArch001DomainFrameworkFreeTest`
and is **not** disabled — it runs and passes. So the name is not the
signal; the `@Disabled` at the other site is. Reading the class name alone
would have produced a wrong conclusion, which is why this is recorded as
measured rather than inferred.

### Policy is already mechanical

`FArchRP040DisabledTestClassificationTest` enforces "never count skipped
tests as PASS": every `@Disabled` must carry a textual reason. It is green
at this SHA (`2 tests / 0 failures / 0 errors`).

A 67-entry breakdown was therefore produced during this WU and **not
committed**: RP-040 already governs the contract, and a parallel taxonomy
would be a second authority for the same fact. The numbers above are the
evidence, not a new artefact to maintain.

### Consequence

No skipped test is an unnoticed omission. Every one carries a reason, and
62 of 67 are archived evidence that was superseded by a later gate — they
are historical record, not pending work.

---

## 2. Run-scoped gate provenance

### The defect being addressed

W4 declared `753 classes / 4712 tests` and had to be corrected to
`452 / 3061`. The wrong figure summed every XML on disk: 452 fresh plus
301 carried over.

The correction separated them with `mtime >= run start`. That is correct
only while nothing else writes into those directories, and this repository
has already been bitten: the W4 receipt's closing note records a detached
Gradle process staying alive holding the checkout lock, so a later run
failed with *"Another Gradle invocation is already using this v2 checkout"*.
A concurrent or delayed writer makes an mtime filter either drop fresh
results or absorb stray ones, and both directions yield a plausible number.

### Tool

`scripts/run-scoped-junit-report.py`, with
`scripts/test_run_scoped_junit_report.py` (14 tests).

```bash
touch "$JCODE_SCRATCH_DIR/gate-boundary"        # BEFORE the build
(cd v2 && ./gradlew <tasks> --rerun-tasks)
python3 scripts/run-scoped-junit-report.py \
    --head "$(git rev-parse HEAD)" --results-dir v2 \
    --boundary-file "$JCODE_SCRATCH_DIR/gate-boundary" -- ./gradlew <tasks>
```

Provenance in every report: head (validated against `--head`), branch,
subject, tree state, gradle argv, boundary source, and a sha256 over the
fresh set. The carried-over set is **reported**, never silently dropped,
because an auditor must see what was excluded.

### Fail-closed paths, each with the mutation that kills it

| claim | mutation | result |
|---|---|---|
| the report is the run, not the directory | remove the fresh/carried-over split | 3 failures |
| errors are distinct from failures | compute `ok` from `failures` only | 1 failure |
| a report cannot be inherited across SHAs | force `head_matches = True` | 1 failure |
| zero evidence is not a green gate | null the empty-fresh-set guard | 1 failure |

### Two failure directions found while writing it

1. **Boundary created after the run.** It excludes the run it was meant to
   capture and yields PASS over zero tests. Discovered because the harness
   created the boundary in the wrong order. The tool now fails closed on an
   empty fresh set.
2. **`UP-TO-DATE`-only run.** Gradle exits 0 in 2 s producing zero XML. The
   tool rejects it rather than certifying an empty gate. Observed directly:
   a real run of two modules produced `BUILD SUCCESSFUL` with every task
   `UP-TO-DATE`, and the report refused it.

### Observed run at this SHA

```text
head        : 5c62327f9d454b87a9060c56d6b36e6c5bfa404a  (--head match: True)
branch      : s6-plugin-sdk
gradle argv : ./gradlew :pipeline-architecture-tests:test :pipeline-step-sdk:runtime:test --rerun-tasks
boundary    : boundary file (measured, not inferred)

                         classes     tests  failures    errors   skips
THIS RUN                     126       736         0         0      10
carried-over                 333      2002         0         0       9

fresh set sha256 : a2af54483c135910bd89cdd5019ee8a2839ef184897eb74acd608f3d9b24d7e5
```

The digest differs from `73c2fa7c…` produced by a different subset of the
same tree, so it identifies the result set and not merely the commit.

### Status

The tool is **invocable, not yet a gate**. Wiring it into the release gate
or `admission-check.py` is a separate decision and was not taken here.

`PRODUCT-GATE` remains `BLOCKED_EXTERNAL`: no CI surface exists since
`754ddda0` removed the GitHub Actions workflows, so "CI green" is not an
available evidence class and is not claimed anywhere in this receipt.

---

## 3. A correction about an identifier

`W5-UAT-01` appears in **no repository document**. It was proposed
in-session and has no specification behind it. No UAT id was invented for
this work; it is identified by its content and by the commits
`fc044e74` (version authority) and `5c62327f` (gate provenance).

---

## End-of-work-unit closure

```text
Reference implementation consulted: JUnit XML schema (testsuite
    attributes); scripts/admission-check.py in-repo, for the existing
    Git-ancestry freshness pattern this work deliberately did NOT duplicate.
Behaviour adopted:                  report one run's results with provenance and
                                    an explicit carried-over remainder.
Intentional deviations:             freshness uses an explicit boundary marker
                                    rather than Git ancestry, because test
                                    results are untracked build output.
Security implications reviewed:     none; reads local test XML only, no
                                    network, no credential handling.
Tests demonstrating the contract:  scripts/test_run_scoped_junit_report.py
```
