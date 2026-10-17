# Delivery-first radical: integration + H0/R1 — 2026-10-17

**Roadmap integrated:** `docs/pipelinek_delivery_first_radical/`.
**Base:** `main@081ffe4d`. **Slice executed:** H0/R1 (`:test-harness` runtime dependency).
**Authority:** `docs/v2/05-roadmap/ROADMAP.md` §13.7 (single link, subordinate).

## What was integrated

Copied with `cp -n` (never overwriting; all three targets verified absent first):

| overlay | canonical path |
| --- | --- |
| `overlay/docs/v2/03-specifications/PRODUCT_FIRST_MINIMUM_VERIFICATION_SPEC.md` | `docs/v2/03-specifications/` |
| `overlay/docs/v2/04-adrs/ADR-PK-DF-002-…` | `docs/v2/04-adrs/` |
| `overlay/docs/v2/05-roadmap/PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md` | `docs/v2/05-roadmap/` |

`ROADMAP.md` gains **one** link block (§13.7) — not a second backlog, not a second sequence. The
`B0..B7` plan remains authoritative.

**`ADR-PK-DF-002` remains `status: proposed`.** Copying a proposed ADR to its canonical path does not
ratify it, and this receipt does not. H0.4 stays blocked on the amendments it enumerates
(AGENTS.md, ROADMAP, `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4, `TEST_IMPACT_AND_EVIDENCE_SPEC`,
`TEST_EXECUTION_LANES_SPEC`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC`).

## The transition, and the trap that decides how it must be done

`dev.rubentxu.pipeline.v2.harness` is a package prefix shared by **three** modules:

| module | role under this roadmap |
| --- | --- |
| `:pipeline-event-harness` | **KEEP** — backs `pipeline events verify`, a public feature |
| `:test-harness` | **CUT** |
| `application/harness/` inside `:pipeline-application` | **CUT** — QA scaffolding |

So **no cut may be driven by a grep on the package name**: that would delete `events verify`'s real
dependency. Discrimination is by **module** and by **import site** (`MainEventsVerifyCli` is keep-side).
The roadmap package's own inspector agrees independently: it flags `InProcessProductProbeExecutor.kt`
as `R1_QA_IN_RUNTIME` and the two `pipeline-event-harness` tests as `KEEP_PUBLIC_FEATURE_REVIEW`.

Measured counts from `tools/test_portfolio.py` against the live checkout (255 candidates, **nothing
deleted**): `harness-main 38`, `harness-tests 51`, `tests-v2 885`.

## H0/R1 executed: QA scaffolding leaves the shipped binary

`:test-harness` was an `implementation` dependency of `:pipeline-application` — the **shipped** module.
Measured before the change:

- `src/main` referenced it from exactly one file, `InProcessProductProbeExecutor.kt`;
- that class is referenced by exactly two files, both **tests**;
- it shipped **6 class files** inside `pipeline-application-0.62.0.jar`.

Two changes:

1. `InProcessProductProbeExecutor.kt` moved `src/main` → `src/test` (same package; its two callers
   are in that package already). The `RunOutcome → ProbeOutcome` mapping keeps its adversarial
   coverage in `InProcessProductProbeExecutorTest` (5 tests) — nothing was deleted.
2. `implementation(project(":test-harness"))` → `testImplementation(...)`, with the measurement
   recorded in the build file so the next reader does not re-derive it.

### Verified on the real artefact, not in the source tree

```text
:pipeline-application:compileKotlin      --rerun-tasks  exit 0   (test-harness OFF the runtime classpath)
:pipeline-application:installDist                      exit 0
lib/ contains pipeline-event-harness-0.62.0.jar        (KEEP intact)
lib/ contains no test-harness jar                      (CUT confirmed)
no harness/{cycle,lanes,plan,retirement,catalog,util} class in any lib/*.jar
./bin/pipelinek events verify --help                  exit 0   (public feature works)
```

The leak entered at `fb97ed43` (BQ4 v2), which is **not** an ancestor of `v0.61.0`; released rc2
shipped `event-harness` and no `test-harness`. So no published candidate carried this JAR — the
defect was in unreleased builds only, and the fix is a reduction, not a recall.

**Precedent worth reusing in H0.4:** this build file already reaches `:test-harness` through
`laneReportClasspath(project(":test-harness"))` — a task-scoped configuration for the `laneReport`
JavaExec task. That is the non-`implementation` shape any remaining consumer can adopt, so H0.4 has a
proven mechanism rather than a new one.

## Two defects found while doing it

**1. A KDoc asserting a mechanism that does not exist.** The class claimed: *"The harness's
`ProbeExecutorDiscovery` uses `ServiceLoader.load(ProbeExecutorContributor)` … when the cycle runs with
`:pipeline-application` on the classpath, this executor is used; without it HF1 probes reach
`BlockedExternal`."* Measured false:

- `ProbeExecutorContributor` is a `fun interface` with **no implementation in the repository**;
- **no** `META-INF/services/…ProbeExecutorContributor` file exists;
- `resolve()` therefore takes its `0 ->` branch and returns the harness default, whose HF1 outcome is
  `BlockedExternal`.

So HF1 does not execute for real today, and the class was never discovered. The KDoc now states the
measured fact. **The contributor was deliberately not wired**: doing so would re-create the runtime
coupling `ADR-PK-DF-002` removes, and no receipt claims HF1 real-run evidence. No `cycle.json` exists
anywhere in the repository, so nothing observable is lost by the move.

**2. The guard protecting that class could not fail — and could not see itself.**
`ProbeExecutionProtocolFitnessTest` named one hard-coded directory
(`src/main/kotlin/…/application/harness`) and `error()`ed if it was absent. Moving the adapter made it
throw with no adapter left to inspect — a real guarantee reported as a hard error.

Fixing it exposed the deeper hole: the guard's own directory survives the adapter, so "a directory
existed" is not evidence there was anything to inspect. Scanning zero files asserts nothing and
**passes vacuously**. It also became self-tripping — the guard declares its forbidden patterns as
*string literals*, so comment-stripping cannot remove them and the widened scan found its own file.

Final shape: both source sets are searched; the guard excludes itself; and the result is required to
be non-empty, so the **adapter having been deleted** is what gets refused.

### Mutations

| mutation | expected | observed |
| --- | --- | --- |
| delete the whole adapter package | fail-closed | `No tests found` — **invalid RED**, the guard class went with it |
| delete only the adapter file | fail-closed | **invalid RED** — `e: Unresolved reference`, HF §6 |
| adapter excluded, guard present | fail-closed | `tests="2" failures="2" errors="0"`, `IllegalStateException: adapter sources are absent` — **valid** |

Two of three candidate mutations were not REDs at all. The one that is a RED required excluding the
adapter from the scanned set while leaving it compilable, which is precisely the vacuous-scan case.
All mutations restored byte-identical (`6b6d138d…`).

## What this slice did NOT do

- **H0.4 (delete `:test-harness`)** — blocked: needs the ADR-PK-DF-002 amendments ratified.
- **H0.2 (prune dead `@Disabled` methods)** — not started; the 17 `R0_PRUNE_DISABLED_METHODS`
  candidates are review candidates, and equivalence consent is required per test.
- No test deleted, no `@Disabled` removed, no SPEC or AGENTS.md text amended, no RC cut.

## Leads

- The `Harness2` STOP condition was not approached: this slice added no ADT, no matrix and no
  infrastructure. It removed one dependency edge.
- HF1-through-`CycleCli` is a **known gap**, not a regression, and the KDoc now says so. If HF1
  real-run is ever wanted, it needs a contributor registration — and the roadmap removes the module
  that would host it, so that is a decision for the ratified ADR, not for this slice.
- `pipeline-application` `src/main` no longer contains any `harness/` package. Whether
  `:test-harness`'s remaining consumers (the 2 test files, `laneReport`) can be relocated or dropped
  under H0.4 is now the whole of the cut.
---

# Part 2 — the guarantee, not just the incident

Closing the edge is worth nothing if the same edge reopens silently. H0/R1 was a
one-line build change; what makes it durable is that nothing permitted it in the first place.

## The blind spot that let it in

`FArch020EventHarnessIsolationTest` extracts every `project("…")` from a build file and
checks **which module** is named. It never asks **which configuration** named it — and the
configuration is the entire question:

```text
testImplementation(project(":x"))    ships nothing
implementation(project(":x"))        lands in the JAR, and in runtimeElements for consumers
```

The first is invisible to a dependency-set check; only one of them ships. That is the hole
H0/R1 lived in.

## FArch021 — a QA module must never sit on a runtime classpath

New fitness in `pipeline-architecture-tests`, guarding the guarantee rather than the incident:

- **runtime axis**: `api` / `implementation` / `runtimeOnly`
- **QA axis**: an explicit `qaModules` set, currently `{":test-harness"}` — **never a name
  pattern**, because `:pipeline-event-harness` is not a QA module and is what backs
  `pipeline events verify`
- **comments stripped**, so prose mentioning a QA module cannot trip it
- **fail-closed on an empty scan**, because "found no build file" must not read as "therefore
  nothing is on a runtime classpath"

### The second test is the one that matters

A regex that stops matching makes this fitness green over a reintroduced leak. The second
test asserts the scan is **not vacuous**: the parser must still find runtime-configuration
project dependencies *and* must still find a `:test-harness` dependency at all — otherwise
the QA filter is dead code that would pass if a runtime declaration were added tomorrow.
That is the defect class `FArchL5NoJgitTest` and `BannedImportsFitnessTest` were each
fixed for, so it is pinned here deliberately.

### Mutations

| mutation | expected | observed |
| --- | --- | --- |
| `testImplementation` → `implementation` (the exact defect) | row 1 RED | `tests="2" failures="1" errors="0"` |
| empty the `qaModules` set (filter becomes dead code) | row 2 RED | `tests="2" failures="1" errors="0"` |

**A restore silently failed and the hash check caught it.** `FArch021` is a new, untracked
file, so `git checkout -- <file>` had nothing to restore and left the M2 mutation in place
while reporting success. Restoring by hash, not by VCS state, is the only reason the
committed bytes are the intended ones.

## Module gate is RED for pre-existing reasons — with base evidence

`:pipeline-architecture-tests:test --rerun` over the whole module:

```text
tests=552 skipped=10 failures=4 errors=0   (111 result classes)

FArchM2CanonicalPipelineCompilerTest      failures=1
FArchM3CanonicalTaskRuntimeTest           failures=1
FArchRP040DisabledTestClassificationTest  failures=1
FArchS1DirectiveKernelFitnessTest         failures=1
FArch021QaModulesStayOffRuntimeClasspathTest  tests=2 skipped=0 failures=0   <- not mine
```

Classified by **re-running the four with `FArch021` removed from the tree**: all four still
fail. Pre-existing at `HEAD`, not introduced here, and not claimed otherwise.

`FArchRP040DisabledTestClassificationTest` is one of the 16 entries the `@Disabled`
inventory generator deliberately refuses to classify (it is itself disabled and its reason
is the bare string `"regression"`). It is therefore a live lead for H0.2, not noise.

## Part 3 — closing 3 of the 4 pre-existing REDs, and the fourth that stays

Base for this part: `main@380dd63b`. Round gate = `:pipeline-architecture-tests:test --rerun`
(budget 600 s; observed 262 s).

```text
before   classes=111 tests=552 skipped=10 failures=4 errors=0
after    classes=112 tests=556 skipped=10 failures=1 errors=0
```

All three were **test defects**: a guard whose predicate does not express the property it
claims. None of them required a product change, and none was silenced.

### FArchRP040 — a signal discarded before it was tested

The regex `^\s*@Disabled(\([^)]*\))?` only participates when a closing paren is on the SAME
line. A reason built by concatenation therefore collapsed the match to bare `@Disabled`,
**throwing away the `(`** that is the only evidence the annotation continues — so
`annotation.endsWith("@Disabled(")` was unreachable code and every concatenated reason was
reported as reasonless.

Fix: decide multi-line from the raw line, then accumulate. Paren *counting* is deliberately
not used: a reason may itself contain parentheses (`"Falsifier (x)"`), which would unbalance
the count.

- mutation: read the argument from the truncated `match.groupValues[0]` → `failures="1"`
- check B: inject a real bare `@Disabled` into `RuntimeBuildIdentityTest.kt` → `failures="1"`,
  proving the fix did not make the fitness *permissive*

### FArchS1 — the negative row was not negative

`a production reference to the example plugin is detected` reimplemented the scan inline
over a `@TempDir`, so it exercised **its own copy** of the predicates. It passed whether or
not `isTestSourceSet` and `codeOnly` worked — which is how both were found defective in the
first place.

Fix: extract `pluginLeakOffenders(root: Path)`; the happy path and the fixture now share one
implementation. Two rows added so the discriminator is pinned from both sides:

- `a COMMENTED reference to the example plugin is not a leak`
- `every TEST source set is sanctioned` (covers `src/test` **and** `src/pluginContractTest`)

| mutation | expected | observed |
| --- | --- | --- |
| A: `endsWith("test", ignoreCase = true)` → `endsWith("Test")` | new row RED | `every TEST source set is sanctioned` RED + happy path RED naming the four real `src/test` files — 1:1 |
| B: `codeOnly` → identity | new row RED | `a COMMENTED reference…` RED naming **`pipeline-release/build.gradle.kts`**, i.e. it reproduces the original incident exactly |
| C: `filterNot(::sanctioned)` → `filterNot { false }` | new rows RED | **both** new rows RED — and the pre-existing detection row stayed **GREEN** |

Mutation C is the finding worth keeping: **a green row that survives a mutation is
vacuous.** The row that existed all along would have passed over a dead filter. It now has
company.

### FArchM2 — a name collision read as a second authority

The allowlist failed on `Stage`. Measured across `v2/**/src/main/kotlin`, every canonical
symbol has **exactly one top-level declaration, in exactly the allowlisted file**; the extra
hit was `MainInspectCli.InspectField.Stage`, a nested projection field of the `--fields`
column set, in `…application`, with no relation to `domain.Stage`.

The pattern was `(?m)^\s*`, so it matched a declaration at **any nesting depth** and named it
after its bare token. Two different types in two packages, one nested, is not a second source
of truth.

Fix: `(?m)^` — top level only. Column 0 is not a hope: `detekt`'s `Indentation` rule is
active (absent from `config/detekt/detekt.yml`, so the default applies) and `detekt` is wired
into `check`, so an indented top-level declaration cannot reach this scanner unflagged by
another authority. Two rows added, `DeclarationScannerFixture`:

| mutation | expected | observed |
| --- | --- | --- |
| D: restore `^\s*` | nested row RED | `a NESTED declaration…` RED + allowlist RED |
| E: keyword group → `enumeration\|protocol` (scanner matches nothing) | second row RED | `a SECOND top-level declaration…` RED + allowlist RED |

**One candidate mutation was invalid and said so.** The first attempt at E renamed only the
`typealias` alternative, leaving `class|interface|object` intact — the run was GREEN, which
read as "the row is unfalsifiable" and was actually "the mutation did nothing". The lesson is
the one HARNESS FIDELITY §5 states: a mutation must be *verified to have applied* before its
GREEN counts as information.

All mutations restored byte-identical and hash-verified (`14d34ba3…`, `b8b8cf1e…`); the
earlier session's `git checkout --` restore destroyed tracked-but-uncommitted work and a
byte-copy backup with a sha256 check is now the mechanism.

### The one that stays RED: FArchM3

`ProcessBuilder` must appear only inside the runtime module. Offenders, all four, are in the
module this roadmap cuts:

```text
test-harness/src/main/.../plan/PlanCli.kt:42
test-harness/src/main/.../plan/Gate.kt:173
test-harness/src/main/.../plan/GateCli.kt:134
test-harness/src/main/.../plan/InstalledDistribution.kt:175
```

The fitness is **correct** and the RED is honest. The fix is deleting the module — H0.4, which
needs `ADR-PK-DF-002` ratified with its amendments. Not silenced, not weakened, not
reclassified. Its own comment already carries the instruction to cut rather than to fix.

## Part 4 — H0.2 measured instead of executed: the classifier was authorising the wrong deletion

**Nothing was pruned.** ADR-PK-DF-002 H0.2 proposes "eliminar métodos históricos `@Disabled`,
conservar vivos" over the population `test_portfolio.py` labels `R0_PRUNE_DISABLED_METHODS`
(17 files). Before deleting anything, the assumption behind that label was measured.

New read-only tool: `tools/disabled_equivalence_audit.py` (ships with the roadmap package).
For every `@Disabled` that says *"superseded by X"*, it resolves X against every declaration
in the tree and reports whether X is **live**, **itself disabled** (a chain inside the same
archive), or **absent**.

```text
@Disabled audited in the R0 population: 60
  declaring an explicit preservation-for-traceability intent: 54

successor status       declares traceability   count
  disabled-successor   True                    22
  live                 True                     4
  missing-successor    True                    25
  no-successor         False                    6
  no-successor         True                     3
```

### The label was wrong, and wrong in the dangerous direction

The classifier keys `R0_PRUNE_DISABLED_METHODS` on the words `historical|superseded|snapshot`
inside the reason. Those words are **exactly the vocabulary of a deliberate archive** — 54 of
these 60 reasons end *"Preserved verbatim for traceability."* So the tool was proposing, with
the authority of a measurement, to delete 54 records their authors deliberately kept, on the
strength of the same sentence that says why they are kept.

And the equivalence the prune would have relied on is not there either: of 51 reasons that
name a successor, **4 name a live one**. 22 name a member of the same archive (also disabled),
25 name nothing that exists. So "equivalence consent per test" cannot be granted for 47 of
them — the claim is unverifiable, not merely unconsented.

### What changed, and what deliberately did not

`test_portfolio.py` now separates *archive* from *prunable*, and — the part that matters —
`R0_PRUNE_DISABLED_METHODS` moved to the **end** of the chain as a fallback:

| movement | count | direction |
| --- | --- | --- |
| `R0_PRUNE` → `R0_ARCHIVED_TRACEABILITY` | 16 | stronger: explicitly "NO podar" |
| `R2_DISABLED_CLASS_REVIEW` / `R2_UAT_E2E_REVIEW` → `R2_CRITICAL_OR_PENDING` | 7 | stronger |
| `R3_FITNESS_REVIEW` → itself (a fitness, back under its own branch) | 1 | restored |
| **newly authorised for deletion** | **0** | — |

`R0_PRUNE_DISABLED_METHODS` population: **17 → 1**.

The single survivor is `CoreErrorStepG2RegistryAdmissionTest`, a whole class whose reason says
*"Archived G2 evidence: superseded by …"* — it declares no traceability intent, so it is a
genuine prune candidate, and it is left for a per-test decision rather than taken now.

### Two ordering defects found while doing it

**1. `[^)]*` cannot cross a `)`.** Widening the search window was required because these
reasons contain `(post-S2-A6-G4)` **before** the phrase being matched, so the window closed
first. This is the *same defect* fixed in `FArchRP040` earlier this session, in a different
language: a parser that stops at the first closer throws away the signal it needs. Both the
Kotlin fix and this one accumulate to a bounded window rather than trusting a delimiter.

**2. A broader predicate silently removed a protection.** Widening `historical` made
`RetryAcceptanceMatrixTest` match, and because `historical` sat *above* the critical branch,
a class carrying `@Disabled("migrated-pending")` was reclassified from *"NO borrar sin prueba
equivalente"* to *"eliminar métodos históricos"*. Same for two secret-redaction UATs, in the
opposite direction. Signal strength now decides the order — `migrated-pending` >
traceability > historical — and `historical` can no longer shadow a fitness or a UAT
classification.

Both were caught by an explicit invariant rather than by reading the diff:

```text
comm -13 <baseline R0_PRUNE> <final R0_PRUNE>   # must be empty
```

It was **not** empty on the first two attempts. A change that widens a predicate needs a
measured safety bound, not a re-read.

### Classification

```text
H0.2  NOT_RUN — bloqueado por equivalencia, no por permiso
```

The blocker is not consent, it is evidence: 47 of 51 equivalence claims do not resolve to a
live test. Deleting under those conditions would destroy the burn-down traceability record
that `ROADMAP.md` §G0..G8 depends on, in exchange for nothing that runs either way. If the
archive is meant to live in the ledger rather than in code, that is a **decision to record**,
not a deletion to perform.

### What this slice did NOT do

- No `@Disabled` removed, no test deleted, no assertion weakened.
- `test_portfolio.py` and `disabled_equivalence_audit.py` change the *classification* and make
  the equivalence *measurable*; neither authorises a deletion and neither deletes.
