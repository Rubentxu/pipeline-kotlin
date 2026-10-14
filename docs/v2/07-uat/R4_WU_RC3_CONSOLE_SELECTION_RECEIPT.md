# R4 / WU-RC3 — Console selection (P0 block 2 of the rc1 audit)

- **Status:** IMPLEMENTED_UNCERTIFIED. Four defects fixed and pinned; end-to-end certification is
  still blocked by the fourth defect (empty Output Plane), so this does not upgrade CON-2.2.
- **Base:** `119967e3` (`main`, after #126 and #127). **Train:** 0.61.0-rc3 (proposed, not published).
- **Provenance:** branch `r4/0-61-rc2-p0-follow`, worktree `pk-rc2`.

## One-line summary

The audit asked for one thing: **unify the selection decision for the single read and the follow
loop.** Chasing that surfaced four defects — and the reason the audit could see them at all is
that the previous block made the follow loop honest about exiting. A loop that exits with `0`
after observing nothing cannot be audited by looking at its output.

## The four defects

### P0-4 — `--follow` shadowed every filter

```kotlin
if (follow) return consoleAdmitFollowFrames(...)        // filters never reach the request
if (hasFilter) return consoleAdmitFramesFiltered(...)
```

`console --frames --follow --stage build` admitted a filterless `FollowFrames`. The `--stage` was
dropped without a word: the operator asked for one stage, was shown the whole run, and got exit 0.
There was no diagnostic because there was no error — the request was simply built differently.

Filters and following are **independent** axes: following is *how long* you read, filtering is
*what* you read. Neither shadows the other, so there was never a reason for this ordering.

### `--stage build` could never match

`StageStepKindResolver` compared the user's token against the **stringified index** the opId
carries. `--stage 0` hit; `--stage build` missed against the very same operation. The names live
in the events journal (`StageStarted.stageName`), which the verb never opened — the class took a
`root` parameter and carried `@Suppress("UNUSED_PARAMETER")` on it.

### `--kind` was a semantic drop

```kotlin
val kindOk = true
return stageOk && stepOk && kindOk
```

A non-empty kind set contributed a **true** conjunct. `--kind literally-anything` selected every
frame in the run, exit 0. This is the "semantic drop" the Semantic Constitution §2 forbids
outright: a flag accepted, never interpreted — and its result is indistinguishable from a filter
that worked, which is why it survived review.

`StepStarted.stepType` already carries exactly the right vocabulary (`sh`, `junit`,
`scm-git.checkout`, and any external plugin's key), so the axis needed no new concepts — only a
journal.

### A required opId made the filters nearly meaningless

`CliParser.consoleValidate` refused a request with no opId positional, **unconditionally**. So the
read was scoped to ONE operation *before any filter ran*, and a filter could only ever narrow
within it. `--stage build` could never reach `run-1-s1-0` whatever the filter said.

This one was only visible once P0-4 was fixed: with the filter dropped entirely, nobody asked what
the filter would have done had it been applied.

## The fix — one selection, two interpreters

`ConsoleOperationSelection.resolve` is a **pure** function over a `Sequence<DomainEvent>`:

```text
--stage NAME -> StageStarted.stageName  -> stageIndex
--step  NAME -> StepStarted.stepName    -> (stageIndex, stepIndex)
--kind  TYPE -> StepStarted.stepType    -> (stageIndex, stepIndex)

every token resolves independently; an UNRESOLVED token is a REFUSAL, not an empty set
an operation survives when it satisfies EVERY non-empty axis (AND across, OR within)
```

The result is a `Selection`, and `Selection.includes(opId)` is the single membership test both
paths consult. `main` resolves it ONCE before the dispatch and hands the value to whichever
interpreter runs — so the single read and the follow loop cannot drift apart again, because they
are handed the same answer rather than each deriving one.

### `--db`: giving the verb the authority it lacked

Stage/step NAMES and kinds are journal-only, so `console` gained an optional `--db`. Three
properties worth stating:

- it is a **path on the request**, not an opened store. The parser admits without performing an
  effect (STRICT TYPED FUNCTIONAL DESIGN §10); `main` opens the journal at interpretation time.
- `dbPath == null` is passed to the resolver as `null`, **not** as an empty history. Those are
  different facts with different answers: an empty history means "this run has no such stage",
  while no journal means "nobody can tell" — which is a refusal naming the axis. Collapsing them
  would make `--db` optional in the worst possible way: silently narrowing the answer.
- an INDEX token (`--stage 0`) resolves **without** a journal, because the opId carries it. A
  caller scripting against indices owns no journal and should not have to.

### Fail-closed, not fail-quiet

An unresolved `--stage typo` selecting nothing looks exactly like a stage that wrote nothing, and
the caller cannot tell a typo from a genuinely silent stage. A filtered console that shows too
little is read as *"the pipeline was quiet"* — a claim about the **product**. So every unresolved
token is a typed refusal naming what was asked for and what exists:

```text
Error: no stage named 'bulid' in this run (known: build, test)
Error: no step of kind 'nonsense' in this run (known kinds: junit, sh)
Error: --kind name stage/step/kind, which are recorded only in the events journal —
       pass --db <path> so the console can resolve them, or use the numeric index (e.g. --stage 0)
```

### The cursor subtlety

The follow loop advances its cursor on the **last ADMITTED** frame, not the last READ one. If it
advanced on the last read ordinal, a filter that dropped every frame in the current window would
re-read that window forever and never reach an admitted frame — a stall that looks like "no output
yet". `RC3-Cb` exists specifically to kill that mutation: the store holds three operations and the
selection admits only the last.

## Test evidence

| Class | Pass / Skip / Fail | Level |
|---|---|---|
| `ConsoleOperationSelectionTest` (new) | 11 / 0 / 0 | HF0 pure |
| `ConsoleRc3FilteredFollowTest` (new) | 9 / 0 / 0 | HF0 parser + HF1 verb |
| `ConsoleFrameFiltersTest` | 23 / 1 / 0 | HF1 |
| `ConsoleFollowTest` | 22 / 1 / 0 | HF1 |
| `ConsoleRc4FollowTerminationTest` | 5 / 0 / 0 | HF1 |
| `ConsoleRc2FrameIntegrityTest` | 4 / 0 / 0 | HF1 |
| `MainConsoleCliPerFrameTest` | 14 / 0 / 0 | HF1 |
| `CliParserUnificationTest` | 20 / 0 / 0 | HF0 |

### Rows and mutations

```text
RC3-1  a stage NAME selects every operation of that stage      -> M-RC3-2 (index comparison)
RC3-2  an index token resolves without a journal
RC3-3  non-empty axes are ANDed; values within an axis are ORed -> M-RC3-3
RC3-4  a kind selects the steps whose stepType it names         -> M-RC3-1 (return AllOperations)
RC3-5  an unknown kind selects NOTHING rather than everything   -> M-RC3-1  <- the rc1 defect
RC3-6  an unresolved name is refused and names the known set    -> M-RC3-4 (empty set instead)
RC3-7  a name or kind without a journal is refused              -> M-RC3-5
RC3-8  membership compares the opId exactly, not as a substring
RC3-9  no filter at all selects every operation
RC3-A  a follow carrying a filter is admitted WITH it           -> M-RC3-A (dispatch order)
RC3-B  a follow without a filter still needs the opId positional
RC3-C  a filtered follow emits only the selected operation's frames -> M-RC3-B (no filtering)
RC3-Cb a filter matching only a later operation still reaches it -> M-RC3-C (cursor)
RC3-D  the journal path reaches the request
RC3-E  an unresolvable filter renders a refusal naming the axis
```

### The one `@Disabled` row

`ConsoleFrameFiltersTest` → *"StageStepKindResolver treats empty axes as no-op conjuncts"* pins
`kinds = setOf("StepStarted")` **matching**. `StepStarted` is not a kind; no step in any run has
it. The row asserts a match, which is the defect. `@Disabled` with the reason inline and the
replacement rows named — not deleted, and not rewritten to match the new behaviour.

The other three resolver rows (index matching, foreign-run rejection, malformed-id rejection)
still describe correct behaviour and were left green.

## Base-vs-head evidence for the red sweep (AGENTS.md rule 16)

A sweep over every `Console*` / `MainConsoleCli*` / `CliParser*` / `Obs*` / `Observation*` class
produced four failing classes, all in the `Obs*` ingress family:

| Class | Base | Head | Verdict |
|---|---|---|---|
| `ObsAOutputStreamingCharacterisationTest` | FAIL | FAIL | pre-existing (proved in #126) |
| `ObsBJvmDeathOutputRecoveryUatTest` + `ObsBLiveOutputIngressTest` | 5 tests / 4 fail | 5 tests / 4 fail | pre-existing |

The second row is the one worth stating plainly: those two classes were **not** in the base
comparison run for #126, so they were re-run at `119967e3` by stashing this block. Identical counts
at base and head. **This block introduces zero new failures**, and none of the four touch the
console verb, the parser, or the selection — they are the fourth defect's ingress family.

`detekt` is also unchanged: 5 issues before and after. Two of them (`consoleValidate` complexity,
`MainConsoleCli` function count) are pre-existing violations whose NUMBERS moved — 34→36 and
17→21 — and this block initially added a sixth (an unused parameter in the new resolver), which is
fixed. The `consoleValidate` complexity is a real signal that the block's remaining work belongs
in a `CliParser` decomposition rather than in more branches inside that one function.

## Harness fidelity

- `ConsoleOperationSelectionTest` is **HF0**. `resolve` opens no journal and touches no
  filesystem, so driving it directly is the faithful level. Its events are the production event
  **shapes**; nothing in it recomputes a selection — the resolver under test is the only thing
  producing them (HARNESS FIDELITY §2).
- `ConsoleRc3FilteredFollowTest` is **HF0 for the parser rows** and **HF1 for the verb rows**. The
  verb rows drive the real `runFollowFrames` over a real `SegmentOutputStore` through the
  production publish order and select with the **real** resolver — the harness deliberately does
  not rebuild the predicate, which would certify the test's own matching logic.
- Two harness defects were found and fixed while writing these rows, both of which had made rows
  pass for the wrong reason: the fixture wrote the store at the control root instead of
  `{controlRoot}/output-plane`, and it used a stream shape the reader could not match.

### What this block does NOT claim

It does not prove `--stage build` resolving against a real journal in a real run. That needs an
installed distribution whose Output Plane is populated, and the fourth defect leaves it empty. Name
resolution is proved at HF0 against production event shapes; the end-to-end wiring is **BLOCKED**
and recorded rather than claimed.

## Reference implementation consulted

- **Reference implementation consulted:** Jenkins `tail -f` / pipeline `when`-style selection, via
  `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`.
- **Behaviour adopted:** content selection and following are independent axes; an unresolvable
  selector is an error, never a silent empty result.
- **Intentional deviations:** Jenkins treats an unmatched `when` condition as "skip", because a
  `when` block is authored in the same file as the branches. A console filter is a **query against
  a completed run**, where "no match" and "I looked in the wrong place" are genuinely different
  and only one of them is the caller's error.
- **Security implications reviewed:** n/a — this block reads the journal and narrows frames; it
  writes nothing, opens no new capability, and changes no credential handling. `--db` points at an
  existing journal the caller already owns.
- **Tests demonstrating the contract:** `ConsoleOperationSelectionTest`,
  `ConsoleRc3FilteredFollowTest`, `ConsoleFrameFiltersTest`.

## Remaining

1. **The fourth defect** still blocks end-to-end certification.
2. `--kind`/`--stage NAME` require `--db`; the error message says so, but there is no default
   journal location to infer.
3. `ConsoleReadRequest.Frames` and `FramesFiltered` are now the same shape — `Frames` with a
   non-`AllOperations` selection. The collapse is a clean-up, deliberately not taken here because
   it would touch 39 references across 4 files for no behavioural gain.
4. `StageStepKindResolver` is retained but called by nothing in production; its KDoc now says so
   and names the replacement. It is kept only so its four characterisation rows still compile.
