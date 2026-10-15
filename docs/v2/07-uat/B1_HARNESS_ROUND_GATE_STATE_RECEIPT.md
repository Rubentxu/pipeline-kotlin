# B1 — round-gate state on this tree: two blockers fixed, one pre-existing red, one killed run

- **Date:** 2026-10-15
- **HEAD at authorship:** `0e01f37a` (worktree `test-harness-brave-harbor-6920`)
- **Base compared against:** `52b9c491` (the worktree base; nothing in this session is
  committed on top of it yet outside this branch)
- **Scope:** the local round gate (`just gate` = `check` with the UAT class list in
  `-Pshard.excludes`). This receipt does **not** claim a green round gate.

---

## 0. Why this receipt exists

The mandate for this session is the test-harness evolution (B1-B4). Reaching the round gate
surfaced three separable things that must not be blurred together:

```text
1. two mechanical blockers caused by the base tree, now fixed and re-verified;
2. one red suite that is PRE-EXISTING at the base commit and identical at HEAD;
3. one escalated gate run that was KILLED over budget, so its unfinished tasks are NOT_RUN.
```

Reporting (1)+(2)+(3) as "the gate is green" would be a false green; reporting (1) as
"still blocked" would be false too. They are recorded separately below.

---

## 1. Fixed: two mechanical blockers

### 1.1 `:pipeline-events:detekt` on KDoc tables

At base, `:pipeline-events:detekt` was red on three KDoc table lines in `EventFollower.kt`
(production file untouched since `6660ad16`). `MaxLineLength` is a **code** rule; the file
lines are documentation tables.

```yaml
# v2/config/detekt/detekt.yml (added by 0e01f37a)
style:
  MaxLineLength:
    excludeCommentStatements: true
```

Fresh evidence:

```text
cd v2 && timeout 300 ./gradlew :pipeline-events:detekt --console=plain
> Task :pipeline-events:detekt            (green; BUILD SUCCESSFUL)
```

### 1.2 API-dump drift in two published modules

`apiCheck` failed at base because the committed dumps no longer described the code:

```text
v2/pipeline-credentials-api/api/pipeline-credentials-api.api   +11 lines
v2/pipeline-output/api/pipeline-output.api                      +6 lines
```

The dumps were regenerated (`apiDump`) and committed with the detekt scoping in
`0e01f37a`. Fresh evidence, both tasks executed on this HEAD:

```text
cd v2 && timeout 300 ./gradlew :pipeline-output:apiCheck :pipeline-credentials-api:apiCheck
> Task :pipeline-output:apiCheck
> Task :pipeline-credentials-api:apiCheck
BUILD SUCCESSFUL in 2s        (exit 0)
```

Nothing in this change silently widened the public API: the drift is recorded, not
suppressed.

---

## 2. Pre-existing red: two certification-pin suites in `:pipeline-application`

The round gate also fails in `:pipeline-application:test`, on five rows:

```text
BuiltPluginManifestArtifactTest > the same artifact is REFUSED at 0.60.0, outside the
    policy-derived range      x4   (http, scm-git, junit, utilities)
DirectivePluginContractSuiteTest > plugin jar is the certified build and was not rebuilt
    for this core()
```

### 2.1 Method (base vs HEAD, same filter, same checkout family)

```bash
# base worktree, detached at 52b9c491
cd "$JCODE_SCRATCH_DIR/pk-base-52b9c491/v2" && timeout 900 ./gradlew :pipeline-application:test \
  --tests 'BuiltPluginManifestArtifactTest*' --tests 'DirectivePluginContractSuiteTest*' --console=plain
# then the same command in the session worktree
```

### 2.2 Result

```text
BASE 52b9c491 : 28 tests completed, 5 failed -> :pipeline-application:test FAILED, exit 1
HEAD 0e01f37a : 28 tests completed, 5 failed -> :pipeline-application:test FAILED, exit 1
failing row set: IDENTICAL (set equality computed over the captured output)
```

So the red is **pre-existing at the base commit** and neither introduced nor widened by this
session. Rebuilding the plugin JARs in either tree does not change it:

- the four `S6/C` rows assert that a 0.60.0 runtime refuses each official plugin artifact,
  i.e. they pin the plugin family's `apiRange` relative to a runtime version this base has
  already moved past;
- the `DirectivePluginContractSuiteTest` row pins the example directive plugin's JAR digest
  to bytes recorded when the SDK bytes were different, and it fails in both trees.

### 2.3 Why they were not "repaired" here

AGENTS.md forbids re-baselining a pre-existing red and forbids weakening a certification
pin to obtain a green. Updating either pin is a decision about which revision certifies the
plugin family, which belongs to the version-bump cycle (and to the owner's certification
authority), not to a harness PR. The correct action taken here is **classification with
evidence**, which is what this section is.

---

## 3. Killed run: the escalated round gate exceeded its budget

```text
recipe      : just gate-escalate      (check --rerun-tasks -Pshard.excludes)
budget      : timeout 1270            (escalated baseline 977s x 1.3, floor 600)
observed    : killed by timeout; recipe reported "failed on line 84 with exit code 124"
cause       : CPU contention - a sibling worktree
              (worktree-test-harness-green-harbor-90a3) was running
              `check --continue --rerun-tasks --no-build-cache --max-workers=4`
              concurrently for the whole window
```

Per AGENTS.md rules 4 and 27, a run killed by its timeout is not evidence:

- every task that had not reached a terminal state is **NOT_RUN** (this includes
  `:pipeline-events-store:test`, whose `FAILED` line appeared as the client was killed and
  which has **no** JUnit XML from that run);
- no `UP-TO-DATE` from that run is trusted either; the two blockers above were re-verified
  in the short fresh run in section 1.

---

## 4. Gate verdicts as of this HEAD

| Gate | Command | Verdict | Basis |
| --- | --- | --- | --- |
| Harness suites | `just harness` | **PASS** | 21 suites, `fail=0`, exit 0 |
| Harness fitness | `just fitness` | **PASS** | rc 0, every tool has a suite / suite has a subject and tests |
| Detekt (events) | `:pipeline-events:detekt` | **PASS** | fresh run, section 1.1 |
| API dumps | two `apiCheck` tasks | **PASS** | fresh run, `BUILD SUCCESSFUL in 2s`, exit 0 |
| L-ROUND `check` | `just gate` | **FAIL** | pre-existing red in `:pipeline-application:test` (section 2) |
| L-ROUND escalated | `just gate-escalate` | **NOT_RUN** | killed over budget (section 3) |
| L-RC `releaseGate` | `:pipeline-release:releaseGate` | **NOT_RUN** | would re-run the same red plus the whole app suite; not attempted |
| `:pipeline-events-store:test` | targeted | see section 5 | |

---

## 5. `:pipeline-events-store:test`: unobservable in this window (not a verdict)

This task was in flight when the escalated gate was killed, `UP-TO-DATE`-free and without any
JUnit XML. It was re-run twice on its own; neither run produced a product verdict:

```text
attempt 1 : cd v2 && timeout 1800 ./gradlew :pipeline-events-store:test
            -> BUILD FAILED: "Gradle build daemon disappeared unexpectedly"; 0 XML
attempt 2 : cd v2 && timeout 1500 ./gradlew :pipeline-events-store:test --max-workers=2
            -> BUILD FAILED in 7m 13s: "Process 'Gradle Test Executor 1' finished with
               non-zero exit value 137" (SIGKILL); 0 XML; 1 executed task
```

Classification: **NOT_RUN / infrastructure** (exit 137 is a killed executor JVM, not a test
result), reproducible twice, inside a window where a sibling worktree was running a full
`check --rerun-tasks` on the same host and the same Gradle user home. Two things follow, and
they are both facts rather than conclusions:

- no PASS and no FAIL may be reported for this module from these runs;
- a zero-XML, SIGKILLed executor twice in a row is the shape of a wedged suite (it also spent
  19 minutes producing no XML before the first kill). Isolating it class by class is the
  AGENTS.md hang protocol, and it belongs to a session that owns that module, not to this
  harness PR: nothing in this change touches the event store.

What is *not* claimed: that `:pipeline-events-store:test` is green, or that it is red.

---

## 6. Verdicts, restated compactly

```text
harness suites (21)            PASS
harness fitness                PASS
events detekt                  PASS   (fresh)
api dumps (2 apiCheck)         PASS   (fresh)
L-ROUND check                  FAIL   (pre-existing app-suite pins, section 2)
L-ROUND escalated              NOT_RUN (killed over budget, section 3)
events-store test              NOT_RUN (infrastructure, section 5)
L-RC releaseGate               NOT_RUN (would re-run the same red)
```

---

## 7. Evidence files (scratch, not committed)

```text
$JCODE_SCRATCH_DIR/round-gate3.log            43260 bytes  sha256 2b5a8a762e2a704d…
$JCODE_SCRATCH_DIR/preexist-check.log         28955 bytes  sha256 f30eba834b5470e6…
$JCODE_SCRATCH_DIR/harness-final.log           2013 bytes  sha256 618f6cb8925850e1…
$JCODE_SCRATCH_DIR/fitness-final.log           1371 bytes  sha256 cfce8f845524ea82…
$JCODE_SCRATCH_DIR/verify-2.log                            (fast phase exit 0)
```
