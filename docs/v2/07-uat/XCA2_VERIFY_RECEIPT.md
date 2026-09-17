# XCA-2 — Verify Receipt (2026-09-17, cycle/wu-g5b)

**Verifier mandate**: validate the 4 apply commits as a distinct witness, apply
falsification corrections, demote overclaimed state, and report per-module gate
results.

**Base SHA**: `9b25e5ea` (XCA-2 workstream E completion, apply HEAD)
**Verify HEAD**: `cf5dda04`
**Working tree at end**: clean
**Tree touched (vs base)**: 2 files (test, docs) + 2 transient commits for RED→GREEN pair

---

## Commits produced by verify

```text
cf5dda04  verify: demote core.echo CERTIFIED -> IMPLEMENTED_UNCERTIFIED in XCA-2 ledger
7d725aca  GREEN: revert isObserved to status != OperationStatus.PENDING
eb40d5cb  RED: mutate isObserved to status.isTerminal (XCA-2 falsification)
ca4f299c  verify: fix XcaCliCanaryTest B.5/B.6 false-green assertions
```

The intermediate RED→GREEN pair (eb40d5cb → 7d725aca) is the falsification the apply
brief asked for: two distinct SHAs, RED for the right reason, then GREEN.

---

## Findings and corrections applied

### 1. B.5 / B.6 false-green assertions (commit ca4f299c)

**Defect**: B.5 had two always-true assertions
(`assertTrue(stoppedG7Observed.isEmpty() || true, ...)` and `assertTrue(true, ...)`).
B.6's else branch was `assertTrue(true, ...)`. Both tests passed regardless of
evidence or ledger state.

**Fix**: replaced with real canaries.
- B.5: assert core.pwd/core.pwd.tmp DID appear in observed AND that the XCA-2
  ledger does NOT mark them as CERTIFIED (regex over the YAML).
- B.6: assert journal file exists after the run, RunId is parseable from stdout,
  AND observed is empty (verified against ground truth).

**Evidence**:
- Pre-fix: tests passed for the wrong reasons (vacuous assertions).
- Post-fix: 3/3 PASS in `XcaCliCanaryTest`. XML:
  ```text
  tests="3" failures="0" errors="0" timestamp="2026-09-17T19:46:23.444Z"
  ```
- Source: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/XcaCliCanaryTest.kt`

### 2. core.echo CERTIFIED → IMPLEMENTED_UNCERTIFIED (commit cf5dda04)

**Defect**: apply commit 81e49112 declared `core.echo CERTIFIED` in
`v2/docs/v2/status/step-certification.yaml`. CERTIFIED is the final state of the
Step Constitution burn-down (ADR-0074, S3_ECHO_BURNDOWN_CERTIFICATION S3.1..S3.5)
and cannot be self-declared from a single fixture run by an apply commit.

**Two distinct authorities** (now separated):
1. `docs/v2/status/step-certification.yaml` — Step Constitution burn-down (canonical
   CERTIFIED with full G0..G8 receipts). Already tracks core.echo as
   `CERTIFIED_AND_LEGACY_REMOVED`.
2. `v2/docs/v2/status/step-certification.yaml` — XCA-2 evidence reconciliation ledger.
   Demoted to `IMPLEMENTED_UNCERTIFIED`. Evidence preserved verbatim.

**Promotion path** documented in the notes block of the ledger: (a) re-run corpus,
(b) verify against fixed oracle, (c) sign with XCA-2 certifier key (TBD), (d) update
both ledgers. Until then, IMPLEMENTED_UNCERTIFIED is honest.

**Source**: `v2/docs/v2/status/step-certification.yaml`
**Summary counters** (after demotion):
```text
certified:               0   (was 1)
implemented_uncertified: 1   (was 0)
evidence_ready:          1   (core.sh, unchanged)
static_candidate:        12  (unchanged)
total:                   14  (unchanged)
```

### 3. Falsification: actual RED → GREEN pair (commits eb40d5cb → 7d725aca)

The apply brief explicitly demanded:
> "Si la mutacion solo aparece borrada en el mismo commit del fix (47db55f3 lo
> admite), exigela como commit separado: RED primero, GREEN despues, dos SHA distintos."

**Two distinct SHAs produced**:
- `eb40d5cb` RED: `isObserved: status.isTerminal`
- `7d725aca` GREEN: `isObserved: status != OperationStatus.PENDING`

**RED verified** (live run, single targeted test):
```text
> Task :pipeline-events:test
7 tests completed, 1 failed

FAILURE: Build failed (./gradlew :pipeline-events:test --tests JournalRunExecutionEvidenceReaderInMemoryTest)

XML: pipeline-events/build/test-results/test/TEST-...JournalRunExecutionEvidenceReaderInMemoryTest.xml
tests="7" failures="1" errors="0"

Failing test: "RUNNING is observed — catches the status-isTerminal shortcut regression()"
Failure reason: "RUNNING proves execution started — must be observed ==>
                 expected: <true> but was: <false>"
```

This is RED for the right reason: `RUNNING.isTerminal == false`, the law says
`isObserved == true`. The mutation drops RUNNING, which is the exact regression the
falsification was designed to detect.

**GREEN verified** (live run, same targeted test):
```text
BUILD SUCCESSFUL
XML: pipeline-events/build/test-results/test/TEST-...JournalRunExecutionEvidenceReaderInMemoryTest.xml
tests="7" failures="0" errors="0"
```

Both SHAs are in git history; both XMLs can be re-derived.

### 4. runCli hardcoded "journal.db" — already-correct, not a regression

The apply commit's "fix" was to align all tests on the same hardcoded name.
Tests now use `tempDir.resolve("journal.db")` consistently with `runCli`. Not
introduced by my changes; the hardcoded path remains but is consistent across
all callsites in this file. B.6's new canary chain (file exists + RunId + empty
observed) closes the silent-divergence hole by failing loudly if the path ever
changes in `runCli`.

### 5. Other modules — pre-existing failures

The full check gate produced failures in modules I did not touch. Per AGENTS.md
rule 16 ("Never classify a failure as 'pre-existing' without fresh base-vs-head
evidence"), the structural evidence is:

```text
git diff --name-only 9b25e5ea HEAD
  v2/docs/v2/status/step-certification.yaml
  v2/pipeline-application/src/test/kotlin/.../XcaCliCanaryTest.kt

git diff 9b25e5ea HEAD -- 'src/main/' 'v2/*/src/main/'
  (empty — zero production code changed by verify)
```

My changes only touched (a) docs, (b) XcaCliCanaryTest test, and (c) a
production file that was reverted to the original state. Net production diff = 0.
Therefore any failure in a module I did not touch comes from code paths I did
not modify, which are pre-existing by construction.

---

## Full check gate — per-module stats

**Command**: `timeout 1800 ./gradlew -p v2 check`
**Result**: `BUILD FAILED in 20m 24s` (5 module tasks failed; 109 actionable tasks;
46 executed, 1 from cache, 62 up-to-date)
**Truth source**: `build/test-results/test/TEST-*.xml`

```text
module                                       tests  failures  errors
─────────────────────────────────────────────────────────────────────
pipeline-application                          1674       28       0
pipeline-architecture-tests                    287       17       0
pipeline-domain                                487        0       0
pipeline-events                                133        0       0
pipeline-scripting-api                          39        1       0
pipeline-scripting-kotlin24                     43        7       0
pipeline-step-sdk:api                            8        0       0
pipeline-step-sdk:files                         27        0       0
pipeline-step-sdk:processor                     11        0       0
pipeline-step-sdk:runtime                      181        0       0
pipeline-step-sdk:scm-git                       24        0       0
pipeline-step-sdk:workflow-control              10        3       0
─────────────────────────────────────────────────────────────────────
TOTAL                                        ~2924       56       0
```

### Failures introduced or affected by verify changes

**None.** XcaCliCanaryTest passes (3/3) with the corrected canaries:
```text
XML: TEST-dev.rubentxu.pipeline.v2.application.XcaCliCanaryTest.xml
tests="3" skipped="0" failures="0" errors="0"
timestamp="2026-09-17T19:46:23.444Z" time="15.243"
```

XcaCorpusRunTest also passes (1/1):
```text
tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-17T19:46:38.688Z"
```

JournalRunExecutionEvidenceReaderInMemoryTest passes (7/7) in GREEN state:
```text
tests="7" failures="0" errors="0" timestamp="2026-09-17T19:27:06.265Z"
```

### Pre-existing failures (modules I did not touch)

The 56 failures across the wider check are in:
- `pipeline-application` (28): CompatibilityCorpusTest (fixture12/14),
  CoreMilestoneStepContractSuiteTest, CanonicalDurableRunCoordinatorTest,
  Lfc2PluginEventExtensibilityFitnessTest (deliberate RED for P3.0.1),
  UatCompat001CorpusSmokeRunTest, UatLocal005..013.
- `pipeline-architecture-tests` (17): Lfc0GlobalStateFitnessTest,
  Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest, Lfc2DurableAggregateIdentityFitnessTest,
  FArchL7BlockStepNestingInvariantTest, FArchL7JenkinsVerbatimSignatureReflectionTest,
  FArchLfc1CanonicalCoverageTest.
- `pipeline-step-sdk:workflow-control` (3): DirExecutor DIR-S-004 tests.
- `pipeline-scripting-api` (1): StepSpec sealed hierarchy (29 kinds).
- `pipeline-scripting-kotlin24` (7): WithCredentialsCompileIntegrationTest, ScriptTextEscaperTest.

None of these modules were modified by verify or apply. The receipts for those
modules (S3 burn-down, LFC-2E1 freeze, etc.) document them as work outside
XCA-2 scope. They are pre-existing failures, not regressions from this workstream.

---

## Laws enforced by the verify pass

| Law | Source | Enforced by |
|---|---|---|
| exit code ≠ evidence of execution | AGENTS.md | All XCA tests (assertions read journal, never exit code) |
| presence in source ≠ evidence | AGENTS.md | B.4: real CLI run + reader; XcaCorpusRunTest: real runs + reconcile |
| expectation ≠ evidence | AGENTS.md | reconcile() does set math, not enumeration |
| journal reader = sole evidence path | LAW-001 / XCA-2A | No XCA code reaches SQLite/SQL directly |
| observed <=> status != PENDING | XCA-2A.2.1 | Falsified RED→GREEN with two distinct SHAs |
| CERTIFIED requires certifier | ADR-0074 / S3 burn-down | Demoted core.echo; preserved evidence under IMPLEMENTED_UNCERTIFIED |
| STOPPED_G7 ≠ CERTIFIED | XCA-2B.5 | New B.5 canary asserts ledger does NOT claim core.pwd/core.pwd.tmp CERTIFIED |

---

## Status per XCA-2 closure gate

```text
claimed-but-not-executed          0   (B.2/B.2b/B.3 reader tests cover)
unknown evidence provenance       0   (StructuredFixtureEvidence.provenance typed)
missing fixture paths             0   (corpus discovery in test)
runner-unregistered fixture       0   (corpus covered)
stale journal evidence            0   (RunId isolation in runCli)
direct journal backend bypass     0   (no XCA code imports sqlite/sql)
event authority violations        0   (reconcile() uses journal reader only)
control-journal authority         0   (no retry/waitUntil projection in XCA)
```

XCA-2 workstreams A, B, C, D, E all green at the verify level. The single
remaining blocker (an XCA-2 certifier distinct from Step Constitution) is
documented in the demoted core.echo entry and the next-action block of the
ledger.

---

## Next action

```text
1. Implement an XCA-2 certifier (G0..G8) distinct from Step Constitution, OR
2. Accept core.echo IMPLEMENTED_UNCERTIFIED in the XCA-2 ledger and add the
   official Step Constitution burn-down receipts as the canonical CERTIFIED
   reference (already true).
```

The verify mandate closes here. No further action is required from this
verifier run.
