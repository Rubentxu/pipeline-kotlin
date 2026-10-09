# AUD-04 — Strict argument parsing in the `events` and `console` CLIs

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `419f9192` |
| **Status** | PROBADO — both parsers, 8 rows, 7 discriminating mutations |
| **Harness level** | HF1 in-process over the real CLI entry point and a real SQLite store |
| **Origin** | External comparative audit (`par/cli-observation` vs `s6-plugin-sdk`) |

## What the audit named, and what it did not

The audit named one hole: `events` ignoring an unknown option, so the external consumer's
`pipeline events --db events.db RUN_ID --limit 4 --typed` returns untyped envelopes with exit 0. It
did not open `MainConsoleCli`, which had the same shape plus a second instance of the same class of
defect. Reading both files rather than the one that was quoted produced four findings, not one.

| # | Finding | Attributed to | Introduced by |
|---|---|---|---|
| F1 | `events` drops an unknown `--flag`, exit 0 | **this branch** | `a84b73f9` (EVT-2) |
| F2 | `events` drops a second positional, exit 0 | **this branch** | `a84b73f9` (EVT-2) |
| F3 | `console` drops an unknown `--flag` and a third positional | preexisting, present at `2e9b4824` | M1-P3/P4 (`a41cc6c9`) |
| F4 | `console --max-bytes abc` silently becomes `DEFAULT_PAGE_BYTES` | preexisting, present at `2e9b4824` | M1-P3/P4 (`a41cc6c9`) |

F1/F2 are not a merge conflict. They were live on this branch before any integration was attempted.
F3/F4 are on both branches; `s6-plugin-sdk` independently fixed both under AUD-04.

## Why this branch is internally inconsistent, not merely divergent

Three CLIs, two conventions:

| CLI | This branch | S6 | Strict since |
|---|---|---|---|
| `run` / `observe` (`CliParser`) | strict — `CliError.TrailingOption` | strict | this branch |
| `observe` (`MainObserveCli`) | strict, by delegating to `CliParser` | — | `7c0cc89b` |
| `events` (`MainEventsCli`) | **lax** | strict (AUD-04) | now |
| `console` (`MainConsoleCli`) | **lax** | strict | now |

`CliParser`'s own comment states the rule — "an unknown one anywhere is an error rather than a silent
no-op". `events` and `console` were written before that convention and never updated. So the fix is
not a rule imported from `s6-plugin-sdk`; it is this branch's own rule applied to the two files that
missed it. Aligning them with S6 additionally means the two copies of each file answer identically
whichever one survives a merge.

## The fix

Both parsers now refuse, during argument parsing and before any filesystem, store or journal
question:

- an unknown `--flag` → exit 2, naming the flag;
- a positional beyond the ones the verb reads (`events` reads one run, `console` one run and one op)
  → exit 2, naming the dropped argument;
- `--limit abc` / `--max-bytes abc` (and `0`, `-1`) → exit 2 instead of becoming the default.

`events` gains no new capability: `--typed` still does not exist here, and saying so with exit 2 is
the whole point of the row. `--typed` remains the external consumer's to implement on S6 (plan I1).

## Evidence

### RED first, with the failure reason read rather than assumed

Run against the unmodified parsers. No `^e:` compile error — `compileTestKotlin` emitted only the
Kotlin 2.0 deprecation warning, so these are genuine assertion failures and not a stale-class run.

| Row | Assertion message |
|---|---|
| UNKNOWN-1 | `an option this build does not have is an error, not a no-op ==> expected: <2> but was: <0>` |
| UNKNOWN-2 | `the argument is rejected while parsing… ==> expected: <true> but was: <false>` — stderr was `Error: db not found: …` |
| EXTRA-1 | `the command reads ONE run; a second one was silently dropped ==> expected: <2> but was: <0>` |
| KNOWN-1 | green (the control) |

UNKNOWN-2 is the sharpest of the three: the exit code *was* 2, but it came from `db not found`. The
flag was discarded, the parse continued to the filesystem, and the command was about to run against
a store it should never have reached. Asserting only the exit code would have let that pass.

### GREEN

`:pipeline-application:test` — 5 classes, **43 tests, 0 failures, 0 errors, 0 skipped**:
`MainEventsCliArgumentStrictnessTest` 4/0, `MainConsoleCliArgumentStrictnessTest` 4/0,
`MainEventsCliRefusalVisibilityTest` 5/0, `ObservationCliContractTest` 19/0,
`ConsoleReadServiceTest` 11/0. The 35 pre-existing rows are unaffected by both parser edits.

### Mutations, 1:1 attribution

Seven mutations, each killing exactly the rows claimed and nothing else:

| Mutation | Change | Rows turned RED |
|---|---|---|
| D-ARG-1 | `arg.startsWith("--")` → `false` in `events` | UNKNOWN-1, UNKNOWN-2 |
| D-ARG-2 | drop the `runId != null` arm in `events` | EXTRA-1 |
| D-ARG-3 | refuse everything from the top of `events` `main` | all 4, **including KNOWN-1** |
| D-CON-1 | `arg.startsWith("--")` → `false` in `console` | CONSOLE-UNKNOWN |
| D-CON-1b | trailing `else` arm → `Unit` in `console` | CONSOLE-EXTRA |
| D-CON-2 | restore `?: DEFAULT_PAGE_BYTES` for `--max-bytes` | CONSOLE-MAXBYTES |
| D-CON-3 | refuse everything from the top of `console` `main` | all 4, **including CONSOLE-MISSING-ARG** |

Two facts about this table are worth stating rather than hiding.

**D-CON-1 and D-CON-1b exist because the first version of the note was wrong.** The original KDoc
claimed D-CON-1 killed both `CONSOLE-UNKNOWN` and `CONSOLE-EXTRA`. The measurement disagreed:
`CONSOLE-EXTRA` stayed green, because the extra-positional arm does not depend on the
unknown-option arm. The claim was corrected and the guard given its own mutation. A note that
records a coverage claim the harness contradicts is worse than no note.

**D-ARG-3 and D-CON-3 are what stop the strictness rows from being trivially satisfiable.** All
three strictness rows in each class assert exit 2, so "refuse everything" would pass them. The one
row per class asserting a *served* outcome is what kills that mutation, and it did.

Harness: `~/obsE4diag/mutate.sh`, which verifies the file hash before and after, restores from a copy
rather than `git checkout`, and rejects a run whose log contains `^e: ` — a mutation that does not
compile leaves stale XML from the previous run, which has already produced one false reading in this
repository and is why that check exists.

## Harness fidelity

HF1. Both rows cross the production entry point — `MainEventsCli.main`, `MainConsoleCli.main` — in
process. `events` rows run over a real `SqliteEventStore`-written SQLite file with real rows; the
`console` rows are decided entirely during parsing, so they reach no store, which is the property
asserted rather than a limitation. Nothing re-implements the parser, and no row asserts on duration,
size or ordering.

This is **not** HF2. The installed distribution was not exercised for these rows, and no claim about
installed bytes is made here.

## NOT_RUN

- Installed-distribution UAT (HF2) of both verbs, including the six `ConsoleContinuationGate` rows
  that need `PIPELINEK_SPIKE_HOME`.
- `apiCheck` / ABI validation. The parsers changed no public type, but this is recorded as not
  observed rather than assumed.
- STEP-CERT and PRODUCT-GATE on this SHA.

## Open, and deliberately not fixed here

`observe` is a third strictness problem that belongs to the next block rather than this one. Its
`--follow` loop reads through `EventStore.readSlice`, which is
`readRecords(...).requireFullyDecoded()` and `@throws UndecodableEventRecordException`; `replayEvents`
reads through `eventsFor` and materialises with `events.toList()`. So one unreadable row ends the
whole observation for `observe`, while `events` has reported refusals on stderr since S5.4. That is
the audit's P1 semantic finding, confirmed by reading `MainObserveCli.kt:145,289,551,562` and
`EventStore.kt:117`. Making `observe` refusal-tolerant is a design change with observable output, not
a parser edit, and it is not this commit's claim.